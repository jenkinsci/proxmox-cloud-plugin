package org.jenkinsci.plugins.proxmox;

import hudson.Extension;
import hudson.model.Computer;
import hudson.model.Executor;
import hudson.model.ExecutorListener;
import hudson.model.Queue;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Counts how many builds have run on each {@link ProxmoxAgent}, driving the "Builds Run" display and
 * the {@code maxTotalUses} retention check, and reaps an agent the instant it exhausts its reuse cap.
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

    private static final Logger LOGGER = Logger.getLogger(ProxmoxBuildListener.class.getName());

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        countUse(executor.getOwner());
        reapIfExhausted(executor);
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        countUse(executor.getOwner());
        reapIfExhausted(executor);
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

    /**
     * Terminate the agent the instant it hits its reuse cap and goes idle, instead of waiting up to a
     * minute for the next {@link ProxmoxRetentionStrategy#check} (during which the capped agent is
     * useless: it accepts no new tasks yet still holds a cap slot). The just-completed executor still
     * reports busy at this point, so "idle" means no OTHER executor on the agent is running a task.
     */
    private static void reapIfExhausted(Executor executor) {
        Computer computer = executor.getOwner();
        if (!(computer instanceof ProxmoxComputer proxmoxComputer)) {
            return;
        }
        ProxmoxAgent agent = proxmoxComputer.getNode();
        if (agent == null) {
            return;
        }
        if (!shouldReapNow(agent.getMaxTotalUses(), agent.getTotalUses(),
                hasOtherBusyExecutor(computer, executor))) {
            return;
        }
        LOGGER.fine(() -> "Agent " + agent.getNodeName() + " reached max uses (" + agent.getMaxTotalUses()
                + ") and is idle; terminating immediately");
        // terminate() shuts down and destroys the VM, so run it off the executor thread.
        Computer.threadPoolForRemoting.submit(() -> {
            try {
                agent.terminate();
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Failed to terminate agent " + agent.getNodeName(), e);
            }
        });
    }

    /**
     * Whether a capped agent should be reaped now: reuse is limited, the cap is reached, and no other
     * executor on the agent is still busy. Pure and package-private for unit testing.
     */
    static boolean shouldReapNow(int maxTotalUses, int totalUses, boolean otherBusyExecutor) {
        return maxTotalUses > 0 && totalUses >= maxTotalUses && !otherBusyExecutor;
    }

    /** Whether any executor other than the one that just completed is still running a task. */
    private static boolean hasOtherBusyExecutor(Computer computer, Executor completing) {
        for (Executor e : computer.getExecutors()) {
            if (e != completing && e.isBusy()) {
                return true;
            }
        }
        for (Executor e : computer.getOneOffExecutors()) {
            if (e != completing && e.isBusy()) {
                return true;
            }
        }
        return false;
    }
}
