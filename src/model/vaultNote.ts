/**
 * 单篇笔记的读写：frontmatter 的拆与合。
 *
 * 为什么单独成一个模块：**这是全项目唯一一处会改写用户手写文件的地方**。
 * 只要这里少带了一行，用户的 `tags:` / `aliases:` 就在下一次自动保存时凭空消失，
 * 而且不会报错、不会提示，是那种「三周后才发现」的丢失。
 *
 * 所以规则只有一条：**不认识的东西一律原样带回去**。
 * 我们只认 `stumate_uid` / `created` / `updated` 三个键（外加 `stumate: true` 标记），
 * 其余 frontmatter 行连顺序一起保存，写回时原封不动跟在后面。
 *
 * 与 Obsidian 的兼容面：
 *  - 它自己的笔记没有 frontmatter —— 能读，保存时才补上（补的是 uid，无害）；
 *  - 它的日期写法是 `2026-10-28` 或 `2026-10-28T14:22` —— 都能解析；
 *  - 它的 `tags: [a, b]` 这类行我们不解析，但整行保留。
 */

import { localIso, yamlScalar } from "./vault";

export interface NoteMeta {
  uid: string;
  /** epoch ms；0 = 文件里没写，或者写了但解析不出来 */
  created: number;
  updated: number;
  /** 我们不认识的 frontmatter 行，原样保留（含顺序） */
  extra: string[];
}

export interface ParsedNote extends NoteMeta {
  body: string;
}

const FENCE = /^---[ \t]*$/;

/**
 * 拆开成「元信息 + 正文」。
 *
 * 容错优先级：读得出正文 > 读得出元信息。任何一段解析失败都退化成
 * 「没有 frontmatter、全文都是正文」——宁可丢掉 uid（下次保存会重新生成一个），
 * 也不能把用户的正文当成元信息吃掉。
 */
export function parseNote(raw: string): ParsedNote {
  const text = raw.charCodeAt(0) === 0xfeff ? raw.slice(1) : raw;
  const empty: ParsedNote = { uid: "", created: 0, updated: 0, extra: [], body: text };

  const lines = text.split(/\r?\n/);
  if (lines.length === 0 || !FENCE.test(lines[0])) return empty;

  // 收尾的 `---`（或 YAML 的 `...`），找不到就整篇当正文
  let close = -1;
  for (let i = 1; i < lines.length; i += 1) {
    if (FENCE.test(lines[i]) || /^\.\.\.[ \t]*$/.test(lines[i])) {
      close = i;
      break;
    }
  }
  if (close < 0) return empty;

  const out: ParsedNote = { uid: "", created: 0, updated: 0, extra: [], body: "" };

  for (let i = 1; i < close; i += 1) {
    const line = lines[i];
    const m = /^([A-Za-z_][\w-]*)\s*:\s*(.*)$/.exec(line);
    if (!m) {
      // 空行、注释、多行 YAML 的续行 —— 我们看不懂，但别人可能看得懂
      if (line.trim() !== "") out.extra.push(line);
      continue;
    }
    const key = m[1].toLowerCase();
    const value = unquote(m[2]);
    if (key === "stumate_uid" || key === "uid") {
      if (!out.uid) out.uid = value;
    } else if (key === "created") {
      out.created = parseDate(value);
    } else if (key === "updated") {
      out.updated = parseDate(value);
    } else if (key === "stumate") {
      // 是我们自己的标记，写回时重新生成，不必保留
    } else {
      out.extra.push(line);
    }
  }

  // 分隔线之后可能紧跟一个空白行（我们写入时总补一个），先吃掉它，
  // 否则每保存一次正文前面就多一个空行，越攒越多。
  let start = close + 1;
  if (start < lines.length && lines[start].trim() === "") start += 1;
  out.body = lines.slice(start).join("\n");
  return out;
}

/**
 * 合回全文。
 *
 * `body` 会被规整成「末尾恰好一个换行、其余空白剥掉」——文件末尾的换行数
 * 是 git 与 diff 的噪音源，统一口径之后改动才看得清。
 */
export function composeNote(meta: NoteMeta, body: string): string {
  const lines: string[] = ["---", "stumate: true"];
  if (meta.uid) lines.push(`stumate_uid: ${yamlScalar(meta.uid)}`);
  if (meta.created > 0) lines.push(`created: ${localIso(meta.created)}`);
  if (meta.updated > 0) lines.push(`updated: ${localIso(meta.updated)}`);
  for (const line of meta.extra) lines.push(line);
  lines.push("---");

  // 只剥**行尾**空白与**结尾**的空白，中间的连续空行一律不动 ——
  // 那是用户排版的一部分（段落间距、引用块前的留白），压掉它等于改内容。
  const clean = body.replace(/[ \t]+$/gm, "").replace(/\s+$/, "");
  const head = lines.join("\n");
  return clean === "" ? `${head}\n\n` : `${head}\n\n${clean}\n`;
}

/** 带引号的值去掉引号并还原转义；`"a \"b\""` → `a "b"` */
function unquote(raw: string): string {
  const v = raw.trim();
  if (v.length < 2) return v;
  const q = v[0];
  if ((q === '"' || q === "'") && v[v.length - 1] === q) {
    const inner = v.slice(1, -1);
    return q === '"' ? inner.replace(/\\(["\\])/g, "$1") : inner.replace(/''/g, "'");
  }
  return v;
}

/**
 * 日期解析。接受 `YYYY-MM-DDTHH:mm:ss` / `YYYY-MM-DD HH:mm` / `YYYY-MM-DD`。
 *
 * 不用 `new Date(str)` 一把梭：它对 `2026-10-28` 会按 UTC 解释（`T14:22` 才按本地），
 * 同一个文件在东八区读出来和 UTC 读出来差 8 小时，跨设备同步时日期会漂。
 */
export function parseDate(raw: string): number {
  const m = /^(\d{4})-(\d{2})-(\d{2})(?:[T ](\d{2}):(\d{2})(?::(\d{2}))?)?/.exec(raw.trim());
  if (!m) return 0;
  const d = new Date(
    Number(m[1]),
    Number(m[2]) - 1,
    Number(m[3]),
    Number(m[4] ?? 0),
    Number(m[5] ?? 0),
    Number(m[6] ?? 0),
  );
  const t = d.getTime();
  return Number.isFinite(t) ? t : 0;
}
