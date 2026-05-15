package org.jenkinsci.plugins.proxmox.api.model;

public record TaskStatus(String upid, String status, String exitstatus) {

    public boolean isRunning() {
        return "running".equals(status);
    }

    public boolean isSuccessful() {
        return !isRunning() && "OK".equals(exitstatus);
    }
}
