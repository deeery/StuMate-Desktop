/**
 * 知识库的思维导图：节点模型、派生规则、布局算法、画布坐标换算、手势状态机。
 *
 * 从原版 `data/MindMap.kt` + `MindViewport.kt` + `MindDrag.kt` 三份搬过来。
 * 这一层刻意不碰 DOM：画布组件只负责「画出来」和「把鼠标事件翻译成对数据的改动」，
 * 规则全部在这里，可以单测。
 */

import type { KnowledgeEntity, MindNode, MindNodeKind } from "./types";

export const MIND_NO_SUBJECT_LABEL = "未分类";
export const MIND_ROOT_LABEL = "知识库";

/** 知识条目的稳定键：优先 uid，老数据没有 uid 时退化成主键 */
export function knowledgeKey(e: KnowledgeEntity): string {
  return e.uid || `id:${e.id}`;
}

/**
 * 导图节点在磁盘上的 kind 是**大写**的。
 *
 * 原版是 Kotlin 的 `enum class MindNodeKind { SUBJECT, ENTRY, MANUAL }`，kotlinx
 * 按枚举名序列化。前端内部统一用小写，转换集中在这里：
 *  - 读：大小写都认（Lite 曾经存过小写文件，不能因此读不出来）
 *  - 写：一律写大写，否则原版读不懂，会把节点当未知类型并重复建树
 *
 * 这条踩过真坑：两侧都用小写时，mindmap.json 里同一批节点出现两套，
 * 导图上能看到两个「大学物理」。共用数据时磁盘格式必须服从原版。
 */
const KIND_FROM_DISK: Record<string, MindNodeKind> = {
  SUBJECT: "subject",
  ENTRY: "entry",
  MANUAL: "manual",
  subject: "subject",
  entry: "entry",
  manual: "manual",
};

const KIND_TO_DISK: Record<MindNodeKind, string> = {
  subject: "SUBJECT",
  entry: "ENTRY",
  manual: "MANUAL",
};

/** 磁盘上的节点：除 kind 是原始字符串外，其余字段与 MindNode 一致。 */
export type MindNodeWire = Omit<MindNode, "kind"> & { kind: string };

export function mindNodeFromDisk(n: MindNodeWire): MindNode {
  return { ...n, kind: KIND_FROM_DISK[n.kind] ?? "manual" };
}

export function mindNodeToDisk(n: MindNode): MindNodeWire {
  return { ...n, kind: KIND_TO_DISK[n.kind] };
}

const MAX_DEPTH = 64;
const MAX_NODES = 2000;

/** 同级排序：未分类恒定垫底（它是兜底桶，不该插在真实科目中间） */
function siblingOrder(a: MindNode, b: MindNode): number {
  const wa = a.kind === "subject" && !a.subject.trim() ? 1 : 0;
  const wb = b.kind === "subject" && !b.subject.trim() ? 1 : 0;
  if (wa !== wb) return wa - wb;
  if (a.position !== b.position) return a.position - b.position;
  return a.label.localeCompare(b.label, "zh-Hans-CN");
}

/** 把平铺的节点列表包成可查询的树 */
export class MindTree {
  readonly nodes: MindNode[];
  readonly byUid: Map<string, MindNode>;
  private readonly childrenMap: Map<string, MindNode[]>;

  constructor(nodes: MindNode[]) {
    this.nodes = nodes;
    this.byUid = new Map(nodes.map((n) => [n.uid, n]));
    const map = new Map<string, MindNode[]>();
    for (const node of nodes) {
      if (node.suppressed) continue;
      const list = map.get(node.parentUid) ?? [];
      list.push(node);
      map.set(node.parentUid, list);
    }
    for (const list of map.values()) list.sort(siblingOrder);
    this.childrenMap = map;
  }

  childrenOf(uid: string): MindNode[] {
    return this.childrenMap.get(uid) ?? [];
  }

  get rootChildren(): MindNode[] {
    return this.childrenOf("");
  }

  /** uid 的全部祖先，由近及远 */
  ancestry(uid: string): string[] {
    const out: string[] = [];
    let current = this.byUid.get(uid)?.parentUid ?? "";
    let guard = 0;
    while (current && guard < MAX_DEPTH) {
      out.push(current);
      current = this.byUid.get(current)?.parentUid ?? "";
      guard += 1;
    }
    return out;
  }

