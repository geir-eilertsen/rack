// "Buy more of this", on an expanded item row — the same row on put.html and
// find.html, so one copy rather than two that drift.
//
// The entry is a copy of the item as it reads now, picture and part number
// included: the reason it is on the list is usually that the drawer ran out,
// and the list has to say which one to buy after the item is gone.
(function () {
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g,
      c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  // One fetch per page, shared by every panel: the stores already used, so
  // the second trip to Leroy goes under "Leroy" and not "leroy ".
  let stores = null;
  function knownStores() {
    if (!stores) {
      stores = fetch('/shopping').then(r => r.ok ? r.json() : []).catch(() => [])
        .then(list => storesOf(list));
    }
    return stores;
  }

  /** Distinct store names, most recently used first, spelled the way they were first typed. */
  function storesOf(list) {
    const seen = new Map();
    (list || []).slice().reverse().forEach(e => {
      if (!e.store) return;
      const k = e.store.trim().toLowerCase();
      if (!seen.has(k)) seen.set(k, e.store.trim());
    });
    return [...seen.values()];
  }

  let uid = 0;

  /** The closed panel for one item row; `where` is {container, slot, index}. */
  function render(where) {
    const key = where.container + '/' + where.slot + '/' + where.index;
    const list = 'shop-stores-' + (++uid);
    return '<details class="shop-panel" data-shop="' + esc(key) + '">'
      + '<summary>Add to shopping list…</summary>'
      + '<div class="shop-body">'
      +   '<p class="shop-hint">Keeps this item\'s photo and part number on the list, so you buy the right one.</p>'
      +   '<div class="shop-row">'
      +     '<input class="shop-store" type="text" placeholder="Store (e.g. Leroy)" list="' + list + '" autocomplete="off">'
      +     '<input class="shop-qty" type="text" placeholder="How many">'
      +   '</div>'
      +   '<datalist id="' + list + '"></datalist>'
      +   '<input class="shop-note" type="text" placeholder="Anything to remember? (e.g. the 6mm, not 8)">'
      +   '<label class="shop-remember" hidden><input type="checkbox" checked> '
      +     'Remember that <b class="shop-brand"></b> is sold at this store</label>'
      +   '<button type="button" class="btn primary shop-add">Add to list</button>'
      +   '<p class="shop-status"></p>'
      + '</div>'
      + '</details>';
  }

  function wire(root) {
    root.querySelectorAll('.shop-panel').forEach(wirePanel);
  }

  function wirePanel(panel) {
    if (panel.dataset.wired) return;
    panel.dataset.wired = '1';
    const [container, slot, index] = panel.dataset.shop.split('/');
    const store = panel.querySelector('.shop-store');
    const qty = panel.querySelector('.shop-qty');
    const note = panel.querySelector('.shop-note');
    const add = panel.querySelector('.shop-add');
    const status = panel.querySelector('.shop-status');
    const datalist = panel.querySelector('datalist');
    const remember = panel.querySelector('.shop-remember');

    panel.addEventListener('click', ev => ev.stopPropagation());
    panel.addEventListener('keydown', ev => ev.stopPropagation());
    const hint = panel.querySelector('.shop-hint');
    let asked = false;
    panel.addEventListener('toggle', () => {
      if (!panel.open) return;
      knownStores().then(names => {
        datalist.innerHTML = names.map(n => '<option value="' + esc(n) + '">').join('');
        if (!store.value && names.length) store.placeholder = 'Store (e.g. ' + names[0] + ')';
      });
      if (asked) return;
      asked = true;
      // The item usually says its brand, and a house brand says its shop:
      // Anslut is Jula's. Filled in, not forced — the box stays editable.
      fetch('/c/' + encodeURIComponent(container) + '/' + encodeURIComponent(slot)
          + '/items/' + encodeURIComponent(index) + '/shopping')
        .then(r => r.ok ? r.json() : null).catch(() => null)
        .then(h => {
          if (!h) return;
          // A brand nobody has said a shop for yet: once the person names
          // the shop, offer to remember it, so the next one fills itself in.
          if (h.unplaced_brand) {
            remember.querySelector('.shop-brand').textContent = h.unplaced_brand;
            remember.hidden = false;
          }
          if (!h.store || store.value) return;
          store.value = h.store;
          hint.textContent = 'Store filled in because ' + h.because + '. Keeps this item\'s photo and part number on the list.';
        });
    });

    add.addEventListener('click', async ev => {
      ev.stopPropagation();
      add.disabled = true;
      status.className = 'shop-status';
      status.textContent = 'Adding…';
      try {
        const res = await fetch('/c/' + encodeURIComponent(container) + '/' + encodeURIComponent(slot)
          + '/items/' + encodeURIComponent(index) + '/shopping', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            store: store.value, qty: qty.value, note: note.value,
            remember_brand: !remember.hidden && remember.querySelector('input').checked
          })
        });
        if (!res.ok) throw new Error((await res.json().catch(() => ({}))).message || res.statusText);
        const entry = await res.json();
        stores = null;                       // a new store name is now a known one
        status.innerHTML = 'On the list' + (entry.store ? ' for <b>' + esc(entry.store) + '</b>' : '')
          + '. <a href="/shopping.html' + (entry.store ? '#store=' + encodeURIComponent(entry.store) : '') + '">Open list</a>';
        if (!remember.hidden && store.value.trim() && remember.querySelector('input').checked) {
          status.innerHTML += ' · Remembered ' + esc(remember.querySelector('.shop-brand').textContent)
            + ' → ' + esc(store.value.trim()) + '.';
          remember.hidden = true;            // the rule exists now
        }
        note.value = '';
        qty.value = '';
      } catch (e) {
        status.className = 'shop-status error';
        status.textContent = 'Could not add: ' + e.message;
      } finally {
        add.disabled = false;
      }
    });
  }

  /**
   * A position typed or pasted by hand — for a shop you are not standing in,
   * such as the Leroy in Spain before the trip. Takes "36.72, -4.42" and the
   * links Google Maps shares: a dropped pin's "!3d…!4d…" first, because "@…"
   * is only where the map was centred. Null when there is nothing to read.
   */
  function parsePlace(text) {
    const t = String(text || '').trim();
    if (!t) return null;
    const num = '(-?\\d{1,3}(?:\\.\\d+)?)';
    const tries = [
      new RegExp('!3d' + num + '!4d' + num),
      new RegExp('[?&](?:q|query|ll|center|destination)=' + num + '(?:,|%2C)\\s*' + num, 'i'),
      new RegExp('@' + num + ',' + num),
      new RegExp('^' + num + '\\s*[,;\\s]\\s*' + num + '$')
    ];
    for (const re of tries) {
      const m = t.match(re);
      if (!m) continue;
      const lat = parseFloat(m[1]), lon = parseFloat(m[2]);
      if (Math.abs(lat) <= 90 && Math.abs(lon) <= 180) return { lat, lon };
    }
    return null;
  }

  window.rackShopping = { render, wire, storesOf, parsePlace };
})();
