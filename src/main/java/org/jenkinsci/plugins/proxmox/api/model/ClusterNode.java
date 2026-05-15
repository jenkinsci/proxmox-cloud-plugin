package org.jenkinsci.plugins.proxmox.api.model;

public record ClusterNode(String node, String status, double cpu, long maxmem) {
}
