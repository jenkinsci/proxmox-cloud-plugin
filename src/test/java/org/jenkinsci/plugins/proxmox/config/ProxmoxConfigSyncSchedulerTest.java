package org.jenkinsci.plugins.proxmox.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.*;

@WithJenkins
class ProxmoxConfigSyncSchedulerTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void recurrencePeriodIs60Seconds() {
        ProxmoxConfigSyncScheduler scheduler = new ProxmoxConfigSyncScheduler();
        assertEquals(60_000, scheduler.getRecurrencePeriod());
    }

    @Test
    void doesNothingWhenDisabled() throws Exception {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        config.setEnabled(false);
        config.setCronSpec("* * * * *");

        ProxmoxConfigSyncScheduler scheduler = new ProxmoxConfigSyncScheduler();
        scheduler.execute(hudson.model.TaskListener.NULL);

        assertEquals(0, j.jenkins.clouds.size());
    }

    @Test
    void doesNothingWhenNoCronSpec() throws Exception {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        config.setEnabled(true);
        config.setCronSpec("");

        ProxmoxConfigSyncScheduler scheduler = new ProxmoxConfigSyncScheduler();
        scheduler.execute(hudson.model.TaskListener.NULL);

        assertEquals(0, j.jenkins.clouds.size());
    }
}
