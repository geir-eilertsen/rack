// The camera, inside the page.
//
// A file input with capture="environment" hands the phone over to its camera
// app, and while that app is in front the phone may reclaim rack behind it. When
// the camera hands the picture back there is no page left to receive it, and the
// app relaunches at the hub with nothing to show for the shot — the photograph
// was lost before it ever reached the server, which is why staging could not
// save it. Nothing a page does can veto that kill; the only fix is to never
// leave. So the viewfinder is drawn here, with getUserMedia, and a shot is a
// frame off the stream.
//
// Frames are taken at the size the rack keeps anyway. The server fits every
// photograph to 1568px on its longest edge, so a canvas that size is not a
// lower-quality copy of what the camera app would have sent — it is what the
// server would have made of it — and it is the size of one thumbnail-decode
// rather than a 50-megapixel bitmap. ImageCapture.takePhoto() would give the
// camera's full still, and is not used: on some Android builds it returns the
// frame turned on its side with no orientation tag to say so, and the page
// cannot check without decoding it, which is the memory cost this exists to
// avoid. What is drawn is what was on screen.
//
// A desktop without a touch screen keeps the file picker, because a file picker
// is what you want there. A phone whose camera cannot be opened — permission
// refused, no rear camera, an old WebView — falls back to the camera app for a
// day, so a refusal does not become a page that cannot take a photo.
(function () {
  const LONGEST_EDGE = 1568;
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
      '<div class="cam-top"><span class="cam-title"></span>' +
        '<button type="button" class="cam-torch" hidden aria-label="Torch" title="Torch">' +
          '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M13 2L3 14h7l-1 8 10-12h-7z"/></svg>' +
        '</button></div>' +
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

  async function open(opts) {
    if (!ui) ui = build();
    if (session) return;
    session = { shots: 0, chain: Promise.resolve(), opts };
    ui.title.textContent = opts.title || 'Take a photo';
    ui.trouble.hidden = true;
    ui.status.hidden = true;
    ui.thumb.hidden = true;
    ui.count.hidden = true;
    ui.torch.hidden = true;
    ui.close.textContent = 'Cancel';
    ui.shutter.disabled = true;
    ui.root.hidden = false;
    document.body.classList.add('cam-open');

    ui.close.onclick = close;
    ui.shutter.onclick = shoot;
    ui.fallback.onclick = () => { const f = session.opts.fallback; close(); if (f) f(); };
    ui.torch.onclick = toggleTorch;

    try {
      stream = await navigator.mediaDevices.getUserMedia({
        audio: false,
        // The rear camera, at more than the rack keeps so the frame is
        // downscaled rather than up. Ideal, not exact: a phone that offers
        // 1920×1080 is a phone that can take the photo.
        video: { facingMode: { ideal: 'environment' }, width: { ideal: 2560 }, height: { ideal: 1920 } }
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

    const caps = track.getCapabilities ? track.getCapabilities() : {};
    ui.torch.hidden = !caps.torch;
    ui.torch.classList.remove('on');
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

  /** One frame off the stream, fitted, as a File the pages already know how to send. */
  function grab() {
    const video = ui.video;
    const w = video.videoWidth, h = video.videoHeight;
    if (!w || !h) return Promise.reject(new Error('No picture from the camera yet'));
    const scale = Math.min(1, LONGEST_EDGE / Math.max(w, h));
    const canvas = document.createElement('canvas');
    canvas.width = Math.round(w * scale);
    canvas.height = Math.round(h * scale);
    const ctx = canvas.getContext('2d');
    ctx.drawImage(video, 0, 0, canvas.width, canvas.height);
    return new Promise((resolve, reject) => {
      canvas.toBlob(blob => {
        // Let the bitmap go before the upload starts rather than when the
        // canvas is collected: this is the memory the phone was short of.
        canvas.width = canvas.height = 0;
        if (!blob) return reject(new Error('Could not encode the photo'));
        resolve(new File([blob], 'photo.jpg', { type: 'image/jpeg' }));
      }, 'image/jpeg', 0.92);
    });
  }

  async function shoot() {
    if (!session || ui.shutter.disabled) return;
    ui.shutter.disabled = true;
    ui.flash.classList.remove('go');
    void ui.flash.offsetWidth;                  // restart the animation
    ui.flash.classList.add('go');
    let file;
    try {
      file = await grab();
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
    const url = URL.createObjectURL(file);
    ui.thumb.onload = () => URL.revokeObjectURL(url);
    ui.thumb.src = url;
    ui.thumb.hidden = false;
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
