package org.jenkinsci.plugins.proxmox;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.Session;
import hudson.model.TaskListener;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.security.ACL;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import jenkins.model.Jenkins;
import org.jenkinsci.plugins.cloudstats.CloudStatistics;
import org.jenkinsci.plugins.cloudstats.PhaseExecutionAttachment;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.proxmox.api.ProxmoxClient;
import org.jenkinsci.plugins.proxmox.api.ProxmoxException;
import org.jenkinsci.plugins.proxmox.api.model.NetworkInterface;
import org.jenkinsci.plugins.proxmox.config.JavaDistribution;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ProxmoxLauncher extends ComputerLauncher {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxLauncher.class.getName());
    private static final int SSH_PORT = 22;
    private static final int SOCKET_TIMEOUT_MS = 3000;

    private final String sshCredentialsId;
    private final String javaPath;
    private final String jvmOptions;
    private final int startupWaitSeconds;
    private final String staticIp;
    private final JavaDistribution javaDistribution;
    private final int javaMajorVersion;

    private transient SSHLauncher delegate;
    // Opens SSH sessions for the Java auto-install. Lazily defaulted to a trilead-backed factory;
    // package-private setter lets tests inject a fake so installJava is unit-testable without a real
    // SSH server. transient like delegate: it is behaviour, not persisted agent config.
    private transient SshConnectionFactory sshConnectionFactory;

    public ProxmoxLauncher(String sshCredentialsId, String javaPath, String jvmOptions,
                            int startupWaitSeconds, String staticIp,
                            JavaDistribution javaDistribution, int javaMajorVersion) {
        this.sshCredentialsId = sshCredentialsId;
        this.javaPath = javaPath != null && !javaPath.isBlank() ? javaPath : "java";
        this.jvmOptions = jvmOptions;
        this.startupWaitSeconds = startupWaitSeconds > 0 ? startupWaitSeconds : 60;
        this.staticIp = staticIp;
        this.javaDistribution = javaDistribution != null ? javaDistribution : JavaDistribution.NONE;
        this.javaMajorVersion = javaMajorVersion;
    }

    @Override
    public void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
        PrintStream log = listener.getLogger();
        if (!(computer instanceof ProxmoxComputer proxmoxComputer)) {
            throw new IOException("Expected ProxmoxComputer but got " + computer.getClass().getName());
        }
        ProxmoxAgent agent = proxmoxComputer.getNode();
        if (agent == null) {
            throw new IOException("Agent node is null");
        }

        try {
            String host = resolveIp(agent, log);
            log.println("[Proxmox] Resolved IP: " + host);

            waitForSsh(host, log);
            waitForSshReady(host, log);

            if (javaDistribution != JavaDistribution.NONE) {
                installJava(host, log);
            }

            delegate = new SSHLauncher(host, SSH_PORT, sshCredentialsId);
            configureDelegate(delegate);
            delegate.launch(computer, listener);
        } catch (IOException | InterruptedException | RuntimeException e) {
            recordLaunchFailure(agent, e);
            throw e;
        }
    }

    /**
     * Record a launch failure against the agent's cloud-stats activity (when tracked) so the cause is
     * visible under Manage Jenkins -&gt; Cloud Statistics. cloud-stats' own {@code onLaunchFailure} hook
     * does not attach any detail, and a failed agent is reaped soon after (taking its launch log with
     * it), so the activity is the only durable record of why the launch failed. The attachment's FAIL
     * status marks the activity completed. Best-effort: a null id (untracked) or missing activity is a
     * no-op. Package-private for unit testing.
     */
    void recordLaunchFailure(ProxmoxAgent agent, Throwable cause) {
        ProvisioningActivity.Id id = agent.getId();
        if (id == null) {
            return;
        }
        CloudStatistics stats = CloudStatistics.get();
        ProvisioningActivity activity = stats.getActivityFor(id);
        if (activity == null) {
            return;
        }
        stats.attach(activity, activity.getCurrentPhase(),
                new PhaseExecutionAttachment.ExceptionAttachment(
                        ProvisioningActivity.Status.FAIL, shortTitle(cause), cause));
    }

    /**
     * A concise, single-line title for the cloud-stats attempts table, which shows the attachment in
     * a narrow column where a long message wraps awkwardly. The full message and stack trace stay on
     * the attachment's detail page (carried by the throwable), so this only trims what the table
     * shows. Package-private for unit testing.
     */
    static String shortTitle(Throwable cause) {
        String msg = cause.getMessage();
        if (msg == null || msg.isBlank()) {
            return cause.getClass().getSimpleName();
        }
        msg = msg.strip().replaceAll("\\s+", " ");
        int max = 38;
        if (msg.length() <= max) {
            return msg;
        }
        int cut = msg.lastIndexOf(' ', max);
        return msg.substring(0, cut > 20 ? cut : max) + "…";
    }

    /**
     * Forward the agent-process tunables that the 3-arg {@link SSHLauncher} constructor does not take.
     * JVM options always apply (they configure the remoting JVM however java got onto the agent); the
     * java path applies only when no JDK was auto-installed (per the field's help text). A blank or
     * default {@code java} path is left unset so SSHLauncher keeps its own java auto-detection rather
     * than being pinned to {@code java} on the PATH. Package-private for unit testing.
     */
    void configureDelegate(SSHLauncher launcher) {
        if (jvmOptions != null && !jvmOptions.isBlank()) {
            launcher.setJvmOptions(jvmOptions);
        }
        if (javaDistribution == JavaDistribution.NONE
                && javaPath != null && !javaPath.isBlank() && !"java".equals(javaPath)) {
            launcher.setJavaPath(javaPath);
        }
    }

    @Override
    public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
        if (delegate != null) {
            delegate.afterDisconnect(computer, listener);
        }
    }

    @Override
    public void beforeDisconnect(SlaveComputer computer, TaskListener listener) {
        if (delegate != null) {
            delegate.beforeDisconnect(computer, listener);
        }
    }

    // Package-private for unit testing (via an injected SshConnectionFactory).
    void installJava(String host, PrintStream log) throws IOException, InterruptedException {
        String installCmd = javaDistribution.getInstallCommand(javaMajorVersion);
        if (installCmd == null) return;

        log.println("[Proxmox] Installing " + javaDistribution.getDisplayName()
                + " " + javaMajorVersion + "...");

        StandardUsernameCredentials creds = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StandardUsernameCredentials.class, Jenkins.get(), null, Collections.emptyList()),
                CredentialsMatchers.withId(sshCredentialsId));

        if (creds == null) {
            throw new IOException("SSH credentials not found: " + sshCredentialsId);
        }

        try (SshConnection conn = sshConnectionFactory().open(host, SSH_PORT)) {
            if (!authenticate(conn, creds)) {
                throw new IOException("SSH authentication failed for Java installation");
            }

            String command = "which java >/dev/null 2>&1 && java -version 2>&1 || "
                    + "sudo bash -c '" + installCmd.replace("'", "'\\''") + "'";
            execRemoteCommand(conn, command, log, "Java installation");

            String verifyCmd = "java -version 2>&1"
                    + " || { JAVA_BIN=$(find /usr/lib/jvm -name java -path '*/bin/java' -type f 2>/dev/null | head -1);"
                    + " [ -n \"$JAVA_BIN\" ] && sudo ln -sf \"$JAVA_BIN\" /usr/local/bin/java"
                    + " && java -version 2>&1; }";
            String output = execRemoteCommand(conn, verifyCmd, log, "Java verification");
            // Log the full `java -version` banner (3 lines). Amazon Corretto reports "openjdk
            // version ..." on the first line like any OpenJDK build; its "Corretto-..." identity is
            // on the Runtime Environment / VM lines, so printing only the first line is misleading.
            log.println("[Proxmox] Java is available:");
            output.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .forEach(line -> log.println("[Proxmox]   " + line));
        }
    }

    private String execRemoteCommand(SshConnection conn, String command, PrintStream log,
                                      String description) throws IOException, InterruptedException {
        SshExecResult result = conn.exec(command, startupWaitSeconds);
        Integer exitCode = result.exitStatus();
        if (exitCode == null || exitCode != 0) {
            log.println("[Proxmox] " + description + " output: " + result.stdout());
            log.println("[Proxmox] " + description + " errors: " + result.stderr());
            throw new IOException(description + " failed with exit code " + exitCode);
        }
        return result.stdout() + result.stderr();
    }

    private boolean authenticate(SshConnection conn, StandardUsernameCredentials creds) throws IOException {
        String username = creds.getUsername();

        if (creds instanceof com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey sshKey) {
            List<String> keys = sshKey.getPrivateKeys();
            if (!keys.isEmpty()) {
                return conn.authenticateWithPublicKey(username, keys.get(0).toCharArray());
            }
        }

        if (creds instanceof StandardUsernamePasswordCredentials passwordCreds) {
            return conn.authenticateWithPassword(username, passwordCreds.getPassword().getPlainText());
        }

        throw new IOException("Unsupported credential type: " + creds.getClass().getName());
    }

    private SshConnectionFactory sshConnectionFactory() {
        if (sshConnectionFactory == null) {
            sshConnectionFactory = TrileadSshConnection::open;
        }
        return sshConnectionFactory;
    }

    /** Inject a fake SSH connection factory for unit testing. */
    void setSshConnectionFactory(SshConnectionFactory factory) {
        this.sshConnectionFactory = factory;
    }

    /** Combined output of a remote command plus its exit status ({@code null} if the channel gave none). */
    record SshExecResult(String stdout, String stderr, Integer exitStatus) {}

    /**
     * A connected SSH session. Abstracts the trilead {@link Connection} so the Java auto-install flow
     * ({@link #installJava}) is unit-testable with a fake instead of a live SSH server.
     */
    interface SshConnection extends AutoCloseable {
        boolean authenticateWithPublicKey(String username, char[] privateKey) throws IOException;

        boolean authenticateWithPassword(String username, String password) throws IOException;

        SshExecResult exec(String command, int timeoutSeconds) throws IOException, InterruptedException;

        @Override
        void close();
    }

    /** Opens (connects) an {@link SshConnection}. Injected so tests can supply a fake. */
    interface SshConnectionFactory {
        SshConnection open(String host, int port) throws IOException;
    }

    /** trilead-backed {@link SshConnection}: the only place the real SSH library is touched. */
    private static final class TrileadSshConnection implements SshConnection {
        private final Connection conn;

        private TrileadSshConnection(Connection conn) {
            this.conn = conn;
        }

        static SshConnection open(String host, int port) throws IOException {
            Connection conn = new Connection(host, port);
            conn.connect(null, 30000, 30000);
            return new TrileadSshConnection(conn);
        }

        @Override
        public boolean authenticateWithPublicKey(String username, char[] privateKey) throws IOException {
            return conn.authenticateWithPublicKey(username, privateKey, null);
        }

        @Override
        public boolean authenticateWithPassword(String username, String password) throws IOException {
            return conn.authenticateWithPassword(username, password);
        }

        @Override
        public SshExecResult exec(String command, int timeoutSeconds) throws IOException, InterruptedException {
            Session session = conn.openSession();
            try {
                session.execCommand(command);
                String stdout = readStream(session.getStdout());
                String stderr = readStream(session.getStderr());
                session.waitForCondition(com.trilead.ssh2.ChannelCondition.EXIT_STATUS,
                        (long) timeoutSeconds * 1000);
                return new SshExecResult(stdout, stderr, session.getExitStatus());
            } finally {
                session.close();
            }
        }

        @Override
        public void close() {
            conn.close();
        }

        private static String readStream(InputStream in) throws IOException {
            if (in == null) return "";
            byte[] buf = new byte[8192];
            StringBuilder sb = new StringBuilder();
            int n;
            while ((n = in.read(buf)) > 0) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
            }
            return sb.toString();
        }
    }

    // Package-private for unit testing.
    String resolveIp(ProxmoxAgent agent, PrintStream log) {
        if (staticIp != null && !staticIp.isBlank()) {
            log.println("[Proxmox] Using static IP from cloud-init config");
            return staticIp;
        }

        log.println("[Proxmox] Querying guest agent for IP address...");
        ProxmoxCloud cloud = agent.getCloud();
        if (cloud == null) {
            throw new ProxmoxException("Cloud " + agent.getCloudName() + " not found");
        }

        ProxmoxClient client = cloud.getClient();
        long deadline = System.currentTimeMillis() + (long) startupWaitSeconds * 1000;

        while (System.currentTimeMillis() < deadline) {
            try {
                List<NetworkInterface> interfaces = client.getVmNetworkInterfaces(
                        agent.getProxmoxNode(), agent.getVmId());
                for (NetworkInterface iface : interfaces) {
                    if ("lo".equals(iface.name()) || iface.ipAddresses() == null) continue;
                    for (NetworkInterface.IpAddress addr : iface.ipAddresses()) {
                        if ("ipv4".equals(addr.ipAddressType()) && !addr.ipAddress().startsWith("127.")) {
                            return addr.ipAddress();
                        }
                    }
                }
            } catch (ProxmoxException e) {
                LOGGER.log(Level.FINE, "Guest agent not ready yet", e);
            }

            log.println("[Proxmox] Waiting for guest agent...");
            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProxmoxException("Interrupted waiting for IP", e);
            }
        }

        throw new ProxmoxException("No IP for VM " + agent.getVmId()
                + " within " + startupWaitSeconds + "s");
    }

    /**
     * Waits until a full SSH auth handshake succeeds on {@code host}. Called after
     * {@link #waitForSsh} (which only checks TCP reachability) because some SSH servers — notably
     * Windows OpenSSH on first boot — accept TCP connections before their auth subsystem is ready,
     * causing the connection to reset mid-handshake. Retries on any connection-level failure;
     * throws immediately if the server explicitly rejects the credentials, since retrying won't help.
     */
    // Package-private for unit testing.
    void waitForSshReady(String host, PrintStream log) throws IOException, InterruptedException {
        StandardUsernameCredentials creds = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StandardUsernameCredentials.class, Jenkins.get(), null,
                        Collections.emptyList()),
                CredentialsMatchers.withId(sshCredentialsId));
        if (creds == null) {
            throw new IOException("SSH credentials not found: " + sshCredentialsId);
        }

        log.println("[Proxmox] Verifying SSH auth on " + host + "...");
        long deadline = System.currentTimeMillis() + (long) startupWaitSeconds * 1000;

        IOException lastError = null;
        while (System.currentTimeMillis() < deadline) {
            try (SshConnection conn = sshConnectionFactory().open(host, SSH_PORT)) {
                boolean ok = authenticate(conn, creds);
                if (!ok) {
                    throw new IOException("SSH authentication rejected for user " + creds.getUsername());
                }
                log.println("[Proxmox] SSH auth verified");
                return;
            } catch (IOException e) {
                String msg = e.getMessage();
                if (msg != null && msg.startsWith("SSH authentication rejected")
                        || msg != null && msg.startsWith("Unsupported credential type")) {
                    throw e;
                }
                lastError = e;
                LOGGER.log(Level.FINE, "SSH auth not ready on " + host, e);
                log.println("[Proxmox] SSH not ready yet, retrying...");
            }
            Thread.sleep(5000);
        }

        throw new ProxmoxException("SSH auth not ready on " + host
                + " within " + startupWaitSeconds + "s"
                + (lastError != null ? ": " + lastError.getMessage() : ""));
    }

    // Package-private for unit testing.
    void waitForSsh(String host, PrintStream log) {
        log.println("[Proxmox] Waiting for SSH on " + host + ":" + SSH_PORT + "...");
        long deadline = System.currentTimeMillis() + (long) startupWaitSeconds * 1000;

        while (System.currentTimeMillis() < deadline) {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(host, SSH_PORT), SOCKET_TIMEOUT_MS);
                log.println("[Proxmox] SSH is reachable");
                return;
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "SSH not ready on " + host, e);
            }

            try {
                Thread.sleep(5000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProxmoxException("Interrupted waiting for SSH", e);
            }
        }

        throw new ProxmoxException("No SSH on " + host
                + " within " + startupWaitSeconds + "s");
    }
}
