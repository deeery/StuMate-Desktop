/**
 * 循环提醒「今日时间线」的纯几何。
 *
 * 只做三件事：本地时刻 → 当天分钟、把今天的相位点纳入课表纵轴跨度、
 * 竖轴上按时间排点并防重叠。**不读盘、不写 recurring.json、不碰 lastFiredAt** ——
 * 数据来源统一是 `todayRecurringFires()`，与「今日循环」卡片同源。
 *
 * 为什么单独成文件：课表页（线段 + 三角形）和循环提醒页（右侧竖轴）
 * 用的是同一批点，但坐标系完全不同（前者是 gridHeight 的 fraction，
 * 后者是 24h 全天）。把换算收在一处，两端才不会各自算错一次。
 */

import type { Span } from "./timeAxis";
import type { TodayRecurringFire } from "./recurring";

export const DAY_MINUTES = 24 * 60;

/** 本地时刻（epoch ms）→ 当天分钟数，0..1439。绝不用 UTC，否则东八区差 8 小时。 */
export function minuteOfDay(ms: number): number {
  const d = new Date(ms);
  return d.getHours() * 60 + d.getMinutes();
}

/** "HH:mm"，时间线与浮层共用 */
export function clockOfDay(ms: number): string {
  const d = new Date(ms);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

/**
 * 把循环提醒的相位点纳入课表时间跨度。
 *
 * 课表的 `spanOf()` 只看本周课程，若提醒在 21:00 而最晚一节课 18:00 下课，
 * 标记会被 `overflow:hidden` 直接裁掉 —— 用户会以为功能没生效。
 * 所以把相位点作为「事件」一起定范围，两端各留 30 分钟余量再圆整到整小时，
 * `hoursPerRow()` 会自动挑合适的刻度步长。
 *
 * v1.7.27：入参放宽成「有 fireAt 就行」，且第三参改为「有没有可见标记」——
 * 课表现在整周七列都会画标记，不再只有「今天在不在本周」这一个条件。
 */
export function extendSpanForFires(
  span: Span,
  fires: { fireAt: number }[],
  anyVisible: boolean,
): Span {
  if (!anyVisible || fires.length === 0) return span;

  let from = span.startMinute;
  let to = span.endMinute;
  for (const f of fires) {
    const m = minuteOfDay(f.fireAt);
    from = Math.min(from, m - 30);
    to = Math.max(to, m + 30);
  }

  const startMinute = Math.max(0, Math.floor(from / 60) * 60);
  const endMinute = Math.min(DAY_MINUTES, Math.ceil(to / 60) * 60);
  return {
    startMinute,
    endMinute: Math.max(endMinute, startMinute + 60),
  };
}

export interface TimelineFire {
  fire: TodayRecurringFire;
  minute: number;
  /** 竖轴上的像素位置（已经过最小间距下推） */
  top: number;
  /** 未经下推的真实时刻位置，UI 用它画一条引导线 */
  rawTop: number;
  /** 与真实时刻对应的位置有偏移：画一条引导短线说明「这里被挤开了」 */
  shifted: boolean;
}

/** 已排好的一个点：原样带回去，加算好的坐标 */
export interface SpreadSlot<T> {
  item: T;
  /** 下推后的位置 */
  top: number;
  /** 真实位置 */
  rawTop: number;
  shifted: boolean;
}

/**
 * 把一批「已有真实位置」的点按下推规则排开，防止同刻/近刻的点叠在一起。
 *
 * 时间线（24h 竖轴）和课表网格（span 坐标系）的**位置算法不同**，但「挤在一起
 * 就把后面的往下推、并记一笔 shifted 好画引导线」这件事是同一件。所以这里
 * 只接受算好的 `rawTop`，不关心它是怎么来的 —— 两处共用同一套防重叠。
 *
 * 不传 `maxTop` 时不设上限（课表网格会传 `gridHeight - 4`，免得最后一个点
 * 被推出可视区）。输入会先按 rawTop 稳定排序。
 */
export function spreadSlots<T>(
  raws: { item: T; rawTop: number }[],
  minGap = 26,
  maxTop = Infinity,
): SpreadSlot<T>[] {
  const sorted = [...raws].sort((a, b) => a.rawTop - b.rawTop);
  let last = -Infinity;
  const out: SpreadSlot<T>[] = [];

  for (const { item, rawTop } of sorted) {
    const adjusted = Math.min(Math.max(rawTop, last + minGap), maxTop);
    out.push({
      item,
      top: adjusted,
      rawTop: Math.min(rawTop, maxTop),
      shifted: adjusted > rawTop + 0.5,
    });
    last = adjusted;
  }
  return out;
}

/**
 * 24 小时全天竖轴上的排点。
 *
 * 一分钟一条的规则会让相邻点挤成一条线，所以按时间升序依次下推到 `minGap`，
 * 并在 `shifted` 上记一笔（UI 画一条引导线，不假装那个点真的在别的时间）。
 * 最后一个点不会被推出容器。
 */
export function layoutTimelineFires(
  fires: TodayRecurringFire[],
  height: number,
  minGap = 26,
): TimelineFire[] {
  const sorted = [...fires].sort(
    (a, b) => a.fireAt - b.fireAt || a.item.id.localeCompare(b.item.id),
  );
  const maxTop = Math.max(height - 4, 0);
  let last = -Infinity;
  const out: TimelineFire[] = [];

  for (const fire of sorted) {
    const minute = minuteOfDay(fire.fireAt);
    const raw = (minute / DAY_MINUTES) * height;
    const adjusted = Math.min(Math.max(raw, last + minGap), maxTop);
    out.push({
      fire,
      minute,
      top: adjusted,
      rawTop: Math.min(raw, maxTop),
      shifted: adjusted > raw + 0.5,
    });
    last = adjusted;
  }
  return out;
}
