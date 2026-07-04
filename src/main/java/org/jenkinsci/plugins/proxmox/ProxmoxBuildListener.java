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
 * {@code run.getExecutor()} therefore never sees the agent. The hooks fire once per task that
 * actually ran on the agent's executor, covering freestyle builds and Pipeline {@code node} blocks
 * alike.
 *
 * <p>The use is counted in {@link #taskAccepted}, NOT on completion (mirroring how the EC2 plugin
 * counts uses): the executor is freed around the time the completion hooks run, so a
 * count-on-completion listener leaves a window in which the queue could dispatch the next build
 * onto a capped agent while {@link ProxmoxRetentionStrategy#isAcceptingTasks} still reads the
 * stale count. Counting on acceptance removes the window entirely: a running build is in the
 * count from the moment it is assigned.
 */
@Extension
public class ProxmoxBuildListener implements ExecutorListener {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxBuildListener.class.getName());

    @Override
    public void taskAccepted(Executor executor, Queue.Task task) {
        countUse(executor.getOwner());
    }

    @Override
    public void taskCompleted(Executor executor, Queue.Task task, long durationMS) {
        reapIfExhausted(executor);
    }

    @Override
    public void taskCompletedWithProblems(Executor executor, Queue.Task task, long durationMS, Throwable problems) {
        reapIfExhausted(executor);
    }

    /** Increment the use count if the task is starting on a Proxmox agent. Package-private for testing. */
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
                // Recycled a used-up warm-pool agent; restore the template minimum promptly rather than
                // waiting for the next periodic top-up (mirrors EC2's taskAccepted maxTotalUses path).
                ProxmoxMinimumInstances.scheduleCheck();
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
