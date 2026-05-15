package org.jenkinsci.plugins.proxmox.api;

public class ProxmoxException extends RuntimeException {

    public ProxmoxException(String message) {
        super(message);
    }

    public ProxmoxException(String message, Throwable cause) {
        super(message, cause);
    }
}
