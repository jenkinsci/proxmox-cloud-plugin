package org.jenkinsci.plugins.proxmox;

import org.jenkinsci.plugins.proxmox.api.ProxmoxClient;
import org.jenkinsci.plugins.proxmox.api.ProxmoxException;
import org.jenkinsci.plugins.proxmox.api.model.VirtualMachine;
import org.jenkinsci.plugins.proxmox.config.TemplateSelectionMode;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * Resolves a dynamic template selection (name regex or tag) to a concrete template VM at
 * provision time. When several templates match, the most recently created wins: highest
 * {@code ctime} from the VM config's {@code meta} property; templates without a {@code ctime}
 * sort oldest; ties go to the highest VM id. Shared by {@link ProxmoxTemplate#provision} and the
 * form's "matches N templates" feedback so both always agree.
 */
final class TemplateResolver {

    private static final Logger LOGGER = Logger.getLogger(TemplateResolver.class.getName());

    private TemplateResolver() {
    }

    /**
     * Full-string match: the pattern must match the entire template name (use {@code .*} for
     * partial matches). Propagates {@link java.util.regex.PatternSyntaxException} on a bad pattern.
     */
    static Predicate<VirtualMachine> nameRegexMatcher(String regex) {
        Pattern pattern = Pattern.compile(regex);
        return vm -> vm.name() != null && pattern.matcher(vm.name()).matches();
    }

    static Predicate<VirtualMachine> tagMatcher(String tag) {
        return vm -> vm.hasTag(tag);
    }

    static VirtualMachine resolve(ProxmoxClient client, String node, TemplateSelectionMode selectionMode,
                                  String nameRegex, String tag) {
        List<VirtualMachine> templates = client.getTemplates(node);
        Predicate<VirtualMachine> matcher = selectionMode == TemplateSelectionMode.NAME_REGEX
                ? nameRegexMatcher(nameRegex)
                : tagMatcher(tag);
        List<VirtualMachine> matches = templates.stream().filter(matcher).toList();
        if (matches.isEmpty()) {
            String criterion = selectionMode == TemplateSelectionMode.NAME_REGEX
                    ? "a name matching '" + nameRegex + "'"
                    : "tag '" + tag + "'";
            throw new ProxmoxException("No template on node '" + node + "' has " + criterion
                    + " (searched " + templates.size() + " templates)");
        }
        return pickNewest(client, node, matches);
    }

    static VirtualMachine pickNewest(ProxmoxClient client, String node, List<VirtualMachine> matches) {
        // Pre-fetch creation times: Stream.max may invoke the comparator's key extractor
        // repeatedly per element, and each lookup is an API call.
        Map<Integer, Long> creationTimes = new HashMap<>();
        for (VirtualMachine vm : matches) {
            creationTimes.put(vm.vmid(), safeCreationTime(client, node, vm.vmid()));
        }
        return matches.stream()
                .max(Comparator.<VirtualMachine>comparingLong(vm -> creationTimes.get(vm.vmid()))
                        .thenComparingInt(VirtualMachine::vmid))
                .orElseThrow();
    }

    private static long safeCreationTime(ProxmoxClient client, String node, int vmId) {
        try {
            return client.getVmCreationTime(node, vmId);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, e, () -> "Could not read creation time of VM " + vmId
                    + " on " + node + "; treating as oldest");
            return -1;
        }
    }
}
