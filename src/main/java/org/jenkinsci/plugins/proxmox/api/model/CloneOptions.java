package org.jenkinsci.plugins.proxmox.api.model;

public record CloneOptions(int newVmId,
                            String name,
                            String description,
                            boolean full,
                            String storage,
                            String pool) {
}
