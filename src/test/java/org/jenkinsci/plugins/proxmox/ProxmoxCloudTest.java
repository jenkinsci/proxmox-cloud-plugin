package org.jenkinsci.plugins.proxmox;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.User;
import hudson.security.ACL;
import hudson.security.ACLContext;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.slaves.OfflineCause;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.htmlunit.html.HtmlPage;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedPlannedNode;
import org.jenkinsci.plugins.proxmox.config.CloneStrategy;
import org.jenkinsci.plugins.proxmox.config.JavaDistribution;
import org.jenkinsci.plugins.proxmox.config.ProxmoxCloudConfigSync;
import org.jenkinsci.plugins.proxmox.config.ProxmoxTokenCredentialsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.MockAuthorizationStrategy;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.HttpResponse;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.Collection;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.*;

@WithJenkins
class ProxmoxCloudTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .configureStaticDsl(true)
            .build();

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    @Test
    void testCanProvisionMatchingLabel() {
        ProxmoxCloud cloud = createTestCloud();
        Cloud.CloudState state = new Cloud.CloudState(Label.get("linux"), 0);

        assertTrue(cloud.canProvision(state));
    }

    @Test
    void testCanProvisionNonMatchingLabel() {
        ProxmoxCloud cloud = createTestCloud();
        Cloud.CloudState state = new Cloud.CloudState(Label.get("windows"), 0);

        assertFalse(cloud.canProvision(state));
    }

    @Test
    void testCanProvisionNullLabelNormalMode() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setMode(Node.Mode.NORMAL);

        ProxmoxCloud cloud = new ProxmoxCloud("test-cloud");
        cloud.setTemplates(List.of(template));

        Cloud.CloudState state = new Cloud.CloudState(null, 0);
        assertTrue(cloud.canProvision(state));
    }

    @Test
    void testCanProvisionNullLabelExclusiveMode() {
        ProxmoxCloud cloud = createTestCloud();
        Cloud.CloudState state = new Cloud.CloudState(null, 0);

        assertFalse(cloud.canProvision(state));
    }

    @Test
    void testInstanceCapPreventsProvisioning() {
        ProxmoxCloud cloud = createTestCloud();
        cloud.setInstanceCap(0);

        Cloud.CloudState state = new Cloud.CloudState(Label.get("linux"), 0);
        assertTrue(cloud.canProvision(state));

        cloud.setInstanceCap(1);
        assertTrue(cloud.canProvision(state));
    }

    @Test
    void testEmptyTemplatesCantProvision() {
        ProxmoxCloud cloud = new ProxmoxCloud("test-cloud");
        cloud.setTemplates(List.of());

        Cloud.CloudState state = new Cloud.CloudState(Label.get("linux"), 0);
        assertFalse(cloud.canProvision(state));
    }

    @Test
    void testOrphanCleanupGracePeriodDefault() {
        ProxmoxCloud cloud = new ProxmoxCloud("test-cloud");
        assertEquals(300, cloud.getOrphanCleanupGracePeriodSeconds());
    }

    @Test
    void testOrphanCleanupGracePeriodAccepted() {
        ProxmoxCloud cloud = new ProxmoxCloud("test-cloud");
        cloud.setOrphanCleanupGracePeriodSeconds(30);
        assertEquals(30, cloud.getOrphanCleanupGracePeriodSeconds());
    }

    @Test
    void testOrphanCleanupGracePeriodRejectsBelowOne() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProxmoxCloud("test-cloud").setOrphanCleanupGracePeriodSeconds(0));
    }

    @Test
    void testOrphanCleanupPeriodDefault() {
        ProxmoxCloud cloud = new ProxmoxCloud("test-cloud");
        assertEquals(600, cloud.getOrphanCleanupPeriodSeconds());
    }

    @Test
    void testOrphanCleanupPeriodAccepted() {
        ProxmoxCloud cloud = new ProxmoxCloud("test-cloud");
        cloud.setOrphanCleanupPeriodSeconds(30); // the minimum allowed
        assertEquals(30, cloud.getOrphanCleanupPeriodSeconds());
    }

    @Test
    void testOrphanCleanupPeriodRejectsBelowMinimum() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProxmoxCloud("test-cloud").setOrphanCleanupPeriodSeconds(29));
    }

    // --- concurrent, race-safe id reservation (issue #17) ---

    @Test
    void reserveVmIdReturnsDistinctIdsAndReleasesForReuse() throws Exception {
        ProxmoxCloud cloud = cloudPointingAtWireMock();
        cloud.setStartVmId(300);
        // Proxmox keeps returning 300 (the stub never "creates" a VM); the reserved set is what forces
        // concurrent callers onto distinct ids.
        stubFor(get(urlEqualTo("/api2/json/cluster/nextid")).willReturn(okJson("{\"data\":\"300\"}")));
        stubFor(get(urlEqualTo("/api2/json/cluster/nextid?vmid=301")).willReturn(okJson("{\"data\":\"301\"}")));

        int first = cloud.reserveVmId();
        int second = cloud.reserveVmId();
        assertEquals(300, first);
        assertEquals(301, second, "a second in-flight reservation must skip the already-reserved id");

        // Once the first id is released, it is free to be chosen again.
        cloud.releaseVmId(first);
        assertEquals(300, cloud.reserveVmId());
    }

    // --- cap accounting excludes offline-dead nodes (issues #16, #17) ---

    @Test
    void getRunningAgentCountExcludesOfflineDeadAgents() throws Exception {
        ProxmoxCloud cloud = new ProxmoxCloud("test-cloud");
        cloud.setOrphanCleanupGracePeriodSeconds(1); // 1s grace

        ProxmoxAgent agent = newAgent("agent-dead", 320);
        j.jenkins.addNode(agent);
        Computer c = agent.toComputer();
        assertNotNull(c);

        // Let the (failing) initial launch settle, then pin a known, backdatable offline cause.
        for (int i = 0; i < 200 && (c.isConnecting() || c.isOnline()); i++) {
            Thread.sleep(25);
        }
        OfflineCause.ChannelTermination cause = new OfflineCause.ChannelTermination(new IOException("blip"));
        c.disconnect(cause).get();

        // Just-disconnected (within the 1s grace) -> still counts as functional capacity.
        setOfflineCauseTimestamp(cause, System.currentTimeMillis());
        assertEquals(1, cloud.getRunningAgentCount(), "a briefly-offline agent still counts toward the cap");

        // Offline well beyond grace -> excluded so it cannot block a working replacement.
        setOfflineCauseTimestamp(cause, System.currentTimeMillis() - 3_600_000L);
        assertEquals(0, cloud.getRunningAgentCount(), "a long-dead offline agent must not hold a cap slot");
    }

    // --- cloud-stats integration ---

    @Test
    void provisionReturnsTrackedPlannedNodesCarryingActivityId() {
        // provision() must hand the NodeProvisioner cloud-stats TrackedPlannedNodes so the activity is
        // opened and tracked; the id carries the cloud + template names. The cap math runs on the
        // calling thread without touching the API (the per-agent clone runs later in the future).
        ProxmoxTemplate template = new ProxmoxTemplate("test-template", "pve1", 9000, "linux", 1);
        ProxmoxCloud cloud = new ProxmoxCloud("test-cloud");
        cloud.setApiUrl("http://localhost:" + wireMock.getPort());
        cloud.setTemplates(List.of(template));

        Collection<NodeProvisioner.PlannedNode> planned =
                cloud.provision(new Cloud.CloudState(Label.get("linux"), 0), 2);

        assertEquals(2, planned.size());
        for (NodeProvisioner.PlannedNode node : planned) {
            assertTrue(node instanceof TrackedPlannedNode,
                    "provision must return cloud-stats TrackedPlannedNodes");
            ProvisioningActivity.Id id = ((TrackedPlannedNode) node).getId();
            assertNotNull(id);
            assertEquals("test-cloud", id.getCloudName());
            assertEquals("test-template", id.getTemplateName());
        }
    }

    // --- warm-pool minimum provisioning (issue #20) ---

    @Test
    void provisionForMinimumRespectsTemplateCapWithoutHittingApi() throws Exception {
        ProxmoxTemplate template = new ProxmoxTemplate("test-template", "pve1", 9000, "linux", 1);
        template.setInstanceCap(1);
        template.setInstanceMin(3);
        ProxmoxCloud cloud = new ProxmoxCloud("test-cloud");
        cloud.setApiUrl("http://localhost:" + wireMock.getPort()); // any API call here would be unstubbed
        cloud.setTemplates(List.of(template));

        // One active agent of this template already equals the cap, so there is no headroom.
        j.jenkins.addNode(newAgent("agent-at-cap", 320));

        assertEquals(0, cloud.provisionForMinimum(template, 3));
        verify(0, anyRequestedFor(anyUrl())); // returned on the cap math, never reserved an id or cloned
    }

    @Test
    void minimumInstancesCheckNoOpsWhenNoMinimumConfigured() {
        ProxmoxTemplate template = new ProxmoxTemplate("test-template", "pve1", 9000, "linux", 1);
        // instanceMin defaults to 0 -> nothing to provision, so the check must do no API work.
        ProxmoxCloud cloud = new ProxmoxCloud("test-cloud");
        cloud.setApiUrl("http://localhost:" + wireMock.getPort());
        cloud.setTemplates(List.of(template));
        j.jenkins.clouds.add(cloud);

        ProxmoxMinimumInstances.checkForMinimumInstances(); // synchronous on the calling thread

        verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void validateTemplateMinimumsAcceptsMinWithinCapOrUnlimited() throws Exception {
        ProxmoxTemplate withinCap = new ProxmoxTemplate("t", "pve1", 100, "linux", 1);
        withinCap.setInstanceCap(4);
        withinCap.setInstanceMin(4); // at the cap is allowed
        ProxmoxTemplate unlimited = new ProxmoxTemplate("u", "pve1", 100, "linux", 1);
        unlimited.setInstanceMin(5); // cap 0 = unlimited, any minimum allowed
        ProxmoxCloud.validateTemplateMinimums(List.of(withinCap, unlimited)); // must not throw
    }

    @Test
    void validateTemplateMinimumsRejectsMinAboveCap() {
        ProxmoxTemplate bad = new ProxmoxTemplate("t", "pve1", 100, "linux", 1);
        bad.setInstanceCap(2);
        bad.setInstanceMin(3);
        assertThrows(Descriptor.FormException.class, () -> ProxmoxCloud.validateTemplateMinimums(List.of(bad)));
    }

    @Test
    void cloudLevelValidationRejectsWindowsTemplateWithoutRemoteFs() throws Exception {
        // Mirror the loop added in ProxmoxCloud.DescriptorImpl.newInstance() so that bypassed
        // ProxmoxTemplate.newInstance() calls (via @DataBoundSetter binding) are still caught.
        ProxmoxTemplate windowsNoFs = new ProxmoxTemplate("w", "pve1", 9001, "windows", 1);
        windowsNoFs.setOsType(org.jenkinsci.plugins.proxmox.config.OsType.WINDOWS);

        assertThrows(Descriptor.FormException.class,
                () -> ProxmoxTemplate.validateWindowsRemoteFs(windowsNoFs));
    }

    @Test
    void cloudLevelValidationAcceptsWindowsTemplateWithRemoteFs() throws Exception {
        ProxmoxTemplate windowsWithFs = new ProxmoxTemplate("w", "pve1", 9001, "windows", 1);
        windowsWithFs.setOsType(org.jenkinsci.plugins.proxmox.config.OsType.WINDOWS);
        windowsWithFs.setRemoteFs("C:\\Users\\jenkins\\agent");

        ProxmoxTemplate.validateWindowsRemoteFs(windowsWithFs); // must not throw
    }

    @Test
    void cloudLevelValidationRejectsWindowsTemplateWithJavaInstall() throws Exception {
        // Mirrors the newInstance() loop, like the remoteFs tests above (issue #29).
        ProxmoxTemplate windowsInstall = new ProxmoxTemplate("w", "pve1", 9001, "windows", 1);
        windowsInstall.setOsType(org.jenkinsci.plugins.proxmox.config.OsType.WINDOWS);
        windowsInstall.setRemoteFs("C:\\Users\\jenkins\\agent");
        windowsInstall.setJavaDistribution(org.jenkinsci.plugins.proxmox.config.JavaDistribution.OPENJDK);

        assertThrows(Descriptor.FormException.class,
                () -> ProxmoxTemplate.validateWindowsJavaDistribution(windowsInstall));
    }

    // --- Copy Template control rendering (issue #25) ---

    @Test
    void copyTemplateControlRenderedWhenEditable() throws Exception {
        ProxmoxCloud cloud = createTestCloud();
        j.jenkins.clouds.add(cloud);
        assertFalse(cloud.isConfigReadOnly(), "precondition: a manually-configured cloud is not read-only");

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo(cloud.getUrl() + "configure");
        String html = page.getWebResponse().getContentAsString();

        assertTrue(html.contains("proxmox-copy-template-button"),
                "Copy Template button should render for an editable cloud");
        assertTrue(html.contains("copy-template"),
                "copy-template adjunct should be loaded for an editable cloud");
    }

    @Test
    void copyTemplateControlHiddenInReadOnlyMode() throws Exception {
        ProxmoxCloud cloud = createTestCloud();
        cloud.setConfigManaged(true);
        j.jenkins.clouds.add(cloud);
        ProxmoxCloudConfigSync.get().setAllowManualChanges(false);
        assertTrue(cloud.isConfigReadOnly(),
                "precondition: config-managed cloud with manual changes disabled is read-only");

        JenkinsRule.WebClient wc = j.createWebClient();
        wc.getOptions().setJavaScriptEnabled(false);
        HtmlPage page = wc.goTo(cloud.getUrl() + "configure");
        String html = page.getWebResponse().getContentAsString();

        assertFalse(html.contains("proxmox-copy-template-button"),
                "Copy Template control must not render in read-only mode");
    }

    @Test
    void doCheckApiUrlRequiresAdminPermission() throws Exception {
        // doCheckApiUrl validates the cloud's API URL; gate it on ADMINISTER (issue #27).
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new MockAuthorizationStrategy()
                .grant(Jenkins.ADMINISTER).everywhere().to("admin")
                .grant(Jenkins.READ).everywhere().to("reader"));
        ProxmoxCloud.DescriptorImpl d = j.jenkins.getDescriptorByType(ProxmoxCloud.DescriptorImpl.class);

        try (ACLContext ignored = ACL.as2(User.getById("reader", true).impersonate2())) {
            assertEquals(FormValidation.Kind.OK, d.doCheckApiUrl("").kind);          // would normally error
            assertEquals(FormValidation.Kind.OK, d.doCheckApiUrl("not-a-url").kind); // would normally error
            assertEquals(FormValidation.Kind.OK, d.doCheckStartVmId(-1).kind);
            assertEquals(FormValidation.Kind.OK, d.doCheckOrphanCleanupGracePeriodSeconds(0).kind);
            assertEquals(FormValidation.Kind.OK, d.doCheckOrphanCleanupPeriodSeconds(0).kind);
        }
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            assertEquals(FormValidation.Kind.ERROR, d.doCheckApiUrl("").kind);
            assertEquals(FormValidation.Kind.ERROR, d.doCheckApiUrl("not-a-url").kind);
            assertEquals(FormValidation.Kind.ERROR, d.doCheckStartVmId(-1).kind);
            assertEquals(FormValidation.Kind.ERROR, d.doCheckOrphanCleanupGracePeriodSeconds(0).kind);
            assertEquals(FormValidation.Kind.ERROR, d.doCheckOrphanCleanupPeriodSeconds(0).kind);
        }
    }

    // --- descriptor connection test + form-validation checks ---

    private ProxmoxCloud.DescriptorImpl cloudDescriptor() {
        return j.jenkins.getDescriptorByType(ProxmoxCloud.DescriptorImpl.class);
    }

    private String registerProxmoxCreds() {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new ProxmoxTokenCredentialsImpl(CredentialsScope.GLOBAL, "proxmox-cred", "desc",
                        "user@pve!token", Secret.fromString("secret")));
        return "proxmox-cred";
    }

    private String apiUrl() {
        return "http://localhost:" + wireMock.getPort();
    }

    @Test
    void doTestConnectionSucceeds() {
        String cred = registerProxmoxCreds();
        stubFor(get(urlEqualTo("/api2/json/version")).willReturn(okJson("{\"data\":{\"version\":\"8.2.4\"}}")));
        FormValidation r = cloudDescriptor().doTestConnection(apiUrl(), cred, false);
        assertEquals(FormValidation.Kind.OK, r.kind);
        assertTrue(r.getMessage().contains("8.2.4"));
    }

    @Test
    void doTestConnectionRejectsTooOldVersion() {
        String cred = registerProxmoxCreds();
        stubFor(get(urlEqualTo("/api2/json/version")).willReturn(okJson("{\"data\":{\"version\":\"7.4\"}}")));
        assertEquals(FormValidation.Kind.ERROR, cloudDescriptor().doTestConnection(apiUrl(), cred, false).kind);
    }

    @Test
    void doTestConnectionWarnsOnNewerVersion() {
        String cred = registerProxmoxCreds();
        stubFor(get(urlEqualTo("/api2/json/version")).willReturn(okJson("{\"data\":{\"version\":\"10.1\"}}")));
        assertEquals(FormValidation.Kind.WARNING, cloudDescriptor().doTestConnection(apiUrl(), cred, false).kind);
    }

    @Test
    void doTestConnectionOkWhenVersionUnparseable() {
        // parseMajorVersion returns 0 for a non-numeric version, so no min/max check fires.
        String cred = registerProxmoxCreds();
        stubFor(get(urlEqualTo("/api2/json/version")).willReturn(okJson("{\"data\":{\"version\":\"weird\"}}")));
        assertEquals(FormValidation.Kind.OK, cloudDescriptor().doTestConnection(apiUrl(), cred, false).kind);
    }

    @Test
    void doTestConnectionReportsAuthFailure() {
        String cred = registerProxmoxCreds();
        stubFor(get(urlEqualTo("/api2/json/version")).willReturn(aResponse().withStatus(401).withBody("no")));
        FormValidation r = cloudDescriptor().doTestConnection(apiUrl(), cred, false);
        assertEquals(FormValidation.Kind.ERROR, r.kind);
        assertTrue(r.getMessage().contains("user@pve!token"));
    }

    @Test
    void doTestConnectionReportsConnectionFailure() {
        String cred = registerProxmoxCreds();
        stubFor(get(urlEqualTo("/api2/json/version")).willReturn(aResponse().withStatus(500).withBody("boom")));
        FormValidation r = cloudDescriptor().doTestConnection(apiUrl(), cred, false);
        assertEquals(FormValidation.Kind.ERROR, r.kind);
        assertTrue(r.getMessage().contains("Connection failed"));
    }

    @Test
    void doTestConnectionRequiresUrlAndCredentials() {
        ProxmoxCloud.DescriptorImpl d = cloudDescriptor();
        assertEquals(FormValidation.Kind.ERROR, d.doTestConnection("", "x", false).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doTestConnection("http://x", "", false).kind);
    }

    @Test
    void doTestConnectionReportsMissingCredential() {
        FormValidation r = cloudDescriptor().doTestConnection(apiUrl(), "nope", false);
        assertEquals(FormValidation.Kind.ERROR, r.kind);
        assertTrue(r.getMessage().contains("Credentials not found"));
    }

    @Test
    void doCheckStartVmIdEnforcesReservedRange() {
        ProxmoxCloud.DescriptorImpl d = cloudDescriptor();
        assertEquals(FormValidation.Kind.ERROR, d.doCheckStartVmId(-1).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckStartVmId(50).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckStartVmId(0).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckStartVmId(300).kind);
    }

    @Test
    void doCheckOrphanCleanupPeriodsEnforceMinimums() {
        ProxmoxCloud.DescriptorImpl d = cloudDescriptor();
        assertEquals(FormValidation.Kind.ERROR, d.doCheckOrphanCleanupGracePeriodSeconds(0).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckOrphanCleanupGracePeriodSeconds(1).kind);
        assertEquals(FormValidation.Kind.ERROR, d.doCheckOrphanCleanupPeriodSeconds(29).kind);
        assertEquals(FormValidation.Kind.OK, d.doCheckOrphanCleanupPeriodSeconds(30).kind);
    }

    @Test
    void doFillCredentialsIdItemsListsProxmoxCredential() {
        String cred = registerProxmoxCreds();
        ListBoxModel m = cloudDescriptor().doFillCredentialsIdItems();
        assertTrue(m.stream().anyMatch(o -> cred.equals(o.value)));
    }

    // --- doProvision / provisionForMinimum / warm-pool provisioning via WireMock ---

    private void stubProvisionSequence(int vmId) {
        stubFor(get(urlEqualTo("/api2/json/cluster/nextid")).willReturn(okJson("{\"data\":\"" + vmId + "\"}")));
        stubFor(post(urlEqualTo("/api2/json/nodes/pve1/qemu/9000/clone"))
                .willReturn(okJson("{\"data\":\"UPID:clone\"}")));
        stubFor(get(urlPathMatching("/api2/json/nodes/pve1/tasks/.*/status"))
                .willReturn(okJson("{\"data\":{\"upid\":\"x\",\"status\":\"stopped\",\"exitstatus\":\"OK\"}}")));
        stubFor(post(urlEqualTo("/api2/json/nodes/pve1/qemu/" + vmId + "/status/start"))
                .willReturn(okJson("{\"data\":\"UPID:start\"}")));
        stubFor(put(urlPathMatching("/api2/json/nodes/pve1/qemu/.*/config"))
                .willReturn(okJson("{\"data\":null}")));
    }

    @Test
    void doProvisionRejectsUnknownTemplate() {
        ProxmoxCloud cloud = cloudPointingAtWireMock();
        cloud.setTemplates(List.of(new ProxmoxTemplate("known", "pve1", 9000, "linux", 1)));
        assertThrows(Exception.class, () -> cloud.doProvision("does-not-exist"));
    }

    @Test
    void doProvisionRejectsBlankTemplate() {
        assertThrows(Exception.class, () -> cloudPointingAtWireMock().doProvision(""));
    }

    @Test
    void doProvisionProvisionsAndRedirects() throws Exception {
        ProxmoxCloud cloud = cloudPointingAtWireMock();
        cloud.setTemplates(List.of(new ProxmoxTemplate("t", "pve1", 9000, "linux", 1)));
        // Deliberately not registered as a Jenkins cloud: the node still provisions and is added, and
        // the agent's async launch fails immediately on getCloud()==null without polling the API, so it
        // cannot pollute the shared WireMock request journal that other tests assert against.
        stubProvisionSequence(300);

        HttpResponse resp = cloud.doProvision("t");
        assertNotNull(resp);
        assertNotNull(j.jenkins.getNode("jenkins-agent-300"));
    }

    @Test
    void doProvisionRenamesCloudStatsActivityToAgentName() throws Exception {
        // Manual provisioning bypasses cloud-stats' CloudProvisioningListener (which performs the
        // rename on the NodeProvisioner path), so doProvision must call onComplete itself or the
        // Cloud Statistics page shows only template names for manually/warm-pool provisioned agents.
        ProxmoxCloud cloud = cloudPointingAtWireMock();
        cloud.setTemplates(List.of(new ProxmoxTemplate("t2", "pve1", 9000, "linux", 1)));
        stubProvisionSequence(301);

        cloud.doProvision("t2");

        assertTrue(CloudStatistics.get().getActivities().stream()
                        .anyMatch(a -> "jenkins-agent-301".equals(a.getName())),
                "no cloud-stats activity carrying the agent name");
    }

    @Test
    void doProvisionReportsProvisionFailure() {
        ProxmoxCloud cloud = cloudPointingAtWireMock();
        cloud.setTemplates(List.of(new ProxmoxTemplate("t", "pve1", 9000, "linux", 1)));
        stubFor(get(urlEqualTo("/api2/json/cluster/nextid")).willReturn(okJson("{\"data\":\"300\"}")));
        stubFor(post(urlEqualTo("/api2/json/nodes/pve1/qemu/9000/clone"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));
        // Clone fails -> the catch path runs onFailure and returns an error response (thrown).
        assertThrows(Exception.class, () -> cloud.doProvision("t"));
    }

    @Test
    void provisionForMinimumAddsNodes() throws Exception {
        ProxmoxCloud cloud = cloudPointingAtWireMock();
        ProxmoxTemplate template = new ProxmoxTemplate("t", "pve1", 9000, "linux", 1);
        cloud.setTemplates(List.of(template));
        // Not registered (see doProvisionProvisionsAndRedirects): keeps the async launch from hitting
        // WireMock so the shared request journal stays clean for the verify(0) tests.
        stubProvisionSequence(300);

        assertEquals(1, cloud.provisionForMinimum(template, 1));
        assertNotNull(j.jenkins.getNode("jenkins-agent-300"));
    }

    @Test
    void provisionForMinimumReportsFailureWhenCloneFails() {
        ProxmoxCloud cloud = cloudPointingAtWireMock();
        ProxmoxTemplate template = new ProxmoxTemplate("t", "pve1", 9000, "linux", 1);
        cloud.setTemplates(List.of(template));
        stubFor(get(urlEqualTo("/api2/json/cluster/nextid")).willReturn(okJson("{\"data\":\"300\"}")));
        stubFor(post(urlEqualTo("/api2/json/nodes/pve1/qemu/9000/clone"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));
        // future.get() throws -> the onFailure branch runs, no node is added.
        assertEquals(0, cloud.provisionForMinimum(template, 1));
        assertNull(j.jenkins.getNode("jenkins-agent-300"));
    }

    @Test
    void checkForMinimumInstancesProvisionsTowardMinimum() {
        ProxmoxCloud cloud = cloudPointingAtWireMock();
        ProxmoxTemplate template = new ProxmoxTemplate("t", "pve1", 9000, "linux", 1);
        template.setInstanceMin(1);
        // checkForMinimumInstances iterates the registered clouds, so this cloud must be registered.
        // A static IP + 1s wait keeps the agent's async launch off WireMock (resolveIp returns the
        // static IP without an API call; waitForSsh fails fast against a black-hole address).
        template.setIpConfig("ip=192.0.2.1/24");
        template.setStartupWaitSeconds(1);
        cloud.setTemplates(List.of(template));
        j.jenkins.clouds.add(cloud);
        stubProvisionSequence(300);

        ProxmoxMinimumInstances.checkForMinimumInstances(); // runs synchronously on the calling thread
        assertNotNull(j.jenkins.getNode("jenkins-agent-300"));
    }

    private static void setOfflineCauseTimestamp(OfflineCause cause, long timestampMs) throws Exception {
        Field f = OfflineCause.class.getDeclaredField("timestamp");
        f.setAccessible(true);
        f.setLong(cause, timestampMs);
    }

    private ProxmoxAgent newAgent(String name, int vmId) throws Exception {
        ProxmoxLauncher launcher = new ProxmoxLauncher("ssh-cred", "java", "", 1, null, JavaDistribution.NONE, 0);
        return new ProxmoxAgent(name, "/home/jenkins", 1, Node.Mode.NORMAL, "linux",
                launcher, "test-cloud", "test-template", "pve1", vmId, 10, 0, null);
    }

    private ProxmoxCloud cloudPointingAtWireMock() {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new ProxmoxTokenCredentialsImpl(CredentialsScope.GLOBAL, "proxmox-cred", "desc",
                        "user@pve!token", Secret.fromString("secret")));
        ProxmoxCloud cloud = new ProxmoxCloud("test-cloud");
        cloud.setApiUrl("http://localhost:" + wireMock.getPort());
        cloud.setCredentialsId("proxmox-cred");
        return cloud;
    }

    private ProxmoxCloud createTestCloud() {
        ProxmoxTemplate template = new ProxmoxTemplate("test", "pve1", 100, "linux", 1);
        template.setCloneStrategy(CloneStrategy.FULL);
        template.setMode(Node.Mode.EXCLUSIVE);

        ProxmoxCloud cloud = new ProxmoxCloud("test-cloud");
        cloud.setApiUrl("https://proxmox:8006");
        cloud.setTemplates(List.of(template));
        return cloud;
    }
}
