package org.jenkinsci.plugins.proxmox;

import hudson.slaves.AbstractCloudComputer;

public class ProxmoxComputer extends AbstractCloudComputer<ProxmoxAgent> {

    public ProxmoxComputer(ProxmoxAgent agent) {
        super(agent);
    }

    public int getVmId() {
        ProxmoxAgent node = getNode();
        return node != null ? node.getVmId() : -1;
    }

    public String getProxmoxNode() {
        ProxmoxAgent node = getNode();
        return node != null ? node.getProxmoxNode() : null;
    }
}
