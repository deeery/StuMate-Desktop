/**
 * 循环提醒计算层。时间全部用 `new Date(y,m,d,h,mi)` **本地构造**，
 * 不写 ISO 字符串 —— 后者带 Z 会被解释成 UTC，换台机器跑就全错。
 * now 是显式参数，不需要假时钟库。
 */

import { describe, expect, it } from "vitest";
import {
  CATCH_UP_LIMIT_MS,
  dateInputToMillis,
  describeNext,
  describeRule,
  firesOnDay,
  lastFireAtOrBefore,
  millisToDateInput,
  nextFireAt,
  normalizeRule,
  planRecurring,
  ruleModeOf,
  skipOnce,
  startOfDay,
  todayRecurringFires,
  upcomingFireAt,
  type RecurringItem,
  type RecurringRule,
} from "../recurring";

const at = (y: number, m: number, d: number, h = 0, mi = 0, s = 0) =>
  new Date(y, m - 1, d, h, mi, s, 0).getTime();

/** 2026-11-08 是周日 */
const SUN = at(2026, 11, 8);
const MON = at(2026, 11, 9);
const clock = (h: number, mi = 0, s = 0) => at(2026, 11, 8, h, mi, s);

const item = (over: Partial<RecurringItem> = {}): RecurringItem => ({
  id: "r1",
  title: "起身活动",
  body: "别一直坐着",
  enabled: true,
  rule: { kind: "interval", minutes: 45, anchor: at(2026, 11, 8, 9) },
  endAt: null,
  lastFiredAt: 0,
  createdAt: SUN,
  ...over,
});

describe("nextFireAt · 间隔型保持相位", () => {
  const rule: RecurringRule = {
    kind: "interval",
    minutes: 45,
    anchor: at(2026, 11, 8, 9),
  };

  it("相位只由 anchor 决定，不因 now 漂移", () => {
    expect(nextFireAt(rule, at(2026, 11, 8, 9))).toBe(at(2026, 11, 8, 9, 45));
    // 11:00 醒来 → 11:15，而不是 11:45（从现在重新起算）
    expect(nextFireAt(rule, clock(11))).toBe(clock(11, 15));
    expect(nextFireAt(rule, clock(11, 15))).toBe(clock(12));
  });

  it("lastFireAtOrBefore 含等号，且锚点之前返回 null", () => {
    expect(lastFireAtOrBefore(rule, clock(11))).toBe(clock(10, 30));
    expect(lastFireAtOrBefore(rule, clock(10, 30))).toBe(clock(10, 30));
    expect(lastFireAtOrBefore(rule, clock(8, 59))).toBeNull();
  });

  it("跨天仍然是同一条相位序列", () => {
    // 9:00 起每 45 分钟：… 23:15, 次日 0:00, 0:45 …
    const next = nextFireAt(rule, at(2026, 11, 8, 23, 50));
    expect(next).toBe(at(2026, 11, 9, 0, 0));
  });
});

