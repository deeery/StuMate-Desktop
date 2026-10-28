/**
 * 知识库的文件树：扫描结果 → 可渲染的树，外加路径与命名的纯函数。
 *
 * ## 为什么树是「算出来的」而不是「存起来的」
 *
 * 库的真相是磁盘上的目录结构。这里没有任何「父子关系」字段、没有 index 文件、
 * 没有缓存 —— 一次 `vault_scan` 拿到 `(路径, 是否目录, mtime)` 列表，
 * `buildTree` 现拼一棵树。好处是「外部改了目录」与「应用里改了目录」走同一条路径，
 * 不存在两边对不上、需要修复索引的情况；代价是每次刷新都要重扫，
 * 但几百个文件的 walkdir 是毫秒级，这点开销换掉整套一致性代码，划算。
 *
 * ## 顶层的含义
 *
 * 「顶层目录 = 科目」是一条**约定**，不是字段。`subjectOf()` 就是这条约定的唯一实现：
 * 第一段目录名即科目，直接扔在根下的笔记算「未分类」。
 * 这样课表页按科目找知识的联动不用改，而用户也不需要理解「科目」是个什么东西 ——
 * 对他来说那就是一个文件夹。
 */

import { UNCLASSIFIED, sanitizeSegment } from "./vault";

/** Rust `vault_scan` 的镜像。路径一律相对 `<库根>/StuMate/`，用 `/` 分隔 */
export interface VaultEntry {
  path: string;
  isDir: boolean;
  mtime: number;
  size: number;
}

export type TreeSort = "name" | "mtime";

export interface TreeNode {
  kind: "folder" | "note";
  /** 相对路径；文件夹不带尾斜杠，笔记带 `.md` */
  path: string;
  /** 显示名：文件夹是目录名，笔记是去掉 `.md` 的文件名（标题即文件名） */
  name: string;
  mtime: number;
  size: number;
  /** 笔记恒为空数组，省掉调用点的判空 */
  children: TreeNode[];
}

export const NOTE_EXT = ".md";

/** 扫描结果 → 树。文件夹优先，同级按 [sort] 排 */
export function buildTree(entries: VaultEntry[], sort: TreeSort = "name"): TreeNode[] {
  const folders = new Map<string, TreeNode>();
  const roots: TreeNode[] = [];

  const ensureFolder = (path: string): TreeNode => {
    const hit = folders.get(path);
    if (hit) return hit;
    const node: TreeNode = {
      kind: "folder",
      path,
      name: path.slice(path.lastIndexOf("/") + 1),
      mtime: 0,
      size: 0,
      children: [],
    };
    folders.set(path, node);

    const parent = parentOf(path);
    if (parent === "") roots.push(node);
    else ensureFolder(parent).children.push(node);
    return node;
  };

  for (const entry of entries) {
    if (entry.isDir) {
      const node = ensureFolder(entry.path);
      node.mtime = Math.max(node.mtime, entry.mtime);
    }
  }

  for (const entry of entries) {
    if (entry.isDir) continue;
    if (!entry.path.toLowerCase().endsWith(NOTE_EXT)) continue; // 非 md 一律不进树
    const parent = parentOf(entry.path);
    // 父目录可能没被列出来（扫描被权限挡掉之类），补建，避免整条笔记凭空消失
    const bucket = parent === "" ? roots : ensureFolder(parent).children;
    bucket.push({
      kind: "note",
      path: entry.path,
      name: baseTitle(entry.path),
      mtime: entry.mtime,
      size: entry.size,
      children: [],
    });
  }

  sortTree(roots, sort);
  return roots;
}

/** 就地排序（递归）。文件夹恒在前 —— 混排会让「新建文件夹」在列表里乱跳 */
export function sortTree(nodes: TreeNode[], sort: TreeSort): void {
  nodes.sort((a, b) => {
    if (a.kind !== b.kind) return a.kind === "folder" ? -1 : 1;
    if (sort === "mtime" && a.mtime !== b.mtime) return b.mtime - a.mtime;
    return compareName(a.name, b.name);
  });
  for (const n of nodes) if (n.children.length > 0) sortTree(n.children, sort);
}

/**
 * 名称比较。`numeric` 让 `第2章` 排在 `第10章` 前面（否则字典序会反过来），
 * `base` 让大小写不敏感 —— 文件系统在 Windows 上本来就不区分，排序不该比它更严格。
 */
