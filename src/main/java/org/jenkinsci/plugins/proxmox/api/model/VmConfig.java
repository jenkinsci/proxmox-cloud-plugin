package org.jenkinsci.plugins.proxmox.api.model;

public record VmConfig(Integer cores,
                        Integer memory,
                        String ciuser,
                        // sshkeys carries SSH public keys for cloud-init injection, not a secret. VmConfig is a
                        // transient request DTO built only to call ProxmoxClient.configureVm; it is never a
                        // @DataBound field and is never serialized to disk. Security-scan false positive.
                        String sshkeys, // lgtm[jenkins/plaintext-storage]
                        String ipconfig0,
                        String nameserver,
                        String searchdomain) {
}
