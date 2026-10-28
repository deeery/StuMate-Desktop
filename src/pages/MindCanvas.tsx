/**
 * 思维导图画布。
 *
 * ## 坐标约定（这条是原版踩过的坑，Web 里换了形态但仍然存在）
 *
 * 平移/缩放只作用在**内容层**的 `transform` 上；命中测试一律用 `MindViewport`
 * 从父空间像素换算到画布 dp。**不要**在画布容器上也加一层 `transform`，
 * 否则浏览器会把 `clientX` 折算一次、我们再折算一次 —— 那正是原版
 * 「平移之后按在节点上却拖走了整个画布」的同一个错误。
 *
 * ## 手势
 *
 * 一条显式循环：按下时定类型（命中节点 = 拖节点，否则 = 平移画布），
 * 整条手势锁死，拖多远都不换类型。状态清理放在**下一次按下**而不是松手，
 * 这样「鼠标在窗口外松开」不会留下一个永久卡死的拖动态。
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  DEFAULT_LAYOUT,
  MindDragTracker,
  MindViewport,
  buildMindTree,
  clampMindZoom,
  dropTargetOf,
  hitTest,
  layoutMindTree,
  type MindLayoutResult,
} from "../model/mindMap";
import type { MindNode } from "../model/types";

const HIT_TOLERANCE = 3;

export interface MindCanvasHandle {
  fit: () => void;
  zoomBy: (delta: number) => void;
  zoom: () => number;
}

export function MindCanvas({
  nodes,
  selectedUid,
  onSelect,
  onReparent,
  onToggleCollapse,
  onRename,
  onDelete,
  onAddChild,
  registerApi,
}: {
  nodes: MindNode[];
  selectedUid: string | null;
  onSelect: (uid: string | null) => void;
  onReparent: (uid: string, parentUid: string) => void;
  onToggleCollapse: (uid: string) => void;
  onRename: (uid: string, label: string) => void;
  onDelete: (uid: string) => void;
  onAddChild: (parentUid: string) => void;
  registerApi?: (api: MindCanvasHandle) => void;
}) {
  const hostRef = useRef<HTMLDivElement | null>(null);
  const [viewport, setViewport] = useState(new MindViewport(0, 0, 1, 1));
  const [dragUid, setDragUid] = useState<string | null>(null);
  const [dragDelta, setDragDelta] = useState({ x: 0, y: 0 });
  const [dropTarget, setDropTarget] = useState<string | null>(null);
  const [menu, setMenu] = useState<{ uid: string; x: number; y: number } | null>(null);

  const tree = useMemo(() => buildMindTree(nodes), [nodes]);
  const layout = useMemo(() => layoutMindTree(tree, DEFAULT_LAYOUT), [tree]);
  const layoutRef = useRef<MindLayoutResult>(layout);
  layoutRef.current = layout;
  const viewportRef = useRef(viewport);
  viewportRef.current = viewport;

  const tracker = useRef(new MindDragTracker(4, 420));
  const gesture = useRef<{ kind: "none" | "node" | "pan"; uid: string | null }>({
    kind: "none",
    uid: null,
  });

  const toCanvas = useCallback((clientX: number, clientY: number) => {
    const rect = hostRef.current?.getBoundingClientRect();
    if (!rect) return { x: 0, y: 0 };
    return viewportRef.current.toCanvas(clientX - rect.left, clientY - rect.top);
  }, []);

  const fit = useCallback(() => {
    const host = hostRef.current;
    if (!host) return;
    const rect = host.getBoundingClientRect();
    const l = layoutRef.current;
    const zoom = clampMindZoom(
      Math.min((rect.width - 40) / Math.max(l.width, 1), (rect.height - 40) / Math.max(l.height, 1), 1),
    );
    setViewport(
      new MindViewport(
        (rect.width - l.width * zoom) / 2,
        (rect.height - l.height * zoom) / 2,
        zoom,
        1,
      ),
    );
  }, []);

  // 首次挂载与切换数据源时自动适应一次，免得新用户看到一片空白
  const fittedFor = useRef<string>("");
  useEffect(() => {
    const signature = `${nodes.length}-${layout.width}-${layout.height}`;
    if (fittedFor.current === signature) return;
    fittedFor.current = signature;
    if (nodes.length > 0) fit();
  }, [nodes.length, layout.width, layout.height, fit]);

  useEffect(() => {
    registerApi?.({
      fit,
      zoomBy: (delta) =>
        setViewport((v) => {
          const next = clampMindZoom(v.zoom + delta);
          // 以视口中心为锚点缩放，否则内容会往左上角跑
          const host = hostRef.current;
          if (!host) return v.withZoom(next);
          const rect = host.getBoundingClientRect();
          const cx = rect.width / 2;
          const cy = rect.height / 2;
          const k = next / v.zoom;
          return new MindViewport(cx - (cx - v.panX) * k, cy - (cy - v.panY) * k, next, 1);
        }),
      zoom: () => viewportRef.current.zoom,
    });
  }, [fit, registerApi]);

  const onPointerDown = (e: React.PointerEvent) => {
    if (e.button !== 0) return;
    setMenu(null);
    const { x, y } = toCanvas(e.clientX, e.clientY);
    const hit = hitTest(layoutRef.current, x, y, HIT_TOLERANCE);
    const uid = hit?.node.uid ?? null;
    const now = performance.now();
    const doubleTap = tracker.current.press(x, y, uid, now);
    gesture.current = { kind: uid === null ? "pan" : "node", uid };
    (e.currentTarget as HTMLElement).setPointerCapture(e.pointerId);

    if (doubleTap && uid) {
      // 双击 = 折叠/展开（只对非根节点生效）
      onToggleCollapse(uid);
      gesture.current = { kind: "none", uid: null };
      tracker.current.release();
      return;
    }
    onSelect(uid);
    if (uid) {
      setDragUid(uid);
      setDragDelta({ x: 0, y: 0 });
    }
  };

  const onPointerMove = (e: React.PointerEvent) => {
    const g = gesture.current;
    if (g.kind === "none") return;
    const { x, y } = toCanvas(e.clientX, e.clientY);
    const { stepX, stepY } = tracker.current.move(x, y);

    if (g.kind === "pan") {
      // 平移步长是父空间像素，直接累加就是 1:1 跟手
      setViewport((v) => v.panAfter(stepX * v.pxPerDp, stepY * v.pxPerDp));
      return;
    }

    if (!tracker.current.dragging || !g.uid) return;
    const dx = viewportRef.current.dragDeltaDp(tracker.current.totalX);
    const dy = viewportRef.current.dragDeltaDp(tracker.current.totalY);
    setDragDelta({ x: dx, y: dy });
    setDropTarget(dropTargetOf(layoutRef.current, tree, g.uid, dx, dy));
  };

  const finish = () => {
    const g = gesture.current;
    const release = tracker.current.release();
    gesture.current = { kind: "none", uid: null };
    setDragUid(null);
    setDragDelta({ x: 0, y: 0 });
    setDropTarget(null);
    if (g.kind === "node" && release.dragged && g.uid && dropTarget) {
      onReparent(g.uid, dropTarget);
    }
  };

  const onContextMenu = (e: React.MouseEvent) => {
    e.preventDefault();
    const { x, y } = toCanvas(e.clientX, e.clientY);
    const hit = hitTest(layoutRef.current, x, y, HIT_TOLERANCE);
    if (!hit) return;
    const rect = hostRef.current?.getBoundingClientRect();
    setMenu({
      uid: hit.node.uid,
      x: e.clientX - (rect?.left ?? 0),
      y: e.clientY - (rect?.top ?? 0),
    });
    onSelect(hit.node.uid);
  };

  const scale = viewport.zoom;

  return (
    <div
      ref={hostRef}
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={finish}
      onPointerCancel={finish}
      onContextMenu={onContextMenu}
      style={{
        position: "relative",
        flex: 1,
        minHeight: 0,
        overflow: "hidden",
        background: "var(--bg)",
        cursor: dragUid ? "grabbing" : "default",
        touchAction: "none",
      }}
    >
      <div
        style={{
          position: "absolute",
          left: 0,
          top: 0,
          transformOrigin: "0 0",
          transform: `translate(${viewport.panX}px, ${viewport.panY}px) scale(${scale})`,
        }}
      >
        <svg
          width={layout.width}
          height={layout.height}
          style={{ position: "absolute", left: 0, top: 0, overflow: "visible", pointerEvents: "none" }}
        >
          {layout.edges.map((edge, i) => {
            const midX = (edge.fromX + edge.toX) / 2;
            return (
              <path
                key={i}
                d={`M ${edge.fromX} ${edge.fromY} C ${midX} ${edge.fromY}, ${midX} ${edge.toY}, ${edge.toX} ${edge.toY}`}
                fill="none"
                stroke="var(--outline-strong)"
                strokeWidth={1.4}
              />
            );
          })}
        </svg>

        {layout.nodes.map((placed) => {
          const uid = placed.node.uid;
          const isRoot = uid === "";
          const isSelected = selectedUid === uid;
          const isDropTarget = dropTarget === uid;
          const isDragging = dragUid === uid;
          const x = placed.left + (isDragging ? dragDelta.x : 0);
          const y = placed.top + (isDragging ? dragDelta.y : 0);
          return (
            <div
              key={uid || "root"}
              style={{
                position: "absolute",
                left: x,
                top: y,
                width: placed.width,
                height: placed.height,
                display: "flex",
                alignItems: "center",
                gap: 6,
                padding: "0 10px",
                borderRadius: 5,
                fontSize: 12,
                fontWeight: isRoot ? 700 : placed.node.kind === "subject" ? 600 : 400,
                color: isRoot ? "var(--on-accent)" : "var(--on-surface)",
                background: isRoot
                  ? "var(--accent)"
                  : placed.node.kind === "subject"
                    ? "var(--surface2)"
                    : "var(--surface)",
                border: `1px solid ${
                  isDropTarget ? "var(--accent)" : isSelected ? "var(--accent)" : "var(--border-rest)"
                }`,
                boxShadow: isDropTarget ? "0 0 0 2px var(--accent-tint)" : undefined,
                opacity: isDragging ? 0.75 : 1,
                cursor: isRoot ? "default" : "grab",
                whiteSpace: "nowrap",
                overflow: "hidden",
                transition: isDragging ? "none" : "left 120ms linear, top 120ms linear",
              }}
            >
              <span style={{ overflow: "hidden", textOverflow: "ellipsis", flex: 1 }}>
                {placed.node.label}
              </span>
              {placed.node.collapsed ? (
                <span style={{ fontSize: 10, color: "var(--on-surface-faint)" }}>
                  +{tree.childrenOf(uid).length}
                </span>
              ) : null}
              {placed.node.kind === "entry" && tree.childrenOf(uid).length > 0 ? (
                <span style={{ fontSize: 9, color: "var(--on-surface-faint)" }}>•</span>
              ) : null}
            </div>
          );
        })}
      </div>

      {menu ? (
        <div
          style={{
            position: "absolute",
            left: menu.x,
            top: menu.y,
            minWidth: 132,
            background: "var(--surface)",
            border: "1px solid var(--outline)",
            borderRadius: 6,
            boxShadow: "var(--shadow-flyout)",
            padding: 4,
            zIndex: 20,
          }}
          onPointerDown={(e) => e.stopPropagation()}
        >
          <MenuItem
            onClick={() => {
              onAddChild(menu.uid);
              setMenu(null);
            }}
          >
            新建子节点
          </MenuItem>
          {menu.uid ? (
            <>
              <MenuItem
                onClick={() => {
                  const node = tree.byUid.get(menu.uid);
                  const next = window.prompt("改名", node?.label ?? "");
                  if (next) onRename(menu.uid, next);
                  setMenu(null);
                }}
              >
                重命名
              </MenuItem>
              <MenuItem
                onClick={() => {
                  onToggleCollapse(menu.uid);
                  setMenu(null);
                }}
              >
                折叠 / 展开
              </MenuItem>
              <MenuItem
                danger
                onClick={() => {
                  onDelete(menu.uid);
                  setMenu(null);
                }}
              >
                删除
              </MenuItem>
            </>
          ) : null}
        </div>
      ) : null}
    </div>
  );
}

function MenuItem({
  children,
  onClick,
  danger,
}: {
  children: React.ReactNode;
  onClick: () => void;
  danger?: boolean;
}) {
  return (
    <button
      onClick={onClick}
      style={{
        display: "block",
        width: "100%",
        textAlign: "left",
        padding: "6px 10px",
        borderRadius: 4,
        fontSize: 12,
        color: danger ? "var(--error)" : "var(--on-surface)",
      }}
      onMouseEnter={(e) => (e.currentTarget.style.background = "var(--hover)")}
      onMouseLeave={(e) => (e.currentTarget.style.background = "transparent")}
    >
      {children}
    </button>
  );
}
