package org.jenkinsci.plugins.proxmox.api.model;

public record StoragePool(String storage, String type, long avail, int shared) {

    public StoragePool(String storage, String type, long avail) {
        this(storage, type, avail, 0);
    }
}
