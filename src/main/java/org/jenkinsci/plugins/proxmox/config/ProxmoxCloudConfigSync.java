package org.jenkinsci.plugins.proxmox.config;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import hudson.Extension;
import hudson.FilePath;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.Symbol;
import org.jenkinsci.plugins.gitclient.Git;
import org.jenkinsci.plugins.gitclient.GitClient;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.HttpResponse;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest2;
import org.kohsuke.stapler.verb.POST;
import org.yaml.snakeyaml.Yaml;

import hudson.EnvVars;
import hudson.model.TaskListener;
import org.eclipse.jgit.transport.RefSpec;
import hudson.scheduler.CronTabList;

import java.io.File;
import java.util.Collections;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

@Extension
@Symbol("proxmoxConfigSync")
public class ProxmoxCloudConfigSync extends GlobalConfiguration {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxCloudConfigSync.class.getName());

    private boolean enabled;
    private String gitUrl;
    private String gitCredentialsId;
    private String gitBranch = "main";
    private String yamlFilePath = "proxmox-cloud.yaml";
    private String cronSpec;
    private boolean allowManualChanges = true;

    private transient volatile String lastSyncResult;
    private transient volatile long lastSyncTimestamp;
    private final transient Object syncLock = new Object();

    public ProxmoxCloudConfigSync() {
        load();
    }

    public static ProxmoxCloudConfigSync get() {
        return GlobalConfiguration.all().get(ProxmoxCloudConfigSync.class);
    }

    @Override
    public boolean configure(StaplerRequest2 req, JSONObject json) throws FormException {
        JSONObject enabledBlock = json.optJSONObject("enabled");
        if (enabledBlock != null) {
            this.enabled = true;
            this.gitUrl = enabledBlock.optString("gitUrl", "");
            this.gitCredentialsId = enabledBlock.optString("gitCredentialsId", "");
            this.gitBranch = enabledBlock.optString("gitBranch", "main");
            this.yamlFilePath = enabledBlock.optString("yamlFilePath", "proxmox-cloud.yaml");
            this.cronSpec = enabledBlock.optString("cronSpec", "");
            this.allowManualChanges = enabledBlock.optBoolean("allowManualChanges", true);
        } else {
            this.enabled = false;
        }
        save();
        return true;
    }

    // ---- Sync execution ----

    public ProxmoxSyncResult performSync() {
        if (!enabled) {
            return new ProxmoxSyncResult(0, 0, Collections.emptyList(),
                    java.util.List.of("Config sync is not enabled"));
        }
        return executeSyncFromGit();
    }

    ProxmoxSyncResult executeSyncFromGit() {
        synchronized (syncLock) {
            String url = gitUrl;
            if (url == null || url.isBlank()) {
                return new ProxmoxSyncResult(0, 0, Collections.emptyList(),
                        java.util.List.of("Git repository URL is not configured"));
            }

            try {
                String yamlContent = fetchYamlFromGit();
                Yaml yaml = new Yaml();
                @SuppressWarnings("unchecked")
                Map<String, Object> yamlData = yaml.load(yamlContent);

                ProxmoxConfigLoader loader = new ProxmoxConfigLoader();
                ProxmoxSyncResult result = loader.processYamlAndPersistConfig(yamlData);

                lastSyncTimestamp = System.currentTimeMillis();
                lastSyncResult = result.getSummary();

                if (result.isSuccess()) {
                    LOGGER.fine("Proxmox config sync completed: " + result.getSummary());
                } else {
                    LOGGER.warning("Proxmox config sync failed: " + result.getSummary());
                }

                return result;
            } catch (Exception e) {
                lastSyncTimestamp = System.currentTimeMillis();
                lastSyncResult = "Error: " + e.getMessage();
                LOGGER.log(Level.SEVERE, "Proxmox config sync failed", e);
                return new ProxmoxSyncResult(0, 0, Collections.emptyList(),
                        java.util.List.of(e.getMessage()));
            }
        }
    }

    String fetchYamlFromGit() throws Exception {
        File workspaceDir = new File(Jenkins.get().getRootDir(), "proxmox-config-sync-workspace");
        FilePath workspace = new FilePath(workspaceDir);
        workspace.mkdirs();

        GitClient git = Git.with(TaskListener.NULL, new EnvVars())
                .in(workspace)
                .using("git")
                .getClient();

        if (gitCredentialsId != null && !gitCredentialsId.isBlank()) {
            StandardUsernameCredentials creds = CredentialsMatchers.firstOrNull(
                    CredentialsProvider.lookupCredentialsInItemGroup(
                            StandardUsernameCredentials.class, Jenkins.get(), null,
                            Collections.emptyList()),
                    CredentialsMatchers.withId(gitCredentialsId));
            if (creds != null) {
                git.addDefaultCredentials(creds);
            }
        }

        FilePath gitDir = workspace.child(".git");
        if (!gitDir.exists()) {
            git.clone(gitUrl, "origin", false, null);
        } else {
            git.fetch_().from(new org.eclipse.jgit.transport.URIish(gitUrl),
                    java.util.List.of(new RefSpec("+refs/heads/*:refs/remotes/origin/*"))).execute();
        }

        String ref = (gitBranch != null && !gitBranch.isBlank()) ? gitBranch : "main";
        git.checkout().ref("origin/" + ref).execute();

        FilePath yamlFile = workspace.child(yamlFilePath);
        if (!yamlFile.exists()) {
            throw new java.io.FileNotFoundException(
                    "YAML config file not found: " + yamlFilePath + " in branch " + ref);
        }

        return yamlFile.readToString();
    }

    // ---- Stapler endpoints ----

    @POST
    public HttpResponse doRunSync(@QueryParameter String gitUrl,
                                   @QueryParameter String gitCredentialsId,
                                   @QueryParameter String gitBranch,
                                   @QueryParameter String yamlFilePath) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        if (gitUrl != null && !gitUrl.isBlank()) {
            this.enabled = true;
            this.gitUrl = gitUrl;
            this.gitCredentialsId = gitCredentialsId;
            this.gitBranch = (gitBranch != null && !gitBranch.isBlank()) ? gitBranch : "main";
            this.yamlFilePath = (yamlFilePath != null && !yamlFilePath.isBlank()) ? yamlFilePath : "proxmox-cloud.yaml";
            save();
        }

        ProxmoxSyncResult result = executeSyncFromGit();

        JSONObject json = new JSONObject();
        json.put("success", result.isSuccess());
        json.put("cloudsConfigured", result.cloudsConfigured());
        json.put("templatesConfigured", result.templatesConfigured());
        json.put("warnings", result.warnings());
        json.put("errors", result.errors());
        json.put("summary", result.getSummary());

        return new HttpResponse() {
            @Override
            public void generateResponse(org.kohsuke.stapler.StaplerRequest2 req,
                                          org.kohsuke.stapler.StaplerResponse2 rsp,
                                          Object node) throws java.io.IOException, jakarta.servlet.ServletException {
                rsp.setContentType("application/json;charset=UTF-8");
                rsp.getWriter().write(json.toString());
            }
        };
    }

    @POST
    public ListBoxModel doFillGitCredentialsIdItems() {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);
        return new StandardListBoxModel()
                .includeEmptyValue()
                .includeMatchingAs(
                        jenkins.model.Jenkins.get().getACL().SYSTEM2,
                        Jenkins.get(),
                        StandardUsernameCredentials.class,
                        Collections.emptyList(),
                        CredentialsMatchers.always());
    }

    @POST
    public FormValidation doCheckCronSpec(@QueryParameter String value) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return FormValidation.ok();
        }
        if (value == null || value.isBlank()) {
            return FormValidation.ok();
        }
        try {
            CronTabList.create(value);
            return FormValidation.ok();
        } catch (Exception e) {
            return FormValidation.error("Invalid cron expression: " + e.getMessage());
        }
    }

    @POST
    public FormValidation doCheckGitUrl(@QueryParameter String value) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return FormValidation.ok();
        }
        if (value == null || value.isBlank()) {
            return FormValidation.error("Git repository URL is required");
        }
        return FormValidation.ok();
    }

    @POST
    public FormValidation doCheckYamlFilePath(@QueryParameter String value) {
        if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
            return FormValidation.ok();
        }
        if (value == null || value.isBlank()) {
            return FormValidation.error("YAML file path is required");
        }
        return FormValidation.ok();
    }

    @POST
    public FormValidation doTestGitConnection(@QueryParameter String gitUrl,
                                               @QueryParameter String gitCredentialsId,
                                               @QueryParameter String gitBranch,
                                               @QueryParameter String yamlFilePath) {
        Jenkins.get().checkPermission(Jenkins.ADMINISTER);

        if (gitUrl == null || gitUrl.isBlank()) {
            return FormValidation.error("Git repository URL is required");
        }

        try {
            File workspaceDir = new File(Jenkins.get().getRootDir(), "proxmox-config-sync-workspace");
            FilePath workspace = new FilePath(workspaceDir);
            workspace.mkdirs();

            GitClient git = Git.with(TaskListener.NULL, new EnvVars())
                    .in(workspace)
                    .using("git")
                    .getClient();

            if (gitCredentialsId != null && !gitCredentialsId.isBlank()) {
                StandardUsernameCredentials creds = CredentialsMatchers.firstOrNull(
                        CredentialsProvider.lookupCredentialsInItemGroup(
                                StandardUsernameCredentials.class, Jenkins.get(), null,
                                Collections.emptyList()),
                        CredentialsMatchers.withId(gitCredentialsId));
                if (creds != null) {
                    git.addDefaultCredentials(creds);
                } else {
                    return FormValidation.warning(
                            "Credentials '%s' not found. Attempting without authentication.",
                            gitCredentialsId);
                }
            }

            FilePath gitDir = workspace.child(".git");
            if (!gitDir.exists()) {
                git.clone(gitUrl, "origin", false, null);
            } else {
                git.fetch_().from(new org.eclipse.jgit.transport.URIish(gitUrl),
                    java.util.List.of(new RefSpec("+refs/heads/*:refs/remotes/origin/*"))).execute();
            }

            String ref = (gitBranch != null && !gitBranch.isBlank()) ? gitBranch : "main";
            git.checkout().ref("origin/" + ref).execute();

            String path = (yamlFilePath != null && !yamlFilePath.isBlank())
                    ? yamlFilePath : "proxmox-cloud.yaml";
            FilePath yamlFile = workspace.child(path);
            if (!yamlFile.exists()) {
                return FormValidation.error(
                        "Connected to repository, but YAML file '%s' not found in branch '%s'",
                        path, ref);
            }

            return FormValidation.ok(
                    "Success: connected to repository and found '%s' in branch '%s'",
                    path, ref);
        } catch (Exception e) {
            return FormValidation.error("Git connection failed: " + e.getMessage());
        }
    }

    // ---- Getters ----

    public boolean isEnabled() { return enabled; }
    public String getGitUrl() { return gitUrl; }
    public String getGitCredentialsId() { return gitCredentialsId; }
    public String getGitBranch() { return gitBranch; }
    public String getYamlFilePath() { return yamlFilePath; }
    public String getCronSpec() { return cronSpec; }
    public boolean isAllowManualChanges() { return allowManualChanges; }
    public String getLastSyncResult() { return lastSyncResult; }
    public long getLastSyncTimestamp() { return lastSyncTimestamp; }

    // ---- Setters ----

    @DataBoundSetter public void setEnabled(boolean v) { this.enabled = v; }
    @DataBoundSetter public void setGitUrl(String v) { this.gitUrl = v; }
    @DataBoundSetter public void setGitCredentialsId(String v) { this.gitCredentialsId = v; }
    @DataBoundSetter public void setGitBranch(String v) { this.gitBranch = v; }
    @DataBoundSetter public void setYamlFilePath(String v) { this.yamlFilePath = v; }
    @DataBoundSetter public void setCronSpec(String v) { this.cronSpec = v; }
    @DataBoundSetter public void setAllowManualChanges(boolean v) { this.allowManualChanges = v; }
}
