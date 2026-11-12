/**
 * 循环提醒的**规则与判定**。整层都是纯函数，不碰时间源、不碰 IPC。
 *
 * 设计口径（v1.7.15 拷问共识，改动前请先读）：
 *
 *  1. 两类规律归约到同一个入口 `nextFireAt(rule, after)`：
 *     · 日历型：每天 / 每周几多选 / 每 N 天，配一个固定 HH:mm；
 *     · 间隔型：每 N 分钟，从 `anchor` 起「anchor + k×间隔」。
 *     两者都**保持原相位**，休眠或重启不改写用户设定的规律。
 *  2. `lastFiredAt` 落盘。每次 tick 只要 `now >= 相位点 && lastFiredAt < 相位点`
 *     就触发一次，于是定时器节流、系统休眠、进程重启三者都只影响延迟，
 *     不影响正确性。
 *  3. 错过的实例**一律跳过**，直接排下一次：把 `lastFiredAt` 快进到
 *     最后一个 ≤ now 的相位点，不补弹、不排队、不设过期上限。
 *     「跳过本次」复用同一套推进。
 *  4. 不做 RRULE / 每月每年 / 例外日期 / 按次数结束 / 打卡。
 *
 * 为什么时间用「本地日的 Date 构造」而不是 `day + 86400000`：
 * 跨夏令时的那天只有 23 或 25 小时。中国目前没有 DST，但规则里存的是
 * 用户看得见的「每天 8:00」，用 `setDate` 才能保证这个 8:00 永远是墙上时钟的 8:00。
 */

import type { ReminderAlert } from "./reminder";

const DAY_MS = 86_400_000;

/** 日历型：星期集合为空 = 每天 */
export interface WeeklyRule {
  kind: "weekly";
  /** 0=周日 … 6=周六，与 `Date.getDay()` 一致。空数组 = 每天 */
  weekdays: number[];
  /** "HH:mm"，本地时间 */
  at: string;
}

/** 日历型：每 N 天，从 anchorDay 起算 */
export interface EveryDaysRule {
  kind: "everyDays";
  days: number;
  at: string;
  /** 锚点日（本地 00:00 的 epoch ms），决定相位 */
  anchorDay: number;
}

/** 间隔型：每 N 分钟，从 anchor 起「anchor + k×间隔」 */
export interface IntervalRule {
  kind: "interval";
  minutes: number;
  /** 相位锚点（epoch ms） */
  anchor: number;
}

export type RecurringRule = WeeklyRule | EveryDaysRule | IntervalRule;

export interface RecurringItem {
  id: string;
  title: string;
  /** 提醒正文；浮窗上作为副标题显示 */
  body: string;
  enabled: boolean;
  rule: RecurringRule;
  /** 可选结束日期（本地 00:00 的 epoch ms）。当天仍然会触发；null = 不限 */
  endAt: number | null;
  /**
   * 上次触发的相位点（epoch ms）。**落盘**，是幂等与保持相位的唯一依据。
   * 0 = 从未触发：首次装载时快进到「最后一个 ≤ now 的相位点」，不补弹。
   */
  lastFiredAt: number;
  createdAt: number;
}

/** 规则类型的界面枚举，与 `RecurringRule.kind` 分开：weekly 一档拆成「每天 / 每周几」 */
export type RuleMode = "daily" | "weekly" | "everyDays" | "interval";

// ── 本地日工具 ─────────────────────────────────────────────────────

export function startOfDay(millis: number): number {
  const d = new Date(millis);
  d.setHours(0, 0, 0, 0);
  return d.getTime();
}

function addDays(dayMillis: number, n: number): number {
  const d = new Date(dayMillis);
  d.setDate(d.getDate() + n);
  return d.getTime();
}

/** "HH:mm" → 本地某天的 epoch ms。非法输入退化为 00:00，不让规则变成 NaN */
function atLocalTime(dayMillis: number, at: string): number {
  const parts = /^(\d{1,2}):(\d{2})$/.exec(at.trim());
  const h = parts ? Math.min(Number(parts[1]), 23) : 0;
  const m = parts ? Math.min(Number(parts[2]), 59) : 0;
  const d = new Date(dayMillis);
  d.setHours(h, m, 0, 0);
  return d.getTime();
}

