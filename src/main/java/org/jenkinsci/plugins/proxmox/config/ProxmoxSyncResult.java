package org.jenkinsci.plugins.proxmox.config;

import java.util.List;

public record ProxmoxSyncResult(
    int cloudsConfigured,
    int templatesConfigured,
    List<String> warnings,
    List<String> errors
) {
    public boolean isSuccess() {
        return errors.isEmpty();
    }

    public String getSummary() {
        if (!isSuccess()) {
            return "Sync failed: " + String.join("; ", errors);
        }
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Configured %d cloud(s) with %d template(s).", cloudsConfigured, templatesConfigured));
        if (!warnings.isEmpty()) {
            sb.append(" Warnings: ").append(String.join("; ", warnings));
        }
        return sb.toString();
    }
}
