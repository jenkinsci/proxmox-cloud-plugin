package org.jenkinsci.plugins.proxmox.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WindowsLoginShellTest {

    @Test
    void cmdNeedsNoWrapper() {
        assertEquals("", WindowsLoginShell.CMD.getStartCommandPrefix());
        assertEquals("", WindowsLoginShell.CMD.getStartCommandSuffix());
    }

    @Test
    void autoCarriesNoWrapperItself() {
        // AUTO is resolved to CMD or POWERSHELL at launch; its own prefix/suffix are unused.
        assertEquals("", WindowsLoginShell.AUTO.getStartCommandPrefix());
        assertEquals("", WindowsLoginShell.AUTO.getStartCommandSuffix());
    }

    @Test
    void windowsPowershellWrapsInCmdSingleQuotes() {
        // PS 5.x rejects &&; wrapping as cmd /c '<command>' hands the literal string to cmd.
        assertEquals("cmd /c '", WindowsLoginShell.POWERSHELL.getStartCommandPrefix());
        assertEquals("'", WindowsLoginShell.POWERSHELL.getStartCommandSuffix());
    }

    @Test
    void everyShellHasADisplayName() {
        for (WindowsLoginShell shell : WindowsLoginShell.values()) {
            assertTrue(shell.getDisplayName() != null && !shell.getDisplayName().isBlank(),
                    shell + " must have a display name");
        }
    }
}