/** "YYYY-MM-DD" → 本地 00:00 的 epoch ms；空串/非法返回 null */
export function dateInputToMillis(value: string): number | null {
  const m = /^(\d{4})-(\d{2})-(\d{2})$/.exec(value);
  if (!m) return null;
  const d = new Date(Number(m[1]), Number(m[2]) - 1, Number(m[3]), 0, 0, 0, 0);
  const t = d.getTime();
  return Number.isFinite(t) ? t : null;
}

export function millisToDateInput(millis: number): string {
  const d = new Date(millis);
  const p = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
}

/** 结束日期当天的最后一毫秒。当天仍然允许触发 */
function endExclusive(endAt: number): number {
  return addDays(startOfDay(endAt), 1) - 1;
}

function safeAt(value: string): string {
  return /^\d{1,2}:\d{2}$/.test(value ?? "") ? value : "08:00";
}

/** 把界面传来的松散值收敛成合法规则，避免 NaN 相位把轮询打挂 */
export function normalizeRule(rule: RecurringRule, now: number): RecurringRule {
  switch (rule.kind) {
    case "weekly":
      return {
        kind: "weekly",
        at: safeAt(rule.at),
        weekdays: [...new Set(rule.weekdays.filter((d) => d >= 0 && d <= 6))].sort(),
      };
    case "everyDays":
      return {
        kind: "everyDays",
        at: safeAt(rule.at),
        days: Math.min(Math.max(Math.round(rule.days) || 1, 1), 365),
        anchorDay: startOfDay(Number.isFinite(rule.anchorDay) ? rule.anchorDay : now),
      };
    case "interval":
      return {
        kind: "interval",
        minutes: Math.min(Math.max(Math.round(rule.minutes) || 1, 1), 60 * 24 * 30),
        anchor: Number.isFinite(rule.anchor) ? rule.anchor : now,
      };
  }
}

// ── 相位计算 ───────────────────────────────────────────────────────

/**
 * 第一个「≥ dayMillis 所在日」的匹配日。
 * weekly 逐日找（最多一周必有命中）；everyDays 先用算术估计 k 再微调，
 * 免得锚点在几年前时循环几万次。
 */
function matchDayFrom(rule: WeeklyRule | EveryDaysRule, dayMillis: number): number | null {
  const target = startOfDay(dayMillis);
  if (rule.kind === "weekly") {
    for (let i = 0; i < 8; i++) {
      const d = addDays(target, i);
      if (rule.weekdays.length === 0 || rule.weekdays.includes(new Date(d).getDay())) {
        return d;
      }
    }
    return null;
  }
  const anchor = startOfDay(rule.anchorDay);
  const step = rule.days * DAY_MS;
  let k = Math.max(0, Math.floor((target - anchor) / step) - 2);
  for (let i = 0; i < 8; i++, k++) {
    const d = addDays(anchor, k * rule.days);
    if (d >= target) return d;
  }
  return null;
}

function nextCalendarFire(
  rule: WeeklyRule | EveryDaysRule,
  after: number,
): number | null {
  let probe = startOfDay(after);
  for (let i = 0; i < 4; i++) {
    const day = matchDayFrom(rule, probe);
    if (day === null) return null;
    const fire = atLocalTime(day, rule.at);
    if (fire > after) return fire;
    // 这个匹配日的固定时刻已经过去 → 从次日继续找
    probe = addDays(day, 1);
  }
  return null;
}