  /** ancestorUid 是否是 uid 的祖先（含自身）。拖拽改父级时用它挡环 */
  isAncestorOrSelf(ancestorUid: string, uid: string): boolean {
    return ancestorUid === uid || this.ancestry(uid).includes(ancestorUid);
  }

  /** 子树里的所有节点（含自身） */
  subtree(uid: string): MindNode[] {
    const out: MindNode[] = [];
    const stack = [uid];
    let guard = 0;
    while (stack.length > 0 && guard < MAX_NODES) {
      const current = stack.pop() as string;
      const node = this.byUid.get(current);
      if (node) out.push(node);
      for (const child of this.childrenOf(current)) stack.push(child.uid);
      guard += 1;
    }
    return out;
  }
}

export function buildMindTree(nodes: MindNode[]): MindTree {
  return new MindTree(nodes);
}

// ── 派生：知识库 → 导图 ──────────────────────────────────────────

export interface MindReconcileResult {
  nodes: MindNode[];
  changed: boolean;
}

/**
 * 把知识库的当前内容并进导图。
 *
 * 规则（每条都有单测）：
 * 1. 知识库里出现的每个科目，保证有一个 subject 节点。**被隐藏的科目不再重建**。
 * 2. 每条知识保证有一个 entry 节点挂在它的科目下；条目改名同步 label。
 * 3. entry 一旦被用户拖到别处，派生**不再拉回** —— 这是「可手动编辑」与
 *    「自动成图」共存的关键：派生只补缺失，不覆盖位置。
 * 4. 知识条目被删：entry 连同子树一起清掉；若它下面挂着手工节点，
 *    则把 entry 降级成 manual 保留下来 —— 用户手打的东西不能因为上游没了就消失。
 * 5. 科目下已经没有条目、也没有手工节点 → 科目节点移除。
 *
 * 幂等：对同一份输入连续调用两次，第二次 changed 必为 false。
 */
export function reconcileMind(
  existing: MindNode[],
  knowledge: KnowledgeEntity[],
  uidFactory: () => string = newUid,
): MindReconcileResult {
  let changed = false;
  const nodes = existing.slice();

  // 科目按名字排序，未分类垫底
  const groups = new Map<string, KnowledgeEntity[]>();
  for (const entry of knowledge) {
    const key = entry.subject.trim();
    const list = groups.get(key) ?? [];
    list.push(entry);
    groups.set(key, list);
  }
  const sortedSubjects = [...groups.keys()].sort((a, b) => {
    if (!a && b) return 1;
    if (a && !b) return -1;
    return a.localeCompare(b, "zh-Hans-CN");
  });

  const keptEntryKeys = new Set<string>();
  let nextSubjectPos =
    Math.max(-1, ...nodes.filter((n) => n.kind === "subject").map((n) => n.position)) + 1;

  for (const subject of sortedSubjects) {
    const entries = groups.get(subject) ?? [];
    const subjectNode = nodes.find((n) => n.kind === "subject" && n.subject === subject);

    if (subjectNode?.suppressed) {
      // 被隐藏的科目整支跳过：连它下面的条目也不再补
      for (const entry of entries) keptEntryKeys.add(knowledgeKey(entry));
      continue;
    }

    let subjectUid: string;
    if (!subjectNode) {
      const created: MindNode = {
        uid: uidFactory(),
        parentUid: "",
        label: subject || MIND_NO_SUBJECT_LABEL,
        kind: "subject",
        knowledgeUid: "",
        subject,
        position: nextSubjectPos,
        collapsed: false,
        suppressed: false,
      };
      nextSubjectPos += 1;
      nodes.push(created);
      changed = true;
      subjectUid = created.uid;
    } else {
      subjectUid = subjectNode.uid;
    }

    let nextPos = Math.max(-1, ...nodes.filter((n) => n.parentUid === subjectUid).map((n) => n.position)) + 1;

    for (const entry of entries) {
      const key = knowledgeKey(entry);
      keptEntryKeys.add(key);
      const title = entry.title.trim() || "(无标题)";
      const node = nodes.find((n) => n.kind === "entry" && n.knowledgeUid === key);
      if (!node) {
        nodes.push({
          uid: uidFactory(),
          parentUid: subjectUid,
          label: title,
          kind: "entry",
          knowledgeUid: key,
          subject,
          position: nextPos,
          collapsed: false,
          suppressed: false,
        });
        nextPos += 1;
        changed = true;
      } else if (node.label !== title) {
        nodes[nodes.indexOf(node)] = { ...node, label: title };
        changed = true;
      }
    }
  }

  // 4. 知识条目被删：清节点。挂着手工子节点的降级保留
  const orphans = nodes.filter((n) => n.kind === "entry" && !keptEntryKeys.has(n.knowledgeUid));
  for (const orphan of orphans) {
    const kidCount = new MindTree(nodes).childrenOf(orphan.uid).length;
    if (kidCount === 0) {
      nodes.splice(nodes.indexOf(orphan), 1);
    } else {
      nodes[nodes.indexOf(orphan)] = { ...orphan, kind: "manual" };
    }
    changed = true;
  }

  // 5. 科目下空了就移除（隐藏的科目节点留着当墓碑，不参与这条）
  const pruned = nodes.filter(
    (node) =>
      node.kind !== "subject" ||
      node.suppressed ||
      nodes.some((n) => n.parentUid === node.uid && !n.suppressed),
  );
  if (pruned.length !== nodes.length) changed = true;

  // 兜底：父节点已经不存在的孤儿节点一律挂回根，避免整支在画布上凭空消失
  const aliveUids = new Set(pruned.map((n) => n.uid));
  const repaired = pruned.map((node) =>
    !node.parentUid || aliveUids.has(node.parentUid) ? node : { ...node, parentUid: "" },
  );
  if (repaired.some((n, i) => n !== pruned[i])) changed = true;

  return { nodes: repaired, changed };
}

