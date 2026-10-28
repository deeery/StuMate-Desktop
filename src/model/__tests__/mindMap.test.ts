import { describe, expect, it } from "vitest";
import {
  MindDragTracker,
  MindViewport,
  addManualChild,
  buildMindTree,
  canReparent,
  clampMindZoom,
  deleteMindNode,
  dropTargetOf,
  hitTest,
  knowledgeKey,
  layoutMindTree,
  mindNodeFromDisk,
  mindNodeToDisk,
  reconcileMind,
  renameMindNode,
  reparentMindNode,
  toggleMindCollapsed,
} from "../mindMap";
import { EMPTY_KNOWLEDGE, type KnowledgeEntity, type MindNode } from "../types";

function k(id: number, subject: string, title: string, uid = `k-${id}`): KnowledgeEntity {
  return { ...EMPTY_KNOWLEDGE, id, subject, title, uid };
}

/** 确定性 uid 工厂：断言才能写死期望值 */
function counter(): () => string {
  let n = 0;
  return () => `u${++n}`;
}

describe("导图派生", () => {
  it("从知识库生成科目节点与条目节点", () => {
    const result = reconcileMind([], [k(1, "高数", "行列式"), k(2, "高数", "极限")], counter());
    expect(result.changed).toBe(true);
    const subjects = result.nodes.filter((n) => n.kind === "subject");
    const entries = result.nodes.filter((n) => n.kind === "entry");
    expect(subjects).toHaveLength(1);
    expect(subjects[0].label).toBe("高数");
    expect(entries).toHaveLength(2);
    expect(entries.every((e) => e.parentUid === subjects[0].uid)).toBe(true);
  });

  it("未分类垫底", () => {
    const result = reconcileMind([], [k(1, "", "零散"), k(2, "高数", "行列式")], counter());
    const rootOrder = result.nodes
      .filter((n) => n.kind === "subject")
      .sort((a, b) => a.position - b.position)
      .map((n) => n.label);
    expect(rootOrder[rootOrder.length - 1]).toBe("未分类");
  });

  it("条目改名同步 label", () => {
    const first = reconcileMind([], [k(1, "高数", "旧名")], counter());
    const second = reconcileMind(first.nodes, [k(1, "高数", "新名")], counter());
    expect(second.changed).toBe(true);
    expect(second.nodes.find((n) => n.kind === "entry")?.label).toBe("新名");
  });

  it("幂等：同一份输入连续跑两次，第二次 changed=false", () => {
    const knowledge = [k(1, "高数", "行列式"), k(2, "英语", "Unit 1")];
    const first = reconcileMind([], knowledge, counter());
    const second = reconcileMind(first.nodes, knowledge, counter());
    expect(second.changed).toBe(false);
    expect(second.nodes).toEqual(first.nodes);
  });

  it("用户拖过的 entry 不再被拉回科目下", () => {
    const first = reconcileMind([], [k(1, "高数", "行列式"), k(2, "高数", "极限")], counter());
    const entry = first.nodes.find((n) => n.kind === "entry" && n.label === "行列式")!;
    const other = first.nodes.find((n) => n.kind === "entry" && n.label === "极限")!;
    const moved = reparentMindNode(first.nodes, entry.uid, other.uid);
    const next = reconcileMind(moved, [k(1, "高数", "行列式"), k(2, "高数", "极限")], counter());
    expect(next.nodes.find((n) => n.uid === entry.uid)?.parentUid).toBe(other.uid);
  });

  it("知识条目被删：没有子节点的 entry 直接移除", () => {
    const first = reconcileMind([], [k(1, "高数", "行列式")], counter());
    const next = reconcileMind(first.nodes, [], counter());
    expect(next.nodes.some((n) => n.kind === "entry")).toBe(false);
    // 科目下空了，科目节点也一起移除
    expect(next.nodes.some((n) => n.kind === "subject")).toBe(false);
  });

  it("知识条目被删但挂着手工子节点：降级成 manual 保留", () => {
    const first = reconcileMind([], [k(1, "高数", "行列式")], counter());
    const entry = first.nodes.find((n) => n.kind === "entry")!;
    const withChild = addManualChild(first.nodes, entry.uid, "我的笔记").nodes;
    const next = reconcileMind(withChild, [], counter());
    const kept = next.nodes.find((n) => n.uid === entry.uid);
    expect(kept?.kind).toBe("manual");
    expect(next.nodes.some((n) => n.label === "我的笔记")).toBe(true);
  });

  it("被隐藏的科目不再重建", () => {
    const first = reconcileMind([], [k(1, "高数", "行列式")], counter());
    const subject = first.nodes.find((n) => n.kind === "subject")!;
    const suppressed = first.nodes.map((n) =>
      n.uid === subject.uid ? { ...n, suppressed: true } : n,
    );
    const next = reconcileMind(suppressed, [k(1, "高数", "行列式")], counter());
    expect(next.nodes.find((n) => n.kind === "subject" && !n.suppressed)).toBeUndefined();
  });

  it("父节点不存在的孤儿挂回根", () => {
    const orphan: MindNode = {
      uid: "x",
      parentUid: "missing",
      label: "孤儿",
      kind: "manual",
      knowledgeUid: "",
      subject: "",
      position: 0,
      collapsed: false,
      suppressed: false,
    };
    const next = reconcileMind([orphan], [], counter());
    expect(next.nodes[0].parentUid).toBe("");
  });
});