/** 最后一个 ≤ dayMillis 所在日的匹配日。与 `matchDayFrom` 互为反向 */
function lastMatchDayAtOrBefore(
  rule: WeeklyRule | EveryDaysRule,
  dayMillis: number,
): number | null {
  const target = startOfDay(dayMillis);
  if (rule.kind === "weekly") {
    for (let i = 0; i < 8; i++) {
      const d = addDays(target, -i);
      if (rule.weekdays.length === 0 || rule.weekdays.includes(new Date(d).getDay())) {
        return d;
      }
    }
    return null;
  }
  const anchor = startOfDay(rule.anchorDay);
  if (target < anchor) return null;
  const step = rule.days * DAY_MS;
  let k = Math.max(0, Math.floor((target - anchor) / step));
  // 算术估计在夏令时边界可能差一天，前后各微调一格即可
  let d = addDays(anchor, k * rule.days);
  let guard = 0;
  while (d > target && k > 0 && guard++ < 4) {
    k -= 1;
    d = addDays(anchor, k * rule.days);
  }
  guard = 0;
  while (addDays(anchor, (k + 1) * rule.days) <= target && guard++ < 4) {
    k += 1;
    d = addDays(anchor, k * rule.days);
  }
  return d <= target ? d : null;
}

function lastCalendarFire(
  rule: WeeklyRule | EveryDaysRule,
  t: number,
): number | null {
  const day = lastMatchDayAtOrBefore(rule, startOfDay(t));
  if (day === null) return null;
  const fire = atLocalTime(day, rule.at);
  if (fire <= t) return fire;
  // 今天就是匹配日，但固定时刻还没到 → 退回上一个匹配日
  const prev = lastMatchDayAtOrBefore(rule, addDays(day, -1));
  if (prev === null) return null;
  const earlier = atLocalTime(prev, rule.at);
  return earlier <= t ? earlier : null;
}

/**
 * 严格晚于 `after` 的下一个触发时刻。
 *
 * 间隔型用闭式解 `anchor + (floor((after-anchor)/step)+1)×step`：
 * 相位只由 anchor 决定，跟"现在几点"无关 —— 这正是保持相位的关键。
 */
export function nextFireAt(rule: RecurringRule, after: number): number | null {
  if (rule.kind === "interval") {
    const step = rule.minutes * 60_000;
    if (step <= 0 || !Number.isFinite(rule.anchor)) return null;
    const k = Math.floor((after - rule.anchor) / step) + 1;
    return rule.anchor + k * step;
  }
  return nextCalendarFire(rule, after);
}

/** 最后一个 ≤ t 的触发时刻。用于新规则初始化与「错过一律跳过」的快进 */
export function lastFireAtOrBefore(rule: RecurringRule, t: number): number | null {
  if (rule.kind === "interval") {
    const step = rule.minutes * 60_000;
    if (step <= 0 || !Number.isFinite(rule.anchor) || t < rule.anchor) return null;
    const k = Math.floor((t - rule.anchor) / step);
    return rule.anchor + k * step;
  }
  return lastCalendarFire(rule, t);
}

// ── 一轮轮询的判定 ─────────────────────────────────────────────────

export interface RecurringFire {
  item: RecurringItem;
  fireAt: number;
}

export interface RecurringFireAtUpdate {
  id: string;
  lastFiredAt: number;
}

export interface RecurringPlan {
  /** 本轮最多一条：浮窗是单例、通知 tag 固定，物理上无法并排显示两条 */
  fire: RecurringFire | null;
  /** 需要落盘的 lastFiredAt（含被跳过的快进） */
  updates: RecurringFireAtUpdate[];
}

/** 超过这个间隔没检查过，就认为刚从休眠/节流中回来：不补弹，只快进 */
export const CATCH_UP_LIMIT_MS = 90_000;

/**
 * 一轮轮询的纯判定。
 *
 * `prevCheckAt` 传 0 表示启动后的第一次检查：此时一律不弹（Q7），
 * 只把每条规则的 lastFiredAt 对齐到时钟。正常轮询时，只有「相位点落在
 * (prevCheckAt, now] 这一段里」才会触发 —— 于是休眠 3 小时后那 4 个
 * 错过的点全部被快进掉，不会连弹。
 */