// ── 编辑操作（纯函数，返回新列表） ──────────────────────────────

export function addManualChild(
  nodes: MindNode[],
  parentUid: string,
  label: string,
  uidFactory: () => string = newUid,
): { nodes: MindNode[]; uid: string } {
  const uid = uidFactory();
  const position = Math.max(-1, ...nodes.filter((n) => n.parentUid === parentUid).map((n) => n.position)) + 1;
  const node: MindNode = {
    uid,
    parentUid,
    label,
    kind: "manual",
    knowledgeUid: "",
    subject: "",
    position,
    collapsed: false,
    suppressed: false,
  };
  return { nodes: [...nodes, node], uid };
}

/** 改名。空白名字不接受（画布上会出现点不中的空块） */
export function renameMindNode(nodes: MindNode[], uid: string, label: string): MindNode[] {
  const trimmed = label.trim();
  if (!trimmed) return nodes;
  return nodes.map((n) => (n.uid === uid ? { ...n, label: trimmed } : n));
}

export function toggleMindCollapsed(nodes: MindNode[], uid: string): MindNode[] {
  return nodes.map((n) => (n.uid === uid ? { ...n, collapsed: !n.collapsed } : n));
}

/**
 * 删除节点。
 *
 * 手工节点直接移除；**派生过的 entry / subject 打墓碑**，否则下次派生会把它原样建回来。
 * 子树里所有派生节点一并打墓碑，保证「删掉一整支」不会只删掉表面。
 */
export function deleteMindNode(nodes: MindNode[], uid: string): MindNode[] {
  const tree = buildMindTree(nodes);
  if (!tree.byUid.has(uid)) return nodes;
  const subtree = new Map(tree.subtree(uid).map((n) => [n.uid, n]));
  const out: MindNode[] = [];
  for (const node of nodes) {
    const hit = subtree.get(node.uid);
    if (!hit) out.push(node);
    else if (hit.kind === "manual") continue;
    else out.push({ ...node, suppressed: true });
  }
  return out;
}

/**
 * 「把 uid 挂到 newParentUid 下」是否真的会改变结构。
 *
 * 拖动中**逐帧**用它决定要不要把落点节点画成高亮。高亮了就得真挂上 ——
 * 如果这里放行一个 reparent 会拒绝的目标，用户会看到「有个块亮着，松手却什么都没发生」。
 * 空串是合法的目标（根），不能当非法。
 */
export function canReparent(tree: MindTree, uid: string, newParentUid: string): boolean {
  const node = tree.byUid.get(uid);
  if (!node) return false;
  if (newParentUid && !tree.byUid.has(newParentUid)) return false;
  if (tree.isAncestorOrSelf(uid, newParentUid)) return false;
  return node.parentUid !== newParentUid;
}

export function reparentMindNode(nodes: MindNode[], uid: string, newParentUid: string): MindNode[] {
  if (!canReparent(buildMindTree(nodes), uid, newParentUid)) return nodes;
  const position =
    Math.max(-1, ...nodes.filter((n) => n.parentUid === newParentUid).map((n) => n.position)) + 1;
  return nodes.map((n) => (n.uid === uid ? { ...n, parentUid: newParentUid, position } : n));
}

