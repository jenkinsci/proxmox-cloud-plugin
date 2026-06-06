package org.jenkinsci.plugins.proxmox;

import hudson.Extension;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Saveable;
import hudson.model.listeners.SaveableListener;
import hudson.slaves.Cloud;
import jenkins.model.Jenkins;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Keeps a per-template warm pool: provisions agents until every template with {@code instanceMin > 0}
 * has that many functional instances running, bounded by the template and cloud instance caps. Mirrors
 * the EC2 plugin's {@code MinimumInstanceChecker}: every check runs serialized on a single background
 * thread via {@link #scheduleCheck()}, so callers (the periodic orphan-cleanup work, config save,
 * startup) never block on provisioning. The retention strategy preserves the minimum by not
 * idle-reaping below it; this class restores the count after a max-uses recycle or any other drop.
 */
public final class ProxmoxMinimumInstances {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxMinimumInstances.class.getName());

    /**
     * Single background thread so checks (which provision, i.e. block) never run concurrently or on a
     * caller's thread. Mirrors the EC2 plugin's MinimumInstanceChecker executor.
     */
    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ProxmoxMinimumInstances");
        t.setDaemon(true);
        return t;
    });

    private ProxmoxMinimumInstances() {
    }

    /** Schedule a minimum-instance check to run asynchronously, so the caller returns immediately. */
    public static void scheduleCheck() {
        EXECUTOR.execute(ProxmoxMinimumInstances::checkForMinimumInstances);
    }

    /**
     * Provision toward every template's {@code instanceMin}. Synchronized as a belt-and-suspenders
     * guard on top of the single-thread executor. Blocks while provisioning (it runs on the dedicated
     * thread), so by the time it returns the new agents are registered and counted, which prevents the
     * next check from over-provisioning the same deficit.
     */
    public static synchronized void checkForMinimumInstances() {
        Jenkins jenkins = Jenkins.getInstanceOrNull();
        if (jenkins == null) {
            return;
        }
        boolean anyMinimum = jenkins.clouds.stream()
                .filter(ProxmoxCloud.class::isInstance)
                .map(ProxmoxCloud.class::cast)
                .flatMap(cloud -> cloud.getTemplates().stream())
                .anyMatch(template -> template.getInstanceMin() > 0);
        if (!anyMinimum) {
            return; // no warm pools configured: skip the work entirely
        }

        for (Cloud cloud : jenkins.clouds) {
            if (!(cloud instanceof ProxmoxCloud proxmoxCloud)) continue;
            for (ProxmoxTemplate template : proxmoxCloud.getTemplates()) {
                int min = template.getInstanceMin();
                if (min <= 0) continue;
                int deficit = min - template.getNumActiveAgents(proxmoxCloud);
                if (deficit <= 0) continue;
                try {
                    int added = proxmoxCloud.provisionForMinimum(template, deficit);
                    if (added > 0) {
                        LOGGER.info("Warm pool: provisioned " + added + " agent(s) for template "
                                + template.getName() + " in cloud " + proxmoxCloud.name
                                + " toward minimum " + min);
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.WARNING, "Warm-pool top-up failed for template "
                            + template.getName() + " in cloud " + proxmoxCloud.name, e);
                }
            }
        }
    }

    /** Top up warm pools at startup (analog of the EC2 plugin's PluginImpl.postInitialize). */
    @Initializer(after = InitMilestone.JOB_LOADED)
    public static void init() {
        scheduleCheck();
    }

    /** Top up warm pools when the global config (clouds/templates) is saved. */
    @Extension
    public static class OnSaveListener extends SaveableListener {
        @Override
        public void onChange(Saveable o, XmlFile file) {
            if (o instanceof Jenkins) {
                scheduleCheck();
            }
        }
    }
}
