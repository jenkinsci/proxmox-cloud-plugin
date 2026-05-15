package org.jenkinsci.plugins.proxmox.config;

public enum CloneStrategy {
    FULL("Full Clone"),
    LINKED("Linked Clone");

    private final String displayName;

    CloneStrategy(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
