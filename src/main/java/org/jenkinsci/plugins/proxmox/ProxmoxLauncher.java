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
import org.jenkinsci.plugins.proxmox.config.WindowsLoginShell;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;
import java.util.logging.Logger;

public class ProxmoxLauncher extends ComputerLauncher {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxLauncher.class.getName());
    private static final int SSH_PORT = 22;
    private static final int SOCKET_TIMEOUT_MS = 3000;

    /**
     * Hard per-attempt cap on a connect+authenticate and on the shell-detection probe. trilead
     * performs its post-key-exchange reads (the auth handshake, command output) with no socket
     * timeout, so a stalled sshd blocks the calling thread forever. The common trigger is a Windows
     * agent whose OpenSSH accepts the TCP connection and completes key exchange while still booting,
     * then goes silent before answering user-auth. Each attempt runs on a worker thread and its
     * connection is force-closed past this bound, so {@link #waitForSshReady}'s retry loop can make
     * progress and honour its own {@code startupWaitSeconds} deadline instead of hanging.
     * NOTE: this is deliberately NOT used for the Java-install command, which legitimately runs for
     * minutes ({@code apt-get} download + install); see {@link #JAVA_INSTALL_TIMEOUT_MS}.
     */
    private static final long SSH_ATTEMPT_TIMEOUT_MS = 30_000;

    /**
     * Bound on each Java auto-install command. An {@code apt-get} JDK install (index refresh +
     * download + dpkg) routinely exceeds the 30s {@link #SSH_ATTEMPT_TIMEOUT_MS} connect/auth cap, so
     * it gets its own generous timeout; the bound still exists so a genuinely stuck command (e.g.
     * apt blocked on a dpkg lock) is force-closed rather than hanging the launch forever. A template
     * with a larger {@code startupWaitSeconds} raises it further (see {@link #installJava}).
     */
    private static final long JAVA_INSTALL_TIMEOUT_MS = 5 * 60_000;

    /**
     * Nonce echoed by the {@link WindowsLoginShell#AUTO} shell probe. The probe runs
     * {@code echo probe && echo <token>}; if the agent's shell accepts {@code &&} the token appears
     * on stdout. Kept to {@code [A-Za-z0-9_]} so no shell treats a character as special, and a
     * package-private constant so tests can build deterministic probe output. See
     * {@link #detectWindowsShell}.
     */
    static final String SHELL_PROBE_TOKEN = "PROXMOX_SHELL_PROBE_OK_9F3A2C";

    private final String sshCredentialsId;
    private final String javaPath;
    private final String jvmOptions;
    private final int startupWaitSeconds;
    private final String staticIp;
    private final JavaDistribution javaDistribution;
    private final int javaMajorVersion;
    // The agent's login shell, used to wrap SSHLauncher's "&&" start command for Windows PowerShell
    // (via setPrefix/SuffixStartSlaveCmd). null for Linux (never wrapped). AUTO (the usual value) is
    // resolved to CMD or POWERSHELL at launch by probing the agent; see resolveLoginShell.
    private final WindowsLoginShell windowsLoginShell;
    // The shell AUTO resolved to at launch; transient behaviour, not persisted config.
    private transient WindowsLoginShell resolvedLoginShell;

    private transient SSHLauncher delegate;
    // Opens SSH sessions for the Java auto-install. Lazily defaulted to a trilead-backed factory;
    // package-private setter lets tests inject a fake so installJava is unit-testable without a real
    // SSH server. transient like delegate: it is behaviour, not persisted agent config.
    private transient SshConnectionFactory sshConnectionFactory;

    public ProxmoxLauncher(String sshCredentialsId, String javaPath, String jvmOptions,
                            int startupWaitSeconds, String staticIp,
                            JavaDistribution javaDistribution, int javaMajorVersion,
                            WindowsLoginShell windowsLoginShell) {
        this.sshCredentialsId = sshCredentialsId;
        this.javaPath = javaPath != null && !javaPath.isBlank() ? javaPath : "java";
        this.jvmOptions = jvmOptions;
        this.startupWaitSeconds = startupWaitSeconds > 0 ? startupWaitSeconds : 60;
        this.staticIp = staticIp;
        this.javaDistribution = javaDistribution != null ? javaDistribution : JavaDistribution.NONE;
        this.javaMajorVersion = javaMajorVersion;
        this.windowsLoginShell = windowsLoginShell;
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

            resolveLoginShell(host, log);

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
        // Windows PowerShell 5.x cannot parse the "&&" SSHLauncher builds into its start command;
        // wrapping it as cmd /c '<command>' (prefix + suffix) routes it through cmd for that shell.
        // Prefer the shell resolved at launch (AUTO -> CMD/POWERSHELL); fall back to the constructor
        // value so direct-call unit tests (no launch()) still see an explicit shell's wrapper.
        WindowsLoginShell effective = resolvedLoginShell != null ? resolvedLoginShell : windowsLoginShell;
        if (effective != null) {
            if (!effective.getStartCommandPrefix().isBlank()) {
                launcher.setPrefixStartSlaveCmd(effective.getStartCommandPrefix());
            }
            if (!effective.getStartCommandSuffix().isBlank()) {
                launcher.setSuffixStartSlaveCmd(effective.getStartCommandSuffix());
            }
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

        // Connect/auth is fast (bounded like every other attempt); the install COMMAND gets a
        // generous timeout because apt-get legitimately runs for minutes. A larger startupWaitSeconds
        // raises the install budget further for slow package mirrors.
        long connectTimeoutMs = attemptTimeoutMs();
        long installTimeoutMs = Math.max((long) startupWaitSeconds * 1000, JAVA_INSTALL_TIMEOUT_MS);
        ExecutorService exec = newSshExecutor(host);
        try (SshConnection conn = connectAndAuth(host, creds, exec, connectTimeoutMs)) {
            String command = "which java >/dev/null 2>&1 && java -version 2>&1 || "
                    + "sudo bash -c '" + installCmd.replace("'", "'\\''") + "'";
            execRemoteCommand(conn, command, log, "Java installation", exec, installTimeoutMs);

            String verifyCmd = "java -version 2>&1"
                    + " || { JAVA_BIN=$(find /usr/lib/jvm -name java -path '*/bin/java' -type f 2>/dev/null | head -1);"
                    + " [ -n \"$JAVA_BIN\" ] && sudo ln -sf \"$JAVA_BIN\" /usr/local/bin/java"
                    + " && java -version 2>&1; }";
            String output = execRemoteCommand(conn, verifyCmd, log, "Java verification", exec, installTimeoutMs);
            // Log the full `java -version` banner (3 lines). Amazon Corretto reports "openjdk
            // version ..." on the first line like any OpenJDK build; its "Corretto-..." identity is
            // on the Runtime Environment / VM lines, so printing only the first line is misleading.
            log.println("[Proxmox] Java is available:");
            output.lines()
                    .map(String::strip)
                    .filter(line -> !line.isEmpty())
                    .forEach(line -> log.println("[Proxmox]   " + line));
        } finally {
            exec.shutdownNow();
        }
    }

    /**
     * Decide the effective login shell for this agent and store it in {@link #resolvedLoginShell}
     * for {@link #configureDelegate}. Linux (a null shell) stays null (never wrapped); an explicit
     * shell is used as chosen; {@link WindowsLoginShell#AUTO} probes the running agent via
     * {@link #detectWindowsShell}. Package-private for unit testing.
     */
    void resolveLoginShell(String host, PrintStream log) throws InterruptedException {
        if (windowsLoginShell == null || windowsLoginShell != WindowsLoginShell.AUTO) {
            resolvedLoginShell = windowsLoginShell;
            return;
        }
        resolvedLoginShell = detectWindowsShell(host, log);
    }

    /**
     * Probe the agent's login shell and return the {@link WindowsLoginShell} whose wrapper makes
     * SSHLauncher's {@code cd "..." && java ...} start command run there. Runs
     * {@code echo probe && echo <token>} and checks STDOUT ONLY for the token: a shell that accepts
     * {@code &&} (cmd.exe or PowerShell 7) echoes it, so no wrapper is needed ({@code CMD}); Windows
     * PowerShell 5.x fails to parse the line, so the token never reaches stdout (the parse error,
     * which echoes the source line and thus the token, goes to stderr), so the command is wrapped via
     * cmd ({@code POWERSHELL}). Reading stdout only is what keeps the stderr source-echo from
     * false-positiving. Never fails the launch: on missing credentials or any {@link IOException} it
     * logs a warning and assumes {@code CMD} (no wrapper), leaving the manual Login Shell override as
     * the escape hatch. Package-private for unit testing.
     */
    WindowsLoginShell detectWindowsShell(String host, PrintStream log) throws InterruptedException {
        log.println("[Proxmox] Auto-detecting agent login shell...");
        StandardUsernameCredentials creds = CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentialsInItemGroup(
                        StandardUsernameCredentials.class, Jenkins.get(), null, Collections.emptyList()),
                CredentialsMatchers.withId(sshCredentialsId));
        if (creds == null) {
            log.println("[Proxmox] SSH credentials not found for shell detection; assuming Command Prompt");
            return WindowsLoginShell.CMD;
        }

        long timeoutMs = attemptTimeoutMs();
        ExecutorService exec = newSshExecutor(host);
        try (SshConnection conn = connectAndAuth(host, creds, exec, timeoutMs)) {
            SshExecResult result = runBounded(conn, "echo probe && echo " + SHELL_PROBE_TOKEN,
                    exec, timeoutMs, "Shell detection");
            boolean acceptsAndAnd = result.stdout() != null && result.stdout().contains(SHELL_PROBE_TOKEN);
            if (acceptsAndAnd) {
                log.println("[Proxmox] Login shell accepts '&&' (Command Prompt or PowerShell 7);"
                        + " no command wrapping");
                return WindowsLoginShell.CMD;
            }
            log.println("[Proxmox] Login shell rejects '&&' (Windows PowerShell 5.x);"
                    + " wrapping start command via cmd");
            return WindowsLoginShell.POWERSHELL;
        } catch (IOException e) {
            log.println("[Proxmox] Shell detection failed (" + e.getMessage() + "); assuming Command"
                    + " Prompt. Set Login Shell manually if the agent fails to start.");
            LOGGER.log(Level.FINE, "Shell detection failed on " + host, e);
            return WindowsLoginShell.CMD;
        } finally {
            exec.shutdownNow();
        }
    }

    /**
     * Run one remote command, bounded by {@code timeoutMs}. The command executes on a worker thread
     * ({@code exec}) so a stalled channel read cannot hang the launch; on timeout the connection is
     * force-closed (which unblocks the trilead worker) and the command is treated as failed.
     */
    private String execRemoteCommand(SshConnection conn, String command, PrintStream log,
                                      String description, ExecutorService exec, long timeoutMs)
            throws IOException, InterruptedException {
        SshExecResult result = runBounded(conn, command, exec, timeoutMs, description);
        Integer exitCode = result.exitStatus();
        if (exitCode == null || exitCode != 0) {
            log.println("[Proxmox] " + description + " output: " + result.stdout());
            log.println("[Proxmox] " + description + " errors: " + result.stderr());
            throw new IOException(description + " failed with exit code " + exitCode);
        }
        return result.stdout() + result.stderr();
    }

    /**
     * Run a remote command bounded by {@code timeoutMs} and return its raw result (does NOT throw on
     * a non-zero exit status, unlike {@link #execRemoteCommand}, so callers that need to inspect the
     * output of a command that may fail can do so). The command runs on a worker thread; on timeout
     * the connection is force-closed to unblock the trilead worker.
     */
    private SshExecResult runBounded(SshConnection conn, String command, ExecutorService exec,
                                     long timeoutMs, String description)
            throws IOException, InterruptedException {
        Future<SshExecResult> future = exec.submit(() -> conn.exec(command, startupWaitSeconds));
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            closeQuietly(conn);
            throw new IOException(description + " timed out after " + timeoutMs + "ms");
        } catch (ExecutionException e) {
            throw unwrap(e, description + " failed");
        }
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

    /**
     * Open an SSH connection and authenticate, bounded by {@code timeoutMs}. The attempt runs on a
     * {@code exec} worker thread; if it exceeds the bound the future is cancelled and the (possibly
     * half-open) connection is force-closed, which is the only way to unblock a trilead thread
     * parked in a post-key-exchange read (trilead has no socket read timeout). On success the
     * returned connection is authenticated and owned by the caller, which must close it. A rejected
     * credential surfaces as an {@code IOException} whose message starts with "SSH authentication
     * rejected" so callers can distinguish it from a retryable connection-level failure.
     */
    private SshConnection connectAndAuth(String host, StandardUsernameCredentials creds,
                                         ExecutorService exec, long timeoutMs)
            throws IOException, InterruptedException {
        AtomicReference<SshConnection> opened = new AtomicReference<>();
        Future<SshConnection> future = exec.submit(() -> {
            SshConnection conn = sshConnectionFactory().open(host, SSH_PORT);
            opened.set(conn);
            if (!authenticate(conn, creds)) {
                conn.close();
                throw new IOException("SSH authentication rejected for user " + creds.getUsername());
            }
            return conn;
        });
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            closeQuietly(opened.get());
            throw new IOException("SSH connect/auth timed out after " + timeoutMs + "ms");
        } catch (ExecutionException e) {
            closeQuietly(opened.get());
            throw unwrap(e, "SSH connect/auth failed");
        }
    }

    /** Per-attempt timeout: the smaller of the startup budget and {@link #SSH_ATTEMPT_TIMEOUT_MS}. */
    private long attemptTimeoutMs() {
        return Math.min((long) startupWaitSeconds * 1000, SSH_ATTEMPT_TIMEOUT_MS);
    }

    /** Daemon-threaded pool for bounded SSH attempts; a leaked (force-closed) attempt never blocks JVM exit. */
    private static ExecutorService newSshExecutor(String host) {
        return Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "proxmox-ssh-" + host);
            t.setDaemon(true);
            return t;
        });
    }

    /** Unwrap a task failure: rethrow its cause when it is already an IOException/RuntimeException, else wrap it. */
    private static IOException unwrap(ExecutionException e, String context) {
        Throwable cause = e.getCause();
        if (cause instanceof IOException io) {
            return io;
        }
        if (cause instanceof RuntimeException re) {
            throw re;
        }
        return new IOException(context, cause != null ? cause : e);
    }

    private static void closeQuietly(SshConnection conn) {
        if (conn != null) {
            try {
                conn.close();
            } catch (RuntimeException ignored) {
                // best-effort: we are unblocking a stalled attempt, a close failure adds nothing
            }
        }
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
                        // Skip loopback and link-local (169.254.x.x): Windows assigns an APIPA
                        // address early in boot before DHCP completes, and the guest agent reports
                        // it. Grabbing it would wedge the launch on an unroutable IP; instead keep
                        // polling until a real lease appears.
                        if ("ipv4".equals(addr.ipAddressType())
                                && !addr.ipAddress().startsWith("127.")
                                && !addr.ipAddress().startsWith("169.254.")) {
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
     * {@link #waitForSsh} (which only checks TCP reachability) because some SSH servers (notably
     * Windows OpenSSH on first boot) accept TCP connections before their auth subsystem is ready.
     * Such a server may reset the connection mid-handshake (a fast, retryable failure) or, worse,
     * complete key exchange and then go silent before answering user-auth. trilead has no socket
     * read timeout past key exchange, so the second case would block forever; each attempt is
     * therefore bounded by {@link #attemptTimeoutMs()} via {@link #connectAndAuth} so the loop keeps
     * its {@code startupWaitSeconds} deadline. Retries on any connection-level failure or attempt
     * timeout; throws immediately if the server explicitly rejects the credentials, since retrying
     * won't help.
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
        long timeoutMs = attemptTimeoutMs();

        ExecutorService exec = newSshExecutor(host);
        IOException lastError = null;
        try {
            while (System.currentTimeMillis() < deadline) {
                try (SshConnection conn = connectAndAuth(host, creds, exec, timeoutMs)) {
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
                Thread.sleep(2000);
            }
        } finally {
            exec.shutdownNow();
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
