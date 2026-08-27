package org.jenkinsci.plugins.proxmox.config;

/** Where the source template for a provision is resolved. */
public enum TemplateLocation {
    FIXED_NODE("One template on Template Node"),
    EACH_TARGET_NODE("Matching template on each Agent Node");

    private final String displayName;

    TemplateLocation(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
