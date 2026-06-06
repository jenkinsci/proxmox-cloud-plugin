package org.jenkinsci.plugins.proxmox;

import hudson.model.Computer;
import hudson.model.Node;
import org.jenkinsci.plugins.proxmox.config.JavaInstallation;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests the use-counting that drives "Builds Run" and {@code maxTotalUses}. The listener counts at the
 * executor level so it works for Pipeline {@code node} blocks, not just freestyle builds (issue #10).
 */
public class ProxmoxBuildListenerTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private ProxmoxAgent newAgent(String name, int vmId) throws Exception {
        ProxmoxLauncher launcher = new ProxmoxLauncher("ssh-cred", "java", "", 1, null, JavaInstallation.NONE);
        return new ProxmoxAgent(name, "/home/jenkins", 1, Node.Mode.NORMAL, "linux",
                launcher, "test-cloud", "test-template", "pve1", vmId, 10, 0);
    }

    @Test
    public void countsEachTaskOnAProxmoxAgent() throws Exception {
        ProxmoxAgent agent = newAgent("count-agent", 320);
        j.jenkins.addNode(agent);
        Computer computer = j.jenkins.getComputer("count-agent");
        assertNotNull("computer should exist after the node is added", computer);

        assertEquals(0, agent.getTotalUses());
        ProxmoxBuildListener.countUse(computer);
        ProxmoxBuildListener.countUse(computer);
        assertEquals("each task on a Proxmox agent counts as one build", 2, agent.getTotalUses());
    }

    @Test
    public void ignoresNonProxmoxComputersAndNull() {
        // The controller's built-in computer must not be counted, and a null owner must be a no-op.
        Computer builtIn = j.jenkins.getComputer("");
        assertNotNull(builtIn);
        ProxmoxBuildListener.countUse(builtIn);
        ProxmoxBuildListener.countUse(null);
        // Success == no ClassCastException / NPE thrown.
    }

    @Test
    public void shouldReapNowOnlyWhenCappedAndOtherwiseIdle() {
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
