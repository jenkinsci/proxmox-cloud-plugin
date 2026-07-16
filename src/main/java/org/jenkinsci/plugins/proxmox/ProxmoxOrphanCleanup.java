package org.jenkinsci.plugins.proxmox;

import com.google.gson.JsonObject;
import hudson.Extension;
import hudson.ExtensionList;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class ProxmoxOrphanCleanup extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxOrphanCleanup.class.getName());
    /**
     * Optional global override (ms). When set it forces both the firing cadence and the per-cloud
     * reconcile period for every cloud, ignoring the per-cloud {@code orphanCleanupPeriodSeconds}.
     * Handy for tests/ops to force a uniform fast cadence; mirrors the EC2 plugin's
     * jenkins.ec2.checkAlivePeriod escape hatch.
     */
    private static final Long PERIOD_OVERRIDE_MS = Long.getLong("jenkins.proxmox.orphanCleanupPeriodMs");

    /** Never fire (or poll the Proxmox API) more than twice a minute, whatever the configured period. */
    private static final long MIN_BASE_MS = TimeUnit.SECONDS.toMillis(30);

    /** Base cadence when no cleanup-enabled cloud is configured yet (matches the field default). */
    private static final long DEFAULT_PERIOD_MS = TimeUnit.MINUTES.toMillis(10);

    // When each cloud was last reconciled (keyed by cloud name). The work fires on the base cadence
    // (the smallest configured period) and gates each cloud here on its own period, so per-cloud edits
    // take effect without a restart for any value at or above the startup base.
    private final Map<String, Long> lastCleanupByCloud = new ConcurrentHashMap<>();

    // The period (ms) Jenkins actually scheduled the fixed-rate timer with, captured on the first
    // getRecurrencePeriod() call. -1 until scheduled. Compared against live config so the restart
    // monitor can warn when an edit reduced the period below the cadence the work is running at.
    private volatile long scheduledPeriodMs = -1;

    public ProxmoxOrphanCleanup() {
        super("Proxmox Orphan Cleanup");
    }

    /**
     * Fire on the smallest configured cloud period (floored at {@link #MIN_BASE_MS}), or the override
     * when set. Jenkins reads this once when scheduling the work; per-cloud gating in {@link #execute}
     * then applies any later edit at or above this base. Lowering a cloud's period below the startup
     * base only fires faster after a restart, which {@link ProxmoxOrphanCleanupRestartMonitor} surfaces.
     */
    @Override
    public long getRecurrencePeriod() {
        long period = computeBasePeriod();
        if (scheduledPeriodMs < 0) {
            // Capture the value Jenkins schedules the fixed-rate timer with, so the restart monitor can
            // detect a later edit that reduced the period below the running cadence.
            scheduledPeriodMs = period;
        }
        return period;
    }

    /** The base cadence implied by current config: smallest cleanup-enabled period (floored), or override. */
    long computeBasePeriod() {
        if (PERIOD_OVERRIDE_MS != null) {
            return PERIOD_OVERRIDE_MS;
        }
        long smallest = Long.MAX_VALUE;
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins != null) {
            for (Cloud cloud : jenkins.clouds) {
                if (cloud instanceof ProxmoxCloud proxmoxCloud && proxmoxCloud.isCleanupOrphanedAgents()) {
                    smallest = Math.min(smallest, (long) proxmoxCloud.getOrphanCleanupPeriodSeconds() * 1000);
                }
            }
        }
        if (smallest == Long.MAX_VALUE) {
            smallest = DEFAULT_PERIOD_MS;
        }
        return Math.max(smallest, MIN_BASE_MS);
    }

    @Override
    protected void execute(TaskListener listener) {
        Jenkins jenkins = Jenkins.get();
        long now = System.currentTimeMillis();

        for (Cloud cloud : jenkins.clouds) {
            if (!(cloud instanceof ProxmoxCloud proxmoxCloud)) continue;
            if (!proxmoxCloud.isCleanupOrphanedAgents()) continue;

            long periodMs = PERIOD_OVERRIDE_MS != null
                    ? PERIOD_OVERRIDE_MS
                    : (long) proxmoxCloud.getOrphanCleanupPeriodSeconds() * 1000;
            if (!isDue(lastCleanupByCloud.get(proxmoxCloud.name), now, periodMs)) {
                continue;
            }
            // Record the attempt before running so a cloud whose cleanup throws waits a full period
            // rather than retrying every base tick.
            lastCleanupByCloud.put(proxmoxCloud.name, now);

            try {
                cleanupCloud(proxmoxCloud, jenkins);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Orphan cleanup failed for cloud " + proxmoxCloud.name, e);
            }
        }

        // Warm-pool top-up runs every tick regardless of the per-cloud cleanup toggle (issue #20): it
        // provisions rather than reconciles. Async, so provisioning never blocks the reconcile thread
        // (the analog of the EC2 plugin's EC2SlaveMonitor.execute -> MinimumInstanceChecker.scheduleCheck).
        ProxmoxMinimumInstances.scheduleCheck();
    }

    /** Whether a cloud is due for reconcile: never run before, or its period has elapsed. Pure for testing. */
    static boolean isDue(Long lastRun, long nowMs, long periodMs) {
        return lastRun == null || nowMs - lastRun >= periodMs;
    }

    /** The singleton instance Jenkins registered. */
    static ProxmoxOrphanCleanup get() {
        return ExtensionList.lookupSingleton(ProxmoxOrphanCleanup.class);
    }

    /**
     * Whether a cloud's cleanup period was reduced below the cadence the work is currently scheduled
     * at, so the faster cadence needs a Jenkins restart to take effect. False when an override forces
     * the period, or before the work has been scheduled. Increasing a period never needs a restart
     * (the work just fires more often than needed, which the per-cloud gating absorbs).
     */
    boolean isRestartNeededForReducedPeriod() {
        if (PERIOD_OVERRIDE_MS != null) {
            return false;
        }
        long scheduled = scheduledPeriodMs;
        return scheduled > 0 && computeBasePeriod() < scheduled;
    }

    /** The cadence (seconds) the work is currently scheduled at (what Jenkins is actually using). */
    public long getScheduledPeriodSeconds() {
        return scheduledPeriodMs / 1000;
    }

    /** The smallest cleanup-enabled period (seconds) currently configured. */
    public long getEffectivePeriodSeconds() {
        return computeBasePeriod() / 1000;
    }

    /** Names of cleanup-enabled clouds whose configured period is below the running cadence. */
    List<String> cloudsNeedingRestart() {
        List<String> names = new ArrayList<>();
        long scheduled = scheduledPeriodMs;
        if (PERIOD_OVERRIDE_MS != null || scheduled <= 0) {
            return names;
        }
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins != null) {
            for (Cloud cloud : jenkins.clouds) {
                if (cloud instanceof ProxmoxCloud proxmoxCloud && proxmoxCloud.isCleanupOrphanedAgents()
                        && Math.max((long) proxmoxCloud.getOrphanCleanupPeriodSeconds() * 1000, MIN_BASE_MS)
                                < scheduled) {
                    names.add(proxmoxCloud.name);
                }
            }
        }
        return names;
    }

    void cleanupCloud(ProxmoxCloud cloud, Jenkins jenkins) {
        ProxmoxClient client = cloud.getClient();
        String marker = "jenkins-managed;cloud:" + cloud.name;
        long graceMs = (long) cloud.getOrphanCleanupGracePeriodSeconds() * 1000;

        // Agents Jenkins currently knows about for this cloud, and when each went offline (captured
        // once here so the pure findDeadNodes decision stays testable).
        List<ProxmoxAgent> agents = new ArrayList<>();
        Set<Integer> knownVmIds = new HashSet<>();
        Map<ProxmoxAgent, Long> offlineSinceByAgent = new HashMap<>();
        for (var node : jenkins.getNodes()) {
            if (node instanceof ProxmoxAgent agent && cloud.name.equals(agent.getCloudName())) {
                agents.add(agent);
                knownVmIds.add(agent.getVmId());
                offlineSinceByAgent.put(agent, agent.getOfflineSince());
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

                // Never destroy a VM that a provision is mid-flight on. Between clone and start the
                // VM is stopped and has no node yet, so the running-grace below would not protect it
                // and it would be reaped out from under the provisioning thread (qmstart then fails
                // with "NNN.conf does not exist"). reserveVmId/releaseVmId bracket the whole clone
                // -> configure -> start, so the id is reserved for exactly that window.
                if (cloud.isVmIdReserved(vm.vmid())) continue;

                // Protect a freshly-started VM whose agent has not registered yet: skip a running VM
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
        for (DeadAgent da : findDeadNodes(agents, vmStatusByNode, offlineSinceByAgent,
                System.currentTimeMillis(), graceMs)) {
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
                LOGGER.fine("Destroying VM " + agent.getVmId() + " on " + agent.getProxmoxNode()
                        + " backing dead agent " + agent.getNodeName() + " (" + da.reason() + ")");
                destroyVm(client, cloud, agent.getProxmoxNode(), agent.getVmId());
            }
            String detail = switch (da.reason()) {
                case VM_GONE -> "backing VM no longer exists";
                case VM_STOPPED -> "backing VM was not running, destroyed it";
                case CHANNEL_OFFLINE -> "agent channel offline beyond grace, destroyed its still-running VM";
            };
            LOGGER.info("Removing Proxmox agent " + agent.getNodeName() + " (VM " + agent.getVmId()
                    + " on " + agent.getProxmoxNode() + "): " + detail);
            try {
                jenkins.removeNode(agent);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to remove dead agent " + agent.getNodeName(), e);
            }
        }
    }

    /** Why an agent is considered dead, for accurate teardown logging. */
    enum DeadReason {
        /** The backing VM is gone from Proxmox (node-only removal). */
        VM_GONE,
        /** The backing VM is present but not running (destroy then remove). */
        VM_STOPPED,
        /** The VM is still running but the agent's channel has been offline beyond grace (issue #16). */
        CHANNEL_OFFLINE
    }

    /** A dead agent: {@code vmExists} is false only for {@link DeadReason#VM_GONE}. */
    record DeadAgent(ProxmoxAgent agent, boolean vmExists, DeadReason reason) {}

    /**
     * Pure decision: which agents are dead and should be torn down. An agent is dead when, past the
     * removal grace period (using the persisted {@code createdAt} to avoid racing a freshly-booting
     * agent), either its backing VM is gone or not running, OR the VM is still running but the agent's
     * channel has been offline beyond the grace window (issue #16: a transient blip or interrupted
     * launch can strand an offline agent over a still-running VM, which the run-state alone treats as
     * healthy). An online or briefly-offline agent over a running VM is left alone.
     *
     * @param vmStatusByNode      live VM status keyed by Proxmox node then VM id; only nodes listed
     *                            successfully are present, so an agent whose node is absent is left alone
     * @param offlineSinceByAgent when each agent's channel went offline (epoch ms), or {@code -1}/absent
     *                            if online or still connecting (see {@link ProxmoxAgent#getOfflineSince()})
     */
    static List<DeadAgent> findDeadNodes(List<ProxmoxAgent> agents,
                                         Map<String, Map<Integer, String>> vmStatusByNode,
                                         Map<ProxmoxAgent, Long> offlineSinceByAgent,
                                         long nowMs, long minAgeMs) {
        List<DeadAgent> dead = new ArrayList<>();
        for (ProxmoxAgent agent : agents) {
            Map<Integer, String> nodeVms = vmStatusByNode.get(agent.getProxmoxNode());
            if (nodeVms == null) continue;                          // listing failed → don't act
            if (nowMs - agent.getCreatedAt() < minAgeMs) continue;  // within grace period → don't act
            String status = nodeVms.get(agent.getVmId());
            if (status == null) {
                dead.add(new DeadAgent(agent, false, DeadReason.VM_GONE));
            } else if (!"running".equals(status)) {
                dead.add(new DeadAgent(agent, true, DeadReason.VM_STOPPED));
            } else {
                // VM running: dead only if the channel has been offline beyond the grace window.
                Long offlineSince = offlineSinceByAgent.get(agent);
                if (offlineSince != null && ProxmoxAgent.isOfflineDead(offlineSince, nowMs, minAgeMs)) {
                    dead.add(new DeadAgent(agent, true, DeadReason.CHANNEL_OFFLINE));  // offline zombie
                }
            }
        }
        return dead;
    }

    private void destroyVm(ProxmoxClient client, ProxmoxCloud cloud, String nodeName, int vmId) {
        // Ids are reused, so verify the VM still carries our marker before destroying it: a stale
        // call must not kill a VM whose id was re-cloned by a newer agent or created manually.
        if (!ProxmoxVms.confirmOwnedByCloud(client, nodeName, vmId, cloud.name)) {
            LOGGER.fine("VM " + vmId + " on " + nodeName + " is gone or no longer managed by cloud "
                    + cloud.name + "; skipping destroy");
            return;
        }
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
            if (ProxmoxVms.isAlreadyGone(e)) {
                LOGGER.fine("VM " + vmId + " on " + nodeName + " vanished during destroy; treating as done");
                return;
            }
            LOGGER.log(Level.WARNING, "Failed to destroy VM " + vmId + " on " + nodeName, e);
        }
    }
}