describe("导图编辑", () => {
  const base = reconcileMind([], [k(1, "高数", "行列式")], counter()).nodes;
  const subject = base.find((n) => n.kind === "subject")!;
  const entry = base.find((n) => n.kind === "entry")!;

  it("改名不接受空白", () => {
    expect(renameMindNode(base, entry.uid, "  ")).toBe(base);
    expect(renameMindNode(base, entry.uid, " 新名 ").find((n) => n.uid === entry.uid)?.label).toBe(
      "新名",
    );
  });

  it("折叠是开关", () => {
    const once = toggleMindCollapsed(base, subject.uid);
    expect(once.find((n) => n.uid === subject.uid)?.collapsed).toBe(true);
    const twice = toggleMindCollapsed(once, subject.uid);
    expect(twice.find((n) => n.uid === subject.uid)?.collapsed).toBe(false);
  });

  it("删除派生节点只打墓碑，不真删", () => {
    const next = deleteMindNode(base, subject.uid);
    const node = next.find((n) => n.uid === subject.uid);
    expect(node).toBeDefined();
    expect(node?.suppressed).toBe(true);
    // 整支都打上墓碑
    expect(next.find((n) => n.uid === entry.uid)?.suppressed).toBe(true);
  });

  it("删除手工节点是真删除（连子树）", () => {
    const added = addManualChild(base, subject.uid, "手工");
    const grand = addManualChild(added.nodes, added.uid, "孙节点");
    const next = deleteMindNode(grand.nodes, added.uid);
    expect(next.some((n) => n.uid === added.uid)).toBe(false);
    expect(next.some((n) => n.uid === grand.uid)).toBe(false);
  });

  it("不能挂到自己或自己的后代上", () => {
    const tree = buildMindTree(base);
    expect(canReparent(tree, subject.uid, subject.uid)).toBe(false);
    expect(canReparent(tree, subject.uid, entry.uid)).toBe(false);
  });

  it("不能挂到不存在的节点上，但可以挂到根（空串）", () => {
    const tree = buildMindTree(base);
    expect(canReparent(tree, entry.uid, "不存在")).toBe(false);
    expect(canReparent(tree, entry.uid, "")).toBe(true);
  });

  it("挂到当前父节点不算改变", () => {
    const tree = buildMindTree(base);
    expect(canReparent(tree, entry.uid, subject.uid)).toBe(false);
  });

  it("非法重挂原样返回（同一实例，调用方可判等）", () => {
    expect(reparentMindNode(base, entry.uid, entry.uid)).toBe(base);
  });
});

