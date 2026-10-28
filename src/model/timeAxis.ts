/**
 * 课表「表格视图」的时间轴。
 *
 * 从原版 `data/TimeAxis.kt` 逐条搬过来。横轴是星期几，纵轴是时间：
 * **从本周最早的一门课开始，每 N 小时一格**。课程按真实时长占据纵向空间，
 * 互相重叠的课左右分栏并排。位置一律用「占整列/整轴的比例」表达，跟像素无关。
 */

import type { ClassEntity } from "./types";

/** 本周的时间范围（当天分钟数，左闭右开） */
export interface Span {
  startMinute: number;
  endMinute: number;
}

/** 一节课排好之后的位置 */
export interface Placed {
  cls: ClassEntity;
  topFraction: number;
  heightFraction: number;
  leftFraction: number;
  widthFraction: number;
}

/** 要在纵轴上放大的时段，左闭右开 [startMinute, endMinute) */
export interface Focus {
  startMinute: number;
  endMinute: number;
}

const HOUR_STEPS = [1, 2, 3, 4, 6, 8, 12];

/** 展开时把该时段在纵轴上放大的倍数 */
export const FOCUS_SCALE = 2.2;
/** 放大时段最多占整轴的比例，免得把其它时段压得看不见 */
export const MAX_FOCUS_SHARE = 0.62;

export function spanMinutes(span: Span): number {
  return Math.max(span.endMinute - span.startMinute, 1);
}

/**
 * 时间轴的比例映射。
 *
 * 没有 focus 时是线性的；有 focus 时改成**分段线性**：focus 那段放大，
 * 其余分钟等比例压缩，总高度不变。这样被展开的课明显变大，
 * 代价只是其它时段略微变矮 —— 比「挤别的块来腾地方」更直观。
 */
export class AxisMapping {
  readonly span: Span;
  private readonly focus: Focus | null;
  private readonly focusShare: number;
  private readonly otherShare: number;
  private readonly focusMinutes: number;
  private readonly otherMinutes: number;
  private readonly focusPerMinute: number;
  private readonly otherPerMinute: number;

  constructor(span: Span, focus: Focus | null, focusShare: number, otherShare: number) {
    this.span = span;
    this.focus = focus;
    this.focusShare = focusShare;
    this.otherShare = otherShare;
    this.focusMinutes = focus ? focus.endMinute - focus.startMinute : 0;
    this.otherMinutes = Math.max(spanMinutes(span) - this.focusMinutes, 0);
    this.focusPerMinute = this.focusMinutes > 0 ? focusShare / this.focusMinutes : 0;
    this.otherPerMinute = this.otherMinutes > 0 ? otherShare / this.otherMinutes : 0;
  }

  fractionOf(minute: number): number {
    const f = this.focus;
    if (!f) return this.linear(minute);
    const head = (f.startMinute - this.span.startMinute) * this.otherPerMinute;
    let value: number;
    if (minute <= f.startMinute) value = this.linearHead(minute);
    else if (minute <= f.endMinute) value = head + (minute - f.startMinute) * this.focusPerMinute;
    else value = head + this.focusShare + (minute - f.endMinute) * this.otherPerMinute;
    return clamp01(value);
  }

  private linear(minute: number): number {
    return clamp01((minute - this.span.startMinute) / spanMinutes(this.span));
  }

  private linearHead(minute: number): number {
    return clamp01((minute - this.span.startMinute) * this.otherPerMinute);
  }
}

/**
 * 造一个映射。
 *
 * `progress` 是「放大到什么程度」：0 = 完全线性，1 = 放大到位。
 * 做成连续参数而不是布尔开关，是为了让「选中放大」能平滑长大：
 * progress=0 时 share 恰好等于该时段原始占比，分段映射精确退化成线性。
 */
export function mappingOf(span: Span, focus: Focus | null, progress = 1): AxisMapping {
  const valid =
    focus &&
    focus.endMinute > focus.startMinute &&
    focus.startMinute >= span.startMinute &&
    focus.endMinute <= span.endMinute
      ? focus
      : null;
  if (!valid) return new AxisMapping(span, null, 1, 1);

  const base = (valid.endMinute - valid.startMinute) / spanMinutes(span);
  // 先用上限夹住，但**不能低于原始占比**，否则反而成了缩小。
  // 顺序不能写成 clamp(base, MAX)：base 本身超过 MAX 时 min > max。
  const full = Math.max(Math.min(base * FOCUS_SCALE, MAX_FOCUS_SHARE), base);
  const share = base + (full - base) * Math.min(Math.max(progress, 0), 1);
  return new AxisMapping(span, valid, share, 1 - share);
}

/** 本周所有课的 [最早开始, 最晚结束]；没有一门时间可解析的课就返回 null */
export function spanOf(classes: ClassEntity[]): Span | null {
  let from = Number.MAX_SAFE_INTEGER;
  let to = Number.MIN_SAFE_INTEGER;
  for (const cls of classes) {
    const start = minutesOf(cls.startTime);
    if (start === null) continue;
    const end = endMinutesOf(cls);
    if (end === null) continue;
    if (start < from) from = start;
    if (end > to) to = end;
  }
  return from === Number.MAX_SAFE_INTEGER ? null : { startMinute: from, endMinute: to };
}