// ── 布局 ────────────────────────────────────────────────────────

export interface MindLayoutOptions {
  nodeHeight: number;
  verticalGap: number;
  horizontalGap: number;
  rootWidth: number;
  subjectWidth: number;
  entryWidth: number;
  manualWidth: number;
  padding: number;
}

export const DEFAULT_LAYOUT: MindLayoutOptions = {
  nodeHeight: 34,
  verticalGap: 12,
  horizontalGap: 44,
  rootWidth: 104,
  subjectWidth: 152,
  entryWidth: 200,
  manualWidth: 168,
  padding: 28,
};

export function widthOf(kind: MindNodeKind, isRoot: boolean, o = DEFAULT_LAYOUT): number {
  if (isRoot) return o.rootWidth;
  if (kind === "subject") return o.subjectWidth;
  if (kind === "entry") return o.entryWidth;
  return o.manualWidth;
}

export interface PlacedMindNode {
  node: MindNode;
  depth: number;
  left: number;
  top: number;
  width: number;
  height: number;
}

export interface MindEdge {
  fromX: number;
  fromY: number;
  toX: number;
  toY: number;
}

export interface MindLayoutResult {
  nodes: PlacedMindNode[];
  edges: MindEdge[];
  width: number;
  height: number;
}

/**
 * 点是否落在节点上。
 *
 * tolerance 把矩形向外扩一圈（画布 dp）。节点之间只有 12dp 的缝，少这 3dp
 * 就会频繁按到空白、把「拖节点」变成「拖画布」。落点判定传 0：
 * 那里比的是**被拖节点的中心**，放宽只会让「松手挂到隔壁」更容易误触。
 */
export function containsPoint(p: PlacedMindNode, x: number, y: number, tolerance = 0): boolean {
  return (
    x >= p.left - tolerance &&
    x <= p.left + p.width + tolerance &&
    y >= p.top - tolerance &&
    y <= p.top + p.height + tolerance
  );
}

/** 命中测试：后画的在上，所以倒序找 */
export function hitTest(
  layout: MindLayoutResult,
  x: number,
  y: number,
  tolerance = 0,
): PlacedMindNode | null {
  for (let i = layout.nodes.length - 1; i >= 0; i -= 1) {
    if (containsPoint(layout.nodes[i], x, y, tolerance)) return layout.nodes[i];
  }
  return null;
}

/**
 * 拖动中的落点：松手会挂到谁身上。
 *
 * 落点取**被拖节点中心**而不是指针位置 —— 用户心里想的是「把这一块放到那一块上」，
 * 指针常常压在目标边缘外一点点。位移单位是画布 dp（已除过缩放）。
 */
export function dropTargetOf(
  layout: MindLayoutResult,
  tree: MindTree,
  uid: string,
  movedX: number,
  movedY: number,
): string | null {
  const placed = layout.nodes.find((p) => p.node.uid === uid);
  if (!placed) return null;
  const cx = placed.left + placed.width / 2 + movedX;
  const cy = placed.top + placed.height / 2 + movedY;
  const hit = hitTest(layout, cx, cy);
  if (!hit) return null;
  return canReparent(tree, uid, hit.node.uid) ? hit.node.uid : null;
}

/**
 * 从左往右生长的整齐树布局（tidy tree）。
 *
 * 做法是最朴素的「叶子占槽、父节点居中于首尾子节点」：每个叶子按访问顺序往下排，
 * 父节点的 y 取首尾子节点中点。不引进力导向：导图是**树**不是图，
 * 力导向只会让同一份数据每次打开位置都不一样，反而更难找东西。
 */
