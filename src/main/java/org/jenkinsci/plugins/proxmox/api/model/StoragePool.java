package org.jenkinsci.plugins.proxmox.api.model;

public record StoragePool(String storage, String type, long avail) {
}
