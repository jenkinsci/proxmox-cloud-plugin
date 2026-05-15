package org.jenkinsci.plugins.proxmox.config;

import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.*;

public class ProxmoxConfigSyncSchedulerTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void recurrencePeriodIs60Seconds() {
        ProxmoxConfigSyncScheduler scheduler = new ProxmoxConfigSyncScheduler();
        assertEquals(60_000, scheduler.getRecurrencePeriod());
    }

    @Test
    public void doesNothingWhenDisabled() throws Exception {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        config.setEnabled(false);
        config.setCronSpec("* * * * *");

        ProxmoxConfigSyncScheduler scheduler = new ProxmoxConfigSyncScheduler();
        scheduler.execute(hudson.model.TaskListener.NULL);

        assertEquals(0, j.jenkins.clouds.size());
    }

    @Test
    public void doesNothingWhenNoCronSpec() throws Exception {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        config.setEnabled(true);
        config.setCronSpec("");

        ProxmoxConfigSyncScheduler scheduler = new ProxmoxConfigSyncScheduler();
        scheduler.execute(hudson.model.TaskListener.NULL);

        assertEquals(0, j.jenkins.clouds.size());
    }
}
