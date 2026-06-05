package org.jenkinsci.plugins.proxmox;

import hudson.model.Node;
import org.jenkinsci.plugins.proxmox.config.JavaInstallation;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.RestartableJenkinsRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Verifies a {@link ProxmoxAgent} survives a Jenkins restart (issue #2). Before the fix the agent
 * implemented {@code EphemeralNode} and was wiped on startup, so an in-flight pipeline could not
 * resume. The agent is reloaded from {@code $JENKINS_HOME/nodes/<name>/config.xml} with its
 * identity and use-count intact.
 */
public class ProxmoxAgentRestartTest {

    @Rule
    public RestartableJenkinsRule rr = new RestartableJenkinsRule();

    @Test
    public void agentSurvivesRestart() {
        rr.then(r -> {
            ProxmoxLauncher launcher = new ProxmoxLauncher("ssh-cred", "java", "", 1, null, JavaInstallation.NONE);
            ProxmoxRetentionStrategy retention = new ProxmoxRetentionStrategy(10, 0);
            ProxmoxAgent agent = new ProxmoxAgent("restart-agent", "/home/jenkins", 1, Node.Mode.NORMAL,
                    "linux", launcher, retention, "test-cloud", "test-template", "pve1", 307);
            agent.incrementUses();
            agent.incrementUses();
            r.jenkins.addNode(agent);
            assertNotNull(r.jenkins.getNode("restart-agent"));
        });

        rr.then(r -> {
            Node node = r.jenkins.getNode("restart-agent");
            assertNotNull("agent should survive restart (no longer EphemeralNode)", node);
            assertTrue(node instanceof ProxmoxAgent);
            ProxmoxAgent agent = (ProxmoxAgent) node;
            assertEquals(307, agent.getVmId());
            assertEquals("pve1", agent.getProxmoxNode());
            assertEquals("test-cloud", agent.getCloudName());
            assertEquals(2, agent.getTotalUses());
        });
    }
}
