package org.jenkinsci.plugins.proxmox;

import hudson.Extension;
import hudson.model.AdministrativeMonitor;

import java.util.List;

/**
 * Warns, via the Jenkins administrative-monitor notification area, when a cloud's
 * <b>Orphan Cleanup Period</b> has been reduced below the cadence the cleanup work is currently
 * scheduled at. {@link ProxmoxOrphanCleanup#getRecurrencePeriod()} is read once when Jenkins schedules
 * the fixed-rate timer, so a period lowered after startup cannot fire faster until a restart; this
 * surfaces that so the change is not silently ineffective. It clears itself once Jenkins is restarted
 * (the new, smaller period becomes the scheduled cadence) or the period is raised back.
 */
@Extension
public class ProxmoxOrphanCleanupRestartMonitor extends AdministrativeMonitor {

    @Override
    public String getDisplayName() {
        return "Proxmox orphan cleanup period reduced";
    }

    @Override
    public boolean isActivated() {
        return ProxmoxOrphanCleanup.get().isRestartNeededForReducedPeriod();
    }

    /** The cadence (seconds) the cleanup work is currently scheduled at. */
    public long getScheduledPeriodSeconds() {
        return ProxmoxOrphanCleanup.get().getScheduledPeriodSeconds();
    }

    /** The smallest cleanup period (seconds) now configured but not yet in effect. */
    public long getEffectivePeriodSeconds() {
        return ProxmoxOrphanCleanup.get().getEffectivePeriodSeconds();
    }

    /** Comma-separated names of the clouds whose reduced period needs a restart. */
    public String getAffectedCloudsDisplay() {
        List<String> names = ProxmoxOrphanCleanup.get().cloudsNeedingRestart();
        return String.join(", ", names);
    }
}
