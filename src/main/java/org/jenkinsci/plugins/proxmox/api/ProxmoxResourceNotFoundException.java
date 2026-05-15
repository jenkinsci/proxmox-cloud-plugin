package org.jenkinsci.plugins.proxmox.api;

public class ProxmoxResourceNotFoundException extends ProxmoxException {

    public ProxmoxResourceNotFoundException(String message) {
        super(message);
    }
}
