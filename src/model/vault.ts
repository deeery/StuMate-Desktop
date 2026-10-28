/**
 * 知识库 → Obsidian vault 的导出（纯逻辑）。
 *
 * ## 为什么这一层在前端
 *
 * 「哪条知识、写到哪个路径、frontmatter 写什么、重名怎么办」全是**可判定的规则**，
 * 放进 Rust 就只能靠跑起来看结果。放在这里是纯函数，vitest 能逐条钉住。
 * Rust 侧只拿 `{path, content}[]` 落盘，不参与任何命名判断。
 *
 * ## 目录约定
 *
 * ```
 * <选定目录>/
 *   Stumate/                     ← 固定一层，导出只碰这里，不污染 vault 其它笔记
 *     大学物理/
 *       牛顿第二定律.md
 *     未分类/
 *       随手记.md
 * ```
 *
 * frontmatter 里的 `stumate_uid` 是回认的锚点：同一条知识重复导出时，
 * 即便标题改了也能靠 uid 认出「这是同一篇」，将来做增量同步直接用它。
 *
 * ⚠️ 导出是**覆盖写**，不删除。手动删掉某条知识后重新导出，vault 里那份会留着 ——
 * 删用户笔记比留一份孤儿危险得多，这条取舍写在这里，避免将来被当成 bug 修掉。
 */

import type { KnowledgeEntity } from "./types";

/** 未分类科目在文件夹与 frontmatter 里的显示名（与原版 `KNOWLEDGE_NO_SUBJECT_LABEL` 一致） */
export const UNCLASSIFIED = "未分类";

/** vault 里的根目录名。固定一层，便于「只导出/只同步这块」 */
export const VAULT_ROOT = "Stumate";

export interface VaultFile {
  /** 相对选定目录的路径，用 `/` 分隔 */
  path: string;
  content: string;
}

/**
 * 文件名净化。
 *
 * Windows 的非法字符是 `\ / : * ? " < > |`，另加 0x00–0x1F 控制字符；
 * 结尾的点和空格在 Windows 上**建不出来**（资源管理器会静默截掉，命令行会报错）；
 * 空名要有兜底。长度按字符数截到 60 —— NTFS 的 255 是**字节**限制，
 * 中文按 UTF-8 占 3 字节，60 个中文正好 180 字节，留得住余量。
 */
export function sanitizeSegment(raw: string, fallback = "未命名"): string {
  // eslint-disable-next-line no-control-regex
  let s = (raw ?? "").replace(/[\\/:*?"<>|\u0000-\u001f]/g, "_").trim();
  s = s.replace(/[.\s]+$/, "");
  if (!s) s = fallback;
  const chars = [...s];
  if (chars.length > 60) s = chars.slice(0, 60).join("");
  // 截断之后可能又落到「以点结尾」上，再来一次
  s = s.replace(/[.\s]+$/, "");
  return s || fallback;
}

/**
 * YAML 标量。只在**必要**时加引号，中文科目名保持裸写（Obsidian 的属性面板读起来更干净）。
 *
 * 需要引号的情形：首尾空白、以 YAML 指示符开头（`:#-?*&!|>'"%@`{}[],`）、
 * 含 `: ` 或 ` #`、以及会被解析成布尔/数字/null 的字面量（`true`、`12`、`~`）。
 */
export function yamlScalar(raw: string): string {
  const v = raw ?? "";
  if (v === "") return '""';
  const needsQuote =
    /^\s|\s$/.test(v) ||
    /^[#\-?*&!|>'"%@`{}\[\],:]/.test(v) ||
    /: |\s#/.test(v) ||
    // 含引号或反斜杠也加引号：虽然 YAML 的纯量允许中间带 `"`，
    // 但那样「转义」这条规则就永远测不到，而不转义的引号在 Obsidian 里
    // 一旦被别的工具改写就容易读错。统一加引号更好推理。
    /["\\]/.test(v) ||
    /^(true|false|null|yes|no|on|off|~)$/i.test(v) ||
    /^-?\d+(\.\d+)?$/.test(v);
  if (!needsQuote) return v;
  return '"' + v.replace(/\\/g, "\\\\").replace(/"/g, '\\"') + '"';
}

/** 本地时间，`YYYY-MM-DDTHH:mm:ss`（不带到时区：Obsidian 里按本地时间显示更直观） */
export function localIso(ms: number): string {
  if (!ms || ms <= 0) return "";
  const d = new Date(ms);
  const p = (n: number, w = 2) => String(n).padStart(w, "0");
  return (
    `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}` +
    `T${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`
  );
}

function frontmatter(entry: KnowledgeEntity, subjectLabel: string): string {
  const lines = [
    "---",
    "stumate: true",
    `stumate_uid: ${yamlScalar(entry.uid || "")}`,
    `subject: ${yamlScalar(subjectLabel)}`,
    `position: ${entry.position}`,
  ];
  const created = localIso(entry.createdAt);
  const updated = localIso(entry.updatedAt);
  if (created) lines.push(`created: ${created}`);
  if (updated) lines.push(`updated: ${updated}`);
  lines.push("---");
  // 收尾两个换行：一个结束 `---`，一个留出「frontmatter 与正文之间必须有空行」的那一行
  return lines.join("\n") + "\n\n";
}

/**
 * 把知识条目铺成一组待写文件。
 *
 * 排序：未分类排在最前，其余按科目名、再按 `position` / `createdAt` —— 与应用内
 * 知识库列表的顺序一致（`data/SubjectOptions.kt` 的约定），导出的目录结构才对得上眼。
 *
 * 重名：同一科目下同名条目用 `-2` `-3` 递增。比较是**大小写不敏感**的，
 * 因为 Windows 文件系统不区分大小写，`ABC.md` 与 `abc.md` 会互相覆盖。
 */
export function buildVaultFiles(entries: KnowledgeEntity[]): VaultFile[] {
  const alive = entries.filter((e) => e.deletedAt === 0);

  const sorted = [...alive].sort((a, b) => {
    const sa = a.subject.trim(),
      sb = b.subject.trim();
    const ua = sa === "",
      ub = sb === "";
    if (ua !== ub) return ua ? -1 : 1;
    if (sa !== sb) return sa < sb ? -1 : 1;
    if (a.position !== b.position) return a.position - b.position;
    return a.createdAt - b.createdAt;
  });

  const used = new Set<string>();
  const files: VaultFile[] = [];

  for (const entry of sorted) {
    const subjectLabel = entry.subject.trim() || UNCLASSIFIED;
    const dir = `${VAULT_ROOT}/${sanitizeSegment(subjectLabel, UNCLASSIFIED)}`;
    const base = sanitizeSegment(entry.title, "未命名条目");

    let name = base;
    let n = 2;
    // 大小写不敏感地查重（Windows）
    while (used.has(`${dir}/${name}`.toLowerCase())) {
      name = `${base}-${n++}`;
    }
    used.add(`${dir}/${name}`.toLowerCase());

    files.push({
      path: `${dir}/${name}.md`,
      content: frontmatter(entry, subjectLabel) + (entry.content ?? "").replace(/\s+$/, "") + "\n",
    });
  }

  return files;
}
