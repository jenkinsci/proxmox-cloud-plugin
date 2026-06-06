package org.jenkinsci.plugins.proxmox;

import hudson.slaves.RetentionStrategy;

import java.util.logging.Level;
import java.util.logging.Logger;

public class ProxmoxRetentionStrategy extends RetentionStrategy<ProxmoxComputer> {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxRetentionStrategy.class.getName());

    // Stateless: the idle-timeout and max-uses limits live on the ProxmoxAgent (seeded from the
    // template, overridable per-agent) and are read live below, so an edit on the agent config page
    // takes effect on the next check without rebuilding the strategy.
    public ProxmoxRetentionStrategy() {
    }

    /**
     * Gate dispatch the instant the reuse cap is reached. The queue consults this at dispatch time
     * (far more often than {@link #check}, which runs ~once a minute), so it closes the race where a
     * capped agent could still be handed new builds between periodic checks.
     */
    @Override
    public boolean isAcceptingTasks(ProxmoxComputer c) {
        ProxmoxAgent agent = c.getNode();
        if (agent == null) {
            return true;
        }
        return acceptsMoreTasks(agent.getMaxTotalUses(), agent.getTotalUses(), c.countBusy());
    }

    @Override
    public long check(ProxmoxComputer c) {
        if (c.isOffline() && !c.isConnecting()) {
            return 1;
        }

        ProxmoxAgent agent = c.getNode();
        if (agent == null) {
            return 1;
        }

        if (shouldTerminateForMaxUses(agent.getMaxTotalUses(), agent.getTotalUses(), c.isIdle())) {
            LOGGER.fine("Agent " + c.getName() + " reached max uses (" + agent.getMaxTotalUses()
                    + ") and is idle, terminating");
            terminate(agent);
            // A used-up warm-pool agent has just been recycled; restore the minimum promptly rather
            // than waiting for the next periodic top-up (mirrors EC2's taskAccepted maxTotalUses path).
            ProxmoxMinimumInstances.scheduleCheck();
            return 1;
        }

        int idleTerminationMinutes = agent.getIdleTerminationMinutes();
        if (idleTerminationMinutes > 0 && c.isIdle()) {
            long idleMs = System.currentTimeMillis() - c.getIdleStartMilliseconds();
            long thresholdMs = (long) idleTerminationMinutes * 60 * 1000;
            if (idleMs > thresholdMs) {
                if (keepsWarmPoolMinimum(agent)) {
                    LOGGER.fine("Agent " + c.getName()
                            + " idle past threshold but retained for the template warm-pool minimum");
                } else {
                    LOGGER.fine("Agent " + c.getName() + " idle for " + (idleMs / 60000)
                            + " minutes, terminating");
                    terminate(agent);
                }
            }
        }

        return 1;
    }

    @Override
    public void start(ProxmoxComputer c) {
        c.connect(false);
    }

    private void terminate(ProxmoxAgent agent) {
        try {
            agent.terminate();
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to terminate agent " + agent.getNodeName(), e);
        }
    }

    /**
     * Whether an agent can accept another task. In-flight builds ({@code busy}) are counted alongside
     * completed uses because the use count only rises at task completion (see {@link
     * ProxmoxBuildListener}); without it a multi-executor agent could dispatch several tasks that all
     * slip under the cap before any completes. The reads are intentionally non-atomic: under the
     * queue lock at dispatch time {@code busy} is stable, and the accept side is conservative, so the
     * cap can never overshoot. Package-private and pure for unit testing.
     */
    static boolean acceptsMoreTasks(int maxTotalUses, int totalUses, int busy) {
        if (maxTotalUses <= 0) {
            return true; // unlimited reuse
        }
        return totalUses + busy < maxTotalUses;
    }

    /**
     * Whether {@link #check} should terminate a capped agent: only once the cap is reached AND the
     * agent is idle, so an in-flight build on a just-capped agent drains rather than being killed
     * mid-run. Package-private and pure for unit testing.
     */
    static boolean shouldTerminateForMaxUses(int maxTotalUses, int totalUses, boolean idle) {
        return maxTotalUses > 0 && totalUses >= maxTotalUses && idle;
    }

    /**
     * Whether this idle agent must be kept to preserve its template's warm-pool minimum. Resolved live
     * from the agent's cloud and template; if either is gone, the agent is not retained (reaped as
     * before). Uses {@link ProxmoxTemplate#getNumActiveAgents}, which excludes offline-dead nodes, so a
     * zombie cannot count toward the minimum.
     */
    private boolean keepsWarmPoolMinimum(ProxmoxAgent agent) {
        ProxmoxCloud cloud = agent.getCloud();
        if (cloud == null) {
            return false;
        }
        ProxmoxTemplate template = cloud.getTemplateByName(agent.getTemplateName());
        if (template == null) {
            return false;
        }
        return retainsMinimum(template.getNumActiveAgents(cloud), template.getInstanceMin());
    }

    /**
     * Whether an idle agent is retained to hold the warm-pool minimum: a minimum is set and the
     * functional count is at or below it (so reaping this agent would drop below the minimum). Mirrors
     * the EC2 plugin's EC2RetentionStrategy minimum gate. Package-private and pure for unit testing.
     */
    static boolean retainsMinimum(int activeAgents, int instanceMin) {
        return activeAgents > 0 && activeAgents <= instanceMin;
    }
}
