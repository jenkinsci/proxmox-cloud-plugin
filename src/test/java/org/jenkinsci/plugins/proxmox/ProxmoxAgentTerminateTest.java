package org.jenkinsci.plugins.proxmox;

import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.util.Secret;
import org.jenkinsci.plugins.proxmox.config.JavaDistribution;
import org.jenkinsci.plugins.proxmox.config.ProxmoxTokenCredentialsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Tests the ownership-verified, idempotent VM teardown in {@link ProxmoxAgent#_terminate} (issue #17):
 * a stale or duplicate terminate must never destroy a VM whose id has been reused, and a VM that is
 * already gone must be treated as successfully destroyed rather than throwing.
 */
@WithJenkins
class ProxmoxAgentTerminateTest {

    @RegisterExtension
    static WireMockExtension wm = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .configureStaticDsl(true)
            .build();

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new ProxmoxTokenCredentialsImpl(CredentialsScope.GLOBAL, "proxmox-cred", "desc",
                        "user@pve!token", Secret.fromString("secret")));
        ProxmoxCloud cloud = new ProxmoxCloud("test-cloud");
        cloud.setApiUrl("http://localhost:" + wm.getPort());
        cloud.setCredentialsId("proxmox-cred");
        cloud.setOperationTimeout(60);
        j.jenkins.clouds.add(cloud);
    }

    private ProxmoxAgent newAgent(int vmId) throws Exception {
        ProxmoxLauncher launcher = new ProxmoxLauncher("ssh-cred", "java", "", 1, null, JavaDistribution.NONE, 0, "", "");
        return new ProxmoxAgent("jenkins-agent-" + vmId, "/home/jenkins", 1, Node.Mode.NORMAL, "linux",
                launcher, "test-cloud", "test-template", "pve1", vmId, 10, 0, null);
    }

    private void stubTaskPolling() {
        stubFor(get(urlPathMatching("/api2/json/nodes/pve1/tasks/.*"))
                .willReturn(okJson("{\"data\":{\"status\":\"stopped\",\"exitstatus\":\"OK\"}}")));
    }

    @Test
    void destroysVmThatStillCarriesOurMarker() throws Exception {
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/305/config"))
                .willReturn(okJson("{\"data\":{\"description\":\"jenkins-managed;cloud:test-cloud;template:linux\"}}")));
        stubFor(post(urlEqualTo("/api2/json/nodes/pve1/qemu/305/status/shutdown"))
                .willReturn(okJson("{\"data\":\"UPID:pve1:shutdown\"}")));
        stubFor(delete(urlPathEqualTo("/api2/json/nodes/pve1/qemu/305"))
                .willReturn(okJson("{\"data\":\"UPID:pve1:destroy\"}")));
        stubTaskPolling();

        newAgent(305)._terminate(TaskListener.NULL);

        verify(deleteRequestedFor(urlPathEqualTo("/api2/json/nodes/pve1/qemu/305")));
    }

    @Test
    void skipsDestroyWhenVmIdHasBeenReused() throws Exception {
        // The id now hosts a VM that is not ours -> must not be destroyed.
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/305/config"))
                .willReturn(okJson("{\"data\":{\"description\":\"someone else's VM\"}}")));

        newAgent(305)._terminate(TaskListener.NULL);

        verify(0, deleteRequestedFor(urlPathEqualTo("/api2/json/nodes/pve1/qemu/305")));
    }

    @Test
    void treatsAlreadyGoneVmAsDestroyed() throws Exception {
        // A duplicate terminate finds the VM gone (404 on the config read) -> no-op, no exception.
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/305/config"))
                .willReturn(aResponse().withStatus(404).withBody("{\"data\":null}")));

        newAgent(305)._terminate(TaskListener.NULL); // must not throw

        verify(0, deleteRequestedFor(urlPathEqualTo("/api2/json/nodes/pve1/qemu/305")));
    }
}
