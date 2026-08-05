// What the rack has spent on model calls, at the foot of every page.
//
// The tally is server-side and cumulative, so this is only ever a read. Pages
// that make a model call (filing a slot, searching by photo, asking about an
// item) call rackUsage.refresh() afterwards so the number moves while you watch;
// everything else picks it up on the next load or when the tab regains focus.
(function () {
  const foot = document.createElement('footer');
  foot.className = 'usage';
  foot.hidden = true;

  function count(n) {
    if (n >= 1e6) return (n / 1e6).toFixed(n < 1e7 ? 2 : 1).replace(/\.0+$/, '') + 'M';
    if (n >= 1e3) return (n / 1e3).toFixed(n < 1e4 ? 1 : 0).replace(/\.0$/, '') + 'k';
    return String(n);
  }

  // Keep the small numbers legible rather than rounding a real cost to $0.00.
  function money(d) {
    if (d >= 1) return '$' + d.toFixed(2);
    if (d >= 0.01) return '$' + d.toFixed(2);
    if (d > 0) return '$' + d.toFixed(4);
    return '$0.00';
  }

  function render(u) {
    if (!u || !u.calls) {
      foot.hidden = true;
      document.body.classList.remove('has-usage');
      return;
    }
    const perModel = (u.models || [])
      .map(m => m.model + ': ' + count(m.input_tokens) + ' in, ' + count(m.output_tokens) + ' out'
        + ' over ' + m.calls + ' call' + (m.calls === 1 ? '' : 's')
        + ' — ' + (m.cost == null ? 'no price configured' : money(m.cost)))
      .join('\n');
    // "at least" when a model has no configured price: its tokens are counted
    // but its cost isn't, and a short total should not read as the whole bill.
    foot.textContent = (u.all_priced ? '' : 'at least ') + money(u.cost)
      + ' · ' + count(u.input_tokens) + ' in · ' + count(u.output_tokens) + ' out · '
      + u.calls + ' call' + (u.calls === 1 ? '' : 's');
    foot.title = perModel;          // the per-model split, for the curious
    foot.hidden = false;
    document.body.classList.add('has-usage');
  }

  async function refresh() {
    try {
      const res = await fetch('/usage');
      if (res.ok) render(await res.json());
    } catch (e) { /* a tally is never worth an error on screen */ }
  }

  function mount() {
    document.body.appendChild(foot);
    refresh();
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', mount);
  else mount();
  window.addEventListener('focus', refresh);

  window.rackUsage = { refresh };
})();
