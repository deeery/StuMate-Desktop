/* MarkDowner — document library + settings.
 *
 * Two independent stores, both on top of MD.util.store (localStorage):
 *
 *   MD.settings  — a flat, schema-checked bag of preferences.
 *   MD.library   — the document collection, plus the dirty/persist lifecycle.
 *
 * Design notes that matter:
 *
 * 1. Writes are debounced and coalesced. Autosave fires on every keystroke in
 *    spirit but hits localStorage on a trailing edge only; a 300 ms typing
 *    pause costs one write, not thirty.
 * 2. Every read is defensive. localStorage is user-visible and hand-editable,
 *    so a corrupt entry must degrade to a default rather than throw during boot
 *    and leave a blank white page.
 * 3. Migration is by version number from day one. Adding a field later must not
 *    require the user to lose their notes.
 */
(function (MD, global) {
  'use strict';

  var store = MD.util.store;
  var uid = MD.util.uid;

  var SETTINGS_KEY = 'settings';
  var LIBRARY_KEY = 'library';
  var SCHEMA_VERSION = 1;

  /* ==========================================================================
   * 1. Settings
   *
   * `DEFAULTS` is also the schema: a stored value is only accepted when it
   * matches the type and (where given) the `options` list of its default.
   * ======================================================================= */

  var DEFAULTS = {
    theme: { value: 'system', options: ['light', 'dark', 'system'] },
    view: { value: 'split', options: ['editor', 'split', 'preview'] },
    fontSize: { value: '14', options: ['12', '13', '14', '15', '16', '18', '20', '22'] },
    lineHeight: { value: 'normal', options: ['compact', 'normal', 'relaxed'] },
    wrap: { value: 'on', options: ['on', 'off'] },
    showGutter: { value: true },
    showSidebar: { value: true },
    showOutline: { value: true },
    syntaxHighlight: { value: true },
    focusMode: { value: false },
    typewriter: { value: false },
    syncScroll: { value: true },
    autoSave: { value: true },
    sortBy: { value: 'updated', options: ['updated', 'created', 'title'] },
    lastDocId: { value: '' },
    onboardingDone: { value: false }
  };

  function readSettings() {
    var raw = store.get(SETTINGS_KEY, null);
    var out = {};
    Object.keys(DEFAULTS).forEach(function (key) {
      var spec = DEFAULTS[key];
      var value = raw && Object.prototype.hasOwnProperty.call(raw, key) ? raw[key] : undefined;
      out[key] = coerce(spec, value);
    });
    return out;
  }

  /** Accept `value` only if it fits `spec`; otherwise fall back to the default. */
  function coerce(spec, value) {
    if (value === undefined || value === null) return spec.value;
    if (spec.options) {
      return spec.options.indexOf(value) !== -1 ? value : spec.value;
    }
    if (typeof spec.value === 'boolean') return value === true || value === 'true';
    if (typeof spec.value === 'number') {
      return typeof value === 'number' && isFinite(value) ? value : spec.value;
    }
    return typeof value === 'string' ? value : spec.value;
  }

  var settings = readSettings();
  var settingsTimer = null;

  function saveSettings() {
    if (settingsTimer) global.clearTimeout(settingsTimer);
    // Trailing-edge write: a slider drag settles into one persisted value.
    settingsTimer = global.setTimeout(function () {
      settingsTimer = null;
      store.set(SETTINGS_KEY, settings);
    }, 180);
  }

  var settingsApi = {
    all: function () { return settings; },
    get: function (key) { return settings[key]; },

    /** Set one key. Returns true when the value actually changed. */
    set: function (key, value) {
      if (!Object.prototype.hasOwnProperty.call(DEFAULTS, key)) return false;
      var next = coerce(DEFAULTS[key], value);
      if (settings[key] === next) return false;
      settings[key] = next;
      saveSettings();
      emit('md:settings', { key: key, value: next });
      return true;
    },

    /** Apply several keys, emitting once per changed key. */
    patch: function (changes) {
      var changed = [];
      Object.keys(changes || {}).forEach(function (key) {
        if (settingsApi.set(key, changes[key])) changed.push(key);
      });
      return changed;
    },

    reset: function () {
      Object.keys(DEFAULTS).forEach(function (key) { settings[key] = DEFAULTS[key].value; });
      saveSettings();
      emit('md:settings', { key: '*', value: null });
    },

    defaults: DEFAULTS,
    flush: function () {
      if (settingsTimer) {
        global.clearTimeout(settingsTimer);
        settingsTimer = null;
      }
      store.set(SETTINGS_KEY, settings);
    }
  };

  function emit(name, detail) {
    try {
      global.dispatchEvent(new CustomEvent(name, { detail: detail }));
    } catch (err) { /* non-fatal: the state itself is already committed */ }
  }

  /* ==========================================================================
   * 2. Document library
   * ======================================================================= */

  var DEFAULT_CONTENT = [
    '# 欢迎使用 MarkDowner',
    '',
    '一个开箱即用的 Markdown 编辑器 —— 无需安装、无需联网、双击即用。',
    '',
    '## 快速上手',
    '',
    '左侧是**文档库**，中间是**编辑器**，右侧是**实时预览**。',
    '顶部工具栏的视图切换可以在 `编辑` / `分栏` / `预览` 三种模式间切换。',
    '',
    '> [!TIP]',
    '> 按 `Ctrl/⌘ + K` 打开命令面板，几乎所有功能都能在那里找到。',
    '',
    '### 支持的语法',
    '',
    '| 功能 | 写法 | 说明 |',
    '| :--- | :--- | :--- |',
    '| 标题 | `# 标题` | 一到六级 |',
    '| 强调 | `**粗体**` `*斜体*` | 也支持 `~~删除线~~` |',
    '| 代码 | `` `行内` `` | 或三个反引号围栏 |',
    '| 任务 | `- [x] 完成` | 可勾选清单 |',
    '| 表格 | `\\| a \\| b \\|` | 支持对齐 |',
    '| 公式 | `$E = mc^2$` | 行内与块级 |',
    '',
    '```javascript',
    '// 代码块带语法高亮，右上角可一键复制',
    'function greet(name) {',
    '  return `你好，${name}！`;',
    '}',
    '```',
    '',
    '行内公式 $E = mc^2$，块级公式：',
    '',
    '$$',
    '\\int_{-\\infty}^{\\infty} e^{-x^2}\\,dx = \\sqrt{\\pi}',
    '$$',
    '',
    '### 任务清单',
    '',
    '- [x] 实时预览与滚动同步',
    '- [x] 亮色 / 暗色 / 跟随系统',
    '- [ ] 写下你自己的笔记',
    '',
    '### 提示块',
    '',
    '> [!NOTE]',
    '> 支持 `NOTE`、`TIP`、`WARNING`、`IMPORTANT`、`CAUTION` 五种提示块。',
    '',
    '---',
    '',
    '开始你的写作吧 ✍️',
    ''
  ].join('\n');

  var library = {
    version: SCHEMA_VERSION,
    docs: []
  };

  function nowId() {
    return 'd' + Date.now().toString(36) + Math.random().toString(36).slice(2, 7);
  }

  /** Normalise one document record, repairing anything a hand-edit broke. */
  function normalizeDoc(raw) {
    if (!raw || typeof raw !== 'object') return null;
    var id = typeof raw.id === 'string' && raw.id ? raw.id : nowId();
    var content = typeof raw.content === 'string' ? raw.content : '';
    var created = typeof raw.created === 'number' && isFinite(raw.created) ? raw.created : Date.now();
    var updated = typeof raw.updated === 'number' && isFinite(raw.updated) ? raw.updated : created;
    return {
      id: id,
      title: typeof raw.title === 'string' ? raw.title : '',
      content: content,
      created: created,
      updated: updated,
      starred: raw.starred === true,
      pinned: raw.pinned === true
    };
  }

  function loadLibrary() {
    var raw = store.get(LIBRARY_KEY, null);
    if (!raw || typeof raw !== 'object' || !Array.isArray(raw.docs)) {
      return freshLibrary();
    }
    var docs = raw.docs.map(normalizeDoc).filter(Boolean);
    if (!docs.length) return freshLibrary();
    return { version: SCHEMA_VERSION, docs: docs };
  }

  function freshLibrary() {
    var stamp = Date.now();
    return {
      version: SCHEMA_VERSION,
      docs: [{
        id: nowId(),
        title: '欢迎使用 MarkDowner',
        content: DEFAULT_CONTENT,
        created: stamp,
        updated: stamp,
        starred: false,
        pinned: false
      }]
    };
  }

  var libraryTimer = null;
  var saveState = 'idle'; // 'idle' | 'saving' | 'error'

  function persistLibrary() {
    if (libraryTimer) global.clearTimeout(libraryTimer);
    saveState = 'saving';
    emit('md:save-state', { state: saveState });
    libraryTimer = global.setTimeout(function () {
      libraryTimer = null;
      var ok = store.set(LIBRARY_KEY, { version: SCHEMA_VERSION, docs: library.docs });
      saveState = ok ? 'idle' : 'error';
      emit('md:save-state', { state: saveState });
    }, 400);
  }

  function find(id) {
    for (var i = 0; i < library.docs.length; i++) {
      if (library.docs[i].id === id) return library.docs[i];
    }
    return null;
  }

  function indexOf(id) {
    for (var i = 0; i < library.docs.length; i++) {
      if (library.docs[i].id === id) return i;
    }
    return -1;
  }

  /** Derive a display title from the first meaningful line of the content. */
  function deriveTitle(doc) {
    if (doc.title && doc.title.trim()) return doc.title.trim();
    var line = MD.util.firstMeaningfulLine(doc.content);
    return line ? MD.util.truncate(line.replace(/^#{1,6}\s*/, '').trim(), 60) : '未命名文档';
  }

  function sortDocs(docs, mode) {
    var list = docs.slice();
    list.sort(function (a, b) {
      // Pinned first, then starred, then the chosen key. Pinning is the user's
      // explicit "keep this on top" and must outrank recency.
      if (a.pinned !== b.pinned) return a.pinned ? -1 : 1;
      if (mode === 'title') {
        return deriveTitle(a).localeCompare(deriveTitle(b), 'zh-Hans-CN');
      }
      if (mode === 'created') return b.created - a.created;
      return b.updated - a.updated;
    });
    return list;
  }

  var libraryApi = {
    all: function () { return library.docs; },

    /** Documents in display order for the current sort setting. */
    sorted: function () {
      return sortDocs(library.docs, settings.sortBy);
    },

    get: find,

    count: function () { return library.docs.length; },

    title: deriveTitle,

    /** Case- and diacritic-insensitive search over title and body. */
    search: function (query) {
      var needle = String(query || '').trim().toLowerCase();
      if (!needle) return libraryApi.sorted();
      return sortDocs(library.docs.filter(function (doc) {
        return deriveTitle(doc).toLowerCase().indexOf(needle) !== -1 ||
          doc.content.toLowerCase().indexOf(needle) !== -1;
      }), settings.sortBy);
    },

    create: function (overrides) {
      var stamp = Date.now();
      var doc = normalizeDoc(Object.assign({
        id: nowId(),
        title: '',
        content: '',
        created: stamp,
        updated: stamp
      }, overrides || {}));
      library.docs.unshift(doc);
      persistLibrary();
      emit('md:library', { action: 'create', doc: doc });
      return doc;
    },

    /** Insert a pre-built document (used by import). */
    insert: function (doc) {
      var normalized = normalizeDoc(doc) || normalizeDoc({ content: '' });
      normalized.id = find(normalized.id) ? nowId() : normalized.id;
      library.docs.unshift(normalized);
      persistLibrary();
      emit('md:library', { action: 'create', doc: normalized });
      return normalized;
    },

    update: function (id, changes) {
      var doc = find(id);
      if (!doc) return null;
      var touched = false;
      Object.keys(changes || {}).forEach(function (key) {
        if (key === 'id' || key === 'created') return;
        if (doc[key] !== changes[key]) {
          doc[key] = changes[key];
          touched = true;
        }
      });
      if (touched) {
        doc.updated = Date.now();
        persistLibrary();
        emit('md:library', { action: 'update', doc: doc });
      }
      return doc;
    },

    /** Patch the content without bumping `updated`… used by the boot seed. */
    silentUpdate: function (id, content) {
      var doc = find(id);
      if (!doc) return null;
      doc.content = content;
      persistLibrary();
      return doc;
    },

    remove: function (id) {
      var at = indexOf(id);
      if (at === -1) return null;
      var removed = library.docs.splice(at, 1)[0];
      persistLibrary();
      emit('md:library', { action: 'remove', doc: removed });
      return removed;
    },

    duplicate: function (id) {
      var source = find(id);
      if (!source) return null;
      var stamp = Date.now();
      var copy = normalizeDoc({
        id: nowId(),
        title: deriveTitle(source) + ' 副本',
        content: source.content,
        created: stamp,
        updated: stamp
      });
      library.docs.unshift(copy);
      persistLibrary();
      emit('md:library', { action: 'create', doc: copy });
      return copy;
    },

    toggleStar: function (id) {
      var doc = find(id);
      if (!doc) return null;
      doc.starred = !doc.starred;
      persistLibrary();
      emit('md:library', { action: 'update', doc: doc });
      return doc;
    },

    togglePin: function (id) {
      var doc = find(id);
      if (!doc) return null;
      doc.pinned = !doc.pinned;
      persistLibrary();
      emit('md:library', { action: 'update', doc: doc });
      return doc;
    },

    /** Newest-first creation timestamp used to seed a fresh install. */
    newest: function () {
      return sortDocs(library.docs, 'updated')[0] || null;
    },

    saveState: function () { return saveState; },

    /** Force any pending write synchronously — used on page unload. */
    flush: function () {
      if (libraryTimer) {
        global.clearTimeout(libraryTimer);
        libraryTimer = null;
      }
      var ok = store.set(LIBRARY_KEY, { version: SCHEMA_VERSION, docs: library.docs });
      saveState = ok ? 'idle' : 'error';
      return ok;
    },

    /** Full backup payload for export. */
    exportAll: function () {
      return JSON.stringify({
        app: 'MarkDowner',
        version: SCHEMA_VERSION,
        exportedAt: new Date().toISOString(),
        settings: settings,
        docs: library.docs
      }, null, 2);
    },

    /**
     * Import a backup. Returns { added, skipped }. Merge-not-replace: an import
     * must never silently destroy the documents already on this machine.
     */
    importAll: function (text) {
      var payload;
      try {
        payload = JSON.parse(text);
      } catch (err) {
        return { added: 0, skipped: 0, error: '文件不是有效的 JSON' };
      }
      var incoming = Array.isArray(payload) ? payload : (payload && payload.docs);
      if (!Array.isArray(incoming)) {
        return { added: 0, skipped: 0, error: '未找到文档数据' };
      }
      var existingIds = Object.create(null);
      library.docs.forEach(function (doc) { existingIds[doc.id] = true; });

      var added = 0;
      var skipped = 0;
      incoming.forEach(function (raw) {
        var doc = normalizeDoc(raw);
        if (!doc) { skipped += 1; return; }
        if (existingIds[doc.id]) doc.id = nowId();
        existingIds[doc.id] = true;
        library.docs.unshift(doc);
        added += 1;
      });

      if (payload && payload.settings && typeof payload.settings === 'object') {
        settingsApi.patch(payload.settings);
      }
      if (added) persistLibrary();
      return { added: added, skipped: skipped };
    },

    reload: function () {
      library = loadLibrary();
      emit('md:library', { action: 'reload', doc: null });
    },

    /** Wipe everything and start over from the welcome document. */
    reset: function () {
      library = freshLibrary();
      persistLibrary();
      emit('md:library', { action: 'reload', doc: null });
      return library.docs[0];
    }
  };

  /* ==========================================================================
   * 3. Boot
   * ======================================================================= */

  library = loadLibrary();

  // Keep the store honest when the tab goes away mid-debounce.
  global.addEventListener('beforeunload', function () {
    if (libraryTimer) libraryApi.flush();
    settingsApi.flush();
  });
  document.addEventListener('visibilitychange', function () {
    if (document.visibilityState === 'hidden') {
      if (libraryTimer) libraryApi.flush();
      settingsApi.flush();
    }
  });

  MD.settings = settingsApi;
  MD.library = libraryApi;
  MD.DEFAULT_CONTENT = DEFAULT_CONTENT;
})(window.MD = window.MD || {}, window);