export function compareName(a: string, b: string): number {
  return a.localeCompare(b, "zh-Hans-CN", { numeric: true, sensitivity: "base" });
}

/** `a/b/c.md` → `a/b`；根下的 `c.md` → `""` */
export function parentOf(path: string): string {
  const i = path.lastIndexOf("/");
  return i < 0 ? "" : path.slice(0, i);
}

/** `a/b/c.md` → `c`（去扩展名） */
export function baseTitle(path: string): string {
  const base = path.slice(path.lastIndexOf("/") + 1);
  return base.toLowerCase().endsWith(NOTE_EXT) ? base.slice(0, -NOTE_EXT.length) : base;
}

/** 拼路径。`dir` 为空表示根，不产生前导斜杠 */
export function joinPath(dir: string, name: string): string {
  return dir === "" ? name : `${dir}/${name}`;
}

/** 科目 = 第一段目录；根下的笔记算未分类 */
export function subjectOf(path: string): string {
  const i = path.indexOf("/");
  return i < 0 ? UNCLASSIFIED : path.slice(0, i);
}

/** 树里所有笔记，按显示顺序（`Ctrl+P` 用） */
export function flattenNotes(nodes: TreeNode[]): TreeNode[] {
  const out: TreeNode[] = [];
  const walk = (list: TreeNode[]) => {
    for (const n of list) {
      if (n.kind === "note") out.push(n);
      else walk(n.children);
    }
  };
  walk(nodes);
  return out;
}

/**
 * 在 [taken] 里挑一个不冲突的文件名。
 *
 * 撞名时追加 ` 2`、` 3`（Obsidian 的写法），而不是覆盖或报错 ——
 * 「新建两条未命名」是最常见的操作，弹一个「名称已存在」在这里毫无帮助。
 * 比较用大小写折叠：Windows 上 `Note.md` 与 `note.md` 是同一个文件。
 */
export function uniqueTitle(base: string, taken: Set<string>): string {
  const clean = sanitizeSegment(base);
  const pool = lowerSet(taken);
  const has = (t: string) => pool.has(t.toLowerCase());
  if (!has(`${clean}${NOTE_EXT}`)) return clean;
  let n = 2;
  while (has(`${clean} ${n}${NOTE_EXT}`)) n += 1;
  return `${clean} ${n}`;
}

/**
 * 全部折叠成小写。
 *
 * 每个用到名字集合的函数都先过一遍——而不是要求调用方「必须传小写」。
 * 那种约定只要有一个调用点忘了，症状就是「偶尔覆盖掉一个文件」，
 * 而且是 Windows 上才出现；把它变成函数自己的责任，比指望每个调用点都记得便宜。
 */
function lowerSet(names: Set<string>): Set<string> {
  const out = new Set<string>();
  for (const n of names) out.add(n.toLowerCase());
  return out;
}

/** 目录里已被占用的名字（笔记带扩展名），全部小写化，给 [uniqueTitle] 用 */
export function takenNames(tree: TreeNode[], dir: string): Set<string> {
  const bucket = dir === "" ? tree : findFolder(tree, dir)?.children ?? [];
  return new Set(
    bucket.map((n) => (n.kind === "folder" ? n.name : `${n.name}${NOTE_EXT}`).toLowerCase()),
  );
}

/** 按路径找文件夹节点 */
export function findFolder(nodes: TreeNode[], path: string): TreeNode | null {
  for (const n of nodes) {
    if (n.kind !== "folder") continue;
    if (n.path === path) return n;
    const hit = findFolder(n.children, path);
    if (hit) return hit;
  }
  return null;
}

/** 按路径找节点（文件夹或笔记） */
export function findNode(nodes: TreeNode[], path: string): TreeNode | null {
  for (const n of nodes) {
    if (n.path === path) return n;
    if (n.kind === "folder") {
      const hit = findNode(n.children, path);
      if (hit) return hit;
    }
  }
  return null;
}

/** 路径的所有祖先目录（含自身当文件夹用），用于「展开到当前笔记」 */
export function ancestorsOf(path: string): string[] {
  const out: string[] = [];
  let dir = parentOf(path);
  while (dir !== "") {
    out.push(dir);
    dir = parentOf(dir);
  }
  return out;
}

