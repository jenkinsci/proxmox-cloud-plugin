package org.jenkinsci.plugins.proxmox.api.model;

/** One entry returned by Proxmox {@code /cluster/status}. */
public record ClusterStatusEntry(
        String type,
        String name,
        Integer online,
        Integer local,
        Integer nodes,
        Integer quorate) {
}