describe("nextFireAt · 日历型", () => {
  it("每天固定时刻：当天已过就排明天，不会因为休眠改成「明天 11:00」", () => {
    const rule: RecurringRule = { kind: "weekly", weekdays: [], at: "08:00" };
    expect(nextFireAt(rule, clock(11))).toBe(at(2026, 11, 9, 8));
    expect(lastFireAtOrBefore(rule, clock(11))).toBe(clock(8));
    expect(lastFireAtOrBefore(rule, clock(7, 59))).toBe(at(2026, 11, 7, 8));
  });

  it("每周几多选：周日之后命中周一；指定周三则排到周三", () => {
    const mw: RecurringRule = { kind: "weekly", weekdays: [1, 3, 5], at: "09:00" };
    expect(nextFireAt(mw, clock(11))).toBe(at(2026, 11, 9, 9));
    const onlyWed: RecurringRule = { kind: "weekly", weekdays: [3], at: "09:00" };
    expect(nextFireAt(onlyWed, clock(11))).toBe(at(2026, 11, 11, 9));
    expect(describeRule(mw)).toBe("每周一、三、五 09:00");
  });

  it("每 N 天：锚点决定相位，几年前锚点也能快速定位", () => {
    const rule: RecurringRule = {
      kind: "everyDays",
      days: 3,
      at: "08:00",
      anchorDay: at(2020, 1, 1),
    };
    // 2020-01-01 起每 3 天； asserted value 由同一算法自洽即可，
    // 关键是「相邻两次差 3 天」与「固定 08:00」两条不变式。
    const first = nextFireAt(rule, clock(11));
    expect(first).not.toBeNull();
    const second = nextFireAt(rule, first!);
    expect(second! - first!).toBe(3 * 86_400_000);
    expect(new Date(first!).getHours()).toBe(8);
    expect(new Date(first!).getMinutes()).toBe(0);
    expect(lastFireAtOrBefore(rule, clock(11))).not.toBeNull();
  });
});

describe("planRecurring · 错过一律跳过", () => {
  it("启动后第一次检查（prevCheckAt=0）只对齐，不补弹", () => {
    const plan = planRecurring([item()], clock(12), 0);
    expect(plan.fire).toBeNull();
    expect(plan.updates).toEqual([{ id: "r1", lastFiredAt: clock(12) }]);
  });

  it("正常轮询跨过相位点 → 触发一次并把 lastFiredAt 对齐", () => {
    const rule: RecurringRule = { kind: "weekly", weekdays: [], at: "09:00" };
    const plan = planRecurring(
      [item({ rule, lastFiredAt: at(2026, 11, 7, 9) })],
      clock(9, 0, 10),
      clock(8, 59, 40),
    );
    expect(plan.fire?.item.id).toBe("r1");
    expect(plan.fire?.fireAt).toBe(clock(9));
    expect(plan.updates).toEqual([{ id: "r1", lastFiredAt: clock(9) }]);
  });

  it("同一相位点不会被连弹两次（lastFiredAt 落盘幂等）", () => {
    const rule: RecurringRule = { kind: "weekly", weekdays: [], at: "09:00" };
    const first = planRecurring(
      [item({ rule, lastFiredAt: at(2026, 11, 7, 9) })],
      clock(9, 0, 10),
      clock(8, 59, 40),
    );
    const again = planRecurring(
      [item({ rule, lastFiredAt: first.updates[0].lastFiredAt })],
      clock(9, 0, 40),
      clock(9, 0, 10),
    );
    expect(again.fire).toBeNull();
    expect(again.updates).toEqual([]);
  });

  it("休眠 3 小时：4 个错过实例全部快进，一个都不弹，相位仍对齐", () => {
    const plan = planRecurring(
      [item({ lastFiredAt: clock(9) })],
      clock(12),
      clock(9, 0, 10),
      CATCH_UP_LIMIT_MS,
    );
    expect(plan.fire).toBeNull();
    // 9:00 / 9:45 / 10:30 / 11:15 / 12:00 → 最后一个是 12:00
    expect(plan.updates[0].lastFiredAt).toBe(clock(12));
    const next = nextFireAt(item().rule, plan.updates[0].lastFiredAt);
    expect(next).toBe(clock(12, 45));
  });

  it("禁用、结束日期已过、结束日期当天仍触发", () => {
    const rule: RecurringRule = { kind: "weekly", weekdays: [], at: "09:00" };
    const base = { rule, lastFiredAt: at(2026, 11, 7, 9) };
    expect(planRecurring([item({ ...base, enabled: false })], clock(9, 0, 10), clock(8, 59, 40)).fire).toBeNull();
    expect(
      planRecurring([item({ ...base, endAt: at(2026, 11, 7) })], clock(9, 0, 10), clock(8, 59, 40)).fire,
    ).toBeNull();
    expect(
      planRecurring([item({ ...base, endAt: at(2026, 11, 8) })], clock(9, 0, 10), clock(8, 59, 40)).fire,
    ).not.toBeNull();
  });

  it("同刻撞车只取一条，按相位时刻再按 id 稳定排序", () => {
    const rule: RecurringRule = { kind: "weekly", weekdays: [], at: "09:00" };
    const a = item({ id: "b", rule, lastFiredAt: at(2026, 11, 7, 9) });
    const b = item({ id: "a", rule, lastFiredAt: at(2026, 11, 7, 9) });
    const plan = planRecurring([a, b], clock(9, 0, 10), clock(8, 59, 40));
    expect(plan.fire?.item.id).toBe("a");
    expect(plan.updates).toHaveLength(2);
  });
});

