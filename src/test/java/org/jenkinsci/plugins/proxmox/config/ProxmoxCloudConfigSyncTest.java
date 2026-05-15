package org.jenkinsci.plugins.proxmox.config;

import hudson.util.FormValidation;
import jenkins.model.GlobalConfiguration;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.*;

public class ProxmoxCloudConfigSyncTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    @Test
    public void globalConfigurationRegistered() {
        ProxmoxCloudConfigSync config = GlobalConfiguration.all().get(ProxmoxCloudConfigSync.class);
        assertNotNull(config);
    }

    @Test
    public void configurationPersistence() throws Exception {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        assertNotNull(config);

        config.setEnabled(true);
        config.setGitUrl("https://github.com/example/repo.git");
        config.setGitCredentialsId("my-creds");
        config.setGitBranch("develop");
        config.setYamlFilePath("config/proxmox.yaml");
        config.setCronSpec("H/5 * * * *");
        config.setAllowManualChanges(false);
        config.save();

        config.load();

        assertTrue(config.isEnabled());
        assertEquals("https://github.com/example/repo.git", config.getGitUrl());
        assertEquals("my-creds", config.getGitCredentialsId());
        assertEquals("develop", config.getGitBranch());
        assertEquals("config/proxmox.yaml", config.getYamlFilePath());
        assertEquals("H/5 * * * *", config.getCronSpec());
        assertFalse(config.isAllowManualChanges());
    }

    @Test
    public void doCheckCronSpec_valid() {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        FormValidation result = config.doCheckCronSpec("H/5 * * * *");
        assertEquals(FormValidation.Kind.OK, result.kind);
    }

    @Test
    public void doCheckCronSpec_invalid() {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        FormValidation result = config.doCheckCronSpec("not a cron expression");
        assertEquals(FormValidation.Kind.ERROR, result.kind);
    }

    @Test
    public void doCheckCronSpec_empty() {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        FormValidation result = config.doCheckCronSpec("");
        assertEquals(FormValidation.Kind.OK, result.kind);
    }

    @Test
    public void doCheckCronSpec_null() {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        FormValidation result = config.doCheckCronSpec(null);
        assertEquals(FormValidation.Kind.OK, result.kind);
    }

    @Test
    public void performSync_notEnabled() {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        config.setEnabled(false);

        ProxmoxSyncResult result = config.performSync();

        assertFalse(result.isSuccess());
        assertTrue(result.errors().get(0).contains("not enabled"));
    }

    @Test
    public void performSync_missingGitUrl() {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        config.setEnabled(true);
        config.setGitUrl("");

        ProxmoxSyncResult result = config.performSync();

        assertFalse(result.isSuccess());
        assertTrue(result.errors().get(0).contains("URL"));
    }

    @Test
    public void allowManualChanges_defaultTrue() {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        assertTrue(config.isAllowManualChanges());
    }

    @Test
    public void doCheckGitUrl_empty() {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        FormValidation result = config.doCheckGitUrl("");
        assertEquals(FormValidation.Kind.ERROR, result.kind);
    }

    @Test
    public void doCheckGitUrl_valid() {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        FormValidation result = config.doCheckGitUrl("https://github.com/org/repo.git");
        assertEquals(FormValidation.Kind.OK, result.kind);
    }

    @Test
    public void doCheckYamlFilePath_empty() {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        FormValidation result = config.doCheckYamlFilePath("");
        assertEquals(FormValidation.Kind.ERROR, result.kind);
    }
}