describe("导图布局", () => {
  const nodes = reconcileMind(
    [],
    [k(1, "高数", "行列式"), k(2, "高数", "极限"), k(3, "英语", "Unit 1")],
    counter(),
  ).nodes;
  const layout = layoutMindTree(buildMindTree(nodes));

  it("根在最左，子节点在右", () => {
    const root = layout.nodes.find((p) => p.node.uid === "")!;
    // 根节点本身也是 kind=subject（原版同样如此），这里要按 uid 排除它
    const subjects = layout.nodes.filter((p) => p.node.kind === "subject" && p.node.uid !== "");
    expect(root.left).toBeLessThan(Math.min(...subjects.map((s) => s.left)));
  });

  it("同层节点 x 相同", () => {
    const subjects = layout.nodes.filter((p) => p.node.kind === "subject" && p.node.uid !== "");
    expect(new Set(subjects.map((s) => s.left)).size).toBe(1);
  });

  it("节点不重叠", () => {
    for (let i = 0; i < layout.nodes.length; i += 1) {
      for (let j = i + 1; j < layout.nodes.length; j += 1) {
        const a = layout.nodes[i];
        const b = layout.nodes[j];
        const overlapX = a.left < b.left + b.width && b.left < a.left + a.width;
        const overlapY = a.top < b.top + b.height && b.top < a.top + a.height;
        expect(overlapX && overlapY).toBe(false);
      }
    }
  });

  it("连线数 = 非折叠父节点的子节点数", () => {
    expect(layout.edges.length).toBe(nodes.filter((n) => !n.suppressed).length);
  });

  it("折叠后子节点不再布局", () => {
    const subject = nodes.find((n) => n.kind === "subject" && n.subject === "高数")!;
    const collapsed = toggleMindCollapsed(nodes, subject.uid);
    const next = layoutMindTree(buildMindTree(collapsed));
    // 只塌了「高数」这一支，「英语」的条目还在
    const mathEntries = next.nodes.filter((p) => p.node.kind === "entry" && p.node.subject === "高数");
    expect(mathEntries).toHaveLength(0);
    expect(next.nodes.some((p) => p.node.subject === "英语")).toBe(true);
  });

  it("命中测试有容差（3dp 内算命中）", () => {
    const target = layout.nodes.find((p) => p.node.kind === "entry")!;
    expect(hitTest(layout, target.left - 2, target.top + 2, 3)).not.toBeNull();
    expect(hitTest(layout, target.left - 8, target.top + 2, 3)).toBeNull();
  });

  it("落点取被拖节点中心：拖到另一个节点上方时能识别", () => {
    const entries = layout.nodes.filter((p) => p.node.kind === "entry");
    const from = entries[0];
    const to = entries[1];
    const dx = to.left - from.left;
    const dy = to.top - from.top;
    const target = dropTargetOf(layout, buildMindTree(nodes), from.node.uid, dx, dy);
    expect(target).toBe(to.node.uid);
  });

  it("落在自己身上返回 null（不高亮）", () => {
    const entry = layout.nodes.find((p) => p.node.kind === "entry")!;
    expect(dropTargetOf(layout, buildMindTree(nodes), entry.node.uid, 0, 0)).toBeNull();
  });
});

describe("画布坐标换算（原版 bug 的回归用例）", () => {
  it("平移之后同一个屏幕点仍落在同一个画布点", () => {
    const vp = new MindViewport(100, 0, 1, 1);
    // 屏幕 200px → 画布 100dp。旧写法（再减一次 pan）会算出 0
    expect(vp.toCanvasX(200)).toBe(100);
  });

  it("缩放 2 倍时屏幕位移换算成画布 dp 要除以 2", () => {
    const vp = new MindViewport(0, 0, 2, 1);
    expect(vp.toCanvasX(200)).toBe(100);
    expect(vp.dragDeltaDp(100)).toBe(50);
  });

  it("平移 1:1 跟手：步长原样累加", () => {
    const vp = new MindViewport(10, 20, 1, 1);
    const next = vp.panAfter(30, 40);
    expect(next.panX).toBe(40);
    expect(next.panY).toBe(60);
  });

  it("zoom 为 0 时不会除出 Infinity（命中测试会静默失效，比崩溃更难查）", () => {
    const vp = new MindViewport(0, 0, 0, 1);
    expect(Number.isFinite(vp.toCanvasX(10))).toBe(true);
  });

  it("缩放夹取在 0.5~1.6", () => {
    expect(clampMindZoom(0.1)).toBe(0.5);
    expect(clampMindZoom(9)).toBe(1.6);
  });

  it("知识条目稳定键：优先 uid，缺失时退化成 id", () => {
    expect(knowledgeKey({ ...EMPTY_KNOWLEDGE, id: 7, uid: "abc" })).toBe("abc");
    expect(knowledgeKey({ ...EMPTY_KNOWLEDGE, id: 7, uid: "" })).toBe("id:7");
  });
});

