package org.jenkinsci.plugins.proxmox;

import hudson.model.Node;
import hudson.slaves.EphemeralNode;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.proxmox.config.JavaInstallation;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/**
 * Persistence-related tests for {@link ProxmoxAgent}. The fix for issue #2 removed the
 * {@code EphemeralNode} marker so agents are persisted and reloaded across restarts.
 */
public class ProxmoxAgentTest {

    @Rule
    public JenkinsRule j = new JenkinsRule();

    private ProxmoxAgent newAgent(String name, int vmId) throws Exception {
        ProxmoxLauncher launcher = new ProxmoxLauncher("ssh-cred", "java", "", 1, null, JavaInstallation.NONE);
        ProxmoxRetentionStrategy retention = new ProxmoxRetentionStrategy(10, 0);
        return new ProxmoxAgent(name, "/home/jenkins", 1, Node.Mode.NORMAL, "linux",
                launcher, retention, "test-cloud", "test-template", "pve1", vmId);
    }

    @Test
    public void agentIsNotEphemeral() {
        // Regression guard for issue #2: an EphemeralNode is wiped on restart and cannot resume jobs.
        assertFalse("ProxmoxAgent must not implement EphemeralNode",
                EphemeralNode.class.isAssignableFrom(ProxmoxAgent.class));
    }

    @Test
    public void xstreamRoundTripPreservesIdentityAndUseCount() throws Exception {
        ProxmoxAgent agent = newAgent("agent-rt", 305);
        agent.incrementUses();
        agent.incrementUses();

        String xml = Jenkins.XSTREAM2.toXML(agent);
        ProxmoxAgent restored = (ProxmoxAgent) Jenkins.XSTREAM2.fromXML(xml);

        assertEquals(305, restored.getVmId());
        assertEquals("pve1", restored.getProxmoxNode());
        assertEquals("test-cloud", restored.getCloudName());
        assertEquals("test-template", restored.getTemplateName());
        // AtomicInteger use count must survive serialization (drives max-uses retention).
        assertEquals(2, restored.getTotalUses());
    }

    @Test
    public void addedNodeIsPersistedToDisk() throws Exception {
        ProxmoxAgent agent = newAgent("agent-persist", 306);
        j.jenkins.addNode(agent);

        Node loaded = j.jenkins.getNode("agent-persist");
        assertNotNull(loaded);
        assertTrue(loaded instanceof ProxmoxAgent);
        assertEquals(306, ((ProxmoxAgent) loaded).getVmId());
    }
}
