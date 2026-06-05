package org.jenkinsci.plugins.proxmox;

import org.junit.Test;

import static org.junit.Assert.*;

public class ProxmoxRetentionStrategyTest {

    @Test
    public void testConstruction() {
        // The strategy is stateless; idle-timeout and max-uses now live on the ProxmoxAgent and are
        // read live in check(). Construction must remain trivial so it can be attached to every agent.
        ProxmoxRetentionStrategy strategy = new ProxmoxRetentionStrategy();
        assertNotNull(strategy);
    }
}
