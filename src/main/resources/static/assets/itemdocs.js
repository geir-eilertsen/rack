// Datasheets on an item, for the two pages that show items.
//
// put.html manages a drawer and find.html searches across them, but an expanded
// item row is the same row on both, so this is one copy rather than two that
// drift.
(function () {
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g,
      c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  function size(bytes) {
    if (bytes == null) return '';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024 * 1024) return Math.round(bytes / 1024) + ' kB';
    return (bytes / (1024 * 1024)).toFixed(1) + ' MB';
  }

  /**
   * The block for one item. `where` identifies the row so the handlers know what
   * they are acting on: {container, slot, index}.
   *
   * A part number is three millimetres wide and the pinout is not printed on it,
   * so this is the difference between a drawer that says what is in it and one
   * that says what you can do with it.
   */
  function render(item, where) {
    const docs = (item.documents || []);
    const key = where.container + '/' + where.slot + '/' + where.index;
    return '<div class="itemdocs" data-docs="' + esc(key) + '">'
      + '<h4>Files and links</h4>'
      + (docs.length
          ? '<ul class="dlist">' + docs.map(row).join('') + '</ul>'
          : '<p class="dnone">No datasheet yet.</p>')
      + '<label class="btn dadd">Add a file'
      + '<input type="file" hidden accept=".pdf,.png,.jpg,.jpeg,.webp,.txt,.md,image/*,application/pdf">'
      + '</label>'
      + '<button type="button" class="btn dlink">Add a link</button>'
      + '<span class="dstatus"></span>'
      // Held back until asked for: two more fields on every expanded item would
      // be in the way of the ones that get used.
      + '<div class="dlinkform" hidden>'
        + '<input class="durl" type="url" inputmode="url" placeholder="https://…" aria-label="Address">'
        + '<input class="dtitle" type="text" placeholder="What it is (optional)" aria-label="Title">'
        + '<button type="button" class="btn primary dlinkgo">Add</button>'
      + '</div>'
      + '</div>';
  }

  /**
   * A stored file opens from rack; a link goes wherever it points, which rack has
   * never seen. Marked as such rather than left to look identical, because one of
   * them still works when the other end of the internet does not.
   */
  function row(d) {
    const isLink = !!d.url;
    const href = isLink ? d.url : '/documents/' + encodeURIComponent(d.filename);
    const ref = isLink ? d.url : d.filename;
    return '<li><a href="' + esc(href) + '" target="_blank"'
      // noreferrer as well for a link: where this came from is nobody's business
      // but the person clicking it.
      + (isLink ? ' rel="noopener noreferrer"' : ' rel="noopener"')
      + ' onclick="event.stopPropagation()">' + esc(d.title) + '</a>'
      + (isLink ? '<span class="dlnk" title="' + esc(d.url) + '">link</span>'
                : (d.size ? '<span class="dsz">' + size(d.size) + '</span>' : ''))
      + '<button type="button" class="ddrop" title="Remove" data-drop="'
      + esc(ref) + '">×</button></li>';
  }

  /**
   * Wires every block inside `root`.
   *
   * <p>A block replaces itself from the slot the server returns, so neither page
   * needs a redraw strategy for this: put.html redraws its whole list from stored
   * state, find.html patches rows in place, and a shared piece that insisted on
   * either would be wrong on one of them. `onSaved` is for anything else the page
   * wants to do with the slot.
   */
  function wire(root, onSaved) {
    root.querySelectorAll('.itemdocs').forEach(block => wireBlock(block, onSaved));
  }

  function wireBlock(block, onSaved) {
    const [container, slot, index] = block.dataset.docs.split('/');
    const itemBase = '/c/' + encodeURIComponent(container) + '/' + encodeURIComponent(slot)
      + '/items/' + encodeURIComponent(index);
    const base = itemBase + '/documents';
    const linkBase = itemBase + '/links';
    const status = block.querySelector('.dstatus');

    const redraw = slotData => {
      const item = (slotData.items || [])[Number(index)];
      if (!item) return;
      block.outerHTML = render(item, { container, slot, index });
      // outerHTML replaced the node, so find the new one and wire that.
      const fresh = document.querySelector('.itemdocs[data-docs="' + cssEscape(block.dataset.docs) + '"]');
      if (fresh) wireBlock(fresh, onSaved);
      if (onSaved) onSaved(slotData);
    };

    const input = block.querySelector('.dadd input');
    input.addEventListener('click', e => e.stopPropagation());
    input.addEventListener('change', async () => {
      const file = input.files && input.files[0];
      input.value = '';
      if (!file) return;
      status.className = 'dstatus';
      status.textContent = 'Keeping ' + file.name + '…';
      const form = new FormData();
      form.append('document', file);
      await send(() => fetch(base, { method: 'POST', body: form }), status, redraw);
    });

    block.querySelectorAll('.ddrop').forEach(btn => btn.addEventListener('click', async e => {
      e.stopPropagation();
      if (!confirm('Remove this? A stored file goes unless something else keeps it too.')) return;
      status.className = 'dstatus';
      status.textContent = 'Removing…';
      await send(() => fetch(base + '?ref=' + encodeURIComponent(btn.dataset.drop),
        { method: 'DELETE' }), status, redraw);
    }));

    const form = block.querySelector('.dlinkform');
    const url = block.querySelector('.durl');
    block.querySelector('.dlink').addEventListener('click', e => {
      e.stopPropagation();
      form.hidden = !form.hidden;
      if (!form.hidden) url.focus();
    });
    form.addEventListener('click', e => e.stopPropagation());
    const addLink = async () => {
      const address = url.value.trim();
      if (!address) { url.focus(); return; }
      status.className = 'dstatus';
      status.textContent = 'Noting it…';
      await send(() => fetch(linkBase, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ url: address, title: block.querySelector('.dtitle').value.trim() })
      }), status, redraw);
    };
    block.querySelector('.dlinkgo').addEventListener('click', e => { e.stopPropagation(); addLink(); });
    form.querySelectorAll('input').forEach(inp => inp.addEventListener('keydown', e => {
      if (e.key === 'Enter') { e.preventDefault(); addLink(); }
    }));
  }

  /** A slot id can carry a hyphen; nothing here needs more than quoting. */
  function cssEscape(s) {
    return String(s).replace(/["\\]/g, '\\$&');
  }

  async function send(call, status, onDone) {
    try {
      const res = await call();
      if (!res.ok) throw new Error((await res.text()) || ('HTTP ' + res.status));
      status.textContent = '';
      onDone(await res.json());
    } catch (e) {
      status.className = 'dstatus err';
      status.textContent = e.message;
    }
  }

  window.rackItemDocs = { render, wire };
})();
