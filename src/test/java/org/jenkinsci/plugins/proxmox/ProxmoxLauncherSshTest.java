package org.jenkinsci.plugins.proxmox;

import com.cloudbees.jenkins.plugins.sshcredentials.impl.BasicSSHUserPrivateKey;
import com.cloudbees.plugins.credentials.CredentialsScope;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import hudson.model.Node;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.util.Secret;
import org.jenkinsci.plugins.proxmox.api.ProxmoxException;
import org.jenkinsci.plugins.proxmox.config.JavaDistribution;
import org.jenkinsci.plugins.proxmox.config.ProxmoxTokenCredentialsImpl;
import org.jenkinsci.plugins.proxmox.config.WindowsLoginShell;
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
        return new ProxmoxLauncher("ssh-cred", "java", "", 1, null, dist, 21, null);
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
        ProxmoxLauncher launcher = new ProxmoxLauncher("missing-cred", "java", "", 1, null, JavaDistribution.OPENJDK, 21, null);
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
        ProxmoxLauncher launcher = new ProxmoxLauncher("ssh-cred", "java", "", 1, "10.9.9.9", JavaDistribution.NONE, 21, null);
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
    void resolveIpSkipsLinkLocalAndPicksRoutable() throws Exception {
        registerCloudAtWireMock();
        // The interface reports an APIPA 169.254 address before the routable DHCP lease; the
        // routable address must win even though the link-local one is listed first.
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/364/agent/network-get-interfaces"))
                .willReturn(okJson("{\"data\":{\"result\":["
                        + "{\"name\":\"eth0\",\"ip-addresses\":["
                        + "{\"ip-address-type\":\"ipv4\",\"ip-address\":\"169.254.182.26\"},"
                        + "{\"ip-address-type\":\"ipv4\",\"ip-address\":\"10.13.1.106\"}"
                        + "]}]}}")));
        assertEquals("10.13.1.106", launcher(JavaDistribution.NONE).resolveIp(agent("a-apipa", 364), nullLog()));
    }

    @Test
    void resolveIpSkipsLinkLocalOnlyAndTimesOut() throws Exception {
        registerCloudAtWireMock();
        // Only an APIPA address is reported (DHCP not yet complete): resolveIp must NOT return it,
        // so with only a link-local IP available it keeps polling and eventually times out.
        stubFor(get(urlEqualTo("/api2/json/nodes/pve1/qemu/365/agent/network-get-interfaces"))
                .willReturn(okJson("{\"data\":{\"result\":["
                        + "{\"name\":\"eth0\",\"ip-addresses\":["
                        + "{\"ip-address-type\":\"ipv4\",\"ip-address\":\"169.254.10.5\"}"
                        + "]}]}}")));
        assertThrows(ProxmoxException.class,
                () -> launcher(JavaDistribution.NONE).resolveIp(agent("a-apipa-only", 365), nullLog()));
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
        // Needs budget > 2 s so the sleep between retries doesn't exhaust the deadline.
        ProxmoxLauncher launcher = new ProxmoxLauncher("ssh-cred", "java", "", 10, null, JavaDistribution.NONE, 21, null);
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
        ProxmoxLauncher launcher = new ProxmoxLauncher("missing-cred", "java", "", 1, null, JavaDistribution.NONE, 21, null);
        assertThrows(java.io.IOException.class, () -> launcher.waitForSshReady("1.2.3.4", nullLog()));
    }

    @Test
    void waitForSshReadyTimesOutWhenAlwaysConnectionReset() throws Exception {
        registerPasswordCredential();
        ProxmoxLauncher launcher = launcher(JavaDistribution.NONE);
        launcher.setSshConnectionFactory((host, port) -> { throw new java.io.IOException("Connection reset"); });
        assertThrows(ProxmoxException.class, () -> launcher.waitForSshReady("1.2.3.4", nullLog()));
    }

    @Test
    void waitForSshReadyBoundsAndForceClosesAHangingAuth() throws Exception {
        // A server that accepts the connection then stalls during auth (the Windows-first-boot case)
        // must NOT hang the launch: the attempt is force-closed past its timeout, retried, and the
        // wait gives up within the startup budget. startupWaitSeconds=1 => 1s per-attempt bound.
        registerPasswordCredential();
        ProxmoxLauncher launcher = launcher(JavaDistribution.NONE);
        java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        launcher.setSshConnectionFactory((host, port) -> new ProxmoxLauncher.SshConnection() {
            @Override public boolean authenticateWithPublicKey(String u, char[] k) throws java.io.IOException { return block(); }
            @Override public boolean authenticateWithPassword(String u, String p) throws java.io.IOException { return block(); }
            private boolean block() throws java.io.IOException {
                try {
                    Thread.sleep(10_000); // longer than the per-attempt bound; simulates a silent server
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new java.io.IOException("interrupted");
                }
                return true;
            }
            @Override public ProxmoxLauncher.SshExecResult exec(String c, int t) { return new ProxmoxLauncher.SshExecResult("", "", 0); }
            @Override public void close() { closes.incrementAndGet(); }
        });
        long start = System.currentTimeMillis();
        assertThrows(ProxmoxException.class, () -> launcher.waitForSshReady("1.2.3.4", nullLog()));
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(elapsed < 8_000, "must give up within the startup budget, not block on the hung auth (was " + elapsed + "ms)");
        assertTrue(closes.get() >= 1, "a timed-out auth attempt must force-close the connection to unblock the worker");
    }

    @Test
    void installJavaAllowsCommandsLongerThanConnectTimeout() throws Exception {
        // Regression guard: the Java install command must get a generous timeout, NOT the 30s
        // connect/auth cap (attemptTimeoutMs). With startupWaitSeconds=1 the connect/auth cap is 1s;
        // an install command that runs ~1.5s (> that cap) must still succeed, because apt-get
        // legitimately takes far longer than the connect timeout. (The bounded-exec force-close on a
        // genuinely stuck command is covered by detectBoundsAndForceClosesAHangingProbe.)
        registerPasswordCredential();
        ProxmoxLauncher launcher = launcher(JavaDistribution.OPENJDK); // startupWaitSeconds=1
        launcher.setSshConnectionFactory((host, port) -> new ProxmoxLauncher.SshConnection() {
            @Override public boolean authenticateWithPublicKey(String u, char[] k) { return true; }
            @Override public boolean authenticateWithPassword(String u, String p) { return true; }
            @Override public ProxmoxLauncher.SshExecResult exec(String c, int t) throws InterruptedException {
                Thread.sleep(1_500); // longer than the 1s connect/auth cap, well under the install budget
                return new ProxmoxLauncher.SshExecResult("openjdk version \"21\"", "", 0);
            }
            @Override public void close() {}
        });
        launcher.installJava("1.2.3.4", nullLog()); // must not throw: install uses the generous timeout
    }

    // --- shell auto-detection (detectWindowsShell / resolveLoginShell) ---

    private static ProxmoxLauncher launcherAuto() {
        return new ProxmoxLauncher("ssh-cred", "java", "", 1, null, JavaDistribution.NONE, 21,
                WindowsLoginShell.AUTO);
    }

    /** A fake whose probe exec returns canned stdout/stderr/exit; auth always succeeds. */
    private static ProxmoxLauncher.SshConnectionFactory execFactory(String stdout, String stderr, int exit) {
        return (host, port) -> new ProxmoxLauncher.SshConnection() {
            @Override public boolean authenticateWithPublicKey(String u, char[] k) { return true; }
            @Override public boolean authenticateWithPassword(String u, String p) { return true; }
            @Override public ProxmoxLauncher.SshExecResult exec(String c, int t) {
                return new ProxmoxLauncher.SshExecResult(stdout, stderr, exit);
            }
            @Override public void close() {}
        };
    }

    @Test
    void detectReturnsCmdWhenProbeTokenOnStdout() throws Exception {
        // cmd.exe / PowerShell 7 run `echo probe && echo <token>`, so the token lands on stdout.
        registerPasswordCredential();
        ProxmoxLauncher launcher = launcherAuto();
        launcher.setSshConnectionFactory(execFactory("probe\n" + ProxmoxLauncher.SHELL_PROBE_TOKEN + "\n", "", 0));
        assertEquals(WindowsLoginShell.CMD, launcher.detectWindowsShell("1.2.3.4", nullLog()));
    }

    @Test
    void detectReturnsPowershellWhenTokenAbsent() throws Exception {
        registerPasswordCredential();
        ProxmoxLauncher launcher = launcherAuto();
        launcher.setSshConnectionFactory(execFactory("", "some error", 1));
        assertEquals(WindowsLoginShell.POWERSHELL, launcher.detectWindowsShell("1.2.3.4", nullLog()));
    }

    @Test
    void detectReturnsPowershellWhenTokenOnlyInStderr() throws Exception {
        // The load-bearing case: PS 5.x fails to parse the line and echoes the source (with the
        // token) to STDERR. Reading stdout only must keep this a POWERSHELL (wrap) decision.
        registerPasswordCredential();
        ProxmoxLauncher launcher = launcherAuto();
        launcher.setSshConnectionFactory(execFactory("",
                "The token '&&' is not a valid statement separator... + echo " + ProxmoxLauncher.SHELL_PROBE_TOKEN, 1));
        assertEquals(WindowsLoginShell.POWERSHELL, launcher.detectWindowsShell("1.2.3.4", nullLog()));
    }

    @Test
    void detectFallsBackToCmdOnIoException() throws Exception {
        registerPasswordCredential();
        ProxmoxLauncher launcher = launcherAuto();
        launcher.setSshConnectionFactory((host, port) -> { throw new java.io.IOException("connect refused"); });
        assertEquals(WindowsLoginShell.CMD, launcher.detectWindowsShell("1.2.3.4", nullLog()));
    }

    @Test
    void detectFallsBackToCmdWhenCredentialMissing() throws Exception {
        ProxmoxLauncher launcher = new ProxmoxLauncher("missing-cred", "java", "", 1, null,
                JavaDistribution.NONE, 21, WindowsLoginShell.AUTO);
        assertEquals(WindowsLoginShell.CMD, launcher.detectWindowsShell("1.2.3.4", nullLog()));
    }

    @Test
    void detectBoundsAndForceClosesAHangingProbe() throws Exception {
        // A stuck probe must not hang the launch: it is force-closed past the bound and falls back to CMD.
        registerPasswordCredential();
        ProxmoxLauncher launcher = launcherAuto();
        java.util.concurrent.atomic.AtomicInteger closes = new java.util.concurrent.atomic.AtomicInteger();
        launcher.setSshConnectionFactory((host, port) -> new ProxmoxLauncher.SshConnection() {
            @Override public boolean authenticateWithPublicKey(String u, char[] k) { return true; }
            @Override public boolean authenticateWithPassword(String u, String p) { return true; }
            @Override public ProxmoxLauncher.SshExecResult exec(String c, int t) throws InterruptedException {
                Thread.sleep(10_000);
                return new ProxmoxLauncher.SshExecResult("", "", 0);
            }
            @Override public void close() { closes.incrementAndGet(); }
        });
        long start = System.currentTimeMillis();
        assertEquals(WindowsLoginShell.CMD, launcher.detectWindowsShell("1.2.3.4", nullLog()));
        assertTrue(System.currentTimeMillis() - start < 8_000, "detection must not hang on a stuck probe");
        assertTrue(closes.get() >= 1, "a timed-out probe must force-close the connection");
    }

    @Test
    void resolveLoginShellNullDoesNotWrap() throws Exception {
        // Linux agent (null shell): no probe, no wrapper.
        ProxmoxLauncher launcher = new ProxmoxLauncher("ssh-cred", "java", "", 1, null,
                JavaDistribution.NONE, 21, null);
        launcher.resolveLoginShell("1.2.3.4", nullLog());
        SSHLauncher d = new SSHLauncher("1.2.3.4", 22, "ssh-cred");
        launcher.configureDelegate(d);
        assertEquals("", d.getPrefixStartSlaveCmd());
        assertEquals("", d.getSuffixStartSlaveCmd());
    }

    @Test
    void resolveLoginShellExplicitSkipsProbe() throws Exception {
        // An explicit shell must NOT probe (the factory would fail the test if opened) and must wrap.
        ProxmoxLauncher launcher = new ProxmoxLauncher("ssh-cred", "java", "", 1, null,
                JavaDistribution.NONE, 21, WindowsLoginShell.POWERSHELL);
        launcher.setSshConnectionFactory((host, port) -> { throw new AssertionError("explicit shell must not probe"); });
        launcher.resolveLoginShell("1.2.3.4", nullLog());
        SSHLauncher d = new SSHLauncher("1.2.3.4", 22, "ssh-cred");
        launcher.configureDelegate(d);
        assertEquals("cmd /c '", d.getPrefixStartSlaveCmd());
        assertEquals("'", d.getSuffixStartSlaveCmd());
    }

    @Test
    void resolveLoginShellAutoThenWrapsWhenPowershellDetected() throws Exception {
        // Full AUTO path: probe detects PS 5.x (token only in stderr) -> configureDelegate wraps.
        registerPasswordCredential();
        ProxmoxLauncher launcher = launcherAuto();
        launcher.setSshConnectionFactory(execFactory("", "parse error + echo " + ProxmoxLauncher.SHELL_PROBE_TOKEN, 1));
        launcher.resolveLoginShell("1.2.3.4", nullLog());
        SSHLauncher d = new SSHLauncher("1.2.3.4", 22, "ssh-cred");
        launcher.configureDelegate(d);
        assertEquals("cmd /c '", d.getPrefixStartSlaveCmd());
        assertEquals("'", d.getSuffixStartSlaveCmd());
    }
}
