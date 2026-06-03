package org.jenkinsci.plugins.proxmox;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.EphemeralNode;
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

public class ProxmoxAgent extends AbstractCloudSlave implements EphemeralNode {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxAgent.class.getName());

    private final String cloudName;
    private final String templateName;
    private final String proxmoxNode;
    private final int vmId;
    private final long createdAt;
    private final java.util.concurrent.atomic.AtomicInteger totalUses = new java.util.concurrent.atomic.AtomicInteger();

    public ProxmoxAgent(String name, String remoteFs, int numExecutors, Node.Mode mode,
                         String labelString, ProxmoxLauncher launcher,
                         ProxmoxRetentionStrategy retentionStrategy,
                         String cloudName, String templateName, String proxmoxNode,
                         int vmId) throws Descriptor.FormException, IOException {
        super(name, remoteFs, launcher);
        setNumExecutors(numExecutors);
        setMode(mode);
        setLabelString(labelString);
        setRetentionStrategy(retentionStrategy);
        this.cloudName = cloudName;
        this.templateName = templateName;
        this.proxmoxNode = proxmoxNode;
        this.vmId = vmId;
        this.createdAt = System.currentTimeMillis();
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
    public Node asNode() {
        return this;
    }

    @Override
    @SuppressWarnings("unchecked")
    public Node reconfigure(StaplerRequest2 req, JSONObject form) throws Descriptor.FormException {
        if (form == null) {
            return null;
        }
        try {
            setNodeDescription(form.optString("nodeDescription", ""));
            setNumExecutors(form.optInt("numExecutors", 1));
            setLabelString(form.optString("labelString", ""));
            setMode(Mode.valueOf(form.optString("mode", Mode.NORMAL.name())));

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

    public long getCreatedAt() {
        return createdAt;
    }

    public int getTotalUses() {
        return totalUses.get();
    }

    public void incrementUses() {
        totalUses.incrementAndGet();
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
