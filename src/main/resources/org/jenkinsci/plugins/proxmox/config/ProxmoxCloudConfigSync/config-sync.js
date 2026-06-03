Behaviour.specify(".proxmox-sync-now", "proxmox-config-sync", 0, function(btn) {
    btn.addEventListener("click", function() {
        var spinner = document.getElementById('proxmox-sync-spinner');
        var resultDiv = document.getElementById('proxmox-sync-result');

        btn.disabled = true;
        spinner.style.display = 'inline';
        resultDiv.innerHTML = '';

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
            var html = '';
            if (data.success) {
                html = '<div style="color:green; font-weight:bold;">&#10003; ' + data.summary + '</div>';
            } else {
                html = '<div style="color:red; font-weight:bold;">&#10007; ' + data.summary + '</div>';
            }
            if (data.warnings && data.warnings.length > 0) {
                html += '<div style="color:orange; margin-top:4px;">Warnings: ' + data.warnings.join('; ') + '</div>';
            }
            resultDiv.innerHTML = html;
        })
        .catch(function(err) {
            resultDiv.innerHTML = '<div style="color:red;">Request failed: ' + err.message + '</div>';
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
