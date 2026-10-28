/**
 * 「今天的课」相关的纯计算：HH:mm → 今天的时间戳、还剩几节、此刻是否空闲。
 * 从原版 `data/TodaySchedule.kt` 搬过来。
 */

import type { ClassEntity } from "./types";
import { weekContains } from "./weekSchedule";

export const DAY_NAMES = [
  "Monday",
  "Tuesday",
  "Wednesday",
  "Thursday",
  "Friday",
  "Saturday",
  "Sunday",
];

/** 今天的一次课；起止时间已经换算成今天的时间戳 */
export interface TodayClass {
  entity: ClassEntity;
  startMillis: number;
  endMillis: number;
}

export function ongoingAt(c: TodayClass, now: number): boolean {
  return now >= c.startMillis && now <= c.endMillis;
}

export function startsWithin(c: TodayClass, now: number, windowMillis: number): boolean {
  return now >= c.startMillis - windowMillis && now < c.startMillis;
}

export function remainingAt(c: TodayClass, now: number): boolean {
  return c.endMillis > now;
}

/** 设备当前是星期几（Monday…Sunday，跟数据库里存的一致） */
export function dayNameOf(millis: number): string {
  // JS: 周日=0 → 需要映射成 Monday 开头
  return DAY_NAMES[(new Date(millis).getDay() + 6) % 7];
}

/** yyyy-MM-dd（本地时区） */
export function dateOf(millis: number): string {
  const d = new Date(millis);
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

/**
 * 时段问候语（今天页的页面标题）。
 *
 * 分档沿用原版 `TodayText.kt` 的口径：按一天的作息节奏切，而不是把 24 小时均分 ——
 * 均分出来的边界（比如 12:00 一刀切）会让人觉得「中午 12 点还算上午」很别扭。
 * 边界整点归**后一档**：08:59 还是早上好，09:00 就切到午安。
 */
export function greetingOf(millis: number): string {
  const hour = new Date(millis).getHours();
  if (hour >= 5 && hour <= 8) return "早上好。";
  if (hour >= 9 && hour <= 11) return "午安。";
  if (hour >= 12 && hour <= 17) return "下午好。";
  if (hour >= 18 && hour <= 22) return "晚上好。";
  return "夜深了。";
}

/** 「10 月 28 日」——原版副标题的日期口径，月/日不补零 */
export function monthDayOf(millis: number): string {
  const d = new Date(millis);
  return `${d.getMonth() + 1} 月 ${d.getDate()} 日`;
}

/** 今天要上的课：长期课程按星期+周次筛，临时提醒只认自己的日期；按开始时间排序 */
export function todayClasses(
  classes: ClassEntity[],
  currentWeekNumber: number | null,
  now: number,
): TodayClass[] {
  const todayName = dayNameOf(now);
  const todayDate = dateOf(now);
  return classes
    .filter((entity) => occursOn(entity, todayName, todayDate, currentWeekNumber))
    .map((entity) => toTodayClass(entity, now))
    .filter((c): c is TodayClass => c !== null)
    .sort((a, b) => a.startMillis - b.startMillis);
}

function occursOn(
  entity: ClassEntity,
  todayName: string,
  todayDate: string,
  currentWeekNumber: number | null,
): boolean {
  if (entity.date) {
    // 临时提醒：只在那一天生效一次，跟周次校准无关
    return entity.date === todayDate;
  }
  return (
    entity.dayOfWeek === todayName &&
    // 只算本周真的会上课的那些（周次没校准/写不清时不限制）
    (currentWeekNumber === null || weekContains(entity.weeks, currentWeekNumber))
  );
}

/** 今天还没结束的课 —— 空闲时段里的「今日剩余」 */
export function remaining(today: TodayClass[], now: number): TodayClass[] {
  return today.filter((c) => remainingAt(c, now));
}

/**
 * 「此刻是否空闲」—— 今天页主卡的三种状态之一。
 *
 * 判定口径对齐设置里的**提前提醒窗口**：[advanceMinutes] 的语义本来就是
 * 「离上课还有 N 分钟就该提示我了」，所以「还没进预警窗」就等于「现在确实还闲着」。
 *
 * 三个必要条件缺一不可：
 *  1. 现在没有课正在进行
 *  2. 今天还有没上完的课（全上完是另一种状态）
 *  3. 离最近那节还没开始的课，间隔**严格大于**预警窗口
 *
 * 边界取严格大于：正好剩 30 分钟而预警也是 30 分钟时，应该已经开始提示了。
 */
export function isIdle(upcoming: TodayClass[], now: number, advanceMinutes: number): boolean {
  if (upcoming.some((c) => ongoingAt(c, now))) return false;
  const next = upcoming.find((c) => c.startMillis > now);
  if (!next) return false;
  const window = Math.max(advanceMinutes, 0) * 60_000;
  return next.startMillis - now > window;
}

/** 一次性提醒的日期；不是一次性提醒或格式不对时返回 null */
export function oneOffDateOf(entity: ClassEntity): number | null {
  if (!entity.date) return null;
  return dateMillisOf(entity.date);
}

/** 一次性提醒的日期是否落在 [fromMillis, toMillis) 之内 */
export function occursInRange(entity: ClassEntity, fromMillis: number, toMillis: number): boolean {
  const date = oneOffDateOf(entity);
  if (date === null) return true;
  return date >= fromMillis && date < toMillis;
}

export function isValidDate(date: string): boolean {
  return dateMillisOf(date) !== null;
}

export function dayNameOfDate(date: string): string | null {
  const millis = dateMillisOf(date);
  return millis === null ? null : dayNameOf(millis);
}

function dateMillisOf(date: string): number | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date.trim());
  if (!m) return null;
  const y = Number(m[1]);
  const mo = Number(m[2]) - 1;
  const d = Number(m[3]);
  const dt = new Date(y, mo, d);
  // 挡住 2026-02-31 这种「Date 会自动进位」的假合法日期
  if (dt.getFullYear() !== y || dt.getMonth() !== mo || dt.getDate() !== d) return null;
  return dt.getTime();
}

function toTodayClass(entity: ClassEntity, now: number): TodayClass | null {
  const startMinutes = minutesOf(entity.startTime);
  const endMinutes = minutesOf(entity.endTime);
  if (startMinutes === null || endMinutes === null) return null;

  const base = new Date(now);
  const at = (minutes: number): number => {
    const d = new Date(base);
    d.setHours(Math.floor(minutes / 60), minutes % 60, 0, 0);
    return d.getTime();
  };

  const startMillis = at(startMinutes);
  let endMillis = at(endMinutes);
  // 跨零点的课（end <= start）算到第二天
  if (endMillis <= startMillis) endMillis += 24 * 60 * 60 * 1000;
  return { entity, startMillis, endMillis };
}

export function minutesOf(hhmm: string): number | null {
  const parts = hhmm.split(":");
  const hour = Number.parseInt(parts[0] ?? "", 10);
  const minute = Number.parseInt(parts[1] ?? "", 10);
  if (!Number.isFinite(hour) || !Number.isFinite(minute)) return null;
  return hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59 ? hour * 60 + minute : null;
}

/** "HH:mm" 距现在的时长文案，用于「距下课 23 分钟」这类行内提示 */
export function humanDuration(millis: number): string {
  const minutes = Math.max(Math.round(millis / 60_000), 0);
  if (minutes < 60) return `${minutes} 分钟`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest === 0 ? `${hours} 小时` : `${hours} 小时 ${rest} 分`;
}
