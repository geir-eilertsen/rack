// The camera, inside the page.
//
// A file input with capture="environment" hands the phone over to its camera
// app, and while that app is in front the phone may reclaim rack behind it. When
// the camera hands the picture back there is no page left to receive it, and the
// app relaunches at the hub with nothing to show for the shot — the photograph
// was lost before it ever reached the server, which is why staging could not
// save it. Nothing a page does can veto that kill; the only fix is to never
// leave. So the viewfinder is drawn here, with getUserMedia.
//
// A shot is a still from the camera's own photo pipeline where the browser
// offers one (ImageCapture.takePhoto, Chromium on Android), and a frame off
// the stream where it does not. The first build took the frame only, and a
// preview frame is the wrong thing to read a part number off: it is the video
// path, noise-reduced and small, focused by whatever continuous autofocus had
// settled on, and small print up close came out blurred. The still is asked
// for at about twice the size the rack keeps, so the server's fit averages
// pixels rather than keeping them, and it is never decoded on the phone — the
// thumbnail in the corner is drawn from the stream, not from the file. The
// objection to takePhoto was that some Android builds hand the still over on
// its side with no orientation tag; the server turns a frame by its tag now,
// and for a frame without one the page sends how the viewfinder was held
// (`seen`), which is the one thing the page knows that the file does not.
//
// Close-up and zoom are the two controls the camera app has that a stream
// lacks, and both are what photographing a small part needs: the main lens
// cannot focus inside about ten centimetres, so the honest way to fill the
// frame with a 3mm part number is to hold the phone where it can focus and
// zoom. Close-up sets manual focus at the lens's nearest distance; zoom is a
// crop on the sensor, so on the still it costs nothing. Both are offered only
// where the track reports the capability. A line under the title says what
// the camera actually gave — stream size, still size, focus range, zoom —
// because what a phone negotiates is not what the page asked for, and a
// blurred photo is a question that line answers.
//
// A desktop without a touch screen keeps the file picker, because a file picker
// is what you want there. A phone whose camera cannot be opened — permission
// refused, no rear camera, an old WebView — falls back to the camera app for a
// day, so a refusal does not become a page that cannot take a photo.
(function () {
  const LONGEST_EDGE = 1568;           // what the rack keeps
  const STILL_EDGE = 2 * LONGEST_EDGE; // asked of the camera's still, so the fit has something to average
  const THUMB = 104;                   // the corner thumbnail at 2× for a 52px box
  const ZOOMS = [1, 2, 4];
  const OFF_KEY = 'rack.camera.off-until';
  const OFF_FOR = 24 * 60 * 60 * 1000;

  /** Whether the in-page camera is the right thing here at all. */
  function usable() {
    if (!window.isSecureContext) return false;
    if (!navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) return false;
    if (!window.matchMedia || !window.matchMedia('(pointer: coarse)').matches) return false;
    try {
      const until = +localStorage.getItem(OFF_KEY) || 0;
      if (until > Date.now()) return false;
    } catch (e) { /* no storage, no memory of a refusal */ }
    return true;
  }

  function giveUpForADay() {
    try { localStorage.setItem(OFF_KEY, String(Date.now() + OFF_FOR)); } catch (e) { /* fine */ }
  }

  /**
   * Puts the in-page camera in front of a file input. `trigger` is what the
   * user taps (a label around the input, or a button that clicks it); tapping
   * it opens the viewfinder here when that is usable and the input otherwise.
   * Either way every photograph reaches `each([file])`, in the order shot, and
   * `done(count)` is told once when the batch is complete — on closing the
   * viewfinder, or as soon as the input has answered. `title` is what to say
   * over the viewfinder, a string or a function of none.
   *
   * A File from the viewfinder carries `seen` — 'portrait' or 'landscape',
   * how the screen was held when it was shot — for the uploader to send
   * beside it; a File from the input has nothing to say about that.
   */
  function claim(trigger, input, handlers) {
    const each = handlers.each || (() => {});
    const done = handlers.done || (() => {});

    input.addEventListener('change', async () => {
      const files = [...(input.files || [])];
      input.value = '';
      if (!files.length) return;
      try { await each(files); } catch (e) { /* the page has said so */ }
      done(files.length);
    });

    trigger.addEventListener('click', ev => {
      if (!usable()) return;                    // the input opens as it always did
      ev.preventDefault();
      open({
        title: typeof handlers.title === 'function' ? handlers.title() : handlers.title,
        each,
        done,
        fallback: () => input.click()
      });
    });
  }

  let ui = null;

  function build() {
    const root = document.createElement('div');
    root.className = 'cam';
    root.hidden = true;
    root.innerHTML =
      '<div class="cam-top"><div class="cam-head"><span class="cam-title"></span><span class="cam-info"></span></div>' +
        '<div class="cam-tools">' +
          '<button type="button" class="cam-tool cam-near" hidden aria-pressed="false" title="Focus as close as the lens can">Close-up</button>' +
          '<button type="button" class="cam-tool cam-zoom" hidden title="Zoom">1×</button>' +
          '<button type="button" class="cam-torch" hidden aria-label="Torch" title="Torch">' +
            '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2L3 14h7l-1 8 10-12h-7z"/></svg>' +
          '</button>' +
        '</div></div>' +
      '<div class="cam-stage"><video autoplay playsinline muted></video><div class="cam-flash"></div>' +
        '<div class="cam-trouble" hidden><p class="cam-why"></p><button type="button" class="btn cam-fallback">Use the camera app instead</button></div></div>' +
      '<div class="cam-bar">' +
        '<button type="button" class="cam-close">Cancel</button>' +
        '<button type="button" class="cam-shutter" aria-label="Take photo"><span></span></button>' +
        '<div class="cam-count"><img alt="" hidden><b hidden></b></div>' +
      '</div>' +
      '<div class="cam-status" hidden></div>';
    document.body.appendChild(root);
    return {
      root,
      title: root.querySelector('.cam-title'),
      info: root.querySelector('.cam-info'),
      near: root.querySelector('.cam-near'),
      zoom: root.querySelector('.cam-zoom'),
      torch: root.querySelector('.cam-torch'),
      video: root.querySelector('video'),
      flash: root.querySelector('.cam-flash'),
      trouble: root.querySelector('.cam-trouble'),
      why: root.querySelector('.cam-why'),
      fallback: root.querySelector('.cam-fallback'),
      close: root.querySelector('.cam-close'),
      shutter: root.querySelector('.cam-shutter'),
      thumb: root.querySelector('.cam-count img'),
      count: root.querySelector('.cam-count b'),
      status: root.querySelector('.cam-status')
    };
  }

  let stream = null;
  let session = null;
  let caps = {};          // what the track says it can do
  let still = null;       // ImageCapture on the track, or null for a frame grab
  let photoCaps = null;   // the still sizes on offer
  let zooms = [];
  let zoomAt = 0;

  async function open(opts) {
    if (!ui) ui = build();
    if (session) return;
    session = { shots: 0, chain: Promise.resolve(), opts };
    ui.title.textContent = opts.title || 'Take a photo';
    ui.info.textContent = '';
    ui.trouble.hidden = true;
    ui.status.hidden = true;
    ui.thumb.hidden = true;
    ui.count.hidden = true;
    ui.torch.hidden = true;
    ui.near.hidden = true;
    ui.zoom.hidden = true;
    ui.close.textContent = 'Cancel';
    ui.shutter.disabled = true;
    ui.root.hidden = false;
    document.body.classList.add('cam-open');

    ui.close.onclick = close;
    ui.shutter.onclick = shoot;
    ui.fallback.onclick = () => { const f = session.opts.fallback; close(); if (f) f(); };
    ui.torch.onclick = toggleTorch;
    ui.near.onclick = toggleNear;
    ui.zoom.onclick = cycleZoom;

    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        // The rear camera, at the largest stream it offers: the viewfinder is
        // what focus is judged on, and where there is no still pipeline the
        // frame grabbed is this. Ideal, not exact: a phone that offers
        // 1920×1080 is a phone that can take the photo.
        video: { facingMode: { ideal: 'environment' }, width: { ideal: 4032 }, height: { ideal: 3024 } }
      });
    } catch (e) {
      // A refusal is remembered for a day so the label goes straight to the
      // camera app tomorrow instead of asking again; anything else is tried
      // afresh next time, since a busy camera is not a missing one.
      if (e && (e.name === 'NotAllowedError' || e.name === 'NotFoundError' || e.name === 'SecurityError')) giveUpForADay();
      trouble(e && e.name === 'NotAllowedError'
        ? 'rack was not allowed to use the camera.'
        : 'The camera could not be opened' + (e && e.message ? ': ' + e.message : '.'));
      return;
    }
    if (!session) { stopStream(); return; }           // closed while asking

    const track = stream.getVideoTracks()[0];
    // A stream can end under the page: the phone went to sleep, another app
    // took the camera. What was shot is on the server; the rest is a fresh open.
    track.onended = () => { if (session) close(); };
    ui.video.srcObject = stream;
    try { await ui.video.play(); } catch (e) { /* autoplay muted; a tap will do */ }
    ui.shutter.disabled = false;

    caps = track.getCapabilities ? track.getCapabilities() : {};
    ui.torch.hidden = !caps.torch;
    ui.torch.classList.remove('on');

    // Close-up wants manual focus with a distance to set it to; zoom wants a
    // range that goes somewhere. Either absent, the button is not offered.
    const modes = caps.focusMode || [];
    ui.near.hidden = !(modes.includes('manual') && caps.focusDistance && isFinite(caps.focusDistance.min));
    setNear(false);
    zooms = caps.zoom && caps.zoom.max > 1 ? ZOOMS.filter(z => z >= caps.zoom.min && z <= caps.zoom.max) : [];
    zoomAt = 0;
    ui.zoom.hidden = zooms.length < 2;
    ui.zoom.textContent = '1×';

    still = null;
    photoCaps = null;
    if (window.ImageCapture) {
      try {
        still = new ImageCapture(track);
        photoCaps = await still.getPhotoCapabilities();
        if (!photoCaps || !photoCaps.imageWidth) still = null;   // nothing to size it by: a frame is the safer answer
      } catch (e) {
        still = null;
      }
    }
    if (!session) { stopStream(); return; }
    report(track);
  }

  /** What the camera actually gave, under the title and on the console. */
  function report(track, extra) {
    const parts = [];
    const vw = ui.video.videoWidth, vh = ui.video.videoHeight;
    const s = track.getSettings ? track.getSettings() : {};
    if (vw && vh) parts.push(vw + '×' + vh);
    else if (s.width) parts.push(s.width + '×' + s.height);
    parts.push(still ? 'still ' + photoCaps.imageWidth.max + '×' + photoCaps.imageHeight.max : 'frames only');
    if (caps.focusMode) {
      let f = 'focus ' + caps.focusMode.join('/');
      if (caps.focusDistance) f += ' ' + metres(caps.focusDistance.min) + '–' + metres(caps.focusDistance.max);
      parts.push(f);
    }
    if (caps.zoom) parts.push('zoom ' + caps.zoom.min + '–' + caps.zoom.max);
    if (extra) parts.push(extra);
    const text = parts.join(' · ');
    ui.info.textContent = text;
    try { console.log('rack camera: ' + text, { settings: s, capabilities: caps, photo: photoCaps }); } catch (e) { /* no console */ }
  }

  function metres(m) {
    if (!isFinite(m)) return '∞';
    return m < 1 ? Math.round(m * 100) + 'cm' : (Math.round(m * 10) / 10) + 'm';
  }

  function trouble(why) {
    ui.why.textContent = why;
    ui.trouble.hidden = false;
    ui.shutter.disabled = true;
  }

  async function toggleTorch() {
    if (!stream) return;
    const track = stream.getVideoTracks()[0];
    const on = !ui.torch.classList.contains('on');
    try {
      await track.applyConstraints({ advanced: [{ torch: on }] });
      ui.torch.classList.toggle('on', on);
    } catch (e) { ui.torch.hidden = true; }
  }

  function setNear(on) {
    ui.near.classList.toggle('on', on);
    ui.near.setAttribute('aria-pressed', on ? 'true' : 'false');
  }

  /**
   * Manual focus at the nearest distance the lens reports, or back to the
   * camera's own autofocus. Reported in metres, nearest first, so `min` is
   * the close end.
   */
  async function toggleNear() {
    if (!stream) return;
    const track = stream.getVideoTracks()[0];
    const on = !ui.near.classList.contains('on');
    const modes = caps.focusMode || [];
    const back = modes.includes('continuous') ? 'continuous' : modes.find(m => m !== 'manual');
    try {
      await track.applyConstraints({ advanced: [on
        ? { focusMode: 'manual', focusDistance: caps.focusDistance.min }
        : (back ? { focusMode: back } : {})] });
      setNear(on);
    } catch (e) {
      ui.near.hidden = true;
      say('Close-up is not available on this camera: ' + (e && e.message ? e.message : e));
    }
  }

  async function cycleZoom() {
    if (!stream || !zooms.length) return;
    const track = stream.getVideoTracks()[0];
    const next = (zoomAt + 1) % zooms.length;
    try {
      await track.applyConstraints({ advanced: [{ zoom: zooms[next] }] });
      zoomAt = next;
      ui.zoom.textContent = zooms[next] + '×';
    } catch (e) {
      ui.zoom.hidden = true;
      say('Zoom is not available on this camera: ' + (e && e.message ? e.message : e));
    }
  }

  /** Which way the screen was held: what the server needs to turn a still that does not say. */
  function seen() {
    return ui.video.videoWidth >= ui.video.videoHeight ? 'landscape' : 'portrait';
  }

  /**
   * A still from the camera's photo pipeline, at about twice the size the
   * rack keeps and the stream's aspect. The sizes on offer are the sensor's,
   * wider than tall, whichever way the phone is held.
   */
  function takeStill() {
    const vw = ui.video.videoWidth, vh = ui.video.videoHeight;
    const long = Math.max(vw, vh) || 4, short = Math.min(vw, vh) || 3;
    const width = clamp(STILL_EDGE, photoCaps.imageWidth);
    const height = clamp(Math.round(width * short / long), photoCaps.imageHeight);
    return still.takePhoto({ imageWidth: width, imageHeight: height }).then(blob =>
      new File([blob], 'photo.jpg', { type: blob.type || 'image/jpeg' }));
  }

  function clamp(wanted, range) {
    if (!range) return wanted;
    return Math.max(range.min || 1, Math.min(range.max || wanted, wanted));
  }

  /** One frame off the stream, fitted, as a File the pages already know how to send. */
  function grab() {
    const video = ui.video;
    const w = video.videoWidth, h = video.videoHeight;
    if (!w || !h) return Promise.reject(new Error('No picture from the camera yet'));
    const scale = Math.min(1, LONGEST_EDGE / Math.max(w, h));
    return fromVideo(Math.round(w * scale), Math.round(h * scale), 0.92);
  }

  function fromVideo(width, height, quality) {
    const canvas = document.createElement('canvas');
    canvas.width = width;
    canvas.height = height;
    const ctx = canvas.getContext('2d');
    ctx.drawImage(ui.video, 0, 0, width, height);
    return new Promise((resolve, reject) => {
      canvas.toBlob(blob => {
        // Let the bitmap go before the upload starts rather than when the
        // canvas is collected: this is the memory the phone was short of.
        canvas.width = canvas.height = 0;
        if (!blob) return reject(new Error('Could not encode the photo'));
        resolve(new File([blob], 'photo.jpg', { type: 'image/jpeg' }));
      }, 'image/jpeg', quality);
    });
  }

  /** The corner thumbnail, from the stream: the still itself is never decoded here. */
  function thumbnail() {
    const w = ui.video.videoWidth, h = ui.video.videoHeight;
    if (!w || !h) return Promise.resolve(null);
    const scale = THUMB / Math.max(w, h);
    return fromVideo(Math.max(1, Math.round(w * scale)), Math.max(1, Math.round(h * scale)), 0.7).catch(() => null);
  }

  /** The still where there is one, the frame where there is not or the still failed. */
  async function take() {
    const held = seen();
    let file;
    if (still) {
      try {
        file = await takeStill();
      } catch (e) {
        // A camera that promised a still and did not give one: say so on the
        // info line, and take frames for the rest of this session.
        still = null;
        if (stream) report(stream.getVideoTracks()[0], 'still failed: ' + (e && e.message ? e.message : e));
      }
    }
    if (!file) file = await grab();
    file.seen = held;
    return file;
  }

  async function shoot() {
    if (!session || ui.shutter.disabled) return;
    ui.shutter.disabled = true;
    ui.flash.classList.remove('go');
    void ui.flash.offsetWidth;                  // restart the animation
    ui.flash.classList.add('go');
    const small = thumbnail();                  // the frame on screen now, before the still is asked for
    let file;
    try {
      file = await take();
    } catch (e) {
      say('Could not take the photo: ' + e.message);
      ui.shutter.disabled = false;
      return;
    }
    ui.shutter.disabled = false;
    session.shots++;
    ui.count.textContent = String(session.shots);
    ui.count.hidden = false;
    ui.close.textContent = 'Done';
    small.then(thumb => {
      if (!thumb) return;
      const url = URL.createObjectURL(thumb);
      ui.thumb.onload = () => URL.revokeObjectURL(url);
      ui.thumb.src = url;
      ui.thumb.hidden = false;
    });
    // Handed over in the order shot, one at a time, while the viewfinder stays
    // up for the next: the upload runs behind the next framing.
    const s = session;
    s.chain = s.chain.then(() => s.opts.each([file])).catch(e => {
      say('Upload failed: ' + (e && e.message ? e.message : e));
    });
  }

  function say(text) {
    ui.status.textContent = text;
    ui.status.hidden = false;
    clearTimeout(say.timer);
    say.timer = setTimeout(() => { ui.status.hidden = true; }, 6000);
  }

  function stopStream() {
    if (stream) stream.getTracks().forEach(t => t.stop());
    stream = null;
    still = null;
    if (ui) ui.video.srcObject = null;
  }

  async function close() {
    if (!session) return;
    const s = session;
    session = null;
    stopStream();
    ui.root.hidden = true;
    document.body.classList.remove('cam-open');
    if (!s.shots) return;
    // The batch is complete once every shot has been handed over — the page
    // may be about to navigate on it.
    await s.chain;
    s.opts.done(s.shots);
  }

  window.rackCamera = { usable, claim, open, close };
})();
