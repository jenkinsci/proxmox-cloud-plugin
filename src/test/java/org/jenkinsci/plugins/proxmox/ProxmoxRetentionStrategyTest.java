package org.jenkinsci.plugins.proxmox;

import hudson.model.Node;
import org.jenkinsci.plugins.proxmox.config.JavaDistribution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProxmoxRetentionStrategy}: the stateless construction contract, the
 * {@code isAcceptingTasks} dispatch gate, and the idle-guarded max-uses termination decision
 * (issue #12). The decision logic lives in two pure static helpers so the multi-executor and
 * mid-build cases can be exercised without a live agent; the wiring tests confirm the override is
 * reachable through a real {@link ProxmoxComputer}.
 */
@WithJenkins
class ProxmoxRetentionStrategyTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    private ProxmoxAgent newAgent(String name, int vmId, int idleMinutes, int maxUses) throws Exception {
        ProxmoxLauncher launcher = new ProxmoxLauncher("ssh-cred", "java", "", 1, null, JavaDistribution.NONE, 0, null);
        return new ProxmoxAgent(name, "/home/jenkins", 1, Node.Mode.NORMAL, "linux",
                launcher, "test-cloud", "test-template", "pve1", vmId, idleMinutes, maxUses, null);
    }

    @Test
    void testConstruction() {
        // The strategy is stateless; idle-timeout and max-uses now live on the ProxmoxAgent and are
        // read live in check(). Construction must remain trivial so it can be attached to every agent.
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy();
        assertNotNull(strategy);
    }

    // --- acceptsMoreTasks: the isAcceptingTasks dispatch gate ---

    @Test
    void acceptsMoreTasksUnlimitedWhenCapNonPositive() {
        // maxTotalUses <= 0 means unlimited reuse: always accept, whatever the count.
        assertTrue(ProxmoxRetentionStrategy.acceptsMoreTasks(0, 0));
        assertTrue(ProxmoxRetentionStrategy.acceptsMoreTasks(0, 99));
        assertTrue(ProxmoxRetentionStrategy.acceptsMoreTasks(-1, 99));
    }

    @Test
    void acceptsMoreTasksBelowCap() {
        assertTrue(ProxmoxRetentionStrategy.acceptsMoreTasks(3, 0));
        assertTrue(ProxmoxRetentionStrategy.acceptsMoreTasks(3, 2));
    }

    @Test
    void acceptsMoreTasksRejectsAtOrAboveCap() {
        // Uses are counted at task ACCEPTANCE (ProxmoxBuildListener.taskAccepted), so an in-flight
        // build is already in totalUses and reaching the cap blocks further dispatch immediately,
        // with no window around executor release for a queued build to slip through (EC2 parity).
        assertFalse(ProxmoxRetentionStrategy.acceptsMoreTasks(1, 1));
        assertFalse(ProxmoxRetentionStrategy.acceptsMoreTasks(2, 2));
        assertFalse(ProxmoxRetentionStrategy.acceptsMoreTasks(2, 3));
    }

    // --- shouldTerminateForMaxUses: the idle-guarded check() termination ---

    @Test
    void shouldNotTerminateWhenUnlimited() {
        assertFalse(ProxmoxRetentionStrategy.shouldTerminateForMaxUses(0, 5, true));
        assertFalse(ProxmoxRetentionStrategy.shouldTerminateForMaxUses(-1, 5, true));
    }

    @Test
    void shouldNotTerminateBelowCap() {
        assertFalse(ProxmoxRetentionStrategy.shouldTerminateForMaxUses(3, 2, true));
    }

    @Test
    void shouldNotTerminateCappedButBusy() {
        // The core fix for issue #12: a capped agent still running a build (not idle) must NOT be
        // killed -- it drains first, then check() reaps it once idle.
        assertFalse(ProxmoxRetentionStrategy.shouldTerminateForMaxUses(2, 2, false));
        assertFalse(ProxmoxRetentionStrategy.shouldTerminateForMaxUses(2, 3, false));
    }

    @Test
    void shouldTerminateCappedAndIdle() {
        assertTrue(ProxmoxRetentionStrategy.shouldTerminateForMaxUses(2, 2, true));
        assertTrue(ProxmoxRetentionStrategy.shouldTerminateForMaxUses(2, 3, true));
    }

    // --- retainsMinimum: the warm-pool idle-termination gate (issue #20) ---

    @Test
    void retainsMinimumFalseWhenNoMinimumConfigured() {
        assertFalse(ProxmoxRetentionStrategy.retainsMinimum(1, 0));
    }

    @Test
    void retainsMinimumTrueAtOrBelowMinimum() {
        assertTrue(ProxmoxRetentionStrategy.retainsMinimum(1, 2), "below the minimum is retained");
        assertTrue(ProxmoxRetentionStrategy.retainsMinimum(2, 2), "exactly at the minimum is retained");
    }

    @Test
    void retainsMinimumFalseAboveMinimum() {
        // Surplus above the minimum is reaped normally.
        assertFalse(ProxmoxRetentionStrategy.retainsMinimum(3, 2));
    }

    @Test
    void retainsMinimumFalseWithNoActiveAgents() {
        assertFalse(ProxmoxRetentionStrategy.retainsMinimum(0, 2));
    }

    // --- isAcceptingTasks: real ProxmoxComputer wiring ---

    @Test
    void isAcceptingTasksFlipsToFalseAtCap() throws Exception {
        ProxmoxAgent agent = newAgent("retention-cap", 330, 10, 2); // maxTotalUses = 2
        j.jenkins.addNode(agent);
        ProxmoxComputer computer = (ProxmoxComputer) j.jenkins.getComputer("retention-cap");
        assertNotNull(computer);

        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy();
        // The computer is idle (countBusy() == 0), so the gate tracks the completed-use count.
        assertTrue(strategy.isAcceptingTasks(computer), "fresh agent accepts work");
        agent.incrementUses();
        assertTrue(strategy.isAcceptingTasks(computer), "one use, below cap, still accepts");
        agent.incrementUses();
        assertFalse(strategy.isAcceptingTasks(computer), "at cap, stops accepting new builds");
    }

    @Test
    void isAcceptingTasksAlwaysTrueWhenUnlimited() throws Exception {
        ProxmoxAgent agent = newAgent("retention-unlimited", 331, 10, 0); // 0 => unlimited reuse
        j.jenkins.addNode(agent);
        ProxmoxComputer computer = (ProxmoxComputer) j.jenkins.getComputer("retention-unlimited");
        assertNotNull(computer);

        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy();
        agent.incrementUses();
        agent.incrementUses();
        agent.incrementUses();
        assertTrue(strategy.isAcceptingTasks(computer), "unlimited agents never stop accepting");
    }
}
