Behaviour.specify(".proxmox-ciUser", "proxmox-remoteFs-sync", 0, function(ciUserInput) {
    var pattern = /^\/home\/[^/]+\/agent$/;
    ciUserInput.addEventListener("input", function() {
        var block = ciUserInput.closest(".repeated-chunk") || ciUserInput.closest("table");
        if (!block) return;
        var osSelect = block.querySelector("select[name$='osType']");
        if (osSelect && osSelect.value === "WINDOWS") return;
        var remoteFsInput = block.querySelector(".proxmox-remoteFs");
        if (!remoteFsInput) return;
        var current = remoteFsInput.value;
        if (current === "" || pattern.test(current)) {
            var user = ciUserInput.value.trim() || "ubuntu";
            remoteFsInput.value = "/home/" + user + "/agent";
        }
    });
});
