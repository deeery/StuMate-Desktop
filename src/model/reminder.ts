/**
 * 上课提醒的判定逻辑。从原版 `platform/ReminderEngine.kt` 的 `checkAndNotify()` 搬过来。
 *
 * **为什么这层在 TS 而不是 Rust**：原版的判定完全建立在 `TodaySchedule`（今天有哪些课、
 * 哪节正在上、哪节快开始）之上，而 `TodaySchedule` 在 Lite 里对应 `src/model/today.ts`，
 * 已经被 vitest 钉住。如果搬到 Rust 再写一遍，就等于同一套规则有第二份实现 ——
 * 两份代码各自演化之后，「今天页说在上课、提醒却没响」这种问题会非常难查。
 * 所以这里只做**纯函数判定**，定时器与副作用留在 `useReminder` 里。
 *
 * 判定口径与原版逐条对齐（顺序也有意义）：
 *   1. 正在上的课里取**开始最晚**的那节（同一时段有重叠课时，取最近开始的）
 *   2. 否则取「已进入提前提醒窗口」的课里**开始最早**的那节
 *   3. 都没有 → 只更新状态文案，不提醒
 */

import type { ClassEntity, ReminderMode } from "./types";
import {
  ongoingAt,
  remaining,
  startsWithin,
  todayClasses,
  type TodayClass,
} from "./today";

/** 托盘 tooltip / 菜单里显示的一行状态 */
export interface ReminderStatus {
  title: string;
  detail: string;
}

/**
 * 当前该用哪种方式呈现提醒。
 *
 * 三档之外还兜一层：`reminderMode` 是 v1.7.28 新增的键，旧设置文件里没有，
 * 磁盘上也可能是被人手改过的字符串/越界数字。这里**永远给出一个合法值**，
 * 免得 `useReminder` 里出现「三档都不匹配 → 一声不响」的静默失效。
 *
 * 迁移口径：没有 `reminderMode` 的老用户，按原来的 `showPopup` 解释
 * —— 开着就是全局浮窗，关掉就是不弹。这保证升级前后观感一致。
 */
export function reminderModeOf(prefs: {
  reminderMode?: unknown;
  showPopup?: unknown;
} | null | undefined): ReminderMode {
  const raw = prefs?.reminderMode;
  if (raw === 0 || raw === 1 || raw === 2) return raw;
  // 字符串数字也认（手改 settings.lite.json 时很常见）
  if (raw === "0" || raw === "1" || raw === "2") return Number(raw) as ReminderMode;
  return prefs?.showPopup === false ? 2 : 0;
}

export interface ReminderAlert {
  classId: number;
  title: string;
  room: string;
  startMillis: number;
  endMillis: number;
  /** true = 已经在上课（上课铃已响），false = 马上要上课 */
  ongoing: boolean;
  /**
   * v1.7.15 循环提醒：非空表示这一帧来自某条规则，浮窗据此显示「跳过本次」。
   * 上课提醒一律不写这个字段。
   *
   * 刻意不新增 `kind` / 独立结构：Rust 侧把 alert 当 `Value` 原样透传，
   * 浮窗仍是同一张 460×176 卡片，用标题文字区分来源就够了。
   */
  recurringId?: string;
}

export interface ReminderDecision {
  status: ReminderStatus;
  /** null = 这一轮没有需要提醒的课 */
  alert: ReminderAlert | null;
}

export interface ReminderInput {
  classes: ClassEntity[];
  /** 当前第几周；未校准为 null（此时不按周次过滤） */
  currentWeek: number | null;
  now: number;
  /** 提前多少分钟开始提醒 */
  advanceMinutes: number;
}

