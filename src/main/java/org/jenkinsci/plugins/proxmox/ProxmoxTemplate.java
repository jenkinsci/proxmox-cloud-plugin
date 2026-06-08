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
import org.jenkinsci.plugins.proxmox.api.ProxmoxClient;
import org.jenkinsci.plugins.proxmox.api.model.CloneOptions;
import org.jenkinsci.plugins.proxmox.api.model.ClusterNode;
import org.jenkinsci.plugins.proxmox.api.model.NetworkDevice;
import org.jenkinsci.plugins.proxmox.api.model.ResourcePool;
import org.jenkinsci.plugins.proxmox.api.model.StoragePool;
import org.jenkinsci.plugins.proxmox.api.model.VirtualMachine;
import org.jenkinsci.plugins.proxmox.api.model.VmConfig;
import org.jenkinsci.plugins.proxmox.config.CloneStrategy;
import org.jenkinsci.plugins.proxmox.config.JavaDistribution;
import org.jenkinsci.plugins.proxmox.config.ProxmoxTokenCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import hudson.RelativePath;
import org.kohsuke.stapler.verb.POST;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;

public class ProxmoxTemplate implements Describable<ProxmoxTemplate> {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxTemplate.class.getName());

    private final String name;
    private final String node;
    private final int templateVmId;
    private final String labelString;
    private final int numExecutors;

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

    /**
     * Clone, configure, and start a VM for the given pre-reserved id, returning the agent. The id is
     * reserved by {@link ProxmoxCloud#reserveVmId()} under a short lock so concurrent provisions get
     * distinct ids; the clone/start here runs outside that lock so agents come up in parallel.
     */
    public ProxmoxAgent provision(ProxmoxCloud cloud, TaskListener listener, int newVmId) throws Exception {
        var log = listener.getLogger();
        ProxmoxClient client = cloud.getClient();

        String vmName = namePrefix + newVmId;
        log.println("[Proxmox] Cloning template " + templateVmId + " → VM " + newVmId + " (" + vmName + ")");

        CloneOptions cloneOpts = new CloneOptions(
                newVmId, vmName,
                "jenkins-managed;cloud:" + cloud.name + ";template:" + name,
                cloneStrategy == CloneStrategy.FULL,
                targetStorage, targetPool);

        String upid = client.cloneVm(node, templateVmId, cloneOpts);
        client.waitForTask(node, upid, cloud.getOperationTimeout());
        log.println("[Proxmox] Clone complete");

        String sshPublicKey = derivePublicKeyFromCredential(log);
        VmConfig vmConfig = buildVmConfig(sshPublicKey);
        if (vmConfig != null) {
            log.println("[Proxmox] Configuring VM " + newVmId);
            client.configureVm(node, newVmId, vmConfig);
        }

        if (networkBridge != null && !networkBridge.isBlank()) {
            log.println("[Proxmox] Setting network bridge to " + networkBridge);
            client.setNetworkBridge(node, newVmId, networkBridge);
        }

        if (diskSizeGb > 0) {
            log.println("[Proxmox] Resizing disk scsi0 to " + diskSizeGb + "G");
            client.resizeVmDisk(node, newVmId, "scsi0", diskSizeGb);
        }

        log.println("[Proxmox] Starting VM " + newVmId);
        upid = client.startVm(node, newVmId);
        client.waitForTask(node, upid, cloud.getOperationTimeout());

        String staticIp = parseStaticIp(ipConfig);
        ProxmoxLauncher launcher = new ProxmoxLauncher(
                credentialsId, javaPath, jvmOptions, startupWaitSeconds, staticIp,
                javaDistribution, javaMajorVersion);

        // Use getRemoteFs() rather than the raw field: a blank Remote FS Root is stored as null
        // (see setRemoteFs), and an agent with a null remoteFS NPEs in
        // SSHLauncher.getWorkingDirectory() at launch.
        return new ProxmoxAgent(
                vmName, getRemoteFs(), numExecutors, mode, labelString,
                launcher,
                cloud.name, name, node, newVmId,
                idleTerminationMinutes, maxTotalUses);
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

    private VmConfig buildVmConfig(String sshPublicKey) {
        boolean hasConfig = (cores > 0) || (memory > 0) || ciUser != null
                || sshPublicKey != null || ipConfig != null
                || nameserver != null || searchDomain != null;

        if (!hasConfig) {
            return null;
        }

        return new VmConfig(
                cores > 0 ? cores : null,
                memory > 0 ? memory : null,
                ciUser,
                sshPublicKey,
                ipConfig,
                nameserver,
                searchDomain);
    }

    private String parseStaticIp(String ipConfig) {
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
        Jenkins jenkins = Jenkins.get();
        long now = System.currentTimeMillis();
        long graceMs = (long) cloud.getOrphanCleanupGracePeriodSeconds() * 1000;
        int count = 0;
        for (var node : jenkins.getNodes()) {
            if (node instanceof ProxmoxAgent agent
                    && cloud.name.equals(agent.getCloudName())
                    && name.equals(agent.getTemplateName())
                    && !agent.isOfflineDead(now, graceMs)) {
                count++;
            }
        }
        return count;
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
    public int getTemplateVmId() { return templateVmId; }
    public String getLabelString() { return labelString; }
    public int getNumExecutors() { return numExecutors; }
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
    public Node.Mode getMode() { return mode; }
    public String getCredentialsId() { return credentialsId; }
    public String getJavaPath() { return javaPath; }
    public String getJvmOptions() { return jvmOptions; }
    public JavaDistribution getJavaDistribution() { return javaDistribution; }
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
        if (v == null || v.isBlank()) throw new IllegalArgumentException("VM Username is required");
        this.ciUser = v;
    }
    @DataBoundSetter public void setIpConfig(String v) { this.ipConfig = v; }
    @DataBoundSetter public void setNameserver(String v) { this.nameserver = v; }
    @DataBoundSetter public void setSearchDomain(String v) { this.searchDomain = v; }

    @Override
    public Descriptor<ProxmoxTemplate> getDescriptor() {
        return Jenkins.get().getDescriptorOrDie(getClass());
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<ProxmoxTemplate> {

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
                @RelativePath("..") @QueryParameter("apiUrl") String apiUrl,
                @RelativePath("..") @QueryParameter("credentialsId") String credentialsId,
                @RelativePath("..") @QueryParameter("ignoreSslErrors") boolean ignoreSslErrors) {
            ListBoxModel model = new ListBoxModel();
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return model;
            }
            model.add("(inherit from template)", "");
            if (node == null || node.isBlank()) return model;
            ProxmoxClient client = tryCreateClient(apiUrl, credentialsId, ignoreSslErrors);
            if (client == null) return model;
            try {
                List<StoragePool> pools = client.getStoragePools(node);
                for (StoragePool p : pools) {
                    model.add(p.storage() + " (" + p.type() + ")", p.storage());
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to fetch storage pools for node " + node, e);
            }
            return model;
        }

        @POST
        public ListBoxModel doFillNetworkBridgeItems(
                @QueryParameter("node") String node,
                @RelativePath("..") @QueryParameter("apiUrl") String apiUrl,
                @RelativePath("..") @QueryParameter("credentialsId") String credentialsId,
                @RelativePath("..") @QueryParameter("ignoreSslErrors") boolean ignoreSslErrors) {
            ListBoxModel model = new ListBoxModel();
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return model;
            }
            model.add("(inherit from template)", "");
            if (node == null || node.isBlank()) return model;
            ProxmoxClient client = tryCreateClient(apiUrl, credentialsId, ignoreSslErrors);
            if (client == null) return model;
            try {
                List<NetworkDevice> devices = client.getNetworkDevices(node);
                for (NetworkDevice d : devices) {
                    if (d.isBridge()) {
                        model.add(d.iface(), d.iface());
                    }
                }
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to fetch network devices for node " + node, e);
            }
            return model;
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
        public FormValidation doCheckNode(@QueryParameter String value) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return FormValidation.ok();
            }
            if (value == null || value.isBlank()) {
                return FormValidation.error("Proxmox Node is required");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckTemplateVmId(@QueryParameter int value) {
            if (value <= 0) {
                return FormValidation.error("Template VM ID must be positive");
            }
            return FormValidation.ok();
        }

        @POST
        public FormValidation doCheckInstanceMin(@QueryParameter int value, @QueryParameter int instanceCap) {
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
