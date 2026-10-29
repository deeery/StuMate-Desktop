/* MarkDowner — shared UI primitives: toasts, modal dialogs, context menus.
 *
 * All three share one concern: a floating layer that must (a) trap nothing it
 * should not, (b) restore focus to whatever opened it, and (c) be dismissible
 * from the keyboard. That logic lives here once rather than three times.
 */
(function (MD, global) {
  'use strict';

  var el = MD.dom.el;
  var icon = MD.icon;
  var on = MD.dom.on;

  /* ==========================================================================
   * 1. Toasts
   * ======================================================================= */

  var TOAST_ICONS = {
    success: 'checkCircle',
    error: 'alertCircle',
    warning: 'alertTriangle',
    info: 'info'
  };

  var region = null;
  var liveRegion = null;

  function toastRegion() {
    if (!region) {
      region = el('div', { className: 'toast-region', role: 'region' });
      region.setAttribute('aria-label', '通知');
      document.body.appendChild(region);
    }
    return region;
  }

  function announce(message) {
    if (!liveRegion) {
      liveRegion = el('div', { className: 'announcer', role: 'status', 'aria-live': 'polite' });
      document.body.appendChild(liveRegion);
    }
    liveRegion.textContent = '';
    // A rAF gap makes repeat announcements of the same string re-fire.
    MD.dom.raf(function () { liveRegion.textContent = message; });
  }

  /**
   * toast({ title, detail, kind, action, duration })
   * `action` is { label, onClick }. Duration 0 means "stay until dismissed".
   */
  function toast(options) {
    var opts = options || {};
    var kind = opts.kind || 'info';
    var duration = opts.duration === undefined ? 3600 : opts.duration;

    var node = el('div', {
      className: 'toast toast--' + kind,
      role: kind === 'error' ? 'alert' : 'status'
    });

    node.appendChild(el('div', { className: 'toast__icon' }, icon(TOAST_ICONS[kind] || 'info', { size: 'md' })));

    var body = el('div', { className: 'toast__body' });
    body.appendChild(el('p', { className: 'toast__title', text: opts.title || '' }));
    if (opts.detail) body.appendChild(el('p', { className: 'toast__detail', text: opts.detail }));
    node.appendChild(body);

    var timer = null;

    function dismiss() {
      if (timer) global.clearTimeout(timer);
      node.setAttribute('data-leaving', 'true');
      var remove = function () { if (node.parentNode) node.parentNode.removeChild(node); };
      if (MD.util.prefersReducedMotion()) remove();
      else global.setTimeout(remove, 200);
    }

    if (opts.action && opts.action.label) {
      var actionBtn = el('button', {
        className: 'toast__action t-interactive',
        type: 'button',
        text: opts.action.label
      });
      actionBtn.addEventListener('click', function () {
        try { opts.action.onClick(); } finally { dismiss(); }
      });
      body.appendChild(actionBtn);
    }

    var closeBtn = el('button', {
      className: 'toast__close t-interactive',
      type: 'button',
      'aria-label': '关闭通知'
    }, icon('close', { size: 'sm' }));
    closeBtn.addEventListener('click', dismiss);
    node.appendChild(closeBtn);

    // Pause the countdown while the pointer rests on the toast, so a long
    // message never vanishes mid-read.
    if (duration > 0) {
      var remaining = duration;
      var startedAt = Date.now();
      var schedule = function () {
        startedAt = Date.now();
        timer = global.setTimeout(dismiss, remaining);
      };
      node.addEventListener('mouseenter', function () {
        if (timer) global.clearTimeout(timer);
        remaining -= Date.now() - startedAt;
      });
      node.addEventListener('mouseleave', function () {
        if (remaining > 0) schedule();
      });
      schedule();
    }

    toastRegion().appendChild(node);
    return dismiss;
  }

  toast.success = function (title, detail) { return toast({ kind: 'success', title: title, detail: detail }); };
  toast.error = function (title, detail) { return toast({ kind: 'error', title: title, detail: detail, duration: 5200 }); };
  toast.warning = function (title, detail) { return toast({ kind: 'warning', title: title, detail: detail }); };
  toast.info = function (title, detail) { return toast({ kind: 'info', title: title, detail: detail }); };

  /* ==========================================================================
   * 2. Overlay / dialog
   * ======================================================================= */

  var openLayers = [];

  /**
   * Mount a floating layer. Handles Escape, backdrop click, focus restore and
   * a focus trap that keeps Tab inside the layer while it is open.
   */
  function openLayer(overlay, options) {
    var opts = options || {};
    var restoreTo = document.activeElement;
    var previousOverflow = document.body.style.overflow;

    overlay.hidden = false;
    document.body.appendChild(overlay);
    if (opts.lockScroll !== false) document.body.style.overflow = 'hidden';

    var entry = { overlay: overlay, restoreTo: restoreTo, onClose: opts.onClose };
    openLayers.push(entry);

    var focusables = function () {
      return MD.dom.$$(
        'a[href], button:not([disabled]), input:not([disabled]), select, textarea, [tabindex]:not([tabindex="-1"])',
        overlay
      ).filter(function (node) {
        return node.offsetParent !== null || node === document.activeElement;
      });
    };

    function close(reason) {
      var at = openLayers.indexOf(entry);
      if (at !== -1) openLayers.splice(at, 1);
      if (overlay.parentNode) overlay.parentNode.removeChild(overlay);
      overlay.hidden = true;
      if (!openLayers.length) document.body.style.overflow = previousOverflow;
      if (restoreTo && restoreTo.focus && document.contains(restoreTo)) {
        try { restoreTo.focus(); } catch (err) { /* element became unfocusable */ }
      }
      if (opts.onClose) opts.onClose(reason);
    }

    entry.close = close;

    overlay.addEventListener('mousedown', function (event) {
      if (event.target === overlay && opts.dismissOnBackdrop !== false) close('backdrop');
    });

    overlay.addEventListener('keydown', function (event) {
      if (event.key !== 'Tab') return;
      var list = focusables();
      if (!list.length) return;
      var first = list[0];
      var last = list[list.length - 1];
      // Only wrap when the entry layer is the topmost one, so a nested dialog
      // does not fight the dialog underneath it.
      if (openLayers[openLayers.length - 1] !== entry) return;
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    });

    var initial = opts.initialFocus
      ? (typeof opts.initialFocus === 'string' ? overlay.querySelector(opts.initialFocus) : opts.initialFocus)
      : null;
    if (!initial) {
      var candidates = focusables();
      var preferred = overlay.querySelector('[data-autofocus]');
      initial = preferred || candidates[0] || overlay;
    }
    MD.dom.raf(function () {
      if (initial && initial.focus) {
        try { initial.focus(); } catch (err) { /* ignore */ }
      }
    });

    return entry;
  }

  /** Close the topmost layer. */
  function closeTopLayer() {
    var top = openLayers[openLayers.length - 1];
    if (top && top.close) { top.close('escape'); return true; }
    return false;
  }

  function hasOpenLayer() {
    return openLayers.length > 0;
  }

  // One global Escape handler: layers are a stack, so the key naturally pops.
  on(document, 'keydown', function (event) {
    if (event.key !== 'Escape') return;
    if (!openLayers.length) return;
    // The command palette and find bar own their own Escape-to-close contract.
    var top = openLayers[openLayers.length - 1];
    if (top.overlay.dataset.escHandler === 'self') return;
    event.stopPropagation();
    event.preventDefault();
    closeTopLayer();
  }, true);

  /**
   * dialog({ title, body, actions, width, kind, onMount })
   * `body` is a node or an array of nodes. `actions` is
   * [{ label, kind, value, primary, autofocus }].
   * Returns a promise resolving to the chosen action's value (or null).
   */
  function dialog(options) {
    var opts = options || {};
    return new Promise(function (resolve) {
      // A promise settles once, and `close()` fires `onClose` — which resolves
      // null. So the action button must settle BEFORE closing, otherwise the
      // onClose null wins the race and every dialog resolves to null: settings
      // would never save, and every confirm would read as "cancelled".
      var settled = false;
      function settle(value) {
        if (settled) return;
        settled = true;
        resolve(value);
      }

      var overlay = el('div', { className: 'overlay', hidden: true });
      overlay.setAttribute('role', 'dialog');
      overlay.setAttribute('aria-modal', 'true');

      var panel = el('div', {
        className: 'dialog' + (opts.width === 'wide' ? ' dialog--wide' : '') +
          (opts.kind === 'confirm' ? ' dialog--confirm' : '')
      });

      if (opts.title) {
        panel.appendChild(el('div', { className: 'dialog__head' }, [
          el('h2', { className: 'dialog__title', id: MD.util.uid('dlg-title'), text: opts.title })
        ]));
      }

      var body = el('div', { className: 'dialog__body' + (opts.flush ? ' dialog__body--flush' : '') });
      MD.dom.append(body, opts.body);
      panel.appendChild(body);

      var foot = el('div', { className: 'dialog__foot' + (opts.split ? ' dialog__foot--split' : '') });
      (opts.actions || []).forEach(function (spec) {
        var cls = 'btn';
        if (spec.kind === 'primary') cls += ' btn--primary';
        else if (spec.kind === 'danger') cls += ' btn--danger';
        else if (spec.kind === 'ghost') cls += ' btn--ghost';
        else cls += ' btn--outline';
        var button = el('button', {
          className: cls,
          type: 'button',
          text: spec.label
        });
        if (spec.autofocus) button.setAttribute('data-autofocus', '');
        button.addEventListener('click', function () {
          if (settled) return;
          var value;
          if (typeof spec.value === 'function') {
            value = spec.value();
            // A function value acts as a validator: `undefined` means "rejected"
            // (e.g. a required field was empty). Keep the dialog open so the user
            // can correct it instead of dismissing and reporting success.
            if (value === undefined) return;
          } else {
            value = spec.value;
          }
          // Settle BEFORE closing: `close()` runs `onClose`, which settles null,
          // so closing first would discard the value the user just chose and
          // every dialog would read as "dismissed".
          settle(value === undefined ? spec.label : value);
          current.close('action');
        });
        foot.appendChild(button);
      });
      if (foot.childNodes.length) panel.appendChild(foot);

      overlay.appendChild(panel);
      var current = openLayer(overlay, {
        initialFocus: opts.initialFocus,
        // Routed through `settle` so a dismissed layer (Escape, backdrop) yields
        // null only when no action already claimed the result.
        onClose: function () { settle(null); },
        onMount: opts.onMount
      });
      if (opts.onMount) opts.onMount(panel, current);
    });
  }

  /** Yes/no confirmation. Resolves true only on the primary action. */
  function confirmDialog(options) {
    var opts = options || {};
    return dialog({
      title: opts.title || '确认操作',
      kind: 'confirm',
      body: el('p', { text: opts.message || '' }),
      actions: [
        { label: opts.cancelLabel || '取消', kind: 'ghost', value: false },
        {
          label: opts.confirmLabel || '确定',
          kind: opts.danger ? 'danger' : 'primary',
          value: true,
          autofocus: true
        }
      ]
    }).then(function (value) { return value === true; });
  }

  /** Single-line text prompt. Resolves the string, or null on cancel. */
  function promptDialog(options) {
    var opts = options || {};
    var input = el('input', {
      className: 'input',
      type: 'text',
      value: opts.value || '',
      placeholder: opts.placeholder || '',
      'aria-label': opts.label || opts.title || '输入'
    });
    var error = el('p', { className: 'field-error', hidden: true, role: 'alert' });

    var field = el('div', { className: 'field' }, [input, error]);
    var wrap = el('div', {}, [
      opts.label ? el('label', { className: 'field__label', text: opts.label }) : null,
      field
    ]);

    function submit() {
      var value = input.value.trim();
      if (opts.required !== false && !value) {
        error.textContent = '不能为空';
        error.hidden = false;
        input.setAttribute('aria-invalid', 'true');
        input.focus();
        return undefined;
      }
      return value;
    }

    input.addEventListener('input', function () {
      error.hidden = true;
      input.removeAttribute('aria-invalid');
    });
    input.addEventListener('keydown', function (event) {
      if (event.key === 'Enter') {
        event.preventDefault();
        panel.querySelector('.btn--primary').click();
      }
    });

    var panel;
    return dialog({
      title: opts.title || '输入',
      body: wrap,
      initialFocus: input,
      actions: [
        { label: '取消', kind: 'ghost', value: null },
        { label: opts.confirmLabel || '确定', kind: 'primary', value: submit, autofocus: false }
      ],
      onMount: function (node) {
        panel = node;
        input.focus();
        input.select();
      }
    }).then(function (value) {
      return typeof value === 'string' ? value : null;
    });
  }

  /* ==========================================================================
   * 3. Context menus
   * ======================================================================= */

  var activeMenu = null;

  function closeMenu() {
    if (!activeMenu) return;
    if (activeMenu.parentNode) activeMenu.parentNode.removeChild(activeMenu);
    activeMenu = null;
  }

  /**
   * menu({ x, y, items }) — items are
   *   { label, icon, shortcut, disabled, danger, onSelect } or { separator: true }
   * The menu flips rather than overflowing the viewport.
   */
  function menu(options) {
    closeMenu();
    var opts = options || {};
    var node = el('div', { className: 'menu', role: 'menu' });

    (opts.items || []).forEach(function (spec) {
      if (spec.separator) {
        node.appendChild(el('div', { className: 'menu__sep', role: 'separator' }));
        return;
      }
      var cls = 'menu__item' + (spec.danger ? ' menu__item--danger' : '');
      var item = el('button', {
        className: cls,
        type: 'button',
        role: 'menuitem',
        disabled: spec.disabled === true
      });
      if (spec.icon) item.appendChild(icon(spec.icon, { size: 'sm', className: 'menu__icon' }));
      item.appendChild(el('span', { className: 'menu__label', text: spec.label }));
      if (spec.shortcut) {
        item.appendChild(el('span', { className: 'menu__shortcut', text: spec.shortcut }));
      }
      if (spec.disabled) item.setAttribute('aria-disabled', 'true');
      item.addEventListener('click', function () {
        closeMenu();
        if (!spec.disabled && spec.onSelect) spec.onSelect();
      });
      node.appendChild(item);
    });

    node.hidden = false;
    document.body.appendChild(node);
    activeMenu = node;

    // Measure, then place: keeping the menu inside the viewport beats
    // honouring the exact click point.
    var rect = node.getBoundingClientRect();
    var pad = 8;
    var x = opts.x || 0;
    var y = opts.y || 0;
    if (x + rect.width + pad > global.innerWidth) x = Math.max(pad, global.innerWidth - rect.width - pad);
    if (y + rect.height + pad > global.innerHeight) y = Math.max(pad, y - rect.height);
    node.style.left = x + 'px';
    node.style.top = y + 'px';

    var first = node.querySelector('.menu__item:not([disabled])');
    if (first) first.focus();

    // Dismiss on the next outside interaction. Registered on the following tick
    // so the click that opened the menu does not also close it.
    global.setTimeout(function () {
      var away = function (event) {
        if (!activeMenu) return;
        if (!activeMenu.contains(event.target)) {
          closeMenu();
          document.removeEventListener('mousedown', away, true);
          document.removeEventListener('keydown', awayKey, true);
        }
      };
      var awayKey = function (event) {
        if (event.key === 'Escape') {
          closeMenu();
          document.removeEventListener('mousedown', away, true);
          document.removeEventListener('keydown', awayKey, true);
        }
      };
      document.addEventListener('mousedown', away, true);
      document.addEventListener('keydown', awayKey, true);
    }, 0);

    return { close: closeMenu, node: node };
  }

  /* ==========================================================================
   * 4. Empty-state helper
   * ======================================================================= */

  function emptyState(spec) {
    return el('div', { className: 'empty' }, [
      spec.icon ? el('div', { className: 'empty__icon' }, icon(spec.icon, { size: 'xl' })) : null,
      el('p', { className: 'empty__title', text: spec.title || '' }),
      spec.text ? el('p', { className: 'empty__text', text: spec.text }) : null,
      spec.action || null
    ]);
  }

  MD.ui = {
    toast: toast,
    announce: announce,
    dialog: dialog,
    confirm: confirmDialog,
    prompt: promptDialog,
    menu: menu,
    closeMenu: closeMenu,
    openLayer: openLayer,
    closeTopLayer: closeTopLayer,
    hasOpenLayer: hasOpenLayer,
    emptyState: emptyState
  };
})(window.MD = window.MD || {}, window);
