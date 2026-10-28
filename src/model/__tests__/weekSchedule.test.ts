import { describe, expect, it } from "vitest";
import {
  currentWeek,
  mondayOf,
  parseWeeks,
  weekContains,
  weekNumber,
  weekStart,
} from "../weekSchedule";

describe("周次解析", () => {
  it("解析 1-16周", () => {
    const set = parseWeeks("1-16周");
    expect(set?.size).toBe(16);
    expect(set?.has(1)).toBe(true);
    expect(set?.has(16)).toBe(true);
    expect(set?.has(17)).toBe(false);
  });

  it("解析 第6周", () => {
    expect([...(parseWeeks("第6周") ?? [])]).toEqual([6]);
  });

  it("解析 1-8周,10-12周", () => {
    const set = parseWeeks("1-8周,10-12周");
    expect(set?.size).toBe(11);
    expect(set?.has(9)).toBe(false);
    expect(set?.has(10)).toBe(true);
  });

  it("解析 1,3,5周", () => {
    expect([...(parseWeeks("1,3,5周") ?? [])]).toEqual([1, 3, 5]);
  });

  it("单双周过滤", () => {
    expect([...(parseWeeks("1-6周(单)") ?? [])]).toEqual([1, 3, 5]);
    expect([...(parseWeeks("1-6周(双)") ?? [])]).toEqual([2, 4, 6]);
  });

  it("区间里的数字不会被当成单周重复统计", () => {
    // 1-16 里的 1 和 16 已经被区间吃掉了
    const set = parseWeeks("1-16周");
    expect([...(set ?? [])].length).toBe(16);
  });

  it("写不出周次时返回 null（不确定，不限制显示）", () => {
    expect(parseWeeks("")).toBeNull();
    expect(parseWeeks("每周")).toBeNull();
    expect(parseWeeks("待定")).toBeNull();
  });

  it("超出上限的周次被忽略", () => {
    expect(parseWeeks("99周")).toBeNull();
    // 1-99 的右端点超上限，整段不采信；剩下的文本里没有可用数字 → 视为「不确定」
    expect(parseWeeks("1-99周")).toBeNull();
  });

  it("周次写不清时 contains 一律为 true", () => {
    expect(weekContains("", 5)).toBe(true);
    expect(weekContains("随便写的", 5)).toBe(true);
    expect(weekContains("1-4周", 5)).toBe(false);
  });
});

describe("周次换算", () => {
  // 2026-10-28 是周三
  const wednesday = new Date(2026, 9, 28, 15, 30).getTime();
  const monday = new Date(2026, 9, 26, 0, 0).getTime();

  it("mondayOf 把任意时刻归一到本周一 00:00", () => {
    expect(mondayOf(wednesday)).toBe(monday);
  });

  it("周日归一到它前面那个周一（不是下周一）", () => {
    const sunday = new Date(2026, 10, 1, 23, 0).getTime();
    expect(mondayOf(sunday)).toBe(monday);
  });

  it("weekNumber 以第 1 周周一为基准", () => {
    expect(weekNumber(monday, wednesday)).toBe(1);
    expect(weekNumber(monday, monday + 7 * 24 * 3600 * 1000)).toBe(2);
    expect(weekNumber(monday, monday - 7 * 24 * 3600 * 1000)).toBe(0);
  });

  it("weekStart 回到该周周一", () => {
    expect(weekStart(monday, 3)).toBe(monday + 2 * 7 * 24 * 3600 * 1000);
  });

  it("未校准时 currentWeek 返回 null", () => {
    expect(currentWeek(0, wednesday)).toBeNull();
    expect(currentWeek(monday, wednesday)).toBe(1);
  });
});
