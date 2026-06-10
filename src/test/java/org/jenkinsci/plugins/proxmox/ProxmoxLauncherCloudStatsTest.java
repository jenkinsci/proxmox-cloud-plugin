package org.jenkinsci.plugins.proxmox;

import hudson.model.Node;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecution;
import org.jenkinsci.plugins.cloudstats.PhaseExecutionAttachment;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.proxmox.config.JavaDistribution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that {@link ProxmoxLauncher} records launch failures against the cloud-stats activity, so the
 * cause is visible under Manage Jenkins -&gt; Cloud Statistics. cloud-stats' own {@code onLaunchFailure}
 * hook attaches nothing, and the failed agent (with its launch log) is reaped shortly after, so the
 * activity is the only durable record of why a launch failed.
 */
@WithJenkins
class ProxmoxLauncherCloudStatsTest {

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    private static ProxmoxLauncher launcher() {
        return new ProxmoxLauncher("ssh-cred", "java", "", 60, null, JavaDistribution.NONE, 21);
    }

    private static ProxmoxAgent agent(String name, int vmId, ProvisioningActivity.Id id) throws Exception {
        return new ProxmoxAgent(name, "/home/jenkins", 1, Node.Mode.NORMAL, "linux",
                launcher(), "test-cloud", "test-template", "pve1", vmId, 10, 0, id);
    }

    @Test
    void launchFailureAttachesExceptionToActivity() throws Exception {
        ProvisioningActivity.Id id = new ProvisioningActivity.Id("test-cloud", "test-template");
        ProvisioningActivity activity = CloudStatistics.ProvisioningListener.get().onStarted(id);
        activity.enterIfNotAlready(ProvisioningActivity.Phase.LAUNCHING);

        ProxmoxAgent agent = agent("agent-launch-fail", 330, id);
        launcher().recordLaunchFailure(agent, new IOException("SSH on 10.0.0.5:22 not reachable"));

        // A FAIL attachment marks the activity completed, so fetch it via the completed-aware lookup.
        ProvisioningActivity after = CloudStatistics.get().getPotentiallyCompletedActivityFor(id);
        assertNotNull(after);
        assertEquals(ProvisioningActivity.Status.FAIL, after.getStatus());
        PhaseExecution launching = after.getPhaseExecution(ProvisioningActivity.Phase.LAUNCHING);
        assertNotNull(launching);
        assertTrue(launching.getAttachments(PhaseExecutionAttachment.ExceptionAttachment.class).stream()
                        .anyMatch(a -> a.getStatus() == ProvisioningActivity.Status.FAIL),
                "the launch-failure exception must be attached to the LAUNCHING phase");
    }

    @Test
    void untrackedAgentLaunchFailureIsNoOp() throws Exception {
        // A null id means the agent opted out of tracking; recording must not throw.
        ProxmoxAgent agent = agent("agent-untracked", 331, null);
        assertDoesNotThrow(() -> launcher().recordLaunchFailure(agent, new IOException("boom")));
    }
}