export function layoutMindTree(
  tree: MindTree,
  options: MindLayoutOptions = DEFAULT_LAYOUT,
): MindLayoutResult {
  const root: MindNode = {
    uid: "",
    parentUid: "",
    label: MIND_ROOT_LABEL,
    kind: "subject",
    knowledgeUid: "",
    subject: "",
    position: 0,
    collapsed: false,
    suppressed: false,
  };

  // 先算每列最宽，再定各列 x —— 否则同列宽窄不一时会参差不齐
  const columnWidth: number[] = [];
  const measure = (node: MindNode, depth: number) => {
    const w = widthOf(node.kind, depth === 0, options);
    while (columnWidth.length <= depth) columnWidth.push(0);
    if (w > columnWidth[depth]) columnWidth[depth] = w;
    if (node.collapsed) return;
    for (const child of tree.childrenOf(node.uid)) measure(child, depth + 1);
  };
  measure(root, 0);

  const columnX: number[] = [];
  let cursorX = options.padding;
  for (const w of columnWidth) {
    columnX.push(cursorX);
    cursorX += w + options.horizontalGap;
  }

  const placed: PlacedMindNode[] = [];
  let cursorY = options.padding;

  const place = (node: MindNode, depth: number): number => {
    const w = widthOf(node.kind, depth === 0, options);
    const h = options.nodeHeight;
    const kids = node.collapsed ? [] : tree.childrenOf(node.uid);
    let centerY: number;
    if (kids.length === 0) {
      centerY = cursorY + h / 2;
      cursorY += h + options.verticalGap;
    } else {
      const centers = kids.map((kid) => place(kid, depth + 1));
      centerY = (centers[0] + centers[centers.length - 1]) / 2;
    }
    placed.push({ node, depth, left: columnX[depth], top: centerY - h / 2, width: w, height: h });
    return centerY;
  };
  place(root, 0);

  // 连线：父右中 → 子左中
  const edges: MindEdge[] = [];
  const placedByUid = new Map(placed.map((p) => [p.node.uid, p]));
  for (const parent of placed) {
    if (parent.node.collapsed) continue;
    for (const child of tree.childrenOf(parent.node.uid)) {
      const target = placedByUid.get(child.uid);
      if (!target) continue;
      edges.push({
        fromX: parent.left + parent.width,
        fromY: parent.top + parent.height / 2,
        toX: target.left,
        toY: target.top + target.height / 2,
      });
    }
  }

  const width = Math.max(0, ...placed.map((p) => p.left + p.width)) + options.padding;
  const height = Math.max(0, ...placed.map((p) => p.top + p.height)) + options.padding;
  return { nodes: placed, edges, width, height };
}

export const MIN_ZOOM = 0.5;
export const MAX_ZOOM = 1.6;

/** 画布缩放夹取：太小看不清、太大一屏看不到两个节点 */
export function clampMindZoom(zoom: number): number {
  return Math.min(Math.max(zoom, MIN_ZOOM), MAX_ZOOM);
}

// ── 画布坐标换算 ────────────────────────────────────────────────

/**
 * 导图画布的坐标换算：**父空间像素 ↔ 画布 dp**。
 *
 * ## 原版踩过的坑（Lite 必须继续遵守）
 *
 * 原版把 `pointerInput` 挂在 `graphicsLayer` 的**内层**，而 Compose 已经替它做过
 * 一次逆变换，代码又手动减了一遍 pan、除了一遍 zoom —— 减两遍、除两遍。
 * 后果不是「有点飘」，是**整块失灵**：平移 100px 之后每次按下都偏移 100px，
 * 明明压在节点上也判成空白 → 落进「拖空白 = 平移画布」的分支。
 *
 * 在 Web 里这条约束换了个形态但仍然存在：**画布容器上不要叠 CSS transform 来平移**，
 * 平移/缩放只作用在内容层（`translate + scale`），命中测试一律用这里的函数换算。
 * 否则浏览器自己会把 `clientX` 折算一次，我们再折一次，就是同一个 bug 的复刻。
 *
 * ## 约定
 *
 * 1. `panX/panY` 单位是**父空间像素**（即 `translate()` 的值）。
 * 2. `zoom` 是 `scale()` 的值。
 * 3. `density` 只在需要把 dp 换成像素时出现；Web 里 1dp 就当 1 CSS px（见 `density` 默认值）。
 */
export class MindViewport {
  readonly panX: number;
  readonly panY: number;
  readonly zoom: number;
  readonly density: number;

  constructor(panX = 0, panY = 0, zoom = 1, density = 1) {
    this.panX = panX;
    this.panY = panY;
    this.zoom = zoom;
    this.density = density;
  }

  /** 1 个画布 dp 在父空间里占多少像素（下限保护：除 0 会得到 Infinity，命中永远不命中） */
  get pxPerDp(): number {
    const scale = this.density * this.zoom;
    return scale > 1e-3 ? scale : 1e-3;
  }

  toCanvasX(parentPx: number): number {
    return (parentPx - this.panX) / this.pxPerDp;
  }

  toCanvasY(parentPx: number): number {
    return (parentPx - this.panY) / this.pxPerDp;
  }

