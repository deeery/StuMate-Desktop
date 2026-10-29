/* MarkDowner — the editing surface.
 *
 * Architecture: a real <textarea> sits on top of a syntax-highlighted backdrop
 * (see src/styles/editor.css for why). The contract this module must uphold is
 * pixel alignment — the caret the OS draws must land exactly on the glyphs we
 * paint underneath. Three things make that true:
 *
 *   1. Both layers share identical padding/font/line-height/white-space, so the
 *      CSS never introduces a per-layer offset.
 *   2. The gutter mirrors the backdrop row for row by reading the height the
 *      backdrop actually painted, so a line that wraps is just as tall in the
 *      gutter as it is in the text.
 *   3. The textarea is the ONLY scrolling element's content; both layers live
 *      inside .editor__scroll, so they cannot scroll independently.
 *
 * Rendering is incremental: lines are cached by content hash and re-tokenized
 * only from the first changed line onward. Typing one character in a 5000-line
 * document therefore costs one tokenization, not five thousand.
 */
(function (MD, global) {
  'use strict';

  var el = MD.dom.el;
  var on = MD.dom.on;
  var syntax = MD.syntax;

  var EMPTY_LINE = '<span class="ln"></span>';

  /* ==========================================================================
   * 1. Constructor
   * ======================================================================= */

  /**
   * create(options) -> editor instance
   *   options.value      initial text
   *   options.onChange   (value, meta) => void
   *   options.onScroll   (ratios) => void
   *   options.onCursor   (info) => void
   *   options.placeholder
   */
  function create(options) {
    var opts = options || {};

    var root = el('div', { className: 'editor' });
    var scroll = el('div', { className: 'editor__scroll' });
    var canvas = el('div', { className: 'editor__canvas' });

    var gutter = el('div', { className: 'editor__gutter', 'aria-hidden': 'true' });
    var gutterInner = el('div', { className: 'editor__gutter-inner' });
    gutter.appendChild(gutterInner);

    // The row-grid data-* knobs live on the canvas, not the layer: the
    // line-number gutter is a sibling of the layer, so anything the gutter must
    // agree on (font size, line height, wrapping) has to be declared one level
    // up where custom properties are visible to both columns.
    var layer = el('div', { className: 'editor__layer' });
    canvas.dataset.fontSize = '14';
    canvas.dataset.lineHeight = 'normal';
    canvas.dataset.wrap = 'on';
    var highlight = el('div', { className: 'editor__highlight', 'aria-hidden': 'true' });
    var input = el('textarea', {
      className: 'editor__input',
      spellcheck: 'false',
      autocomplete: 'off',
      autocapitalize: 'off',
      autocorrect: 'off',
      placeholder: opts.placeholder || '开始写作…',
      'aria-label': 'Markdown 编辑器'
    });
    input.setAttribute('wrap', 'soft');

    layer.appendChild(highlight);
    layer.appendChild(input);
    canvas.appendChild(gutter);
    canvas.appendChild(layer);
    scroll.appendChild(canvas);
    root.appendChild(scroll);

    var api = {
      root: root,
      input: input,
      highlight: highlight,
      gutter: gutter,
      layer: layer,
      scroll: scroll,
      getValue: function () { return input.value; },
      setValue: setValue,
      setSelection: setSelection,
      getSelection: getSelection,
      replaceRange: replaceRange,
      wrapSelection: wrapSelection,
      insertAtCursor: insertAtCursor,
      transformLines: transformLines,
      focus: function () { input.focus(); },
      isFocused: function () { return document.activeElement === input; },
      setOption: setOption,
      setSearch: setSearch,
      scrollToLine: scrollToLine,
      cursorInfo: cursorInfo,
      scrollRatios: scrollRatios,
      destroy: destroy,
      on: function (type, handler) { return onCustom(type, handler); }
    };

    /* ---------------------------------------------------------------- state */

    var lines = [''];
    var htmlCache = [];       // cached html per source line
    var searchHits = [];      // [{ line, start, end }]
    var currentHit = -1;
    var customHandlers = Object.create(null);
    var renderHandle = null;

    /** Line height in px, read from the canvas where the grid vars are declared. */
    function lineHeightPx() {
      var styles = global.getComputedStyle(canvas);
      var size = parseFloat(styles.getPropertyValue('--editor-font-size')) ||
        parseFloat(global.getComputedStyle(layer).fontSize) || 14;
      var ratio = parseFloat(styles.getPropertyValue('--editor-line-height')) || 1.75;
      return size * ratio;
    }

    /* ------------------------------------------------------------ measuring */

    /**
     * How many visual rows a painted backdrop line occupies.
     *
     * This reads the element instead of predicting it. Canvas `measureText` was
     * wrong here: a backdrop line is a run of token spans, and `**bold**` or a
     * table row paints at a heavier weight than the raw string implies, so the
     * estimated width came out too small. One line that wrapped in the backdrop
     * but was estimated as single-height shifted every line number below it —
     * the gutter drifted further down the document the longer it got.
     *
     * The spans are already in the document when this runs, so the first read
     * flushes layout once and the remaining reads are served from that layout.
     * Row counts are returned rather than pixel heights so the caller can
     * multiply by the exact fractional line height instead of accumulating the
     * integer rounding that `offsetHeight` reports.
     */
    function rowsFor(span, lineHeight) {
      if (!span) return 1;
      var painted = span.offsetHeight;
      if (!painted || !lineHeight) return 1;
      return Math.max(1, Math.round(painted / lineHeight));
    }

    /* ------------------------------------------------------------- rendering */

    /**
     * Incrementally rebuild the backdrop. `changedFrom` lets us reuse every
     * cached line above the edit point.
     */
    function render(changedFrom) {
      var wrapOn = canvas.dataset.wrap !== 'off';

      var result = syntax.highlightLines(lines, null);
      var html = result.lines;

      var fragment = document.createDocumentFragment();
      for (var i = 0; i < html.length; i++) {
        var span = document.createElement('span');
        span.className = 'ln';
        span.innerHTML = html[i] || '';
        fragment.appendChild(span);
      }
      highlight.textContent = '';
      highlight.appendChild(fragment);
      htmlCache = html;

      renderGutter(wrapOn, highlight.children);

      // Focus mode dims everything except the paragraph under the caret.
      if (root.classList.contains('editor--focus')) applyFocusLine();
    }

    /**
     * The gutter must mirror the backdrop row for row. When wrapping is off each
     * line is exactly one row; when it is on the line numbers inherit the height
     * the backdrop actually painted, read from the live elements.
     */
    function renderGutter(wrapOn, spans) {
      var lineHeight = lineHeightPx();
      var nodes = document.createDocumentFragment();

      for (var n = 0; n < lines.length; n++) {
        var no = document.createElement('span');
        no.className = 'editor__line-no';
        no.textContent = String(n + 1);
        if (wrapOn) {
          var rows = rowsFor(spans && spans[n], lineHeight);
          if (rows > 1) no.style.height = (rows * lineHeight) + 'px';
        }
        nodes.appendChild(no);
      }
      gutterInner.textContent = '';
      gutterInner.appendChild(nodes);
    }

    function applyFocusLine() {
      var info = cursorInfo();
      var children = highlight.children;
      for (var i = 0; i < children.length; i++) {
        children[i].classList.toggle('is-focus-line', i === info.line);
      }
    }

    /** Coalesce renders to one per frame — a fast typist must not thrash layout. */
    function scheduleRender(changedFrom) {
      if (renderHandle) {
        if (typeof changedFrom === 'number' && changedFrom < renderHandle.from) {
          renderHandle.from = changedFrom;
        }
        return;
      }
      renderHandle = { from: changedFrom };
      MD.dom.raf(function () {
        var pending = renderHandle;
        renderHandle = null;
        var t0 = performance.now();
        render(pending ? pending.from : 0);
        stats.renderMs = performance.now() - t0;
        stats.lines = lines.length;
        emit('render', stats);
      });
    }

    var stats = { lines: 0, renderMs: 0 };

    /* -------------------------------------------------------- value plumbing */

    function setValue(text, meta) {
      var next = text === null || text === undefined ? '' : String(text);
      if (input.value === next) {
        refreshLines();
        scheduleRender(0);
        return;
      }
      // Preserve the caret proportionally when swapping documents, so switching
      // away and back lands you roughly where you were.
      input.value = next;
      refreshLines();
      scheduleRender(0);
      emit('change', next, meta || { source: 'set' });
    }

    function refreshLines() {
      lines = input.value.split('\n');
    }

    /** Re-tokenize only from the first line that actually differs. */
    function syncFromText(meta) {
      var next = input.value.split('\n');
      var from = 0;
      var max = Math.min(lines.length, next.length);
      while (from < max && lines[from] === next[from]) from += 1;
      var changed = from < lines.length || from < next.length;
      lines = next;
      if (changed) {
        // Gutter heights are read from the painted backdrop, so nothing has to
        // be invalidated here — the repaint measures the new spans directly.
        scheduleRender(from);
      }
      emit('change', input.value, meta || {});
    }

    /* ------------------------------------------------------------- events */

    function emit(type, a, b) {
      var list = customHandlers[type];
      if (list) {
        for (var i = 0; i < list.length; i++) {
          try { list[i](a, b); } catch (err) { reportError(err, type); }
        }
      }
      if (type === 'change' && opts.onChange) {
        try { opts.onChange(a, b); } catch (err) { reportError(err, 'onChange'); }
      }
    }

    function reportError(err, where) {
      // A listener throwing must not take the editor down with it.
      if (global.console) global.console.error('[MarkDowner] editor listener failed (' + where + ')', err);
    }

    function onCustom(type, handler) {
      (customHandlers[type] || (customHandlers[type] = [])).push(handler);
      return function off() {
        var list = customHandlers[type] || [];
        var at = list.indexOf(handler);
        if (at !== -1) list.splice(at, 1);
      };
    }

    on(input, 'input', function (event) {
      syncFromText({ source: 'input', inputType: event.inputType });
      emit('cursor');
    });

    var selectSync = MD.util.throttle(function () {
      emit('cursor');
      if (root.classList.contains('editor--focus')) applyFocusLine();
      scrollCaretIntoView();
    }, 60);

    on(input, 'keyup', selectSync);
    on(input, 'click', function () { emit('cursor'); applyFocusLineIfNeeded(); });
    on(input, 'select', function () { emit('cursor'); });
    on(input, 'focus', function () {
      emit('focus', true);
      applyFocusLineIfNeeded();
    });
    on(input, 'blur', function () { emit('focus', false); });

    function applyFocusLineIfNeeded() {
      if (root.classList.contains('editor--focus')) applyFocusLine();
    }

    on(input, 'scroll', function () {
      // The textarea does not scroll (its ancestor does); forward the canvas
      // scroll instead. This handler covers the case where a browser still
      // scrolls the textarea itself.
      if (input.scrollTop || input.scrollLeft) {
        scroll.scrollTop += input.scrollTop;
        scroll.scrollLeft += input.scrollLeft;
        input.scrollTop = 0;
        input.scrollLeft = 0;
      }
    });

    on(scroll, 'scroll', function () {
      emit('scroll', scrollRatios());
    });

    // --- Tab inserts instead of moving focus -----------------------------
    on(input, 'keydown', function (event) {
      if (event.key === 'Tab' && !event.ctrlKey && !event.metaKey && !event.altKey) {
        event.preventDefault();
        if (event.shiftKey) outdentSelection();
        else indentSelection();
        return;
      }
      if (event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
        autoContinueList(event);
      }
    });

    // --- Drag & drop a .md file onto the editor --------------------------
    on(root, 'dragover', function (event) {
      if (!hasFiles(event)) return;
      event.preventDefault();
      root.dataset.drop = 'true';
    });
    on(root, 'dragleave', function (event) {
      if (event.target === root) delete root.dataset.drop;
    });
    on(root, 'drop', function (event) {
      delete root.dataset.drop;
      if (!hasFiles(event)) return;
      event.preventDefault();
      var file = event.dataTransfer.files[0];
      MD.util.readAsText(file).then(function (text) {
        emit('file-drop', { name: file.name, text: text });
      }).catch(function () {
        MD.ui.toast.error('无法读取文件', file.name);
      });
    });

    function hasFiles(event) {
      var dt = event.dataTransfer;
      if (!dt) return false;
      if (dt.types && Array.prototype.indexOf.call(dt.types, 'Files') === -1 &&
          dt.types.indexOf && dt.types.indexOf('Files') === -1) return false;
      return true;
    }

    /* --------------------------------------------------- list continuation */

    var RE_LINE_PREFIX = /^(\s*)([-*+]|\d{1,9}[.)])(\s+)(\[[ xX]\]\s+)?/;

    /**
     * Enter inside a list continues it; Enter on an empty item ends it. This is
     * the single most-missed behaviour in hand-rolled editors.
     */
    function autoContinueList(event) {
      var pos = input.selectionStart;
      if (pos !== input.selectionEnd) return;
      var text = input.value;
      var lineStart = text.lastIndexOf('\n', pos - 1) + 1;
      var lineEnd = text.indexOf('\n', pos);
      if (lineEnd === -1) lineEnd = text.length;
      var line = text.slice(lineStart, lineEnd);
      var match = RE_LINE_PREFIX.exec(line);
      if (!match) return;

      event.preventDefault();
      var body = line.slice(match[0].length);
      var indent = match[1];
      var marker = match[2];
      var spacing = match[3] || ' ';
      var checkbox = match[4] || '';

      if (!body.trim()) {
        // Empty item: clear the marker rather than adding another.
        replaceRange(lineStart, lineEnd, '');
        return;
      }

      var nextMarker = marker;
      if (/^\d/.test(marker)) {
        nextMarker = (parseInt(marker, 10) + 1) + marker.slice(-1);
      }
      var nextBox = checkbox ? '[ ] ' : '';
      // Ordered lists indent by marker width so the text column stays aligned.
      var prefix = /^\d/.test(nextMarker)
        ? indent + ' '.repeat(marker.length - nextMarker.length) + nextMarker + spacing + nextBox
        : indent + nextMarker + spacing + nextBox;
      replaceRange(pos, pos, '\n' + prefix);
    }

    /* ------------------------------------------------------ indent helpers */

    function selectedLineRange() {
      var text = input.value;
      var start = text.lastIndexOf('\n', input.selectionStart - 1) + 1;
      var end = text.indexOf('\n', input.selectionEnd);
      if (end === -1) end = text.length;
      return { start: start, end: end };
    }

    function indentSelection() {
      var range = selectedLineRange();
      var block = input.value.slice(range.start, range.end);
      var shifted = block.split('\n').map(function (line) {
        return line === '' ? line : '    ' + line;
      }).join('\n');
      replaceRange(range.start, range.end, shifted, { keepSelection: true });
    }

    function outdentSelection() {
      var range = selectedLineRange();
      var block = input.value.slice(range.start, range.end);
      var removed = 0;
      var shifted = block.split('\n').map(function (line) {
        var strip = /^(\t| {1,4})/.exec(line);
        if (!strip) return line;
        removed += strip[1].length;
        return line.slice(strip[1].length);
      }).join('\n');
      if (!removed) return;
      replaceRange(range.start, range.end, shifted, { keepSelection: true });
    }

    /* -------------------------------------------------- selection utilities */

    function getSelection() {
      return {
        start: input.selectionStart,
        end: input.selectionEnd,
        text: input.value.slice(input.selectionStart, input.selectionEnd)
      };
    }

    function setSelection(start, end) {
      input.focus();
      input.setSelectionRange(start, end === undefined ? start : end);
      emit('cursor');
    }

    /**
     * replaceRange(start, end, text, { keepSelection })
     * Uses execCommand where available so the native undo stack records the
     * edit; a mutation made via `value =` is invisible to Ctrl+Z.
     */
    function replaceRange(start, end, text, flags) {
      var options2 = flags || {};
      input.focus();

      var before = input.value;
      var selStart = input.selectionStart;
      var selEnd = input.selectionEnd;

      if (start === undefined) start = selStart;
      if (end === undefined) end = selEnd;

      input.setSelectionRange(start, end);
      var inserted = false;
      // execCommand is deprecated but remains the only way to participate in
      // the browser's native undo history. Fall back to a direct assignment.
      try {
        inserted = document.execCommand('insertText', false, text);
      } catch (err) {
        inserted = false;
      }

      if (!inserted) {
        input.value = before.slice(0, start) + text + before.slice(end);
        input.dispatchEvent(new Event('input', { bubbles: true }));
      }

      var caret = start + text.length;
      if (options2.keepSelection) {
        var delta = input.value.length - before.length;
        input.setSelectionRange(selStart, selEnd + delta);
      } else if (options2.selectFrom !== undefined) {
        input.setSelectionRange(options2.selectFrom, caret);
      } else {
        input.setSelectionRange(caret, caret);
      }
      // execCommand fires `input`; a direct assignment did too. Either way the
      // model is already in sync, so only refresh the cursor readout here.
      emit('cursor');
    }

    /**
     * wrapSelection(prefix, suffix, { placeholder })
     * Toggles: wrapping an already-wrapped range unwraps it. This makes every
     * toolbar button a toggle, which is what people expect from a bold button.
     */
    function wrapSelection(prefix, suffix, flags) {
      var options2 = flags || {};
      suffix = suffix === undefined ? prefix : suffix;
      var sel = getSelection();
      var text = input.value;

      // Already wrapped just inside the selection? Unwrap.
      var outerStart = sel.start - prefix.length;
      var outerEnd = sel.end + suffix.length;
      if (outerStart >= 0 && outerEnd <= text.length &&
          text.slice(outerStart, sel.start) === prefix &&
          text.slice(sel.end, outerEnd) === suffix) {
        replaceRange(outerStart, outerEnd, sel.text, { selectFrom: outerStart });
        return;
      }

      // Wrapped immediately outside a collapsed caret? Step inside it.
      if (sel.start === sel.end && outerStart >= 0 && outerEnd <= text.length &&
          text.slice(outerStart, sel.start) === prefix &&
          text.slice(sel.end, outerEnd) === suffix) {
        setSelection(sel.start, sel.end);
        return;
      }

      // Empty selection: insert the pair and put the caret in the middle.
      if (!sel.text) {
        var placeholder = options2.placeholder || '';
        replaceRange(sel.start, sel.end, prefix + placeholder + suffix, {
          selectFrom: sel.start + prefix.length
        });
        return;
      }

      // A selection already containing the markers: strip them.
      if (sel.text.length >= prefix.length + suffix.length &&
          sel.text.indexOf(prefix) === 0 &&
          sel.text.slice(-suffix.length) === suffix) {
        var inner = sel.text.slice(prefix.length, sel.text.length - suffix.length);
        replaceRange(sel.start, sel.end, inner, { selectFrom: sel.start });
        return;
      }

      replaceRange(sel.start, sel.end, prefix + sel.text + suffix, {
        selectFrom: sel.start + prefix.length
      });
    }

    function insertAtCursor(text) {
      var sel = getSelection();
      replaceRange(sel.start, sel.end, text, { selectFrom: sel.start + text.length });
    }

    /** Prefix every selected line — headings, quotes, list markers. */
    function transformLines(transform) {
      var range = selectedLineRange();
      var block = input.value.slice(range.start, range.end);
      var shifted = block.split('\n').map(function (line, index) {
        return transform(line, index);
      }).join('\n');
      replaceRange(range.start, range.end, shifted, { keepSelection: true });
    }

    /** Toggle an ATX heading level on the current line(s). */
    function setHeading(level) {
      transformLines(function (line) {
        var stripped = line.replace(/^\s{0,3}#{1,6}\s+/, '');
        if (level === 0) return stripped;
        return '#'.repeat(level) + ' ' + stripped.replace(/^\s+/, '');
      });
    }

    function setLinePrefix(prefix) {
      transformLines(function (line) {
        if (line.indexOf(prefix) === 0) return line.slice(prefix.length);
        var stripped = line.replace(/^(\s*)(?:>\s?|[-*+]\s+|\d+[.)]\s+)?/, '$1');
        return prefix + stripped;
      });
    }

    /* --------------------------------------------------------- caret & scroll */

    function cursorInfo() {
      var text = input.value;
      var pos = input.selectionStart;
      var before = text.slice(0, pos);
      var line = before.split('\n').length - 1;
      var column = pos - (before.lastIndexOf('\n') + 1);
      var selected = input.selectionEnd - input.selectionStart;
      return {
        pos: pos,
        end: input.selectionEnd,
        line: line,
        column: column,
        lineNumber: line + 1,
        columnNumber: column + 1,
        selected: selected
      };
    }

    /** Keep the caret on screen after a programmatic edit. */
    function scrollCaretIntoView() {
      var lineHeight = lineHeightPx();
      var top = input.selectionStart === input.selectionEnd
        ? cursorInfo().line * lineHeight
        : 0;
      var pad = lineHeight * 3;
      var viewTop = scroll.scrollTop;
      var viewBottom = viewTop + scroll.clientHeight;
      var caretTop = top + parseFloat(global.getComputedStyle(layer).paddingTop || '0');
      if (caretTop - pad < viewTop) {
        scroll.scrollTop = Math.max(0, caretTop - pad);
      } else if (caretTop + lineHeight + pad > viewBottom) {
        scroll.scrollTop = caretTop + lineHeight + pad - scroll.clientHeight;
      }
    }

    function scrollToLine(line, options2) {
      var lineHeight = lineHeightPx();
      var styles = global.getComputedStyle(layer);
      var padTop = parseFloat(styles.paddingTop) || 0;
      var target = padTop + line * lineHeight;
      var behavior = (options2 && options2.smooth && !MD.util.prefersReducedMotion()) ? 'smooth' : 'auto';
      scroll.scrollTo({ top: Math.max(0, target - lineHeight * 2), behavior: behavior });
    }

    function scrollRatios() {
      var maxTop = scroll.scrollHeight - scroll.clientHeight;
      var maxLeft = scroll.scrollWidth - scroll.clientWidth;
      return {
        top: maxTop > 0 ? scroll.scrollTop / maxTop : 0,
        left: maxLeft > 0 ? scroll.scrollLeft / maxLeft : 0,
        scrollTop: scroll.scrollTop,
        scrollHeight: scroll.scrollHeight,
        clientHeight: scroll.clientHeight
      };
    }

    /* ------------------------------------------------------------ search */

    function setSearch(query, options2) {
      var flags = options2 || {};
      searchHits = [];
      currentHit = -1;
      if (!query) {
        renderSearchHighlights();
        return { count: 0, index: -1 };
      }

      var source = input.value;
      var haystack = flags.caseSensitive ? source : source.toLowerCase();
      var needle = flags.caseSensitive ? query : query.toLowerCase();
      var offset = 0;
      var guard = 0;

      while (offset <= haystack.length - needle.length && guard < 5000) {
        var at = haystack.indexOf(needle, offset);
        if (at === -1) break;
        // Whole-word filtering is handled here rather than in the regex so a
        // regex-special query string can never break the search.
        var left = source.charAt(at - 1);
        var right = source.charAt(at + needle.length);
        var boundaryOk = !flags.wholeWord ||
          (!syntax.isWordChar(left) && !syntax.isWordChar(right));
        if (boundaryOk) {
          var lineStart = source.lastIndexOf('\n', at - 1) + 1;
          searchHits.push({
            line: source.slice(0, lineStart).split('\n').length - 1,
            start: at - lineStart,
            end: at - lineStart + needle.length
          });
        }
        offset = at + Math.max(1, needle.length);
        guard += 1;
      }

      if (searchHits.length) currentHit = 0;
      renderSearchHighlights();
      return { count: searchHits.length, index: currentHit };
    }

    function renderSearchHighlights() {
      var byLine = Object.create(null);
      searchHits.forEach(function (hit, index) {
        (byLine[hit.line] || (byLine[hit.line] = [])).push({ hit: hit, index: index });
      });

      var children = highlight.children;
      for (var i = 0; i < children.length; i++) {
        var marks = byLine[i];
        if (!marks) continue;
        // Re-run the tokenizer for this line only, then splice highlight spans
        // in by character offset. Doing it on the raw line keeps the alignment
        // exact because the offsets come from the same string.
        var raw = lines[i] || '';
        var pieces = [];
        var cursor = 0;
        marks.sort(function (a, b) { return a.hit.start - b.hit.start; });
        marks.forEach(function (entry) {
          if (entry.hit.start < cursor) return; // overlapping hits: keep the first
          pieces.push({ text: raw.slice(cursor, entry.hit.start), hit: false });
          pieces.push({ text: raw.slice(entry.hit.start, entry.hit.end), hit: true, index: entry.index });
          cursor = entry.hit.end;
        });
        pieces.push({ text: raw.slice(cursor), hit: false });

        // Paint the marks on top of the already-rendered line by wrapping the
        // matching text runs in a shadow element. Because the backdrop is
        // non-interactive we can simply re-render this one line.
        var container = children[i];
        var textNode = container.firstChild;
        if (!textNode) continue;
        renderLineWithHits(container, raw, marks);
      }
    }

    function renderLineWithHits(container, raw, marks) {
      // Re-tokenize the single line, then overlay hit rectangles via a sibling
      // layer is overkill; instead we walk the rendered spans and split any
      // text node that intersects a hit range.
      var offset = 0;
      var hits = marks.map(function (entry) { return entry; })
        .sort(function (a, b) { return a.hit.start - b.hit.start; });
      var hitAt = 0;

      function walk(node) {
        if (node.nodeType === Node.TEXT_NODE) {
          var text = node.nodeValue;
          var start = offset;
          var end = offset + text.length;
          offset = end;

          var overlapping = hits.filter(function (entry) {
            return entry.hit.end > start && entry.hit.start < end;
          });
          if (!overlapping.length) return;

          var frag = document.createDocumentFragment();
          var pos = start;
          overlapping.forEach(function (entry) {
            var from = Math.max(entry.hit.start, start);
            var to = Math.min(entry.hit.end, end);
            if (from > pos) frag.appendChild(document.createTextNode(text.slice(pos - start, from - start)));
            var mark = el('mark', {
              className: 'search-hit' + (entry.index === currentHit ? ' search-hit--current' : '')
            });
            mark.textContent = text.slice(from - start, to - start);
            frag.appendChild(mark);
            pos = to;
          });
          if (pos < end) frag.appendChild(document.createTextNode(text.slice(pos - start)));
          node.parentNode.replaceChild(frag, node);
          return;
        }
        var child = node.firstChild;
        while (child) {
          var next = child.nextSibling;
          walk(child);
          child = next;
        }
      }
      // Snapshot children first: walk() mutates the tree as it goes.
      var children = Array.prototype.slice.call(container.childNodes);
      children.forEach(walk);
    }

    function searchStep(delta) {
      if (!searchHits.length) return;
      currentHit = (currentHit + delta + searchHits.length) % searchHits.length;
      var hit = searchHits[currentHit];
      scrollToLine(hit.line, { smooth: true });
      renderSearchHighlights();
      return currentHit;
    }

    /** Scroll the line containing character offset `pos` into view. */
    function revealOffset(pos) {
      var line = input.value.slice(0, pos).split('\n').length - 1;
      scrollToLine(line, { smooth: false });
    }

    /* ------------------------------------------------------------ options */

    function setOption(key, value) {
      switch (key) {
        case 'fontSize':
          canvas.dataset.fontSize = String(value);
          break;
        case 'lineHeight':
          canvas.dataset.lineHeight = String(value);
          break;
        case 'wrap':
          canvas.dataset.wrap = value === 'off' || value === false ? 'off' : 'on';
          break;
        case 'gutter':
          gutter.hidden = value === false;
          break;
        case 'highlight':
          root.classList.toggle('editor--nohl', value === false);
          break;
        case 'focus':
          root.classList.toggle('editor--focus', value === true);
          applyFocusLine();
          break;
        case 'typewriter':
          root.classList.toggle('editor--typewriter', value === true);
          break;
        default:
          return false;
      }
      if (key === 'fontSize' || key === 'lineHeight' || key === 'wrap' || key === 'gutter') {
        scheduleRender(0);
      }
      return true;
    }

    /* ------------------------------------------------------------- boot */

    // Seed from the option so the first paint is already correct.
    if (opts.value) input.value = opts.value;
    refreshLines();

    // Re-measure when the pane resizes: the backdrop reflows and the gutter
    // reads its heights from the newly painted spans.
    var resizeObserver = null;
    if (global.ResizeObserver) {
      resizeObserver = new global.ResizeObserver(MD.util.debounce(function () {
        scheduleRender(0);
      }, 120));
      resizeObserver.observe(layer);
    }

    // Repaint once the real face is active, otherwise the backdrop measured
    // against a fallback font would size the gutter wrongly.
    if (document.fonts && document.fonts.ready) {
      document.fonts.ready.then(function () {
        scheduleRender(0);
      }).catch(function () { /* font loading is best-effort */ });
    }

    function destroy() {
      if (resizeObserver) resizeObserver.disconnect();
      customHandlers = Object.create(null);
    }

    // First paint happens on the next frame so the element is in the document
    // and has a measurable width.
    scheduleRender(0);

    return api;
  }

  MD.Editor = { create: create };
})(window.MD = window.MD || {}, window);
