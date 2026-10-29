/**
 * MarkDowner — Icon set
 *
 * One icon family only: a 24×24 grid, 1.75px stroke, round caps and joins,
 * `fill: none`. Consistency is enforced structurally — every glyph comes from
 * this single map, so the UI can never drift into mixed icon styles.
 *
 * Rule honoured from the design system: no emoji as structural icons.
 * Brand/pictographic emoji appear nowhere in the chrome.
 *
 * Classic script (no ES modules) so the app runs from file:// by double-click.
 * Exposes: MD.icon(name, opts) / MD.filledIcon(name, opts) / MD.ICONS
 */
(function (global) {
  'use strict';

  var MD = (global.MD = global.MD || {});

  /** @type {Record<string, string>} name → inner SVG markup (children of <svg>) */
  var ICONS = {
  /* ----- Text formatting -------------------------------------------------- */
  bold: '<path d="M6 4h8a4 4 0 0 1 4 4 4 4 0 0 1-4 4H6z"/><path d="M6 12h9a4 4 0 0 1 4 4 4 4 0 0 1-4 4H6z"/>',
  italic:
    '<line x1="19" y1="4" x2="10" y2="4"/><line x1="14" y1="20" x2="5" y2="20"/><line x1="15" y1="4" x2="9" y2="20"/>',
  strikethrough:
    '<path d="M16 4H9a3 3 0 0 0-2.83 4"/><path d="M14 12a4 4 0 0 1 0 8H6"/><line x1="4" y1="12" x2="20" y2="12"/>',
  underline: '<path d="M6 4v6a6 6 0 0 0 12 0V4"/><line x1="4" y1="20" x2="20" y2="20"/>',
  highlight:
    '<path d="m9 11-6 6v3h9l3-3"/><path d="m22 12-4.6 4.6a2 2 0 0 1-2.8 0l-5.2-5.2a2 2 0 0 1 0-2.8L14 4Z"/>',

  /* ----- Headings & structure -------------------------------------------- */
  heading: '<path d="M6 12h12"/><path d="M6 20V4"/><path d="M18 20V4"/>',
  heading1: '<path d="M4 12h8"/><path d="M4 18V6"/><path d="M12 18V6"/><path d="m17 12 3-2v8"/>',
  heading2:
    '<path d="M4 12h8"/><path d="M4 18V6"/><path d="M12 18V6"/><path d="M21 18h-4c0-4 4-3 4-6 0-1.5-2-2.5-4-1"/>',
  heading3:
    '<path d="M4 12h8"/><path d="M4 18V6"/><path d="M12 18V6"/><path d="M17.5 10.5c1.7-1 3.5 0 3.5 1.5a2 2 0 0 1-2 2"/><path d="M17 17.5c2 1.5 4 .3 4-1.5a2 2 0 0 0-2-2"/>',
  paragraph: '<path d="M13 4v16"/><path d="M17 4v16"/><path d="M19 4H9.5a4.5 4.5 0 0 0 0 9H13"/>',

  /* ----- Lists ------------------------------------------------------------ */
  list: '<line x1="8" y1="6" x2="21" y2="6"/><line x1="8" y1="12" x2="21" y2="12"/><line x1="8" y1="18" x2="21" y2="18"/><circle cx="3.5" cy="6" r="1.25"/><circle cx="3.5" cy="12" r="1.25"/><circle cx="3.5" cy="18" r="1.25"/>',
  listOrdered:
    '<line x1="10" y1="6" x2="21" y2="6"/><line x1="10" y1="12" x2="21" y2="12"/><line x1="10" y1="18" x2="21" y2="18"/><path d="M4 6h1v4"/><path d="M4 10h2"/><path d="M6 18H4c0-1 2-2 2-3s-1-1.5-2-1"/>',
  listTodo:
    '<path d="m3 7 2 2 3-4"/><path d="m3 17 2 2 3-4"/><line x1="13" y1="8" x2="21" y2="8"/><line x1="13" y1="18" x2="21" y2="18"/>',
  indent: '<polyline points="3 8 7 12 3 16"/><line x1="11" y1="12" x2="21" y2="12"/><line x1="11" y1="6" x2="21" y2="6"/><line x1="11" y1="18" x2="21" y2="18"/>',
  outdent: '<polyline points="7 8 3 12 7 16"/><line x1="11" y1="12" x2="21" y2="12"/><line x1="11" y1="6" x2="21" y2="6"/><line x1="11" y1="18" x2="21" y2="18"/>',

  /* ----- Blocks ----------------------------------------------------------- */
  quote:
    '<path d="M17 6H3"/><path d="M21 12H8"/><path d="M21 18H8"/><path d="M3 12v6"/>',
  code: '<polyline points="16 18 22 12 16 6"/><polyline points="8 6 2 12 8 18"/>',
  codeBlock:
    '<path d="m10 9-3 3 3 3"/><path d="m14 15 3-3-3-3"/><rect x="3" y="3" width="18" height="18" rx="2"/>',
  link: '<path d="M10 13a5 5 0 0 0 7.54.54l3-3a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="M14 11a5 5 0 0 0-7.54-.54l-3 3a5 5 0 0 0 7.07 7.07l1.71-1.71"/>',
  unlink:
    '<path d="m18.84 12.25 1.72-1.71a5 5 0 0 0-7.07-7.07l-1.72 1.71"/><path d="m5.17 11.75-1.71 1.71a5 5 0 0 0 7.07 7.07l1.71-1.71"/><line x1="8" y1="2" x2="8" y2="5"/><line x1="2" y1="8" x2="5" y2="8"/><line x1="16" y1="19" x2="16" y2="22"/><line x1="19" y1="16" x2="22" y2="16"/>',
  image:
    '<rect x="3" y="3" width="18" height="18" rx="2"/><circle cx="9" cy="9" r="2"/><path d="m21 15-3.09-3.09a2 2 0 0 0-2.82 0L6 21"/>',
  table:
    '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M3 9h18"/><path d="M3 15h18"/><path d="M12 3v18"/>',
  rule: '<line x1="4" y1="12" x2="20" y2="12"/>',
  math: '<path d="M18 4H6l5 8-5 8h12"/><path d="M13 9l5 6"/><path d="M18 9l-5 6"/>',
  footnote: '<path d="M4 16h4"/><path d="M6 8v8"/><path d="M14 8h6"/><path d="M14 12h4"/><path d="M14 16h6"/>',
  admonition:
    '<path d="M12 8v5"/><circle cx="12" cy="16.5" r="0.75"/><path d="M10.3 3.6 2.4 17.3A1.9 1.9 0 0 0 4 20h16a1.9 1.9 0 0 0 1.6-2.7L13.7 3.6a1.9 1.9 0 0 0-3.4 0Z"/>',
  comment: '<path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z"/>',
  details:
    '<path d="m9 8 4 4-4 4"/><rect x="3" y="3" width="18" height="18" rx="2"/>',

  /* ----- History ---------------------------------------------------------- */
  undo: '<path d="M3 7v6h6"/><path d="M21 17a9 9 0 0 0-9-9 9 9 0 0 0-6 2.3L3 13"/>',
  redo: '<path d="M21 7v6h-6"/><path d="M3 17a9 9 0 0 1 9-9 9 9 0 0 1 6 2.3L21 13"/>',
  eraser:
    '<path d="m7 21-4.3-4.3a1 1 0 0 1 0-1.4L13.4 4.6a1 1 0 0 1 1.4 0l5.6 5.6a1 1 0 0 1 0 1.4L11.6 21"/><path d="M22 21H7"/><path d="m5 11 9 9"/>',

  /* ----- App chrome ------------------------------------------------------- */
  sidebar: '<rect x="3" y="3" width="18" height="18" rx="2"/><line x1="9" y1="3" x2="9" y2="21"/>',
  outline: '<line x1="4" y1="6" x2="14" y2="6"/><line x1="7" y1="12" x2="20" y2="12"/><line x1="10" y1="18" x2="20" y2="18"/>',
  layoutEditor: '<rect x="3" y="3" width="18" height="18" rx="2"/><line x1="12" y1="3" x2="12" y2="21"/>',
  layoutSplit: '<rect x="3" y="3" width="18" height="18" rx="2"/><line x1="12" y1="3" x2="12" y2="21"/>',
  layoutPreview: '<rect x="3" y="3" width="18" height="18" rx="2"/><line x1="3" y1="8" x2="21" y2="8"/>',
  eye: '<path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/>',
  eyeOff:
    '<path d="M9.9 5.2A9.9 9.9 0 0 1 12 5c6.4 0 10 7 10 7a17 17 0 0 1-2.8 3.7"/><path d="M6.3 6.3A17 17 0 0 0 2 12s3.6 7 10 7a9.6 9.6 0 0 0 4.6-1.1"/><line x1="2" y1="2" x2="22" y2="22"/>',

  /* ----- Files & documents ------------------------------------------------ */
  file: '<path d="M14 2H7a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7z"/><polyline points="14 2 14 8 20 8"/>',
  filePlus:
    '<path d="M14 2H7a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7z"/><polyline points="14 2 14 8 20 8"/><line x1="12" y1="12" x2="12" y2="18"/><line x1="9" y1="15" x2="15" y2="15"/>',
  fileText:
    '<path d="M14 2H7a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V7z"/><polyline points="14 2 14 8 20 8"/><line x1="9" y1="13" x2="15" y2="13"/><line x1="9" y1="17" x2="13" y2="17"/>',
  folder:
    '<path d="M20 20H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2h4l2 3h8a2 2 0 0 1 2 2Z"/>',
  folderOpen:
    '<path d="M4 20h14a2 2 0 0 0 1.9-1.4L22 12H6a2 2 0 0 0-1.9 1.4L2 20z"/><path d="M2 18V6a2 2 0 0 1 2-2h4l2 3h8a2 2 0 0 1 2 2v3"/>',
  save: '<path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z"/><polyline points="17 21 17 13 7 13 7 21"/><polyline points="7 3 7 8 15 8"/>',
  download:
    '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/>',
  upload:
    '<path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="17 8 12 3 7 8"/><line x1="12" y1="3" x2="12" y2="15"/>',
  print:
    '<polyline points="6 9 6 2 18 2 18 9"/><path d="M6 18H4a2 2 0 0 1-2-2v-5a2 2 0 0 1 2-2h16a2 2 0 0 1 2 2v5a2 2 0 0 1-2 2h-2"/><rect x="6" y="14" width="12" height="8" rx="1"/>',
  history:
    '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><polyline points="3 3 3 8 8 8"/><polyline points="12 7 12 12 15.5 14"/>',
  star: '<polygon points="12 2 15.1 8.3 22 9.3 17 14.1 18.2 21 12 17.8 5.8 21 7 14.1 2 9.3 8.9 8.3"/>',
  pin: '<line x1="12" y1="17" x2="12" y2="22"/><path d="M5 17h14l-1.5-4V4h1a1 1 0 0 0 0-2h-13a1 1 0 0 0 0 2h1v9z"/>',

  /* ----- Actions ---------------------------------------------------------- */
  plus: '<line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/>',
  minus: '<line x1="5" y1="12" x2="19" y2="12"/>',
  close: '<line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>',
  check: '<polyline points="20 6 9 17 4 12"/>',
  checkCircle: '<circle cx="12" cy="12" r="10"/><polyline points="16.5 9 10.8 15 7.5 11.7"/>',
  alertCircle: '<circle cx="12" cy="12" r="10"/><line x1="12" y1="8" x2="12" y2="12.5"/><circle cx="12" cy="16" r="0.75"/>',
  alertTriangle:
    '<path d="M10.3 3.6 2.4 17.3A1.9 1.9 0 0 0 4 20h16a1.9 1.9 0 0 0 1.6-2.7L13.7 3.6a1.9 1.9 0 0 0-3.4 0Z"/><line x1="12" y1="9" x2="12" y2="13"/><circle cx="12" cy="16.5" r="0.75"/>',
  info: '<circle cx="12" cy="12" r="10"/><line x1="12" y1="11" x2="12" y2="16"/><circle cx="12" cy="8" r="0.75"/>',
  copy: '<rect x="9" y="9" width="12" height="12" rx="2"/><path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"/>',
  trash: '<path d="M3 6h18"/><path d="M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6"/><line x1="10" y1="11" x2="10" y2="17"/><line x1="14" y1="11" x2="14" y2="17"/>',
  pencil:
    '<path d="M11 4H4a2 2 0 0 0-2 2v14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2v-7"/><path d="M18.5 2.5a2.12 2.12 0 0 1 3 3L12 15l-4 1 1-4Z"/>',
  rotateCcw: '<path d="M3 12a9 9 0 1 0 3-6.7L3 8"/><polyline points="3 3 3 8 8 8"/>',
  search: '<circle cx="11" cy="11" r="8"/><line x1="21" y1="21" x2="16.65" y2="16.65"/>',
  replace:
    '<path d="M15 4h5a2 2 0 0 1 2 2v3"/><polyline points="19 2 22 5 19 8"/><path d="M9 20H4a2 2 0 0 1-2-2v-3"/><polyline points="5 22 2 19 5 16"/><path d="M17 9v9a2 2 0 0 1-2 2H9a2 2 0 0 1-2-2V9a2 2 0 0 1 2-2h6a2 2 0 0 1 2 2Z"/>',
  filter: '<polygon points="22 3 2 3 10 12.5 10 19 14 21 14 12.5"/>',
  settings:
    '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5v.1a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1.1-1.5 1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1A1.7 1.7 0 0 0 4.6 9a1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9V9a1.7 1.7 0 0 0 1.5 1h.1a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1Z"/>',
  more: '<circle cx="12" cy="5" r="1.5"/><circle cx="12" cy="12" r="1.5"/><circle cx="12" cy="19" r="1.5"/>',
  command:
    '<path d="M15 6a3 3 0 1 1 3 3h-3zM6 9a3 3 0 1 1 3-3v3zM9 18a3 3 0 1 1-3-3h3zM18 15a3 3 0 1 1-3 3v-3z"/><rect x="9" y="9" width="6" height="6"/>',
  keyboard:
    '<rect x="2" y="6" width="20" height="12" rx="2"/><line x1="6" y1="10" x2="6" y2="10"/><line x1="10" y1="10" x2="10" y2="10"/><line x1="14" y1="10" x2="14" y2="10"/><line x1="18" y1="10" x2="18" y2="10"/><line x1="7" y1="14" x2="17" y2="14"/>',
  sparkle:
    '<path d="M12 3v4"/><path d="M12 17v4"/><path d="M3 12h4"/><path d="M17 12h4"/><path d="m6.3 6.3 2.5 2.5"/><path d="m15.2 15.2 2.5 2.5"/><path d="m17.7 6.3-2.5 2.5"/><path d="m8.8 15.2-2.5 2.5"/>',
  chevronDown: '<polyline points="6 9 12 15 18 9"/>',
  chevronRight: '<polyline points="9 6 15 12 9 18"/>',
  chevronLeft: '<polyline points="15 6 9 12 15 18"/>',
  chevronUp: '<polyline points="6 15 12 9 18 15"/>',
  arrowDown: '<line x1="12" y1="5" x2="12" y2="19"/><polyline points="6 13 12 19 18 13"/>',
  arrowUp: '<line x1="12" y1="19" x2="12" y2="5"/><polyline points="6 11 12 5 18 11"/>',
  cornerDownLeft: '<polyline points="9 10 4 15 9 20"/><path d="M20 4v7a4 4 0 0 1-4 4H4"/>',
  moveVertical: '<circle cx="9" cy="6" r="1.2"/><circle cx="9" cy="12" r="1.2"/><circle cx="9" cy="18" r="1.2"/><circle cx="15" cy="6" r="1.2"/><circle cx="15" cy="12" r="1.2"/><circle cx="15" cy="18" r="1.2"/>',

  /* ----- Theme ------------------------------------------------------------ */
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2"/><path d="M12 20v2"/><path d="m4.9 4.9 1.4 1.4"/><path d="m17.7 17.7 1.4 1.4"/><path d="M2 12h2"/><path d="M20 12h2"/><path d="m6.3 17.7-1.4 1.4"/><path d="m19.1 4.9-1.4 1.4"/>',
  moon: '<path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8Z"/>',
  monitor:
    '<rect x="2" y="3" width="20" height="14" rx="2"/><line x1="8" y1="21" x2="16" y2="21"/><line x1="12" y1="17" x2="12" y2="21"/>',

  /* ----- Status & meta ---------------------------------------------------- */
  clock: '<circle cx="12" cy="12" r="10"/><polyline points="12 6.5 12 12 16 14"/>',
  calendar:
    '<rect x="3" y="5" width="18" height="16" rx="2"/><line x1="3" y1="10" x2="21" y2="10"/><line x1="8" y1="3" x2="8" y2="7"/><line x1="16" y1="3" x2="16" y2="7"/>',
  type: '<polyline points="4 7 4 4 20 4 20 7"/><line x1="9" y1="20" x2="15" y2="20"/><line x1="12" y1="4" x2="12" y2="20"/>',
  hash: '<line x1="4" y1="9" x2="20" y2="9"/><line x1="4" y1="15" x2="20" y2="15"/><line x1="10" y1="3" x2="8" y2="21"/><line x1="16" y1="3" x2="14" y2="21"/>',
  text: '<path d="M17 6.1H3"/><path d="M21 12.1H3"/><path d="M15.1 18H3"/>',
  book: '<path d="M4 19.5A2.5 2.5 0 0 1 6.5 17H20"/><path d="M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2Z"/>',
  languages:
    '<path d="m5 8 6 6"/><path d="m4 14 6-6 2-3"/><path d="M2 5h12"/><path d="M7 2h1"/><path d="m22 22-5-10-5 10"/><path d="M14 18h6"/>',
  focus: '<circle cx="12" cy="12" r="3"/><path d="M3 7V5a2 2 0 0 1 2-2h2"/><path d="M17 3h2a2 2 0 0 1 2 2v2"/><path d="M21 17v2a2 2 0 0 1-2 2h-2"/><path d="M7 21H5a2 2 0 0 1-2-2v-2"/>',
  wand: '<path d="m3 21 9-9"/><path d="M15 4V2"/><path d="M15 16v-2"/><path d="M8 9h2"/><path d="M20 9h2"/><path d="m17.8 6.2 1.4-1.4"/><path d="m17.8 11.8 1.4 1.4"/><path d="M12.2 6.2 10.8 4.8"/>',
  sortAsc: '<path d="m3 8 4-4 4 4"/><path d="M7 4v16"/><path d="M15 8h6"/><path d="M15 12h4"/><path d="M15 16h2"/>',
  star_filled: '<polygon points="12 2 15.1 8.3 22 9.3 17 14.1 18.2 21 12 17.8 5.8 21 7 14.1 2 9.3 8.9 8.3"/>',
};

/**
 * Build an SVG icon element.
 * @param {keyof ICONS | string} name
 * @param {{ size?: 'sm'|'md'|'lg'|'xl', className?: string }} [opts]
 * @returns {SVGElement}
 */
  function icon(name, opts) {
    opts = opts || {};
    var markup = ICONS[name];
    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
    svg.setAttribute('viewBox', '0 0 24 24');
    svg.setAttribute('aria-hidden', 'true');
    svg.setAttribute('focusable', 'false');
    svg.classList.add('icon');
    if (opts.size) svg.classList.add('icon--' + opts.size);
    if (opts.className) {
      opts.className.split(/\s+/).filter(Boolean).forEach(function (cls) {
        svg.classList.add(cls);
      });
    }
    if (markup) {
      svg.innerHTML = markup;
      // Solid glyphs (favourites) opt into fill; the rest stay stroked.
      if (name === 'star_filled' || name === 'star') svg.setAttribute('data-solid', 'true');
    } else {
      // Fail loudly in dev rather than rendering an invisible blank.
      svg.innerHTML = '<circle cx="12" cy="12" r="9"/>';
      svg.dataset.missingIcon = String(name);
    }
    return svg;
  }

  /** Filled variant helper — used for the "favourite" toggle's on state. */
  function filledIcon(name, opts) {
    var svg = icon(name, opts);
    svg.style.fill = 'currentColor';
    return svg;
  }

  MD.ICONS = ICONS;
  MD.icon = icon;
  MD.filledIcon = filledIcon;
})(window);
