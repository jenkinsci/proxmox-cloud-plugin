package org.jenkinsci.plugins.proxmox;

import hudson.plugins.sshslaves.SSHLauncher;
import org.jenkinsci.plugins.proxmox.config.JavaInstallation;
import org.junit.Test;

import static org.junit.Assert.assertEquals;

/**
 * Tests that {@link ProxmoxLauncher} forwards the agent-process tunables (JVM options and java path)
 * onto the delegate {@link SSHLauncher}. The 3-arg SSHLauncher constructor the plugin uses does not
 * accept them, so they are applied via setters in {@code configureDelegate()}. These were bound from
 * the UI and the YAML config loader but previously dropped before reaching the launcher.
 */
public class ProxmoxLauncherTest {

    private static ProxmoxLauncher launcher(String javaPath, String jvmOptions, JavaInstallation java) {
        return new ProxmoxLauncher("ssh-cred", javaPath, jvmOptions, 60, null, java);
    }

    private static SSHLauncher delegate() {
        return new SSHLauncher("10.0.0.5", 22, "ssh-cred");
    }

    @Test
    public void jvmOptionsAreForwarded() {
        SSHLauncher d = delegate();
        launcher("java", "-Xmx512m -Dfoo=bar", JavaInstallation.NONE).configureDelegate(d);
        assertEquals("-Xmx512m -Dfoo=bar", d.getJvmOptions());
    }

    @Test
    public void blankJvmOptionsAreNotForwarded() {
        SSHLauncher d = delegate();
        launcher("java", "   ", JavaInstallation.NONE).configureDelegate(d);
        assertEquals("", d.getJvmOptions()); // SSHLauncher default when unset
    }

    @Test
    public void jvmOptionsForwardedEvenWhenJdkAutoInstalled() {
        // JVM options configure the remoting JVM regardless of how java got onto the agent.
        SSHLauncher d = delegate();
        launcher("java", "-Xmx1g", JavaInstallation.OPENJDK_21).configureDelegate(d);
        assertEquals("-Xmx1g", d.getJvmOptions());
    }

    @Test
    @SuppressWarnings("deprecation") // getJavaPath() is deprecated but is the only read accessor
    public void customJavaPathForwardedWhenJdkNotAutoInstalled() {
        SSHLauncher d = delegate();
        launcher("/opt/jdk/bin/java", "", JavaInstallation.NONE).configureDelegate(d);
        assertEquals("/opt/jdk/bin/java", d.getJavaPath());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void defaultJavaPathLeavesSshLauncherAutoDetection() {
        // The constructor normalises blank -> "java"; the default "java" must NOT pin the delegate,
        // so SSHLauncher keeps its own (richer) java auto-detection instead of "java" on the PATH.
        SSHLauncher d = delegate();
        launcher("java", "", JavaInstallation.NONE).configureDelegate(d);
        assertEquals("", d.getJavaPath());
    }

    @Test
    @SuppressWarnings("deprecation")
    public void customJavaPathIgnoredWhenJdkAutoInstalled() {
        // Java Path applies only when Java Version is NONE (per the field help): an auto-installed
        // JDK lands on the PATH, so a custom path must not override the freshly installed java.
        SSHLauncher d = delegate();
        launcher("/opt/jdk/bin/java", "", JavaInstallation.OPENJDK_21).configureDelegate(d);
        assertEquals("", d.getJavaPath());
    }
}