  toCanvas(parentPx: number, parentPy: number): { x: number; y: number } {
    return { x: this.toCanvasX(parentPx), y: this.toCanvasY(parentPy) };
  }

  /** 拖动位移：父空间累计位移（像素）→ 节点要挪多少画布 dp */
  dragDeltaDp(totalPx: number): number {
    return totalPx / this.pxPerDp;
  }

  /** 平移画布：步长**原样**累加（两者同为父空间像素，直接相加就是 1:1 跟手） */
  panAfter(stepX: number, stepY: number): MindViewport {
    return new MindViewport(this.panX + stepX, this.panY + stepY, this.zoom, this.density);
  }

  withZoom(zoom: number): MindViewport {
    return new MindViewport(this.panX, this.panY, clampMindZoom(zoom), this.density);
  }
}

// ── 手势状态机 ──────────────────────────────────────────────────

export interface MindDragRelease {
  uid: string | null;
  dragged: boolean;
  totalX: number;
  totalY: number;
  doubleTap: boolean;
}

/**
 * 把「按下 / 移动 / 松手」翻译成「选中了谁、是否在拖、拖了多远、是不是双击」。
 *
 * ## 为什么要有这个状态机
 *
 * 原版上一版在画布上叠了两个指针处理器（tap + drag），各自独立跑手势循环，
 * 结果「按下即选中」这条最符合直觉的反馈根本不存在；更糟的是鼠标在**窗口外松开**时
 * up 事件永远不到达，状态停在「正在拖动」—— 节点一直半透明、后续点击全部落空。
 *
 * 现在的约定：**清理放在「下一次按下」而不是「上一次松手」**，没有漏掉的路径。
 *
 * ## 为什么位移按「绝对位置差」算
 *
 * 增量累加（total += delta）会在丢帧或事件合并时累积误差，一旦某一帧漏了，
 * 后面的位移就整体偏移。绝对位置差 `当前 - 按下` 天然自纠正。
 */
export class MindDragTracker {
  private readonly slopPx: number;
  private readonly doubleTapMs: number;

  downUid: string | null = null;
  dragging = false;

  private downX = 0;
  private downY = 0;
  private lastX = 0;
  private lastY = 0;
  private lastTapUid: string | null = null;
  private lastTapAt = 0;
  private doubleTapPending = false;

  constructor(slopPx = 4, doubleTapMs = 420) {
    this.slopPx = slopPx;
    this.doubleTapMs = doubleTapMs;
  }

  get totalX(): number {
    return this.lastX - this.downX;
  }

  get totalY(): number {
    return this.lastY - this.downY;
  }

  /** 按下。**无条件重置**拖动相关状态。返回是否是双击的第二下 */
  press(x: number, y: number, uid: string | null, nowMs: number): boolean {
    this.downX = x;
    this.downY = y;
    this.lastX = x;
    this.lastY = y;
    this.downUid = uid;
    this.dragging = false;
    this.doubleTapPending =
      uid !== null &&
      uid === this.lastTapUid &&
      nowMs - this.lastTapAt >= 0 &&
      nowMs - this.lastTapAt <= this.doubleTapMs;
    this.lastTapUid = uid;
    this.lastTapAt = nowMs;
    return this.doubleTapPending;
  }

  /** 移动。返回相对上一帧的增量（画布平移用它，逐帧累加才跟手） */
  move(x: number, y: number): { stepX: number; stepY: number } {
    const stepX = x - this.lastX;
    const stepY = y - this.lastY;
    this.lastX = x;
    this.lastY = y;
    if (!this.dragging && Math.hypot(x - this.downX, y - this.downY) > this.slopPx) {
      this.dragging = true;
    }
    return { stepX, stepY };
  }

  /** 松手。返回本次手势的结论并清空状态 */
  release(): MindDragRelease {
    const result: MindDragRelease = {
      uid: this.downUid,
      dragged: this.dragging,
      totalX: this.totalX,
      totalY: this.totalY,
      doubleTap: this.doubleTapPending,
    };
    // 只在**真拖动过**时作废 lastTapUid：否则「点一下、马上拖一下」会被误判成双击
    if (this.dragging) this.lastTapUid = null;
    this.downUid = null;
    this.dragging = false;
    this.doubleTapPending = false;
    return result;
  }
}

export function newUid(): string {
  if (typeof crypto !== "undefined" && "randomUUID" in crypto) return crypto.randomUUID();
  return `n-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
}