describe("手势状态机", () => {
  it("按下命中节点后，拖多远都不会退化成平移", () => {
    const tracker = new MindDragTracker();
    tracker.press(0, 0, "node-a", 1000);
    expect(tracker.downUid).toBe("node-a");
    for (let i = 1; i <= 20; i += 1) {
      tracker.move(i * 10, i * 10);
    }
    expect(tracker.dragging).toBe(true);
    expect(tracker.downUid).toBe("node-a");
  });

  it("小于 slop 不算拖动（手抖不该把节点甩出去）", () => {
    const tracker = new MindDragTracker(4);
    tracker.press(0, 0, "node-a", 1000);
    tracker.move(2, 2);
    expect(tracker.dragging).toBe(false);
  });

  it("位移按绝对位置差算，不会累积丢帧误差", () => {
    const tracker = new MindDragTracker();
    tracker.press(100, 100, "a", 1000);
    tracker.move(130, 100);
    tracker.move(150, 120);
    expect(tracker.totalX).toBe(50);
    expect(tracker.totalY).toBe(20);
  });

  it("上一次在窗口外松手（没有 up）之后，下一次按下必须从干净状态开始", () => {
    const tracker = new MindDragTracker();
    tracker.press(0, 0, "a", 1000);
    tracker.move(50, 50);
    expect(tracker.dragging).toBe(true);
    // 模拟：up 事件永远没到，直接在别处按下
    tracker.press(10, 10, null, 2000);
    expect(tracker.downUid).toBeNull();
    expect(tracker.dragging).toBe(false);
    expect(tracker.totalX).toBe(0);
  });

  it("双击同一节点才成立；点空白处不参与双击", () => {
    const tracker = new MindDragTracker(4, 420);
    expect(tracker.press(0, 0, "a", 1000)).toBe(false);
    expect(tracker.press(0, 0, "a", 1200)).toBe(true);
    expect(tracker.press(0, 0, null, 1300)).toBe(false);
    expect(tracker.press(0, 0, null, 1400)).toBe(false);
  });

  it("真拖动过之后，紧接着的点按不算双击", () => {
    const tracker = new MindDragTracker(4, 420);
    tracker.press(0, 0, "a", 1000);
    tracker.move(30, 30);
    tracker.release();
    expect(tracker.press(0, 0, "a", 1100)).toBe(false);
  });
});

describe("磁盘格式：kind 大小写", () => {
  // 原版用 Kotlin 枚举名序列化，磁盘上是大写。读写两侧都必须对齐，
  // 否则原版读不懂 Lite 写的节点，会在同一份文件里重复建树。
  const base = {
    uid: "n1",
    parentUid: "",
    label: "大学物理",
    knowledgeUid: "",
    subject: "大学物理",
    position: 0,
    collapsed: false,
    suppressed: false,
  };

  it("读：大写的 SUBJECT/ENTRY/MANUAL 都认得", () => {
    expect(mindNodeFromDisk({ ...base, kind: "SUBJECT" }).kind).toBe("subject");
    expect(mindNodeFromDisk({ ...base, kind: "ENTRY" }).kind).toBe("entry");
    expect(mindNodeFromDisk({ ...base, kind: "MANUAL" }).kind).toBe("manual");
  });

  it("读：小写也认，未知值退化成 manual", () => {
    expect(mindNodeFromDisk({ ...base, kind: "subject" }).kind).toBe("subject");
    expect(mindNodeFromDisk({ ...base, kind: "???`" }).kind).toBe("manual");
  });

  it("写：一律输出大写", () => {
    expect(mindNodeToDisk({ ...base, kind: "subject" }).kind).toBe("SUBJECT");
    expect(mindNodeToDisk({ ...base, kind: "entry" }).kind).toBe("ENTRY");
    expect(mindNodeToDisk({ ...base, kind: "manual" }).kind).toBe("MANUAL");
  });

  it("往返：读回来再写出去，kind 不变", () => {
    for (const kind of ["SUBJECT", "ENTRY", "MANUAL"]) {
      const round = mindNodeToDisk(mindNodeFromDisk({ ...base, kind }));
      expect(round.kind).toBe(kind);
    }
  });
});
