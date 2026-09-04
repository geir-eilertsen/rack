// Taking a photograph, shared by the pages that do it.
//
// The front page needs it because starting an add should open the camera on the
// tap that starts it, not on a second tap once a page has loaded — and a file
// cannot be carried across a navigation, so it goes in sessionStorage and is
// picked up on the other side.
(function () {
  const STASH = 'rack.pendingPhotos';

  // 1568px is the longest edge the vision model keeps; anything larger is
  // downsampled on arrival, so sending more is upload time for nothing.
  async function resize(file, maxDim = 1568, quality = 0.85) {
    const bitmap = await createImageBitmap(file);
    const scale = Math.min(1, maxDim / Math.max(bitmap.width, bitmap.height));
    if (scale >= 1 && file.size < 3_000_000) { bitmap.close(); return file; }
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(bitmap.width * scale);
    canvas.height = Math.round(bitmap.height * scale);
    canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    // The decoded camera frame is tens of megabytes and the camera app wants
    // that memory back for the next shot: release it now rather than when the
    // collector gets round to it, and the canvas the moment its JPEG exists.
    bitmap.close();
    const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/jpeg', quality));
    canvas.width = canvas.height = 0;
    return new File([blob], 'photo.jpg', { type: 'image/jpeg' });
  }

  function readAsDataUrl(file) {
    return new Promise((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result);
      reader.onerror = () => reject(reader.error);
      reader.readAsDataURL(file);
    });
  }

  async function toFile(dataUrl) {
    const blob = await (await fetch(dataUrl)).blob();
    return new File([blob], 'photo.jpg', { type: 'image/jpeg' });
  }

  /** Hands photographs to the next page. Already resized, so a batch is a few hundred kB. */
  async function stash(files) {
    const urls = [];
    for (const file of files) urls.push(await readAsDataUrl(await resize(file)));
    try {
      sessionStorage.setItem(STASH, JSON.stringify(urls));
      return true;
    } catch (e) {
      // Out of room, or storage refused. The photograph is not worth losing the
      // navigation over — the next page simply starts empty.
      return false;
    }
  }

  /** Takes them once. A reload must not re-add photographs already filed. */
  async function takeStash() {
    const raw = sessionStorage.getItem(STASH);
    if (!raw) return [];
    sessionStorage.removeItem(STASH);
    try {
      return await Promise.all(JSON.parse(raw).map(toFile));
    } catch (e) {
      return [];
    }
  }

  window.rackPhotos = { resize, stash, takeStash };
})();
