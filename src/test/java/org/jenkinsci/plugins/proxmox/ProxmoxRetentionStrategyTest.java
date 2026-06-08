package org.jenkinsci.plugins.proxmox;

import hudson.model.Node;
import org.jenkinsci.plugins.proxmox.config.JavaDistribution;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for {@link ProxmoxRetentionStrategy}: the stateless construction contract, the
 * {@code isAcceptingTasks} dispatch gate, and the idle-guarded max-uses termination decision
 * (issue #12). The decision logic lives in two pure static helpers so the multi-executor and
 * mid-build cases can be exercised without a live agent; the wiring tests confirm the override is
 * reachable through a real {@link ProxmoxComputer}.
 */
public class ProxmoxRetentionStrategyTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private ProxmoxAgent newAgent(String name, int vmId, int idleMinutes, int maxUses) throws Exception {
        ProxmoxLauncher launcher = new ProxmoxLauncher("ssh-cred", "java", "", 1, null, JavaDistribution.NONE, 0);
        return new ProxmoxAgent(name, "/home/jenkins", 1, Node.Mode.NORMAL, "linux",
                launcher, "test-cloud", "test-template", "pve1", vmId, idleMinutes, maxUses);
    }

    @Test
    public void testConstruction() {
        // The strategy is stateless; idle-timeout and max-uses now live on the ProxmoxAgent and are
        // read live in check(). Construction must remain trivial so it can be attached to every agent.
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy();
        assertNotNull(strategy);
    }

    // --- acceptsMoreTasks: the isAcceptingTasks dispatch gate ---

    @Test
    public void acceptsMoreTasksUnlimitedWhenCapNonPositive() {
        // maxTotalUses <= 0 means unlimited reuse: always accept, whatever the counts.
        assertTrue(ProxmoxRetentionStrategy.acceptsMoreTasks(0, 0, 0));
        assertTrue(ProxmoxRetentionStrategy.acceptsMoreTasks(0, 99, 5));
        assertTrue(ProxmoxRetentionStrategy.acceptsMoreTasks(-1, 99, 5));
    }

    @Test
    public void acceptsMoreTasksBelowCap() {
        assertTrue(ProxmoxRetentionStrategy.acceptsMoreTasks(3, 0, 0));
        assertTrue(ProxmoxRetentionStrategy.acceptsMoreTasks(3, 2, 0));
    }

    @Test
    public void acceptsMoreTasksRejectsAtOrAboveCap() {
        assertFalse(ProxmoxRetentionStrategy.acceptsMoreTasks(2, 2, 0));
        assertFalse(ProxmoxRetentionStrategy.acceptsMoreTasks(2, 3, 0));
    }

    @Test
    public void acceptsMoreTasksCountsInFlightBuilds() {
        // Multi-executor: completed + in-flight must not exceed the cap, even though the use count
        // only rises at completion. cap=2 with one completed + one running build => reject.
        assertFalse(ProxmoxRetentionStrategy.acceptsMoreTasks(2, 1, 1));
        // cap=3: one completed + one running leaves room for exactly one more, but not two.
        assertTrue(ProxmoxRetentionStrategy.acceptsMoreTasks(3, 1, 1));
        assertFalse(ProxmoxRetentionStrategy.acceptsMoreTasks(3, 1, 2));
    }

    // --- shouldTerminateForMaxUses: the idle-guarded check() termination ---

    @Test
    public void shouldNotTerminateWhenUnlimited() {
        assertFalse(ProxmoxRetentionStrategy.shouldTerminateForMaxUses(0, 5, true));
        assertFalse(ProxmoxRetentionStrategy.shouldTerminateForMaxUses(-1, 5, true));
    }

    @Test
    public void shouldNotTerminateBelowCap() {
        assertFalse(ProxmoxRetentionStrategy.shouldTerminateForMaxUses(3, 2, true));
    }

    @Test
    public void shouldNotTerminateCappedButBusy() {
        // The core fix for issue #12: a capped agent still running a build (not idle) must NOT be
        // killed -- it drains first, then check() reaps it once idle.
        assertFalse(ProxmoxRetentionStrategy.shouldTerminateForMaxUses(2, 2, false));
        assertFalse(ProxmoxRetentionStrategy.shouldTerminateForMaxUses(2, 3, false));
    }

    @Test
    public void shouldTerminateCappedAndIdle() {
        assertTrue(ProxmoxRetentionStrategy.shouldTerminateForMaxUses(2, 2, true));
        assertTrue(ProxmoxRetentionStrategy.shouldTerminateForMaxUses(2, 3, true));
    }

    // --- retainsMinimum: the warm-pool idle-termination gate (issue #20) ---

    @Test
    public void retainsMinimumFalseWhenNoMinimumConfigured() {
        assertFalse(ProxmoxRetentionStrategy.retainsMinimum(1, 0));
    }

    @Test
    public void retainsMinimumTrueAtOrBelowMinimum() {
        assertTrue("below the minimum is retained", ProxmoxRetentionStrategy.retainsMinimum(1, 2));
        assertTrue("exactly at the minimum is retained", ProxmoxRetentionStrategy.retainsMinimum(2, 2));
    }

    @Test
    public void retainsMinimumFalseAboveMinimum() {
        // Surplus above the minimum is reaped normally.
        assertFalse(ProxmoxRetentionStrategy.retainsMinimum(3, 2));
    }

    @Test
    public void retainsMinimumFalseWithNoActiveAgents() {
        assertFalse(ProxmoxRetentionStrategy.retainsMinimum(0, 2));
    }

    // --- isAcceptingTasks: real ProxmoxComputer wiring ---

    @Test
    public void isAcceptingTasksFlipsToFalseAtCap() throws Exception {
        ProxmoxAgent agent = newAgent("retention-cap", 330, 10, 2); // maxTotalUses = 2
        j.jenkins.addNode(agent);
        ProxmoxComputer computer = (ProxmoxComputer) j.jenkins.getComputer("retention-cap");
        assertNotNull(computer);

        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy();
        // The computer is idle (countBusy() == 0), so the gate tracks the completed-use count.
        assertTrue("fresh agent accepts work", strategy.isAcceptingTasks(computer));
        agent.incrementUses();
        assertTrue("one use, below cap, still accepts", strategy.isAcceptingTasks(computer));
        agent.incrementUses();
        assertFalse("at cap, stops accepting new builds", strategy.isAcceptingTasks(computer));
    }

    @Test
    public void isAcceptingTasksAlwaysTrueWhenUnlimited() throws Exception {
        ProxmoxAgent agent = newAgent("retention-unlimited", 331, 10, 0); // 0 => unlimited reuse
        j.jenkins.addNode(agent);
        ProxmoxComputer computer = (ProxmoxComputer) j.jenkins.getComputer("retention-unlimited");
        assertNotNull(computer);

        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy();
        agent.incrementUses();
        agent.incrementUses();
        agent.incrementUses();
        assertTrue("unlimited agents never stop accepting", strategy.isAcceptingTasks(computer));
    }
}
