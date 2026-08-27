Behaviour.specify(".proxmox-agent-nodes", "proxmox-agent-nodes", 0, function (root) {
    var chunk = root.closest(".repeated-chunk");
    var list = root.querySelector(".proxmox-agent-node-list");
    var status = root.querySelector(".proxmox-agent-node-status");
    var dependencyValue = root.querySelector('input[name="_.selectedTargetNodes"]');
    var targetStorageValue = root.querySelector('input[name="_.currentTargetStorage"]');
    var networkBridgeValue = root.querySelector('input[name="_.currentNetworkBridge"]');
    var sourceSelect = chunk && chunk.querySelector('select[name="_.node"]');
    var targetStorageSelect = chunk && chunk.querySelector('select[name="_.targetStorage"]');
    var networkBridgeSelect = chunk && chunk.querySelector('select[name="_.networkBridge"]');
    if (!chunk || !list || !status || !dependencyValue || !targetStorageValue
            || !networkBridgeValue || !sourceSelect) return;
    if (!root.id) root.id = "proxmox-agent-nodes-" + Math.random().toString(36).slice(2);

    var saved = new Set((root.dataset.selected || "").split(",").filter(Boolean));

    function checkedValues() {
        return new Set(Array.from(list.querySelectorAll('input[name="_.targetNodes"]:checked'))
            .map(function (input) { return input.value; }));
    }

    function notifyDependents() {
        dependencyValue.value = Array.from(checkedValues()).join(",");
        fireEvent(sourceSelect, "change");
    }

    function render() {
        var current = checkedValues();
        if (current.size === 0) current = saved;
        if (current.size === 0 && sourceSelect.value) current.add(sourceSelect.value);

        var options = Array.from(sourceSelect.options).filter(function (option) {
            return option.value && !option.value.startsWith("(");
        });
        var known = new Set(options.map(function (option) { return option.value; }));
        current.forEach(function (value) {
            if (!known.has(value)) {
                options.push({ value: value, textContent: value + " (unavailable)" });
            }
        });
        list.replaceChildren();
        if (options.length === 0) {
            status.textContent = "Agent nodes are unavailable until the API connection is configured.";
            return;
        }
        status.textContent = "";

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

            var label = document.createElement("label");
            label.htmlFor = input.id;
            label.textContent = option.textContent;
            input.addEventListener("change", function () {
                if (checkedValues().size === 0) {
                    input.checked = true;
                    status.textContent = "At least one Agent Node is required.";
                    return;
                }
                status.textContent = "";
                saved = checkedValues();
                root.dataset.selected = Array.from(saved).join(",");
                notifyDependents();
            });
            wrapper.append(input, label);
            list.appendChild(wrapper);
        });

        if (checkedValues().size === 0 && options.length > 0) {
            var first = list.querySelector('input[name="_.targetNodes"]');
            first.checked = true;
            saved = new Set([first.value]);
            root.dataset.selected = first.value;
        }
    }

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
    render();
});
