// The shape of a container's slot grid, worked out from the slot ids.
//
// A container stores a flat list of slots — the cols and rows given at
// registration are spent producing that list and not kept — so the grid was
// drawn five across for everything. That is right for the 5x12 rack it was
// written for and wrong for a 2x5 cupboard, which came out as A1 B1 A2 B2 A3 /
// B3 A4 B4 A5 B5: ten drawers in the correct order and the wrong shape.
//
// Nothing new is stored, because nothing needs to be: ContainerLayout emits
// each block row-major with one letter per column, so a row is a run of
// consecutive ids sharing a number and the ids already say how wide the thing
// is. Derived in the page rather than served, the same way a slot's frames are
// derived from its items.
//
// Rows are read one at a time, because a cabinet of mixed drawer sizes is
// genuinely ragged: a band of six small drawers over a pair of large ones is
// six across and then two across, and drawing that as one uniform width would
// be a picture of a cabinet nobody owns.
(function () {
  const GRID = /^([A-Za-z]+)(\d+)$/;
  // What a list with no shape to read gets, and the width the rack has always
  // had.
  const DEFAULT_COLUMNS = 5;
  // A ragged grid is laid on tracks every row divides into, so each row fills
  // the width exactly. The gaps between tracks are real pixels, though, and a
  // grid fine enough to divide 5 and 7 exactly is 35 tracks of mostly gap on a
  // phone. Past this the widest row sets the width and a narrower one leaves a
  // remainder, which is untidy where the alternative is unreadable.
  const MAX_TRACKS = 24;

  /**
   * The width of each row, or null where the ids have no shape to read. A row
   * is a maximal run of consecutive ids carrying the same number.
   */
  function rowWidths(ids) {
    const widths = [];
    let current = null;
    for (const id of ids) {
      const m = GRID.exec(id);
      if (!m) return null;                        // "top-left" — not a grid at all
      if (m[2] === current) widths[widths.length - 1]++;
      else { widths.push(1); current = m[2]; }
    }
    return widths;
  }

  function lcm(a, b) {
    let x = a;
    let y = b;
    while (y) { const t = x % y; x = y; y = t; }
    return (a / x) * b;
  }

  /**
   * How to lay the slots out: how many tracks the grid has, and — only where
   * the rows differ — how many tracks each slot takes and which slots start a
   * row.
   */
  function layout(slots) {
    const ids = (slots || []).filter(Boolean);
    if (!ids.length) return { tracks: DEFAULT_COLUMNS, cells: null };
    const fallback = { tracks: Math.min(ids.length, DEFAULT_COLUMNS), cells: null };
    const widths = rowWidths(ids);
    if (!widths) return fallback;
    // A reading where no row reaches two is a numbered run rather than a column
    // of one-wide rows: linear(4, "Box") gives Box1..Box4.
    if (widths.every(w => w < 2)) return fallback;
    if (widths.every(w => w === widths[0])) return { tracks: widths[0], cells: null };

    let tracks = widths.reduce(lcm, 1);
    if (tracks > MAX_TRACKS) tracks = Math.max.apply(null, widths);
    const cells = [];
    for (const w of widths) {
      const span = Math.max(1, Math.floor(tracks / w));
      for (let i = 0; i < w; i++) cells.push({ span: span, first: i === 0 });
    }
    return { tracks: tracks, cells: cells };
  }

  /** The uniform width the ids imply, for a caller that only wants a number. */
  function columns(slots) {
    return layout(slots).tracks;
  }

  /**
   * Draws `host`'s grid at the width its slot ids imply. Called after the slot
   * elements are in place, because a ragged shape is held by the cells rather
   * than by the grid: each row's first slot starts a new line, so a row that
   * does not divide the tracks evenly still gets a line of its own.
   */
  function shape(host, slots) {
    if (!host) return;
    const l = layout(slots);
    host.style.setProperty('--slot-cols', l.tracks);
    const kids = host.children;
    for (let i = 0; i < kids.length; i++) {
      const cell = l.cells && l.cells[i];
      kids[i].style.gridColumn = cell
        ? (cell.first ? '1 / span ' + cell.span : 'span ' + cell.span)
        : '';
    }
  }

  /**
   * Fills `host` with one element per slot, `make` building each, and shapes
   * the grid around them. One place owns the order of those two steps, because
   * the shaping reads the elements it is shaping.
   */
  function render(host, slots, make) {
    if (!host) return;
    host.innerHTML = '';
    (slots || []).forEach(sid => {
      const el = make(sid);
      if (el) host.appendChild(el);
    });
    shape(host, slots);
  }

  window.rackSlots = { columns, shape, render };
})();
