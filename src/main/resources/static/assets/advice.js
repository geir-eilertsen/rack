// Asking the model about a job, and drawing what comes back.
//
// Both the ask page and a project page do this. The difference is only what
// happens at the end — the ask page starts a new project, a project page folds
// the answer into the one already open — so the asking and the drawing live
// here and the two pages keep just their own last step.
(function () {
  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g,
      c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  // A checklist cites a drawer, which answers "which drawer" and not "which
  // room". Started as the file loads: the answer is wanted once a model call
  // has come back, which is seconds away at the earliest.
  rackPlaces.load();

  /** " — garage, north wall", or nothing for a container nobody has placed. */
  function where(containerId) {
    const text = rackPlaces.where(containerId);
    return text ? '<span class="where">' + esc(text) + '</span>' : '';
  }

  async function post(url, body) {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });
    if (!res.ok) throw new Error((await res.text()) || ('HTTP ' + res.status));
    return res.json();
  }

  const ask = question => post('/ask', { question });
  const plan = (project, needed) => post('/plan', { project, needed });

  /** The part plus the caveat, since a substitution changes what to order. */
  const shoppingLine = need => need.part + (need.note ? ' — ' + need.note : '');

  /** Everything the checklist could not find: what a plan is asked about. */
  const gaps = answer => (answer.checklist || []).filter(x => x.status !== 'have');

  function renderAnswer(a, opts) {
    const list = a.checklist || [];
    const n = s => list.filter(x => x.status === s).length;
    const counts = list.length
      ? '<div class="counts">'
        + '<span><b>' + n('have') + '</b> have</span>'
        + (n('partial') ? '<span><b>' + n('partial') + '</b> partial</span>' : '')
        + '<span><b>' + n('missing') + '</b> missing</span>'
        + '</div>'
      : '';
    return (a.summary
        ? '<div class="summary"><p>' + esc(a.summary) + '</p>'
          // Worth stating: a thing not listed as found is a thing the rack
          // really has not got, because nothing was sampled away first.
          + '<p class="considered">Read all ' + a.items_considered + ' items in the index.</p></div>'
        : '')
      + counts
      + (list.length
          ? '<ul class="needs">' + list.map(x => need(x, opts)).join('') + '</ul>'
          : '<p class="empty">Nothing to check against that.</p>');
  }

  function need(x, opts) {
    const s = ['have', 'partial', 'missing'].includes(x.status) ? x.status : 'missing';
    const found = x.found || [];
    return '<li class="need ' + s + '">'
      + '<div class="head">'
        + '<span class="part">' + esc(x.part || '') + '</span>'
        + '<span class="tag ' + s + '">' + s + '</span>'
      + '</div>'
      + (x.why ? '<p class="why">' + esc(x.why) + '</p>' : '')
      + (found.length
          ? '<ul class="found">' + found.map(f =>
              '<li><a href="' + rackRoute.slotHref(f.container, f.slot) + '">'
                + esc(f.container) + ' · ' + esc(f.slot) + '</a>'
              + where(f.container)
              + '<span class="what">' + esc(f.item || '')
              + (f.note ? ' — ' + esc(f.note) : '') + '</span></li>').join('')
            + '</ul>'
          : '')
      + (x.note ? '<p class="note">' + esc(x.note) + '</p>' : '')
      + '</li>';
  }

  function renderPlan(p) {
    const suppliers = p.suppliers || [];
    const steps = p.steps || [];
    const cautions = p.cautions || [];
    return (suppliers.length
        ? '<div class="section-head">What to buy, and where</div>' + suppliers.map(supplier).join('')
        : '')
      + (cautions.length
        ? '<div class="section-head">How to do it</div>'
          + '<div class="cautions"><h3>Before you start</h3><ul>'
          + cautions.map(c => '<li>' + esc(c) + '</li>').join('') + '</ul></div>'
        : (steps.length ? '<div class="section-head">How to do it</div>' : ''))
      + (steps.length ? '<ul class="steps">' + steps.map(step).join('') + '</ul>' : '');
  }

  function supplier(s) {
    const reach = ['local', 'national', 'international'].includes(s.reach) ? s.reach : 'national';
    const lines = s.items || [];
    // Pasteable, because the next thing you do with a list is put it somewhere.
    const plain = lines.map(l => (l.qty ? l.qty + '× ' : '') + l.part
      + (l.code ? '  [' + l.code + ']' : '') + (l.search ? '  (' + l.search + ')' : '')).join('\n');
    return '<div class="supplier">'
      + '<div class="top"><div class="who"><span class="nm">' + esc(s.name || '') + '</span>'
        + '<span class="reach ' + reach + '">' + reach + '</span></div>'
        + (s.why ? '<p class="why">' + esc(s.why) + '</p>' : '') + '</div>'
      + '<ul class="lines">' + lines.map(l =>
          '<li><span class="qty">' + esc(l.qty || '') + '</span><span class="body">'
          + '<span class="what">' + esc(l.part || '') + '</span>'
          + (l.code ? ' <span class="code">' + esc(l.code) + '</span>' : '')
          + (l.search ? '<br><span class="search">search: ' + esc(l.search) + '</span>' : '')
          + (l.note ? '<span class="lnote">' + esc(l.note) + '</span>' : '')
          + '</span></li>').join('') + '</ul>'
      + '<div class="foot">'
        + (s.ordering ? '<p class="ordering">' + esc(s.ordering) + '</p>' : '')
        + '<button class="btn" type="button" data-copy="' + esc(plain) + '">Copy this list</button>'
      + '</div></div>';
  }

  function step(s) {
    const uses = s.uses || [];
    return '<li><span class="title">' + esc(s.title || '') + '</span>'
      + (s.detail ? '<p class="detail">' + esc(s.detail) + '</p>' : '')
      + (uses.length
          ? '<ul class="uses">' + uses.map(u =>
              '<li>Uses <a href="' + rackRoute.slotHref(u.container, u.slot)
              + '">' + esc(u.container) + ' · ' + esc(u.slot)
              + '</a> — ' + esc(u.item || '') + where(u.container) + '</li>').join('') + '</ul>'
          : '')
      + '</li>';
  }

  /**
   * The parts to keep out of an answer and a plan.
   *
   * Taken from the plan's own supplier lines where there is a plan, and from the
   * checklist otherwise — never matched between the two by name. They describe
   * the same parts in different words, and guessing which line is which would put
   * the wrong order code beside the wrong part.
   */
  function partsFrom(answer, plan) {
    const checklist = (answer && answer.checklist) || [];
    const parts = [];
    for (const x of checklist.filter(n => n.status === 'have' || n.status === 'partial')) {
      parts.push({
        part: x.part, status: 'in_stock', note: x.note,
        from: (x.found || []).map(f => ({ container: f.container, slot: f.slot, item: f.item }))
      });
    }
    if (plan) {
      for (const s of plan.suppliers || []) {
        for (const l of s.items || []) {
          parts.push({ part: l.part, qty: l.qty, status: 'to_buy', supplier: s.name,
                       search: l.search, code: l.code, note: l.note, from: [] });
        }
      }
    } else {
      for (const x of checklist.filter(n => n.status === 'missing')) {
        parts.push({ part: x.part, status: 'to_buy', note: x.note, from: [] });
      }
    }
    return parts;
  }

  function stepsFrom(plan) {
    return ((plan && plan.steps) || []).map(s => ({
      title: s.title, detail: s.detail,
      uses: (s.uses || []).map(u => ({ container: u.container, slot: u.slot, item: u.item }))
    }));
  }

  // ---- keeping what came back ----------------------------------------------
  //
  // An answer is one model call over every item in the rack and a plan is a
  // couple of minutes and several cents. Both used to live in a variable and
  // nowhere else, so a refresh spent them again — and on a project page so did
  // ticking a step, because every save redraws the page from the server, and so
  // did following one of the answer's own drawer links and coming back.
  //
  // Kept in the browser rather than on the project, because this is a proposal
  // under review: taking it in is what makes it the project's, and a second copy
  // on the server would be the same parts written twice with nothing to say
  // which of them was meant. The cost is that it does not follow you to another
  // device — the same trade the pending photo strip already makes.
  const HELD = 'rack.advice.';

  function remember(key, held) {
    const row = JSON.stringify({ ...held, at: new Date().toISOString() });
    try {
      localStorage.setItem(HELD + key, row);
    } catch (e) {
      // Out of room. The answers for other jobs are the ones worth losing.
      sweep(HELD + key);
      try { localStorage.setItem(HELD + key, row); } catch (e2) { /* this page still has it */ }
    }
  }

  function recall(key) {
    try {
      const row = localStorage.getItem(HELD + key);
      return row ? JSON.parse(row) : null;
    } catch (e) {
      return null;   // storage refused, or half a row: ask again
    }
  }

  function forget(key) {
    try { localStorage.removeItem(HELD + key); } catch (e) { /* nothing to undo */ }
  }

  function sweep(keep) {
    try {
      for (const k of Object.keys(localStorage)) {
        if (k.startsWith(HELD) && k !== keep) localStorage.removeItem(k);
      }
    } catch (e) { /* nothing to sweep */ }
  }

  /** Minutes, because an answer from before lunch is not one from a moment ago. */
  function ago(iso) {
    const ms = Date.now() - new Date(iso).getTime();
    if (!(ms >= 0)) return 'just now';
    const mins = Math.floor(ms / 60000);
    if (mins < 1) return 'just now';
    if (mins < 60) return mins + (mins === 1 ? ' minute ago' : ' minutes ago');
    const hours = Math.floor(mins / 60);
    if (hours < 24) return hours + (hours === 1 ? ' hour ago' : ' hours ago');
    const days = Math.floor(hours / 24);
    return days === 1 ? 'yesterday' : days + ' days ago';
  }

  /**
   * When it was worked out, said on the answer itself.
   *
   * The only thing worse than losing an answer to a refresh is showing an old
   * one as though it were current: the drawers move underneath it, and this
   * answer's whole claim is that it read them.
   */
  function keptNote(at) {
    return at
      ? '<p class="kept">Worked out ' + esc(ago(at)) + ' and kept since — the drawers may have '
        + 'moved. Ask again for a fresh reading.</p>'
      : '';
  }

  // One listener for every copy button on the page, present and future.
  document.addEventListener('click', async e => {
    const btn = e.target.closest('[data-copy]');
    if (!btn) return;
    try {
      await navigator.clipboard.writeText(btn.dataset.copy);
      const was = btn.textContent;
      btn.textContent = 'Copied';
      setTimeout(() => { btn.textContent = was; }, 1400);
    } catch (err) {
      // Clipboard refused — no permission, or not a secure context. Showing the
      // text is the fallback that always works.
      const pre = document.createElement('pre');
      pre.textContent = btn.dataset.copy;
      pre.style.cssText = 'white-space:pre-wrap;font-size:.82rem;margin:.5rem 0 0';
      btn.replaceWith(pre);
    }
  });

  window.rackAdvice = {
    esc, ask, plan, gaps, shoppingLine, renderAnswer, renderPlan, partsFrom, stepsFrom,
    remember, recall, forget, ago, keptNote
  };
})();
