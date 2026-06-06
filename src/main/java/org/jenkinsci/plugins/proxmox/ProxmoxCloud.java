package org.jenkinsci.plugins.proxmox;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.proxmox.api.ProxmoxAuthenticationException;
import org.jenkinsci.plugins.proxmox.api.ProxmoxClient;
import org.jenkinsci.plugins.proxmox.api.ProxmoxException;
import org.jenkinsci.plugins.proxmox.config.ProxmoxCloudConfigSync;
import org.jenkinsci.plugins.proxmox.config.ProxmoxTokenCredentials;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

import jakarta.servlet.http.HttpServletResponse;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.kohsuke.stapler.HttpRedirect;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.HttpResponses;
import org.kohsuke.stapler.interceptor.RequirePOST;

public class ProxmoxCloud extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxCloud.class.getName());

    static final int MIN_PVE_VERSION = 8;
    static final int MAX_KNOWN_PVE_VERSION = 9;

    private String apiUrl;
    private String credentialsId;
    private boolean ignoreSslErrors = false;
    private int instanceCap;
    private int operationTimeout = 300;
    private int startVmId;
    private boolean cleanupOrphanedAgents;
    private int orphanCleanupGracePeriodSeconds = 300;
    private boolean configManaged;
    private long lastSyncTimestamp;
    private long lastConfigTimestamp;
    private List<ProxmoxTemplate> templates = new ArrayList<>();

    private transient volatile ProxmoxClient client;
    private transient volatile Object provisionLock;
    private transient volatile Set<Integer> reservedVmIds;

    private static final DateTimeFormatter SYNC_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @DataBoundConstructor
    public ProxmoxCloud(String name) {
        super(name);
    }

    protected Object readResolve() {
        // Field added after initial release; clouds persisted before it (or with an out-of-range
        // value) deserialize with 0 because XStream skips field initializers. Restore the default
        // so orphan cleanup never acts with a zero grace period.
        if (orphanCleanupGracePeriodSeconds < 1) {
            orphanCleanupGracePeriodSeconds = 300;
        }
        return this;
    }

    private Object getProvisionLock() {
        Object lock = provisionLock;
        if (lock == null) {
            synchronized (this) {
                lock = provisionLock;
                if (lock == null) {
                    lock = new Object();
                    provisionLock = lock;
                }
            }
        }
        return lock;
    }

    private Set<Integer> getReservedVmIds() {
        Set<Integer> reserved = reservedVmIds;
        if (reserved == null) {
            synchronized (this) {
                reserved = reservedVmIds;
                if (reserved == null) {
                    reserved = new HashSet<>();
                    reservedVmIds = reserved;
                }
            }
        }
        return reserved;
    }

    /**
     * Atomically reserve a free VM id for an imminent clone. {@link #getProvisionLock()} is held only
     * for the (fast) id lookup, not the clone/start, so concurrent provisions pick distinct ids yet
     * run their clones in parallel and the cloud fills its cap in roughly one provision time. The
     * reserved set also covers ids that are about to be cloned but whose VM does not exist yet, which
     * Proxmox's {@code nextid} cannot see. Always pair with {@link #releaseVmId(int)} in a finally.
     */
    int reserveVmId() {
        ProxmoxClient apiClient = getClient();
        synchronized (getProvisionLock()) {
            Set<Integer> reserved = getReservedVmIds();
            int floor = startVmId;
            for (int attempt = 0; attempt < 1000; attempt++) {
                int id = apiClient.getNextVmId(floor);
                if (reserved.add(id)) {
                    return id;
                }
                floor = id + 1; // already reserved in-flight; search above it
            }
            throw new ProxmoxException("Could not reserve a free VM id starting at " + startVmId);
        }
    }

    void releaseVmId(int vmId) {
        Set<Integer> reserved = reservedVmIds;
        if (reserved != null) {
            synchronized (getProvisionLock()) {
                reserved.remove(vmId);
            }
        }
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(CloudState state, int excessWorkload) {
        Label label = state.getLabel();
        List<NodeProvisioner.PlannedNode> planned = new ArrayList<>();

        for (ProxmoxTemplate template : templates) {
            if (excessWorkload <= 0) break;
            if (!template.matches(label)) continue;

            int currentCount = template.getNumActiveAgents(this);
            int templateCap = template.getInstanceCap();
            int available = (templateCap > 0) ? templateCap - currentCount : Integer.MAX_VALUE;

            if (instanceCap > 0) {
                int totalCount = getRunningAgentCount();
                available = Math.min(available, instanceCap - totalCount);
            }

            int toProvision = Math.min(excessWorkload, available);
            for (int i = 0; i < toProvision; i++) {
                String displayName = template.getName() + " (pending)";
                ProxmoxTemplate t = template;
                // Each clone reserves its own id under a short lock, then clones/starts outside it, so
                // multiple agents come up concurrently up to the cap rather than strictly serially.
                Future<Node> future = Computer.threadPoolForRemoting.submit(() -> {
                    int vmId = reserveVmId();
                    try {
                        return t.provision(this, hudson.model.TaskListener.NULL, vmId);
                    } finally {
                        releaseVmId(vmId);
                    }
                });
                planned.add(new NodeProvisioner.PlannedNode(displayName, future, template.getNumExecutors()));
                excessWorkload -= template.getNumExecutors();
            }
        }

        return planned;
    }

    @Override
    public boolean canProvision(CloudState state) {
        Label label = state.getLabel();
        if (instanceCap > 0 && getRunningAgentCount() >= instanceCap) {
            return false;
        }
        for (ProxmoxTemplate template : templates) {
            if (template.matches(label)) {
                int cap = template.getInstanceCap();
                if (cap <= 0 || template.getNumActiveAgents(this) < cap) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Functional agents for this cloud, used for instance-cap accounting. Offline-dead nodes (an
     * extended-offline channel, or a phantom whose VM is gone) are excluded so they cannot hold cap
     * slots and block working replacements while the orphan reconcile catches up (issues #16, #17).
     */
    public int getRunningAgentCount() {
        long now = System.currentTimeMillis();
        long graceMs = (long) orphanCleanupGracePeriodSeconds * 1000;
        int count = 0;
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof ProxmoxAgent agent && name.equals(agent.getCloudName())
                    && !agent.isOfflineDead(now, graceMs)) {
                count++;
            }
        }
        return count;
    }

    @RequirePOST
    public HttpResponse doProvision(@QueryParameter String template) {
        checkPermission(PROVISION);

        if (template == null || template.isBlank()) {
            throw HttpResponses.error(HttpServletResponse.SC_BAD_REQUEST, "The 'template' parameter is missing");
        }

        ProxmoxTemplate t = getTemplateByName(template);
        if (t == null) {
            throw HttpResponses.error(HttpServletResponse.SC_BAD_REQUEST, "No such template: " + template);
        }

        Jenkins jenkins = Jenkins.get();
        if (jenkins.isQuietingDown()) {
            throw HttpResponses.error(HttpServletResponse.SC_BAD_REQUEST, "Jenkins is quieting down");
        }

        try {
            ProxmoxAgent agent;
            int vmId = reserveVmId();
            try {
                agent = t.provision(this, hudson.model.TaskListener.NULL, vmId);
            } finally {
                releaseVmId(vmId);
            }
            jenkins.addNode(agent);
            return new HttpRedirect("/computer/" + agent.getNodeName());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to provision from template: " + template, e);
            throw HttpResponses.error(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                    "Provisioning failed: " + e.getMessage());
        }
    }

    public ProxmoxTemplate getTemplateByName(String name) {
        for (ProxmoxTemplate t : templates) {
            if (t.getName().equals(name)) {
                return t;
            }
        }
        return null;
    }

    public ProxmoxClient getClient() {
        ProxmoxClient c = client;
        if (c == null) {
            synchronized (this) {
                c = client;
                if (c == null) {
                    ProxmoxTokenCredentials creds = CredentialsMatchers.firstOrNull(
                            CredentialsProvider.lookupCredentialsInItemGroup(
                                    ProxmoxTokenCredentials.class, Jenkins.get(), null, Collections.emptyList()),
                            CredentialsMatchers.withId(credentialsId));

                    if (creds == null) {
                        throw new ProxmoxException("Credentials not found: " + credentialsId);
                    }

                    c = new ProxmoxClient(apiUrl, creds.getTokenId(), creds.getTokenSecret(), ignoreSslErrors);
                    client = c;
                }
            }
        }
        return c;
    }

    private synchronized void resetClient() {
        client = null;
    }

    // Getters
    public String getApiUrl() { return apiUrl; }
    public String getCredentialsId() { return credentialsId; }
    public boolean isIgnoreSslErrors() { return ignoreSslErrors; }
    public int getInstanceCap() { return instanceCap; }
    public int getOperationTimeout() { return operationTimeout; }
    public int getStartVmId() { return startVmId; }
    public int getOrphanCleanupGracePeriodSeconds() { return orphanCleanupGracePeriodSeconds; }
    public boolean isCleanupOrphanedAgents() { return cleanupOrphanedAgents; }
    public boolean isConfigManaged() { return configManaged; }
    public long getLastSyncTimestamp() { return lastSyncTimestamp; }
    public long getLastConfigTimestamp() { return lastConfigTimestamp; }
    public List<ProxmoxTemplate> getTemplates() { return templates; }

    public boolean isConfigReadOnly() {
        if (!configManaged) return false;
        ProxmoxCloudConfigSync sync = ProxmoxCloudConfigSync.get();
        return sync != null && !sync.isAllowManualChanges();
    }

    public String getLastUpdateDisplay() {
        long ts = configManaged ? lastSyncTimestamp : lastConfigTimestamp;
        if (ts == 0) return "";
        return SYNC_TIME_FORMAT.format(Instant.ofEpochMilli(ts));
    }

    public boolean isManuallyModified() {
        return configManaged && lastConfigTimestamp > 0;
    }

    public String getManualModificationDisplay() {
        if (!configManaged || lastConfigTimestamp == 0) return "";
        return SYNC_TIME_FORMAT.format(Instant.ofEpochMilli(lastConfigTimestamp));
    }

    // Setters
    @DataBoundSetter public void setApiUrl(String v) { this.apiUrl = v; resetClient(); }
    @DataBoundSetter public void setCredentialsId(String v) { this.credentialsId = v; resetClient(); }
    @DataBoundSetter public void setIgnoreSslErrors(boolean v) { this.ignoreSslErrors = v; resetClient(); }
    @DataBoundSetter public void setInstanceCap(int v) {
        if (v < 0) throw new IllegalArgumentException("Instance cap must be non-negative");
        this.instanceCap = v;
    }
    @DataBoundSetter public void setOperationTimeout(int v) {
        if (v < 1) throw new IllegalArgumentException("Operation timeout must be at least 1");
        this.operationTimeout = v;
    }
    @DataBoundSetter public void setStartVmId(int v) {
        if (v < 0) throw new IllegalArgumentException("Start VM ID must be non-negative");
        this.startVmId = v;
    }
    @DataBoundSetter public void setCleanupOrphanedAgents(boolean v) { this.cleanupOrphanedAgents = v; }
    @DataBoundSetter public void setOrphanCleanupGracePeriodSeconds(int v) {
        if (v < 1) throw new IllegalArgumentException("Agent removal grace period must be at least 1 second");
        this.orphanCleanupGracePeriodSeconds = v;
    }
    @DataBoundSetter public void setConfigManaged(boolean v) { this.configManaged = v; }
    @DataBoundSetter public void setLastSyncTimestamp(long v) { this.lastSyncTimestamp = v; }
    @DataBoundSetter public void setLastConfigTimestamp(long v) { this.lastConfigTimestamp = v; }
    @DataBoundSetter public void setTemplates(List<ProxmoxTemplate> v) { this.templates = v != null ? v : new ArrayList<>(); }

    @Extension
    @Symbol("proxmox")
    public static class DescriptorImpl extends Descriptor<Cloud> {

        @Override
        public String getDisplayName() {
            return "Proxmox VE";
        }

        @Override
        public Cloud newInstance(org.kohsuke.stapler.StaplerRequest2 req, net.sf.json.JSONObject formData) throws FormException {
            ProxmoxCloud cloud;
            try {
                cloud = (ProxmoxCloud) super.newInstance(req, formData);
            } catch (LinkageError e) {
                Throwable root = e;
                while (root.getCause() != null) root = root.getCause();
                throw new FormException(root.getMessage(), e, "");
            }
            if (cloud != null) {
                cloud.setLastConfigTimestamp(System.currentTimeMillis());
            }
            return cloud;
        }

        @POST
        public FormValidation doTestConnection(@QueryParameter String apiUrl,
                                                @QueryParameter String credentialsId,
                                                @QueryParameter boolean ignoreSslErrors) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);

            if (apiUrl == null || apiUrl.isBlank()) {
                return FormValidation.error("API URL is required");
            }
            if (credentialsId == null || credentialsId.isBlank()) {
                return FormValidation.error("Credentials are required");
            }

            try {
                ProxmoxTokenCredentials creds = CredentialsMatchers.firstOrNull(
                        CredentialsProvider.lookupCredentialsInItemGroup(
                                ProxmoxTokenCredentials.class, Jenkins.get(), null, Collections.emptyList()),
                        CredentialsMatchers.withId(credentialsId));

                if (creds == null) {
                    return FormValidation.error("Credentials not found: " + credentialsId);
                }

                ProxmoxClient testClient = new ProxmoxClient(
                        apiUrl, creds.getTokenId(), creds.getTokenSecret(), ignoreSslErrors);
                String version = testClient.getVersion();
                int majorVersion = parseMajorVersion(version);
                if (majorVersion > 0 && majorVersion < MIN_PVE_VERSION) {
                    return FormValidation.error(
                            "Connected to Proxmox VE %s - version %d+ is required",
                            version, MIN_PVE_VERSION);
                }
                if (majorVersion > MAX_KNOWN_PVE_VERSION) {
                    return FormValidation.warning(
                            "Connected to Proxmox VE %s - this plugin has been tested with PVE %d. "
                            + "Please report any issues.", version, MAX_KNOWN_PVE_VERSION);
                }
                return FormValidation.ok("Connected to Proxmox VE " + version);
            } catch (ProxmoxAuthenticationException e) {
                ProxmoxTokenCredentials creds = CredentialsMatchers.firstOrNull(
                        CredentialsProvider.lookupCredentialsInItemGroup(
                                ProxmoxTokenCredentials.class, Jenkins.get(), null, Collections.emptyList()),
                        CredentialsMatchers.withId(credentialsId));
                String tokenId = creds != null ? creds.getTokenId() : "unknown";
                return FormValidation.error(
                        "Authentication failed (token ID: " + tokenId + "). "
                        + "Verify with: curl -k -H 'Authorization: PVEAPIToken="
                        + tokenId + "=<secret>' " + apiUrl + "/api2/json/version");
            } catch (ProxmoxException e) {
                return FormValidation.error("Connection failed: " + e.getMessage());
            }
        }

        @POST
        public ListBoxModel doFillCredentialsIdItems() {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            StandardListBoxModel model = new StandardListBoxModel();
            model.includeEmptyValue();
            model.includeAs(jenkins.model.Jenkins.get().getACL().SYSTEM2,
                    Jenkins.get(),
                    ProxmoxTokenCredentials.class);
            return model;
        }

        public FormValidation doCheckApiUrl(@QueryParameter String value) {
            if (value == null || value.isBlank()) {
                return FormValidation.error("API URL is required");
            }
            if (!value.startsWith("https://") && !value.startsWith("http://")) {
                return FormValidation.error("URL must start with https:// or http://");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckStartVmId(@QueryParameter int value) {
            if (value < 0) {
                return FormValidation.error("Must be non-negative (0 = default)");
            }
            if (value >= 1 && value < 100) {
                return FormValidation.error("Proxmox reserves VM IDs 1-99");
            }
            return FormValidation.ok();
        }

        public FormValidation doCheckOrphanCleanupGracePeriodSeconds(@QueryParameter int value) {
            if (value < 1) {
                return FormValidation.error("Must be at least 1 second");
            }
            return FormValidation.ok();
        }

        private static int parseMajorVersion(String version) {
            if (version == null || version.isBlank()) return 0;
            try {
                int dot = version.indexOf('.');
                String major = dot > 0 ? version.substring(0, dot) : version;
                return Integer.parseInt(major);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
    }
}
