package org.jenkinsci.plugins.proxmox.config;

import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.TaskListener;
import hudson.scheduler.CronTabList;

import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
public class ProxmoxConfigSyncScheduler extends AsyncPeriodicWork {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxConfigSyncScheduler.class.getName());
    private static final long RECURRENCE_MS = 60_000;

    public ProxmoxConfigSyncScheduler() {
        super("Proxmox Config Sync Scheduler");
    }

    @Override
    public long getRecurrencePeriod() {
        return RECURRENCE_MS;
    }

    @Override
    protected void execute(TaskListener listener) {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        if (config == null || !config.isEnabled()) return;

        String cronSpec = config.getCronSpec();
        if (cronSpec == null || cronSpec.isBlank()) return;

        try {
            CronTabList cronTabList = CronTabList.create(cronSpec);
            Calendar cal = new GregorianCalendar();
            cal.add(Calendar.MINUTE, -1);
            if (cronTabList.check(cal)) {
                LOGGER.info("Cron schedule matched, triggering Proxmox config sync");
                config.performSync();
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Failed to evaluate cron schedule or run sync", e);
        }
    }
}
