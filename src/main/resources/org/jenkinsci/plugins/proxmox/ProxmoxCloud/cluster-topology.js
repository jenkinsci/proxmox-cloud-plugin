Behaviour.specify(
    ".proxmox-cluster-topology",
    "proxmox-cluster-topology",
    0,
    function (message) {
        var scope = message.closest(".repeated-chunk") || message.closest("form");
        if (!scope) return;

        var apiUrl = scope.querySelector('input[name="_.apiUrl"]');
        var credentials = scope.querySelector('select[name="_.credentialsId"]');
        var ignoreSslErrors = scope.querySelector('input[name="_.ignoreSslErrors"]');
        if (!apiUrl || !credentials || !ignoreSslErrors) return;

        var requestGeneration = 0;
        var refreshTimer = null;

        function showCloudMessage(state) {
            message.classList.remove(
                "jenkins-alert-info", "jenkins-alert-warning", "jenkins-alert-error"
            );
            if (state.state === "cluster" || state.state === "unconfigured") {
                message.hidden = true;
                message.textContent = "";
                return;
            }
            if (state.state === "standalone") {
                var node = state.nodes.length > 0 ? state.nodes[0].name : "the local node";
                message.classList.add("jenkins-alert-info");
                message.textContent = "Standalone Proxmox detected. Template and agent placement use "
                    + node + ".";
            } else {
                message.classList.add("jenkins-alert-warning");
                message.textContent = state.message ||
                    "Jenkins could not determine whether this Proxmox endpoint is standalone or clustered.";
            }
            message.hidden = false;
        }

        function currentSelectValue(select) {
            return select ? (select.value || select.getAttribute("value") || "") : "";
        }

        function setSelectValue(select, value) {
            if (!select) return;
            var changed = currentSelectValue(select) !== value;
            select.setAttribute("value", value);
            select.value = value;
            if (changed && select.value === value) fireEvent(select, "change");
        }

        function setLocationLocked(select, locked) {
            if (!select) return;
            // Jenkins' buildFormTree serializes disabled controls into the hidden JSON payload.
            // A separate submission mirror would therefore produce an invalid enum array.
            select.disabled = locked;
        }

        function setRowHidden(control, hidden) {
            var row = control && control.closest(".jenkins-form-item");
            if (row) row.hidden = hidden;
        }

        function selectedTargets(agentNodes) {
            if (!agentNodes) return [];
            var selected = agentNodes.dataset.selected || "";
            return selected.split(",").map(function (value) { return value.trim(); }).filter(Boolean);
        }

        function applyToTemplate(warning, state) {
            var chunk = warning.closest(".repeated-chunk");
            if (!chunk) return;
            var location = chunk.querySelector('select[name="templateLocation"]');
            var node = chunk.querySelector('select[name="_.node"]');
            var agentNodes = chunk.querySelector(".proxmox-agent-nodes");
            if (!location || !node || !agentNodes) return;

            var previous = warning._proxmoxPlacementBeforeStandalone;
            if (state.state !== "standalone" && previous) {
                setSelectValue(location, previous.location);
                setSelectValue(node, previous.node);
                warning._proxmoxPlacementBeforeStandalone = null;
            }

            setLocationLocked(location, state.state === "permission-missing");
            warning._proxmoxTopology = state;

            if (!node._proxmoxStandaloneFillListener) {
                node._proxmoxStandaloneFillListener = true;
                node.addEventListener("filled", function () {
                    var active = warning._proxmoxTopology;
                    if (active && active.state === "standalone" && active.nodes.length === 1) {
                        setSelectValue(node, active.nodes[0].name);
                    }
                });
            }

            if (state.state === "standalone" && state.nodes.length === 1) {
                var soleNode = state.nodes[0].name;
                var targets = selectedTargets(agentNodes);
                var locationValue = currentSelectValue(location);
                var nodeValue = currentSelectValue(node);
                var stale = (locationValue && locationValue !== "FIXED_NODE")
                    || (nodeValue && nodeValue !== soleNode)
                    || (targets.length > 0 && (targets.length !== 1 || targets[0] !== soleNode));

                if (!previous) {
                    warning._proxmoxPlacementBeforeStandalone = {
                        location: locationValue || "FIXED_NODE",
                        node: nodeValue
                    };
                }
                setSelectValue(location, "FIXED_NODE");
                setSelectValue(node, soleNode);
                setRowHidden(location, true);
                setRowHidden(node, true);
                setRowHidden(agentNodes, true);

                if (stale) {
                    warning.textContent = "This template has saved cluster placement, but the API "
                        + "endpoint is standalone. Saving will change it to Single source template on "
                        + soleNode + ".";
                    warning.hidden = false;
                } else {
                    warning.textContent = "";
                    warning.hidden = true;
                }
            } else {
                setRowHidden(location, false);
                setRowHidden(node, false);
                setRowHidden(agentNodes, false);
                // Template Node visibility within the full placement UI is controlled by
                // template-location-toggle.js.
                fireEvent(location, "change");
                warning.textContent = "";
                warning.hidden = true;
            }

            agentNodes.dispatchEvent(new CustomEvent("proxmox:cluster-topology", {
                detail: state
            }));
        }

        function applyState(state) {
            scope._proxmoxClusterTopology = state;
            showCloudMessage(state);
            scope.querySelectorAll(".proxmox-template-topology-warning").forEach(function (warning) {
                applyToTemplate(warning, state);
            });
        }

        function unavailable(messageText) {
            return { state: "unavailable", nodes: [], message: messageText };
        }

        function refresh() {
            var url = apiUrl.value.trim();
            var credentialsId = credentials.value;
            if (!url || !credentialsId) {
                requestGeneration += 1;
                applyState({ state: "unconfigured", nodes: [], message: "" });
                return;
            }

            var generation = ++requestGeneration;
            var params = new URLSearchParams();
            params.append("apiUrl", url);
            params.append("credentialsId", credentialsId);
            params.append("ignoreSslErrors", ignoreSslErrors.checked ? "true" : "false");
            fetch(rootURL + "/descriptor/org.jenkinsci.plugins.proxmox.ProxmoxCloud/clusterInventory?"
                    + params.toString(), {
                method: "POST",
                headers: crumb.wrap({})
            })
                .then(function (response) {
                    if (!response.ok) throw new Error("HTTP " + response.status);
                    return response.json();
                })
                .then(function (state) {
                    if (generation !== requestGeneration) return;
                    if (!state || typeof state.state !== "string" || !Array.isArray(state.nodes)) {
                        applyState(unavailable("Proxmox returned an invalid cluster-topology response."));
                        return;
                    }
                    applyState(state);
                })
                .catch(function (error) {
                    if (generation !== requestGeneration) return;
                    applyState(unavailable("Jenkins could not query Proxmox cluster topology: "
                        + error.message));
                });
        }

        function scheduleRefresh() {
            window.clearTimeout(refreshTimer);
            refreshTimer = window.setTimeout(refresh, 150);
        }

        [apiUrl, credentials, ignoreSslErrors].forEach(function (field) {
            field.addEventListener("change", scheduleRefresh);
            field.addEventListener("filled", scheduleRefresh);
        });

        new MutationObserver(function (mutations) {
            mutations.forEach(function (mutation) {
                mutation.addedNodes.forEach(function (added) {
                    if (!(added instanceof Element)) return;
                    var warnings = [];
                    if (added.matches(".proxmox-template-topology-warning")) warnings.push(added);
                    added.querySelectorAll(".proxmox-template-topology-warning").forEach(function (item) {
                        warnings.push(item);
                    });
                    warnings.forEach(function (warning) {
                        if (scope._proxmoxClusterTopology) {
                            applyToTemplate(warning, scope._proxmoxClusterTopology);
                        }
                    });
                });
            });
        }).observe(scope, { childList: true, subtree: true });

        scheduleRefresh();
    }
);
