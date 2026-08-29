package org.jenkinsci.plugins.proxmox;

import hudson.model.TaskListener;
import hudson.plugins.sshslaves.SSHLauncher;

import java.io.IOException;

/**
 * SSH Build Agents launcher that reports a Windows PowerShell environment without invoking the
 * incompatible {@code set} alias. Windows PowerShell resolves {@code set} to {@code Set-Variable},
 * which requires a name and otherwise prints a parameter-binding error in the agent launch log.
 */
final class PowerShellEnvironmentSSHLauncher extends SSHLauncher {

    static final String ENVIRONMENT_COMMAND = "cmd /c set";

    PowerShellEnvironmentSSHLauncher(String host, int port, String credentialsId) {
        super(host, port, credentialsId);
    }

    @Override
    protected void reportEnvironment(TaskListener listener) throws IOException, InterruptedException {
        listener.getLogger().println(getTimestamp() + " [SSH] The remote user's environment is:");
        getConnection().exec(ENVIRONMENT_COMMAND, listener.getLogger());
    }
}
