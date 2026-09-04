// What an item goes with, and where that is — a phone's charger, a remote's
// receiver — for the two pages that show items.
//
// put.html manages a drawer and find.html searches across them, but an
// expanded item row is the same row on both, so this is one copy rather than
// two that drift. Fetched when the panel is opened rather than for every row
// on every draw: it is a model call, small but not free, and most things have
// no other half. The counterpart's own slot is reported as already here rather
// than offered as a move, because the question is what to bring together and
// that already is.
(function () {
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g,
      c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  /** The closed panel for one item row; `where` is {container, slot, index}. */
  function render(where) {
    const key = where.container + '/' + where.slot + '/' + where.index;
    return '<details class="pair-panel" data-pair="' + esc(key) + '">'
      + '<summary>What does this go with?</summary>'
      + '<div class="pair-body"></div>'
      + '</details>';
  }

  /**
   * Wire every panel under `root`. The page supplies what only it knows:
   *   place(container, slot) — the label for a drawer
   *   room(container)        — where the container is, or ''
   *   itemName(item)         — the title line for an item
   *   moved(container, slot) — an item has moved there; redraw and say so
   *   failed(message)        — a move did not happen
   */
  function wire(root, page) {
    root.querySelectorAll('.pair-panel').forEach(panel => wirePanel(panel, page));
  }

  function wirePanel(panel, page) {
    const [container, slot, index] = panel.dataset.pair.split('/');
    const host = panel.querySelector('.pair-body');
    panel.addEventListener('click', ev => ev.stopPropagation());
    panel.addEventListener('toggle', async () => {
      if (!panel.open || host.children.length) return;
      host.innerHTML = '<p class="pair-hint">Looking…</p>';
      let result = null;
      try {
        const res = await fetch('/c/' + encodeURIComponent(container) + '/' + encodeURIComponent(slot)
          + '/items/' + encodeURIComponent(index) + '/companions');
        if (res.ok) result = await res.json();
      } catch (e) { /* handled below */ }
      if (window.rackUsage) rackUsage.refresh();
      if (!result) {
        host.innerHTML = '<p class="pair-hint">Could not look this up.</p>';
        return;
      }
      host.innerHTML = body(result, page);
      host.querySelectorAll('.pair-row').forEach(row => {
        const { c, s, i } = row.dataset;
        row.querySelector('.there').addEventListener('click', ev => {
          ev.stopPropagation();
          move(container, slot, index, c, s, ev.currentTarget, page);
        });
        row.querySelector('.here').addEventListener('click', ev => {
          ev.stopPropagation();
          move(c, s, i, container, slot, ev.currentTarget, page);
        });
      });
    });
  }

  function body(result, page) {
    const terms = (result.terms || []).map(t => '“' + esc(t) + '”').join(', ');
    const found = result.hits || [];
    // What is already beside it is the pair working, and worth saying: the
    // camera is in this drawer with its charger, and "nothing anywhere else"
    // would be true and hide that.
    const together = (result.together || []).map(h => esc(page.itemName(h.item)));
    const alreadyHere = together.length
      ? '<p class="pair-hint">Already here: ' + together.join('; ') + '.</p>'
      : '';
    if (!found.length) {
      return alreadyHere + '<p class="pair-hint">'
        + (terms ? 'Looked for ' + terms + ' and found nothing anywhere else.' : 'Nothing this is known to go with.')
        + '</p>';
    }
    return alreadyHere
      + '<p class="pair-hint">Looked for ' + terms + '. Which half moves is your call.</p>'
      + found.map(h => {
          const room = page.room(h.container);
          return '<div class="pair-row" data-c="' + esc(h.container) + '" data-s="' + esc(h.slot) + '" data-i="' + h.index + '">'
            + '<div class="name">' + esc(page.itemName(h.item)) + '</div>'
            + '<div class="at">' + esc(page.place(h.container, h.slot)) + (room ? ' — ' + esc(room) : '') + '</div>'
            + '<div class="acts">'
              + '<button type="button" class="btn there">Move this there</button>'
              + '<button type="button" class="btn here">Bring it here</button>'
            + '</div>'
          + '</div>';
        }).join('');
  }

  async function move(srcContainer, srcSlot, idx, dstContainer, dstSlot, btn, page) {
    const original = btn.textContent;
    btn.disabled = true;
    btn.textContent = 'Moving…';
    try {
      const res = await fetch('/c/' + encodeURIComponent(srcContainer) + '/' + encodeURIComponent(srcSlot)
        + '/items/' + encodeURIComponent(idx) + '/move', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ target_container: dstContainer, target_slot: dstSlot })
      });
      if (!res.ok) throw new Error((await res.text()) || ('HTTP ' + res.status));
      page.moved(dstContainer, dstSlot);
    } catch (e) {
      btn.disabled = false;
      btn.textContent = original;
      page.failed(e.message);
    }
  }

  window.rackCompanions = { render, wire };
})();
