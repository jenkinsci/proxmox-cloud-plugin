package org.jenkinsci.plugins.proxmox;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import hudson.Extension;
import hudson.model.Describable;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.model.labels.LabelAtom;
import hudson.security.ACL;
import hudson.util.ComboBoxModel;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.proxmox.api.ProxmoxClient;
import org.jenkinsci.plugins.proxmox.api.ProxmoxException;
import org.jenkinsci.plugins.proxmox.api.model.CloneOptions;
import org.jenkinsci.plugins.proxmox.api.model.ClusterNode;
import org.jenkinsci.plugins.proxmox.api.model.NetworkDevice;
import org.jenkinsci.plugins.proxmox.api.model.ResourcePool;
import org.jenkinsci.plugins.proxmox.api.model.StoragePool;
import org.jenkinsci.plugins.proxmox.api.model.VirtualMachine;
import org.jenkinsci.plugins.proxmox.api.model.VmConfig;
import org.jenkinsci.plugins.proxmox.config.CloneStrategy;
import org.jenkinsci.plugins.proxmox.config.JavaDistribution;
import org.jenkinsci.plugins.proxmox.config.OsType;
import org.jenkinsci.plugins.proxmox.config.WindowsLoginShell;
import org.jenkinsci.plugins.proxmox.config.ProxmoxTokenCredentials;
import org.jenkinsci.plugins.proxmox.config.TemplateSelectionMode;
import org.jenkinsci.plugins.proxmox.config.TemplateLocation;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import hudson.RelativePath;
import org.kohsuke.stapler.verb.POST;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Collectors;

