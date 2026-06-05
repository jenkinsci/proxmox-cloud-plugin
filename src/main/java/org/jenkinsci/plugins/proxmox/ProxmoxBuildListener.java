package org.jenkinsci.plugins.proxmox;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Queue;

/**
 * Counts how many builds have run on each {@link ProxmoxAgent}, driving the "Builds Run" display and
 * the {@code maxTotalUses} retention check.
 *
 * <p>Implemented as an {@link ExecutorListener} rather than a {@code RunListener}: for Pipeline jobs
 * the top-level {@code Run} executes as a flyweight task on the controller, while the work runs as
 * separate executor tasks inside {@code node {}} blocks. A {@code RunListener} keyed off
 * {@code run.getExecutor()} therefore never sees the agent. {@code taskCompleted} fires once per task
 * that actually ran on the agent's executor, covering freestyle builds and Pipeline {@code node}
 * blocks alike (mirrors how the EC2 plugin counts uses in its retention strategy).
 */
@Extension
public class ProxmoxBuildListener implements ExecutorListener {

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        countUse(executor.getOwner());
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        countUse(executor.getOwner());
    }

    /** Increment the use count if the task ran on a Proxmox agent. Package-private for testing. */
    static void countUse(Computer computer) {
        if (computer instanceof ProxmoxComputer proxmoxComputer) {
            ProxmoxAgent agent = proxmoxComputer.getNode();
            if (agent != null) {
                agent.incrementUses();
            }
        }
    }
}