describe("todayRecurringFires · 今日页卡片（v1.7.17）", () => {
  const daily = (atTime: string, over: Partial<RecurringItem> = {}) =>
    item({ rule: { kind: "weekly", weekdays: [], at: atTime }, ...over });

  it("只列今天之内还有相位点的规则，明天的那一条不出现", () => {
    const now = clock(7);
    const fires = todayRecurringFires(
      [
        daily("08:00", { id: "a", title: "早读" }),
        // 每周一 08:00，今天是周日 → 下一次在明天
        item({
          id: "b",
          rule: { kind: "weekly", weekdays: [1], at: "08:00" },
        }),
      ],
      now,
    );
    expect(fires.map((f) => f.item.id)).toEqual(["a"]);
    expect(fires[0].fireAt).toBe(clock(8));
    expect(fires[0].remainingToday).toBe(1);
  });

  it("停用 / 结束日期已过 / 下一次已越过今天，一律不列", () => {
    const now = clock(9);
    const fires = todayRecurringFires(
      [
        daily("08:00", { id: "past" }), // 今天 08:00 已过，下一次是明天
        daily("10:00", { id: "off", enabled: false }),
        daily("10:00", { id: "ended", endAt: at(2026, 11, 7) }),
        daily("10:00", { id: "ok" }),
      ],
      now,
    );
    expect(fires.map((f) => f.item.id)).toEqual(["ok"]);
  });

  it("按触发时刻升序；同刻按 id 稳定排序", () => {
    const now = clock(7);
    const fires = todayRecurringFires(
      [
        daily("10:00", { id: "late" }),
        daily("08:00", { id: "z" }),
        daily("08:00", { id: "a" }),
      ],
      now,
    );
    expect(fires.map((f) => f.item.id)).toEqual(["a", "z", "late"]);
  });

  it("间隔型给出今天剩余次数，且结束日期当天会被截断", () => {
    const now = clock(9, 0, 10);
    const rule: RecurringRule = {
      kind: "interval",
      minutes: 60,
      anchor: at(2026, 11, 8, 9),
    };
    const fires = todayRecurringFires(
      [item({ id: "hour", rule }), item({ id: "todayEnd", rule, endAt: SUN })],
      now,
    );
    expect(fires).toHaveLength(2);
    // 10:00 ~ 23:00 共 14 个相位点；endAt 是今天，同样覆盖到当天最后一刻
    expect(fires[0].fireAt).toBe(clock(10));
    expect(fires[0].remainingToday).toBe(14);
    expect(fires[1].remainingToday).toBe(14);
  });

  it("今天最后一分钟仍算今天；分钟级间隔有次数上限不会刷爆", () => {
    const now = clock(23, 58);
    const edge = todayRecurringFires([daily("23:59", { id: "edge" })], now);
    expect(edge).toHaveLength(1);
    expect(edge[0].fireAt).toBe(clock(23, 59));

    const minute: RecurringRule = {
      kind: "interval",
      minutes: 1,
      anchor: SUN,
    };
    const many = todayRecurringFires([item({ id: "minute", rule: minute })], clock(0, 0, 1));
    expect(many).toHaveLength(1);
    expect(many[0].remainingToday).toBeLessThanOrEqual(500);
    expect(many[0].remainingToday).toBeGreaterThan(100);
  });

  it("空列表返回空数组，不抛异常", () => {
    expect(todayRecurringFires([], clock(12))).toEqual([]);
  });
});