export function planRecurring(
  items: RecurringItem[],
  now: number,
  prevCheckAt: number,
  catchUpLimitMs: number = CATCH_UP_LIMIT_MS,
): RecurringPlan {
  const fresh = prevCheckAt > 0 && now - prevCheckAt <= catchUpLimitMs;
  const candidates: RecurringFire[] = [];
  const updates: RecurringFireAtUpdate[] = [];

  for (const item of items) {
    if (!item.enabled) continue;
    if (item.endAt !== null && now > endExclusive(item.endAt)) continue;

    // 新规则：从「最后一个 ≤ now 的相位点」起算，创建之前的历史一律不补弹
    if (!item.lastFiredAt || item.lastFiredAt <= 0) {
      const aligned = lastFireAtOrBefore(item.rule, now);
      if (aligned !== null) updates.push({ id: item.id, lastFiredAt: aligned });
      continue;
    }

    let due = nextFireAt(item.rule, item.lastFiredAt);
    let first: number | null = null;
    let lastDue: number | null = null;
    let guard = 0;
    while (due !== null && due <= now && guard++ < 10_000) {
      if (first === null) first = due;
      lastDue = due;
      due = nextFireAt(item.rule, due);
    }
    if (lastDue === null) continue;

    // 只在这个相位点确实是「刚才跨过去的」时才提醒；更早的是休眠欠账
    if (fresh && first !== null && first > prevCheckAt && first <= now) {
      candidates.push({ item, fireAt: first });
    }
    // 无论触发与否，都把 lastFiredAt 快进到最后一个 ≤ now 的相位点
    updates.push({ id: item.id, lastFiredAt: lastDue });
  }

  // 同一时刻撞车时取相位最早的一条，同相位按 id 稳定排序
  candidates.sort((a, b) => a.fireAt - b.fireAt || a.item.id.localeCompare(b.item.id));
  return { fire: candidates[0] ?? null, updates };
}

/**
 * 「跳过本次」：把 lastFiredAt 推进一个周期。
 * 复用 nextFireAt，与「错过快进」是同一套相位逻辑，不另写一条代码路径。
 */
export function skipOnce(item: RecurringItem, now: number): number {
  const base =
    item.lastFiredAt > 0
      ? item.lastFiredAt
      : (lastFireAtOrBefore(item.rule, now) ?? now);
  return nextFireAt(item.rule, base) ?? base;
}

/** 规则在界面上的下一次触发时刻（已考虑结束日期）；null = 不再触发 */
export function upcomingFireAt(item: RecurringItem, now: number): number | null {
  if (!item.enabled) return null;
  const base = Math.max(now, item.lastFiredAt || 0);
  const next = nextFireAt(item.rule, base);
  if (next === null) return null;
  if (item.endAt !== null && next > endExclusive(item.endAt)) return null;
  return next;
}

// ── 文案 ───────────────────────────────────────────────────────────

const WEEKDAY_CN = ["日", "一", "二", "三", "四", "五", "六"];

export function describeRule(rule: RecurringRule): string {
  switch (rule.kind) {
    case "weekly":
      return rule.weekdays.length === 0
        ? `每天 ${rule.at}`
        : `每周${[...rule.weekdays].sort().map((d) => WEEKDAY_CN[d]).join("、")} ${rule.at}`;
    case "everyDays":
      return `每 ${rule.days} 天 ${rule.at}`;
    case "interval": {
      const m = rule.minutes;
      if (m % (60 * 24) === 0) return `每 ${m / (60 * 24)} 天`;
      if (m % 60 === 0) return `每 ${m / 60} 小时`;
      return `每 ${m} 分钟`;
    }
  }
}

/** 今日页卡片的一行：一条规则 + 它今天内下一次触发时刻 */
export interface TodayRecurringFire {
  item: RecurringItem;
  /** 本地时刻（epoch ms），严格晚于 now 且落在今天之内 */
  fireAt: number;
  /** 含本次在内，今天还剩几个相位点；封顶避免「每 1 分钟」刷满列表 */
  remainingToday: number;
}

/** 单条规则一天最多数到这里。真实间隔最小 1 分钟也不过 1440，500 足够表意 */
const MAX_TODAY_FIRES = 500;

/**
 * 今日页右列用：只列**今天之内还有相位点**的启用规则，按触发时刻升序。
 *
 * 与 `planRecurring` 的区别：那边问「这一轮要不要弹浮窗」，这边问「今天
 * 还会响几次」——所以不碰 lastFiredAt、不产生任何落盘更新，是纯读函数。
 * 停用的、结束日期已过的、下一次已经排到明天的规则都不出现。
 * 同刻撞车按 id 稳定排序，跟浮窗那边口径一致。
 */
