package org.jenkinsci.plugins.proxmox;

import com.google.gson.JsonObject;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Computer;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.proxmox.api.ProxmoxClient;
import org.jenkinsci.plugins.proxmox.api.ProxmoxException;
import org.jenkinsci.plugins.proxmox.api.model.VirtualMachine;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class ProxmoxOrphanCleanup extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxOrphanCleanup.class.getName());
    // Cadence mirrors the EC2 plugin's EC2SlaveMonitor (10 min), overridable like its
    // jenkins.ec2.checkAlivePeriod system property.
    private static final long RECURRENCE_MS =
            Long.getLong("jenkins.proxmox.orphanCleanupPeriodMs", TimeUnit.MINUTES.toMillis(10));

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

    void cleanupCloud(ProxmoxCloud cloud, Jenkins jenkins) {
        ProxmoxClient client = cloud.getClient();
        String marker = "jenkins-managed;cloud:" + cloud.name;
        long graceMs = (long) cloud.getOrphanCleanupGracePeriodSeconds() * 1000;

        // Agents Jenkins currently knows about for this cloud.
        List<ProxmoxAgent> agents = new ArrayList<>();
        Set<Integer> knownVmIds = new HashSet<>();
        for (var node : jenkins.getNodes()) {
            if (node instanceof ProxmoxAgent agent && cloud.name.equals(agent.getCloudName())) {
                agents.add(agent);
                knownVmIds.add(agent.getVmId());
            }
        }

        // Proxmox nodes to inspect: union of template nodes and the nodes our agents live on,
        // so an agent on a node not covered by any template is still reconciled.
        Set<String> nodeNames = new LinkedHashSet<>();
        for (var template : cloud.getTemplates()) {
            nodeNames.add(template.getNode());
        }
        for (ProxmoxAgent agent : agents) {
            nodeNames.add(agent.getProxmoxNode());
        }

        // Fetch each node's VM list once. Only nodes we queried successfully end up in the maps;
        // a node whose listing failed is therefore never used to conclude a VM is gone or stopped.
        Map<String, List<VirtualMachine>> vmsByNode = new HashMap<>();
        Map<String, Map<Integer, String>> vmStatusByNode = new HashMap<>();
        for (String nodeName : nodeNames) {
            try {
                List<VirtualMachine> vms = client.getVms(nodeName);
                vmsByNode.put(nodeName, vms);
                Map<Integer, String> statusById = new HashMap<>();
                for (VirtualMachine vm : vms) {
                    statusById.put(vm.vmid(), vm.status());
                }
                vmStatusByNode.put(nodeName, statusById);
            } catch (ProxmoxException e) {
                LOGGER.log(Level.WARNING, "Failed to list VMs on node " + nodeName, e);
            }
        }

        // Pass 1: destroy orphaned VMs (jenkins-managed, with no corresponding Jenkins node).
        for (Map.Entry<String, List<VirtualMachine>> entry : vmsByNode.entrySet()) {
            String nodeName = entry.getKey();
            for (VirtualMachine vm : entry.getValue()) {
                if (vm.isTemplate()) continue;
                if (knownVmIds.contains(vm.vmid())) continue;

                String description;
                try {
                    JsonObject config = client.getVmConfig(nodeName, vm.vmid());
                    description = config.has("description") ? config.get("description").getAsString() : "";
                } catch (ProxmoxException e) {
                    LOGGER.log(Level.WARNING, "Failed to read config for VM " + vm.vmid() + " on " + nodeName, e);
                    continue;
                }
                if (!description.contains(marker)) continue;

                // Protect a freshly-cloned VM whose agent has not registered yet: skip a running VM
                // still within the grace period. A non-running orphan is destroyed regardless.
                if ("running".equals(vm.status()) && vm.uptime() * 1000L < graceMs) continue;

                LOGGER.fine("Destroying orphaned VM " + vm.vmid() + " (" + vm.name() + ") on " + nodeName);
                destroyVm(client, cloud, nodeName, vm.vmid());
            }
        }

        // Pass 2: reconcile dead Jenkins nodes against live VM state. The analog of the EC2 plugin's
        // EC2SlaveMonitor; removing EphemeralNode means stale agents would otherwise persist forever.
        // Unlike EC2 (which keeps stopped instances for its stop/start reuse feature), this plugin
        // never reuses VMs, so a stopped agent VM is also dead and is destroyed before node removal.
        for (DeadAgent da : findDeadNodes(agents, vmStatusByNode, System.currentTimeMillis(), graceMs)) {
            ProxmoxAgent agent = da.agent();
            Computer computer = agent.toComputer();
            if (computer != null && computer.isOnline()) {
                // A live channel implies a running VM; treat a not-running listing as a transient
                // glitch and wait for the next cycle rather than tear down a busy agent.
                LOGGER.fine("Skipping " + agent.getNodeName() + " - VM " + agent.getVmId()
                        + " not running per listing but computer is online");
                continue;
            }
            if (da.vmExists()) {
                LOGGER.fine("Destroying not-running VM " + agent.getVmId() + " on " + agent.getProxmoxNode()
                        + " backing agent " + agent.getNodeName());
                destroyVm(client, cloud, agent.getProxmoxNode(), agent.getVmId());
            }
            LOGGER.info("Removing Proxmox agent " + agent.getNodeName() + " - backing VM " + agent.getVmId()
                    + (da.vmExists() ? " was not running" : " no longer exists") + " on " + agent.getProxmoxNode());
            try {
                jenkins.removeNode(agent);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to remove dead agent " + agent.getNodeName(), e);
            }
        }
    }

    /** An agent whose backing VM is gone ({@code vmExists=false}) or present but not running. */
    record DeadAgent(ProxmoxAgent agent, boolean vmExists) {}

    /**
     * Pure decision: which agents back onto a VM that is gone or not running, and are past the
     * removal grace period. The grace window (using the persisted {@code createdAt}) avoids racing
     * a VM that is briefly stopped while a freshly-provisioned agent boots.
     *
     * @param vmStatusByNode live VM status keyed by Proxmox node then VM id; only nodes listed
     *                       successfully are present, so an agent whose node is absent is left alone
     */
    static List<DeadAgent> findDeadNodes(List<ProxmoxAgent> agents,
                                         Map<String, Map<Integer, String>> vmStatusByNode,
                                         long nowMs, long minAgeMs) {
        List<DeadAgent> dead = new ArrayList<>();
        for (ProxmoxAgent agent : agents) {
            Map<Integer, String> nodeVms = vmStatusByNode.get(agent.getProxmoxNode());
            if (nodeVms == null) continue;                          // listing failed → don't act
            if (nowMs - agent.getCreatedAt() < minAgeMs) continue;  // within grace period → don't act
            String status = nodeVms.get(agent.getVmId());
            if (status == null) {
                dead.add(new DeadAgent(agent, false));              // VM gone
            } else if (!"running".equals(status)) {
                dead.add(new DeadAgent(agent, true));               // VM present but not running
            }
        }
        return dead;
    }

    private void destroyVm(ProxmoxClient client, ProxmoxCloud cloud, String nodeName, int vmId) {
        try {
            try {
                String upid = client.stopVm(nodeName, vmId);
                client.waitForTask(nodeName, upid, 60);
            } catch (ProxmoxException e) {
                LOGGER.log(Level.FINE, "Stop failed (VM may already be stopped)", e);
            }
            String upid = client.destroyVm(nodeName, vmId, true);
            client.waitForTask(nodeName, upid, cloud.getOperationTimeout());
        } catch (ProxmoxException e) {
            LOGGER.log(Level.WARNING, "Failed to destroy VM " + vmId + " on " + nodeName, e);
        }
    }
}
