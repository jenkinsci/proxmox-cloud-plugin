Behaviour.specify(
    ".proxmox-template-location-toggle",
    "proxmox-template-location-toggle",
    0,
    function (marker) {
        var entry = marker.closest(".jenkins-form-item");
        var select = entry && entry.querySelector("select");
        var chunk = marker.closest(".repeated-chunk");
        if (!select || !chunk) return;

        function apply(isInitialLoad) {
            var local = select.value === "EACH_TARGET_NODE";
            chunk.querySelectorAll(".proxmox-fixed-template-node").forEach(function (fixedMarker) {
                var row = fixedMarker.closest(".jenkins-form-item");
                if (row) row.style.display = local ? "none" : "";
            });

            var staticRadio = chunk.querySelector(
                'input[type="radio"][name$="templateSelectionMode"][value="STATIC_ID"]'
            );
            if (staticRadio) {
                staticRadio.disabled = local;
                var label = staticRadio.nextElementSibling;
                if (label) label.setAttribute("aria-disabled", local ? "true" : "false");
                if (local && staticRadio.checked && !isInitialLoad) {
                    var tagRadio = chunk.querySelector(
                        'input[type="radio"][name$="templateSelectionMode"][value="TAG"]'
                    );
                    if (tagRadio) {
                        tagRadio.checked = true;
                        tagRadio.defaultChecked = true;
                        fireEvent(tagRadio, "change");
                    }
                }
            }
        }

        apply(true);
        select.addEventListener("change", function () { apply(false); });
    }
);
