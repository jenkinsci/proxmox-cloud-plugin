package org.jenkinsci.plugins.proxmox;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import hudson.model.Node;
import hudson.model.TaskListener;
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
        return new ProxmoxAgent(name, "/home/jenkins", 1, Node.Mode.NORMAL, "linux",
                launcher, "test-cloud", "test-template", node, vmId, 10, 0);
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

    /** No agent has reported an offline time (all online/connecting). */
    private static final Map<ProxmoxAgent, Long> ALL_ONLINE = Map.of();

    @Test
    public void findDeadNodes_retainsRunningVm() throws Exception {
        ProxmoxAgent agent = newAgent("a", "pve1", 300);
        Map<String, Map<Integer, String>> status = Map.of("pve1", Map.of(300, "running"));
        assertTrue(ProxmoxOrphanCleanup.findDeadNodes(List.of(agent), status,
                ALL_ONLINE, farFuture(), NO_GRACE).isEmpty());
    }

    @Test
    public void findDeadNodes_flagsGoneVmForNodeOnlyRemoval() throws Exception {
        ProxmoxAgent agent = newAgent("a", "pve1", 301);
        Map<String, Map<Integer, String>> status = Map.of("pve1", Map.of(300, "running"));
        List<DeadAgent> dead = ProxmoxOrphanCleanup.findDeadNodes(List.of(agent), status,
                ALL_ONLINE, farFuture(), NO_GRACE);
        assertEquals(1, dead.size());
        assertEquals(301, dead.get(0).agent().getVmId());
        assertFalse("gone VM => nothing to destroy", dead.get(0).vmExists());
    }

    @Test
    public void findDeadNodes_flagsStoppedVmForDestroyAndRemoval() throws Exception {
        ProxmoxAgent agent = newAgent("a", "pve1", 301);
        Map<String, Map<Integer, String>> status = Map.of("pve1", Map.of(301, "stopped"));
        List<DeadAgent> dead = ProxmoxOrphanCleanup.findDeadNodes(List.of(agent), status,
                ALL_ONLINE, farFuture(), NO_GRACE);
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
                ALL_ONLINE, farFuture(), NO_GRACE).isEmpty());
    }

    @Test
    public void findDeadNodes_skipsAgentWithinGracePeriod() throws Exception {
        ProxmoxAgent agent = newAgent("a", "pve1", 301); // VM gone
        Map<String, Map<Integer, String>> status = Map.of("pve1", Map.of(300, "running"));
        // Agent is ~0s old; with a 600s grace it must be left alone despite the VM being gone.
        List<DeadAgent> dead = ProxmoxOrphanCleanup.findDeadNodes(List.of(agent), status,
                ALL_ONLINE, agent.getCreatedAt() + 1000, 600_000L);
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
                List.of(running, gone, stopped, unqueried), status, ALL_ONLINE, farFuture(), NO_GRACE);

        assertEquals(2, dead.size());
        DeadAgent goneResult = dead.stream().filter(d -> d.agent().getVmId() == 301).findFirst().orElseThrow();
        DeadAgent stoppedResult = dead.stream().filter(d -> d.agent().getVmId() == 302).findFirst().orElseThrow();
        assertFalse(goneResult.vmExists());
        assertTrue(stoppedResult.vmExists());
    }

    // --- findDeadNodes: offline-with-running-VM zombies (issue #16) ---

    @Test
    public void findDeadNodes_flagsRunningVmWhoseChannelIsOfflineBeyondGrace() throws Exception {
        ProxmoxAgent agent = newAgent("zombie", "pve1", 300);
        Map<String, Map<Integer, String>> status = Map.of("pve1", Map.of(300, "running"));
        long now = agent.getCreatedAt() + 600_000L;          // past the createdAt grace
        long offlineSince = now - 120_000L;                  // channel dead for 2 min
        Map<ProxmoxAgent, Long> offline = Map.of(agent, offlineSince);

        List<DeadAgent> dead = ProxmoxOrphanCleanup.findDeadNodes(List.of(agent), status,
                offline, now, 60_000L);                      // 60s grace

        assertEquals(1, dead.size());
        assertTrue("running VM still exists and must be destroyed", dead.get(0).vmExists());
        assertEquals("flagged because the channel is offline, not the run-state",
                ProxmoxOrphanCleanup.DeadReason.CHANNEL_OFFLINE, dead.get(0).reason());
    }

    @Test
    public void findDeadNodes_retainsRunningVmWhoseChannelBlippedWithinGrace() throws Exception {
        ProxmoxAgent agent = newAgent("blip", "pve1", 300);
        Map<String, Map<Integer, String>> status = Map.of("pve1", Map.of(300, "running"));
        long now = agent.getCreatedAt() + 600_000L;
        long offlineSince = now - 10_000L;                   // offline only 10s
        Map<ProxmoxAgent, Long> offline = Map.of(agent, offlineSince);

        assertTrue("a brief blip must not reap a running agent",
                ProxmoxOrphanCleanup.findDeadNodes(List.of(agent), status, offline, now, 60_000L).isEmpty());
    }

    @Test
    public void findDeadNodes_retainsRunningVmThatIsOnline() throws Exception {
        ProxmoxAgent agent = newAgent("online", "pve1", 300);
        Map<String, Map<Integer, String>> status = Map.of("pve1", Map.of(300, "running"));
        // -1 mirrors ProxmoxAgent.getOfflineSince() for an online/connecting computer.
        Map<ProxmoxAgent, Long> offline = Map.of(agent, -1L);
        assertTrue(ProxmoxOrphanCleanup.findDeadNodes(List.of(agent), status,
                offline, farFuture(), NO_GRACE).isEmpty());
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
        // Ownership is verified before destroy: the VM still carries our marker, so destroy proceeds.
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/303/config"))
                .willReturn(okJson("{\"data\":{\"description\":\"jenkins-managed;cloud:test-cloud\"}}")));
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

    @Test
    public void cleanup_removesStaleNodeButSpareesReusedVm() throws Exception {
        ProxmoxCloud cloud = cloudPointingAtWireMock();
        // A stale node points at VM 305, but that id has since been re-cloned for something else
        // (a foreign / manually-created VM). The dead node must be removed, but the VM that no longer
        // carries our marker must NOT be destroyed (issue #17: stale terminate hitting a reused id).
        ProxmoxAgent stale = newAgent("agent-stale", "pve1", 305);
        backdateCreatedAt(stale, 3_600_000L);
        j.jenkins.addNode(stale);

        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu"))
                .willReturn(okJson("{\"data\":[" +
                        "{\"vmid\":305,\"name\":\"someone-else\",\"status\":\"stopped\",\"template\":0}" +
                        "]}")));
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/305/config"))
                .willReturn(okJson("{\"data\":{\"description\":\"not ours\"}}")));
        stubFor(post(urlEqualTo("/api2/json/nodes/pve1/qemu/305/status/stop"))
                .willReturn(okJson("{\"data\":\"UPID:pve1:stop\"}")));
        stubFor(delete(urlPathEqualTo("/api2/json/nodes/pve1/qemu/305"))
                .willReturn(okJson("{\"data\":\"UPID:pve1:destroy\"}")));
        stubFor(get(urlPathMatching("/api2/json/nodes/pve1/tasks/.*"))
                .willReturn(okJson("{\"data\":{\"status\":\"stopped\",\"exitstatus\":\"OK\"}}")));

        new ProxmoxOrphanCleanup().cleanupCloud(cloud, j.jenkins);

        verify(0, deleteRequestedFor(urlPathEqualTo("/api2/json/nodes/pve1/qemu/305")));
        assertNull("the stale Jenkins node must still be removed", j.jenkins.getNode("agent-stale"));
    }

    // --- cadence + per-cloud gating (issue #19) ---

    @Test
    public void isDue_firstRunAlwaysDue() {
        assertTrue(ProxmoxOrphanCleanup.isDue(null, 1_000L, 600_000L));
    }

    @Test
    public void isDue_trueOncePeriodElapsed() {
        assertTrue("exactly at the period is due", ProxmoxOrphanCleanup.isDue(0L, 600_000L, 600_000L));
        assertTrue("past the period is due", ProxmoxOrphanCleanup.isDue(0L, 700_000L, 600_000L));
    }

    @Test
    public void isDue_falseWithinPeriod() {
        assertFalse(ProxmoxOrphanCleanup.isDue(0L, 599_999L, 600_000L));
    }

    @Test
    public void recurrencePeriod_defaultsWhenNoCloudsConfigured() {
        assertEquals(600_000L, new ProxmoxOrphanCleanup().getRecurrencePeriod());
    }

    @Test
    public void recurrencePeriod_usesSmallestEnabledCloudPeriod() {
        ProxmoxCloud a = new ProxmoxCloud("cloud-a");
        a.setCleanupOrphanedAgents(true);
        a.setOrphanCleanupPeriodSeconds(300);
        ProxmoxCloud b = new ProxmoxCloud("cloud-b");
        b.setCleanupOrphanedAgents(true);
        b.setOrphanCleanupPeriodSeconds(120);
        j.jenkins.clouds.add(a);
        j.jenkins.clouds.add(b);

        assertEquals(120_000L, new ProxmoxOrphanCleanup().getRecurrencePeriod());
    }

    @Test
    public void recurrencePeriod_honorsThirtySecondMinimum() {
        ProxmoxCloud fast = new ProxmoxCloud("cloud-fast");
        fast.setCleanupOrphanedAgents(true);
        fast.setOrphanCleanupPeriodSeconds(30); // the minimum allowed period
        j.jenkins.clouds.add(fast);

        assertEquals(30_000L, new ProxmoxOrphanCleanup().getRecurrencePeriod());
    }

    @Test
    public void recurrencePeriod_ignoresCleanupDisabledClouds() {
        ProxmoxCloud disabled = new ProxmoxCloud("cloud-disabled");
        disabled.setCleanupOrphanedAgents(false);
        disabled.setOrphanCleanupPeriodSeconds(30); // would lower the base if it were counted
        ProxmoxCloud enabled = new ProxmoxCloud("cloud-enabled");
        enabled.setCleanupOrphanedAgents(true);
        enabled.setOrphanCleanupPeriodSeconds(300);
        j.jenkins.clouds.add(disabled);
        j.jenkins.clouds.add(enabled);

        assertEquals(300_000L, new ProxmoxOrphanCleanup().getRecurrencePeriod());
    }

    @Test
    public void execute_gatesSecondRunWithinPeriod() throws Exception {
        ProxmoxCloud cloud = cloudPointingAtWireMock(); // cleanup enabled, default 600s period
        j.jenkins.clouds.add(cloud);
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu"))
                .willReturn(okJson("{\"data\":[]}")));

        ProxmoxOrphanCleanup cleanup = new ProxmoxOrphanCleanup();
        cleanup.execute(TaskListener.NULL);
        cleanup.execute(TaskListener.NULL); // immediate second pass: gated by the 600s period

        verify(1, getRequestedFor(urlEqualTo("/api2/json/nodes/pve1/qemu")));
    }

    // --- restart-required monitor when a period is reduced below the scheduled cadence (issue #19) ---

    private ProxmoxCloud cleanupCloud(String name, int periodSeconds) {
        ProxmoxCloud cloud = new ProxmoxCloud(name);
        cloud.setCleanupOrphanedAgents(true);
        cloud.setOrphanCleanupPeriodSeconds(periodSeconds);
        j.jenkins.clouds.add(cloud);
        return cloud;
    }

    @Test
    public void restartMonitor_inactiveWhenPeriodNotReduced() {
        cleanupCloud("c", 600);
        ProxmoxOrphanCleanup work = new ProxmoxOrphanCleanup();
        work.getRecurrencePeriod(); // schedules at 600s
        assertFalse(work.isRestartNeededForReducedPeriod());
        assertTrue(work.cloudsNeedingRestart().isEmpty());
    }

    @Test
    public void restartMonitor_activatesWhenPeriodReducedBelowScheduled() {
        ProxmoxCloud cloud = cleanupCloud("c", 600);
        ProxmoxOrphanCleanup work = new ProxmoxOrphanCleanup();
        work.getRecurrencePeriod(); // captures scheduled = 600s

        cloud.setOrphanCleanupPeriodSeconds(40); // reduced live, no restart yet

        assertTrue(work.isRestartNeededForReducedPeriod());
        assertEquals(List.of("c"), work.cloudsNeedingRestart());
        assertEquals(600, work.getScheduledPeriodSeconds());
        assertEquals(40, work.getEffectivePeriodSeconds());
    }

    @Test
    public void restartMonitor_clearsWhenPeriodRaisedBack() {
        ProxmoxCloud cloud = cleanupCloud("c", 600);
        ProxmoxOrphanCleanup work = new ProxmoxOrphanCleanup();
        work.getRecurrencePeriod();
        cloud.setOrphanCleanupPeriodSeconds(40);
        assertTrue(work.isRestartNeededForReducedPeriod());

        cloud.setOrphanCleanupPeriodSeconds(600); // raised back to the scheduled cadence

        assertFalse(work.isRestartNeededForReducedPeriod());
    }

    @Test
    public void restartMonitor_inactiveBeforeWorkScheduled() {
        cleanupCloud("c", 40);
        // getRecurrencePeriod() never called -> scheduledPeriodMs still -1 -> no false positive.
        assertFalse(new ProxmoxOrphanCleanup().isRestartNeededForReducedPeriod());
    }

    private static long farFuture() {
        return System.currentTimeMillis() + 1_000_000L;
    }
}
