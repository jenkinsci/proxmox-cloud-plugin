// Copy Template: adds a "Copy Template" button next to the core-generated "Add Template" button.
// Clicking it shows a menu of the cloud's existing template names; choosing one appends a new
// template row pre-filled with that template's current on-form values, with Name left blank.
//
// The copy reuses Jenkins core's repeatable "expand" (container.tag.expand()) to add a blank row,
// then copies field values across. The API-populated selects (node, template VM id, storage, pool,
// network bridge) and the SSH credentials select fill asynchronously, so for those we set the
// element's "value" attribute as well as .value: core's select.js restores the selection from that
// attribute once the fill completes. That is the same path that restores a saved template on page
// load, so it also drives the node -> dependent-select refill cascade.
Behaviour.specify(
    ".proxmox-copy-template-control",
    "proxmox-copy-template",
    0,
    function (control) {
        var scope = control.closest(".repeated-chunk") || control.closest("form");
        if (!scope) {
            return;
        }
        // The templates repeatableProperty is the only repeatable in a cloud's config, so this
        // resolves to this cloud's templates list on both the per-cloud configure page and the
        // legacy multi-cloud (hetero-list) layout.
        var container = scope.querySelector(".repeated-container");
        var button = control.querySelector(".proxmox-copy-template-button");
        if (!container || !button) {
            return;
        }

        var menu = null;

        function templateChunks() {
            return Array.prototype.filter.call(container.children, function (n) {
                return n.classList && n.classList.contains("repeated-chunk");
            });
        }

        function closeMenu() {
            if (menu) {
                menu.remove();
                menu = null;
                document.removeEventListener("click", onDocumentClick, true);
            }
        }

        function onDocumentClick(event) {
            if (!control.contains(event.target)) {
                closeMenu();
            }
        }

        function refreshState() {
            button.disabled = templateChunks().length === 0;
            if (button.disabled) {
                closeMenu();
            }
        }

        function openMenu() {
            closeMenu();
            var chunks = templateChunks();
            if (chunks.length === 0) {
                return;
            }
            menu = document.createElement("div");
            menu.className = "jenkins-dropdown";
            menu.style.position = "absolute";
            menu.style.top = "100%";
            menu.style.left = "0";
            menu.style.zIndex = "1000";
            menu.style.marginTop = "4px";
            menu.style.maxHeight = "320px";
            menu.style.overflowY = "auto";
            menu.style.background = "var(--background)";
            menu.style.border = "var(--jenkins-border--subtle)";
            menu.style.borderRadius = "var(--form-input-border-radius)";
            menu.style.boxShadow = "var(--dropdown-box-shadow)";

            chunks.forEach(function (chunk) {
                var nameInput = chunk.querySelector('input[name="_.name"]');
                var name = nameInput && nameInput.value.trim();
                var item = document.createElement("button");
                item.type = "button";
                item.className = "jenkins-dropdown__item";
                item.textContent = name || "(unnamed)";
                item.addEventListener("click", function () {
                    closeMenu();
                    copyTemplate(chunk);
                });
                menu.appendChild(item);
            });

            control.appendChild(menu);
            // Defer so the click that opened the menu doesn't immediately close it.
            window.setTimeout(function () {
                document.addEventListener("click", onDocumentClick, true);
            }, 0);
        }

        function copyTemplate(sourceChunk) {
            var support = container.tag;
            if (!support || !support.insertionPoint) {
                return;
            }
            // Build the new row from the source row's HTML (mirroring core repeatableSupport.expand(),
            // but cloning the source instead of the blank master) and set every value BEFORE Behaviour
            // init runs. The API-populated selects then already carry their fetched <option>s and
            // selected values when the field validators first fire, so there is no empty-value check
            // racing the async fill (which previously left a stale "required" error on, e.g., the node).
            var nc = document.createElement("div");
            nc.className = "repeated-chunk";
            nc.setAttribute("name", support.name);
            nc.innerHTML = sourceChunk.innerHTML;

            copyFieldValues(sourceChunk, nc);

            support.insertionPoint.parentNode.insertBefore(nc, support.insertionPoint);
            if (support.withDragDrop && typeof registerSortableDragDrop === "function") {
                registerSortableDragDrop(nc);
            }
            Behaviour.applySubtree(nc, true);
            if (typeof support.update === "function") {
                support.update();
            }

            var nameInput = nc.querySelector('input[name="_.name"]');
            if (nameInput) {
                nameInput.focus();
            }
        }

        // Copy the source row's current field values into the new row. innerHTML carries each field's
        // attributes but not the user's live input/select state, so transfer .value/.checked here.
        // Runs before applySubtree so validators see the final values on their first check.
        function copyFieldValues(src, dst) {
            var fields = src.querySelectorAll("input, select, textarea");
            for (var i = 0; i < fields.length; i++) {
                var sf = fields[i];
                var name = sf.getAttribute("name");
                if (!name) {
                    continue;
                }
                var tag = sf.tagName.toLowerCase();
                var type = (sf.getAttribute("type") || "").toLowerCase();
                if (name === "_.name") {
                    var dn = dst.querySelector('input[name="_.name"]');
                    if (dn) {
                        dn.value = "";
                        dn.removeAttribute("value"); // leave the new template's Name blank
                    }
                    continue;
                }
                if (tag === "input" && (type === "checkbox" || type === "radio")) {
                    var group = dst.querySelectorAll('input[name="' + name + '"]');
                    var match = null;
                    if (type === "radio") {
                        for (var r = 0; r < group.length; r++) {
                            if (group[r].value === sf.value) {
                                match = group[r];
                                break;
                            }
                        }
                    } else {
                        match = group[0];
                    }
                    if (match) {
                        match.checked = sf.checked;
                    }
                    continue;
                }
                var df = dst.querySelector(tag + '[name="' + name + '"]');
                if (!df) {
                    continue;
                }
                df.value = sf.value;
                if (tag === "select" && sf.getAttribute("fillUrl")) {
                    // The cloned <option>s let df.value stick now; the value attribute restores the
                    // selection after select.js re-fetches the options on init.
                    df.setAttribute("value", sf.value);
                }
            }
        }

        // Place the control next to the "Add Template" button and reveal it, vertically aligned
        // with it. The add button stays a direct child of the container (core's repeatable.js
        // update() looks it up there), so we only insert alongside it, never move it.
        var addButton = container.querySelector(".repeatable-add");
        control.style.display = "inline-block";
        control.style.verticalAlign = "bottom";
        control.style.marginLeft = "0.5rem";
        if (addButton) {
            addButton.style.verticalAlign = "bottom";
            addButton.insertAdjacentElement("afterend", control);
        }

        button.addEventListener("click", function () {
            if (menu) {
                closeMenu();
            } else {
                openMenu();
            }
        });

        // Keep the disabled state in sync as templates are added/removed via core Add/Delete.
        new MutationObserver(refreshState).observe(container, { childList: true });
        refreshState();
    }
);
