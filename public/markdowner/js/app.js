/* MarkDowner — application shell.
 *
 * This module owns the things that only make sense once every other piece
 * exists: the active document, theme, layout state, and the wiring between
 * panes. Everything else (tokenizer, renderer, store, editor, preview) is a
 * pure-ish component that this file drives.
 *
 * Ordering matters at boot:
 *   1. theme        — before first paint, so nothing flashes
 *   2. editor + preview — they need a measured DOM
 *   3. documents    — needs both panes to exist
 *   4. commands     — bound to the now-live app object
 */
(function (MD, global) {
  'use strict';

  var el = MD.dom.el;
  var $ = MD.dom.$;
  var on = MD.dom.on;
  var commands = MD.commands;
  var ui = MD.ui;
  var settings = MD.settings;
  var library = MD.library;

  /* ==========================================================================
   * 1. App state
   * ======================================================================= */

  var app = {
    editor: null,
    preview: null,
    sync: null,
    toolbar: null,
    activeDocId: '',
    savedContent: '',
    dirty: false,
    findState: { query: '', index: 0, count: 0, replace: '' }
  };

  var dom = {};

  function cacheDom() {
    dom.theme = document.documentElement;
    dom.titleInput = $('#doc-title');
    dom.dirtyDot = $('#doc-dirty');
    dom.panes = $('#panes');
    dom.sidebar = $('#sidebar');
    dom.outline = $('#outline');
    dom.outlineList = $('#outline-list');
    dom.libraryList = $('#library-list');
    dom.librarySearch = $('#library-search');
    dom.toolbar = $('#toolbar');
    dom.editorHost = $('#editor-host');
    dom.previewHost = $('#preview-host');
    dom.findbar = $('#findbar');
    dom.findInput = $('#find-input');
    dom.replaceInput = $('#replace-input');
    dom.findCount = $('#find-count');
    dom.saveDot = $('#save-dot');
    dom.saveStatus = $('#save-status');
    dom.statLine = $('#stat-line');
    dom.statCol = $('#stat-col');
    dom.statSelection = $('#stat-selection');
    dom.statSelectionWrap = $('#stat-selection-wrap');
    dom.statWords = $('#stat-words');
    dom.statChars = $('#stat-chars');
    dom.statLines = $('#stat-lines');
    dom.statReading = $('#stat-reading');
    dom.dropzone = $('#dropzone');
    dom.resizerSidebar = $('#resizer-sidebar');
    dom.resizerOutline = $('#resizer-outline');
    dom.resizerSplit = $('#resizer-split');
  }

  /* ==========================================================================
   * 2. Theme
   * ======================================================================= */

  var THEME_CYCLE = ['light', 'dark', 'system'];
  var THEME_ICON = { light: 'sun', dark: 'moon', system: 'monitor' };
  var THEME_LABEL = { light: '亮色', dark: '暗色', system: '跟随系统' };

  function applyTheme(theme, options2) {
    var next = THEME_CYCLE.indexOf(theme) === -1 ? 'system' : theme;
    dom.theme.setAttribute('data-theme', next);
    settings.set('theme', next);

    var button = $('#btn-theme');
    if (button) {
      button.textContent = '';
      button.appendChild(MD.icon(THEME_ICON[next], { size: 'md' }));
      button.title = '主题：' + THEME_LABEL[next];
      button.setAttribute('aria-label', '主题：' + THEME_LABEL[next]);
    }
    // KaTeX and highlight.js resolve their colours from CSS variables, but a
    // re-render is the only way to re-resolve them inside inline SVG output.
    if (app.preview && !(options2 && options2.silent)) {
      app.preview.setSource(app.editor.getValue(), { immediate: true });
    }
    if (options2 && options2.announce) {
      ui.announce('已切换到' + THEME_LABEL[next] + '主题');
    }
  }

  function cycleTheme() {
    var current = settings.get('theme');
    var at = THEME_CYCLE.indexOf(current);
    applyTheme(THEME_CYCLE[(at + 1) % THEME_CYCLE.length], { announce: true });
  }

  /** Follow the OS when the user picked "system". */
  function watchSystemTheme() {
    if (!global.matchMedia) return;
    var query = global.matchMedia('(prefers-color-scheme: dark)');
    var handler = function () {
      // Nothing to do: the CSS media query inside [data-theme='system'] handles
      // it. Re-render only so any script-measured colours stay correct.
      if (settings.get('theme') === 'system' && app.preview) {
        app.preview.setSource(app.editor.getValue(), { immediate: true });
      }
    };
    if (query.addEventListener) query.addEventListener('change', handler);
    else if (query.addListener) query.addListener(handler);
  }

  /* ==========================================================================
   * 3. Layout — panes, sidebar, outline, resizers
   * ======================================================================= */

  function setView(mode) {
    var next = ['editor', 'split', 'preview'].indexOf(mode) === -1 ? 'split' : mode;
    dom.panes.dataset.view = next;
    settings.set('view', next);
    var buttons = MD.dom.$$('.segmented__item[data-view-mode]');
    buttons.forEach(function (button) {
      button.setAttribute('aria-pressed', String(button.dataset.viewMode === next));
    });
    // The editor was laid out at zero width while hidden; re-measure now.
    MD.dom.raf(function () {
      if (app.preview) app.preview.remeasure();
      if (app.sync) app.sync.measure();
    });
  }

  /** The real narrow-screen behaviour is CSS; this keeps state in sync. */
  function isNarrow() {
    return global.innerWidth <= 900;
  }

  function setSidebar(visible, options2) {
    var next = !!visible;
    dom.sidebar.hidden = !next;
    settings.set('showSidebar', next);
    var button = $('#btn-sidebar');
    if (button) button.setAttribute('aria-pressed', String(next));
    if (options2 && options2.announce) ui.announce(next ? '已显示文档库' : '已隐藏文档库');
    if (app.editor && !next === false) MD.dom.raf(function () { app.preview.remeasure(); });
  }

  function setOutline(visible, options2) {
    var next = !!visible;
    dom.outline.hidden = !next;
    settings.set('showOutline', next);
    var button = $('#btn-outline');
    if (button) button.setAttribute('aria-pressed', String(next));
    if (options2 && options2.announce) ui.announce(next ? '已显示大纲' : '已隐藏大纲');
  }

  /**
   * Drag a resizer to resize a panel.
   *
   * Two details that make this feel native: the handle's hit area is wider than
   * its visible line (CSS), and `pointer-events: none` on the body during a drag
   * stops the iframe-like selection flicker over the panes.
   */
  function makeResizer(handle, options2) {
    if (!handle) return;
    var opts = options2 || {};
    var dragging = false;
    var startPos = 0;
    var startSize = 0;

    handle.addEventListener('pointerdown', function (event) {
      if (event.button !== 0) return;
      dragging = true;
      startPos = opts.axis === 'y' ? event.clientY : event.clientX;
      startSize = opts.get();
      handle.setPointerCapture(event.pointerId);
      handle.dataset.dragging = 'true';
      document.body.dataset.resizing = 'true';
      event.preventDefault();
    });

    handle.addEventListener('pointermove', function (event) {
      if (!dragging) return;
      var current = opts.axis === 'y' ? event.clientY : event.clientX;
      var delta = (current - startPos) * (opts.invert ? -1 : 1);
      opts.set(MD.util.clamp(startSize + delta, opts.min, opts.max));
    });

    function endDrag(event) {
      if (!dragging) return;
      dragging = false;
      delete handle.dataset.dragging;
      delete document.body.dataset.resizing;
      if (event && handle.releasePointerCapture) {
        try { handle.releasePointerCapture(event.pointerId); } catch (err) { /* already released */ }
      }
      if (app.preview) app.preview.remeasure();
    }

    handle.addEventListener('pointerup', endDrag);
    handle.addEventListener('pointercancel', endDrag);

    // Keyboard resizing: a separator that only responds to a mouse is a
    // barrier, and this is exactly the kind of control that gets tabbed to.
    handle.addEventListener('keydown', function (event) {
      var step = event.shiftKey ? 40 : 12;
      var delta = 0;
      var horizontal = opts.axis !== 'y';
      if (horizontal && event.key === 'ArrowLeft') delta = -step * (opts.invert ? -1 : 1);
      else if (horizontal && event.key === 'ArrowRight') delta = step * (opts.invert ? -1 : 1);
      else if (!horizontal && event.key === 'ArrowUp') delta = -step;
      else if (!horizontal && event.key === 'ArrowDown') delta = step;
      else if (event.key === 'Home') { event.preventDefault(); opts.set(opts.min); return; }
      else if (event.key === 'End') { event.preventDefault(); opts.set(opts.max); return; }
      else return;
      event.preventDefault();
      opts.set(MD.util.clamp(opts.get() + delta, opts.min, opts.max));
    });

    // Double-click restores the default — a cheap escape hatch from a bad size.
    handle.addEventListener('dblclick', function () { opts.set(opts.defaultValue); });
  }

  function installResizers() {
    makeResizer(dom.resizerSidebar, {
      axis: 'x',
      get: function () { return dom.sidebar.getBoundingClientRect().width; },
      set: function (value) {
        document.documentElement.style.setProperty('--sidebar-width', value + 'px');
        settings.set('sidebarWidth', value);
      },
      min: 180,
      max: 520,
      defaultValue: 268
    });

    makeResizer(dom.resizerOutline, {
      axis: 'x',
      invert: true,
      get: function () { return dom.outline.getBoundingClientRect().width; },
      set: function (value) {
        document.documentElement.style.setProperty('--outline-width', value + 'px');
        settings.set('outlineWidth', value);
      },
      min: 160,
      max: 460,
      defaultValue: 220
    });

    makeResizer(dom.resizerSplit, {
      axis: 'x',
      get: function () {
        var pane = $('.pane--editor');
        return pane ? pane.getBoundingClientRect().width : 0;
      },
      set: function (value) {
        var total = dom.panes.getBoundingClientRect().width - 11;
        if (total <= 0) return;
        var ratio = MD.util.clamp(value / total, 0.2, 0.8);
        var editorPane = $('.pane--editor');
        var previewPane = $('.pane--preview');
        if (!editorPane || !previewPane) return;
        editorPane.style.flex = '0 0 ' + (ratio * 100).toFixed(3) + '%';
        previewPane.style.flex = '1 1 auto';
      },
      min: 240,
      max: 4000,
      defaultValue: 0
    });
  }

  /* ==========================================================================
   * 4. Document lifecycle
   * ======================================================================= */

  function markDirty(isDirty) {
    app.dirty = isDirty;
    dom.dirtyDot.dataset.dirty = String(isDirty);
    dom.titleInput.setAttribute('aria-label', isDirty ? '文档标题（有未保存更改）' : '文档标题');
    updateTitlebar();
  }

  function updateTitlebar() {
    var title = dom.titleInput.value.trim() || '未命名文档';
    document.title = (app.dirty ? '• ' : '') + title + ' — MarkDowner';
  }

  /** Persist the active document. Debounced so typing does not hammer storage. */
  var persistActive = MD.util.debounce(function () {
    if (!app.activeDocId) return;
    var content = app.editor.getValue();
    if (content === app.savedContent) return;
    library.update(app.activeDocId, { content: content });
    app.savedContent = content;
    markDirty(false);
  }, 600);

  function openDocument(id, options2) {
    var doc = library.get(id);
    if (!doc) return false;

    // Flush the outgoing document before switching away from it.
    if (app.activeDocId && app.activeDocId !== id) {
      var outgoing = app.editor.getValue();
      if (outgoing !== app.savedContent) {
        library.update(app.activeDocId, { content: outgoing });
      }
    }

    app.activeDocId = doc.id;
    app.savedContent = doc.content;
    settings.set('lastDocId', doc.id);

    app.editor.setValue(doc.content);
    app.editor.setSelection(0, 0);
    app.editor.scroll.scrollTop = 0;

    dom.titleInput.value = doc.title || '';
    markDirty(false);
    renderLibrary();
    renderOutline(MD.md.outline(doc.content));
    app.preview.setSource(doc.content, { immediate: true });
    updateStats();

    if (!(options2 && options2.silent)) {
      app.editor.focus();
      ui.announce('已打开「' + library.title(doc) + '」');
    }
    return true;
  }

  function createDocument() {
    var doc = library.create({ content: '' });
    openDocument(doc.id, { silent: true });
    dom.titleInput.value = '';
    dom.titleInput.focus();
    dom.titleInput.select();
    ui.announce('已新建文档');
    return doc;
  }

  function deleteDocument(id) {
    var doc = library.get(id);
    if (!doc) return;
    ui.confirm({
      title: '删除文档',
      message: '「' + library.title(doc) + '」将被永久删除，此操作无法撤销。',
      confirmLabel: '删除',
      danger: true
    }).then(function (ok) {
      if (!ok) return;
      var wasActive = app.activeDocId === id;
      var sorted = library.sorted();
      library.remove(id);
      ui.toast.success('已删除', library.title(doc));
      if (wasActive) {
        var next = library.sorted()[0];
        if (next) openDocument(next.id, { silent: true });
        else {
          var fresh = library.create({ content: '' });
          openDocument(fresh.id, { silent: true });
        }
      } else {
        renderLibrary();
      }
    });
  }

  /* ==========================================================================
   * 5. Document library panel
   * ======================================================================= */

  function renderLibrary() {
    var query = dom.librarySearch.value;
    var docs = query ? library.search(query) : library.sorted();
    dom.libraryList.textContent = '';

    if (!docs.length) {
      dom.libraryList.appendChild(query
        ? ui.emptyState({
          icon: 'search',
          title: '没有找到文档',
          text: '试试其他关键词。'
        })
        : ui.emptyState({
          icon: 'fileText',
          title: '还没有文档',
          text: '新建一篇开始写作。'
        }));
      return;
    }

    var pinned = docs.filter(function (doc) { return doc.pinned; });
    var starred = docs.filter(function (doc) { return doc.starred && !doc.pinned; });
    var rest = docs.filter(function (doc) { return !doc.starred && !doc.pinned; });

    if (pinned.length) {
      dom.libraryList.appendChild(librarySection('置顶', pinned.length, pinned));
    }
    if (starred.length) {
      dom.libraryList.appendChild(librarySection('收藏', starred.length, starred));
    }
    if (rest.length) {
      dom.libraryList.appendChild(librarySection(
        pinned.length || starred.length ? '全部' : '',
        rest.length,
        rest
      ));
    }
  }

  function librarySection(label, count, docs) {
    var section = el('div', { className: 'sidebar__section' });
    if (label) {
      section.appendChild(el('div', { className: 'sidebar__label' }, [
        el('span', { text: label }),
        el('span', { className: 'sidebar__label-count', text: String(count) })
      ]));
    }
    var list = el('div', { role: 'list' });
    // Label the list itself so screen readers announce "置顶, list, 2 items"
    // instead of an unlabelled list the user cannot identify.
    if (label) list.setAttribute('aria-label', label);
    docs.forEach(function (doc) { list.appendChild(documentCard(doc)); });
    section.appendChild(list);
    return section;
  }

  function documentCard(doc) {
    var active = doc.id === app.activeDocId;
    var card = el('div', {
      className: 'doc-card',
      role: 'listitem',
      tabIndex: '0',
      dataset: { docId: doc.id }
    });
    if (active) card.setAttribute('aria-current', 'true');
    card.setAttribute('aria-label', library.title(doc));

    var body = el('div', { className: 'doc-card__body' }, [
      el('span', { className: 'doc-card__title', text: library.title(doc) }),
      el('span', { className: 'doc-card__meta' }, [
        el('span', { text: MD.util.fmt.relative(doc.updated) }),
        el('span', { text: '·' }),
        el('span', { text: MD.util.fmt.bytes(doc.content.length * 2) })
      ])
    ]);
    var excerpt = MD.md.excerpt(doc.content, 120);
    if (excerpt) body.appendChild(el('span', { className: 'doc-card__excerpt', text: excerpt }));
    card.appendChild(body);

    var actions = el('div', { className: 'doc-card__actions' });
    actions.appendChild(cardAction(doc.starred ? 'star_filled' : 'star',
      doc.starred ? '取消收藏' : '收藏', function () {
        library.toggleStar(doc.id);
        renderLibrary();
      }));
    actions.appendChild(cardAction('more', '更多操作', function (event) {
      openDocMenu(event, doc);
    }));
    card.appendChild(actions);

    card.addEventListener('click', function (event) {
      if (event.target.closest('.doc-card__action')) return;
      openDocument(doc.id);
    });
    card.addEventListener('keydown', function (event) {
      if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault();
        openDocument(doc.id);
      }
    });
    card.addEventListener('contextmenu', function (event) {
      event.preventDefault();
      openDocMenu(event, doc);
    });

    return card;
  }

  function cardAction(iconName, label, handler) {
    var button = el('button', {
      className: 'doc-card__action',
      type: 'button',
      title: label,
      'aria-label': label
    });
    button.appendChild(iconName === 'star_filled'
      ? MD.filledIcon(iconName, { size: 'sm' })
      : MD.icon(iconName, { size: 'sm' }));
    button.addEventListener('click', function (event) {
      event.stopPropagation();
      handler(event);
    });
    return button;
  }

  function openDocMenu(event, doc) {
    var rect = event.currentTarget.getBoundingClientRect
      ? event.currentTarget.getBoundingClientRect()
      : { left: event.clientX, bottom: event.clientY };
    ui.menu({
      x: event.clientX || rect.left,
      y: event.clientY || rect.bottom,
      items: [
        {
          label: '打开', icon: 'fileText',
          onSelect: function () { openDocument(doc.id); }
        },
        {
          label: '重命名', icon: 'pencil',
          onSelect: function () { renameDocument(doc); }
        },
        {
          label: '创建副本', icon: 'copy',
          onSelect: function () {
            var copy = library.duplicate(doc.id);
            renderLibrary();
            if (copy) ui.toast.success('已创建副本', library.title(copy));
          }
        },
        { separator: true },
        {
          label: doc.starred ? '取消收藏' : '收藏', icon: doc.starred ? 'star_filled' : 'star',
          onSelect: function () { library.toggleStar(doc.id); renderLibrary(); }
        },
        {
          label: doc.pinned ? '取消置顶' : '置顶', icon: 'pin',
          onSelect: function () { library.togglePin(doc.id); renderLibrary(); }
        },
        { separator: true },
        {
          label: '导出为 Markdown', icon: 'download',
          onSelect: function () { exportMarkdown(doc); }
        },
        {
          label: '导出为 HTML', icon: 'code',
          onSelect: function () { exportHtml(doc); }
        },
        { separator: true },
        {
          label: '删除', icon: 'trash', danger: true,
          onSelect: function () { deleteDocument(doc.id); }
        }
      ]
    });
  }

  function renameDocument(doc) {
    ui.prompt({
      title: '重命名文档',
      label: '标题',
      value: library.title(doc),
      confirmLabel: '保存'
    }).then(function (value) {
      if (!value) return;
      library.update(doc.id, { title: value });
      if (doc.id === app.activeDocId) {
        dom.titleInput.value = value;
        updateTitlebar();
      }
      renderLibrary();
      ui.toast.success('已重命名');
    });
  }

  /* ==========================================================================
   * 6. Outline
   * ======================================================================= */

  var activeHeadingId = '';

  function renderOutline(headings) {
    var list = MD.Preview.buildOutline(headings, {
      onSelect: function (heading) {
        activeHeadingId = heading.id;
        // Hand sync ownership to the preview so this jump also carries the
        // editor, instead of the editor's owner role cancelling it out.
        if (app.sync) app.sync.claim('preview');
        app.preview.scrollToId(heading.id);
        syncOutlineActive();
      }
    });
    dom.outlineList.textContent = '';
    dom.outlineList.appendChild(list);
    syncOutlineActive();
  }

  function syncOutlineActive() {
    var items = MD.dom.$$('.outline-item', dom.outlineList);
    items.forEach(function (item) {
      if (item.dataset.id === activeHeadingId) item.setAttribute('data-active', 'true');
      else item.removeAttribute('data-active');
    });
  }

  /** Highlight the outline entry for the heading nearest the preview's top. */
  var trackOutline = MD.util.throttle(function () {
    if (dom.outline.hidden) return;
    var headings = app.preview.getHeadings();
    if (!headings.length) return;
    var top = app.preview.root.scrollTop + 40;
    var current = headings[0];
    var nodes = app.preview.doc.querySelectorAll('[id]');
    for (var i = 0; i < headings.length; i++) {
      var node = app.preview.doc.querySelector('[id="' + cssEscape(headings[i].id) + '"]');
      if (!node) continue;
      if (node.offsetTop <= top) current = headings[i];
      else break;
    }
    if (current.id === activeHeadingId) return;
    activeHeadingId = current.id;
    syncOutlineActive();
  }, 150);

  function cssEscape(value) {
    if (global.CSS && global.CSS.escape) return global.CSS.escape(value);
    return String(value).replace(/["\\]/g, '\\$&');
  }

  /* ==========================================================================
   * 7. Status bar
   * ======================================================================= */

  var updateStats = MD.util.throttle(function () {
    var text = app.editor.getValue();
    var info = MD.util.stats(text);
    dom.statWords.textContent = MD.util.fmt.num(info.words);
    dom.statChars.textContent = MD.util.fmt.num(info.chars);
    dom.statLines.textContent = MD.util.fmt.num(info.lines);
    dom.statReading.textContent = info.readingMinutes + ' 分钟';
  }, 180);

  function updateCursor() {
    var info = app.editor.cursorInfo();
    dom.statLine.textContent = String(info.lineNumber);
    dom.statCol.textContent = String(info.columnNumber);
    if (info.selected > 0) {
      dom.statSelectionWrap.hidden = false;
      dom.statSelection.textContent = MD.util.fmt.num(info.selected);
    } else {
      dom.statSelectionWrap.hidden = true;
    }
  }

  function setSaveState(state) {
    dom.saveDot.dataset.state = state;
    dom.saveStatus.textContent = state === 'saving' ? '保存中…'
      : state === 'error' ? '保存失败' : '已保存';
  }

  /* ==========================================================================
   * 8. Find & replace
   * ======================================================================= */

  function openFindBar(withReplace) {
    dom.findbar.hidden = false;
    var target = withReplace ? dom.replaceInput : dom.findInput;
    MD.dom.raf(function () { target.focus(); target.select(); });
    runFind();
  }

  function closeFindBar() {
    dom.findbar.hidden = true;
    app.editor.setSearch('');
    app.editor.focus();
  }

  function runFind() {
    var options2 = {
      caseSensitive: $('#find-case').getAttribute('aria-pressed') === 'true',
      wholeWord: $('#find-word').getAttribute('aria-pressed') === 'true'
    };
    var result = app.editor.setSearch(dom.findInput.value, options2);
    app.findState.query = dom.findInput.value;
    app.findState.count = result.count;
    app.findState.index = result.index;
    updateFindCount();
    return result;
  }

  function updateFindCount() {
    var count = app.findState.count;
    var index = app.findState.index;
    if (!dom.findInput.value) {
      dom.findCount.textContent = '0 / 0';
      dom.findCount.removeAttribute('data-state');
      return;
    }
    if (!count) {
      dom.findCount.textContent = '无结果';
      dom.findCount.dataset.state = 'empty';
    } else {
      dom.findCount.textContent = (index + 1) + ' / ' + count;
      dom.findCount.removeAttribute('data-state');
    }
  }

  function stepFind(delta) {
    if (!app.findState.count) return;
    var next = app.editor.searchStep(delta);
    app.findState.index = next;
    updateFindCount();
  }

  function replaceOne() {
    if (!app.findState.count) return;
    var query = dom.findInput.value;
    if (!query) return;
    var sel = app.editor.getSelection();
    var replacement = dom.replaceInput.value;
    // Only replace when the current selection *is* the match, so the button
    // never eats unrelated text.
    if (sel.text.toLowerCase() === query.toLowerCase() && sel.text.length === query.length) {
      app.editor.replaceRange(sel.start, sel.end, replacement);
    }
    runFind();
    stepFind(1);
  }

  function replaceAll() {
    var query = dom.findInput.value;
    if (!query) return;
    var options2 = {
      caseSensitive: $('#find-case').getAttribute('aria-pressed') === 'true',
      wholeWord: $('#find-word').getAttribute('aria-pressed') === 'true'
    };
    var text = app.editor.getValue();
    var haystack = options2.caseSensitive ? text : text.toLowerCase();
    var needle = options2.caseSensitive ? query : query.toLowerCase();
    var replacement = dom.replaceInput.value;

    var out = '';
    var cursor = 0;
    var replaced = 0;

    while (true) {
      var at = haystack.indexOf(needle, cursor);
      if (at === -1) break;
      if (options2.wholeWord) {
        var left = text.charAt(at - 1);
        var right = text.charAt(at + needle.length);
        if (MD.syntax.isWordChar(left) || MD.syntax.isWordChar(right)) {
          out += text.slice(cursor, at + needle.length);
          cursor = at + needle.length;
          continue;
        }
      }
      out += text.slice(cursor, at) + replacement;
      cursor = at + needle.length;
      replaced += 1;
      if (replaced > 20000) break; // runaway guard
    }
    out += text.slice(cursor);

    if (!replaced) {
      ui.toast.info('没有可替换的内容');
      return;
    }
    // One edit, one undo step: replace-all must be reversible with a single
    // Ctrl+Z, which means writing the whole document in one operation.
    app.editor.replaceRange(0, text.length, out);
    runFind();
    ui.toast.success('已替换 ' + replaced + ' 处');
  }

  /* ==========================================================================
   * 9. File operations
   * ======================================================================= */

  function currentMarkdown() {
    return app.editor.getValue();
  }

  function currentTitle() {
    return dom.titleInput.value.trim() || library.title(library.get(app.activeDocId) || {}) || '未命名文档';
  }

  function exportMarkdown(doc) {
    var title = doc ? library.title(doc) : currentTitle();
    var content = doc ? doc.content : currentMarkdown();
    MD.util.download(MD.util.safeFilename(title, 'document') + '.md', content, 'text/markdown;charset=utf-8');
    ui.toast.success('已导出 Markdown', title + '.md');
  }

  function exportHtml(doc) {
    var title = doc ? library.title(doc) : currentTitle();
    var content = doc ? doc.content : currentMarkdown();
    var rendered = MD.md.render(content);
    var html = buildStandaloneHtml(title, rendered.html);
    MD.util.download(MD.util.safeFilename(title, 'document') + '.html', html, 'text/html;charset=utf-8');
    ui.toast.success('已导出 HTML', title + '.html');
  }

  /**
   * A standalone HTML document that carries its own typography. The point of
   * exporting is that the file still looks right on someone else's machine, so
   * the stylesheet is embedded rather than linked.
   */
  function buildStandaloneHtml(title, bodyHtml) {
    return [
      '<!DOCTYPE html>',
      '<html lang="zh-CN">',
      '<head>',
      '<meta charset="UTF-8">',
      '<meta name="viewport" content="width=device-width, initial-scale=1">',
      '<title>' + MD.util.escapeHtml(title) + '</title>',
      '<style>',
      EXPORT_CSS,
      '</style>',
      '</head>',
      '<body>',
      '<article class="doc">',
      bodyHtml,
      '</article>',
      '</body>',
      '</html>'
    ].join('\n');
  }

  var EXPORT_CSS = [
    ':root{color-scheme:light dark}',
    'body{margin:0;background:#fafafa;color:#09090b;',
    "font-family:'Public Sans',-apple-system,BlinkMacSystemFont,'Segoe UI','PingFang SC','Microsoft YaHei',sans-serif;",
    'font-size:17px;line-height:1.75;-webkit-text-size-adjust:100%}',
    '.doc{max-width:72ch;margin:0 auto;padding:56px 24px 96px;overflow-wrap:break-word}',
    "h1,h2,h3,h4,h5,h6{font-family:'Libre Bodoni',Georgia,serif;line-height:1.2;letter-spacing:-.02em;margin:2em 0 .6em}",
    'h1{font-size:2.25rem;font-weight:600;margin-top:0;padding-bottom:.3em;border-bottom:2px solid #e4e4e7}',
    'h2{font-size:1.65rem;font-weight:600;padding-bottom:.25em;border-bottom:1px solid #e4e4e7}',
    'h3{font-size:1.3rem}h4{font-size:1.1rem}h5,h6{font-size:1rem}',
    'a{color:#be185d;text-decoration:underline;text-underline-offset:2px}',
    'p,ul,ol,blockquote,pre,table,figure,dl{margin:0 0 1.25em}',
    'ul,ol{padding-left:1.6em}li+li{margin-top:.3em}',
    'li::marker{color:#ec4899}',
    'blockquote{margin-left:0;padding:.2em 0 .2em 1.1em;border-left:3px solid #ec4899;color:#52525b}',
    'code{font-family:"JetBrains Mono",Consolas,Menlo,monospace;font-size:.875em;',
    'background:rgba(9,9,11,.055);color:#be185d;padding:.15em .35em;border-radius:3px}',
    'pre{background:#f6f6f7;border:1px solid #e4e4e7;border-radius:8px;padding:16px;overflow-x:auto}',
    'pre code{background:none;color:#18181b;padding:0;font-size:.85em;line-height:1.65}',
    'table{border-collapse:collapse;width:100%;font-size:.95em}',
    'th,td{border:1px solid #e4e4e7;padding:8px 12px;text-align:left}',
    'th{background:#f4f4f5;font-weight:600}',
    'hr{border:0;border-top:1px solid #e4e4e7;margin:2.5em 0}',
    'img{max-width:100%;height:auto;border-radius:8px}',
    'mark{background:#fde68a;color:#422006;padding:.1em .2em;border-radius:2px}',
    'kbd{font-family:"JetBrains Mono",monospace;font-size:.8em;background:#fff;',
    'border:1px solid #d4d4d8;border-bottom-width:2px;border-radius:4px;padding:.1em .4em}',
    '.callout{border:1px solid #e4e4e7;border-left:3px solid #1d4ed8;border-radius:8px;',
    'padding:12px 16px;background:rgba(29,78,216,.06);margin:1.5em 0}',
    '.callout__title{font-weight:600;text-transform:uppercase;letter-spacing:.08em;',
    'font-size:.75rem;margin:0 0 .4em;color:#1d4ed8}',
    '.callout--tip{border-left-color:#15803d;background:rgba(21,128,61,.06)}',
    '.callout--tip .callout__title{color:#15803d}',
    '.callout--warning,.callout--caution{border-left-color:#b45309;background:rgba(180,83,9,.07)}',
    '.callout--warning .callout__title,.callout--caution .callout__title{color:#b45309}',
    '.callout--important{border-left-color:#dc2626;background:rgba(220,38,38,.06)}',
    '.callout--important .callout__title{color:#dc2626}',
    '.callout__body>*:last-child{margin-bottom:0}',
    '.task-list-item{list-style:none;margin-left:-1.4em}',
    '.table-scroll{overflow-x:auto}',
    '.heading-anchor{display:none}',
    '.code-copy{display:none}',
    '@media (prefers-color-scheme:dark){',
    'body{background:#18181b;color:#fafafa}',
    'a{color:#f9a8d4}h1,h2{border-bottom-color:#35353d}blockquote{color:#a1a1aa}',
    'code{background:rgba(250,250,250,.09);color:#f9a8d4}',
    'pre{background:#141417;border-color:#2f2f37}pre code{color:#e8e8ea}',
    'th,td{border-color:#35353d}th{background:#1f1f23}hr{border-top-color:#35353d}',
    'kbd{background:#26262b;border-color:#4a4a54}',
    '.callout{border-color:#35353d}',
    '}',
    '@media print{body{background:#fff;color:#000}.doc{padding:0;max-width:none}}'
  ].join('');

  function importFiles(files) {
    var list = Array.prototype.slice.call(files);
    if (!list.length) return;
    var targets = list.filter(function (file) {
      return /\.(md|markdown|mdown|mkd|txt|text)$/i.test(file.name) || file.type.indexOf('text') === 0;
    });
    if (!targets.length) {
      ui.toast.warning('没有可导入的文件', '支持 .md / .markdown / .txt');
      return;
    }

    var created = [];
    var failures = [];

    // Sequential so the created order matches the file order.
    targets.reduce(function (chain, file) {
      return chain.then(function () {
        return MD.util.readAsText(file).then(function (text) {
          var doc = library.insert({
            title: file.name.replace(/\.[^.]+$/, ''),
            content: text
          });
          created.push(doc);
        }).catch(function () {
          failures.push(file.name);
        });
      });
    }, Promise.resolve()).then(function () {
      renderLibrary();
      if (created.length) {
        openDocument(created[0].id, { silent: true });
        ui.toast.success(
          '已导入 ' + created.length + ' 个文件',
          created.length === 1 ? library.title(created[0]) : created.map(library.title).join('、')
        );
      }
      if (failures.length) {
        ui.toast.error('有 ' + failures.length + ' 个文件读取失败', failures.join('、'));
      }
    });
  }

  function pickFiles() {
    var input = el('input', {
      type: 'file',
      accept: '.md,.markdown,.mdown,.mkd,.txt,text/markdown,text/plain',
      multiple: true,
      style: 'display:none'
    });
    input.addEventListener('change', function () {
      importFiles(input.files);
      input.remove();
    });
    document.body.appendChild(input);
    input.click();
  }

  function saveDocument() {
    persistActive.flush();
    library.flush();
    setSaveState('idle');
    ui.toast.success('已保存', library.title(library.get(app.activeDocId) || {}));
  }

  function printDocument() {
    // The preview is the print target; make sure it reflects the editor first.
    app.preview.setSource(currentMarkdown(), { immediate: true });
    // Give the browser one frame to lay out before handing over to print.
    MD.dom.raf(function () {
      window.setTimeout(function () { global.print(); }, 60);
    });
  }

  /* ==========================================================================
   * 10. Settings dialog
   * ======================================================================= */

  function openSettings() {
    var draft = Object.assign({}, settings.all());

    var body = el('div');

    function section(title) {
      var node = el('section', { className: 'dialog__section' });
      node.appendChild(el('h3', { className: 'dialog__section-title', text: title }));
      body.appendChild(node);
      return node;
    }

    // `into` lets a segmented row live inside an existing section instead of
    // opening a new one — used by the wrap control, which belongs to "编辑器".
    function segmented(label, key, values, labels, into) {
      var group = into || el('div', { className: 'dialog__section' });
      if (label) group.appendChild(el('h3', { className: 'dialog__section-title', text: label }));
      var row = el('div', { className: 'segmented', role: 'group' });
      row.setAttribute('aria-label', label || key);
      values.forEach(function (value, index) {
        var button = el('button', {
          className: 'segmented__item',
          type: 'button',
          text: labels[index],
          'aria-pressed': String(String(draft[key]) === String(value))
        });
        button.addEventListener('click', function () {
          draft[key] = value;
          MD.dom.$$('.segmented__item', row).forEach(function (item, i) {
            item.setAttribute('aria-pressed', String(i === index));
          });
        });
        row.appendChild(button);
      });
      group.appendChild(row);
      if (!into) body.appendChild(group);
      return group;
    }

    function toggle(label, hint, key) {
      var current = draft[key] === true;
      var row = el('button', {
        className: 'option-row',
        type: 'button',
        role: 'switch',
        'aria-checked': String(current)
      });
      row.appendChild(el('span', { className: 'option-row__label' }, [
        el('span', { text: label }),
        hint ? el('span', { className: 'option-row__hint', text: hint }) : null
      ]));
      var knob = el('span', { className: 'switch', 'aria-hidden': 'true' });
      knob.setAttribute('aria-checked', String(current));
      row.appendChild(knob);
      row.addEventListener('click', function () {
        draft[key] = !draft[key];
        row.setAttribute('aria-checked', String(draft[key]));
        knob.setAttribute('aria-checked', String(draft[key]));
      });
      body.appendChild(row);
      return row;
    }

    segmented('外观', 'theme', ['light', 'dark', 'system'], ['亮色', '暗色', '跟随系统']);
    segmented('编辑器字号', 'fontSize',
      ['12', '13', '14', '15', '16', '18', '20', '22'],
      ['12', '13', '14', '15', '16', '18', '20', '22']);
    segmented('行高', 'lineHeight', ['compact', 'normal', 'relaxed'], ['紧凑', '标准', '宽松']);

    var editorSection = section('编辑器');
    // `wrap` is the enum 'on'|'off' (see MD.settings DEFAULTS), so it gets a
    // segmented row rather than a switch — a switch could never round-trip.
    segmented('自动换行', 'wrap', ['on', 'off'], ['折行', '不折行'], editorSection);
    toggle('显示行号', '左侧行号栏', 'showGutter');
    toggle('语法高亮', '正文着色', 'syntaxHighlight');
    toggle('专注模式', '淡化非当前段落', 'focusMode');
    toggle('打字机模式', '当前行保持在屏幕中部', 'typewriter');

    section('行为');
    toggle('自动保存', '停止输入后写入本地存储', 'autoSave');
    toggle('滚动同步', '编辑与预览位置联动', 'syncScroll');
    toggle('显示文档库', '', 'showSidebar');
    toggle('显示大纲', '', 'showOutline');

    var panel;
    ui.dialog({
      title: '设置',
      width: 'wide',
      flush: false,
      body: body,
      split: true,
      actions: [
        {
          label: '恢复默认', kind: 'ghost', value: '__reset', autofocus: false
        },
        { label: '取消', kind: 'outline', value: null },
        { label: '保存', kind: 'primary', value: '__save' }
      ],
      onMount: function (node) { panel = node; }
    }).then(function (result) {
      if (result === '__reset') {
        settings.reset();
        applyAllSettings(true);
        ui.toast.success('已恢复默认设置');
        openSettings();
        return;
      }
      if (result !== '__save') return;
      var changed = settings.patch(draft);
      applyAllSettings(false);
      ui.toast.success('设置已保存',
        changed.length ? '更新了 ' + changed.length + ' 项' : '没有变化');
    });
  }

  /** Push every persisted setting into the live UI. */
  function applyAllSettings(announce) {
    var all = settings.all();
    applyTheme(all.theme, { silent: true });
    setView(all.view);
    app.editor.setOption('fontSize', all.fontSize);
    app.editor.setOption('lineHeight', all.lineHeight);
    app.editor.setOption('wrap', all.wrap);
    app.editor.setOption('gutter', all.showGutter);
    app.editor.setOption('highlight', all.syntaxHighlight);
    app.editor.setOption('focus', all.focusMode);
    app.editor.setOption('typewriter', all.typewriter);
    setSidebar(all.showSidebar);
    setOutline(all.showOutline);
    if (app.sync) app.sync.setEnabled(all.syncScroll);

    syncToggleButton('#btn-focus-mode', all.focusMode);
    syncToggleButton('#btn-typewriter', all.typewriter);
    syncToggleButton('#btn-wrap', all.wrap === 'on');
    syncToggleButton('#btn-sync-scroll', all.syncScroll);
    if (announce) ui.announce('设置已应用');
  }

  function syncToggleButton(selector, pressed) {
    var button = $(selector);
    if (button) button.setAttribute('aria-pressed', String(!!pressed));
  }

  /** Flip a boolean setting and re-apply. */
  function toggleSetting(key, button) {
    var next = !settings.get(key);
    settings.set(key, next);
    applyAllSettings(false);
    if (button) button.setAttribute('aria-pressed', String(next));
    return next;
  }

  /* ==========================================================================
   * 11. Formatting helpers used by commands
   * ======================================================================= */

  function editor() { return app.editor; }

  function insertBlock(text, options2) {
    var opts = options2 || {};
    var instance = editor();
    var sel = instance.getSelection();
    var value = instance.getValue();

    // Place a block on its own lines: find the enclosing line boundaries and
    // pad with newlines only where they are actually missing.
    var lineStart = value.lastIndexOf('\n', sel.start - 1) + 1;
    var lineEnd = value.indexOf('\n', sel.end);
    if (lineEnd === -1) lineEnd = value.length;

    var before = value.slice(0, lineStart);
    var after = value.slice(lineEnd);
    var prefix = (before && !/\n\n$/.test(before)) ? (/\n$/.test(before) ? '\n' : '\n\n') : '';
    var suffix = (after && !/^\n\n/.test(after)) ? (/^\n/.test(after) ? '\n' : '\n\n') : '';

    var block = text;
    if (opts.selectedText && sel.text) {
      block = text.replace('{{selection}}', sel.text);
    }

    instance.replaceRange(lineStart, lineEnd, prefix + block + suffix, {
      selectFrom: lineStart + prefix.length + (opts.selectOffset || 0)
    });
    instance.focus();
  }

  function rangeText(marker) {
    var instance = editor();
    var sel = instance.getSelection();
    if (!sel.text) return '';
    return marker + sel.text + marker;
  }

  /* ==========================================================================
   * 12. Command registration
   * ======================================================================= */

  function registerCommands() {
    var F = '格式';
    var E = '编辑';
    var V = '视图';
    var FILE = '文件';
    var APP = '应用';

    function wrap(id, label, iconName, accel, prefix, suffix, placeholder) {
      commands.register({
        id: id, label: label, icon: iconName, accel: accel, group: F,
        run: function () {
          editor().wrapSelection(prefix, suffix === undefined ? prefix : suffix,
            { placeholder: placeholder || '' });
        }
      });
    }

    wrap('format.bold', '加粗', 'bold', 'Mod+B', '**', '**', '粗体');
    wrap('format.italic', '斜体', 'italic', 'Mod+I', '*', '*', '斜体');
    wrap('format.strike', '删除线', 'strikethrough', 'Mod+Shift+X', '~~', '~~', '删除线');
    wrap('format.mark', '高亮', 'highlight', null, '==', '==', '高亮');
    wrap('format.code', '行内代码', 'code', 'Mod+E', '`', '`', 'code');
    wrap('format.underline', '下划线', 'underline', null, '<u>', '</u>', '下划线');

    command('format.h1', '一级标题', 'heading1', 'Mod+Alt+1', F, function () { editor().setHeading(1); });
    command('format.h2', '二级标题', 'heading2', 'Mod+Alt+2', F, function () { editor().setHeading(2); });
    command('format.h3', '三级标题', 'heading3', 'Mod+Alt+3', F, function () { editor().setHeading(3); });
    command('format.paragraph', '正文', 'paragraph', 'Mod+Alt+0', F, function () { editor().setHeading(0); });

    command('format.quote', '引用', 'quote', 'Mod+Alt+Q', F, function () {
      editor().setLinePrefix('> ');
    });
    command('format.list', '无序列表', 'list', 'Mod+Alt+L', F, function () {
      editor().setLinePrefix('- ');
    });
    command('format.listOrdered', '有序列表', 'listOrdered', 'Mod+Alt+O', F, function () {
      editor().setLinePrefix('1. ');
    });
    command('format.task', '任务清单', 'listTodo', 'Mod+Alt+T', F, function () {
      editor().setLinePrefix('- [ ] ');
    });
    command('format.callout', '提示块', 'admonition', 'Mod+Alt+H', F, function () {
      editor().transformLines(function (line) { return '> [!NOTE]\n> ' + line; });
    });
    command('format.footnote', '脚注', 'footnote', null, F, function () {
      editor().insertAtCursor('[^1]');
    });
    command('format.details', '折叠块', 'details', null, F, function () {
      insertBlock('<details>\n<summary>点击展开</summary>\n\n{{selection}}\n\n</details>', {
        selectedText: true
      });
    });
    command('format.math', '数学公式', 'math', 'Mod+Shift+E', F, function () {
      var sel = editor().getSelection();
      if (sel.text && sel.text.indexOf('\n') === -1) {
        editor().wrapSelection('$', '$', { placeholder: 'E = mc^2' });
      } else {
        insertBlock('$$\n{{selection}}\n$$', { selectedText: true });
      }
    });
    command('format.link', '插入链接', 'link', 'Mod+Alt+K', F, function () {
      var sel = editor().getSelection();
      if (sel.text) {
        editor().wrapSelection('[', '](https://)', {});
        // Put the caret inside the URL part.
        var end = editor().getSelection().end;
        editor().setSelection(end - 1, end - 1);
      } else {
        editor().insertAtCursor('[链接文字](https://)');
      }
    });
    command('format.image', '插入图片', 'image', null, F, function () {
      editor().insertAtCursor('![图片描述](图片地址)');
    });
    command('format.table', '插入表格', 'table', 'Mod+Alt+A', F, function () {
      insertBlock([
        '| 列 1 | 列 2 | 列 3 |',
        '| :--- | :---: | ---: |',
        '| 内容 | 内容 | 内容 |'
      ].join('\n'));
    });
    command('format.rule', '分隔线', 'rule', 'Mod+Alt+R', F, function () {
      insertBlock('---');
    });
    command('format.codeBlock', '代码块', 'codeBlock', 'Mod+Alt+C', F, function () {
      insertBlock('```\n{{selection}}\n```', { selectedText: true });
    });
    command('format.indent', '增加缩进', 'indent', null, F, function () {
      editor().transformLines(function (line) { return line ? '    ' + line : line; });
    });
    command('format.outdent', '减少缩进', 'outdent', null, F, function () {
      editor().transformLines(function (line) {
        var strip = /^(\t| {1,4})/.exec(line);
        return strip ? line.slice(strip[1].length) : line;
      });
    });

    command('edit.undo', '撤销', 'undo', 'Mod+Z', E, function () {
      editor().focus();
      document.execCommand('undo');
      editor().input.dispatchEvent(new Event('input', { bubbles: true }));
    });
    command('edit.redo', '重做', 'redo', 'Mod+Shift+Z', E, function () {
      editor().focus();
      document.execCommand('redo');
      editor().input.dispatchEvent(new Event('input', { bubbles: true }));
    });
    command('edit.find', '查找', 'search', 'Mod+F', E, function () { openFindBar(false); });
    command('edit.replace', '查找与替换', 'replace', 'Mod+H', E, function () { openFindBar(true); });
    command('edit.selectAll', '全选', 'text', 'Mod+A', E, function () {
      editor().setSelection(0, editor().getValue().length);
    });
    command('edit.copyAll', '复制全文', 'copy', null, E, function () {
      MD.util.copyText(editor().getValue())
        .then(function () { ui.toast.success('已复制全文'); })
        .catch(function () { ui.toast.error('复制失败'); });
    });
    command('edit.deleteLine', '删除当前行', 'trash', 'Mod+Shift+K', E, function () {
      editor().transformLines(function () { return '\u0000'; });
      // transformLines joins with \n; strip the sentinel rows instead.
      var value = editor().getValue().replace(/^\u0000\n?/gm, '').replace(/\u0000/g, '');
      editor().setValue(value);
    });
    command('edit.duplicateLine', '复制当前行', 'copy', 'Mod+Alt+D', E, function () {
      var sel = editor().getSelection();
      var value = editor().getValue();
      var lineStart = value.lastIndexOf('\n', sel.start - 1) + 1;
      var lineEnd = value.indexOf('\n', sel.end);
      if (lineEnd === -1) lineEnd = value.length;
      var line = value.slice(lineStart, lineEnd);
      editor().replaceRange(lineEnd, lineEnd, '\n' + line);
    });
    command('edit.moveLineUp', '上移行', 'arrowUp', 'Mod+Alt+Up', E, function () {
      moveLine(-1);
    });
    command('edit.moveLineDown', '下移行', 'arrowDown', 'Mod+Alt+Down', E, function () {
      moveLine(1);
    });
    command('edit.toggleComment', '切换注释', 'comment', 'Mod+/', E, function () {
      editor().transformLines(function (line) {
        if (/^\s*<!--.*-->\s*$/.test(line)) {
          return line.replace(/^(\s*)<!--\s?/, '$1').replace(/\s?-->(\s*)$/, '$1'.slice(0, 0) + '$2' === '$2' ? '$2' : '$2');
        }
        return '<!-- ' + line + ' -->';
      });
    });
    command('edit.trimTrailing', '删除行尾空格', 'eraser', null, E, function () {
      var before = editor().getValue();
      var after = before.replace(/[ \t]+$/gm, '');
      if (before === after) { ui.toast.info('没有需要清理的行尾空格'); return; }
      editor().setValue(after);
      ui.toast.success('已清理行尾空格');
    });

    function moveLine(direction) {
      var sel = editor().getSelection();
      var value = editor().getValue();
      var lineStart = value.lastIndexOf('\n', sel.start - 1) + 1;
      var lineEnd = value.indexOf('\n', sel.end);
      if (lineEnd === -1) lineEnd = value.length;
      var line = value.slice(lineStart, lineEnd);

      if (direction < 0) {
        if (lineStart === 0) return;
        var prevStart = value.lastIndexOf('\n', lineStart - 2) + 1;
        var prevLine = value.slice(prevStart, lineStart - 1);
        editor().replaceRange(prevStart, lineEnd, line + '\n' + prevLine);
        editor().setSelection(prevStart, prevStart + line.length);
      } else {
        if (lineEnd >= value.length) return;
        var nextEnd = value.indexOf('\n', lineEnd + 1);
        if (nextEnd === -1) nextEnd = value.length;
        var nextLine = value.slice(lineEnd + 1, nextEnd);
        editor().replaceRange(lineStart, nextEnd, nextLine + '\n' + line);
        editor().setSelection(lineStart + nextLine.length + 1, lineStart + nextLine.length + 1 + line.length);
      }
    }

    function command(id, label, iconName, accel, group, run, extra) {
      commands.register(Object.assign({
        id: id, label: label, icon: iconName, accel: accel, group: group, run: run
      }, extra || {}));
    }

    command('view.editor', '仅编辑', 'layoutEditor', 'Mod+1', V, function () { setView('editor'); });
    command('view.split', '分栏', 'layoutSplit', 'Mod+2', V, function () { setView('split'); });
    command('view.preview', '仅预览', 'layoutPreview', 'Mod+3', V, function () { setView('preview'); });
    command('view.toggleTheme', '切换主题', 'sun', 'Mod+Shift+L', V, function () { cycleTheme(); });
    command('view.themeLight', '亮色主题', 'sun', null, V, function () { applyTheme('light'); });
    command('view.themeDark', '暗色主题', 'moon', null, V, function () { applyTheme('dark'); });
    command('view.themeSystem', '跟随系统主题', 'monitor', null, V, function () { applyTheme('system'); });
    command('view.toggleFocus', '专注模式', 'focus', 'Mod+Shift+F', V, function () {
      toggleSetting('focusMode', $('#btn-focus-mode'));
    });
    command('view.toggleTypewriter', '打字机模式', 'type', 'Mod+Shift+D', V, function () {
      toggleSetting('typewriter', $('#btn-typewriter'));
    });
    command('view.toggleWrap', '自动换行', 'text', null, V, function () {
      toggleSetting('wrap', $('#btn-wrap'));
    });
    command('view.toggleSidebar', '显示/隐藏文档库', 'sidebar', 'Mod+\\', V, function () {
      setSidebar(dom.sidebar.hidden);
    });
    command('view.toggleOutline', '显示/隐藏大纲', 'outline', null, V, function () {
      setOutline(dom.outline.hidden);
    });
    command('view.toggleSync', '滚动同步', 'moveVertical', null, V, function () {
      var next = toggleSetting('syncScroll', $('#btn-sync-scroll'));
      ui.announce(next ? '已开启滚动同步' : '已关闭滚动同步');
    });
    command('view.zoomIn', '增大字号', 'plus', 'Mod+=', V, function () { zoomFont(1); });
    command('view.zoomOut', '减小字号', 'minus', 'Mod+-', V, function () { zoomFont(-1); });
    command('view.zoomReset', '重置字号', 'rotateCcw', 'Mod+0', V, function () {
      settings.set('fontSize', '14');
      applyAllSettings(false);
    });

    function zoomFont(delta) {
      var steps = ['12', '13', '14', '15', '16', '18', '20', '22'];
      var at = steps.indexOf(String(settings.get('fontSize')));
      if (at === -1) at = 2;
      var next = MD.util.clamp(at + delta, 0, steps.length - 1);
      settings.set('fontSize', steps[next]);
      applyAllSettings(false);
    }

    command('file.new', '新建文档', 'filePlus', 'Mod+N', FILE, function () { createDocument(); });
    command('file.save', '保存', 'save', 'Mod+S', FILE, function () { saveDocument(); });
    command('file.import', '导入文件…', 'upload', 'Mod+O', FILE, function () { pickFiles(); });
    command('file.exportMd', '导出 Markdown', 'download', null, FILE, function () { exportMarkdown(); });
    command('file.exportHtml', '导出 HTML', 'code', 'Mod+Shift+S', FILE, function () { exportHtml(); });
    command('file.print', '打印 / 导出 PDF', 'print', 'Mod+P', FILE, function () { printDocument(); });
    command('file.copyHtml', '复制 HTML', 'copy', null, FILE, function () {
      var rendered = MD.md.render(currentMarkdown());
      MD.util.copyText(rendered.html)
        .then(function () { ui.toast.success('已复制渲染后的 HTML'); })
        .catch(function () { ui.toast.error('复制失败'); });
    });
    command('file.exportAll', '导出全部数据（备份）', 'download', null, FILE, function () {
      MD.util.download('markdowner-backup-' + new Date().toISOString().slice(0, 10) + '.json',
        library.exportAll(), 'application/json');
      ui.toast.success('已导出备份', library.count() + ' 篇文档');
    });
    command('file.restoreBackup', '从备份导入', 'history', null, FILE, function () {
      var input = el('input', { type: 'file', accept: '.json,application/json', style: 'display:none' });
      input.addEventListener('change', function () {
        var file = input.files[0];
        input.remove();
        if (!file) return;
        MD.util.readAsText(file).then(function (text) {
          var result = library.importAll(text);
          if (result.error) { ui.toast.error('导入失败', result.error); return; }
          renderLibrary();
          var first = library.sorted()[0];
          if (first) openDocument(first.id, { silent: true });
          ui.toast.success('已导入 ' + result.added + ' 篇文档',
            result.skipped ? result.skipped + ' 条被跳过' : '原有文档已保留');
        });
      });
      document.body.appendChild(input);
      input.click();
    });

    command('app.palette', '命令面板', 'command', 'Mod+K', APP, function () {
      commands.openPalette({ mode: 'command' });
    });
    command('app.paletteDoc', '快速打开文档', 'folderOpen', 'Mod+P', APP, function () {
      commands.openPalette({ mode: 'document' });
    });
    command('app.goToHeading', '跳转到标题', 'outline', null, APP, function () {
      var headings = app.preview.getHeadings();
      if (!headings.length) { ui.toast.info('文档中还没有标题'); return; }
      commands.openPalette({ mode: 'goTo' });
    });
    command('app.settings', '设置', 'settings', 'Mod+,', APP, function () { openSettings(); });
    command('app.shortcuts', '键盘快捷键', 'keyboard', null, APP, function () { showShortcuts(); });
    command('app.about', '关于 MarkDowner', 'info', null, APP, function () { showAbout(); });
    command('app.reset', '清除全部数据', 'trash', null, APP, function () {
      ui.confirm({
        title: '清除全部数据',
        message: '所有文档与设置都会被删除，且无法恢复。建议先导出备份。',
        confirmLabel: '全部清除',
        danger: true
      }).then(function (ok) {
        if (!ok) return;
        var doc = library.reset();
        settings.reset();
        applyAllSettings(false);
        openDocument(doc.id, { silent: true });
        renderLibrary();
        ui.toast.success('已清除全部数据');
      });
    });
  }

  function showShortcuts() {
    var groups = Object.create(null);
    commands.shortcuts.forEach(function (shortcut) {
      var cmd = commands.get(shortcut.id);
      if (!cmd) return;
      (groups[cmd.group] || (groups[cmd.group] = [])).push({
        label: cmd.label,
        accel: shortcut.accel
      });
    });

    var body = el('div');
    Object.keys(groups).forEach(function (group) {
      var section = el('section', { className: 'dialog__section' });
      section.appendChild(el('h3', { className: 'dialog__section-title', text: group }));
      var table = el('div', { className: 'shortcut-list' });
      groups[group]
        .sort(function (a, b) { return a.label.localeCompare(b.label, 'zh-Hans-CN'); })
        .forEach(function (entry) {
          var row = el('div', { className: 'shortcut-row' });
          row.appendChild(el('span', { className: 'shortcut-row__label', text: entry.label }));
          row.appendChild(el('kbd', { text: commands.accelLabel(entry.accel) }));
          table.appendChild(row);
        });
      section.appendChild(table);
      body.appendChild(section);
    });

    ui.dialog({
      title: '键盘快捷键',
      width: 'wide',
      body: body,
      actions: [{ label: '关闭', kind: 'primary', value: true, autofocus: true }]
    });
  }

  function showAbout() {
    var body = el('div');
    body.appendChild(el('p', {
      text: 'MarkDowner —— 开箱即用的 Markdown 编辑器。'
    }));
    body.appendChild(el('p', {
      text: '无需安装、无需联网：所有资源（含字体与数学公式）都随文件一同分发。'
    }));
    var list = el('div', { className: 'about-grid' });
    [
      ['解析', 'marked'],
      ['净化', 'DOMPurify'],
      ['代码高亮', 'highlight.js'],
      ['数学公式', 'KaTeX'],
      ['字体', 'Public Sans / Libre Bodoni / JetBrains Mono'],
      ['存储', 'localStorage（本机，不上传）']
    ].forEach(function (row) {
      list.appendChild(el('div', { className: 'about-grid__key', text: row[0] }));
      list.appendChild(el('div', { className: 'about-grid__value', text: row[1] }));
    });
    body.appendChild(list);

    ui.dialog({
      title: '关于',
      body: body,
      actions: [{ label: '好的', kind: 'primary', value: true, autofocus: true }]
    });
  }

  /* ==========================================================================
   * 13. Wiring
   * ======================================================================= */

  function syncToolbarState() {
    // Cheap context detection: look at the characters immediately around the
    // caret. Doing it by inspecting the selection avoids a second tokenizer.
    var value = app.editor.getValue();
    var info = app.editor.cursorInfo();
    var active = [];

    var lineStart = value.lastIndexOf('\n', info.pos - 1) + 1;
    var lineEnd = value.indexOf('\n', info.pos);
    if (lineEnd === -1) lineEnd = value.length;
    var line = value.slice(lineStart, lineEnd);

    function inside(marker) {
      var before = value.lastIndexOf(marker, info.pos - 1);
      var after = value.indexOf(marker, info.pos);
      if (before === -1 || after === -1) return false;
      // Reject if a paragraph break sits between them.
      return value.slice(before, info.pos).indexOf('\n\n') === -1 &&
        value.slice(info.pos, after).indexOf('\n\n') === -1;
    }

    if (inside('**') || inside('__')) active.push('format.bold');
    if (inside('~~')) active.push('format.strike');
    if (inside('==')) active.push('format.mark');
    if (/^\s*#{1,6}\s/.test(line)) active.push('format.h1');
    if (/^\s*>\s/.test(line)) active.push('format.quote');
    if (/^\s*[-*+]\s+\[[ xX]\]/.test(line)) active.push('format.task');
    else if (/^\s*[-*+]\s/.test(line)) active.push('format.list');
    else if (/^\s*\d+[.)]\s/.test(line)) active.push('format.listOrdered');

    if (app.toolbar) app.toolbar.sync(active);
  }

  function wireEditor() {
    app.editor.on('change', function (value, meta) {
      if (meta && meta.source === 'set') {
        // Programmatic swap (opening a document): reset the baseline instead of
        // reporting the new document as dirty.
        app.savedContent = value;
        markDirty(false);
        updateStats();
        return;
      }
      markDirty(value !== app.savedContent);
      updateStats();
      app.preview.setSource(value, { delay: 140 });
      if (settings.get('autoSave')) persistActive();
      renderOutlineSoon(value);
    });

    app.editor.on('cursor', function () {
      updateCursor();
      syncToolbarState();
    });

    app.editor.on('file-drop', function (payload) {
      var doc = library.insert({ title: payload.name.replace(/\.[^.]+$/, ''), content: payload.text });
      renderLibrary();
      openDocument(doc.id, { silent: true });
      ui.toast.success('已导入', payload.name);
    });
  }

  var renderOutlineSoon = MD.util.debounce(function (value) {
    renderOutline(MD.md.outline(value));
  }, 320);

  function wirePreview() {
    app.preview.on('render', function (info) {
      if (info.headings && app.activeHeadingId === undefined) return;
      renderOutline(info.headings);
    });
    on(app.preview.root, 'scroll', trackOutline);

    // Anchor links inside the preview scroll the pane rather than reloading.
    on(app.preview.doc, 'click', function (event) {
      var anchor = event.target.closest('a[href^="#"]');
      if (!anchor) return;
      event.preventDefault();
      var id = decodeURIComponent(anchor.getAttribute('href').slice(1));
      if (app.sync) app.sync.claim('preview');
      if (app.preview.scrollToId(id)) {
        activeHeadingId = id;
        syncOutlineActive();
      }
    });
  }

  function wireLibrary() {
    var search = MD.util.debounce(function () { renderLibrary(); }, 140);
    on(dom.librarySearch, 'input', search);

    on($('#btn-new-doc'), 'click', function () { commands.run('file.new'); });

    on($('#btn-library-menu'), 'click', function (event) {
      var rect = event.currentTarget.getBoundingClientRect();
      ui.menu({
        x: rect.right - 200,
        y: rect.bottom + 4,
        items: [
          { label: '新建文档', icon: 'filePlus', onSelect: function () { commands.run('file.new'); } },
          {
            label: '按更新时间排序', icon: 'clock',
            onSelect: function () { settings.set('sortBy', 'updated'); renderLibrary(); }
          },
          {
            label: '按创建时间排序', icon: 'calendar',
            onSelect: function () { settings.set('sortBy', 'created'); renderLibrary(); }
          },
          {
            label: '按标题排序', icon: 'sortAsc',
            onSelect: function () { settings.set('sortBy', 'title'); renderLibrary(); }
          },
          { separator: true },
          { label: '导入文件…', icon: 'upload', onSelect: function () { commands.run('file.import'); } },
          {
            label: '导出全部数据', icon: 'download',
            onSelect: function () { commands.run('file.exportAll'); }
          },
          { separator: true },
          {
            label: '清除全部数据', icon: 'trash', danger: true,
            onSelect: function () { commands.run('app.reset'); }
          }
        ]
      });
    });
  }

  function wireChrome() {
    MD.dom.$$('.segmented__item[data-view-mode]').forEach(function (button) {
      button.addEventListener('click', function () { setView(button.dataset.viewMode); });
    });

    on($('#btn-sidebar'), 'click', function () { setSidebar(dom.sidebar.hidden, { announce: true }); });
    on($('#btn-outline'), 'click', function () { setOutline(dom.outline.hidden, { announce: true }); });
    on($('#btn-theme'), 'click', function () { cycleTheme(); });
    on($('#btn-settings'), 'click', function () { openSettings(); });
    on($('#btn-palette'), 'click', function () { commands.run('app.palette'); });

    on($('#btn-focus-mode'), 'click', function () { commands.run('view.toggleFocus'); });
    on($('#btn-typewriter'), 'click', function () { commands.run('view.toggleTypewriter'); });
    on($('#btn-wrap'), 'click', function () { commands.run('view.toggleWrap'); });
    on($('#btn-sync-scroll'), 'click', function () { commands.run('view.toggleSync'); });
    on($('#btn-copy-html'), 'click', function () { commands.run('file.copyHtml'); });
    on($('#btn-print'), 'click', function () { commands.run('file.print'); });

    // Title field: renaming the document is a first-class action, not a
    // hidden menu item.
    on(dom.titleInput, 'input', function () {
      if (!app.activeDocId) return;
      library.update(app.activeDocId, { title: dom.titleInput.value.trim() });
      markDirty(dom.titleInput.value.trim() !== '' || app.editor.getValue() !== app.savedContent);
      renderLibrary();
    });
    on(dom.titleInput, 'keydown', function (event) {
      if (event.key === 'Enter') {
        event.preventDefault();
        dom.titleInput.blur();
        app.editor.focus();
      } else if (event.key === 'Escape') {
        var doc = library.get(app.activeDocId);
        dom.titleInput.value = doc ? doc.title : '';
        dom.titleInput.blur();
      }
    });

    // Find bar
    on(dom.findInput, 'input', function () { runFind(); });
    on(dom.findInput, 'keydown', function (event) {
      if (event.key === 'Enter') {
        event.preventDefault();
        stepFind(event.shiftKey ? -1 : 1);
      } else if (event.key === 'Escape') {
        event.preventDefault();
        closeFindBar();
      }
    });
    on(dom.replaceInput, 'keydown', function (event) {
      if (event.key === 'Enter') {
        event.preventDefault();
        replaceOne();
      } else if (event.key === 'Escape') {
        event.preventDefault();
        closeFindBar();
      }
    });
    on($('#find-next'), 'click', function () { stepFind(1); });
    on($('#find-prev'), 'click', function () { stepFind(-1); });
    on($('#find-close'), 'click', closeFindBar);
    on($('#replace-one'), 'click', replaceOne);
    on($('#replace-all'), 'click', replaceAll);
    ['#find-case', '#find-word'].forEach(function (selector) {
      on($(selector), 'click', function (event) {
        var button = event.currentTarget;
        var pressed = button.getAttribute('aria-pressed') === 'true';
        button.setAttribute('aria-pressed', String(!pressed));
        runFind();
      });
    });

    // Whole-window drag & drop
    on(document, 'dragover', function (event) {
      if (!event.dataTransfer) return;
      var hasFile = Array.prototype.some.call(event.dataTransfer.types || [], function (type) {
        return type === 'Files';
      });
      if (!hasFile) return;
      event.preventDefault();
      event.dataTransfer.dropEffect = 'copy';
      dom.dropzone.hidden = false;
    });
    on(document, 'dragleave', function (event) {
      // Only hide when the pointer actually leaves the window.
      if (event.relatedTarget === null || event.clientX === 0) dom.dropzone.hidden = true;
    });
    on(document, 'drop', function (event) {
      dom.dropzone.hidden = true;
      if (!event.dataTransfer || !event.dataTransfer.files.length) return;
      event.preventDefault();
      importFiles(event.dataTransfer.files);
    });

    // Persist on exit
    on(global, 'beforeunload', function () {
      persistActive.flush();
      library.flush();
      settings.flush();
    });
  }

  /* ==========================================================================
   * 14. Boot
   * ======================================================================= */

  function boot() {
    cacheDom();
    MD.app = app;

    // 1. Theme first: nothing should flash the wrong colour scheme.
    applyTheme(settings.get('theme'), { silent: true });

    // 2. Editor and preview need a measured DOM.
    app.editor = MD.Editor.create({
      value: '',
      placeholder: '开始写作…  输入 Markdown，右侧会实时预览'
    });
    dom.editorHost.appendChild(app.editor.root);

    app.preview = MD.Preview.create({ source: '' });
    dom.previewHost.appendChild(app.preview.root);

    // 3. Decorate the static chrome with icons.
    applyStaticIcons();

    // 4. Panes and their wiring.
    installResizers();
    wireChrome();
    wireEditor();
    wirePreview();
    wireLibrary();
    app.sync = MD.Preview.linkScroll(app.editor, app.preview, {
      enabled: settings.get('syncScroll')
    });

    // 5. Commands, then the toolbar they populate.
    registerCommands();
    app.toolbar = commands.buildToolbar(dom.toolbar);
    commands.installShortcuts(function (event) {
      // Never hijack keys while a dialog or the palette owns the keyboard.
      if (ui.hasOpenLayer()) return true;
      var target = event.target;
      if (!target) return false;
      // Inside the editor, only Mod-combos are ours (plus a few handled by the
      // editor itself). Plain typing must never be intercepted.
      var tag = target.tagName;
      var isField = tag === 'INPUT' || tag === 'TEXTAREA' || target.isContentEditable;
      if (isField && event.key === 'Tab') return true;
      return false;
    });

    // 6. Settings that affect measured layout, then the first document.
    var sidebarWidth = settings.get('sidebarWidth');
    if (typeof sidebarWidth === 'number') {
      document.documentElement.style.setProperty('--sidebar-width', sidebarWidth + 'px');
    }
    var outlineWidth = settings.get('outlineWidth');
    if (typeof outlineWidth === 'number') {
      document.documentElement.style.setProperty('--outline-width', outlineWidth + 'px');
    }

    applyAllSettings(false);

    var last = library.get(settings.get('lastDocId'));
    var first = last || library.sorted()[0] || library.create({ content: '' });
    openDocument(first.id, { silent: true });

    on(global, 'md:save-state', function (event) {
      setSaveState(event.detail.state);
    });
    on(global, 'md:storage-error', function () {
      ui.toast.error('保存失败', '浏览器本地存储空间已满，请导出备份后清理旧文档。');
    });

    watchSystemTheme();

    // Re-measure when the window resizes: the panes' widths drive the preview
    // anchor ladder and the editor's wrapped-line heights.
    var onResize = MD.util.debounce(function () {
      app.preview.remeasure();
      if (app.sync) app.sync.measure();
      setView(settings.get('view'));
    }, 160);
    on(global, 'resize', onResize);

    if (!settings.get('onboardingDone')) {
      settings.set('onboardingDone', true);
      MD.dom.raf(function () {
        ui.toast.info('欢迎使用 MarkDowner', '按 ' + commands.accelLabel('Mod+K') +
          ' 打开命令面板，或按 ' + commands.accelLabel('Mod+/') + ' 查看所有快捷键。', 6000);
      });
    }

    document.body.dataset.ready = 'true';
  }

  /** Static chrome needs JS-built SVG icons; the markup stays icon-free. */
  function applyStaticIcons() {
    var map = {
      '#btn-sidebar': 'sidebar',
      '#btn-outline': 'outline',
      '#btn-palette': 'command',
      '#btn-settings': 'settings',
      '#btn-theme': THEME_ICON[settings.get('theme')] || 'monitor',
      '#btn-new-doc': 'filePlus',
      '#btn-library-menu': 'more',
      '#library-search-icon': 'search',
      '#editor-label-icon': 'pencil',
      '#preview-label-icon': 'eye',
      '#btn-focus-mode': 'focus',
      '#btn-typewriter': 'type',
      '#btn-wrap': 'text',
      '#btn-sync-scroll': 'moveVertical',
      '#btn-copy-html': 'copy',
      '#btn-print': 'print',
      '#find-prev': 'chevronUp',
      '#find-next': 'chevronDown',
      '#find-case': 'type',
      '#find-word': 'hash',
      '#find-close': 'close',
      '#dropzone-icon': 'upload'
    };
    Object.keys(map).forEach(function (selector) {
      var host = $(selector);
      if (!host) return;
      // Replace the sr-only placeholder with the glyph, keeping the label.
      var label = host.querySelector('.sr-only');
      host.textContent = '';
      host.appendChild(MD.icon(map[selector], { size: selector === '#dropzone-icon' ? 'xl' : 'md' }));
      if (label) host.appendChild(label);
    });
  }

  // The command palette reaches back into the app for headings and documents;
  // expose the entry point alongside the object itself.
  app.openDocument = openDocument;

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', boot);
  } else {
    boot();
  }
})(window.MD = window.MD || {}, window);