/** "HH:mm"（本地时区） */
export function formatClock(millis: number): string {
  const d = new Date(millis);
  return `${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

function detailOf(c: TodayClass): string {
  return `${c.entity.room}  ${formatClock(c.startMillis)} - ${formatClock(c.endMillis)}`;
}

/**
 * 挑出这一轮该关注的那节课。
 *
 * 抽成独立函数是为了单测能直接打它 —— 原版这段 `when { ... }` 嵌在副作用里，
 * 只能靠跑起来观察，测不到边界。
 */
export function pickFocus(
  today: TodayClass[],
  now: number,
  advanceMinutes: number,
): TodayClass | null {
  const ongoing = today.filter((c) => ongoingAt(c, now));
  if (ongoing.length > 0) {
    // 重叠课取「开始最晚」的：它才是刚打铃、最该提醒的那节
    return ongoing.reduce((a, b) => (b.startMillis >= a.startMillis ? b : a));
  }
  const window = Math.max(advanceMinutes, 0) * 60_000;
  const upcoming = today.filter((c) => startsWithin(c, now, window));
  if (upcoming.length > 0) {
    // 快上课的取「开始最早」的：先上的先提醒
    return upcoming.reduce((a, b) => (b.startMillis < a.startMillis ? b : a));
  }
  return null;
}

/** 纯判定：给定课表与此刻，得出「该显示什么状态」和「该不该提醒」。 */
export function decideReminder(input: ReminderInput): ReminderDecision {
  const { classes, currentWeek, now, advanceMinutes } = input;
  const today = todayClasses(classes, currentWeek, now);
  const focus = pickFocus(today, now, advanceMinutes);
  const left = remaining(today, now);

  const status: ReminderStatus = focus
    ? {
        title: `${ongoingAt(focus, now) ? "正在上课" : "即将上课"}：${focus.entity.title}`,
        detail: detailOf(focus),
      }
    : today.length === 0
      ? { title: "StuMate Lite", detail: "运行中 — 今日无课" }
      : left.length === 0
        ? { title: "今日课程已全部结束！", detail: `今天共 ${today.length} 节课` }
        : {
            title: `StuMate Lite — 今日剩余 ${left.length} 节课`,
            detail: `下一节：${left[0].entity.title} ${formatClock(left[0].startMillis)} ${left[0].entity.room}`,
          };

  const alert: ReminderAlert | null = focus
    ? {
        classId: focus.entity.id,
        title: focus.entity.title,
        room: focus.entity.room,
        startMillis: focus.startMillis,
        endMillis: focus.endMillis,
        ongoing: ongoingAt(focus, now),
      }
    : null;

  return { status, alert };
}

/**
 * 提醒卡上要显示的东西。口径逐条对齐原版 `OverlayScreen.kt`：
 *   · 倒计时与进度**都从时间区间现算**，不信 `ongoing` 字段 ——
 *     卡片可能停留几十秒，期间"还有 1 分钟"会变成"已上课 0 分钟"。
 *   · `end <= start`（区间退化）时一律降级成"只显示时间"，不做除零。
 */
export interface OverlayView {
  phase: "正在上课" | "即将上课";
  /** 空字符串表示不显示（区间退化或时间已过完） */
  countdown: string;
  /** 0..1，只有"正在上课"时才有意义 */
  progress: number;
}

export function overlayView(alert: ReminderAlert, now: number): OverlayView {
  const { startMillis: start, endMillis: end } = alert;
  const hasRange = end > start;
  const inClass = hasRange && now >= start && now <= end;
  const minutesIn = inClass ? Math.floor((now - start) / 60_000) : 0;
  // 向上取整：还剩 30 秒也算"还有 1 分钟"，显示 0 分钟会让人以为已经上课了
  const minutesToStart =
    hasRange && now < start ? Math.ceil((start - now) / 60_000) : 0;
  const progress = inClass ? (now - start) / (end - start) : 0;

  return {
    phase: alert.ongoing ? "正在上课" : "即将上课",
    countdown: inClass
      ? `已上课 ${minutesIn} 分钟`
      : minutesToStart > 0
        ? `还有 ${minutesToStart} 分钟`
        : "",
    progress,
  };
}

