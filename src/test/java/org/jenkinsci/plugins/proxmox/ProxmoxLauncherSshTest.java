package org.jenkinsci.plugins.proxmox;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import hudson.model.Node;
import hudson.util.Secret;
import org.jenkinsci.plugins.proxmox.api.ProxmoxException;
import org.jenkinsci.plugins.proxmox.config.JavaDistribution;
import org.jenkinsci.plugins.proxmox.config.ProxmoxTokenCredentialsImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

import java.io.OutputStream;
import java.io.PrintStream;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link ProxmoxLauncher}'s launch internals. The Java auto-install goes through an
 * injectable {@link ProxmoxLauncher.SshConnectionFactory} seam so it can be exercised with a fake
 * instead of a live SSH server; IP resolution and the SSH reachability wait are exercised against
 * WireMock / a black-hole address.
 */
@WithJenkins
class ProxmoxLauncherSshTest {

    @RegisterExtension
    static WireMockExtension wireMock = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .configureStaticDsl(true)
            .build();

    private JenkinsRule j;

    @BeforeEach
    void setUp(JenkinsRule rule) {
        j = rule;
    }

    private static PrintStream nullLog() {
        return new PrintStream(OutputStream.nullOutputStream());
    }

    private static ProxmoxLauncher launcher(JavaDistribution dist) {
        return new ProxmoxLauncher("ssh-cred", "java", "", 1, null, dist, 21);
    }

    /** A fake SSH session that records nothing beyond the canned auth result and exit status. */
    private static ProxmoxLauncher.SshConnectionFactory fakeFactory(boolean authOk, int exitStatus) {
        return (host, port) -> new ProxmoxLauncher.SshConnection() {
            @Override
            public boolean authenticateWithPublicKey(String username, char[] privateKey) {
                return authOk;
            }

            @Override
            public boolean authenticateWithPassword(String username, String password) {
                return authOk;
            }

            @Override
            public ProxmoxLauncher.SshExecResult exec(String command, int timeoutSeconds) {
                return new ProxmoxLauncher.SshExecResult("openjdk version \"21.0.1\"", "", exitStatus);
            }

            @Override
            public void close() {
            }
        };
    }

