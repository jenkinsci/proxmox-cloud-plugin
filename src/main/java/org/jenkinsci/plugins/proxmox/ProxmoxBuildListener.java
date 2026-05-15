package org.jenkinsci.plugins.proxmox;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.model.listeners.RunListener;

@Extension
public class ProxmoxBuildListener extends RunListener<Run<?, ?>> {

    @Override
    public void onCompleted(Run<?, ?> run, TaskListener listener) {
        Executor executor = run.getExecutor();
        if (executor == null) return;

        Computer computer = executor.getOwner();
        if (computer instanceof ProxmoxComputer proxmoxComputer) {
            ProxmoxAgent agent = proxmoxComputer.getNode();
            if (agent != null) {
                agent.incrementUses();
            }
        }
    }
}
