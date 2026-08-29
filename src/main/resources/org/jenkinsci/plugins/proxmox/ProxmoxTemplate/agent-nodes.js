Behaviour.specify(".proxmox-agent-nodes", "proxmox-agent-nodes", 0, function (root) {
    var chunk = root.closest(".repeated-chunk");
    var list = root.querySelector(".proxmox-agent-node-list");
    var summary = root.querySelector(".proxmox-agent-node-summary");
    var status = root.querySelector(".proxmox-agent-node-status");
    var dependencyValue = root.querySelector('input[name="_.selectedTargetNodes"]');
    var targetStorageValue = root.querySelector('input[name="_.currentTargetStorage"]');
    var networkBridgeValue = root.querySelector('input[name="_.currentNetworkBridge"]');
    var sourceSelect = chunk && chunk.querySelector('select[name="_.node"]');
    var targetStorageSelect = chunk && chunk.querySelector('select[name="_.targetStorage"]');
    var networkBridgeSelect = chunk && chunk.querySelector('select[name="_.networkBridge"]');
    if (!chunk || !list || !summary || !status || !dependencyValue || !targetStorageValue
            || !networkBridgeValue || !sourceSelect) return;
    if (!root.id) root.id = "proxmox-agent-nodes-" + Math.random().toString(36).slice(2);

    var saved = new Set((root.dataset.selected || "").split(",").filter(Boolean));
    var topology = null;
    var selectionBeforeStandalone = null;

    function checkedValues() {
        return new Set(Array.from(list.querySelectorAll(
            'input[name="_.targetNodes"]:checked'
        )).map(function (input) { return input.value; }));
    }

    function effectiveSelection() {
        var checked = checkedValues();
        return checked.size > 0 ? checked : new Set(saved);
    }

    function notifyDependents() {
        dependencyValue.value = Array.from(checkedValues()).join(",");
        fireEvent(sourceSelect, "change");
    }

    function sourceOptions() {
        return Array.from(sourceSelect.options).filter(function (option) {
            return option.value && !option.value.startsWith("(");
        }).map(function (option) {
            var optionText = option.textContent || option.value;
            var offline = optionText.endsWith(" (offline)");
            return {
                value: option.value,
                name: offline ? optionText.slice(0, -" (offline)".length) : optionText,
                online: !offline,
                unavailable: false
            };
        });
    }

    function inventoryOptions() {
        if (topology && (topology.state === "cluster" || topology.state === "standalone")) {
            return topology.nodes.map(function (node) {
                return {
                    value: node.name,
                    name: node.name,
                    online: node.online === true,
                    unavailable: false
                };
            });
        }
        return sourceOptions();
    }

    function updateSummary(optionCount, selectedCount) {
        if (optionCount === 0) {
            summary.textContent = "";
            return;
        }
        summary.textContent = selectedCount + " of " + optionCount + " "
            + (optionCount === 1 ? "node" : "nodes") + " selected";
    }

    function render() {
        var current = effectiveSelection();
        if (topology && topology.state === "standalone" && topology.nodes.length === 1) {
            current = new Set([topology.nodes[0].name]);
        } else if (current.size === 0 && sourceSelect.value) {
            current.add(sourceSelect.value);
        }

        var options = inventoryOptions();
        var known = new Set(options.map(function (option) { return option.value; }));
        current.forEach(function (value) {
            if (!known.has(value)) {
                options.push({
                    value: value,
                    name: value,
                    online: false,
                    unavailable: true
                });
            }
        });

        list.replaceChildren();
        status.textContent = "";
        if (options.length === 0) {
            summary.textContent = "";
            status.textContent = "Agent nodes are unavailable until the API connection is configured.";
            return;
        }

        if (current.size === 0) current.add(options[0].value);
        var locked = topology && topology.state === "permission-missing";
        options.forEach(function (option, index) {
            var wrapper = document.createElement("span");
            wrapper.className = "jenkins-checkbox proxmox-agent-node-option";

            var input = document.createElement("input");
            input.type = "checkbox";
            input.name = "_.targetNodes";
            input.value = option.value;
            input.setAttribute("json", option.value);
            input.id = root.id + "-" + index;
            input.checked = current.has(option.value);
            input.disabled = locked;

            var label = document.createElement("label");
            label.htmlFor = input.id;
            label.textContent = option.name;

            var availability = document.createElement("span");
            availability.className = "proxmox-agent-node-availability";
            availability.textContent = option.unavailable
                ? "Unavailable"
                : (option.online ? "Online" : "Offline");

            input.addEventListener("change", function () {
                if (checkedValues().size === 0) {
                    input.checked = true;
                    status.textContent = "At least one Agent Node is required.";
                    updateSummary(options.length, checkedValues().size);
                    return;
                }
                status.textContent = "";
                saved = checkedValues();
                root.dataset.selected = Array.from(saved).join(",");
                updateSummary(options.length, saved.size);
                notifyDependents();
            });
            wrapper.append(input, label, availability);
            list.appendChild(wrapper);
        });

        var selected = checkedValues();
        updateSummary(options.length, selected.size);
    }

    function applyTopology(next) {
        var wasStandalone = topology && topology.state === "standalone";
        var willBeStandalone = next && next.state === "standalone";
        if (!wasStandalone && willBeStandalone) {
            selectionBeforeStandalone = effectiveSelection();
        } else if (wasStandalone && !willBeStandalone && selectionBeforeStandalone) {
            saved = new Set(selectionBeforeStandalone);
            root.dataset.selected = Array.from(saved).join(",");
            selectionBeforeStandalone = null;
        }
        topology = next;
        render();
        notifyDependents();
    }

    root.addEventListener("proxmox:cluster-topology", function (event) {
        applyTopology(event.detail);
    });
    sourceSelect.addEventListener("filled", function () {
        render();
        notifyDependents();
    });
    if (targetStorageSelect) {
        targetStorageSelect.addEventListener("change", function () {
            targetStorageValue.value = targetStorageSelect.value;
        });
    }
    if (networkBridgeSelect) {
        networkBridgeSelect.addEventListener("change", function () {
            networkBridgeValue.value = networkBridgeSelect.value;
        });
    }

    var templateContainer = chunk.parentElement;
    var cloudScope = templateContainer && templateContainer.closest(".repeated-chunk");
    if (!cloudScope) cloudScope = chunk.closest("form");
    if (cloudScope && cloudScope._proxmoxClusterTopology) {
        applyTopology(cloudScope._proxmoxClusterTopology);
    } else {
        render();
    }
});
