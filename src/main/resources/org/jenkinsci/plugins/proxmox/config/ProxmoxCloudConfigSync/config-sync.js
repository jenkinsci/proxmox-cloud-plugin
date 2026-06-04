Behaviour.specify(".proxmox-sync-now", "proxmox-config-sync", 0, function(btn) {
    btn.addEventListener("click", function() {
        var spinner = document.getElementById('proxmox-sync-spinner');

        btn.disabled = true;
        spinner.style.display = 'inline';

        var descriptorUrl = document.querySelector('[data-descriptor-url*="ProxmoxCloudConfigSync"]');
        var baseUrl = (descriptorUrl ? descriptorUrl.getAttribute('data-descriptor-url') : '')
                      || rootURL + '/descriptor/org.jenkinsci.plugins.proxmox.config.ProxmoxCloudConfigSync';

        var form = btn.closest('form');
        var params = new URLSearchParams();
        params.append('gitUrl', proxmoxFindField(form, 'gitUrl'));
        params.append('gitCredentialsId', proxmoxFindField(form, 'gitCredentialsId'));
        params.append('gitBranch', proxmoxFindField(form, 'gitBranch'));
        params.append('yamlFilePath', proxmoxFindField(form, 'yamlFilePath'));

        fetch(baseUrl + '/runSync?' + params.toString(), {
            method: 'POST',
            headers: crumb.wrap({})
        })
        .then(function(response) { return response.json(); })
        .then(function(data) {
            var hasWarnings = data.warnings && data.warnings.length > 0;
            var message = data.summary;
            if (hasWarnings) {
                message += ' (Warnings: ' + data.warnings.join('; ') + ')';
            }
            if (!data.success) {
                notificationBar.show(message, notificationBar.ERROR);
            } else if (hasWarnings) {
                notificationBar.show(message, notificationBar.WARNING);
            } else {
                notificationBar.show(message, notificationBar.SUCCESS);
            }
        })
        .catch(function(err) {
            notificationBar.show('Sync request failed: ' + err.message, notificationBar.ERROR);
        })
        .finally(function() {
            btn.disabled = false;
            spinner.style.display = 'none';
        });
    });
});

function proxmoxFindField(form, fieldName) {
    var input = form.querySelector('input[name="_.'+fieldName+'"]')
             || form.querySelector('select[name="_.'+fieldName+'"]');
    return input ? input.value : '';
}
