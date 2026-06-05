package org.jenkinsci.plugins.proxmox;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import hudson.model.Node;
import hudson.util.Secret;
import org.jenkinsci.plugins.proxmox.ProxmoxOrphanCleanup.DeadAgent;
import org.jenkinsci.plugins.proxmox.config.CloneStrategy;
import org.jenkinsci.plugins.proxmox.config.JavaInstallation;
import org.jenkinsci.plugins.proxmox.config.ProxmoxTokenCredentialsImpl;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Tests for {@link ProxmoxOrphanCleanup}, covering both directions of reconciliation: destroying
 * VMs Jenkins no longer tracks, and (added with issue #2) removing persisted agent nodes whose
 * backing VM is gone or no longer running.
 */
public class ProxmoxOrphanCleanupTest {

    private static final long NO_GRACE = 0L;

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private WireMockServer wireMock;

    @Before
    public void setUp() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        WireMock.configureFor("localhost", wireMock.port());
    }

    @After
    public void tearDown() {
        wireMock.stop();
    }

    private ProxmoxAgent newAgent(String name, String node, int vmId) throws Exception {
        ProxmoxLauncher launcher = new ProxmoxLauncher("ssh-cred", "java", "", 1, null, JavaInstallation.NONE);
        ProxmoxRetentionStrategy retention = new ProxmoxRetentionStrategy(10, 0);
        return new ProxmoxAgent(name, "/home/jenkins", 1, Node.Mode.NORMAL, "linux",
                launcher, retention, "test-cloud", "test-template", node, vmId);
    }

    /** Make an agent look older than the grace period so cleanup will act on it. */
    private void backdateCreatedAt(ProxmoxAgent agent, long ageMs) throws Exception {
        Field f = ProxmoxAgent.class.getDeclaredField("createdAt");
        f.setAccessible(true);
        f.setLong(agent, System.currentTimeMillis() - ageMs);
    }

    private ProxmoxCloud cloudPointingAtWireMock() {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new ProxmoxTokenCredentialsImpl(CredentialsScope.GLOBAL, "proxmox-cred", "desc",
                        "user@pve!token", Secret.fromString("secret")));

        ProxmoxTemplate template = new ProxmoxTemplate("test-template", "pve1", 9000, "linux", 1);
        template.setCloneStrategy(CloneStrategy.FULL);

        ProxmoxCloud cloud = new ProxmoxCloud("test-cloud");
        cloud.setApiUrl("http://localhost:" + wireMock.port());
        cloud.setCredentialsId("proxmox-cred");
        cloud.setOperationTimeout(60);
        cloud.setCleanupOrphanedAgents(true);
        cloud.setTemplates(List.of(template));
        return cloud;
    }

    // --- findDeadNodes: pure decision logic ---

    @Test
    public void findDeadNodes_retainsRunningVm() throws Exception {
        ProxmoxAgent agent = newAgent("a", "pve1", 300);
        Map<String, Map<Integer, String>> status = Map.of("pve1", Map.of(300, "running"));
        assertTrue(ProxmoxOrphanCleanup.findDeadNodes(List.of(agent), status,
                farFuture(), NO_GRACE).isEmpty());
    }

    @Test
    public void findDeadNodes_flagsGoneVmForNodeOnlyRemoval() throws Exception {
        ProxmoxAgent agent = newAgent("a", "pve1", 301);
        Map<String, Map<Integer, String>> status = Map.of("pve1", Map.of(300, "running"));
        List<DeadAgent> dead = ProxmoxOrphanCleanup.findDeadNodes(List.of(agent), status,
                farFuture(), NO_GRACE);
        assertEquals(1, dead.size());
        assertEquals(301, dead.get(0).agent().getVmId());
        assertFalse("gone VM => nothing to destroy", dead.get(0).vmExists());
    }

    @Test
    public void findDeadNodes_flagsStoppedVmForDestroyAndRemoval() throws Exception {
        ProxmoxAgent agent = newAgent("a", "pve1", 301);
        Map<String, Map<Integer, String>> status = Map.of("pve1", Map.of(301, "stopped"));
        List<DeadAgent> dead = ProxmoxOrphanCleanup.findDeadNodes(List.of(agent), status,
                farFuture(), NO_GRACE);
        assertEquals(1, dead.size());
        assertEquals(301, dead.get(0).agent().getVmId());
        assertTrue("stopped VM still exists and must be destroyed", dead.get(0).vmExists());
    }

    @Test
    public void findDeadNodes_skipsAgentWhenNodeListingFailed() throws Exception {
        ProxmoxAgent agent = newAgent("a", "pve1", 301);
        // pve1 absent from the map => its VM listing failed => never conclude the VM is gone/stopped.
        Map<String, Map<Integer, String>> status = new HashMap<>();
        assertTrue(ProxmoxOrphanCleanup.findDeadNodes(List.of(agent), status,
                farFuture(), NO_GRACE).isEmpty());
    }

    @Test
    public void findDeadNodes_skipsAgentWithinGracePeriod() throws Exception {
        ProxmoxAgent agent = newAgent("a", "pve1", 301); // VM gone
        Map<String, Map<Integer, String>> status = Map.of("pve1", Map.of(300, "running"));
        // Agent is ~0s old; with a 600s grace it must be left alone despite the VM being gone.
        List<DeadAgent> dead = ProxmoxOrphanCleanup.findDeadNodes(List.of(agent), status,
                agent.getCreatedAt() + 1000, 600_000L);
        assertTrue(dead.isEmpty());
    }

    @Test
    public void findDeadNodes_handlesMixedFleet() throws Exception {
        ProxmoxAgent running = newAgent("running", "pve1", 300);
        ProxmoxAgent gone = newAgent("gone", "pve1", 301);
        ProxmoxAgent stopped = newAgent("stopped", "pve1", 302);
        ProxmoxAgent unqueried = newAgent("unqueried", "pve2", 303); // pve2 not in the fetched map
        Map<String, Map<Integer, String>> status = Map.of("pve1", Map.of(300, "running", 302, "stopped"));

        List<DeadAgent> dead = ProxmoxOrphanCleanup.findDeadNodes(
                List.of(running, gone, stopped, unqueried), status, farFuture(), NO_GRACE);

        assertEquals(2, dead.size());
        DeadAgent goneResult = dead.stream().filter(d -> d.agent().getVmId() == 301).findFirst().orElseThrow();
        DeadAgent stoppedResult = dead.stream().filter(d -> d.agent().getVmId() == 302).findFirst().orElseThrow();
        assertFalse(goneResult.vmExists());
        assertTrue(stoppedResult.vmExists());
    }

    // --- end-to-end via cleanupCloud (JenkinsRule + WireMock) ---

    @Test
    public void cleanup_removesNodeWhoseVmVanished_andKeepsLiveNode() throws Exception {
        ProxmoxCloud cloud = cloudPointingAtWireMock();
        j.jenkins.addNode(newAgent("agent-live", "pve1", 300));
        ProxmoxAgent dead = newAgent("agent-dead", "pve1", 301);
        backdateCreatedAt(dead, 3_600_000L); // older than the 600s grace
        j.jenkins.addNode(dead);

        // VM 300 still runs on pve1; VM 301 is gone.
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu"))
                .willReturn(okJson("{\"data\":[" +
                        "{\"vmid\":300,\"name\":\"agent-live\",\"status\":\"running\",\"template\":0}" +
                        "]}")));

        new ProxmoxOrphanCleanup().cleanupCloud(cloud, j.jenkins);

        assertNotNull("agent whose VM still runs must be retained", j.jenkins.getNode("agent-live"));
        assertNull("agent whose backing VM vanished must be removed", j.jenkins.getNode("agent-dead"));
    }

    @Test
    public void cleanup_destroysStoppedVm_andRemovesNode() throws Exception {
        ProxmoxCloud cloud = cloudPointingAtWireMock();
        ProxmoxAgent stopped = newAgent("agent-stopped", "pve1", 303);
        backdateCreatedAt(stopped, 3_600_000L);
        j.jenkins.addNode(stopped);

        // VM 303 exists but is stopped -> must be destroyed, then the node removed.
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu"))
                .willReturn(okJson("{\"data\":[" +
                        "{\"vmid\":303,\"name\":\"agent-stopped\",\"status\":\"stopped\",\"template\":0}" +
                        "]}")));
        stubFor(post(urlEqualTo("/api2/json/nodes/pve1/qemu/303/status/stop"))
                .willReturn(okJson("{\"data\":\"UPID:pve1:stop\"}")));
        stubFor(delete(urlPathEqualTo("/api2/json/nodes/pve1/qemu/303"))
                .willReturn(okJson("{\"data\":\"UPID:pve1:destroy\"}")));
        stubFor(get(urlPathMatching("/api2/json/nodes/pve1/tasks/.*"))
                .willReturn(okJson("{\"data\":{\"status\":\"stopped\",\"exitstatus\":\"OK\"}}")));

        new ProxmoxOrphanCleanup().cleanupCloud(cloud, j.jenkins);

        verify(deleteRequestedFor(urlPathEqualTo("/api2/json/nodes/pve1/qemu/303")));
        assertNull("agent with a stopped VM must be removed", j.jenkins.getNode("agent-stopped"));
    }

    @Test
    public void cleanup_destroysOnlyOrphanedManagedVms() throws Exception {
        ProxmoxCloud cloud = cloudPointingAtWireMock();
        // No Jenkins agents registered, so every non-template VM is "unknown"; only the one carrying
        // our marker should be destroyed. Validates the orphaned-VM pass survived the refactor.
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu"))
                .willReturn(okJson("{\"data\":[" +
                        "{\"vmid\":9000,\"name\":\"template\",\"status\":\"stopped\",\"template\":1}," +
                        "{\"vmid\":400,\"name\":\"managed\",\"status\":\"running\",\"uptime\":100000,\"template\":0}," +
                        "{\"vmid\":401,\"name\":\"foreign\",\"status\":\"running\",\"uptime\":100000,\"template\":0}" +
                        "]}")));
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/400/config"))
                .willReturn(okJson("{\"data\":{\"description\":\"jenkins-managed;cloud:test-cloud\"}}")));
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/401/config"))
                .willReturn(okJson("{\"data\":{\"description\":\"unrelated vm\"}}")));
        stubFor(post(urlEqualTo("/api2/json/nodes/pve1/qemu/400/status/stop"))
                .willReturn(okJson("{\"data\":\"UPID:pve1:stop\"}")));
        stubFor(delete(urlPathEqualTo("/api2/json/nodes/pve1/qemu/400"))
                .willReturn(okJson("{\"data\":\"UPID:pve1:destroy\"}")));
        stubFor(get(urlPathMatching("/api2/json/nodes/pve1/tasks/.*"))
                .willReturn(okJson("{\"data\":{\"status\":\"stopped\",\"exitstatus\":\"OK\"}}")));

        new ProxmoxOrphanCleanup().cleanupCloud(cloud, j.jenkins);

        verify(deleteRequestedFor(urlPathEqualTo("/api2/json/nodes/pve1/qemu/400")));
        verify(0, deleteRequestedFor(urlPathEqualTo("/api2/json/nodes/pve1/qemu/401")));
        verify(0, deleteRequestedFor(urlPathEqualTo("/api2/json/nodes/pve1/qemu/9000")));
    }

    @Test
    public void cleanup_skipsOrphanedRunningVmWithinGracePeriod() throws Exception {
        ProxmoxCloud cloud = cloudPointingAtWireMock(); // grace = 600s default
        // A jenkins-managed VM with no Jenkins node, running, uptime only 5s -> likely a clone whose
        // agent has not registered yet -> must NOT be destroyed within the grace period.
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu"))
                .willReturn(okJson("{\"data\":[" +
                        "{\"vmid\":410,\"name\":\"provisioning\",\"status\":\"running\",\"uptime\":5,\"template\":0}" +
                        "]}")));
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/410/config"))
                .willReturn(okJson("{\"data\":{\"description\":\"jenkins-managed;cloud:test-cloud\"}}")));

        new ProxmoxOrphanCleanup().cleanupCloud(cloud, j.jenkins);

        verify(0, deleteRequestedFor(urlPathEqualTo("/api2/json/nodes/pve1/qemu/410")));
    }

    @Test
    public void cleanup_destroysOrphanedStoppedVmRegardlessOfGracePeriod() throws Exception {
        ProxmoxCloud cloud = cloudPointingAtWireMock();
        // A stopped jenkins-managed orphan cannot be a live provision, so it is destroyed even with
        // zero uptime (the grace period only protects running VMs).
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu"))
                .willReturn(okJson("{\"data\":[" +
                        "{\"vmid\":411,\"name\":\"leaked\",\"status\":\"stopped\",\"uptime\":0,\"template\":0}" +
                        "]}")));
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/411/config"))
                .willReturn(okJson("{\"data\":{\"description\":\"jenkins-managed;cloud:test-cloud\"}}")));
        stubFor(post(urlEqualTo("/api2/json/nodes/pve1/qemu/411/status/stop"))
                .willReturn(okJson("{\"data\":\"UPID:pve1:stop\"}")));
        stubFor(delete(urlPathEqualTo("/api2/json/nodes/pve1/qemu/411"))
                .willReturn(okJson("{\"data\":\"UPID:pve1:destroy\"}")));
        stubFor(get(urlPathMatching("/api2/json/nodes/pve1/tasks/.*"))
                .willReturn(okJson("{\"data\":{\"status\":\"stopped\",\"exitstatus\":\"OK\"}}")));

        new ProxmoxOrphanCleanup().cleanupCloud(cloud, j.jenkins);

        verify(deleteRequestedFor(urlPathEqualTo("/api2/json/nodes/pve1/qemu/411")));
    }

    private static long farFuture() {
        return System.currentTimeMillis() + 1_000_000L;
    }
}
