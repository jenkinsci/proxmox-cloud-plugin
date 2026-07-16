Behaviour.specify(".proxmox-os-type-toggle", "proxmox-os-type-toggle", 0, function(marker) {
    // Jenkins 2.4xx+ renders <f:entry> as <div class="jenkins-form-item">, not <tr>.
    var entry = marker.closest(".jenkins-form-item");
    if (!entry) return;
    var select = entry.querySelector("select");
    if (!select) return;

    var LINUX_REMOFS_PATTERN = /^\/home\/[^/]+\/agent$/;

    function apply(isInitialLoad) {
        var block = select.closest(".repeated-chunk");
        if (!block) return;
        var isWindows = select.value === "WINDOWS";

        // Hide the containing form entry for every Linux-only field.
        block.querySelectorAll(".proxmox-linux-only").forEach(function(el) {
            var row = el.closest(".jenkins-form-item");
            if (row) row.style.display = isWindows ? "none" : "";
        });

        // Mirror image: show Windows-only fields (e.g. Login Shell) only when Windows is active.
        block.querySelectorAll(".proxmox-windows-only").forEach(function(el) {
            var row = el.closest(".jenkins-form-item");
            if (row) row.style.display = isWindows ? "" : "none";
        });

        // Also hide the entire Cloud-Init section (header + entries).
        // <f:section> renders as <section class="jenkins-section">.
        var sectionStart = block.querySelector(".proxmox-section-start");
        if (sectionStart) {
            var section = sectionStart.closest(".jenkins-section");
            if (section) section.style.display = isWindows ? "none" : "";
        }

        // Always clear remoteFs when Windows is active and the value is the Linux computed
        // default. This handles both initial page load (Windows template with stored null
        // showing the /home/.../agent computed fallback) and explicit OS type switches.
        if (isWindows) {
            var remoteFsInput = block.querySelector(".proxmox-remoteFs");
            if (remoteFsInput && LINUX_REMOFS_PATTERN.test(remoteFsInput.value)) {
                remoteFsInput.value = "";
            }
        }

        // On explicit user change (not initial load), also clear the other Linux-only
        // field values so stale data does not silently persist behind hidden fields.
        if (!isInitialLoad && isWindows) {
            ["ciUser", "ipConfig", "nameserver", "searchDomain"].forEach(function(field) {
                var el = block.querySelector("[name$='" + field + "']");
                if (el) el.value = "";
            });
            var distSelect = block.querySelector("[name$='javaDistribution']");
            if (distSelect) distSelect.value = "NONE";
        }
    }

    apply(true);
    select.addEventListener("change", function() { apply(false); });
});
