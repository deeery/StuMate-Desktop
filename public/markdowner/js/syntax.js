/* MarkDowner — editor backdrop tokenizer.
 *
 * The editor is a native <textarea> with a syntax-highlighted backdrop painted
 * underneath (see src/styles/editor.css). The two layers must agree on glyph
 * positions pixel-for-pixel, which imposes one hard rule on this module:
 *
 *   it may only emit spans, and every emitted span's text must concatenate
 *   back to the exact input line, character for character.
 *
 * So: no trimming, no tabs expansion, no trailing-newline normalisation.
 * Everything is escaped, never re-encoded. If a construct is not recognised it
 * is emitted verbatim as plain text — a tokenizer bug degrades the colours, it
 * never corrupts the document you are looking at.
 *
 * The tokenizer is a pure function of (line, blockState), which lets the editor
 * cache lines and re-tokenize only the tail after an edit.
 */
(function (MD) {
  'use strict';

  var escapeHtml = MD.util.escapeHtml;

  function tag(cls, inner) {
    return '<span class="' + cls + '">' + inner + '</span>';
  }

  function plain(text) {
    return escapeHtml(text);
  }

  /* ==========================================================================
   * 1. Inline tokenizer
   *
   * Hand-rolled scanner rather than one mega-regex: sticky matching keeps it
   * linear, priority order is explicit, and every rule stays readable and
   * individually testable.
   * ======================================================================= */

  var URL_TAIL = /(?:\s+["'][^"']*["'])?/y;

  var MATCHERS = [
    // Hard escape: a backslash before punctuation disables the construct.
    {
      name: 'escape',
      re: /\\./y,
      render: function (m) { return tag('tok-escape', plain(m[0])); }
    },

    // Code span. Runs of N backticks open and must be closed by exactly N.
    {
      name: 'code',
      re: /(`+)([^`]|[^`][\s\S]*?[^`])\1(?!`)/y,
      render: function (m) {
        return tag('tok-code', m[1] + plain(m[2]) + m[1]);
      }
    },
    {
      name: 'code-empty',
      re: /``+/y,
      render: function (m) { return tag('tok-code', plain(m[0])); }
    },

    // Image: ![alt](src "title")
    {
      name: 'image',
      re: /!\[([^\]]*)\]\(\s*([^\s)]*)((?:\s+["'][^"']*["'])?)\s*\)/y,
      render: function (m) {
        return tag('tok-image',
          tag('tok-link-mark', '![') + plain(m[1]) + tag('tok-link-mark', '](') +
          tag('tok-link-url', plain(m[2])) + plain(m[3]) + tag('tok-link-mark', ')'));
      }
    },

    // Wikilink: [[target]] or [[target|label]]. The target is kept visible —
    // collapsing it to the label would break the round-trip invariant.
    {
      name: 'wikilink',
      re: /\[\[([^\]|\n]+)(\|([^\]\n]+))?\]\]/y,
      render: function (m) {
        var hasLabel = m[3] !== undefined;
        return tag('tok-link-mark', '[[') +
          (hasLabel ? tag('tok-link-url', plain(m[1])) + tag('tok-link-mark', '|') : '') +
          tag('tok-link-text', plain(hasLabel ? m[3] : m[1])) +
          tag('tok-link-mark', ']]');
      }
    },

    // Reference-style footnote: [^key]
    {
      name: 'footnote',
      re: /\[\^([^\]\n]+)\]/y,
      render: function (m) { return tag('tok-footnote', plain(m[0])); }
    },

    // Link: [text](href "title")
    {
      name: 'link',
      re: /\[([^\]]*)\]\(\s*([^\s)]*)((?:\s+["'][^"']*["'])?)\s*\)/y,
      render: function (m) {
        return tag('tok-link-mark', '[') + tag('tok-link-text', plain(m[1])) +
          tag('tok-link-mark', '](') + tag('tok-link-url', plain(m[2])) +
          plain(m[3]) + tag('tok-link-mark', ')');
      }
    },

    // Shortcut reference link: [text][ref] / [text]
    {
      name: 'link-ref',
      re: /\[([^\]]+)\]\[([^\]\n]*)\]/y,
      render: function (m) {
        return tag('tok-link-mark', '[') + tag('tok-link-text', plain(m[1])) +
          tag('tok-link-mark', '][' + plain(m[2]) + ']');
      }
    },

    // Autolink and raw HTML. We only colour tags we are willing to render.
    {
      name: 'autolink',
      re: /<((?:https?|mailto|tel):[^>\s]+)>/y,
      render: function (m) {
        return tag('tok-link-mark', '&lt;') + tag('tok-link-url', plain(m[1])) +
          tag('tok-link-mark', '&gt;');
      }
    },
    {
      name: 'html',
      re: /<!--[\s\S]*?-->|<\/?[a-zA-Z][a-zA-Z0-9-]*(?:\s[^<>]*?)?\/?>/y,
      render: function (m) { return tag('tok-html', plain(m[0])); }
    },
    // A bare < that is not a tag: colour it as a likely stray angle bracket.
    {
      name: 'lt',
      re: /</y,
      render: function () { return tag('tok-html', '&lt;'); }
    },

    // Inline math: $...$ with no space just inside the delimiters.
    {
      name: 'math',
      re: /\$(?!\s)((?:\\.|[^$\\\n])+?)(?<!\s|\\)\$/y,
      render: function (m) {
        return tag('tok-math', plain('$' + m[1] + '$'));
      }
    },
    {
      name: 'math-paren',
      re: /\\\(([\s\S]+?)\\\)/y,
      render: function (m) { return tag('tok-math', plain(m[0])); }
    },

    // Emphasis family, strongest first.
    {
      name: 'strong-em',
      re: /\*\*\*(?=\S)([\s\S]*?\S)\*\*\*/y,
      render: function (m) {
        return tag('tok-strong', tag('tok-em', '***' + inline(m[1]) + '***'));
      }
    },
    {
      name: 'strong-star',
      re: /\*\*(?=\S)([\s\S]*?\S)\*\*/y,
      render: function (m) { return tag('tok-strong', '**' + inline(m[1]) + '**'); }
    },
    {
      name: 'strong-underscore',
      re: /__(?=\S)([\s\S]*?\S)__/y,
      guard: function (str, i) { return !isWordChar(str.charAt(i - 1)); },
      render: function (m) { return tag('tok-strong', '__' + inline(m[1]) + '__'); }
    },
    {
      name: 'em-star',
      re: /\*(?=\S)([\s\S]*?\S)\*/y,
      render: function (m) { return tag('tok-em', '*' + inline(m[1]) + '*'); }
    },
    {
      name: 'em-underscore',
      // GFM forbids intra-word `_` emphasis; without this guard snake_case
      // identifiers and file_names would be rendered as emphasis.
      re: /_(?=\S)([\s\S]*?\S)_(?!\w)/y,
      guard: function (str, i) { return !isWordChar(str.charAt(i - 1)); },
      render: function (m) { return tag('tok-em', '_' + inline(m[1]) + '_'); }
    },
    {
      name: 'strike',
      re: /~~(?=\S)([\s\S]*?\S)~~/y,
      render: function (m) { return tag('tok-strike', '~~' + inline(m[1]) + '~~'); }
    },
    {
      name: 'mark',
      re: /==(?=\S)([\s\S]*?\S)==/y,
      render: function (m) { return tag('tok-mark', '==' + inline(m[1]) + '=='); }
    },

    // Bare URL: colour it, but keep the text exact.
    {
      name: 'bare-url',
      re: /(?:https?:\/\/|www\.)[^\s<>()\[\]"']+/y,
      guard: function (str, i) {
        var prev = str.charAt(i - 1);
        return prev !== '(' && prev !== '<' && prev !== '"' && prev !== "'" && prev !== '/';
      },
      render: function (m) { return tag('tok-link-url', plain(m[0])); }
    }
  ];

  var WORD_RE = /[\w\u00C0-\u024F\u4E00-\u9FFF]/;

  function isWordChar(ch) {
    return !!ch && WORD_RE.test(ch);
  }

  function matchAt(str, index) {
    for (var i = 0; i < MATCHERS.length; i++) {
      var matcher = MATCHERS[i];
      if (matcher.guard && !matcher.guard(str, index)) continue;
      matcher.re.lastIndex = index;
      var result = matcher.re.exec(str);
      if (result && result.index === index) {
        return { matcher: matcher, m: result };
      }
    }
    return null;
  }

  var INLINE_MAX_DEPTH = 6;

  function inline(text, depth) {
    if (!text) return '';
    depth = depth || 0;
    if (depth > INLINE_MAX_DEPTH) return plain(text);

    var out = '';
    var buffer = '';
    var i = 0;

    while (i < text.length) {
      var hit = matchAt(text, i);
      if (!hit) {
        buffer += text.charAt(i);
        i += 1;
        continue;
      }
      if (buffer) { out += plain(buffer); buffer = ''; }
      var consumed = hit.m[0];
      // A matcher must always advance, otherwise the loop spins forever.
      if (!consumed.length) {
        buffer += text.charAt(i);
        i += 1;
        continue;
      }
      out += hit.matcher.render(hit.m, text, i, depth);
      i += consumed.length;
    }
    if (buffer) out += plain(buffer);
    return out;
  }

  /* ==========================================================================
   * 2. Block-level line classification
   *
   * state is a mutable cursor shared across a document's lines. It only ever
   * holds fence/frontmatter/table context, so re-tokenizing a suffix is cheap.
   * ======================================================================= */

  function newState() {
    return { fence: null, fenceFence: false, frontmatter: false, table: false };
  }

  function cloneState(state) {
    return {
      fence: state.fence,
      fenceFence: state.fenceFence,
      frontmatter: state.frontmatter,
      table: state.table
    };
  }

  var RE_FENCE_OPEN = /^(\s{0,3})(`{3,}|~{3,})(.*)$/;
  var RE_FRONTMATTER = /^(---|\+\+\+)\s*$/;
  var RE_ATX = /^(\s{0,3})(#{1,6})(\s+(.*))?$/;
  var RE_SETEXT = /^\s{0,3}(=+|-+)\s*$/;
  var RE_RULE = /^\s{0,3}(?:(?:\*\s*){3,}|(?:-\s*){3,}|(?:_\s*){3,})$/;
  var RE_QUOTE = /^(\s{0,3})((?:>\s?)+)(.*)$/;
  var RE_LIST = /^(\s*)([-*+]|\d{1,9}[.)])(\s+)(.*)$/;
  var RE_TASK = /^(\[[ xX]\])(\s*)([\s\S]*)$/;
  var RE_INDENT_CODE = /^(?: {4}|\t)/;
  var RE_TABLE_SEP = /^\s*\|?(?:\s*:?-{1,}:?\s*\|)+\s*:?-{1,}:?\s*\|?\s*$/;
  var RE_MATH_BLOCK = /^\s*\$\$\s*$/;

  /**
   * Split a table row into ordered parts, preserving every character.
   * Emits {pipe:true} for each `|` and {pipe:false, text} for the runs between
   * them, so concatenating the parts reproduces the line exactly.
   */
  function splitTableRow(line) {
    var parts = [];
    var current = '';
    for (var i = 0; i < line.length; i++) {
      var ch = line.charAt(i);
      if (ch === '\\' && i + 1 < line.length) {
        current += ch + line.charAt(i + 1);
        i += 1;
      } else if (ch === '|') {
        parts.push({ pipe: false, text: current });
        parts.push({ pipe: true, text: '|' });
        current = '';
      } else {
        current += ch;
      }
    }
    parts.push({ pipe: false, text: current });
    return parts;
  }

  function renderTableRow(line, isSeparator) {
    var parts = splitTableRow(line);
    var out = '';
    for (var i = 0; i < parts.length; i++) {
      var part = parts[i];
      if (part.pipe) {
        out += tag('tok-table-mark', '|');
        continue;
      }
      if (!part.text) continue;
      if (isSeparator) {
        out += tag('tok-table-mark', plain(part.text));
        continue;
      }
      var leading = /^\s*/.exec(part.text)[0];
      var trailing = /\s*$/.exec(part.text)[0];
      var core = part.text.slice(leading.length, part.text.length - trailing.length);
      out += plain(leading) + tag('tok-table', inline(core)) + plain(trailing);
    }
    return out;
  }

  /**
   * tokenizeLine(line, state, prevLine, nextLine) -> html string
   * Mutates `state`. The returned html always decodes back to `line`.
   *
   * `nextLine` exists solely for tables: a header row is only identifiable
   * because the delimiter row follows it, so one line of lookahead is required.
   */
  function tokenizeLine(line, state, prevLine, nextLine) {
    // ---- inside a fenced code block -------------------------------------
    if (state.fence) {
      var closing = state.fenceFence
        ? new RegExp('^\\s{0,3}' + (state.fence === '`' ? '`{3,}' : '~{3,}') + '\\s*$')
        : null;
      if (closing && closing.test(line)) {
        state.fence = null;
        state.fenceFence = false;
        return tag('tok-fence', plain(line));
      }
      return tag('tok-code', plain(line));
    }

    // ---- YAML/TOML frontmatter ------------------------------------------
    if (state.frontmatter) {
      if (RE_FRONTMATTER.test(line)) {
        state.frontmatter = false;
        return tag('tok-fence', plain(line));
      }
      return tag('tok-quote', plain(line));
    }
    if (line === '' && prevLine === undefined) {
      // Very first line empty: nothing special, fall through.
    }
    if (prevLine === undefined && RE_FRONTMATTER.test(line)) {
      state.frontmatter = true;
      return tag('tok-fence', plain(line));
    }

    // ---- display math block ---------------------------------------------
    if (state.fence === null && RE_MATH_BLOCK.test(line)) {
      // A lone `$$` toggles math mode; reuse fence slot with a marker.
      if (state.mathBlock) {
        state.mathBlock = false;
        return tag('tok-math', plain(line));
      }
      state.mathBlock = true;
      return tag('tok-math', plain(line));
    }
    if (state.mathBlock) return tag('tok-math', plain(line));

    // ---- empty line ------------------------------------------------------
    if (line === '') {
      state.table = false;
      return '';
    }

    // ---- fenced code opening --------------------------------------------
    var fenceOpen = RE_FENCE_OPEN.exec(line);
    if (fenceOpen && fenceOpen[2].charAt(0) === '`' && fenceOpen[3].indexOf('`') !== -1) {
      fenceOpen = null; // an info string may not contain backticks
    }
    if (fenceOpen) {
      state.fence = fenceOpen[2].charAt(0);
      state.fenceFence = true;
      state.table = false;
      var indent = fenceOpen[1];
      var marks = fenceOpen[2];
      var info = fenceOpen[3];
      return plain(indent) + tag('tok-fence', plain(marks)) +
        (info ? tag('tok-fence-info', plain(info)) : '');
    }

    // ---- thematic break (before list, so `---` is a rule) ---------------
    if (RE_RULE.test(line)) {
      state.table = false;
      return tag('tok-rule', plain(line));
    }

    // ---- table ----------------------------------------------------------
    // A header row is only a header because the delimiter row follows it, so
    // this is the one place the tokenizer needs lookahead.
    var isSep = RE_TABLE_SEP.test(line) && line.indexOf('|') !== -1;
    var isHeader = !state.table && nextLine !== undefined &&
      line.indexOf('|') !== -1 && RE_TABLE_SEP.test(nextLine) && nextLine.indexOf('|') !== -1;
    if (isSep || isHeader || (state.table && line.indexOf('|') !== -1)) {
      state.table = true;
      return renderTableRow(line, isSep);
    }
    state.table = false;

    // ---- blockquote -----------------------------------------------------
    var quote = RE_QUOTE.exec(line);
    if (quote) {
      var marks2 = quote[2];
      var rest = quote[3];
      var callout = /^\[!(NOTE|TIP|WARNING|IMPORTANT|CAUTION)\]/i.exec(rest);
      if (callout) {
        // Only the keyword is tagged; the remainder keeps its exact spacing so
        // the backdrop stays character-identical to the textarea.
        return plain(quote[1]) + tag('tok-quote-mark', plain(marks2)) +
          tag('tok-admonition', plain(callout[0])) + inline(rest.slice(callout[0].length));
      }
      return plain(quote[1]) + tag('tok-quote-mark', plain(marks2)) +
        tag('tok-quote', inline(rest));
    }

    // ---- ATX heading ----------------------------------------------------
    var atx = RE_ATX.exec(line);
    if (atx) {
      var body = atx[4] === undefined ? '' : atx[4];
      return plain(atx[1]) + tag('tok-heading-mark', plain(atx[2])) +
        (body ? plain(' ') + tag('tok-heading', inline(body)) : '');
    }

    // ---- setext heading underline ---------------------------------------
    if (RE_SETEXT.test(line) && prevLine !== undefined && prevLine.trim() &&
        !RE_RULE.test(line) && !RE_LIST.test(prevLine) && !RE_ATX.test(prevLine)) {
      return tag('tok-heading-mark', plain(line));
    }

    // ---- list item ------------------------------------------------------
    var list = RE_LIST.exec(line);
    if (list) {
      var after = list[4];
      var taskMatch = RE_TASK.exec(after);
      var head = plain(list[1]) + tag('tok-list-mark', plain(list[2])) + plain(list[3]);
      if (taskMatch) {
        return head + tag('tok-task', plain(taskMatch[1])) + plain(taskMatch[2]) +
          inline(taskMatch[3]);
      }
      return head + inline(after);
    }

    // ---- indented code --------------------------------------------------
    if (RE_INDENT_CODE.test(line) && prevLine !== undefined && prevLine === '') {
      return tag('tok-code', plain(line));
    }

    // ---- HTML block -----------------------------------------------------
    if (/^\s{0,3}</.test(line)) {
      return tag('tok-html', inline(line));
    }

    // ---- paragraph ------------------------------------------------------
    return inline(line);
  }

  /* ==========================================================================
   * 3. Public API
   * ======================================================================= */

  /**
   * highlightLines(source, seedState) -> { lines: string[], state }
   * `lines[i]` is the html for source line i (no trailing newline included).
   * Accepts either a string or a pre-split array, so the editor can reuse an
   * already-split document instead of splitting it a second time.
   */
  function highlightLines(source, seedState) {
    var state = seedState ? cloneState(seedState) : newState();
    var raw = Array.isArray(source) ? source : String(source).split('\n');
    var lines = new Array(raw.length);
    for (var i = 0; i < raw.length; i++) {
      lines[i] = tokenizeLine(
        raw[i],
        state,
        i === 0 ? undefined : raw[i - 1],
        i + 1 < raw.length ? raw[i + 1] : undefined
      );
    }
    return { lines: lines, state: state };
  }

  /** Joined html — handy for tests and for one-shot rendering. */
  function highlight(source) {
    return highlightLines(source).lines.join('\n');
  }

  /** Create/reset a block state cursor (exported so the editor can cache one). */
  function state() {
    return newState();
  }

  MD.syntax = {
    highlight: highlight,
    highlightLines: highlightLines,
    tokenizeLine: tokenizeLine,
    inline: inline,
    state: state,
    cloneState: cloneState,
    isWordChar: isWordChar
  };
})(window.MD = window.MD || {});
