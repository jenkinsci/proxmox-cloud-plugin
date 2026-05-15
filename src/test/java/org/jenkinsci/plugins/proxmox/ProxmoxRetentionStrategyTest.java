package org.jenkinsci.plugins.proxmox;

import org.junit.Test;

import static org.junit.Assert.*;

public class ProxmoxRetentionStrategyTest {

    @Test
    public void testConstructionWithValidValues() {
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy(30, 10);
        assertNotNull(strategy);
    }

    @Test
    public void testConstructionWithZeroIdleMinutes() {
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy(0, 0);
        assertNotNull(strategy);
    }
}
