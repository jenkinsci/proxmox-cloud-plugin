package org.jenkinsci.plugins.proxmox.config;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProxmoxSyncResultTest {

    @Test
    void summaryReportsSuccess() {
        ProxmoxSyncResult r = new ProxmoxSyncResult(2, 3, List.of(), List.of());
        assertTrue(r.isSuccess());
        assertEquals("Configured 2 cloud(s) with 3 template(s).", r.getSummary());
    }

    @Test
    void summaryIncludesWarnings() {
        ProxmoxSyncResult r = new ProxmoxSyncResult(1, 1, List.of("w1", "w2"), List.of());
        assertTrue(r.isSuccess());
        assertTrue(r.getSummary().contains("Warnings: w1; w2"), r.getSummary());
    }

    @Test
    void summaryReportsFailureWithErrors() {
        ProxmoxSyncResult r = new ProxmoxSyncResult(0, 0, List.of(), List.of("e1", "e2"));
        assertFalse(r.isSuccess());
        assertEquals("Sync failed: e1; e2", r.getSummary());
    }
}
