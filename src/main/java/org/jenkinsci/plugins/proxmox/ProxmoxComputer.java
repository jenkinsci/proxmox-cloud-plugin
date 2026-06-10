package org.jenkinsci.plugins.proxmox;

import hudson.slaves.AbstractCloudComputer;
import org.jenkinsci.plugins.cloudstats.ProvisioningActivity;
import org.jenkinsci.plugins.cloudstats.TrackedItem;

public class ProxmoxComputer extends AbstractCloudComputer<ProxmoxAgent> implements TrackedItem {

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

    /** Delegates to the backing node so cloud-stats can track this computer's launch/operate phases. */
    @Override
    public ProvisioningActivity.Id getId() {
        ProxmoxAgent node = getNode();
        return node != null ? node.getId() : null;
    }
}
