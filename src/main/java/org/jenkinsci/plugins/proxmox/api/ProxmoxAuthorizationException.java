package org.jenkinsci.plugins.proxmox.api;

/** The Proxmox token authenticated but lacks permission for the requested operation. */
public class ProxmoxAuthorizationException extends ProxmoxException {

    public ProxmoxAuthorizationException(String message) {
        super(message);
    }
}
