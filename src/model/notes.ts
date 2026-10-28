/**
 * 便签：分类、Deadline 倒计时、拖动排序几何。
 *
 * 前两段来自原版 `data/NoteEntity.kt`，第三段来自 `data/NoteReorder.kt`
 * （那个文件的注释把 v1.7.5 修掉的三个缺陷讲得很清楚，这里保留结论）。
 */

import type { NoteEntity } from "./types";

export const NOTE_COLOR_COUNT = 8;
export const DEFAULT_NOTE_COLOR = 0;

export type NoteTypeKind = "none" | "general" | "deadline";

export interface NoteType {
  label: string;
  iconLabel: string;
  editableLabel?: boolean;
  kind: NoteTypeKind;
}

/** 顺序即 typeIndex。第 0 项固定是「空」，老数据迁移补 0 正好落在「无分类」上 */
export const NOTE_TYPES: NoteType[] = [
  { label: "空", iconLabel: "", kind: "none" },
  { label: "工作", iconLabel: "工", kind: "general" },
  { label: "生活", iconLabel: "生", kind: "general" },
  { label: "学习", iconLabel: "学", kind: "general" },
  { label: "Deadline", iconLabel: "期", kind: "deadline" },
  { label: "自定义", iconLabel: "自", editableLabel: true, kind: "general" },
  { label: "Deadline 自定义", iconLabel: "期", editableLabel: true, kind: "deadline" },
];

export const NOTE_TYPE_NONE = 0;
export const NOTE_TYPE_DEADLINE = 4;

/** 越界收敛，避免脏数据把 UI 打挂（分类可能来自旧库或手改的备份） */
export function noteTypeAt(index: number): NoteType {
  return NOTE_TYPES[Math.min(Math.max(index, 0), NOTE_TYPES.length - 1)];
}

/** 这条便签实际显示的分类名：自定义类走用户填的名字，为空时退回占位示例 */
export function typeLabel(note: NoteEntity): string {
  const type = noteTypeAt(note.typeIndex);
  if (type.kind === "none") return "";
  return type.editableLabel ? note.customLabel.trim() || type.label : type.label;
}

export function hasDeadline(note: NoteEntity): boolean {
  return noteTypeAt(note.typeIndex).kind === "deadline" && note.deadlineAt > 0;
}

const HOUR_MILLIS = 60 * 60 * 1000;
const DAY_MILLIS = 24 * HOUR_MILLIS;

/**
 * 距截止还剩多久的人话文案。
 *
 * 档位刻意跟「人怎么读时间」走：还剩 3 天时没人关心还剩几小时，
 * 还剩 2 小时时也不关心还剩几分。
 */
export function deadlineCountdown(deadlineAt: number, now: number): string {
  const left = deadlineAt - now;
  if (left <= 0) {
    const overdue = -left;
    const days = Math.floor(overdue / DAY_MILLIS);
    return days >= 1 ? `已过期 ${days} 天` : "已过期";
  }
  const minutes = Math.floor(left / 60_000);
  if (minutes < 1) return "即将到期";
  if (minutes < 60) return `${minutes} 分钟后`;
  if (left < DAY_MILLIS) {
    const hours = Math.floor(left / HOUR_MILLIS);
    const mins = minutes % 60;
    return mins === 0 ? `${hours} 小时后` : `${hours} 小时 ${mins} 分后`;
  }
  if (left < 30 * DAY_MILLIS) return `${Math.floor(left / DAY_MILLIS)} 天后`;
  return `${Math.floor(left / (30 * DAY_MILLIS))} 个月后`;
}

/** 截止时刻的显示文案：`MM/dd HH:mm`（当年省略年份，跨年补上） */
export function deadlineTimeLabel(deadlineAt: number, now: number): string {
  const target = new Date(deadlineAt);
  const today = new Date(now);
  const md = `${target.getMonth() + 1}/${target.getDate()} ${String(target.getHours()).padStart(
    2,
    "0",
  )}:${String(target.getMinutes()).padStart(2, "0")}`;
  return target.getFullYear() === today.getFullYear() ? md : `${target.getFullYear()}/${md}`;
}

// ── 拖动排序几何 ────────────────────────────────────────────────
//
// 落点口径：洞吸附到最近的槽位。所有行在**原始布局**里排成一列，
// 第 k 行的顶边就是 slotTops[k]。被拖行顶边是 slotTops[start] + offset，
// 落点取「顶边离它最近的那个槽位」。
//
// 这等价于「被拖行的中心越过交界线就翻一格」，但**阈值不依赖行高统一**：
// 38dp / 41dp 混排时每个交界都按相邻两行的真实高度算，不会像「拿自己的行高
// 当步长」那样攒误差（v1.7.5 修的三个缺陷之一）。

