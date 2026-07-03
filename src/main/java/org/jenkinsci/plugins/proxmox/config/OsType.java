package org.jenkinsci.plugins.proxmox.config;

public enum OsType {
    LINUX("Linux"),
    WINDOWS("Microsoft Windows");

    private final String displayName;

    OsType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