/**
 * 移动：把 [path] 挪到 [targetDir] 下，名字撞了就顺延。
 *
 * 只算新路径，不动磁盘 —— 于是「拖拽预览」和「真正落盘」可以用同一份逻辑，
 * 不会出现「松手之后名字突然变了」。
 */
export function movePath(path: string, targetDir: string, tree: TreeNode[]): string {
  const name = path.slice(path.lastIndexOf("/") + 1);
  if (targetDir === parentOf(path)) return path; // 原地，别当成移动处理
  const taken = takenNames(tree, targetDir);
  if (!taken.has(name.toLowerCase())) return joinPath(targetDir, name);
  if (path.toLowerCase().endsWith(NOTE_EXT)) {
    const title = uniqueTitle(baseTitle(path), taken);
    return joinPath(targetDir, `${title}${NOTE_EXT}`);
  }
  let n = 2;
  while (taken.has(`${name} ${n}`.toLowerCase())) n += 1;
  return joinPath(targetDir, `${name} ${n}`);
}

/**
 * 改名。笔记补 `.md`，文件夹不补；同名视为「没改」。
 *
 * [taken] 是同级的已有名字（小写、笔记带扩展名）。撞名**顺延而不是报错** ——
 * 理由和新建一样：改名是个高频动作，弹窗拦住的只是「没改成」，
 * 而顺延至少把事做成了，用户看得到结果，再改一次的成本也比填一个错误弹窗低。
 */
export function renamePath(path: string, nextName: string, taken: Set<string>): string {
  const isNote = path.toLowerCase().endsWith(NOTE_EXT);
  const stem = isNote ? baseTitle(path) : path.slice(path.lastIndexOf("/") + 1);
  let name = sanitizeSegment(nextName, stem);
  if (isNote) {
    // 用户可能顺手打了 `.md`，别变成 `xx.md.md`
    if (name.toLowerCase().endsWith(NOTE_EXT)) name = name.slice(0, -NOTE_EXT.length);
    name = `${name}${NOTE_EXT}`;
  }
  const self = isNote ? `${stem}${NOTE_EXT}` : stem;
  // 「没改」按**逐字**比，不按大小写折叠：`a.md` → `A.md` 在 Windows 上是一次
  // 真改名，折叠了就会被当成没改而静默丢掉用户的输入。
  if (name === self) return path;

  // 自己的旧名字不算冲突（改大小写时最容易踩到），所以按折叠后的键剔掉自己
  const others = lowerSet(taken);
  others.delete(self.toLowerCase());
  if (!others.has(name.toLowerCase())) return joinPath(parentOf(path), name);

  if (isNote) {
    return joinPath(parentOf(path), `${uniqueTitle(name.slice(0, -NOTE_EXT.length), others)}${NOTE_EXT}`);
  }
  let n = 2;
  while (others.has(`${name} ${n}`.toLowerCase())) n += 1;
  return joinPath(parentOf(path), `${name} ${n}`);
}

/**
 * 快速切换的匹配打分。返回 -1 = 不匹配。
 *
 * 三档，越靠前分越高：前缀命中 > 子串命中 > 子序列命中。
 * 子序列兜底是为了「只记得其中几个字」的场合（输 `电波式` 命中 `电磁波公式`）——
 * 这类输入在树里点不出来，但人确实记得。**不做拼音**：拼音表要一整份字典，
 * 而中文笔记的标题往往本身就带了想找的那几个字。
 */
export function matchScore(query: string, target: string): number {
  const q = query.trim().toLowerCase();
  if (q === "") return 0;
  const t = target.toLowerCase();

  if (t.startsWith(q)) return 3000 - t.length;
  const idx = t.indexOf(q);
  if (idx >= 0) return 2000 - idx * 4 - t.length;

  // 子序列：按顺序命中即可，连续命中给额外分（连续片段更像「真的记得这个名字」）
  let ti = 0;
  let score = 0;
  let streak = 0;
  for (const ch of q) {
    const found = t.indexOf(ch, ti);
    if (found < 0) return -1;
    streak = found === ti ? streak + 1 : 1;
    score += 10 + streak * 4 - Math.min(found - ti, 12);
    ti = found + 1;
  }
  return Math.max(1, score);
}
