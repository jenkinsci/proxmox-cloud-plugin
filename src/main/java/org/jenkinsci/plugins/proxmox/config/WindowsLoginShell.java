package org.jenkinsci.plugins.proxmox.config;

/**
 * The login shell configured as the Windows agent's OpenSSH {@code DefaultShell}. It decides how the
 * remoting start command must be quoted.
 *
 * <p>{@code SSHLauncher} launches the agent with a hardcoded {@code cd "<workDir>" && java ...}
 * command. {@code &&} is a valid statement separator in {@code cmd.exe} and in PowerShell 7+
 * ({@code pwsh}), so those shells run the command as-is. Windows PowerShell 5.x rejects it
 * ("The token '&amp;&amp;' is not a valid statement separator in this version") and the agent JVM
 * never starts. For PowerShell the command is wrapped as {@code cmd /c '<command>'}: PowerShell
 * treats the single-quoted body as one literal argument (so it never tries to parse {@code &&}) and
 * hands it to {@code cmd.exe}, which runs {@code cd "..." && java ...} correctly. This was verified
 * against a live Win32-OpenSSH PowerShell 5.1 shell; note PowerShell's {@code --%} stop-parsing
 * token does NOT work over the OpenSSH exec channel, so it is not used.
 *
 * <p>{@link #AUTO} (the default) detects the shell at launch instead of asking the user to pick it
 * (see {@code ProxmoxLauncher.detectWindowsShell}); the explicit values remain as manual overrides.
 * The prefix and suffix are applied via
 * {@link hudson.plugins.sshslaves.SSHLauncher#setPrefixStartSlaveCmd} and
 * {@link hudson.plugins.sshslaves.SSHLauncher#setSuffixStartSlaveCmd}. Only relevant for Windows
 * agents; Linux agents never wrap the command.
 */
public enum WindowsLoginShell {
    /** Detect the shell at launch and wrap only if it rejects {@code &&}. Resolves to CMD or POWERSHELL. */
    AUTO("Auto-detect (recommended)", "", ""),

    /** {@code cmd.exe} (the OpenSSH default when no {@code DefaultShell} is set). Needs no wrapper. */
    CMD("Command Prompt (cmd.exe)", "", ""),

    /**
     * PowerShell. Windows PowerShell 5.x cannot parse {@code &&}, so the command is wrapped as
     * {@code cmd /c '<command>'}. The wrapper is a literal single-quoted string in every PowerShell
     * version, so it is safe for both Windows PowerShell 5.x and PowerShell 7+.
     */
    POWERSHELL("PowerShell", "cmd /c '", "'");

    private final String displayName;
    private final String startCommandPrefix;
    private final String startCommandSuffix;

    WindowsLoginShell(String displayName, String startCommandPrefix, String startCommandSuffix) {
        this.displayName = displayName;
        this.startCommandPrefix = startCommandPrefix;
        this.startCommandSuffix = startCommandSuffix;
    }

    public String getDisplayName() {
        return displayName;
    }

    /**
     * Prefix prepended to {@code SSHLauncher}'s agent-start command so it runs under this shell.
     * Empty when the shell already accepts {@code SSHLauncher}'s {@code &&} syntax.
     */
    public String getStartCommandPrefix() {
        return startCommandPrefix;
    }

    /** Suffix appended to {@code SSHLauncher}'s agent-start command (closes the wrapper, if any). */
    public String getStartCommandSuffix() {
        return startCommandSuffix;
    }
}
