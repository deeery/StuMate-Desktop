/**
 * 周次工具：解析课表的「周数」文本，并换算学期周次。
 *
 * 从原版 `data/WeekSchedule.kt` 逐条搬过来（含每条注释里的坑）。
 * 支持：`1-16周`、`第6周`、`1-8周,10-12周`、`1,3,5周`、`1-16周(单)`、`2-16周(双)`。
 *
 * 解析不出周次时返回 null，含义是「不确定」—— 此时不限制显示和提醒，
 * 免得用户随手写点别的备注就把整门课隐藏掉。
 */

export const WEEK_MILLIS = 7 * 24 * 60 * 60 * 1000;
const MAX_WEEK = 60;

const RANGE = /(\d+)\s*-\s*(\d+)/g;
const SINGLE = /\d+/g;

/**
 * 解析周次集合；文本里没有可用的周次数字时返回 null。
 *
 * 实现上先把区间吃掉，剩下的数字按单周算 —— 否则 `1-16` 里的 1 和 16
 * 会被再统计一次（虽然结果集合相同，但 `rest` 的处理决定了单双周过滤的语义）。
 */
export function parseWeeks(text: string): Set<number> | null {
  if (!text || !text.trim()) return null;
  const weeks = new Set<number>();
  let rest = text;

  for (const match of text.matchAll(RANGE)) {
    const from = Number.parseInt(match[1], 10);
    const to = Number.parseInt(match[2], 10);
    if (Number.isFinite(from) && Number.isFinite(to) && from >= 1 && to >= from && to <= MAX_WEEK) {
      for (let w = from; w <= to; w += 1) weeks.add(w);
    }
    rest = rest.replace(match[0], " ");
  }
  for (const match of rest.matchAll(SINGLE)) {
    const w = Number.parseInt(match[0], 10);
    if (w >= 1 && w <= MAX_WEEK) weeks.add(w);
  }
  if (weeks.size === 0) return null;

  const oddOnly = text.includes("单");
  const evenOnly = text.includes("双");
  const filtered = [...weeks]
    .filter((w) => (oddOnly ? w % 2 === 1 : evenOnly ? w % 2 === 0 : true))
    .sort((a, b) => a - b);

  return filtered.length > 0 ? new Set(filtered) : null;
}

/** 这门课在第 week 周是否有课；周次写不出来时一律算「有」 */
export function weekContains(weeksText: string, week: number): boolean {
  const set = parseWeeks(weeksText);
  return set ? set.has(week) : true;
}

/** 把任意时刻归一到它所在那一周的周一 00:00（本地时区） */
export function mondayOf(millis: number): number {
  const d = new Date(millis);
  d.setHours(0, 0, 0, 0);
  // JS: 周日=0 … 周六=6 → 距离本周周一几天
  const daysSinceMonday = (d.getDay() + 6) % 7;
  d.setDate(d.getDate() - daysSinceMonday);
  return d.getTime();
}

/** 第 week 周的周一（week1Monday 是第 1 周的周一） */
export function weekStart(week1Monday: number, week: number): number {
  return week1Monday + (week - 1) * WEEK_MILLIS;
}

/** 今天是第几周（week1Monday 是第 1 周的周一） */
export function weekNumber(week1Monday: number, today: number): number {
  return Math.floor((mondayOf(today) - mondayOf(week1Monday)) / WEEK_MILLIS) + 1;
}

/** 没校准时返回 null —— 此时课表不按周次过滤 */
export function currentWeek(week1Monday: number, now = Date.now()): number | null {
  if (!week1Monday) return null;
  return weekNumber(week1Monday, now);
}
