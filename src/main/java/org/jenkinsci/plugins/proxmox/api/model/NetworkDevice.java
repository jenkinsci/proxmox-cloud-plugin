package org.jenkinsci.plugins.proxmox.api.model;

public record NetworkDevice(String iface, String type, int active) {

    public boolean isBridge() {
        return "bridge".equals(type);
    }
}
