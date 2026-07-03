package org.jenkinsci.plugins.proxmox.api.model;

import com.google.gson.annotations.SerializedName;

import java.util.List;
import java.util.Locale;

public record VirtualMachine(
        int vmid,
        String name,
        String status,
        String node,
        long uptime,
        @SerializedName("template") int templateFlag,
        String tags
) {
    public boolean isTemplate() {
        return templateFlag == 1;
    }

    /**
     * Proxmox stores tags as a semicolon-delimited string, lowercase by default. Returned
     * lowercased so matching is case-insensitive even when the datacenter allows mixed case.
     */
    public List<String> tagList() {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(tags.split(";"))
                .map(String::trim)
                .filter(t -> !t.isEmpty())
                .map(t -> t.toLowerCase(Locale.ROOT))
                .toList();
    }

    public boolean hasTag(String tag) {
        return tag != null && !tag.isBlank() && tagList().contains(tag.trim().toLowerCase(Locale.ROOT));
    }
}
