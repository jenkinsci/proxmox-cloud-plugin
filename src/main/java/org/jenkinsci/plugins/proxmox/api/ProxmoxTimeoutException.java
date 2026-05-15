package org.jenkinsci.plugins.proxmox.api;

public class ProxmoxTimeoutException extends ProxmoxException {

    public ProxmoxTimeoutException(String message) {
        super(message);
    }
}
