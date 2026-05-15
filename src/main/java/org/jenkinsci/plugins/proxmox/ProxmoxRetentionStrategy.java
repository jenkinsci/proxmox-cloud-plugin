package org.jenkinsci.plugins.proxmox;

import hudson.slaves.RetentionStrategy;

import java.util.logging.Level;
import java.util.logging.Logger;

public class ProxmoxRetentionStrategy extends RetentionStrategy<ProxmoxComputer> {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxRetentionStrategy.class.getName());

    private final int idleTerminationMinutes;
    private final int maxTotalUses;

    public ProxmoxRetentionStrategy(int idleTerminationMinutes, int maxTotalUses) {
        this.idleTerminationMinutes = idleTerminationMinutes;
        this.maxTotalUses = maxTotalUses;
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

        if (maxTotalUses > 0 && agent.getTotalUses() >= maxTotalUses) {
            LOGGER.info("Agent " + c.getName() + " reached max uses (" + maxTotalUses + "), terminating");
            terminate(agent);
            return 1;
        }

        if (idleTerminationMinutes > 0 && c.isIdle()) {
            long idleMs = System.currentTimeMillis() - c.getIdleStartMilliseconds();
            long thresholdMs = (long) idleTerminationMinutes * 60 * 1000;
            if (idleMs > thresholdMs) {
                LOGGER.info("Agent " + c.getName() + " idle for " + (idleMs / 60000) + " minutes, terminating");
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
