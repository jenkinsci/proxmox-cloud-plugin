package org.jenkinsci.plugins.proxmox;

import com.google.gson.JsonObject;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.proxmox.api.ProxmoxClient;
import org.jenkinsci.plugins.proxmox.api.ProxmoxException;
import org.jenkinsci.plugins.proxmox.api.model.VirtualMachine;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class ProxmoxOrphanCleanup extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxOrphanCleanup.class.getName());
    private static final long RECURRENCE_MS = 15 * 60 * 1000;
    private static final long MIN_AGE_MS = 10 * 60 * 1000;

    public ProxmoxOrphanCleanup() {
        super("Proxmox Orphan Cleanup");
    }

    @Override
    public long getRecurrencePeriod() {
        return RECURRENCE_MS;
    }

    @Override
    protected void execute(TaskListener listener) {
        Jenkins jenkins = Jenkins.get();

        for (Cloud cloud : jenkins.clouds) {
            if (!(cloud instanceof ProxmoxCloud proxmoxCloud)) continue;
            if (!proxmoxCloud.isCleanupOrphanedAgents()) continue;

            try {
                cleanupCloud(proxmoxCloud, jenkins);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Orphan cleanup failed for cloud " + proxmoxCloud.name, e);
            }
        }
    }

    private void cleanupCloud(ProxmoxCloud cloud, Jenkins jenkins) {
        ProxmoxClient client = cloud.getClient();
        String marker = "jenkins-managed;cloud:" + cloud.name;

        Set<Integer> knownVmIds = new HashSet<>();
        for (var node : jenkins.getNodes()) {
            if (node instanceof ProxmoxAgent agent && cloud.name.equals(agent.getCloudName())) {
                knownVmIds.add(agent.getVmId());
            }
        }

        Set<String> checkedNodes = new HashSet<>();
        for (var template : cloud.getTemplates()) {
            String nodeName = template.getNode();
            if (!checkedNodes.add(nodeName)) continue;

            List<VirtualMachine> vms;
            try {
                vms = client.getVms(nodeName);
            } catch (ProxmoxException e) {
                LOGGER.log(Level.WARNING, "Failed to list VMs on node " + nodeName, e);
                continue;
            }

            for (VirtualMachine vm : vms) {
                if (vm.isTemplate()) continue;
                if (knownVmIds.contains(vm.vmid())) continue;

                try {
                    JsonObject config = client.getVmConfig(nodeName, vm.vmid());
                    String description = config.has("description") ? config.get("description").getAsString() : "";
                    if (!description.contains(marker)) continue;

                    long uptimeSeconds = getUptime(client, nodeName, vm.vmid());
                    if (uptimeSeconds * 1000 < MIN_AGE_MS) continue;

                    LOGGER.info("Destroying orphaned VM " + vm.vmid() + " (" + vm.name() + ") on " + nodeName);
                    try {
                        String upid = client.stopVm(nodeName, vm.vmid());
                        client.waitForTask(nodeName, upid, 60);
                    } catch (ProxmoxException e) {
                        LOGGER.log(Level.FINE, "Stop failed (VM may already be stopped)", e);
                    }
                    String upid = client.destroyVm(nodeName, vm.vmid(), true);
                    client.waitForTask(nodeName, upid, cloud.getOperationTimeout());
                } catch (ProxmoxException e) {
                    LOGGER.log(Level.WARNING, "Failed to check/destroy VM " + vm.vmid(), e);
                }
            }
        }
    }

    private long getUptime(ProxmoxClient client, String node, int vmId) {
        try {
            VirtualMachine status = client.getVmStatus(node, vmId);
            if (!"running".equals(status.status())) return Long.MAX_VALUE / 1000;
        } catch (ProxmoxException e) {
            LOGGER.log(Level.FINE, "Could not get status for VM " + vmId, e);
        }
        return Long.MAX_VALUE / 1000;
    }
}