export function todayRecurringFires(
  items: RecurringItem[],
  now: number,
): TodayRecurringFire[] {
  // 用 setDate 加一天再减 1ms，而不是 startOfDay + 86400000：跨夏令时那天
  // 只有 23 或 25 小时。中国目前无 DST，但这里和全文件保持同一套本地日口径。
  const endOfDay = addDays(startOfDay(now), 1) - 1;
  const out: TodayRecurringFire[] = [];

  for (const item of items) {
    if (!item.enabled) continue;
    const fireAt = upcomingFireAt(item, now);
    if (fireAt === null || fireAt > endOfDay) continue;

    const hardEnd =
      item.endAt === null ? endOfDay : Math.min(endOfDay, endExclusive(item.endAt));
    let count = 0;
    let t: number | null = fireAt;
    while (t !== null && t <= hardEnd && count < MAX_TODAY_FIRES) {
      count += 1;
      t = nextFireAt(item.rule, t);
    }
    out.push({ item, fireAt, remainingToday: count });
  }

  out.sort((a, b) => a.fireAt - b.fireAt || a.item.id.localeCompare(b.item.id));
  return out;
}

/** 设置页「下次触发」的一行文案 */
export function describeNext(fireAt: number | null, now: number): string {
  if (fireAt === null) return "已结束或不启用";
  const delta = fireAt - now;
  if (delta <= 0) return "即将触发";
  const mins = Math.floor(delta / 60_000);
  if (mins < 60) return `${Math.max(1, mins)} 分钟后`;
  if (mins < 24 * 60) return `${Math.floor(mins / 60)} 小时后`;
  const d = new Date(fireAt);
  return `${d.getMonth() + 1} 月 ${d.getDate()} 日 ${String(d.getHours()).padStart(2, "0")}:${String(d.getMinutes()).padStart(2, "0")}`;
}

/** 新建规则时的默认锚点：日历型用今天 00:00，间隔型用此刻 */
export function defaultRule(mode: RuleMode, now: number): RecurringRule {
  switch (mode) {
    case "daily":
      return { kind: "weekly", weekdays: [], at: "08:00" };
    case "weekly":
      return { kind: "weekly", weekdays: [new Date(now).getDay()], at: "08:00" };
    case "everyDays":
      return { kind: "everyDays", days: 2, at: "08:00", anchorDay: startOfDay(now) };
    case "interval":
      return { kind: "interval", minutes: 45, anchor: now };
  }
}

export function ruleModeOf(rule: RecurringRule): RuleMode {
  if (rule.kind === "interval") return "interval";
  if (rule.kind === "everyDays") return "everyDays";
  return rule.weekdays.length === 0 ? "daily" : "weekly";
}

/**
 * 循环提醒复用上课提醒的那一帧 alert（与浮窗结构）。
 *
 * 三个字段是刻意这样填的：
 *  · `room` 承载正文 —— 浮窗上本来就是副标题行，不用给它加新字段；
 *  · `startMillis == endMillis` 让 `overlayView` 判定「区间退化」，
 *    自动不显示倒计时和进度条；
 *  · `recurringId` 是唯一新增的可选字段，浮窗据此显示「跳过本次」。
 *
 * **不写 lastFiredAt、不改相位**。「立即试一次」与真正触发共用这一帧，
 * 区别只在于调用方是否随后落盘。
 */
/**
 * 「立即试一次」用的假规则 id。
 *
 * 预览帧照常带 `recurringId`（浮窗才会显示「跳过本次」，IPC 通路才验得到），
 * 但主窗口按这个 id 找不到真实规则，`commit` 直接 no-op —— 于是预览
 * 既不写 lastFiredAt，也不改相位，规则本身完全没被动过。
 */
export const RECURRING_PREVIEW_ID = "__preview__";

export function recurringAlert(item: RecurringItem, now: number): ReminderAlert {
  return {
    classId: -1,
    title: item.title,
    room: item.body,
    startMillis: now,
    endMillis: now,
    ongoing: false,
    recurringId: item.id,
  };
}
