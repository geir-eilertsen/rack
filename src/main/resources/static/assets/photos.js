// Taking a photograph, shared by the pages that do it.
//
// The front page needs it because starting an add should open the camera on the
// tap that starts it, not on a second tap once a page has loaded — and a file
// cannot be carried across a navigation, so it goes in sessionStorage and is
// picked up on the other side.
(function () {
  const STASH = 'rack.pendingPhotos';

  // Reads a JPEG's pixel size off its header. A browser can decode a JPEG at
  // a fraction of its size and never hold the whole frame, but only if told
  // what size it wants before it starts — and it cannot be asked a picture's
  // size without decoding it. Null for anything that is not a JPEG, which is
  // then decoded as it comes.
  async function jpegSize(file) {
    const head = new DataView(await file.slice(0, 1 << 20).arrayBuffer());
    if (head.byteLength < 4 || head.getUint16(0) !== 0xFFD8) return null;
    let at = 2;
    while (at + 9 <= head.byteLength) {
      if (head.getUint8(at) !== 0xFF) return null;
      const marker = head.getUint8(at + 1);
      if (marker === 0xFF) { at += 1; continue; }                                  // fill byte
      if (marker === 0x01 || (marker >= 0xD0 && marker <= 0xD8)) { at += 2; continue; } // no payload
      if (marker === 0xDA || marker === 0xD9) return null;                          // image data began: no frame header
      const frameHeader = marker >= 0xC0 && marker <= 0xCF && marker !== 0xC4 && marker !== 0xC8 && marker !== 0xCC;
      if (frameHeader) return { width: head.getUint16(at + 7), height: head.getUint16(at + 5) };
      at += 2 + head.getUint16(at + 2);
    }
    return null;
  }

  // 1568px is the longest edge the vision model keeps; anything larger is
  // downsampled on arrival, so sending more is upload time for nothing.
  //
  // The decode is asked for that size rather than the camera's. A 12-megapixel
  // frame decodes to 48MB and a 50-megapixel one to 200MB, and a phone that has
  // just handed one over wants that memory back for the next shot. One
  // dimension is given and the browser keeps the ratio: which edge is the width
  // depends on an orientation tag this does not read, and a wrong guess at the
  // pair would squash the picture where a wrong guess at one edge only leaves
  // it a little larger than asked, for the canvas below to finish.
  async function resize(file, maxDim = 1568, quality = 0.85) {
    const size = await jpegSize(file);
    const asked = size ? Math.min(1, maxDim / Math.max(size.width, size.height)) : 1;
    const bitmap = await createImageBitmap(file, asked < 1
      ? { resizeWidth: Math.round(size.width * asked), resizeQuality: 'high' }
      : {});
    const scale = Math.min(1, maxDim / Math.max(bitmap.width, bitmap.height));
    if (asked >= 1 && scale >= 1 && file.size < 3_000_000) { bitmap.close(); return file; }
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(bitmap.width * scale);
    canvas.height = Math.round(bitmap.height * scale);
    canvas.getContext('2d').drawImage(bitmap, 0, 0, canvas.width, canvas.height);
    // Release the decoded frame now rather than when the collector gets round
    // to it, and the canvas the moment its JPEG exists.
    bitmap.close();
    const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/jpeg', quality));
    canvas.width = canvas.height = 0;
    return new File([blob], 'photo.jpg', { type: 'image/jpeg' });
  }

  // A small copy for showing on the page. An <img> is decoded at the size it
  // was given, not the size it is drawn at, so a strip of 88px thumbnails
  // backed by the photographs themselves is the photographs themselves in
  // memory — seven megabytes each — for as long as the strip is on screen,
  // which is exactly while the camera is open for the next one.
  async function preview(file, edge = 264) {
    const bitmap = await createImageBitmap(file, { resizeWidth: edge, resizeQuality: 'medium' });
    const canvas = document.createElement('canvas');
    canvas.width = bitmap.width;
    canvas.height = bitmap.height;
    canvas.getContext('2d').drawImage(bitmap, 0, 0);
    bitmap.close();
    const blob = await new Promise(resolve => canvas.toBlob(resolve, 'image/jpeg', 0.8));
    canvas.width = canvas.height = 0;
    return blob;
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

  window.rackPhotos = { resize, preview, stash, takeStash };
})();