describe("跳过本次 / 下次触发 / 文案", () => {
  it("skipOnce 推进一个周期，与错过快进共用 nextFireAt", () => {
    const r = item({ lastFiredAt: clock(9) });
    const skipped = skipOnce(r, clock(9, 0, 10));
    expect(skipped).toBe(clock(9, 45));
    expect(nextFireAt(r.rule, skipped)).toBe(clock(10, 30));
  });

  it("upcomingFireAt 考虑启用状态与结束日期（结束当天仍会触发）", () => {
    const r = item({ lastFiredAt: clock(9) });
    expect(upcomingFireAt(r, clock(9, 0, 10))).toBe(clock(9, 45));
    expect(upcomingFireAt({ ...r, enabled: false }, clock(9))).toBeNull();
    // 间隔型在结束当天还有后续拍，所以仍有一个触发点
    expect(upcomingFireAt({ ...r, endAt: at(2026, 11, 8) }, clock(11))).toBe(clock(11, 15));
    // 日历型结束在 11/8：11/8 11:00 的下一次是 11/9 08:00，已越过结束日
    const daily: RecurringRule = { kind: "weekly", weekdays: [], at: "08:00" };
    expect(
      upcomingFireAt({ ...r, rule: daily, endAt: at(2026, 11, 8) }, clock(11)),
    ).toBeNull();
    expect(describeNext(clock(9, 45), clock(9))).toBe("45 分钟后");
    expect(describeNext(null, clock(9))).toBe("已结束或不启用");
  });

  it("日期输入与本地日互转不丢天", () => {
    expect(millisToDateInput(at(2026, 11, 8, 15, 30))).toBe("2026-11-08");
    expect(dateInputToMillis("2026-11-08")).toBe(startOfDay(at(2026, 11, 8)));
    expect(dateInputToMillis("")).toBeNull();
  });

  it("normalizeRule 把 NaN / 越界收敛成可用相位", () => {
    const bad = normalizeRule(
      { kind: "interval", minutes: Number.NaN, anchor: Number.NaN },
      SUN,
    );
    expect(bad.kind).toBe("interval");
    if (bad.kind === "interval") {
      expect(bad.minutes).toBe(1);
      expect(bad.anchor).toBe(SUN);
      expect(describeRule(bad)).toBe("每 1 分钟");
    }
    const interval = normalizeRule(
      { kind: "interval", minutes: 120, anchor: SUN },
      SUN,
    );
    expect(describeRule(interval)).toBe("每 2 小时");
    expect(ruleModeOf({ kind: "weekly", weekdays: [], at: "08:00" })).toBe("daily");
    expect(describeRule({ kind: "everyDays", days: 2, at: "08:00", anchorDay: SUN })).toBe(
      "每 2 天 08:00",
    );
  });
});

/**
 * v1.7.27：课表整周视图按「天」取相位点。
 *
 * 与 `todayRecurringFires` 的关键差别：那边问「今天还剩几次」，只返回未来的；
 * 这边问「这一天有哪些次」，**已过去的也要返回**（`past` 标出来），否则下午
 * 打开课表一片空白 —— 这正是本版要修的现象。
 */
