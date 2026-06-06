package org.jenkinsci.plugins.proxmox;

import com.google.gson.JsonObject;
import org.jenkinsci.plugins.proxmox.api.ProxmoxClient;
import org.jenkinsci.plugins.proxmox.api.ProxmoxException;
import org.jenkinsci.plugins.proxmox.api.ProxmoxResourceNotFoundException;

/**
 * Helpers for safely destroying the VMs this plugin manages, shared by the per-agent teardown
 * ({@link ProxmoxAgent#_terminate}) and the periodic reconcile ({@link ProxmoxOrphanCleanup}).
 *
 * <p>VM ids are reused: Proxmox hands back the lowest free id, so a freed agent id can be re-cloned
 * immediately. A stale or duplicate terminate must therefore never destroy a VM by id alone, or it
 * could kill a newer agent's VM (or a manually-created VM) that happens to share the id. These
 * helpers gate destruction on the {@code jenkins-managed;cloud:<name>} description marker and treat a
 * missing VM as already-destroyed so a double-terminate is a no-op rather than an error.
 */
final class ProxmoxVms {

    private ProxmoxVms() {}

    /** The description marker stamped on every VM this cloud clones (see {@link ProxmoxTemplate}). */
    static String cloudMarker(String cloudName) {
        return "jenkins-managed;cloud:" + cloudName;
    }

    /**
     * Whether a VM config carries the given cloud's managed marker. Pure and package-private for
     * unit testing.
     */
    static boolean isManagedByCloud(JsonObject config, String cloudName) {
        if (config == null || !config.has("description") || config.get("description").isJsonNull()) {
            return false;
        }
        return config.get("description").getAsString().contains(cloudMarker(cloudName));
    }

    /**
     * Whether a Proxmox error means the VM is already gone, so destruction can be treated as done.
     * Covers both a clean 404 and the 500 Proxmox returns when destroying a VM whose config file no
     * longer exists ({@code Configuration file '.../NNN.conf' does not exist}). Pure and
     * package-private for unit testing.
     */
    static boolean isAlreadyGone(ProxmoxException e) {
        if (e instanceof ProxmoxResourceNotFoundException) {
            return true;
        }
        String message = e.getMessage();
        return message != null && message.contains("does not exist");
    }

    /**
     * Confirm a VM still belongs to this cloud before destroying it. Returns {@code false} (skip the
     * destroy) when the VM is gone or no longer carries this cloud's marker, e.g. its id has been
     * reused. Logging is left to the caller, which has the agent/node context.
     */
    static boolean confirmOwnedByCloud(ProxmoxClient client, String node, int vmId, String cloudName) {
        try {
            return isManagedByCloud(client.getVmConfig(node, vmId), cloudName);
        } catch (ProxmoxResourceNotFoundException e) {
            return false; // VM gone
        }
    }
}
