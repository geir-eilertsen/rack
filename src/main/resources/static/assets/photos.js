// Taking a photograph, shared by the pages that do it.
//
// A frame goes to the server the moment the camera hands it over, as it came.
// The page never decodes it: a phone's camera frame is tens of megapixels, and
// decoding it on the page to make the photograph the rack keeps was a 48MB
// bitmap on a device that wanted that memory back for the very next shot —
// phones reported low memory, froze, and killed the app while the camera was
// open. The server fits it to the size the vision model reads, keeps what the
// camera wrote about the shot, and hands back an id and a small copy for the
// strip. Filing then names the ids.
//
// Because the batch lives on the server, a page that was killed and relaunched
// finds it again by asking — which is also how a photograph taken from the
// front page reaches put.html: it is simply already there.
(function () {
  const PREVIEW = 264;

  /**
   * Uploads each file in turn and returns what the server made of them:
   * [{id, url, preview, container, slot, at}]. `where` is {container, slot}
   * or null; `onProgress(n, total)` is told before each upload. One request
   * per photograph rather than one for the batch, so a batch of five that
   * fails on the fourth keeps three.
   */
  async function stage(files, where, onProgress) {
    const staged = [];
    let n = 0;
    for (const file of files) {
      if (onProgress) onProgress(++n, files.length);
      const form = new FormData();
      form.append('photo', file, file.name || 'photo.jpg');
      if (where && where.container) form.append('c', where.container);
      if (where && where.slot) form.append('s', where.slot);
      const res = await fetch('/staging', { method: 'POST', body: form });
      if (!res.ok) throw new Error((await res.text()) || ('HTTP ' + res.status));
      staged.push(...await res.json());
    }
    return staged;
  }

  /** Every photograph waiting to be filed, oldest first. */
  async function list() {
    const res = await fetch('/staging');
    if (!res.ok) throw new Error('HTTP ' + res.status);
    return res.json();
  }

  /** Dropping one that has already gone is not an error. */
  async function drop(id) {
    try { await fetch('/staging/' + encodeURIComponent(id), { method: 'DELETE' }); } catch (e) { /* gone is gone */ }
  }

  function previewUrl(id) { return '/staging/' + encodeURIComponent(id) + '?w=' + PREVIEW; }
  function url(id) { return '/staging/' + encodeURIComponent(id); }

  window.rackPhotos = { stage, list, drop, previewUrl, url };
})();
