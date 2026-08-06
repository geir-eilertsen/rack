// The bits both project pages need: how a status reads, and how a project is
// summed up in one line.
(function () {
  const STATUS_WORDS = {
    planning: 'planning',
    shopping: 'shopping',
    building: 'building',
    done: 'done',
    parked: 'parked'
  };

  function esc(s) {
    return String(s == null ? '' : s).replace(/[&<>"']/g,
      c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  }

  function statusTag(status) {
    const s = STATUS_WORDS[status] ? status : 'planning';
    return '<span class="pstatus ' + s + '">' + STATUS_WORDS[s] + '</span>';
  }

  /** Counts, not prose — the card is scanned, not read. */
  function summary(p) {
    const bits = [];
    if (p.steps) bits.push(p.steps_done + ' of ' + p.steps + ' steps');
    if (p.still_to_buy) bits.push('<b>' + p.still_to_buy + '</b> still to buy');
    else if (p.parts) bits.push(p.parts + ' parts, all here');
    bits.push(when(p.finished_at || p.updated_at, p.finished_at ? 'finished' : 'touched'));
    return bits.join(' · ');
  }

  /** Relative, because "3 days ago" is the question and a timestamp is not. */
  function when(iso, verb) {
    if (!iso) return verb + ' — never';
    const days = Math.floor((Date.now() - new Date(iso).getTime()) / 86400000);
    if (days <= 0) return verb + ' today';
    if (days === 1) return verb + ' yesterday';
    return verb + ' ' + days + ' days ago';
  }

  window.rackProject = { esc, statusTag, summary, when };
})();
