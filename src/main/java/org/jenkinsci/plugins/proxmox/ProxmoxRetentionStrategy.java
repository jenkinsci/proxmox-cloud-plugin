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

    @Override
    public long check(ProxmoxComputer c) {
        if (c.isOffline() && !c.isConnecting()) {
            return 1;
        }

        ProxmoxAgent agent = c.getNode();
        if (agent == null) {
            return 1;
        }

        int maxTotalUses = agent.getMaxTotalUses();
        if (maxTotalUses > 0 && agent.getTotalUses() >= maxTotalUses) {
            LOGGER.fine("Agent " + c.getName() + " reached max uses (" + maxTotalUses + "), terminating");
            terminate(agent);
            return 1;
        }

        int idleTerminationMinutes = agent.getIdleTerminationMinutes();
        if (idleTerminationMinutes > 0 && c.isIdle()) {
            long idleMs = System.currentTimeMillis() - c.getIdleStartMilliseconds();
            long thresholdMs = (long) idleTerminationMinutes * 60 * 1000;
            if (idleMs > thresholdMs) {
                LOGGER.fine("Agent " + c.getName() + " idle for " + (idleMs / 60000) + " minutes, terminating");
                terminate(agent);
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
}
