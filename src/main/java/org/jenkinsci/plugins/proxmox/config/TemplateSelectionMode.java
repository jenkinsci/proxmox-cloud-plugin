package org.jenkinsci.plugins.proxmox.config;

public enum TemplateSelectionMode {
    STATIC_ID("Static template VM id"),
    NAME_REGEX("Newest template whose name matches a regex"),
    TAG("Newest template with a tag");

    private final String displayName;

    TemplateSelectionMode(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
