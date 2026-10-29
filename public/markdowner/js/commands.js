/* MarkDowner — command registry.
 *
 * One list of commands, three consumers:
 *   - the formatting toolbar renders a subset
 *   - the command palette (Ctrl/⌘+K) renders all of them, searchable
 *   - the keyboard map binds accelerators to ids
 *
 * Defining a feature once means the button, the palette row and the shortcut
 * can never disagree about what it does — and every new command is automatically
 * discoverable in the palette, which is the difference between "has features"
 * and "features you can find".
 */
(function (MD, global) {
  'use strict';

  var el = MD.dom.el;

  var commands = [];
  var byId = Object.create(null);

  /**
   * register({ id, label, hint, icon, accel, group, run, enabled, hidden })
   *   accel   'Mod+B' style; 'Mod' resolves to ⌘ on macOS, Ctrl elsewhere
   *   group   palette section heading
   *   enabled () => boolean, evaluated when the palette is opened
   */
  function register(spec) {
    if (!spec || !spec.id) throw new Error('command requires an id');
    if (byId[spec.id]) throw new Error('duplicate command id: ' + spec.id);
    var command = {
      id: spec.id,
      label: spec.label || spec.id,
      hint: spec.hint || '',
      icon: spec.icon || null,
      accel: spec.accel || null,
      group: spec.group || '其他',
      run: spec.run || function () {},
      enabled: spec.enabled || null,
      hidden: spec.hidden === true,
      order: commands.length
    };
    commands.push(command);
    byId[spec.id] = command;
    return command;
  }

  function get(id) {
    return byId[id] || null;
  }

  function run(id, arg) {
    var command = byId[id];
    if (!command) {
      if (global.console) global.console.warn('[MarkDowner] unknown command:', id);
      return false;
    }
    if (command.enabled && !command.enabled()) return false;
    command.run(arg);
    return true;
  }

  function isEnabled(command) {
    return !command.enabled || command.enabled();
  }

  /* ==========================================================================
   * 1. Accelerator parsing and matching
   * ======================================================================= */

  var isMac = MD.util.isMac();

  /** 'Mod+Shift+K' -> { mod, shift, alt, key } */
  function parseAccel(accel) {
    if (!accel) return null;
    var parts = String(accel).split('+');
    var spec = { mod: false, shift: false, alt: false, key: '' };
    parts.forEach(function (part) {
      var token = part.trim();
      var lower = token.toLowerCase();
      if (lower === 'mod' || lower === 'cmdorctrl' || lower === 'ctrl') spec.mod = true;
      else if (lower === 'shift') spec.shift = true;
      else if (lower === 'alt' || lower === 'option') spec.alt = true;
      else spec.key = token;
    });
    return spec;
  }

  function matchesAccel(event, accel) {
    var spec = parseAccel(accel);
    if (!spec) return false;
    var modPressed = isMac ? event.metaKey : event.ctrlKey;
    if (spec.mod !== modPressed) return false;
    if (spec.shift !== event.shiftKey) return false;
    if (spec.alt !== event.altKey) return false;

    var key = spec.key;
    var eventKey = event.key;
    if (key.length === 1) {
      return eventKey.toLowerCase() === key.toLowerCase();
    }
    if (key === '/') return eventKey === '/' || eventKey === '?';
    return eventKey === key ||
      (key === 'Enter' && eventKey === 'Enter') ||
      (key === 'Escape' && eventKey === 'Escape') ||
      (key === 'Space' && (eventKey === ' ' || eventKey === 'Spacebar')) ||
      (key === 'Backspace' && eventKey === 'Backspace') ||
      (key === 'Delete' && eventKey === 'Delete') ||
      (key === 'Tab' && eventKey === 'Tab');
  }

  /** Human-readable accelerator for menus, e.g. 'Ctrl+K' / '⌘K'. */
  function accelLabel(accel) {
    if (!accel) return '';
    var spec = parseAccel(accel);
    var parts = [];
    if (spec.mod) parts.push(isMac ? '⌘' : 'Ctrl');
    if (spec.shift) parts.push(isMac ? '⇧' : 'Shift');
    if (spec.alt) parts.push(isMac ? '⌥' : 'Alt');
    var key = spec.key;
    var display = { Enter: '↵', Escape: 'Esc', Space: 'Space', Tab: 'Tab' }[key] || key;
    if (isMac) parts.push(display);
    else parts.push(display.toUpperCase());
    return isMac ? parts.join('') : parts.join('+');
  }

  /* ==========================================================================
   * 2. Palette
   *
   * Selection model: a flat index over the *visible* rows, with the DOM
   * re-rendered on each keystroke. `aria-selected` and the roving highlight
   * both read from that index, so they cannot disagree.
   * ======================================================================= */

  function openPalette(options) {
    var opts = options || {};
    var mode = opts.mode || 'command';

    return new Promise(function (resolve) {
      var input = el('input', {
        className: 'palette__input',
        type: 'text',
        placeholder: mode === 'goTo' ? '跳转到标题…' : '输入命令…',
        autocomplete: 'off',
        spellcheck: 'false',
        'aria-label': mode === 'goTo' ? '跳转到标题' : '命令面板',
        role: 'combobox',
        'aria-expanded': 'true',
        'aria-controls': 'palette-list'
      });

      var search = el('div', { className: 'palette__search' }, [
        MD.icon('search', { size: 'md', className: 'palette__search-icon' }),
        input
      ]);

      var list = el('div', {
        className: 'palette__list',
        id: 'palette-list',
        role: 'listbox'
      });

      var palette = el('div', { className: 'palette', role: 'dialog' }, [search, list]);
      var overlay = el('div', { className: 'overlay overlay--palette', hidden: true }, [palette]);
      overlay.dataset.escHandler = 'self';

      var items = [];
      var filtered = [];
      var selected = 0;

      /* ------------------------------------------------------------ sources */

      function commandItems() {
        return commands.filter(function (command) {
          if (command.hidden) return false;
          return isEnabled(command);
        }).map(function (command) {
          return {
            id: command.id,
            group: command.group,
            label: command.label,
            hint: command.accel ? accelLabel(command.accel) : (command.hint || ''),
            icon: command.icon,
            search: (command.label + ' ' + command.id + ' ' + command.group + ' ' +
              (command.hint || '')).toLowerCase(),
            payload: command.id
          };
        });
      }

      function headingItems() {
        return MD.app.preview.getHeadings().map(function (heading) {
          return {
            id: 'goto-' + heading.id,
            group: '跳转到',
            label: heading.text,
            hint: 'H' + heading.level,
            icon: 'hash',
            search: heading.text.toLowerCase(),
            payload: heading
          };
        });
      }

      function documentItems() {
        return MD.library.sorted().map(function (doc) {
          return {
            id: 'doc-' + doc.id,
            group: '文档',
            label: MD.library.title(doc),
            hint: MD.util.fmt.relative(doc.updated),
            icon: doc.starred ? 'star_filled' : 'fileText',
            search: (MD.library.title(doc) + ' ' + doc.content.slice(0, 240)).toLowerCase(),
            payload: doc.id
          };
        });
      }

      function collect() {
        if (mode === 'goTo') return headingItems();
        if (mode === 'document') return documentItems();
        // The command palette also offers headings and documents: one entry
        // point for "go to" and "do" beats teaching the user two shortcuts.
        return commandItems().concat(headingItems()).concat(documentItems());
      }

      items = collect();

      /* ------------------------------------------------------------ filter */

      /**
       * Subsequence match, the convention every command palette uses: "trp"
       * finds "toggle typewriter". Returns the score and matched indices so the
       * label can highlight them.
       */
      function fuzzy(needle, haystack) {
        if (!needle) return { score: 1, indices: [] };
        var indices = [];
        var at = 0;
        var score = 0;
        var streak = 0;
        for (var i = 0; i < needle.length; i++) {
          var found = haystack.indexOf(needle.charAt(i), at);
          if (found === -1) return null;
          if (found === at) { streak += 1; score += 2 + streak; }
          else { streak = 0; score += 1; }
          indices.push(found);
          at = found + 1;
        }
        // Prefer shorter haystacks when the match quality ties.
        score -= Math.min(20, Math.floor(haystack.length / 12));
        return { score: score, indices: indices };
      }

      function apply(query) {
        var needle = String(query || '').trim().toLowerCase();
        if (!needle) {
          filtered = items.slice(0, 60);
        } else {
          var scored = [];
          items.forEach(function (item) {
            var result = fuzzy(needle, item.search);
            if (!result) return;
            // Command labels outrank document body matches.
            var bonus = item.group === '文档' ? 0 : 8;
            scored.push({ item: item, score: result.score + bonus, indices: result.indices });
          });
          scored.sort(function (a, b) { return b.score - a.score; });
          filtered = scored.slice(0, 60).map(function (entry) {
            entry.item.matchIndices = entry.indices;
            return entry.item;
          });
        }
        selected = 0;
        renderList(needle);
      }

      function highlightLabel(label, indices, needle) {
        if (!indices || !indices.length || !needle) return document.createTextNode(label);
        // Fuzzy indices are into the *search* string, which starts with the
        // label only when the label is the first field — which it is. Clamp so
        // a body match never slices past the label.
        var frag = document.createDocumentFragment();
        var safe = indices.filter(function (i) { return i < label.length; });
        if (!safe.length) return document.createTextNode(label);
        var cursor = 0;
        safe.forEach(function (index) {
          if (index < cursor) return;
          if (index > cursor) frag.appendChild(document.createTextNode(label.slice(cursor, index)));
          var mark = el('mark');
          mark.textContent = label.charAt(index);
          frag.appendChild(mark);
          cursor = index + 1;
        });
        if (cursor < label.length) frag.appendChild(document.createTextNode(label.slice(cursor)));
        return frag;
      }

      function renderList(needle) {
        list.textContent = '';
        if (!filtered.length) {
          list.appendChild(el('p', {
            className: 'palette__empty',
            text: mode === 'goTo' ? '文档中还没有标题' : '没有匹配的命令'
          }));
          input.removeAttribute('aria-activedescendant');
          return;
        }

        var lastGroup = null;
        filtered.forEach(function (item, index) {
          if (item.group !== lastGroup) {
            lastGroup = item.group;
            list.appendChild(el('p', { className: 'palette__group-label', text: item.group }));
          }
          var row = el('button', {
            className: 'palette__item',
            type: 'button',
            role: 'option',
            id: 'palette-item-' + index,
            'aria-selected': String(index === selected)
          });
          if (item.icon) {
            row.appendChild(el('span', { className: 'palette__item-icon' },
              MD.icon(item.icon, { size: 'sm' })));
          }
          row.appendChild(el('span', { className: 'palette__item-label' },
            highlightLabel(item.label, item.matchIndices, needle)));
          if (item.hint) {
            row.appendChild(el('span', { className: 'palette__item-hint', text: item.hint }));
          }
          row.addEventListener('click', function () { choose(index); });
          row.addEventListener('mousemove', function () {
            if (selected === index) return;
            selected = index;
            syncSelection();
          });
          list.appendChild(row);
        });
        syncSelection();
      }

      function syncSelection() {
        var rows = list.querySelectorAll('.palette__item');
        for (var i = 0; i < rows.length; i++) {
          rows[i].setAttribute('aria-selected', String(i === selected));
        }
        var active = rows[selected];
        if (active) {
          input.setAttribute('aria-activedescendant', active.id);
          // `nearest` keeps a long list from jumping to the top on every step.
          if (active.scrollIntoView) active.scrollIntoView({ block: 'nearest' });
        }
      }

      function choose(index) {
        var item = filtered[index];
        if (!item) return;
        layer.close('chosen');
        window.setTimeout(function () { invoke(item); }, 0);
      }

      /**
       * Jump the preview to a heading. Sync ownership goes to the preview
       * first, so the editor follows instead of fighting the jump — the same
       * handoff the outline panel does.
       */
      function jumpTo(id) {
        if (MD.app.sync) MD.app.sync.claim('preview');
        MD.app.preview.scrollToId(id);
      }

      function invoke(item) {
        if (mode === 'goTo') {
          jumpTo(item.payload.id);
          return;
        }
        if (mode === 'document') {
          MD.app.openDocument(item.payload);
          return;
        }
        if (item.id.indexOf('goto-') === 0) {
          jumpTo(item.payload.id);
          return;
        }
        if (item.id.indexOf('doc-') === 0) {
          MD.app.openDocument(item.payload);
          return;
        }
        run(item.payload);
      }

      /* ------------------------------------------------------------- events */

      input.addEventListener('input', function () { apply(input.value); });

      input.addEventListener('keydown', function (event) {
        if (event.key === 'ArrowDown') {
          event.preventDefault();
          if (!filtered.length) return;
          selected = Math.min(selected + 1, filtered.length - 1);
          syncSelection();
        } else if (event.key === 'ArrowUp') {
          event.preventDefault();
          if (!filtered.length) return;
          selected = Math.max(selected - 1, 0);
          syncSelection();
        } else if (event.key === 'Home') {
          event.preventDefault();
          selected = 0;
          syncSelection();
        } else if (event.key === 'End') {
          event.preventDefault();
          selected = Math.max(0, filtered.length - 1);
          syncSelection();
        } else if (event.key === 'Enter') {
          event.preventDefault();
          choose(selected);
        } else if (event.key === 'Escape') {
          event.preventDefault();
          layer.close('escape');
        }
      });

      var layer = MD.ui.openLayer(overlay, {
        initialFocus: input,
        onClose: function (reason) { resolve(reason === 'chosen' ? true : false); }
      });

      apply('');
    });
  }

  /* ==========================================================================
   * 3. Toolbar builder
   *
   * The toolbar is declared as data, not markup: each entry is a command id or
   * a separator, so it stays in sync with the registry automatically.
   * ======================================================================= */

  var TOOLBAR_LAYOUT = [
    { id: 'format.bold' },
    { id: 'format.italic' },
    { id: 'format.strike' },
    { id: 'format.mark' },
    { id: 'format.code' },
    { sep: true },
    { id: 'format.h1' },
    { id: 'format.h2' },
    { id: 'format.h3' },
    { sep: true },
    { id: 'format.quote' },
    { id: 'format.list' },
    { id: 'format.listOrdered' },
    { id: 'format.task' },
    { sep: true },
    { id: 'format.link' },
    { id: 'format.image' },
    { id: 'format.table' },
    { id: 'format.rule' },
    { sep: true },
    { id: 'format.indent' },
    { id: 'format.outdent' },
    { id: 'format.math' },
    { sep: true },
    { id: 'edit.undo' },
    { id: 'edit.redo' },
    { spacer: true },
    { id: 'view.toggleFocus' },
    { id: 'edit.find' }
  ];

  function buildToolbar(host) {
    var buttons = Object.create(null);
    var groups = [];

    function currentGroup() {
      var group = el('div', { className: 'toolbar__group' });
      groups.push({ node: group, ids: [] });
      host.appendChild(group);
      return groups[groups.length - 1];
    }

    var group = currentGroup();

    TOOLBAR_LAYOUT.forEach(function (entry) {
      if (entry.sep) {
        host.appendChild(el('div', { className: 'toolbar__sep', 'aria-hidden': 'true' }));
        group = currentGroup();
        return;
      }
      if (entry.spacer) {
        host.appendChild(el('div', { className: 'toolbar__spacer' }));
        group = currentGroup();
        return;
      }
      var command = get(entry.id);
      if (!command) {
        if (global.console) global.console.warn('[MarkDowner] toolbar references unknown command:', entry.id);
        return;
      }
      var button = el('button', {
        className: 'btn btn--icon btn--ghost',
        type: 'button',
        title: command.label + (command.accel ? '  (' + accelLabel(command.accel) + ')' : ''),
        'aria-label': command.label
      });
      if (command.icon) button.appendChild(MD.icon(command.icon, { size: 'md' }));
      button.addEventListener('click', function () {
        // Keep focus in the editor: a toolbar click must not steal the caret.
        var hadFocus = MD.app.editor.isFocused();
        run(command.id);
        if (hadFocus) MD.app.editor.focus();
      });
      // mousedown-preventDefault stops the textarea from losing its selection
      // before the click handler runs — without it, bold would apply to nothing.
      button.addEventListener('mousedown', function (event) { event.preventDefault(); });
      group.node.appendChild(button);
      group.ids.push(command.id);
      buttons[command.id] = button;
    });

    return {
      /** Reflect cursor context: bold button lights up inside bold text. */
      sync: function (activeIds) {
        Object.keys(buttons).forEach(function (id) {
          var on = activeIds && activeIds.indexOf(id) !== -1;
          if (on) buttons[id].setAttribute('data-active', 'true');
          else buttons[id].removeAttribute('data-active');
        });
      },
      buttons: buttons
    };
  }

  /* ==========================================================================
   * 4. Keyboard map
   * ======================================================================= */

  var SHORTCUTS = [
    { accel: 'Mod+B', id: 'format.bold' },
    { accel: 'Mod+I', id: 'format.italic' },
    { accel: 'Mod+Shift+X', id: 'format.strike' },
    { accel: 'Mod+E', id: 'format.code' },
    { accel: 'Mod+K', id: 'app.palette' },
    { accel: 'Mod+Shift+P', id: 'app.palette' },
    { accel: 'Mod+P', id: 'app.paletteDoc' },
    { accel: 'Mod+F', id: 'edit.find' },
    { accel: 'Mod+H', id: 'edit.replace' },
    { accel: 'Mod+S', id: 'file.save' },
    { accel: 'Mod+Shift+S', id: 'file.exportHtml' },
    { accel: 'Mod+N', id: 'file.new' },
    { accel: 'Mod+O', id: 'file.import' },
    { accel: 'Mod+1', id: 'view.editor' },
    { accel: 'Mod+2', id: 'view.split' },
    { accel: 'Mod+3', id: 'view.preview' },
    { accel: 'Mod+Shift+L', id: 'view.toggleTheme' },
    { accel: 'Mod+Shift+F', id: 'view.toggleFocus' },
    { accel: 'Mod+Shift+E', id: 'format.math' },
    { accel: 'Mod+Alt+1', id: 'format.h1' },
    { accel: 'Mod+Alt+2', id: 'format.h2' },
    { accel: 'Mod+Alt+3', id: 'format.h3' },
    { accel: 'Mod+Alt+0', id: 'format.paragraph' },
    { accel: 'Mod+Alt+Q', id: 'format.quote' },
    { accel: 'Mod+Alt+L', id: 'format.list' },
    { accel: 'Mod+Alt+O', id: 'format.listOrdered' },
    { accel: 'Mod+Alt+T', id: 'format.task' },
    { accel: 'Mod+Alt+C', id: 'format.codeBlock' },
    { accel: 'Mod+Alt+K', id: 'format.link' },
    { accel: 'Mod+Alt+A', id: 'format.table' },
    { accel: 'Mod+Alt+R', id: 'format.rule' },
    { accel: 'Mod+Alt+H', id: 'format.callout' },
    { accel: 'Mod+Alt+D', id: 'edit.duplicateLine' },
    { accel: 'Mod+Alt+Up', id: 'edit.moveLineUp' },
    { accel: 'Mod+Alt+Down', id: 'edit.moveLineDown' },
    { accel: 'Mod+Alt+Delete', id: 'edit.deleteLine' },
    { accel: 'Mod+Shift+K', id: 'edit.deleteLine' },
    { accel: 'Mod+/', id: 'edit.toggleComment' },
    { accel: 'Mod+=', id: 'view.zoomIn' },
    { accel: 'Mod+-', id: 'view.zoomOut' },
    { accel: 'Mod+0', id: 'view.zoomReset' },
    { accel: 'Mod+Shift+D', id: 'view.toggleTypewriter' },
    { accel: 'Mod+\\', id: 'view.toggleSidebar' }
  ];

  /**
   * Install the global key handler.
   * `guard` lets the app suppress shortcuts while a modal or the palette owns
   * the keyboard — the check runs before any command is dispatched.
   */
  function installShortcuts(guard) {
    document.addEventListener('keydown', function (event) {
      // Ignore auto-repeat for everything except a couple of navigation keys,
      // so holding Ctrl+B does not flap bold on and off.
      if (event.repeat && event.key !== 'ArrowUp' && event.key !== 'ArrowDown') return;
      if (guard && guard(event)) return;

      // A bare Escape closes transient UI; leave it to the layer stack.
      if (event.key === 'Escape') return;

      for (var i = 0; i < SHORTCUTS.length; i++) {
        var shortcut = SHORTCUTS[i];
        if (!matchesAccel(event, shortcut.accel)) continue;
        var command = get(shortcut.id);
        if (!command || !isEnabled(command)) continue;
        event.preventDefault();
        event.stopPropagation();
        command.run();
        return;
      }
    }, true);
  }

  MD.commands = {
    register: register,
    get: get,
    run: run,
    all: function () { return commands.slice(); },
    isEnabled: isEnabled,
    accelLabel: accelLabel,
    matchesAccel: matchesAccel,
    buildToolbar: buildToolbar,
    openPalette: openPalette,
    installShortcuts: installShortcuts,
    shortcuts: SHORTCUTS
  };
})(window.MD = window.MD || {}, window);
