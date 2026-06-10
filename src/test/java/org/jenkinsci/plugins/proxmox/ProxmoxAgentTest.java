package org.jenkinsci.plugins.proxmox;

import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.slaves.EphemeralNode;
import jenkins.model.Jenkins;
import net.sf.json.JSONObject;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.proxmox.config.JavaDistribution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;
import org.kohsuke.stapler.StaplerRequest2;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProxmoxAgent}: persistence (issue #2) and the per-agent lifecycle overrides
 * (issue #10) exposed on the agent config page.
 */
@WithJenkins
class ProxmoxAgentTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    private ProxmoxAgent newAgent(String name, int vmId) throws Exception {
        return newAgent(name, vmId, 10, 0);
    }

    private ProxmoxAgent newAgent(String name, int vmId, int idleMinutes, int maxUses) throws Exception {
        ProxmoxLauncher launcher = new ProxmoxLauncher("ssh-cred", "java", "", 1, null, JavaDistribution.NONE, 0);
        return new ProxmoxAgent(name, "/home/jenkins", 1, Node.Mode.NORMAL, "linux",
                launcher, "test-cloud", "test-template", "pve1", vmId, idleMinutes, maxUses, null);
    }

    @Test
    void agentIsNotEphemeral() {
        // Regression guard for issue #2: an EphemeralNode is wiped on restart and cannot resume jobs.
        assertFalse(EphemeralNode.class.isAssignableFrom(ProxmoxAgent.class),
                "ProxmoxAgent must not implement EphemeralNode");
    }

    @Test
    void xstreamRoundTripPreservesIdentityUseCountAndLifecycle() throws Exception {
        ProxmoxAgent agent = newAgent("agent-rt", 305, 15, 7);
        agent.incrementUses();
        agent.incrementUses();

        String xml = Jenkins.XSTREAM2.toXML(agent);
        ProxmoxAgent restored = (ProxmoxAgent) Jenkins.XSTREAM2.fromXML(xml);

        assertEquals(305, restored.getVmId());
        assertEquals("pve1", restored.getProxmoxNode());
        assertEquals("test-cloud", restored.getCloudName());
        assertEquals("test-template", restored.getTemplateName());
        // Use count must survive serialization (drives max-uses retention).
        assertEquals(2, restored.getTotalUses());
        // Per-agent lifecycle overrides must persist across restart, not reset to template defaults.
        assertEquals(15, restored.getIdleTerminationMinutes());
        assertEquals(7, restored.getMaxTotalUses());
        // The stateless retention strategy must survive too.
        assertTrue(restored.getRetentionStrategy() instanceof ProxmoxRetentionStrategy);
    }

    private ProxmoxAgent newTrackedAgent(String name, int vmId, ProvisioningActivity.Id id) throws Exception {
        ProxmoxLauncher launcher = new ProxmoxLauncher("ssh-cred", "java", "", 1, null, JavaDistribution.NONE, 0);
        return new ProxmoxAgent(name, "/home/jenkins", 1, Node.Mode.NORMAL, "linux",
                launcher, "test-cloud", "test-template", "pve1", vmId, 10, 0, id);
    }

    @Test
    void getIdReturnsProvisioningActivityId() throws Exception {
        ProvisioningActivity.Id id = new ProvisioningActivity.Id("test-cloud", "test-template");
        ProxmoxAgent agent = newTrackedAgent("agent-tracked", 320, id);
        // cloud-stats correlates the planned node, node and computer by this exact instance.
        assertSame(id, agent.getId());
    }

    @Test
    void provisioningIdSurvivesXStream() throws Exception {
        ProvisioningActivity.Id id = new ProvisioningActivity.Id("test-cloud", "test-template");
        ProxmoxAgent agent = newTrackedAgent("agent-tracked-rt", 321, id);

        String xml = Jenkins.XSTREAM2.toXML(agent);
        ProxmoxAgent restored = (ProxmoxAgent) Jenkins.XSTREAM2.fromXML(xml);

        // Id equality is by fingerprint, so the reloaded agent still resolves to the same activity
        // after a controller restart.
        assertNotNull(restored.getId());
        assertEquals(id.getFingerprint(), restored.getId().getFingerprint());
    }

    @Test
    void computerDelegatesIdToNode() throws Exception {
        // cloud-stats reads the id off the Computer (for launch/operate phases); ProxmoxComputer must
        // forward it from the node so the same activity is tracked end to end.
        ProvisioningActivity.Id id = new ProvisioningActivity.Id("test-cloud", "test-template");
        ProxmoxAgent agent = newTrackedAgent("agent-computer", 323, id);
        j.jenkins.addNode(agent);

        Computer computer = agent.toComputer();
        assertNotNull(computer);
        assertTrue(computer instanceof ProxmoxComputer);
        ProvisioningActivity.Id computerId = ((ProxmoxComputer) computer).getId();
        assertNotNull(computerId);
        assertEquals(id.getFingerprint(), computerId.getFingerprint());
    }

    @Test
    void nullProvisioningIdIsTolerated() throws Exception {
        // TrackedItem permits a null id to opt out of tracking (e.g. an agent serialized before the
        // cloud-stats integration); getId() must simply return null rather than blow up.
        ProxmoxAgent agent = newTrackedAgent("agent-untracked", 322, null);
        assertNull(agent.getId());
    }

    @Test
    void getCloudReturnsNullWhenCloudNotRegistered() throws Exception {
        // newAgent uses cloudName "test-cloud", which is not registered as a Jenkins cloud here.
        ProxmoxAgent agent = newAgent("agent-nocloud", 340);
        assertNull(agent.getCloud());
    }

    @Test
    void addedNodeIsPersistedToDisk() throws Exception {
        ProxmoxAgent agent = newAgent("agent-persist", 306);
        j.jenkins.addNode(agent);

        Node loaded = j.jenkins.getNode("agent-persist");
        assertNotNull(loaded);
        assertTrue(loaded instanceof ProxmoxAgent);
        assertEquals(306, ((ProxmoxAgent) loaded).getVmId());
    }

    @Test
    void lifecycleGettersReturnConstructorValues() throws Exception {
        ProxmoxAgent agent = newAgent("agent-life", 310, 42, 9);
        assertEquals(42, agent.getIdleTerminationMinutes());
        assertEquals(9, agent.getMaxTotalUses());
    }

    @Test
    void getInstanceCapReadsTemplate() throws Exception {
        ProxmoxTemplate template = new ProxmoxTemplate("test-template", "pve1", 9000, "linux", 1);
        template.setInstanceCap(5);
        ProxmoxCloud cloud = new ProxmoxCloud("test-cloud");
        cloud.setTemplates(List.of(template));
        j.jenkins.clouds.add(cloud);

        ProxmoxAgent agent = newAgent("agent-cap", 311);
        assertEquals(5, agent.getInstanceCap(), "instance cap is read through to the owning template");
    }

    @Test
    void getInstanceCapIsZeroWhenCloudMissing() throws Exception {
        // No cloud registered: the read-only display must degrade gracefully rather than blow up.
        ProxmoxAgent agent = newAgent("agent-nocap", 312);
        assertEquals(0, agent.getInstanceCap());
    }

    @Test
    void reconfigureUpdatesLifecycleAndPreservesIdentity() throws Exception {
        ProxmoxAgent agent = newAgent("agent-reconf", 313, 30, 0);

        JSONObject form = new JSONObject();
        form.put("nodeDescription", "diagnostics");
        form.put("numExecutors", 1);
        form.put("labelString", "linux");
        form.put("mode", Node.Mode.NORMAL.name());
        form.put("idleTerminationMinutes", 120);
        form.put("maxTotalUses", 3);
        // Read-only on the form; a stray value must not override the template-backed cap.
        form.put("instanceCap", 999);

        // req is only used for the nodeProperties branch, which this form omits.
        ProxmoxAgent result = (ProxmoxAgent) agent.reconfigure((StaplerRequest2) null, form);

        assertEquals(120, result.getIdleTerminationMinutes());
        assertEquals(3, result.getMaxTotalUses());
        // Identity/placement fields are immutable and must be untouched.
        assertEquals(313, result.getVmId());
        assertEquals("test-cloud", result.getCloudName());
        assertEquals("test-template", result.getTemplateName());
        assertEquals("pve1", result.getProxmoxNode());
    }

    @Test
    void isOfflineDeadAppliesGraceWindow() {
        long now = 10_000_000L;
        long grace = 60_000L; // 60s
        assertFalse(ProxmoxAgent.isOfflineDead(-1L, now, grace), "online/connecting (-1) is never dead");
        assertFalse(ProxmoxAgent.isOfflineDead(now - 30_000L, now, grace), "offline within grace is not dead");
        assertTrue(ProxmoxAgent.isOfflineDead(now - 120_000L, now, grace), "offline beyond grace is dead");
        assertTrue(ProxmoxAgent.isOfflineDead(now - grace, now, grace), "boundary (exactly grace) counts as dead");
    }

    @Test
    void getOfflineSinceFallsBackToCreatedAtWithoutComputer() throws Exception {
        // A node not registered with Jenkins has no computer; getOfflineSince treats it as offline
        // since creation (the phantom case), so it does not artificially count toward the cap.
        ProxmoxAgent agent = newAgent("agent-nocomputer", 315);
        assertEquals(agent.getCreatedAt(), agent.getOfflineSince());
    }

    @Test
    void reconfigureRejectsNegativeLifecycleValues() throws Exception {
        ProxmoxAgent agent = newAgent("agent-neg", 314, 30, 0);

        JSONObject form = new JSONObject();
        form.put("mode", Node.Mode.NORMAL.name());
        form.put("idleTerminationMinutes", -1);
        form.put("maxTotalUses", 0);

        assertThrows(Descriptor.FormException.class, () -> agent.reconfigure((StaplerRequest2) null, form));
    }
}
