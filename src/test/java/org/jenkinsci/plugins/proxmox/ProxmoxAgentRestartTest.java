package org.jenkinsci.plugins.proxmox;

import hudson.model.Node;
import org.jenkinsci.plugins.proxmox.config.JavaDistribution;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.junit.jupiter.JenkinsSessionExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies a {@link ProxmoxAgent} survives a Jenkins restart (issue #2). Before the fix the agent
 * implemented {@code EphemeralNode} and was wiped on startup, so an in-flight pipeline could not
 * resume. The agent is reloaded from {@code $JENKINS_HOME/nodes/<name>/config.xml} with its
 * identity and use-count intact.
 */
class ProxmoxAgentRestartTest {

    @RegisterExtension
    static JenkinsSessionExtension session = new JenkinsSessionExtension();

    @Test
    void agentSurvivesRestart() throws Throwable {
        session.then(r -> {
            ProxmoxLauncher launcher = new ProxmoxLauncher("ssh-cred", "java", "", 1, null, JavaDistribution.NONE, 0, "", "");
            ProxmoxAgent agent = new ProxmoxAgent("restart-agent", "/home/jenkins", 1, Node.Mode.NORMAL,
                    "linux", launcher, "test-cloud", "test-template", "pve1", 307, 10, 0, null);
            agent.incrementUses();
            agent.incrementUses();
            r.jenkins.addNode(agent);
            assertNotNull(r.jenkins.getNode("restart-agent"));
        });

        session.then(r -> {
            Node node = r.jenkins.getNode("restart-agent");
            assertNotNull(node, "agent should survive restart (no longer EphemeralNode)");
            assertTrue(node instanceof ProxmoxAgent);
            ProxmoxAgent agent = (ProxmoxAgent) node;
            assertEquals(307, agent.getVmId());
            assertEquals("pve1", agent.getProxmoxNode());
            assertEquals("test-cloud", agent.getCloudName());
            assertEquals(2, agent.getTotalUses());
        });
    }
}
