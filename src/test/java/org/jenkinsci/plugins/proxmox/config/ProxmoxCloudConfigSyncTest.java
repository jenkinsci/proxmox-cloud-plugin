package org.jenkinsci.plugins.proxmox.config;

import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.util.FormValidation;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.HttpResponse;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

@WithJenkins
class ProxmoxCloudConfigSyncTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void globalConfigurationRegistered() {
        ProxmoxCloudConfigSync config = GlobalConfiguration.all().get(ProxmoxCloudConfigSync.class);
        assertNotNull(config);
    }

    @Test
    void configurationPersistence() throws Exception {
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
    void doCheckCronSpec_valid() {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        FormValidation result = config.doCheckCronSpec("H/5 * * * *");
        assertEquals(FormValidation.Kind.OK, result.kind);
    }

    @Test
    void doCheckCronSpec_invalid() {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        FormValidation result = config.doCheckCronSpec("not a cron expression");
        assertEquals(FormValidation.Kind.ERROR, result.kind);
    }

    @Test
    void doCheckCronSpec_empty() {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        FormValidation result = config.doCheckCronSpec("");
        assertEquals(FormValidation.Kind.OK, result.kind);
    }

    @Test
    void doCheckCronSpec_null() {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        FormValidation result = config.doCheckCronSpec(null);
        assertEquals(FormValidation.Kind.OK, result.kind);
    }

    @Test
    void performSync_notEnabled() {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        config.setEnabled(false);

        ProxmoxSyncResult result = config.performSync();

        assertFalse(result.isSuccess());
        assertTrue(result.errors().get(0).contains("not enabled"));
    }

    @Test
    void performSync_missingGitUrl() {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        config.setEnabled(true);
        config.setGitUrl("");

        ProxmoxSyncResult result = config.performSync();

        assertFalse(result.isSuccess());
        assertTrue(result.errors().get(0).contains("URL"));
    }

    @Test
    void allowManualChanges_defaultTrue() {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        assertTrue(config.isAllowManualChanges());
    }

    @Test
    void doCheckGitUrl_empty() {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        FormValidation result = config.doCheckGitUrl("");
        assertEquals(FormValidation.Kind.ERROR, result.kind);
    }

    @Test
    void doCheckGitUrl_valid() {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        FormValidation result = config.doCheckGitUrl("https://github.com/org/repo.git");
        assertEquals(FormValidation.Kind.OK, result.kind);
    }

    @Test
    void doCheckYamlFilePath_empty() {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        FormValidation result = config.doCheckYamlFilePath("");
        assertEquals(FormValidation.Kind.ERROR, result.kind);
    }

    @Test
    void doCheckMethodsRequireAdminPermission() throws Exception {
        // The config-sync doCheck methods return OK for a user lacking ADMINISTER (issue #27).
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ).everywhere().to("reader"));
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();

        try (ACLContext ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
            assertEquals(FormValidation.Kind.OK, config.doCheckGitUrl("").kind);
            assertEquals(FormValidation.Kind.OK, config.doCheckYamlFilePath("").kind);
            assertEquals(FormValidation.Kind.OK, config.doCheckCronSpec("not a cron expression").kind);
        }
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            assertEquals(FormValidation.Kind.ERROR, config.doCheckGitUrl("").kind);
            assertEquals(FormValidation.Kind.ERROR, config.doCheckYamlFilePath("").kind);
            assertEquals(FormValidation.Kind.ERROR, config.doCheckCronSpec("not a cron expression").kind);
        }
    }

    // --- git-backed sync against a local JGit repo fixture ---

    /** Create a throwaway local git repo containing the basic example config as proxmox-cloud.yaml. */
    private String createGitRepoWithConfig() throws Exception {
        File repo = Files.createTempDirectory("proxmox-cfg-repo").toFile();
        String yaml;
        try (var in = getClass().getResourceAsStream("/proxmox/proxmox-config-basic.yaml")) {
            yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        Files.writeString(new File(repo, "proxmox-cloud.yaml").toPath(), yaml);
        try (org.eclipse.jgit.api.Git git = org.eclipse.jgit.api.Git.init()
                .setDirectory(repo).setInitialBranch("main").call()) {
            git.add().addFilepattern(".").call();
            git.commit().setMessage("init").setAuthor("t", "t@example.com")
                    .setCommitter("t", "t@example.com").setSign(false).call();
        }
        return repo.getAbsolutePath();
    }

    @Test
    void executeSyncFromGitClonesAndAppliesConfig() throws Exception {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        config.setEnabled(true);
        config.setGitUrl(createGitRepoWithConfig());
        config.setGitBranch("main");
        config.setYamlFilePath("proxmox-cloud.yaml");

        ProxmoxSyncResult result = config.executeSyncFromGit();

        assertTrue(result.isSuccess(), "sync should succeed: " + result.getSummary());
        assertTrue(result.cloudsConfigured() >= 1);
        assertNotNull(config.getLastSyncResult());
        assertTrue(config.getLastSyncTimestamp() > 0);
    }

    @Test
    void executeSyncFromGitReportsMissingYamlFile() throws Exception {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        config.setEnabled(true);
        config.setGitUrl(createGitRepoWithConfig());
        config.setGitBranch("main");
        config.setYamlFilePath("does-not-exist.yaml"); // fetchYamlFromGit throws -> caught as an error result

        ProxmoxSyncResult result = config.executeSyncFromGit();

        assertFalse(result.isSuccess());
        assertNotNull(config.getLastSyncResult());
    }

    @Test
    void doTestGitConnectionSucceeds() throws Exception {
        FormValidation r = ProxmoxCloudConfigSync.get()
                .doTestGitConnection(createGitRepoWithConfig(), null, "main", "proxmox-cloud.yaml");
        assertEquals(FormValidation.Kind.OK, r.kind);
    }

    @Test
    void doTestGitConnectionReportsMissingYaml() throws Exception {
        FormValidation r = ProxmoxCloudConfigSync.get()
                .doTestGitConnection(createGitRepoWithConfig(), null, "main", "missing.yaml");
        assertEquals(FormValidation.Kind.ERROR, r.kind);
    }

    @Test
    void doTestGitConnectionRejectsEmptyUrl() {
        assertEquals(FormValidation.Kind.ERROR,
                ProxmoxCloudConfigSync.get().doTestGitConnection("", null, "main", "x.yaml").kind);
    }

    @Test
    void doTestGitConnectionReportsBadRepository() {
        FormValidation r = ProxmoxCloudConfigSync.get()
                .doTestGitConnection("/nonexistent/proxmox/repo", null, "main", "x.yaml");
        assertEquals(FormValidation.Kind.ERROR, r.kind);
    }

    @Test
    void doRunSyncReturnsResponseAndRecordsResult() throws Exception {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        HttpResponse resp = config.doRunSync(createGitRepoWithConfig(), null, "main", "proxmox-cloud.yaml");
        assertNotNull(resp);
        assertNotNull(config.getLastSyncResult());
    }

    @Test
    void configureBindsEnabledBlockAndDisable() throws Exception {
        ProxmoxCloudConfigSync config = ProxmoxCloudConfigSync.get();
        net.sf.json.JSONObject block = new net.sf.json.JSONObject();
        block.put("gitUrl", "https://example.com/repo.git");
        block.put("gitBranch", "develop");
        block.put("yamlFilePath", "cfg.yaml");
        block.put("allowManualChanges", false);
        net.sf.json.JSONObject form = new net.sf.json.JSONObject();
        form.put("enabled", block);

        config.configure((org.kohsuke.stapler.StaplerRequest2) null, form);
        assertTrue(config.isEnabled());
        assertEquals("https://example.com/repo.git", config.getGitUrl());
        assertEquals("develop", config.getGitBranch());
        assertFalse(config.isAllowManualChanges());

        config.configure((org.kohsuke.stapler.StaplerRequest2) null, new net.sf.json.JSONObject()); // disabled
        assertFalse(config.isEnabled());
    }

    @Test
    void doFillGitCredentialsIdItemsReturnsModel() {
        assertNotNull(ProxmoxCloudConfigSync.get().doFillGitCredentialsIdItems());
    }
}