public class ProxmoxTemplate implements Describable<ProxmoxTemplate> {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxTemplate.class.getName());

    private final String name;
    private final String node;
    private final int templateVmId;
    private final String labelString;
    private final int numExecutors;

    private TemplateSelectionMode templateSelectionMode = TemplateSelectionMode.STATIC_ID;
    private TemplateLocation templateLocation = TemplateLocation.FIXED_NODE;
    private List<String> targetNodes;
    private String templateNameRegex;
    private String templateTag;
    private OsType osType = OsType.LINUX;
    private CloneStrategy cloneStrategy = CloneStrategy.FULL;
    private String targetStorage;
    private String targetPool;
    private int cores;
    private int memory;
    private int diskSizeGb;
    private String networkBridge;
    private String remoteFs;
    private Node.Mode mode = Node.Mode.EXCLUSIVE;
    private String credentialsId;
    private String javaPath = "java";
    private String jvmOptions;
    private JavaDistribution javaDistribution = JavaDistribution.NONE;
    private int javaMajorVersion = JavaDistribution.RECOMMENDED_MIN_MAJOR_VERSION;
    private WindowsLoginShell windowsLoginShell = WindowsLoginShell.AUTO;
    private int idleTerminationMinutes = 30;
    private int instanceCap;
    private int instanceMin;
    private int maxTotalUses;
    private String namePrefix = "jenkins-agent-";
    private int startupWaitSeconds = 60;

    private String ciUser;
    private String ipConfig;
    private String nameserver;
    private String searchDomain;

    private transient Set<LabelAtom> labelSet;

    @DataBoundConstructor
    public ProxmoxTemplate(String name, String node, int templateVmId, String labelString, int numExecutors) {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Name is required");
        this.name = name;
        this.node = node;
        this.templateVmId = templateVmId;
        this.labelString = labelString;
        this.numExecutors = numExecutors > 0 ? numExecutors : 1;
    }

    public boolean matches(Label label) {
        if (label == null) {
            return mode == Node.Mode.NORMAL;
        }
        return label.matches(getLabelSet());
    }

    record ProvisioningCandidate(String sourceNode, int sourceVmId, String targetNode) {
    }

    /** Clone, configure, and start a VM for a reserved id and placement. */
    ProxmoxAgent provision(ProxmoxCloud cloud, TaskListener listener, int newVmId,
                           ProvisioningActivity.Id activityId,
                           ProvisioningCandidate candidate) throws Exception {
        var log = listener.getLogger();
        ProxmoxClient client = cloud.getClient();

        String sourceNode = candidate.sourceNode();
        String targetNode = candidate.targetNode();
        int sourceVmId = candidate.sourceVmId();
        String vmName = namePrefix + newVmId;
        String route = sourceNode.equals(targetNode)
                ? " on " + sourceNode
                : " from " + sourceNode + " to " + targetNode;
        log.println("[Proxmox] Cloning template " + sourceVmId + route
                + " → VM " + newVmId + " (" + vmName + ")");

        CloneOptions cloneOpts = new CloneOptions(
                newVmId, vmName,
                "jenkins-managed;cloud:" + cloud.name + ";template:" + name,
                cloneStrategy == CloneStrategy.FULL,
                targetStorage, targetPool,
                sourceNode.equals(targetNode) ? null : targetNode);

        String upid = client.cloneVm(sourceNode, sourceVmId, cloneOpts);
        client.waitForTask(sourceNode, upid, cloud.getOperationTimeout());
        log.println("[Proxmox] Clone complete");

        String sshPublicKey = derivePublicKeyFromCredential(log);
        VmConfig vmConfig = buildVmConfig(sshPublicKey);
        if (vmConfig != null) {
            log.println("[Proxmox] Configuring VM " + newVmId);
            client.configureVm(targetNode, newVmId, vmConfig);
        }

        if (networkBridge != null && !networkBridge.isBlank()) {
            log.println("[Proxmox] Setting network bridge to " + networkBridge);
            client.setNetworkBridge(targetNode, newVmId, networkBridge);
        }

        if (diskSizeGb > 0) {
            log.println("[Proxmox] Resizing disk scsi0 to " + diskSizeGb + "G");
            client.resizeVmDisk(targetNode, newVmId, "scsi0", diskSizeGb);
        }

        log.println("[Proxmox] Starting VM " + newVmId);
        upid = client.startVm(targetNode, newVmId);
        client.waitForTask(targetNode, upid, cloud.getOperationTimeout());

        String staticIp = parseStaticIp(ipConfig);
        // The login shell only matters for Windows (it wraps SSHLauncher's start command); Linux
        // passes null so the launcher never wraps. AUTO is resolved by probing the agent at launch.
        WindowsLoginShell loginShell = getOsType() == OsType.WINDOWS ? getWindowsLoginShell() : null;
        ProxmoxLauncher launcher = new ProxmoxLauncher(
                credentialsId, javaPath, jvmOptions, startupWaitSeconds, staticIp,
                javaDistribution, javaMajorVersion, loginShell);

        // Use getRemoteFs() rather than the raw field: a blank Remote FS Root is stored as null
        // (see setRemoteFs), and an agent with a null remoteFS NPEs in
        // SSHLauncher.getWorkingDirectory() at launch.
        return new ProxmoxAgent(
                vmName, getRemoteFs(), numExecutors, mode, labelString,
                launcher,
                cloud.name, name, targetNode, newVmId,
                idleTerminationMinutes, maxTotalUses, activityId);
    }

    /**
     * Compatibility helper for callers that provision directly. New cloud paths reserve an explicit
     * placement so concurrent provisions can be balanced.
     */
    public ProxmoxAgent provision(ProxmoxCloud cloud, TaskListener listener, int newVmId,
                                  ProvisioningActivity.Id activityId) throws Exception {
        List<ProvisioningCandidate> candidates = resolveProvisioningCandidates(cloud.getClient(), listener.getLogger());
        if (candidates.size() != 1) {
            throw new ProxmoxException("Direct provisioning requires exactly one eligible Agent Node");
        }
        return provision(cloud, listener, newVmId, activityId, candidates.get(0));
    }

    List<ProvisioningCandidate> resolveProvisioningCandidates(ProxmoxClient client,
                                                               java.io.PrintStream log) {
        List<String> targets = getOnlineTargetNodes(client);
        if (getTemplateLocation() == TemplateLocation.FIXED_NODE) {
            if (node == null || node.isBlank()) {
                throw new ProxmoxException("Template Node is required for a fixed source template");
            }
            int sourceVmId = resolveTemplateVmId(client, node, log);
            return targets.stream()
                    .map(target -> new ProvisioningCandidate(node, sourceVmId, target))
                    .toList();
        }

        TemplateSelectionMode selectionMode = getTemplateSelectionMode();
        if (selectionMode == TemplateSelectionMode.STATIC_ID) {
            throw new ProxmoxException(
                    "Matching a local template on each Agent Node requires name-regex or tag selection");
        }
        Predicate<VirtualMachine> matcher = selectionMode == TemplateSelectionMode.NAME_REGEX
                ? TemplateResolver.nameRegexMatcher(templateNameRegex)
                : TemplateResolver.tagMatcher(templateTag);
        List<ProvisioningCandidate> candidates = new ArrayList<>();
        for (String target : targets) {
            List<VirtualMachine> templates = client.getTemplates(target);
            List<VirtualMachine> matches = templates.stream().filter(matcher).toList();
            if (matches.isEmpty()) {
                String message = "Skipping Agent Node " + target
                        + ": no local template matches " + selectionDescription();
                log.println("[Proxmox] " + message);
                LOGGER.info(message);
                continue;
            }
            VirtualMachine winner = TemplateResolver.pickNewest(client, target, matches);
            log.println("[Proxmox] Local template on " + target + " resolved to VM " + winner.vmid()
                    + (winner.name() != null ? " (" + winner.name() + ")" : ""));
            candidates.add(new ProvisioningCandidate(target, winner.vmid(), target));
        }
        if (candidates.isEmpty()) {
            throw new ProxmoxException("No selected online Agent Node has a local template matching "
                    + selectionDescription());
        }
        return candidates;
    }

    private List<String> getOnlineTargetNodes(ProxmoxClient client) {
        List<String> configured = getTargetNodes();
        if (configured.isEmpty()) {
            throw new ProxmoxException("At least one Agent Node is required");
        }
        if (configured.size() == 1) {
            return configured;
        }
        Set<String> online = client.getNodes().stream()
                .filter(clusterNode -> "online".equals(clusterNode.status()))
                .map(ClusterNode::node)
                .collect(Collectors.toSet());
        List<String> available = configured.stream().filter(online::contains).toList();
        if (available.isEmpty()) {
            throw new ProxmoxException("None of the selected Agent Nodes is online: "
                    + String.join(", ", configured));
        }
        return available;
    }

    private String selectionDescription() {
        return getTemplateSelectionMode() == TemplateSelectionMode.NAME_REGEX
                ? "name regex '" + templateNameRegex + "'"
                : "tag '" + templateTag + "'";
    }

    /**
     * The VM id to clone from. Static mode returns the configured id without touching the API;
     * dynamic modes resolve the regex/tag against the node's templates at every provision, so a
     * template rebuilt under a fresh id is picked up automatically. Throws
     * {@link org.jenkinsci.plugins.proxmox.api.ProxmoxException} when nothing matches.
     */
    int resolveTemplateVmId(ProxmoxClient client, java.io.PrintStream log) {
        return resolveTemplateVmId(client, node, log);
    }

    int resolveTemplateVmId(ProxmoxClient client, String sourceNode, java.io.PrintStream log) {
        TemplateSelectionMode selectionMode = getTemplateSelectionMode();
        if (selectionMode == TemplateSelectionMode.STATIC_ID) {
            return templateVmId;
        }
        VirtualMachine winner = TemplateResolver.resolve(
                client, sourceNode, selectionMode, templateNameRegex, templateTag);
        log.println("[Proxmox] Template selection (" + selectionMode + ") on " + sourceNode
                + " resolved to VM " + winner.vmid()
                + (winner.name() != null ? " (" + winner.name() + ")" : ""));
        return winner.vmid();
    }

    private String derivePublicKeyFromCredential(java.io.PrintStream log) {
        if (credentialsId == null || credentialsId.isBlank()) {
            return null;
        }

        StandardUsernameCredentials creds = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StandardUsernameCredentials.class, Jenkins.get(), null,
                        Collections.emptyList()),
                CredentialsMatchers.withId(credentialsId));

        if (!(creds instanceof SSHUserPrivateKey sshKey)) {
            log.println("[Proxmox] SSH credential is not a private key credential, skipping public key derivation");
            return null;
        }

        List<String> keys = sshKey.getPrivateKeys();
        if (keys.isEmpty()) {
            log.println("[Proxmox] SSH credential has no private keys");
            return null;
        }

        hudson.util.Secret passphraseSecret = sshKey.getPassphrase();
        String passphrase = passphraseSecret != null ? passphraseSecret.getPlainText() : null;

        try {
            String publicKey = SshKeyUtil.deriveOpenSshPublicKey(keys.get(0), passphrase);
            log.println("[Proxmox] Derived SSH public key from credential");
            return publicKey;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to derive public key from SSH credential", e);
            log.println("[Proxmox] WARNING: Failed to derive public key from SSH credential: " + e.getMessage());
            return null;
        }
    }

    // Package-private for unit testing.
    VmConfig buildVmConfig(String sshPublicKey) {
        boolean isLinux = getOsType() == OsType.LINUX;
        boolean hasConfig = (cores > 0) || (memory > 0)
                || (isLinux && (ciUser != null || sshPublicKey != null
                        || ipConfig != null || nameserver != null || searchDomain != null));

        if (!hasConfig) {
            return null;
        }

        return new VmConfig(
                cores > 0 ? cores : null,
                memory > 0 ? memory : null,
                isLinux ? ciUser : null,
                isLinux ? sshPublicKey : null,
                isLinux ? ipConfig : null,
                isLinux ? nameserver : null,
                isLinux ? searchDomain : null);
    }

    // Package-private for unit testing.
    String parseStaticIp(String ipConfig) {
        if (ipConfig == null || ipConfig.isBlank()) return null;
        if (ipConfig.contains("dhcp")) return null;
        int ipStart = ipConfig.indexOf("ip=");
        if (ipStart < 0) return null;
        String after = ipConfig.substring(ipStart + 3);
        int comma = after.indexOf(',');
        String ipCidr = comma >= 0 ? after.substring(0, comma) : after;
        int slash = ipCidr.indexOf('/');
        return slash >= 0 ? ipCidr.substring(0, slash) : ipCidr;
    }

    /**
     * Functional agents from this template, for per-template instance-cap accounting. Offline-dead
     * nodes are excluded (mirrors {@link ProxmoxCloud#getRunningAgentCount()}) so a dead node cannot
     * hold a cap slot and block a working replacement (issues #16, #17).
     */
    public int getNumActiveAgents(ProxmoxCloud cloud) {
        return getActiveAgentCountsByNode(cloud).values().stream().mapToInt(Integer::intValue).sum();
    }

    Map<String, Integer> getActiveAgentCountsByNode(ProxmoxCloud cloud) {
        Jenkins jenkins = Jenkins.get();
        long now = System.currentTimeMillis();
        long graceMs = (long) cloud.getOrphanCleanupGracePeriodSeconds() * 1000;
        Map<String, Integer> counts = new HashMap<>();
        for (var node : jenkins.getNodes()) {
            if (node instanceof ProxmoxAgent agent
                    && cloud.name.equals(agent.getCloudName())
                    && name.equals(agent.getTemplateName())
                    && !agent.isOfflineDead(now, graceMs)) {
                counts.merge(agent.getProxmoxNode(), 1, Integer::sum);
            }
        }
        return counts;
    }

    public Set<LabelAtom> getLabelSet() {
        if (labelSet == null) {
            labelSet = Label.parse(labelString);
        }
        return labelSet;
    }

    // Getters
    public String getName() { return name; }
    public String getNode() { return node; }
    public TemplateLocation getTemplateLocation() {
        return templateLocation != null ? templateLocation : TemplateLocation.FIXED_NODE;
    }
    public List<String> getTargetNodes() {
        if (targetNodes != null && !targetNodes.isEmpty()) {
            return List.copyOf(targetNodes);
        }
        if (getTemplateLocation() == TemplateLocation.FIXED_NODE
                && node != null && !node.isBlank()) {
            return List.of(node);
        }
        return List.of();
    }
    public List<String> getRawTargetNodes() {
        return targetNodes != null ? List.copyOf(targetNodes) : null;
    }
    public String getTargetNodesCsv() { return String.join(",", getTargetNodes()); }
    public int getTemplateVmId() { return templateVmId; }
    public TemplateSelectionMode getTemplateSelectionMode() {
        // Null for configs saved before dynamic selection existed; those cloned a fixed id.
        return templateSelectionMode != null ? templateSelectionMode : TemplateSelectionMode.STATIC_ID;
    }
    public String getTemplateNameRegex() { return templateNameRegex; }
    public String getTemplateTag() { return templateTag; }
    public String getLabelString() { return labelString; }
    public int getNumExecutors() { return numExecutors; }
    public OsType getOsType() { return osType != null ? osType : OsType.LINUX; }
    public CloneStrategy getCloneStrategy() { return cloneStrategy; }
    public String getTargetStorage() { return targetStorage; }
    public String getTargetPool() { return targetPool; }
    public int getCores() { return cores; }
    public int getMemory() { return memory; }
    public int getDiskSizeGb() { return diskSizeGb; }
    public String getNetworkBridge() { return networkBridge; }
    public String getRemoteFs() {
        if (remoteFs != null && !remoteFs.isBlank()) return remoteFs;
        String user = (ciUser != null && !ciUser.isBlank()) ? ciUser : "ubuntu";
        return "/home/" + user + "/agent";
    }
    public String getRawRemoteFs() { return remoteFs; }
    public Node.Mode getMode() { return mode; }
    public String getCredentialsId() { return credentialsId; }
    public String getJavaPath() { return javaPath; }
    public String getJvmOptions() { return jvmOptions; }
    public JavaDistribution getJavaDistribution() { return javaDistribution; }
    // Null-defaulting for configs persisted before this field existed (XStream skips initializers).
    public WindowsLoginShell getWindowsLoginShell() {
        return windowsLoginShell != null ? windowsLoginShell : WindowsLoginShell.AUTO;
    }
    public int getJavaMajorVersion() { return javaMajorVersion; }
    public int getIdleTerminationMinutes() { return idleTerminationMinutes; }
    public int getInstanceCap() { return instanceCap; }
    public int getInstanceMin() { return instanceMin; }
    public int getMaxTotalUses() { return maxTotalUses; }
    public String getNamePrefix() { return namePrefix; }
    public int getStartupWaitSeconds() { return startupWaitSeconds; }
    public String getCiUser() { return ciUser; }
    public String getIpConfig() { return ipConfig; }
    public String getNameserver() { return nameserver; }
    public String getSearchDomain() { return searchDomain; }

    // Setters
    @DataBoundSetter public void setTemplateLocation(TemplateLocation v) {
        this.templateLocation = v != null ? v : TemplateLocation.FIXED_NODE;
    }
    @DataBoundSetter public void setTargetNodes(List<String> values) {
        if (values == null) {
            this.targetNodes = null;
            return;
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                normalized.add(value.trim());
            }
        }
        this.targetNodes = List.copyOf(normalized);
    }
    @DataBoundSetter public void setTemplateSelectionMode(TemplateSelectionMode v) {
        this.templateSelectionMode = v != null ? v : TemplateSelectionMode.STATIC_ID;
    }
    @DataBoundSetter public void setTemplateNameRegex(String v) {
        if (v == null || v.isBlank()) {
            this.templateNameRegex = null;
            return;
        }
        try {
            Pattern.compile(v);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(
                    "Invalid template name regular expression: " + e.getMessage());
        }
        this.templateNameRegex = v;
    }
    @DataBoundSetter public void setTemplateTag(String v) {
        this.templateTag = (v == null || v.isBlank()) ? null : v.trim();
    }
    @DataBoundSetter public void setOsType(OsType v) { this.osType = v != null ? v : OsType.LINUX; }
    @DataBoundSetter public void setCloneStrategy(CloneStrategy v) { this.cloneStrategy = v; }
    @DataBoundSetter public void setTargetStorage(String v) { this.targetStorage = v; }
    @DataBoundSetter public void setTargetPool(String v) { this.targetPool = v; }
    @DataBoundSetter public void setCores(int v) {
        if (v < 0) throw new IllegalArgumentException("CPU cores must be non-negative");
        this.cores = v;
    }
    @DataBoundSetter public void setMemory(int v) {
        if (v < 0) throw new IllegalArgumentException("Memory must be non-negative");
        this.memory = v;
    }
    @DataBoundSetter public void setDiskSizeGb(int v) {
        if (v < 0) throw new IllegalArgumentException("Disk size must be non-negative");
        this.diskSizeGb = v;
    }
    @DataBoundSetter public void setNetworkBridge(String v) { this.networkBridge = v; }
    @DataBoundSetter public void setRemoteFs(String v) { this.remoteFs = (v != null && !v.isBlank()) ? v : null; }
    @DataBoundSetter public void setMode(Node.Mode v) { this.mode = v; }
    @DataBoundSetter public void setCredentialsId(String v) { this.credentialsId = v; }
    @DataBoundSetter public void setJavaPath(String v) { this.javaPath = v; }
    @DataBoundSetter public void setJvmOptions(String v) { this.jvmOptions = v; }
    @DataBoundSetter public void setWindowsLoginShell(WindowsLoginShell v) {
        this.windowsLoginShell = v != null ? v : WindowsLoginShell.AUTO;
    }
    @DataBoundSetter public void setJavaDistribution(JavaDistribution v) {
        this.javaDistribution = v != null ? v : JavaDistribution.NONE;
    }
    @DataBoundSetter public void setJavaMajorVersion(int v) {
        if (v < 0) throw new IllegalArgumentException("Java major version must be non-negative");
        this.javaMajorVersion = v;
    }
    @DataBoundSetter public void setIdleTerminationMinutes(int v) {
        if (v < 0) throw new IllegalArgumentException("Idle termination minutes must be non-negative");
        this.idleTerminationMinutes = v;
    }
    @DataBoundSetter public void setInstanceCap(int v) {
        if (v < 0) throw new IllegalArgumentException("Instance cap must be non-negative");
        this.instanceCap = v;
    }
    @DataBoundSetter public void setInstanceMin(int v) {
        if (v < 0) throw new IllegalArgumentException("Instance minimum must be non-negative");
        this.instanceMin = v;
    }
    @DataBoundSetter public void setMaxTotalUses(int v) {
        if (v < 0) throw new IllegalArgumentException("Max total uses must be non-negative");
        this.maxTotalUses = v;
    }
    @DataBoundSetter public void setNamePrefix(String v) { this.namePrefix = v; }
    @DataBoundSetter public void setStartupWaitSeconds(int v) {
        if (v < 0) throw new IllegalArgumentException("Startup wait seconds must be non-negative");
        this.startupWaitSeconds = v;
    }
    @DataBoundSetter public void setCiUser(String v) {
        this.ciUser = (v == null || v.isBlank()) ? null : v;
    }
    @DataBoundSetter public void setIpConfig(String v) { this.ipConfig = v; }
    @DataBoundSetter public void setNameserver(String v) { this.nameserver = v; }
    @DataBoundSetter public void setSearchDomain(String v) { this.searchDomain = v; }

    @Override
    public Descriptor<ProxmoxTemplate> getDescriptor() {
        return Jenkins.get().getDescriptorOrDie(getClass());
    }

    static void validateTemplateSelection(ProxmoxTemplate template) throws Descriptor.FormException {
        validatePlacement(template);
        switch (template.getTemplateSelectionMode()) {
            case STATIC_ID -> {
                if (template.getTemplateVmId() <= 0) {
                    throw new Descriptor.FormException(
                            "Template VM ID must be selected when using a static template id",
                            "templateVmId");
                }
            }
            case NAME_REGEX -> {
                if (template.getTemplateNameRegex() == null) {
                    throw new Descriptor.FormException(
                            "Template name regex is required when matching templates by name",
                            "templateNameRegex");
                }
            }
            case TAG -> {
                if (template.getTemplateTag() == null) {
                    throw new Descriptor.FormException(
                            "Template tag is required when matching templates by tag",
                            "templateTag");
                }
            }
        }
    }

    static void validatePlacement(ProxmoxTemplate template) throws Descriptor.FormException {
        if (template.getRawTargetNodes() != null && template.getRawTargetNodes().isEmpty()) {
            throw new Descriptor.FormException("At least one Agent Node is required", "targetNodes");
        }
        if (template.getTemplateLocation() == TemplateLocation.EACH_TARGET_NODE
                && template.getRawTargetNodes() == null) {
            throw new Descriptor.FormException(
                    "Agent Nodes must be selected when matching a template on each node", "targetNodes");
        }
        if (template.getTargetNodes().isEmpty()) {
            throw new Descriptor.FormException("At least one Agent Node is required", "targetNodes");
        }
        if (template.getTemplateLocation() == TemplateLocation.FIXED_NODE
                && (template.getNode() == null || template.getNode().isBlank())) {
            throw new Descriptor.FormException(
                    "Template Node is required when using one source template", "node");
        }
        if (template.getTemplateLocation() == TemplateLocation.EACH_TARGET_NODE
                && template.getTemplateSelectionMode() == TemplateSelectionMode.STATIC_ID) {
            throw new Descriptor.FormException(
                    "Matching a local template on each Agent Node requires name-regex or tag selection",
                    "templateSelectionMode");
        }
    }

    static void validateWindowsRemoteFs(ProxmoxTemplate template) throws Descriptor.FormException {
        if (template.getOsType() == OsType.WINDOWS && template.getRawRemoteFs() == null) {
            throw new Descriptor.FormException("Remote FS Root is required for Windows agents", "remoteFs");
        }
    }

    static void validateWindowsJavaDistribution(ProxmoxTemplate template) throws Descriptor.FormException {
        if (template.getOsType() == OsType.WINDOWS
                && template.getJavaDistribution() != JavaDistribution.NONE) {
            throw new Descriptor.FormException(
                    "Java Distribution must be None for Windows agents (Java auto-install is Linux-only)",
                    "javaDistribution");
        }
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ProxmoxTemplate> {

        /** Cap on per-match creation-time lookups in the form's match-count preview. */
        private static final int MAX_WINNER_PREVIEW_MATCHES = 20;

        @Override
        public String getDisplayName() {
            return "Proxmox VM Template";
        }

        @Override
        public ProxmoxTemplate newInstance(org.kohsuke.stapler.StaplerRequest2 req,
                                           net.sf.json.JSONObject formData) throws FormException {
            ProxmoxTemplate template;
            try {
                template = (ProxmoxTemplate) super.newInstance(req, formData);
            } catch (LinkageError e) {
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();
                throw new FormException(root.getMessage(), e, "");
            }
            // Enforce the cross-field rule on save: doCheckInstanceMin only validates client-side, which
            // does not block submission, so the warm-pool minimum could otherwise be saved above the cap.
            if (template != null && template.getInstanceCap() > 0
                    && template.getInstanceMin() > template.getInstanceCap()) {
                throw new FormException("Instance minimum (" + template.getInstanceMin()
                        + ") cannot exceed the instance cap (" + template.getInstanceCap() + ")",
                        "instanceMin");
            }
            // doCheckJavaMajorVersion only validates client-side, which does not block submission.
            // A version below the recommended minimum is allowed (it only warns); reject just the
            // unusable case of a non-positive version when a distribution is selected, which would
            // otherwise build a nonsensical package name (e.g. openjdk-0-jre-headless).
            if (template != null && template.getJavaDistribution() != JavaDistribution.NONE
                    && template.getJavaMajorVersion() < 1) {
                throw new FormException("Java major version must be set (1 or greater) "
                        + "when a Java distribution is selected", "javaMajorVersion");
            }
            if (template != null) {
                validateTemplateSelection(template);
                validateWindowsRemoteFs(template);
                validateWindowsJavaDistribution(template);
            }
            return template;
        }

        private ProxmoxClient tryCreateClient(String apiUrl, String credentialsId,
                                               boolean ignoreSslErrors) {
            if (apiUrl == null || apiUrl.isBlank()
                    || credentialsId == null || credentialsId.isBlank()) {
                return null;
            }
            try {
                ProxmoxTokenCredentials creds = CredentialsMatchers.firstOrNull(
                        CredentialsProvider.lookupCredentialsInItemGroup(
                                ProxmoxTokenCredentials.class, Jenkins.get(), null,
                                Collections.emptyList()),
                        CredentialsMatchers.withId(credentialsId));
                if (creds == null) return null;
                return new ProxmoxClient(apiUrl, creds.getTokenId(),
                        creds.getTokenSecret(), ignoreSslErrors);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to create Proxmox API client", e);
                return null;
            }
        }

        @POST
        public ListBoxModel doFillNodeItems(
                @RelativePath("..") @QueryParameter("apiUrl") String apiUrl,
                @RelativePath("..") @QueryParameter("credentialsId") String credentialsId,
                @RelativePath("..") @QueryParameter("ignoreSslErrors") boolean ignoreSslErrors) {
            ListBoxModel model = new ListBoxModel();
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return model;
            }
            ProxmoxClient client = tryCreateClient(apiUrl, credentialsId, ignoreSslErrors);
            if (client == null) {
                model.add("(configure API connection first)", "");
                return model;
            }
            try {
                List<ClusterNode> nodes = client.getNodes();
                model.add("", "");
                for (ClusterNode n : nodes) {
                    String label = "online".equals(n.status())
                            ? n.node()
                            : n.node() + " (offline)";
                    model.add(label, n.node());
                }
            } catch (Exception e) {
                model.add("(API error: " + e.getMessage() + ")", "");
            }
            return model;
        }

        @POST
        public ListBoxModel doFillTemplateVmIdItems(
                @QueryParameter("node") String node,
                @RelativePath("..") @QueryParameter("apiUrl") String apiUrl,
                @RelativePath("..") @QueryParameter("credentialsId") String credentialsId,
                @RelativePath("..") @QueryParameter("ignoreSslErrors") boolean ignoreSslErrors) {
            ListBoxModel model = new ListBoxModel();
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return model;
            }
            if (node == null || node.isBlank()) {
                model.add("(select a node first)", "");
                return model;
            }
            ProxmoxClient client = tryCreateClient(apiUrl, credentialsId, ignoreSslErrors);
            if (client == null) {
                model.add("(configure API connection first)", "");
                return model;
            }
            try {
                List<VirtualMachine> templates = client.getTemplates(node);
                model.add("", "");
                for (VirtualMachine t : templates) {
                    String label = "VM " + t.vmid() + " - " + (t.name() != null ? t.name() : "unnamed");
                    model.add(label, String.valueOf(t.vmid()));
                }
                if (templates.isEmpty()) {
                    model.add("(no templates found on " + node + ")", "");
                }
            } catch (Exception e) {
                model.add("(API error: " + e.getMessage() + ")", "");
            }
            return model;
        }

        @POST
        public ListBoxModel doFillTargetStorageItems(
                @QueryParameter("node") String node,
                @QueryParameter("selectedTargetNodes") String selectedTargetNodes,
                @QueryParameter("templateLocation") String templateLocation,
                @QueryParameter("currentTargetStorage") String currentTargetStorage,
                @RelativePath("..") @QueryParameter("apiUrl") String apiUrl,
                @RelativePath("..") @QueryParameter("credentialsId") String credentialsId,
                @RelativePath("..") @QueryParameter("ignoreSslErrors") boolean ignoreSslErrors) {
            ListBoxModel model = new ListBoxModel();
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return model;
            }
            model.add("(inherit from template)", "");
            List<String> nodes = selectedResourceNodes(node, selectedTargetNodes);
            if (nodes.isEmpty()) {
                includeUnavailableChoice(model, currentTargetStorage);
                return model;
            }
            ProxmoxClient client = tryCreateClient(apiUrl, credentialsId, ignoreSslErrors);
            if (client == null) {
                includeUnavailableChoice(model, currentTargetStorage);
                return model;
            }
            try {
                boolean sharedRequired = TemplateLocation.FIXED_NODE.name().equals(templateLocation)
                        && node != null && !node.isBlank()
                        && nodes.stream().anyMatch(targetNode -> !node.equals(targetNode));
                Map<String, StoragePool> common = new java.util.LinkedHashMap<>();
                boolean first = true;
                for (String targetNode : nodes) {
                    Map<String, StoragePool> onNode = client.getStoragePools(targetNode).stream()
                            .filter(pool -> !sharedRequired || pool.shared() != 0)
                            .collect(Collectors.toMap(StoragePool::storage, pool -> pool,
                                    (left, right) -> left, java.util.LinkedHashMap::new));
                    if (first) {
                        common.putAll(onNode);
                        first = false;
                    } else {
                        common.keySet().retainAll(onNode.keySet());
                    }
                }
                for (StoragePool p : common.values()) {
                    model.add(p.storage() + " (" + p.type() + ")", p.storage());
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to fetch common storage pools for nodes " + nodes, e);
            }
            includeUnavailableChoice(model, currentTargetStorage);
            return model;
        }

        @POST
        public ListBoxModel doFillNetworkBridgeItems(
                @QueryParameter("node") String node,
                @QueryParameter("selectedTargetNodes") String selectedTargetNodes,
                @QueryParameter("currentNetworkBridge") String currentNetworkBridge,
                @RelativePath("..") @QueryParameter("apiUrl") String apiUrl,
                @RelativePath("..") @QueryParameter("credentialsId") String credentialsId,
                @RelativePath("..") @QueryParameter("ignoreSslErrors") boolean ignoreSslErrors) {
            ListBoxModel model = new ListBoxModel();
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return model;
            }
            model.add("(inherit from template)", "");
            List<String> nodes = selectedResourceNodes(node, selectedTargetNodes);
            if (nodes.isEmpty()) {
                includeUnavailableChoice(model, currentNetworkBridge);
                return model;
            }
            ProxmoxClient client = tryCreateClient(apiUrl, credentialsId, ignoreSslErrors);
            if (client == null) {
                includeUnavailableChoice(model, currentNetworkBridge);
                return model;
            }
            try {
                Set<String> common = new LinkedHashSet<>();
                boolean first = true;
                for (String targetNode : nodes) {
                    Set<String> onNode = client.getNetworkDevices(targetNode).stream()
                            .filter(NetworkDevice::isBridge)
                            .map(NetworkDevice::iface)
                            .collect(Collectors.toCollection(LinkedHashSet::new));
                    if (first) {
                        common.addAll(onNode);
                        first = false;
                    } else {
                        common.retainAll(onNode);
                    }
                }
                for (String bridge : common) model.add(bridge, bridge);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to fetch common network bridges for nodes " + nodes, e);
            }
            includeUnavailableChoice(model, currentNetworkBridge);
            return model;
        }

        private void includeUnavailableChoice(ListBoxModel model, String currentValue) {
            if (currentValue != null && !currentValue.isBlank()
                    && model.stream().noneMatch(option -> currentValue.equals(option.value))) {
                model.add(currentValue + " (unavailable)", currentValue);
            }
        }

        private List<String> selectedResourceNodes(String node, String selectedTargetNodes) {
            if (selectedTargetNodes != null) {
                List<String> selected = java.util.Arrays.stream(selectedTargetNodes.split(","))
                        .filter(value -> value != null && !value.isBlank())
                        .map(String::trim)
                        .distinct()
                        .toList();
                if (!selected.isEmpty()) return selected;
            }
            return node != null && !node.isBlank() ? List.of(node) : List.of();
        }

        @POST
        public ListBoxModel doFillTargetPoolItems(
                @RelativePath("..") @QueryParameter("apiUrl") String apiUrl,
                @RelativePath("..") @QueryParameter("credentialsId") String credentialsId,
                @RelativePath("..") @QueryParameter("ignoreSslErrors") boolean ignoreSslErrors) {
            ListBoxModel model = new ListBoxModel();
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return model;
            }
            model.add("(none)", "");
            ProxmoxClient client = tryCreateClient(apiUrl, credentialsId, ignoreSslErrors);
            if (client == null) return model;
            try {
                List<ResourcePool> pools = client.getPools();
                for (ResourcePool p : pools) {
                    String label = p.comment() != null && !p.comment().isBlank()
                            ? p.poolid() + " - " + p.comment()
                            : p.poolid();
                    model.add(label, p.poolid());
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to fetch resource pools", e);
            }
            return model;
        }


        @POST
        public ListBoxModel doFillCredentialsIdItems() {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            ListBoxModel model = new ListBoxModel();
            model.add("- none -", "");
            for (StandardUsernameCredentials c : CredentialsProvider.lookupCredentialsInItemGroup(
                    StandardUsernameCredentials.class, Jenkins.get(), ACL.SYSTEM2,
                    Collections.emptyList())) {
                model.add(c.getId(), c.getId());
            }
            return model;
        }

        @POST
        public FormValidation doCheckNode(@QueryParameter String value,
                                          @QueryParameter String templateLocation) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            if (TemplateLocation.EACH_TARGET_NODE.name().equals(templateLocation)) {
                return FormValidation.ok();
            }
            if (value == null || value.isBlank()) {
                return FormValidation.error("Template Node is required");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckTemplateVmId(@QueryParameter int value,
                                                  @QueryParameter String templateSelectionMode,
                                                  @QueryParameter String templateLocation) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            if (TemplateLocation.EACH_TARGET_NODE.name().equals(templateLocation)) {
                return FormValidation.ok();
            }
            // The static-id select still submits (hidden, not disabled) in dynamic modes; don't
            // flag its empty value then.
            if (templateSelectionMode != null && !templateSelectionMode.isBlank()
                    && !TemplateSelectionMode.STATIC_ID.name().equals(templateSelectionMode)) {
                return FormValidation.ok();
            }
            if (value <= 0) {
                return FormValidation.error("Template VM ID must be positive");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckTemplateNameRegex(
                @QueryParameter String value,
                @QueryParameter String node,
                @QueryParameter String templateLocation,
                @QueryParameter("selectedTargetNodes") String selectedTargetNodes,
                @RelativePath("..") @QueryParameter("apiUrl") String apiUrl,
                @RelativePath("..") @QueryParameter("credentialsId") String credentialsId,
                @RelativePath("..") @QueryParameter("ignoreSslErrors") boolean ignoreSslErrors) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            if (value == null || value.isBlank()) {
                return FormValidation.error("Template name regex is required");
            }
            Predicate<VirtualMachine> matcher;
            try {
                matcher = TemplateResolver.nameRegexMatcher(value);
            } catch (PatternSyntaxException e) {
                return FormValidation.error("Invalid regular expression: " + e.getMessage());
            }
            return countTemplateMatchesForLocation(node, templateLocation, selectedTargetNodes,
                    apiUrl, credentialsId, ignoreSslErrors, matcher);
        }

        @POST
        public FormValidation doCheckTemplateTag(
                @QueryParameter String value,
                @QueryParameter String node,
                @QueryParameter String templateLocation,
                @QueryParameter("selectedTargetNodes") String selectedTargetNodes,
                @RelativePath("..") @QueryParameter("apiUrl") String apiUrl,
                @RelativePath("..") @QueryParameter("credentialsId") String credentialsId,
                @RelativePath("..") @QueryParameter("ignoreSslErrors") boolean ignoreSslErrors) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            if (value == null || value.isBlank()) {
                return FormValidation.error("Template tag is required");
            }
            return countTemplateMatchesForLocation(node, templateLocation, selectedTargetNodes,
                    apiUrl, credentialsId, ignoreSslErrors, TemplateResolver.tagMatcher(value));
        }

        private FormValidation countTemplateMatchesForLocation(
                String node, String templateLocation, String selectedTargetNodes,
                String apiUrl, String credentialsId, boolean ignoreSslErrors,
                Predicate<VirtualMachine> matcher) {
            if (!TemplateLocation.EACH_TARGET_NODE.name().equals(templateLocation)) {
                return countTemplateMatches(node, apiUrl, credentialsId, ignoreSslErrors, matcher);
            }
            List<String> nodes = selectedResourceNodes(null, selectedTargetNodes);
            if (nodes.isEmpty()) return FormValidation.ok();
            ProxmoxClient client = tryCreateClient(apiUrl, credentialsId, ignoreSslErrors);
            if (client == null) return FormValidation.ok();
            List<String> missing = new ArrayList<>();
            try {
                for (String targetNode : nodes) {
                    boolean matched = client.getTemplates(targetNode).stream().anyMatch(matcher);
                    if (!matched) missing.add(targetNode);
                }
            } catch (Exception e) {
                return FormValidation.warning("Could not query Proxmox: " + e.getMessage());
            }
            int matching = nodes.size() - missing.size();
            if (missing.isEmpty()) {
                return FormValidation.ok("Matches a local template on all " + nodes.size() + " Agent Nodes");
            }
            return FormValidation.warning("Matches a local template on " + matching + "/" + nodes.size()
                    + " Agent Nodes; no match on " + String.join(", ", missing));
        }

        /**
         * Informational match count for the dynamic selection fields. Zero matches is a warning,
         * not an error: the selection is re-resolved at every provision, so a not-yet-built
         * template is a legitimate saved state. Fetching each match's creation time costs one API
         * call per match, so the "will currently clone" preview is skipped for absurd match counts.
         */
        private FormValidation countTemplateMatches(String node, String apiUrl, String credentialsId,
                boolean ignoreSslErrors, Predicate<VirtualMachine> matcher) {
            if (node == null || node.isBlank()) {
                return FormValidation.ok();
            }
            ProxmoxClient client = tryCreateClient(apiUrl, credentialsId, ignoreSslErrors);
            if (client == null) {
                return FormValidation.ok();
            }
            try {
                List<VirtualMachine> matches = client.getTemplates(node).stream()
                        .filter(matcher).collect(Collectors.toList());
                if (matches.isEmpty()) {
                    return FormValidation.warning("Matches 0 templates on " + node
                            + "; provisioning will fail until a template matches");
                }
                String message = "Matches " + matches.size()
                        + (matches.size() == 1 ? " template" : " templates");
                if (matches.size() <= MAX_WINNER_PREVIEW_MATCHES) {
                    VirtualMachine winner = TemplateResolver.pickNewest(client, node, matches);
                    message += "; will currently clone VM " + winner.vmid()
                            + (winner.name() != null ? " (" + winner.name() + ")" : "");
                }
                return FormValidation.ok(message);
            } catch (Exception e) {
                return FormValidation.warning("Could not query Proxmox: " + e.getMessage());
            }
        }

        @POST
        public FormValidation doCheckRemoteFs(@QueryParameter String value,
                                              @QueryParameter String osType) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            if (OsType.WINDOWS.name().equals(osType) && (value == null || value.isBlank())) {
                return FormValidation.error("Remote FS Root is required for Windows agents");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckInstanceMin(@QueryParameter int value, @QueryParameter int instanceCap) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            if (value < 0) {
                return FormValidation.error("Must be non-negative");
            }
            if (instanceCap > 0 && value > instanceCap) {
                return FormValidation.error("Instance minimum cannot exceed the instance cap (" + instanceCap + ")");
            }
            return FormValidation.ok();
        }

        public ComboBoxModel doFillJavaMajorVersionItems() {
            return new ComboBoxModel("21", "25");
        }

        @POST
        public FormValidation doCheckJavaMajorVersion(@QueryParameter String value,
                                                      @QueryParameter String javaDistribution) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            // The version is only used when a distribution is selected (see ProxmoxLauncher).
            if (javaDistribution == null || javaDistribution.isBlank()
                    || JavaDistribution.NONE.name().equals(javaDistribution)) {
                return FormValidation.ok();
            }
            if (value == null || value.isBlank()) {
                return FormValidation.error("Java major version is required when a distribution is selected");
            }
            int parsed;
            try {
                parsed = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
                return FormValidation.error("Java major version must be a whole number");
            }
            if (parsed < 1) {
                return FormValidation.error("Java major version must be a positive version number");
            }
            if (parsed < JavaDistribution.RECOMMENDED_MIN_MAJOR_VERSION) {
                return FormValidation.warning("Java " + parsed + " is older than the recommended minimum ("
                        + JavaDistribution.RECOMMENDED_MIN_MAJOR_VERSION
                        + "); make sure the package is available in the agent's apt repositories");
            }
            return FormValidation.ok();
        }


    }
}