const EPS = 0.5;

/** 每一行的顶边在内容坐标里的位置（第 0 行是 0，之后逐行累加前一行的高度） */
export function slotTops(heights: number[]): number[] {
  const tops: number[] = [];
  let acc = 0;
  for (const h of heights) {
    tops.push(acc);
    acc += h > 0 ? h : 0;
  }
  return tops;
}

/** 把「被拖行相对原位移动了多少像素」换算成它应该落在第几格 */
export function resolveDropIndex(tops: number[], startIndex: number, offsetPx: number): number {
  if (tops.length === 0) return 0;
  const start = Math.min(Math.max(startIndex, 0), tops.length - 1);
  const visualTop = tops[start] + offsetPx;
  let best = start;
  let bestDiff = Math.abs(visualTop - tops[start]);
  for (let k = 0; k < tops.length; k += 1) {
    const diff = Math.abs(visualTop - tops[k]);
    if (diff < bestDiff - EPS) {
      best = k;
      bestDiff = diff;
    } else if (Math.abs(diff - bestDiff) <= EPS && Math.abs(k - start) < Math.abs(best - start)) {
      // 平局：离原位更近的那个赢，等价于「临界点上先不动」
      best = k;
      bestDiff = diff;
    }
  }
  return best;
}

/** 被拖行当前的视觉位移上限：不允许把它拖出列表首尾 */
export function clampOffset(offsetPx: number, tops: number[], startIndex: number): number {
  if (tops.length === 0) return 0;
  const start = Math.min(Math.max(startIndex, 0), tops.length - 1);
  const min = tops[0] - tops[start];
  const max = tops[tops.length - 1] - tops[start];
  return Math.min(Math.max(offsetPx, min), max);
}

/**
 * 让位位移：拖动中其它行要不要上下挪、挪多少。
 * 挪动量恒等于**被拖行自己的高度** —— 「洞」有多大就腾多大，混排也不留缝。
 */
export function shiftFor(
  index: number,
  startIndex: number,
  targetIndex: number,
  draggedHeight: number,
): number {
  if (index === startIndex) return 0;
  if (targetIndex > startIndex && index > startIndex && index <= targetIndex) return -draggedHeight;
  if (targetIndex < startIndex && index >= targetIndex && index < startIndex) return draggedHeight;
  return 0;
}

/**
 * 筛选 / 搜索态下的拖动 → **全量列表**的新顺序。
 *
 * 列表被筛选时下标是「看得见的那几条」，而 position 是**全量**的。
 * 直接拿可见下标去改全量就是 v1.7.4 之前的「拖 A 动 B」。
 * 规则只有一条：插到目标位置那一行的前面；拖到末尾则跟在最后一个可见行后面。
 */
export function moveInFullOrder<T>(
  all: T[],
  visible: T[],
  dragged: T,
  targetVisibleIndex: number,
): T[] {
  if (!all.includes(dragged)) return all;
  const reduced = all.filter((x) => x !== dragged);
  if (reduced.length === 0) return all;
  const visibleWithout = visible.filter((x) => x !== dragged);
  if (visibleWithout.length === 0) return all;

  let insertAt: number;
  if (targetVisibleIndex >= visibleWithout.length) {
    const last = visibleWithout[visibleWithout.length - 1];
    const at = reduced.indexOf(last);
    if (at < 0) return all;
    insertAt = at + 1;
  } else {
    const anchor = visibleWithout[Math.max(targetVisibleIndex, 0)];
    const at = reduced.indexOf(anchor);
    if (at < 0) return all;
    insertAt = at;
  }
  const next = reduced.slice();
  next.splice(insertAt, 0, dragged);
  return next;
}

/**
 * 拖到列表可视区上下边缘时的自动滚动速度（px/秒，正值 = 向下滚）。
 * 速度随「进入热区的深度」线性增长：刚进热区时慢，越靠边越快。
 */
export function autoScrollVelocity(
  rowTop: number,
  rowBottom: number,
  viewportHeight: number,
  hotZone: number,
  maxSpeed: number,
): number {
  if (hotZone <= 0 || viewportHeight <= hotZone * 2) return 0;
  const upDepth = (hotZone - rowTop) / hotZone;
  if (upDepth > 0) return -maxSpeed * Math.min(upDepth, 1);
  const downDepth = (rowBottom - (viewportHeight - hotZone)) / hotZone;
  if (downDepth > 0) return maxSpeed * Math.min(downDepth, 1);
  return 0;
}
