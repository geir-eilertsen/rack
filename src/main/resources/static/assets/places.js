// Where a thing is, in words.
//
// A container is keyed by a short id ("rack") and named by its owner
// ("Skuffereol"): the key belongs in URLs, the name belongs on screen. Now that
// a container also records where it physically is, there is a third answer —
// the room to walk to, which the index has never been able to give. "lab · 10"
// is precise and useless to somebody who does not already know where lab is.
//
// Shared rather than copied, because the search results and a project's
// checklist ask the same question and a copy each is how the 1568px resize
// helper drifted. put.html keeps its own list: it loads containers as part of a
// startup sequence that reports its own failure on screen, where this one
// answers "no containers" and lets the page carry on.
(function () {
  let pending = null;
  let containers = [];

  /** One fetch per page, however many callers want the answer. */
  function load() {
    if (!pending) {
      pending = fetch('/c')
        .then(r => (r.ok ? r.json() : []))
        .catch(() => [])
        .then(list => { containers = list || []; return containers; });
    }
    return pending;
  }

  const list = () => containers;
  const get = id => containers.find(c => c.id === id);
  const name = id => { const c = get(id); return c && c.name ? c.name : id; };
  const divided = c => !!(c && (c.slots || []).length > 1);

  // A container with one storage space is the whole answer; appending its lone
  // slot id would read as "Plastboks 1 · 1".
  function place(containerId, slot) {
    return divided(get(containerId)) ? name(containerId) + ' · ' + slot : name(containerId);
  }

  /** The room, not the drawer. Empty for a container nobody has placed. */
  function where(containerId) {
    const c = get(containerId);
    return c && c.location ? c.location : '';
  }

  /**
   * Every location already in use, so the next container goes in one of them by
   * being picked rather than by being typed again slightly differently — which
   * is the whole difference between two containers in "Garage" and one each in
   * "Garage" and "garage ". Built out of what has been typed, so it is still not
   * a list anybody has to maintain.
   *
   * Takes an explicit list for a page holding a fresher one than the cache.
   */
  function locations(from) {
    const seen = new Map();
    for (const c of from || containers) {
      const text = (c.location || '').trim();
      if (text && !seen.has(text.toLowerCase())) seen.set(text.toLowerCase(), text);
    }
    return [...seen.values()].sort((a, b) => a.localeCompare(b, undefined, { sensitivity: 'base' }));
  }

  window.rackPlaces = { load, list, get, name, divided, place, where, locations };
})();
