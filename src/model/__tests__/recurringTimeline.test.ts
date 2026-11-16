import { describe, expect, it } from "vitest";
import type { DayRecurringFire, RecurringItem, TodayRecurringFire } from "../recurring";
import {
  DAY_MINUTES,
  clockOfDay,
  extendSpanForFires,
  layoutTimelineFires,
  mergeWeekFires,
  minuteOfDay,
} from "../recurringTimeline";

/** 固定用 2026-11-13 本地时刻，避免测试依赖运行当天 */
function at(id: string, hh: number, mm = 0): TodayRecurringFire {
  const fireAt = new Date(2026, 10, 13, hh, mm, 0, 0).getTime();
  const item: RecurringItem = {
    id,
    title: id,
    body: "",
    enabled: true,
    rule: { kind: "weekly", weekdays: [5], at: "08:00" },
    endAt: null,
    lastFiredAt: 0,
    createdAt: fireAt,
  };
  return { item, fireAt, remainingToday: 1 };
}

const BASE = { startMinute: 8 * 60, endMinute: 18 * 60 };

describe("本地时刻换算", () => {
  it("09:35 → 575 分钟，格式化补零", () => {
    const ms = at("a", 9, 35).fireAt;
    expect(minuteOfDay(ms)).toBe(575);
    expect(clockOfDay(ms)).toBe("09:35");
  });

  it("00:00 与 23:59 落在两端", () => {
    expect(minuteOfDay(at("a", 0, 0).fireAt)).toBe(0);
    expect(minuteOfDay(at("a", 23, 59).fireAt)).toBe(DAY_MINUTES - 1);
  });
});

describe("extendSpanForFires · 把今天的相位点纳入课表纵轴", () => {
  it("没有触发点 / 今天不在当前周，一律原样返回", () => {
    expect(extendSpanForFires(BASE, [], true)).toEqual(BASE);
    expect(extendSpanForFires(BASE, [at("a", 21)], false)).toEqual(BASE);
  });

  it("相位点已在跨度内时不拉长时间轴", () => {
    expect(extendSpanForFires(BASE, [at("a", 10), at("b", 15)], true)).toEqual(BASE);
  });

  it("晚于下课时向上扩到整小时（21:00 → 22:00）", () => {
    expect(extendSpanForFires(BASE, [at("a", 21)], true)).toEqual({
      startMinute: 8 * 60,
      endMinute: 22 * 60,
    });
  });

  it("早于第一节课时向下扩到整小时（06:00 → 05:00）", () => {
    expect(extendSpanForFires(BASE, [at("a", 6)], true)).toEqual({
      startMinute: 5 * 60,
      endMinute: 18 * 60,
    });
  });

  it("23:50 的提醒不会把纵轴推过 24:00", () => {
    expect(extendSpanForFires(BASE, [at("a", 23, 50)], true).endMinute).toBe(DAY_MINUTES);
  });
});

describe("layoutTimelineFires · 24h 竖轴排点", () => {
  it("空数组返回空", () => {
    expect(layoutTimelineFires([], 480)).toEqual([]);
  });

  it("按触发时刻升序，同刻按 id 稳定排序", () => {
    const got = layoutTimelineFires([at("b", 9), at("z", 8), at("a", 8)], 480);
    expect(got.map((g) => g.fire.item.id)).toEqual(["a", "z", "b"]);
  });

  it("09:00 与 09:01 会被最小间距推开，并标记 shifted", () => {
    const got = layoutTimelineFires([at("a", 9), at("b", 9, 1)], 480, 26);
    expect(got[0].shifted).toBe(false);
    expect(got[1].shifted).toBe(true);
    expect(got[1].top - got[0].top).toBeCloseTo(26, 5);
  });

  it("点再密也不会被推出容器", () => {
    const many = Array.from({ length: 40 }, (_, i) => at(`r${i}`, 9));
    const got = layoutTimelineFires(many, 480, 26);
    expect(got.every((g) => g.top <= 476)).toBe(true);
    expect(got.every((g, i) => i === 0 || g.top >= got[i - 1].top)).toBe(true);
  });

  it("高度按全天 24h 线性映射", () => {
    const got = layoutTimelineFires([at("a", 12)], 480, 26);
    expect(got[0].minute).toBe(720);
    expect(got[0].top).toBeCloseTo(240, 5);
  });
});

/**
 * 课表整周视图的「连续日期共用一条线」：七列各画一个三角时，
 * 列边界会把同一件事切成七段；合并后一段只有三角 + 一条穿过列边界的线。
 */
describe("mergeWeekFires · 连续日期合成一段", () => {
  /** 构造某一天的相位点：day 0..6（周一..周日），hh/mm 为当天时刻 */
  function dayFire(id: string, day: number, hh: number, mm = 0, dayCount = 1): DayRecurringFire {
    const fireAt = new Date(2026, 10, 9 + day, hh, mm).getTime();
    const item: RecurringItem = {
      id,
      title: id,
      body: "",
      enabled: true,
      rule: { kind: "weekly", weekdays: [], at: `${String(hh).padStart(2, "0")}:${String(mm).padStart(2, "0")}` },
      endAt: null,
      lastFiredAt: 0,
      createdAt: fireAt,
    };
    return { item, fireAt, dayCount, past: false };
  }

  const empty = () => [[], [], [], [], [], [], []] as DayRecurringFire[][];

  it("每天都触发 → 一整段 0..6，只有一个三角", () => {
    const byDay = empty();
    for (let d = 0; d < 7; d += 1) byDay[d].push(dayFire("a", d, 8));
    const runs = mergeWeekFires(byDay);
    expect(runs).toHaveLength(1);
    expect([runs[0].startDay, runs[0].endDay]).toEqual([0, 6]);
  });

  it("周一/周二连续 + 周四单独 → 两段", () => {
    const byDay = empty();
    byDay[0].push(dayFire("a", 0, 8));
    byDay[1].push(dayFire("a", 1, 8));
    byDay[3].push(dayFire("a", 3, 8));
    const runs = mergeWeekFires(byDay);
    expect(runs.map((r) => [r.startDay, r.endDay])).toEqual([
      [0, 1],
      [3, 3],
    ]);
  });

  it("日期连续但时刻不同（间隔型首点漂移）不合并", () => {
    const byDay = empty();
    byDay[0].push(dayFire("a", 0, 8));
    byDay[1].push(dayFire("a", 1, 9));
    expect(mergeWeekFires(byDay).map((r) => [r.startDay, r.endDay])).toEqual([
      [0, 0],
      [1, 1],
    ]);
  });

  it("两条规则交错时按起始日排序，互不粘连", () => {
    const byDay = empty();
    byDay[0].push(dayFire("b", 0, 10));
    byDay[1].push(dayFire("a", 1, 8));
    byDay[2].push(dayFire("b", 2, 10));
    const runs = mergeWeekFires(byDay);
    expect(runs.map((r) => `${r.item.id}:${r.startDay}-${r.endDay}`)).toEqual([
      "b:0-0",
      "a:1-1",
      "b:2-2",
    ]);
  });

  it("空周返回空数组", () => {
    expect(mergeWeekFires(empty())).toEqual([]);
  });
});
