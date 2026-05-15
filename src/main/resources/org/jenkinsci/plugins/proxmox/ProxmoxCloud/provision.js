Behaviour.specify("[data-type='proxmox-provision']", 'proxmox-provision', -99, function(e) {
  e.addEventListener("click", function (event) {
    const form = document.getElementById(e.dataset.form);
    form.querySelector("[name='template']").value = e.dataset.template;
    buildFormTree(form);
    form.submit();
  });
});