describe("firesOnDay · 某一天的相位点（课表整周视图）", () => {
  it("每天 08:00：当天一个点，08:00 之前 past=false、之后 past=true", () => {
    const daily = item({
      rule: { kind: "weekly", weekdays: [], at: "08:00" },
      id: "daily",
    });
    const day = MON;

    const before = firesOnDay([daily], day, at(2026, 11, 9, 7, 0));
    expect(before).toHaveLength(1);
    expect(before[0].fireAt).toBe(at(2026, 11, 9, 8, 0));
    expect(before[0].dayCount).toBe(1);
    expect(before[0].past).toBe(false);

    const after = firesOnDay([daily], day, at(2026, 11, 9, 16, 46));
    expect(after[0].past).toBe(true);
  });

  it("规则不覆盖这一天时不出现（每周三 ≠ 周一）", () => {
    const wed = item({ rule: { kind: "weekly", weekdays: [3], at: "08:00" } });
    expect(firesOnDay([wed], MON, MON)).toHaveLength(0);
    expect(firesOnDay([wed], at(2026, 11, 11), at(2026, 11, 11))).toHaveLength(1);
  });

  it("间隔型只取当天首个点，次数写进 dayCount", () => {
    // anchor 是 11-08 09:00、每 45 分钟：900 分钟正好 20 步，
    // 所以 11-09 00:00 恰好落在相位上；当天从 00:00 起共 32 个点（00:00~23:15）
    const every45 = item({ id: "i45" });
    const fires = firesOnDay([every45], MON, MON);
    expect(fires).toHaveLength(1);
    expect(fires[0].fireAt).toBe(at(2026, 11, 9, 0, 0));
    expect(fires[0].dayCount).toBe(32);
  });

  it("停用的规则不出现", () => {
    const off = item({ enabled: false });
    expect(firesOnDay([off], MON, MON)).toHaveLength(0);
  });

  it("endAt 之后不再有相位点", () => {
    const ended = item({
      rule: { kind: "weekly", weekdays: [], at: "08:00" },
      endAt: at(2026, 11, 10),
    });
    // 结束日当天仍然触发，次日就没有了
    expect(firesOnDay([ended], MON, MON)).toHaveLength(1);
    expect(firesOnDay([ended], at(2026, 11, 11), at(2026, 11, 11))).toHaveLength(0);
  });

  it("同刻按 id 稳定排序", () => {
    const a = item({ id: "bbb", rule: { kind: "weekly", weekdays: [], at: "08:00" } });
    const b = item({ id: "aaa", rule: { kind: "weekly", weekdays: [], at: "08:00" } });
    const fires = firesOnDay([a, b], MON, MON);
    expect(fires.map((f) => f.item.id)).toEqual(["aaa", "bbb"]);
  });

  it("与 todayRecurringFires 互补：已过的一天这边有、那边没有", () => {
    const daily = item({ rule: { kind: "weekly", weekdays: [], at: "08:00" } });
    const now = at(2026, 11, 9, 16, 46);
    expect(todayRecurringFires([daily], now)).toHaveLength(0);
    expect(firesOnDay([daily], startOfDay(now), now)).toHaveLength(1);
  });
});

describe("firesOnDay · past 只在「今天」判定", () => {
  it("本周已过去的日期不淡化（前天 08:00 不算过期任务）", () => {
    const daily = item({ rule: { kind: "weekly", weekdays: [], at: "08:00" } });
    const now = at(2026, 11, 15, 16, 46); // 周日 16:46
    const friday = firesOnDay([daily], at(2026, 11, 13), now);
    expect(friday).toHaveLength(1);
    expect(friday[0].past).toBe(false);

    const today = firesOnDay([daily], startOfDay(now), now);
    expect(today[0].past).toBe(true);
  });

  it("同一周内七列只有今天可能为 past", () => {
    const daily = item({ rule: { kind: "weekly", weekdays: [], at: "08:00" } });
    const now = at(2026, 11, 15, 16, 46);
    const monday = at(2026, 11, 9);
    const all = Array.from({ length: 7 }, (_, i) =>
      firesOnDay([daily], startOfDay(monday + i * 86_400_000), now),
    );
    expect(all.map((f) => f[0].past)).toEqual([
      false, false, false, false, false, false, true,
    ]);
  });
});
