// The shape of a container's slot grid, worked out from the slot ids.
//
// A container stores a flat list of slots — the cols and rows given at
// registration are spent producing that list and not kept — so the grid was
// drawn five across for everything. That is right for the 5x12 rack it was
// written for and wrong for a 2x5 cupboard, which came out as A1 B1 A2 B2 A3 /
// B3 A4 B4 A5 B5: ten drawers in the correct order and the wrong shape.
//
// Nothing new is stored, because nothing needs to be: ContainerLayout.grid
// emits row-major, one letter per column, so the ids already say how wide the
// thing is. Derived in the page rather than served, the same way a slot's
// frames are derived from its items.
(function () {
  const GRID = /^([A-Za-z]+)(\d+)$/;
  // What a list with no shape to read gets, and the width the rack has always
  // had.
  const DEFAULT_COLUMNS = 5;

  function columns(slots) {
    const ids = (slots || []).filter(Boolean);
    if (!ids.length) return DEFAULT_COLUMNS;
    const fallback = Math.min(ids.length, DEFAULT_COLUMNS);
    const rows = new Map();
    for (const id of ids) {
      const m = GRID.exec(id);
      if (!m) return fallback;                       // "top-left", or a linear 1..11
      rows.set(m[2], (rows.get(m[2]) || 0) + 1);
    }
    const widths = [...rows.values()];
    const width = widths[0];
    if (!widths.every(w => w === width)) return fallback;   // ragged: not a grid
    // A one-wide reading is unreliable rather than tall: linear(4, "Box")
    // gives Box1..Box4, which is a numbered run and not a column of four.
    return width < 2 ? fallback : width;
  }

  /** Draws `host`'s grid at the width its slot ids imply. */
  function shape(host, slots) {
    if (host) host.style.setProperty('--slot-cols', columns(slots));
  }

  window.rackSlots = { columns, shape };
})();
