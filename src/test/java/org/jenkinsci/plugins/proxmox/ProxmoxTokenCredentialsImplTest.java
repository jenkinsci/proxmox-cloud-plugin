package org.jenkinsci.plugins.proxmox;

import com.cloudbees.plugins.credentials.CredentialsScope;
import hudson.util.Secret;
import org.jenkinsci.plugins.proxmox.config.ProxmoxTokenCredentialsImpl;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ProxmoxTokenCredentialsImplTest {

    @Test
    void testCredentialFields() {
        ProxmoxTokenCredentialsImpl creds = new ProxmoxTokenCredentialsImpl(
                CredentialsScope.GLOBAL,
                "test-id",
                "Test Proxmox Token",
                "user@pve!mytoken",
                Secret.fromString("12345678-abcd-efgh-ijkl-123456789012"));

        assertEquals("user@pve!mytoken", creds.getTokenId());
        assertEquals("12345678-abcd-efgh-ijkl-123456789012", creds.getTokenSecret().getPlainText());
        assertEquals("Test Proxmox Token", creds.getDescription());
    }
}
