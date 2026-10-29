/* ============================================================================
 * MarkDowner — util.js
 * Foundation layer: DOM helpers, formatting, storage, clipboard, measurement.
 * Loaded first; every other module builds on MD.util / MD.dom.
 * Classic script (no modules) so the app runs from file:// by double-click.
 * ========================================================================= */
(function (global) {
  'use strict';

  var MD = (global.MD = global.MD || {});

  /* ==========================================================================
   * 1. DOM construction
   * ======================================================================= */

  function $(sel, root) {
    return (root || document).querySelector(sel);
  }

  function $$(sel, root) {
    return Array.prototype.slice.call((root || document).querySelectorAll(sel));
  }

  /**
   * Create an element. Supported attrs keys:
   *   class, text, html, dataset{}, style{}, on<Event>(handler),
   *   aria-* and any other attribute name is passed through verbatim
   */
  function el(tag, attrs, children) {
    var node = document.createElement(tag);
    if (attrs) {
      Object.keys(attrs).forEach(function (key) {
        var value = attrs[key];
        if (value === null || value === undefined || value === false) return;
        if (key === 'class' || key === 'className') node.className = value;
        else if (key === 'classList' && Array.isArray(value)) node.className = value.join(' ');
        else if (key === 'text') node.textContent = String(value);
        else if (key === 'html') node.innerHTML = value;
        else if (key === 'dataset') {
          Object.keys(value).forEach(function (d) { node.dataset[d] = value[d]; });
        } else if (key === 'style') {
          if (typeof value === 'string') node.setAttribute('style', value);
          else Object.keys(value).forEach(function (p) { node.style[p] = value[p]; });
        } else if (key.slice(0, 2) === 'on' && typeof value === 'function') {
          node.addEventListener(key.slice(2).toLowerCase(), value);
        } else if (value === true) {
          node.setAttribute(key, '');
        } else {
          node.setAttribute(key, value);
        }
      });
    }
    return append(node, children);
  }

  function append(parent, children) {
    if (children === null || children === undefined) return parent;
    var list = Array.isArray(children) ? children : [children];
    for (var i = 0; i < list.length; i++) {
      var child = list[i];
      if (child === null || child === undefined || child === false) continue;
      parent.appendChild(
        typeof child === 'object' && child.nodeType ? child : document.createTextNode(String(child))
      );
    }
    return parent;
  }

  function clear(node) {
    while (node && node.firstChild) node.removeChild(node.firstChild);
    return node;
  }

  function on(target, type, handler, opts) {
    target.addEventListener(type, handler, opts);
    return function off() { target.removeEventListener(type, handler, opts); };
  }

  /** Delegated listener: handler receives (event, matchedElement). */
  function delegate(root, selector, type, handler) {
    return on(root, type, function (event) {
      var match = event.target && event.target.closest ? event.target.closest(selector) : null;
      if (match && root.contains(match)) handler(event, match);
    });
  }

  function show(node, visible) {
    if (node) node.hidden = !visible;
    return node;
  }

  function setText(node, text) {
    if (node && node.textContent !== text) node.textContent = text;
    return node;
  }

  function raf(fn) { return global.requestAnimationFrame(fn); }

  /* ==========================================================================
   * 2. Strings
   * ======================================================================= */

  var HTML_ESCAPES = { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' };

  function escapeHtml(value) {
    return String(value).replace(/[&<>"']/g, function (ch) { return HTML_ESCAPES[ch]; });
  }

  function escapeAttr(value) {
    return escapeHtml(value);
  }

  function slugify(text) {
    var slug = String(text)
      .trim()
      .toLowerCase()
      .replace(/\s+/g, '-')
      .replace(/[^\p{L}\p{N}_-]+/gu, '')
      .replace(/-{2,}/g, '-')
      .replace(/^-+|-+$/g, '');
    return slug || 'section';
  }

  function truncate(text, max) {
    text = String(text).replace(/\s+/g, ' ').trim();
    return text.length > max ? text.slice(0, max - 1) + '…' : text;
  }

  /** 2 -> "2nd"; used for ordinals in generated content. */
  function ordinal(n) {
    var s = ['th', 'st', 'nd', 'rd'];
    var v = n % 100;
    return n + (s[(v - 20) % 10] || s[v] || s[0]);
  }

  /** First line that carries meaning — used for document excerpts. */
  function firstMeaningfulLine(text) {
    var lines = String(text).split(/\r?\n/);
    for (var i = 0; i < lines.length; i++) {
      var line = lines[i]
        .replace(/^\s{0,3}#{1,6}\s*/, '')
        .replace(/^\s{0,3}>\s?/, '')
        .replace(/^\s*[-*+]\s+/, '')
        .replace(/^\s*\d{1,9}[.)]\s+/, '')
        .replace(/[*_`~=]/g, '')
        .trim();
      if (line) return line;
    }
    return '';
  }

  /* ==========================================================================
   * 3. Timing
   * ======================================================================= */

  function debounce(fn, wait) {
    var timer = null;
    function debounced() {
      var args = arguments;
      var self = this;
      if (timer) clearTimeout(timer);
      timer = setTimeout(function () { timer = null; fn.apply(self, args); }, wait);
    }
    debounced.cancel = function () { if (timer) { clearTimeout(timer); timer = null; } };
    debounced.flush = function () { if (timer) { clearTimeout(timer); timer = null; fn.call(this); } };
    return debounced;
  }

  function throttle(fn, wait) {
    var last = 0;
    var timer = null;
    var pendingArgs = null;
    return function () {
      var now = Date.now();
      var remaining = wait - (now - last);
      pendingArgs = arguments;
      if (remaining <= 0) {
        if (timer) { clearTimeout(timer); timer = null; }
        last = now;
        fn.apply(this, pendingArgs);
      } else if (!timer) {
        var self = this;
        timer = setTimeout(function () {
          timer = null;
          last = Date.now();
          fn.apply(self, pendingArgs);
        }, remaining);
      }
    };
  }

  /* ==========================================================================
   * 4. Misc value helpers
   * ======================================================================= */

  function clamp(value, min, max) {
    return value < min ? min : value > max ? max : value;
  }

  var uidSeed = 0;
  function uid(prefix) {
    uidSeed += 1;
    return (prefix || 'id') + '-' + Date.now().toString(36) + '-' + uidSeed.toString(36) +
      Math.random().toString(36).slice(2, 6);
  }

  function isMac() {
    return /Mac|iPhone|iPad|iPod/.test(global.navigator.platform || global.navigator.userAgent || '');
  }

  function isTouch() {
    return global.matchMedia ? global.matchMedia('(pointer: coarse)').matches : 'ontouchstart' in global;
  }

  function deepClone(value) {
    return value === undefined ? value : JSON.parse(JSON.stringify(value));
  }

  function prefersReducedMotion() {
    return !!(global.matchMedia && global.matchMedia('(prefers-reduced-motion: reduce)').matches);
  }

  /* ==========================================================================
   * 5. Text statistics (CJK aware — the audience writes Chinese)
   * ======================================================================= */

  var CJK_RE = /[\u3040-\u30ff\u3400-\u4dbf\u4e00-\u9fff\uf900-\ufaff\uac00-\ud7af]/g;

  function countCJK(text) {
    var match = String(text).match(CJK_RE);
    return match ? match.length : 0;
  }

  function countLatinWords(text) {
    var stripped = String(text).replace(CJK_RE, ' ');
    var match = stripped.match(/[A-Za-z0-9_'’-]+/g);
    return match ? match.length : 0;
  }

  function stats(text) {
    text = String(text || '');
    var cjk = countCJK(text);
    var words = countLatinWords(text);
    var lines = text.length ? text.split(/\r?\n/).length : 0;
    // Reading speed: ~400 CJK glyphs/min, ~220 latin words/min.
    var minutes = cjk / 400 + words / 220;
    return {
      chars: text.length,
      charsNoSpace: text.replace(/\s/g, '').length,
      words: words + cjk,
      lines: lines,
      paragraphs: text.split(/\n\s*\n/).filter(function (p) { return p.trim(); }).length,
      readingMinutes: minutes < 1 && text.trim() ? 1 : Math.round(minutes)
    };
  }

  /* ==========================================================================
   * 6. Formatting
   * ======================================================================= */

  function pad2(n) { return n < 10 ? '0' + n : String(n); }

  var fmt = {
    num: function (n) {
      return String(n).replace(/\B(?=(\d{3})+(?!\d))/g, ',');
    },
    bytes: function (n) {
      if (!n) return '0 B';
      var units = ['B', 'KB', 'MB', 'GB'];
      var i = Math.floor(Math.log(n) / Math.log(1024));
      i = clamp(i, 0, units.length - 1);
      var value = n / Math.pow(1024, i);
      return (i === 0 ? value : value.toFixed(value < 10 ? 1 : 0)) + ' ' + units[i];
    },
    date: function (ts) {
      var d = new Date(ts);
      return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate()) +
        ' ' + pad2(d.getHours()) + ':' + pad2(d.getMinutes());
    },
    dateShort: function (ts) {
      var d = new Date(ts);
      return d.getFullYear() + '-' + pad2(d.getMonth() + 1) + '-' + pad2(d.getDate());
    },
    clock: function (ts) {
      var d = new Date(ts);
      return pad2(d.getHours()) + ':' + pad2(d.getMinutes()) + ':' + pad2(d.getSeconds());
    },
    relative: function (ts) {
      var diff = Date.now() - ts;
      if (diff < 45000) return '刚刚';
      if (diff < 3600000) return Math.round(diff / 60000) + ' 分钟前';
      if (diff < 86400000) return Math.round(diff / 3600000) + ' 小时前';
      if (diff < 172800000) return '昨天';
      if (diff < 604800000) return Math.round(diff / 86400000) + ' 天前';
      return fmt.dateShort(ts);
    },
    duration: function (minutes) {
      if (!minutes) return '不到 1 分钟';
      if (minutes < 60) return minutes + ' 分钟';
      var h = Math.floor(minutes / 60);
      var m = minutes % 60;
      return m ? h + ' 小时 ' + m + ' 分' : h + ' 小时';
    }
  };

  /* ==========================================================================
   * 7. Local storage — namespaced, quota-safe, never throws
   * ======================================================================= */

  var STORE_PREFIX = 'markdowner.v1.';

  var store = {
    available: (function () {
      try {
        var probe = STORE_PREFIX + '__probe__';
        global.localStorage.setItem(probe, '1');
        global.localStorage.removeItem(probe);
        return true;
      } catch (err) {
        return false;
      }
    })(),

    get: function (key, fallback) {
      if (!store.available) return fallback;
      try {
        var raw = global.localStorage.getItem(STORE_PREFIX + key);
        if (raw === null || raw === undefined) return fallback;
        return JSON.parse(raw);
      } catch (err) {
        return fallback;
      }
    },

    set: function (key, value) {
      if (!store.available) return false;
      try {
        global.localStorage.setItem(STORE_PREFIX + key, JSON.stringify(value));
        return true;
      } catch (err) {
        // Quota exceeded: surface it instead of failing silently.
        // Emitted as an event (not a direct call) so this module never has to
        // import the UI layer — that would be a circular dependency.
        try {
          global.dispatchEvent(new CustomEvent('md:storage-error', {
            detail: { key: key, error: err }
          }));
        } catch (inner) { /* CustomEvent unavailable — the boolean return still reports failure */ }
        return false;
      }
    },

    remove: function (key) {
      if (!store.available) return;
      try { global.localStorage.removeItem(STORE_PREFIX + key); } catch (err) { /* ignore */ }
    },

    clearAll: function () {
      if (!store.available) return;
      try {
        var doomed = [];
        for (var i = 0; i < global.localStorage.length; i++) {
          var key = global.localStorage.key(i);
          if (key && key.indexOf(STORE_PREFIX) === 0) doomed.push(key);
        }
        doomed.forEach(function (key) { global.localStorage.removeItem(key); });
      } catch (err) { /* ignore */ }
    },

    usedBytes: function () {
      if (!store.available) return 0;
      var total = 0;
      try {
        for (var i = 0; i < global.localStorage.length; i++) {
          var key = global.localStorage.key(i);
          if (key && key.indexOf(STORE_PREFIX) === 0) {
            total += (global.localStorage.getItem(key) || '').length + key.length;
          }
        }
      } catch (err) { /* ignore */ }
      return total * 2; // UTF-16 code units
    }
  };

  /* ==========================================================================
   * 8. Clipboard, files, downloads
   * ======================================================================= */

  function copyText(text) {
    if (global.navigator.clipboard && global.isSecureContext) {
      return global.navigator.clipboard.writeText(text);
    }
    return new Promise(function (resolve, reject) {
      var scratch = el('textarea', {
        value: text,
        style: 'position:fixed;top:-1000px;left:-1000px;opacity:0;'
      });
      document.body.appendChild(scratch);
      scratch.select();
      var ok = false;
      try { ok = document.execCommand('copy'); } catch (err) { ok = false; }
      document.body.removeChild(scratch);
      ok ? resolve() : reject(new Error('execCommand copy failed'));
    });
  }

  function readAsText(file) {
    return new Promise(function (resolve, reject) {
      var reader = new FileReader();
      reader.onload = function () { resolve(String(reader.result)); };
      reader.onerror = function () { reject(reader.error || new Error('read failed')); };
      reader.readAsText(file, 'utf-8');
    });
  }

  function download(filename, content, mime) {
    var blob = content instanceof Blob ? content : new Blob([content], { type: mime || 'text/plain;charset=utf-8' });
    var url = URL.createObjectURL(blob);
    var anchor = el('a', { href: url, download: filename, style: 'display:none' });
    document.body.appendChild(anchor);
    anchor.click();
    setTimeout(function () {
      document.body.removeChild(anchor);
      URL.revokeObjectURL(url);
    }, 1000);
  }

  function safeFilename(title, fallback) {
    var name = String(title || '')
      .replace(/[\\/:*?"<>|\u0000-\u001f]/g, '-')
      .replace(/\s+/g, ' ')
      .trim();
    if (!name) name = fallback || 'untitled';
    if (name.length > 80) name = name.slice(0, 80);
    return name;
  }

  /* ==========================================================================
   * 9. Text measurement — used by the editor gutter to size wrapped lines
   * ======================================================================= */

  var measureContext = null;

  function contextFor(font) {
    if (!measureContext) {
      var canvas = document.createElement('canvas');
      measureContext = canvas.getContext('2d');
    }
    if (font && measureContext.font !== font) measureContext.font = font;
    return measureContext;
  }

  function textWidth(text, font) {
    try {
      return contextFor(font).measureText(text).width;
    } catch (err) {
      return String(text).length * 8;
    }
  }

  /** Expand tabs the way `tab-size: 4` renders them, for measurement only. */
  function expandTabs(text, tabSize) {
    tabSize = tabSize || 4;
    if (text.indexOf('\t') === -1) return text;
    return text.replace(/[^\t]*\t/g, function (chunk) {
      var fill = tabSize - (chunk.length % tabSize);
      return chunk.slice(0, -1) + new Array(fill + 1).join(' ');
    });
  }

  /* ==========================================================================
   * 10. Selection geometry helpers (textarea based)
   * ======================================================================= */

  function lineStartOffsets(text) {
    var offsets = [0];
    for (var i = 0; i < text.length; i++) {
      if (text.charCodeAt(i) === 10) offsets.push(i + 1);
    }
    return offsets;
  }

  /** 0-based line number containing `index`. */
  function lineAt(offsets, index) {
    var lo = 0;
    var hi = offsets.length - 1;
    while (lo < hi) {
      var mid = (lo + hi + 1) >> 1;
      if (offsets[mid] <= index) lo = mid; else hi = mid - 1;
    }
    return lo;
  }

  function isWordChar(ch) {
    return !!ch && /[A-Za-z0-9_\u00c0-\uffff]/.test(ch);
  }

  /** Fuzzily expand a collapsed selection to the surrounding word. */
  function wordAt(text, index) {
    var start = index;
    var end = index;
    while (start > 0 && isWordChar(text.charAt(start - 1))) start--;
    while (end < text.length && isWordChar(text.charAt(end))) end++;
    return { start: start, end: end, text: text.slice(start, end) };
  }

  /* ==========================================================================
   * 11. Export surface
   * ======================================================================= */

  MD.dom = {
    $: $, $$: $$, el: el, append: append, clear: clear,
    on: on, delegate: delegate, show: show, setText: setText, raf: raf
  };

  MD.util = {
    escapeHtml: escapeHtml,
    escapeAttr: escapeAttr,
    slugify: slugify,
    truncate: truncate,
    ordinal: ordinal,
    firstMeaningfulLine: firstMeaningfulLine,
    debounce: debounce,
    throttle: throttle,
    clamp: clamp,
    uid: uid,
    isMac: isMac,
    isTouch: isTouch,
    deepClone: deepClone,
    prefersReducedMotion: prefersReducedMotion,
    countCJK: countCJK,
    stats: stats,
    fmt: fmt,
    store: store,
    copyText: copyText,
    readAsText: readAsText,
    download: download,
    safeFilename: safeFilename,
    textWidth: textWidth,
    expandTabs: expandTabs,
    lineStartOffsets: lineStartOffsets,
    lineAt: lineAt,
    wordAt: wordAt
  };
})(window);
