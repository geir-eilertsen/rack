// rack's own way of asking, in place of the browser's confirm() and prompt().
//
// The browser's boxes look like a different program, cannot say which button
// deletes something, and on a phone they are a system sheet over the page.
// These are a native <dialog> opened modally, so focus, Esc and the screen
// reader come for free, drawn in rack's own style.
//
//   await rackDialog.prompt({ title, message, value, placeholder, ok })        -> text | null
//   rackDialog.really(button)  -> false on the first click ("Really?"), true on the second
//   await rackDialog.ask({ title, message, input: { value, placeholder },
//                          choices: ['Komplett', 'Amazon.es'], choice, ok })   -> { value, choice } | null
//
// Cancel, Esc and a tap outside all answer null (or false). A dangerous
// confirmation starts with focus on Cancel, so Enter does not delete.
(function () {
  function ask(o) {
    o = o || {};
    return new Promise(resolve => {
      const d = document.createElement('dialog');
      d.className = 'rack-dialog';
      d.innerHTML = '<form>'
        + (o.title ? '<h2 class="rd-title"></h2>' : '')
        + (o.message ? '<p class="rd-msg"></p>' : '')
        + (o.choices && o.choices.length ? '<div class="rd-choices" role="radiogroup"></div>' : '')
        + (o.input ? '<label class="rd-field"><span class="rd-label"></span><input class="rd-value" type="text" autocomplete="off"></label>' : '')
        + '<div class="rd-acts"><button type="button" class="btn rd-cancel"></button>'
        + '<button type="submit" class="btn rd-ok"></button></div>'
        + '</form>';
      const $ = sel => d.querySelector(sel);
      if (o.title) $('.rd-title').textContent = o.title;
      if (o.message) $('.rd-msg').textContent = o.message;
      $('.rd-cancel').textContent = o.cancel || 'Cancel';
      const ok = $('.rd-ok');
      ok.textContent = o.ok || 'OK';
      ok.classList.add(o.danger ? 'danger-fill' : 'primary');

      let choice = null;
      if (o.choices && o.choices.length) {
        choice = o.choice != null && o.choices.indexOf(o.choice) >= 0 ? o.choice : o.choices[0];
        const host = $('.rd-choices');
        o.choices.forEach(c => {
          const b = document.createElement('button');
          b.type = 'button';
          b.className = 'rd-chip';
          b.setAttribute('role', 'radio');
          b.textContent = c;
          b.addEventListener('click', () => { choice = c; mark(); });
          host.appendChild(b);
        });
        const mark = () => host.querySelectorAll('.rd-chip').forEach(b => {
          const on = b.textContent === choice;
          b.classList.toggle('on', on);
          b.setAttribute('aria-checked', String(on));
        });
        mark();
      }

      const input = o.input ? $('.rd-value') : null;
      if (input) {
        const spec = typeof o.input === 'object' ? o.input : {};
        input.value = spec.value != null ? spec.value : '';
        if (spec.placeholder) input.placeholder = spec.placeholder;
        if (spec.label) $('.rd-label').textContent = spec.label; else $('.rd-label').remove();
        input.maxLength = spec.maxLength || 500;
      }

      let result = null;
      $('form').addEventListener('submit', ev => {
        ev.preventDefault();
        if (input && o.input.required && !input.value.trim()) { input.focus(); return; }
        result = { value: input ? input.value : '', choice };
        d.close();
      });
      $('.rd-cancel').addEventListener('click', () => d.close());
      // A tap on the shade outside the box is a way out, as on any sheet.
      d.addEventListener('click', ev => { if (ev.target === d) d.close(); });
      d.addEventListener('close', () => { d.remove(); resolve(result); });

      document.body.appendChild(d);
      d.showModal();
      if (input) { input.focus(); input.select(); }
      else (o.danger ? $('.rd-cancel') : ok).focus();
    });
  }

  /** The text typed, '' when nothing was, null when cancelled. */
  function prompt(o) {
    o = Object.assign({}, o);
    o.input = { value: o.value, placeholder: o.placeholder, label: o.label, required: o.required };
    return ask(o).then(r => r === null ? null : r.value);
  }

  /**
   * Two clicks on the button itself, for anything that deletes. The first
   * turns it into "Really?" and answers false; a second click on it within a
   * few seconds answers true. Waiting, or clicking anywhere else, puts it
   * back. Quicker than a dialog and impossible to do by one stray tap:
   *
   *   btn.addEventListener('click', () => { if (!rackDialog.really(btn)) return; ...delete... });
   */
  const REALLY_MS = 4000;
  function really(btn, label) {
    if (btn.dataset.really) {
      disarm(btn);
      return true;
    }
    btn.dataset.really = '1';
    btn.dataset.reallyWas = btn.innerHTML;
    btn.dataset.reallyTitle = btn.getAttribute('title') || '';
    btn.textContent = label || 'Really?';
    btn.setAttribute('title', 'Click again to confirm');
    btn.classList.add('really');
    const off = ev => { if (ev.target !== btn && !btn.contains(ev.target)) disarm(btn); };
    btn._reallyOff = off;
    btn._reallyTimer = setTimeout(() => disarm(btn), REALLY_MS);
    // Next tick: the click that armed it is still bubbling.
    setTimeout(() => document.addEventListener('click', off, true), 0);
    return false;
  }

  function disarm(btn) {
    if (!btn.dataset.really) return;
    clearTimeout(btn._reallyTimer);
    document.removeEventListener('click', btn._reallyOff, true);
    btn.innerHTML = btn.dataset.reallyWas;
    if (btn.dataset.reallyTitle) btn.setAttribute('title', btn.dataset.reallyTitle); else btn.removeAttribute('title');
    btn.classList.remove('really');
    delete btn.dataset.really;
    delete btn.dataset.reallyWas;
    delete btn.dataset.reallyTitle;
  }

  window.rackDialog = { ask, prompt, really };
})();
