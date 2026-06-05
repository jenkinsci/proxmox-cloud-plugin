package org.jenkinsci.plugins.proxmox;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.NodeProperty;
import hudson.slaves.NodePropertyDescriptor;
import hudson.util.DescribableList;
import jenkins.model.Jenkins;

import net.sf.json.JSONObject;
import org.kohsuke.stapler.StaplerRequest2;
import org.jenkinsci.plugins.proxmox.api.ProxmoxClient;
import org.jenkinsci.plugins.proxmox.api.ProxmoxException;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ProxmoxAgent extends AbstractCloudSlave {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxAgent.class.getName());

    private final String cloudName;
    private final String templateName;
    private final String proxmoxNode;
    private final int vmId;
    private final long createdAt;
    // Lifecycle settings owned by the agent (seeded from the template at provision time) so they can
    // be overridden per-agent from its config page — e.g. to keep one VM alive for diagnostics. The
    // stateless ProxmoxRetentionStrategy reads these live. volatile (not AtomicInteger): they are set
    // from the stapler request thread and read from the retention-check thread, both plain
    // assignments with no read-modify-write, so visibility is all that's needed.
    private volatile int idleTerminationMinutes;
    private volatile int maxTotalUses;
    // Plain int (not AtomicInteger): the node is now persisted, and Jenkins' XStream2 refuses to
    // marshal AtomicInteger. Thread safety comes from the synchronized accessors below.
    private int totalUses;

    public ProxmoxAgent(String name, String remoteFs, int numExecutors, Node.Mode mode,
                         String labelString, ProxmoxLauncher launcher,
                         String cloudName, String templateName, String proxmoxNode,
                         int vmId, int idleTerminationMinutes, int maxTotalUses)
            throws Descriptor.FormException, IOException {
        super(name, remoteFs, launcher);
        setNumExecutors(numExecutors);
        setMode(mode);
        setLabelString(labelString);
        this.cloudName = cloudName;
        this.templateName = templateName;
        this.proxmoxNode = proxmoxNode;
        this.vmId = vmId;
        this.idleTerminationMinutes = idleTerminationMinutes;
        this.maxTotalUses = maxTotalUses;
        this.createdAt = System.currentTimeMillis();
        setRetentionStrategy(new ProxmoxRetentionStrategy());
    }

    @Override
    public AbstractCloudComputer<?> createComputer() {
        return new ProxmoxComputer(this);
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        LOGGER.fine("Terminating agent " + getNodeName() + " (VM " + vmId + " on " + proxmoxNode + ")");

        ProxmoxCloud cloud = getCloud();
        if (cloud == null) {
            LOGGER.warning("Cloud " + cloudName + " not found, cannot terminate VM " + vmId);
            return;
        }

        ProxmoxClient client = cloud.getClient();
        try {
            try {
                String upid = client.shutdownVm(proxmoxNode, vmId, 30);
                client.waitForTask(proxmoxNode, upid, 60);
            } catch (ProxmoxException e) {
                LOGGER.log(Level.FINE, "Graceful shutdown failed, forcing stop", e);
                String upid = client.stopVm(proxmoxNode, vmId);
                client.waitForTask(proxmoxNode, upid, 60);
            }

            String upid = client.destroyVm(proxmoxNode, vmId, true);
            client.waitForTask(proxmoxNode, upid, cloud.getOperationTimeout());
            LOGGER.fine("VM " + vmId + " destroyed on " + proxmoxNode);
        } catch (ProxmoxException e) {
            LOGGER.log(Level.WARNING, "Failed to terminate VM " + vmId, e);
            throw new IOException("Failed to terminate VM " + vmId, e);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public Node reconfigure(StaplerRequest2 req, JSONObject form) throws Descriptor.FormException {
        if (form == null) {
            return null;
        }
        int idle = form.optInt("idleTerminationMinutes", idleTerminationMinutes);
        int maxUses = form.optInt("maxTotalUses", maxTotalUses);
        if (idle < 0) {
            throw new Descriptor.FormException("Idle termination minutes must be non-negative",
                    "idleTerminationMinutes");
        }
        if (maxUses < 0) {
            throw new Descriptor.FormException("Max total uses must be non-negative", "maxTotalUses");
        }
        // instanceCap is a template-level fleet limit, shown read-only here; never read it back.

        try {
            setNodeDescription(form.optString("nodeDescription", ""));
            setNumExecutors(form.optInt("numExecutors", 1));
            setLabelString(form.optString("labelString", ""));
            setMode(Mode.valueOf(form.optString("mode", Mode.NORMAL.name())));
            this.idleTerminationMinutes = idle;
            this.maxTotalUses = maxUses;

            if (form.has("nodeProperties")) {
                DescribableList<NodeProperty<?>, NodePropertyDescriptor> props = getNodeProperties();
                props.rebuild(req, form.optJSONObject("nodeProperties"),
                        NodeProperty.all());
            }
        } catch (IOException e) {
            throw new Descriptor.FormException("Failed to save agent configuration: " + e.getMessage(),
                    e, "nodeDescription");
        }

        return this;
    }

    public ProxmoxCloud getCloud() {
        Jenkins jenkins = Jenkins.get();
        return (ProxmoxCloud) jenkins.getCloud(cloudName);
    }

    public String getCloudName() {
        return cloudName;
    }

    public String getTemplateName() {
        return templateName;
    }

    public String getProxmoxNode() {
        return proxmoxNode;
    }

    public int getVmId() {
        return vmId;
    }

    public int getIdleTerminationMinutes() {
        return idleTerminationMinutes;
    }

    public int getMaxTotalUses() {
        return maxTotalUses;
    }

    /**
     * Template-level instance cap, shown read-only on the agent config page. Reads through to the
     * owning template; returns 0 (no limit) if the cloud or template no longer exists.
     */
    public int getInstanceCap() {
        ProxmoxCloud cloud = getCloud();
        if (cloud == null) {
            return 0;
        }
        ProxmoxTemplate template = cloud.getTemplateByName(templateName);
        return template != null ? template.getInstanceCap() : 0;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public synchronized int getTotalUses() {
        return totalUses;
    }

    public synchronized void incrementUses() {
        totalUses++;
    }

    @Extension
    public static class DescriptorImpl extends SlaveDescriptor {

        @Override
        public String getDisplayName() {
            return "Proxmox Agent";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }
}