/** 每格几小时：行数不超过 maxRows，从最小步长往上试 */
export function hoursPerRow(span: Span, maxRows: number): number {
  const limit = Math.max(maxRows, 1);
  return (
    HOUR_STEPS.find((step) => ceilDiv(spanMinutes(span), step * 60) <= limit) ??
    HOUR_STEPS[HOUR_STEPS.length - 1]
  );
}

export function rowCount(span: Span, perRow: number): number {
  return Math.max(ceilDiv(spanMinutes(span), perRow * 60), 1);
}

/** 分钟数 → "HH:mm" */
export function labelOf(minute: number): string {
  const day = 24 * 60;
  const normalized = ((minute % day) + day) % day;
  const hh = String(Math.floor(normalized / 60)).padStart(2, "0");
  const mm = String(normalized % 60).padStart(2, "0");
  return `${hh}:${mm}`;
}

/**
 * 「斑马纹」时间带：每隔一小时交替的横向色带。
 *
 * **相位锚定在 span 起点所在的那一小时**，而不是绝对偶数小时。原因：span 起点
 * 由本周最早的课决定，常落在奇数小时（07:43 → 第 7 小时）。若按绝对偶数小时取，
 * 短跨度可能整段落进留白，一条带都没有。锚定起点后第一小时永远有带。
 */
export function zebraBands(span: Span, minuteOfDay = 0): Focus[] {
  const hourOfStart = Math.max(span.startMinute - minuteOfDay, 0) / 60;
  const hourStart = Math.floor(hourOfStart);
  const bands: Focus[] = [];
  for (let i = 0; i < 24; i += 1) {
    const bandStart = minuteOfDay + (hourStart + i * 2) * 60;
    if (bandStart >= span.endMinute) break;
    const bandEnd = bandStart + 60;
    const from = Math.max(bandStart, span.startMinute);
    const to = Math.min(bandEnd, span.endMinute);
    if (to > from) bands.push({ startMinute: from, endMinute: to });
  }
  return bands;
}

/**
 * 没有任何一列被高亮时，纵轴该标哪些时刻。
 *
 * 对齐到 stepMinutes 的整数倍 —— 起点常是 07:43 这种时间，直接按「起点 + n×步长」
 * 标出来就既不是整点也没法当钟表读。
 */
export function roundMarks(span: Span, stepMinutes: number): number[] {
  if (stepMinutes <= 0) return [span.startMinute];
  const first = ceilDiv(span.startMinute, stepMinutes) * stepMinutes;
  const marks: number[] = [];
  for (let m = first; m <= span.endMinute; m += stepMinutes) marks.push(m);
  return marks.length > 0 ? marks : [span.startMinute];
}

/** 线性映射下某个时间点的比例（0..1） */
export function fractionOf(minute: number, span: Span): number {
  return clamp01((minute - span.startMinute) / spanMinutes(span));
}

/**
 * 把某一天的课排好位置。
 *
 * 互相重叠（直接或间接）的课归为一组，组内左右等分并排。
 * 时间解析不出来的课直接跳过 —— 表格里画不出它的位置。
 */
export function layout(dayClasses: ClassEntity[], mapping: AxisMapping): Placed[] {
  const timed = dayClasses
    .map((cls) => {
      const start = minutesOf(cls.startTime);
      const end = endMinutesOf(cls);
      return start === null || end === null ? null : { cls, start, end };
    })
    .filter((x): x is { cls: ClassEntity; start: number; end: number } => x !== null)
    .sort((a, b) => a.start - b.start || a.end - b.end);

  if (timed.length === 0) return [];

  const result: Placed[] = [];
  let groupStart = 0;
  let runningEnd = timed[0].end;

  for (let i = 1; i <= timed.length; i += 1) {
    const next = timed[i];
    if (!next || next.start >= runningEnd) {
      result.push(...placeGroup(timed.slice(groupStart, i), mapping));
      if (next) {
        groupStart = i;
        runningEnd = next.end;
      }
    } else if (next.end > runningEnd) {
      runningEnd = next.end;
    }
  }
  return result;
}

function placeGroup(
  group: { cls: ClassEntity; start: number; end: number }[],
  mapping: AxisMapping,
): Placed[] {
  const share = 1 / group.length;
  return group.map((item, index) => {
    const top = mapping.fractionOf(item.start);
    return {
      cls: item.cls,
      topFraction: top,
      heightFraction: mapping.fractionOf(item.end) - top,
      leftFraction: index * share,
      widthFraction: share,
    };
  });
}

/** "HH:mm" → 当天分钟数；解析不出来返回 null */
export function minutesOf(hhmm: string): number | null {
  const parts = hhmm.split(":");
  const hour = Number.parseInt(parts[0]?.trim() ?? "", 10);
  const minute = Number.parseInt(parts[1]?.trim() ?? "", 10);
  if (!Number.isFinite(hour) || !Number.isFinite(minute)) return null;
  return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59 ? hour * 60 + minute : null;
}

/** 结束分钟；结束不晚于开始的（跨零点、脏数据）按开始 + 1 分钟算，免得画成 0 高度 */
function endMinutesOf(cls: ClassEntity): number | null {
  const start = minutesOf(cls.startTime);
  if (start === null) return null;
  const end = minutesOf(cls.endTime);
  if (end === null) return null;
  return end > start ? end : start + 1;
}

function ceilDiv(a: number, b: number): number {
  return a <= 0 ? 0 : Math.floor((a + b - 1) / b);
}

function clamp01(v: number): number {
  return Math.min(Math.max(v, 0), 1);
}
