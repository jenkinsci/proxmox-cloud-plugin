package org.jenkinsci.plugins.proxmox.api;

public class ProxmoxTaskFailedException extends ProxmoxException {

    private final String upid;
    private final String exitStatus;

    public ProxmoxTaskFailedException(String upid, String exitStatus) {
        super("Task " + upid + " failed with exit status: " + exitStatus);
        this.upid = upid;
        this.exitStatus = exitStatus;
    }

    public String getUpid() {
        return upid;
    }

    public String getExitStatus() {
        return exitStatus;
    }
}
