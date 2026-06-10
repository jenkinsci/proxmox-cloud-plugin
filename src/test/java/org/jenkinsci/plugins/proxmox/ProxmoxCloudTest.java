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
import hudson.util.Secret;
import jenkins.model.Jenkins;
import org.htmlunit.html.HtmlPage;
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
        }
        try (ACLContext ignored = ACL.as2(User.getById("admin", true).impersonate2())) {
            assertEquals(FormValidation.Kind.ERROR, d.doCheckApiUrl("").kind);
            assertEquals(FormValidation.Kind.ERROR, d.doCheckApiUrl("not-a-url").kind);
        }
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
