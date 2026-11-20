// The brands the rack already knows, offered in every brand box.
//
// One <datalist id="rack-brands"> per page, filled from GET /brands; any input
// with list="rack-brands" picks it up, including ones drawn later. refresh()
// after saving, so a brand typed for the first time is offered next time.
(function () {
  function datalist() {
    let el = document.getElementById('rack-brands');
    if (!el) {
      el = document.createElement('datalist');
      el.id = 'rack-brands';
      document.body.appendChild(el);
    }
    return el;
  }

  function refresh() {
    return fetch('/brands').then(r => r.ok ? r.json() : []).catch(() => [])
      .then(names => {
        const el = datalist();
        el.textContent = '';
        names.forEach(n => {
          const o = document.createElement('option');
          o.value = n;
          el.appendChild(o);
        });
        return names;
      });
  }

  window.rackBrands = { refresh };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', refresh);
  else refresh();
})();
