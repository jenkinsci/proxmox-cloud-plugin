package org.jenkinsci.plugins.proxmox.api.model;

import com.google.gson.annotations.SerializedName;

public record VirtualMachine(
        int vmid,
        String name,
        String status,
        String node,
        @SerializedName("template") int templateFlag
) {
    public boolean isTemplate() {
        return templateFlag == 1;
    }
}
