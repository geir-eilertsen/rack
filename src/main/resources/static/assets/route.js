// Where you are, in the address bar.
//
// Every page used to read its state once, out of a query string, and never
// mention it again: pick a different drawer and the address still named the one
// you arrived at, so Back left the page entirely, a reload put you somewhere
// else, and a search was a thing you could not send anybody. The state was real
// and only the address was wrong.
//
// The hash is the right half of the URL for it, because it never reaches the
// server — these are static pages and there is no router behind them. Each page
// keeps its own shape in it (put.html#rack/A1, find.html#q=BC547) rather than
// one router owning them all.
//
// The query form stays readable forever. Every drawer in this house has a
// printed QR sticker on it encoding put.html?c=&s=, and stickers cannot be
// reissued because the code changed — so a page reads a query when it finds
// one and rewrites it into the hash.
(function () {
  // The hash this page wrote itself. Writing the address is the *result* of
  // something the page has already drawn, so the event that write causes has
  // nothing left to do — where a Back button's event has everything to do.
  let ours = null;

  function raw() {
    return location.hash.replace(/^#/, '');
  }

  function decode(part) {
    try { return decodeURIComponent(part); } catch (e) { return part; }   // hand-typed %
  }

  /** The hash read as a path: `#rack/A1` is `['rack', 'A1']`. */
  function path() {
    const value = raw();
    if (!value || value.indexOf('=') >= 0) return [];    // a k=v hash is not a path
    return value.split('/').filter(Boolean).map(decode);
  }

  /** The hash read as a query: `#q=BC547`. */
  function params() {
    return new URLSearchParams(raw());
  }

  /** Builds a path hash, dropping the parts that are not there. */
  function join(parts) {
    return (parts || [])
      .filter(p => p !== null && p !== undefined && p !== '')
      .map(encodeURIComponent)
      .join('/');
  }

  /**
   * Writes the address. `replace` rewrites the entry instead of adding one —
   * for state the user did not deliberately move to: a keystroke in a search
   * box, or the page restoring what the address already said.
   */
  function go(hash, options) {
    const next = hash ? '#' + hash : '';
    if ((location.hash || '') === next) return;
    if (!next || (options && options.replace)) {
      // Assigning '' leaves a bare '#' behind, and a replace fires no event.
      history.replaceState(null, '', location.pathname + location.search + next);
      return;
    }
    ours = next;
    location.hash = next;
  }

  /** Runs `fn` when the address changes under the page — Back, Forward, an edited URL. */
  function onChange(fn) {
    window.addEventListener('hashchange', () => {
      const mine = location.hash === ours;
      ours = null;
      if (!mine) fn();
    });
  }

  /** Takes `?c=&s=` back out once it has been read, so the address says one thing. */
  function dropQuery() {
    if (!location.search) return;
    history.replaceState(null, '', location.pathname + location.hash);
  }

  /** The one place that knows what a drawer link looks like. */
  function slotHref(container, slot) {
    return '/put.html#' + join([container, slot]);
  }

  function projectHref(id) {
    return '/project.html#' + join([id]);
  }

  /** The pair a drawer link carries, in either form — old answers hold old links. */
  function readSlotHref(href) {
    const value = href || '';
    const hash = value.split('#')[1];
    if (hash) {
      const parts = hash.split('/').filter(Boolean).map(decode);
      return { container: parts[0] || null, slot: parts[1] || null };
    }
    const query = new URLSearchParams(value.split('?')[1] || '');
    return { container: query.get('c'), slot: query.get('s') };
  }

  window.rackRoute = { path, params, join, go, onChange, dropQuery, slotHref, projectHref, readSlotHref };
})();