    private void registerPasswordCredential() throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new UsernamePasswordCredentialsImpl(CredentialsScope.GLOBAL, "ssh-cred", "d", "ubuntu", "pw"));
    }

    // --- installJava via the injected SSH seam ---

    @Test
    void installJavaSucceedsWithPasswordAuth() throws Exception {
        registerPasswordCredential();
        ProxmoxLauncher launcher = launcher(JavaDistribution.OPENJDK);
        launcher.setSshConnectionFactory(fakeFactory(true, 0));
        launcher.installJava("1.2.3.4", nullLog()); // no exception => install + verify succeeded
    }

    @Test
    void installJavaUsesPublicKeyAuth() throws Exception {
        SystemCredentialsProvider.getInstance().getCredentials().add(new BasicSSHUserPrivateKey(
                CredentialsScope.GLOBAL, "ssh-cred", "ubuntu",
                new BasicSSHUserPrivateKey.DirectEntryPrivateKeySource("a-private-key"), null, "d"));
        ProxmoxLauncher launcher = launcher(JavaDistribution.OPENJDK);
        boolean[] usedPublicKey = {false};
        launcher.setSshConnectionFactory((host, port) -> new ProxmoxLauncher.SshConnection() {
            @Override
            public boolean authenticateWithPublicKey(String username, char[] privateKey) {
                usedPublicKey[0] = true;
                return true;
            }

            @Override
            public boolean authenticateWithPassword(String username, String password) {
                return false;
            }

            @Override
            public ProxmoxLauncher.SshExecResult exec(String command, int timeoutSeconds) {
                return new ProxmoxLauncher.SshExecResult("ok", "", 0);
            }

            @Override
            public void close() {
            }
        });
        launcher.installJava("1.2.3.4", nullLog());
        assertTrue(usedPublicKey[0], "an SSH private-key credential must authenticate by public key");
    }

    @Test
    void installJavaThrowsOnAuthFailure() throws Exception {
        registerPasswordCredential();
        ProxmoxLauncher launcher = launcher(JavaDistribution.OPENJDK);
        launcher.setSshConnectionFactory(fakeFactory(false, 0));
        assertThrows(java.io.IOException.class, () -> launcher.installJava("1.2.3.4", nullLog()));
    }

    @Test
    void installJavaThrowsOnNonZeroExit() throws Exception {
        registerPasswordCredential();
        ProxmoxLauncher launcher = launcher(JavaDistribution.OPENJDK);
        launcher.setSshConnectionFactory(fakeFactory(true, 1));
        assertThrows(java.io.IOException.class, () -> launcher.installJava("1.2.3.4", nullLog()));
    }

    @Test
    void installJavaThrowsWhenCredentialMissing() {
        ProxmoxLauncher launcher = new ProxmoxLauncher("missing-cred", "java", "", 1, null, JavaDistribution.OPENJDK, 21);
        assertThrows(java.io.IOException.class, () -> launcher.installJava("1.2.3.4", nullLog()));
    }

    @Test
    void installJavaSkippedWhenDistributionNone() throws Exception {
        // NONE => getInstallCommand returns null, so installJava returns before any credential/SSH work.
        launcher(JavaDistribution.NONE).installJava("1.2.3.4", nullLog());
    }

    // --- resolveIp ---

    private ProxmoxAgent agent(String name, int vmId) throws Exception {
        return new ProxmoxAgent(name, "/home/jenkins", 1, Node.Mode.NORMAL, "linux",
                launcher(JavaDistribution.NONE), "test-cloud", "test-template", "pve1", vmId, 10, 0, null);
    }

    private void registerCloudAtWireMock() {
        SystemCredentialsProvider.getInstance().getCredentials().add(
                new ProxmoxTokenCredentialsImpl(CredentialsScope.GLOBAL, "proxmox-cred", "d",
                        "user@pve!token", Secret.fromString("secret")));
        ProxmoxCloud cloud = new ProxmoxCloud("test-cloud");
        cloud.setApiUrl("http://localhost:" + wireMock.getPort());
        cloud.setCredentialsId("proxmox-cred");
        j.jenkins.clouds.add(cloud);
    }

    @Test
    void resolveIpReturnsStaticIpWithoutApi() throws Exception {
        ProxmoxLauncher launcher = new ProxmoxLauncher("ssh-cred", "java", "", 1, "10.9.9.9", JavaDistribution.NONE, 21);
        assertEquals("10.9.9.9", launcher.resolveIp(agent("a-static", 360), nullLog()));
        verify(0, anyRequestedFor(anyUrl())); // static IP path makes no API call
    }

    @Test
    void resolveIpQueriesGuestAgentForIpv4() throws Exception {
        registerCloudAtWireMock();
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/361/agent/network-get-interfaces"))
                .willReturn(okJson("{\"data\":{\"result\":["
                        + "{\"name\":\"lo\",\"ip-addresses\":[{\"ip-address-type\":\"ipv4\",\"ip-address\":\"127.0.0.1\"}]},"
                        + "{\"name\":\"eth0\",\"ip-addresses\":[{\"ip-address-type\":\"ipv4\",\"ip-address\":\"10.0.0.50\"}]}"
                        + "]}}")));
        assertEquals("10.0.0.50", launcher(JavaDistribution.NONE).resolveIp(agent("a-guest", 361), nullLog()));
    }

    @Test
    void resolveIpThrowsWhenCloudMissing() throws Exception {
        // The agent's cloud "test-cloud" is not registered, so resolveIp fails before any API call.
        assertThrows(ProxmoxException.class, () -> launcher(JavaDistribution.NONE).resolveIp(agent("a-nocloud", 362), nullLog()));
    }

    @Test
    void resolveIpTimesOutWhenNoUsableIp() throws Exception {
        registerCloudAtWireMock();
        // Only loopback is reported, so no usable IPv4 is ever found and resolveIp times out.
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/363/agent/network-get-interfaces"))
                .willReturn(okJson("{\"data\":{\"result\":["
                        + "{\"name\":\"lo\",\"ip-addresses\":[{\"ip-address-type\":\"ipv4\",\"ip-address\":\"127.0.0.1\"}]}"
                        + "]}}")));
        assertThrows(ProxmoxException.class, () -> launcher(JavaDistribution.NONE).resolveIp(agent("a-noip", 363), nullLog()));
    }

    // --- waitForSsh ---

    @Test
    void waitForSshThrowsWhenUnreachable() {
        // An unresolvable host fails the socket connect immediately; with a 1s budget the wait gives up.
        assertThrows(ProxmoxException.class,
                () -> launcher(JavaDistribution.NONE).waitForSsh("nonexistent.invalid.example", nullLog()));
    }

    // --- waitForSshReady ---

    @Test
    void waitForSshReadySucceedsOnFirstAttempt() throws Exception {
        registerPasswordCredential();
        ProxmoxLauncher launcher = launcher(JavaDistribution.NONE);
        launcher.setSshConnectionFactory(fakeFactory(true, 0));
        launcher.waitForSshReady("1.2.3.4", nullLog());
    }

    @Test
    void waitForSshReadyRetriesOnConnectionReset() throws Exception {
        registerPasswordCredential();
        // Needs budget > 5 s so the sleep between retries doesn't exhaust the deadline.
        ProxmoxLauncher launcher = new ProxmoxLauncher("ssh-cred", "java", "", 10, null, JavaDistribution.NONE, 21);
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        launcher.setSshConnectionFactory((host, port) -> {
            if (calls.getAndIncrement() == 0) {
                throw new java.io.IOException("Connection reset");
            }
            return new ProxmoxLauncher.SshConnection() {
                @Override public boolean authenticateWithPublicKey(String u, char[] k) { return true; }
                @Override public boolean authenticateWithPassword(String u, String p) { return true; }
                @Override public ProxmoxLauncher.SshExecResult exec(String c, int t) { return new ProxmoxLauncher.SshExecResult("", "", 0); }
                @Override public void close() {}
            };
        });
        launcher.waitForSshReady("1.2.3.4", nullLog());
        assertEquals(2, calls.get(), "should have tried twice: first failed, second succeeded");
    }

    @Test
    void waitForSshReadyThrowsImmediatelyOnAuthRejected() throws Exception {
        registerPasswordCredential();
        ProxmoxLauncher launcher = launcher(JavaDistribution.NONE);
        launcher.setSshConnectionFactory(fakeFactory(false, 0));
        assertThrows(java.io.IOException.class,
                () -> launcher.waitForSshReady("1.2.3.4", nullLog()),
                "should fail immediately when auth is explicitly rejected, not retry until timeout");
    }

    @Test
    void waitForSshReadyThrowsWhenCredentialMissing() {
        ProxmoxLauncher launcher = new ProxmoxLauncher("missing-cred", "java", "", 1, null, JavaDistribution.NONE, 21);
        assertThrows(java.io.IOException.class, () -> launcher.waitForSshReady("1.2.3.4", nullLog()));
    }

    @Test
    void waitForSshReadyTimesOutWhenAlwaysConnectionReset() throws Exception {
        registerPasswordCredential();
        ProxmoxLauncher launcher = launcher(JavaDistribution.NONE);
        launcher.setSshConnectionFactory((host, port) -> { throw new java.io.IOException("Connection reset"); });
        assertThrows(ProxmoxException.class, () -> launcher.waitForSshReady("1.2.3.4", nullLog()));
    }
}
