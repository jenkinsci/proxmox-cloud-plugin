package org.jenkinsci.plugins.proxmox;

import com.trilead.ssh2.Connection;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.util.StreamTaskListener;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PowerShellEnvironmentSSHLauncherTest {

    @Test
    void reportsEnvironmentWithPowerShellCommand() throws Exception {
        AtomicReference<String> command = new AtomicReference<>();
        Connection connection = new Connection("unused") {
            @Override
            public int exec(String remoteCommand, OutputStream output)
                    throws java.io.IOException, InterruptedException {
                command.set(remoteCommand);
                return 0;
            }
        };
        PowerShellEnvironmentSSHLauncher launcher =
                new PowerShellEnvironmentSSHLauncher("10.0.0.5", 22, "ssh-cred");
        Field connectionField = SSHLauncher.class.getDeclaredField("connection");
        connectionField.setAccessible(true);
        connectionField.set(launcher, connection);

        ByteArrayOutputStream log = new ByteArrayOutputStream();
        try (StreamTaskListener listener = new StreamTaskListener(log, StandardCharsets.UTF_8)) {
            launcher.reportEnvironment(listener);
        }

        assertEquals(PowerShellEnvironmentSSHLauncher.ENVIRONMENT_COMMAND, command.get());
        assertTrue(log.toString(StandardCharsets.UTF_8).contains("The remote user's environment is:"));
    }
}
