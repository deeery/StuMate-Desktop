/* MarkDowner — preview pane, outline, and two-way scroll sync.
 *
 * The preview is a render target, not a document. It is rebuilt from source on
 * a debounce; the only subtlety is scroll sync, which needs a line-anchored
 * mapping between the two panes rather than a naive ratio.
 *
 * Why: a document's source and its rendering have wildly different heights per
 * line (one `#` line becomes a 44px heading, one blank source line becomes
 * nothing). A pure ratio drifts badly on exactly the documents people write.
 * Instead we tag each top-level rendered block with the source line it came
 * from, then interpolate between the two nearest anchors.
 */
(function (MD, global) {
  'use strict';

  var el = MD.dom.el;
  var on = MD.dom.on;
  var BLOCK_TAGS = 'p, h1, h2, h3, h4, h5, h6, ul, ol, blockquote, pre, table, hr, figure, dl, details, div';

  /* ==========================================================================
   * 1. Source-line anchoring
   *
   * marked does not tell us where a block came from, so we recover it by
   * walking the source and the rendered blocks in parallel. Both are in
   * document order, so a single forward scan with a fuzzy match is enough and
   * stays O(n).
   * ======================================================================= */

  /**
   * Heuristic: match each rendered block to the first source line at or after
   * the cursor whose text could have produced it. Whitespace, markers and
   * inline syntax are all stripped before comparing, so `# Title` matches
   * `<h1>Title</h1>`.
   */
  function anchorBlocks(doc, source) {
    var lines = source.split('\n');
    var normalised = lines.map(normalizeForMatch);
    var blocks = doc.querySelectorAll(BLOCK_TAGS);
    var cursor = 0;

    Array.prototype.forEach.call(blocks, function (block) {
      // Only direct children of the document get anchors; nested blocks inherit
      // their parent's position.
      var target = normalizeForMatch(block.textContent);
      if (!target) return;

      for (var i = cursor; i < lines.length; i++) {
        var candidate = normalised[i];
        if (!candidate) continue;
        // A block may span several source lines (a paragraph, a table), so an
        // exact prefix match is the right test rather than equality.
        if (candidate === target || target.indexOf(candidate) === 0 ||
            candidate.indexOf(target) === 0 || similar(candidate, target)) {
          block.dataset.srcLine = String(i);
          cursor = i + 1;
          return;
        }
      }
      // No confident match: fall back to the cursor so ordering is preserved.
      block.dataset.srcLine = String(cursor);
    });
  }

  function normalizeForMatch(text) {
    return String(text)
      .replace(/[\s\u00a0]+/g, '')
      .replace(/[*_~`#>|\-+=\[\]()!]/g, '')
      .replace(/\\/g, '')
      .toLowerCase();
  }

  function similar(a, b) {
    if (!a || !b) return false;
    var probe = Math.min(a.length, b.length, 24);
    if (probe < 4) return false;
    return a.slice(0, probe) === b.slice(0, probe);
  }

  /* ==========================================================================
   * 2. Preview
   * ======================================================================= */

  function create(options) {
    var opts = options || {};

    var root = el('div', { className: 'preview', tabIndex: '0' });
    var doc = el('article', { className: 'preview__doc' });
    root.appendChild(doc);

    var api = {
      root: root,
      doc: doc,
      render: render,
      setSource: setSource,
      scrollToRatio: scrollToRatio,
      scrollToLine: scrollToLine,
      scrollToId: scrollToId,
      ratio: ratio,
      setSyncFlash: setSyncFlash,
      destroy: destroy
    };

    var source = opts.source || '';
    var headings = [];
    var anchors = [];   // sorted [{ srcLine, offsetTop }]
    var customHandlers = Object.create(null);
    var pendingHandle = null;
    var lastRenderKey = null;

    function emit(type, a) {
      var list = customHandlers[type];
      if (!list) return;
      list.forEach(function (handler) {
        try { handler(a); } catch (err) {
          if (global.console) global.console.error('[MarkDowner] preview listener failed', err);
        }
      });
    }

    function setSource(text, meta) {
      source = text === null || text === undefined ? '' : String(text);
      schedule(meta);
    }

    /** Debounced re-render. Typing must never block on the parser. */
    function schedule(meta) {
      var immediate = meta && meta.immediate;
      if (pendingHandle) global.clearTimeout(pendingHandle);
      if (immediate) { pendingHandle = null; render(); return; }
      pendingHandle = global.setTimeout(function () {
        pendingHandle = null;
        render();
      }, meta && meta.delay !== undefined ? meta.delay : 140);
    }

    function render() {
      var t0 = performance.now();
      var result = MD.md.render(source);
      var scrollRatioBefore = ratio();

      if (result.html !== lastRenderKey) {
        lastRenderKey = result.html;
        doc.innerHTML = result.html;
        headings = result.headings;
        anchorBlocks(doc, source);
        measureAnchors();
        markEmpty(result.empty);
      } else {
        headings = result.headings;
      }

      emit('render', {
        headings: headings,
        ms: performance.now() - t0,
        empty: result.empty
      });
      // Hold the reading position across a re-render: a parse must never
      // teleport the reader back to the top.
      if (scrollRatioBefore > 0.001) scrollToRatio(scrollRatioBefore);
      return result;
    }

    function markEmpty(isEmpty) {
      doc.classList.toggle('is-empty', isEmpty);
      if (!isEmpty) return;
      var placeholder = el('div', { className: 'preview__placeholder' }, [
        el('div', { className: 'preview__placeholder-icon' }, MD.icon('fileText', { size: 'xl' })),
        el('p', { className: 'preview__placeholder-title', text: '预览会显示在这里' }),
        el('p', {
          className: 'preview__placeholder-text',
          text: '开始输入 Markdown，右侧会实时渲染。'
        })
      ]);
      doc.appendChild(placeholder);
    }

    /**
     * Build the line->offset ladder used for sync. Read once after each render
     * and after images settle, rather than on every scroll event — layout reads
     * during scroll are what make editors feel sticky.
     */
    function measureAnchors() {
      var blocks = doc.querySelectorAll('[data-src-line]');
      anchors = [];
      Array.prototype.forEach.call(blocks, function (block) {
        var line = Number(block.dataset.srcLine);
        if (!isFinite(line)) return;
        anchors.push({ srcLine: line, offsetTop: block.offsetTop });
      });
      anchors.sort(function (a, b) { return a.srcLine - b.srcLine; });
    }

    /** Re-measure once lazy images have loaded and shifted the layout. */
    var remeasure = MD.util.debounce(function () {
      if (root.isConnected) measureAnchors();
    }, 200);
    doc.addEventListener('load', remeasure, true);

    /* ------------------------------------------------------------ scrolling */

    function ratio() {
      var max = root.scrollHeight - root.clientHeight;
      return max > 0 ? root.scrollTop / max : 0;
    }

    function scrollToRatio(value) {
      var max = root.scrollHeight - root.clientHeight;
      if (max <= 0) return;
      setScrollTop(value * max);
    }

    /** Find the preview offset that corresponds to a source line. */
    function offsetForLine(line) {
      if (!anchors.length) return null;
      if (line <= anchors[0].srcLine) return anchors[0].offsetTop;
      var last = anchors[anchors.length - 1];
      if (line >= last.srcLine) return last.offsetTop;

      // Binary search the ladder, then interpolate between the bracketing
      // anchors so a long paragraph scrolls smoothly rather than in jumps.
      var low = 0;
      var high = anchors.length - 1;
      while (high - low > 1) {
        var mid = (low + high) >> 1;
        if (anchors[mid].srcLine <= line) low = mid;
        else high = mid;
      }
      var a = anchors[low];
      var b = anchors[high];
      var span = b.srcLine - a.srcLine;
      if (span <= 0) return a.offsetTop;
      var t = (line - a.srcLine) / span;
      return a.offsetTop + t * (b.offsetTop - a.offsetTop);
    }

    /**
     * The inverse of offsetForLine: which source line sits at a preview offset?
     * Without this the preview -> editor direction can only fall back to a
     * scroll ratio, and a ratio is meaningless as soon as the two panes have
     * different heights (a long code block, a wide table).
     */
    function sourceLineAt(top) {
      if (!anchors.length) return null;
      if (top <= anchors[0].offsetTop) return anchors[0].srcLine;
      var last = anchors[anchors.length - 1];
      if (top >= last.offsetTop) return last.srcLine;

      var low = 0;
      var high = anchors.length - 1;
      while (high - low > 1) {
        var mid = (low + high) >> 1;
        if (anchors[mid].offsetTop <= top) low = mid;
        else high = mid;
      }
      var a = anchors[low];
      var b = anchors[high];
      var span = b.offsetTop - a.offsetTop;
      if (span <= 0) return a.srcLine;
      var t = (top - a.offsetTop) / span;
      return Math.round(a.srcLine + t * (b.srcLine - a.srcLine));
    }

    /**
     * Jump instantly.
     *
     * `.preview` declares `scroll-behavior: smooth`, and per the CSSOM View
     * spec that property applies to the CSSOM scrolling APIs too — so both
     * `scrollTo({behavior:'auto'})` and a bare `root.scrollTop = x` get
     * animated by the browser. A sync write that takes hundreds of frames to
     * land streams scroll events long past any echo guard, which is what made
     * the two panes fight each other.
     *
     * `scrollTo({behavior:'instant'})` is tempting but not portable: an engine
     * that predates the enum value coerces it to 'auto' and scrolls smoothly
     * anyway, without throwing, so the bug would return silently. Overriding
     * the property inline is the one escape that holds on every engine that
     * implements scroll-behavior at all, because an inline declaration beats
     * the stylesheet.
     */
    function setScrollTop(value) {
      var top = Math.max(0, value);
      var previous = root.style.scrollBehavior;
      root.style.scrollBehavior = 'auto';
      try {
        root.scrollTo({ top: top, behavior: 'auto' });
      } finally {
        root.style.scrollBehavior = previous;
      }
      return root.scrollTop;
    }

    function scrollToLine(line, options2) {
      var target = offsetForLine(line);
      if (target === null) return;
      // 'auto' is not "instant" here — it defers to the pane's own
      // scroll-behavior, which is smooth. Ask for 'instant' explicitly.
      var behavior = (options2 && options2.smooth && !MD.util.prefersReducedMotion()) ? 'smooth' : 'instant';
      root.scrollTo({ top: Math.max(0, target - 16), behavior: behavior });
    }

    function scrollToId(id) {
      var node = doc.querySelector('#' + cssEscape(id));
      if (!node) return false;
      var behavior = MD.util.prefersReducedMotion() ? 'instant' : 'smooth';
      root.scrollTo({ top: Math.max(0, node.offsetTop - 16), behavior: behavior });
      flash(node);
      return true;
    }

    function flash(node) {
      node.setAttribute('data-sync-flash', 'true');
      global.setTimeout(function () { node.removeAttribute('data-sync-flash'); }, 900);
    }

    /** Briefly outline the block a sync landed on, so the jump is legible. */
    function setSyncFlash(enabled) {
      doc.dataset.syncFlash = enabled ? 'on' : 'off';
    }

    function cssEscape(value) {
      if (global.CSS && global.CSS.escape) return global.CSS.escape(value);
      return String(value).replace(/["\\]/g, '\\$&');
    }

    function onCustom(type, handler) {
      (customHandlers[type] || (customHandlers[type] = [])).push(handler);
    }

    api.on = onCustom;
    api.getHeadings = function () { return headings; };
    api.remeasure = measureAnchors;
    api.offsetForLine = offsetForLine;
    api.sourceLineAt = sourceLineAt;
    api.setScrollTop = setScrollTop;

    if (source) schedule({ immediate: true });

    function destroy() {
      if (pendingHandle) global.clearTimeout(pendingHandle);
      customHandlers = Object.create(null);
    }

    return api;
  }

  /* ==========================================================================
   * 3. Outline
   * ======================================================================= */

  /**
   * Build the outline list. Kept separate from the pane so the caller decides
   * whether to mount it in the sidebar, the right rail, or nowhere.
   */
  function buildOutline(headings, options2) {
    var opts = options2 || {};
    var list = el('div', { className: 'outline__list', role: 'tree' });

    if (!headings || !headings.length) {
      list.appendChild(el('p', { className: 'outline__empty', text: '文档中还没有标题' }));
      return list;
    }

    // Normalise so a document that starts at h3 is not indented twice.
    var minLevel = headings.reduce(function (min, h) {
      return Math.min(min, h.level);
    }, 6);

    headings.forEach(function (heading) {
      var level = MD.util.clamp(heading.level - minLevel + 1, 1, 6);
      var item = el('button', {
        className: 'outline-item outline-item--h' + level,
        type: 'button',
        title: heading.text,
        dataset: { id: heading.id, level: String(heading.level) }
      });
      item.appendChild(el('span', { className: 'outline-item__text', text: heading.text }));
      if (opts.onSelect) {
        item.addEventListener('click', function () { opts.onSelect(heading); });
      }
      list.appendChild(item);
    });

    return list;
  }

  /* ==========================================================================
   * 4. Wire editor <-> preview scroll sync
   * ======================================================================= */

  /**
   * Two-way sync with an explicit owner.
   *
   * The old design suppressed each pane's echo for a fixed 90 ms. That is
   * fragile in two ways: `.preview` declares `scroll-behavior: smooth`, so a
   * programmatic jump streams scroll events for hundreds of milliseconds — far
   * past any suppression window — and each pane's echo then re-armed the other
   * one, so the pair could oscillate and lock against the user's own scroll.
   *
   * The rule now is ownership, not timing. Whichever pane the user last
   * actually drove is the owner: it is the only source of truth, the other
   * pane follows, and a follower is never allowed to push back. Ownership is
   * claimed from real input events (wheel/touch/pointer/keys), never inferred
   * from a scroll event, because a scroll can also be caused by layout
   * shifting under a lazy image — which must not steal the owner's scroll.
   */
  function linkScroll(editor, preview, options2) {
    var opts = options2 || {};
    var enabled = opts.enabled !== false;

    // 'editor' | 'preview' — the pane the user is driving. Defaults to the
    // editor, which is the pane that owns the document being edited.
    var owner = 'editor';
    // The offsets we last wrote ourselves. Recorded so a write can never be
    // mistaken for a user gesture, independent of how long it takes to land.
    var wrote = { editor: null, preview: null };

    function isEcho(side, value) {
      var expected = wrote[side];
      if (expected === null) return false;
      wrote[side] = null;
      return Math.abs(value - expected) <= 1;
    }

    /** The owner drives; the follower is written and never writes back. */
    function driveNow(side) {
      if (!enabled) return;
      if (side === 'preview') {
        // Line-anchored: a ratio is meaningless once the two panes have
        // different heights (a long code block, a wide table).
        var line = topVisibleLine(editor);
        if (line === null || !preview.offsetForLine) {
          wrote.preview = preview.setScrollTop(
            editor.scrollRatios().top * (preview.root.scrollHeight - preview.root.clientHeight)
          );
        } else {
          wrote.preview = preview.setScrollTop(Math.max(0, preview.offsetForLine(line) - 16));
        }
      } else {
        // Inverse mapping: read the source line under the preview's top edge
        // and land the editor on that exact line.
        var source = preview.sourceLineAt ? preview.sourceLineAt(preview.root.scrollTop) : null;
        if (source === null) {
          editor.scroll.scrollTop = preview.ratio() * (editor.scroll.scrollHeight - editor.scroll.clientHeight);
        } else {
          editor.scrollToLine(source);
        }
        wrote.editor = editor.scroll.scrollTop;
      }
    }

    // Reading getComputedStyle + searching the anchor ladder on every scroll
    // frame is what makes a long document feel sticky, so coalesce the writes.
    // Both edges are kept: the leading one makes a jump land immediately, the
    // trailing one lets a gesture settle on its final position.
    var driveTo = MD.util.throttle(driveNow, 32);

    /** Hand ownership to a pane. `fromGesture` marks a real user interaction. */
    function claim(side, fromGesture) {
      if (owner === side) {
        // Same owner: only a genuine gesture is worth re-driving, so that a
        // stray scroll never re-syncs a resting pane.
        if (fromGesture) driveTo(side === 'editor' ? 'preview' : 'editor');
        return;
      }
      owner = side;
      driveTo(side === 'editor' ? 'preview' : 'editor');
    }

    function onEditorScroll() {
      if (!enabled || owner !== 'editor') return;   // follower echo — never drives
      if (isEcho('editor', editor.scroll.scrollTop)) return;
      driveTo('preview');
    }

    function onPreviewScroll() {
      if (!enabled || owner !== 'preview') return;  // follower echo — never drives
      if (isEcho('preview', preview.root.scrollTop)) return;
      driveTo('editor');
    }

    // Ownership comes from input, not from scrolling: a scroll can also be
    // produced by layout shifting, which must not steal the owner's scroll.
    function claimFrom(side) {
      return function () { if (enabled) claim(side, true); };
    }
    ['wheel', 'touchstart', 'pointerdown', 'keydown'].forEach(function (type) {
      on(editor.scroll, type, claimFrom('editor'));
      on(preview.root, type, claimFrom('preview'));
    });

    editor.on('scroll', onEditorScroll);
    on(preview.root, 'scroll', onPreviewScroll);

    return {
      setEnabled: function (next) {
        enabled = next;
        // Re-enabling must not replay a stale offset from before the toggle.
        if (enabled) wrote = { editor: null, preview: null };
      },
      isEnabled: function () { return enabled; },
      /** Hand ownership over silently (anchor jumps, outline jumps). The
       *  jump's own scroll event then does the driving, so there is no
       *  wasted intermediate move first. */
      claim: function (side) { if (owner !== side) owner = side; },
      owner: function () { return owner; },
      fromEditor: function () { owner = 'editor'; driveTo('preview'); },
      fromPreview: function () { owner = 'preview'; driveTo('editor'); },
      measure: function () { wrote = { editor: null, preview: null }; }
    };
  }

  /** Which source line is at the top of the editor viewport? */
  function topVisibleLine(editor) {
    // Read from the canvas: that is where --editor-line-height-px is declared,
    // because the gutter (a sibling of the text layer) needs it too.
    var styles = global.getComputedStyle(editor.scroll.querySelector('.editor__canvas'));
    var padTop = parseFloat(styles.paddingTop) || 0;
    var lineHeight = parseFloat(styles.getPropertyValue('--editor-line-height-px')) || 24;
    var top = editor.scroll.scrollTop - padTop;
    var line = Math.floor(top / lineHeight);
    var total = editor.getValue().split('\n').length;
    return MD.util.clamp(line, 0, Math.max(0, total - 1));
  }

  MD.Preview = {
    create: create,
    buildOutline: buildOutline,
    linkScroll: linkScroll,
    topVisibleLine: topVisibleLine
  };
})(window.MD = window.MD || {}, window);
