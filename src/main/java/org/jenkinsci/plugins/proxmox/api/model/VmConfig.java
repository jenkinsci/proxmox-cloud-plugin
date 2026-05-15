package org.jenkinsci.plugins.proxmox.api.model;

public record VmConfig(Integer cores,
                        Integer memory,
                        String ciuser,
                        String sshkeys,
                        String ipconfig0,
                        String nameserver,
                        String searchdomain) {
}
