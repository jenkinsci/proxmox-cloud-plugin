package org.jenkinsci.plugins.proxmox;

import hudson.model.Computer;
import hudson.model.Node;
import org.jenkinsci.plugins.proxmox.config.JavaDistribution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests the use-counting that drives "Builds Run" and {@code maxTotalUses}. The listener counts at the
 * executor level so it works for Pipeline {@code node} blocks, not just freestyle builds (issue #10).
 */
@WithJenkins
class ProxmoxBuildListenerTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    private ProxmoxAgent newAgent(String name, int vmId) throws Exception {
        ProxmoxLauncher launcher = new ProxmoxLauncher("ssh-cred", "java", "", 1, null, JavaDistribution.NONE, 0);
        return new ProxmoxAgent(name, "/home/jenkins", 1, Node.Mode.NORMAL, "linux",
                launcher, "test-cloud", "test-template", "pve1", vmId, 10, 0, null);
    }

    @Test
    void countsEachTaskOnAProxmoxAgent() throws Exception {
        ProxmoxAgent agent = newAgent("count-agent", 320);
        j.jenkins.addNode(agent);
        Computer computer = j.jenkins.getComputer("count-agent");
        assertNotNull(computer, "computer should exist after the node is added");

        assertEquals(0, agent.getTotalUses());
        ProxmoxBuildListener.countUse(computer);
        ProxmoxBuildListener.countUse(computer);
        assertEquals(2, agent.getTotalUses(), "each task on a Proxmox agent counts as one build");
    }

    @Test
    void ignoresNonProxmoxComputersAndNull() {
        // The controller's built-in computer must not be counted, and a null owner must be a no-op.
        Computer builtIn = j.jenkins.getComputer("");
        assertNotNull(builtIn);
        ProxmoxBuildListener.countUse(builtIn);
        ProxmoxBuildListener.countUse(null);
        // Success == no ClassCastException / NPE thrown.
    }

    @Test
    void shouldReapNowOnlyWhenCappedAndOtherwiseIdle() {
        // Unlimited reuse (maxTotalUses <= 0) never reaps.
        assertFalse(ProxmoxBuildListener.shouldReapNow(0, 5, false));
        // Under the cap: keep the agent for more builds.
        assertFalse(ProxmoxBuildListener.shouldReapNow(2, 1, false));
        // At the cap but another executor still busy: let it drain, do not reap yet.
        assertFalse(ProxmoxBuildListener.shouldReapNow(2, 2, true));
        // At the cap and otherwise idle: reap immediately.
        assertTrue(ProxmoxBuildListener.shouldReapNow(2, 2, false));
        // Over the cap and idle (e.g. multi-executor overshoot): also reap.
        assertTrue(ProxmoxBuildListener.shouldReapNow(2, 3, false));
    }
}
