import { describe, expect, it } from "vitest";
import {
  dateOf,
  dayNameOf,
  dayNameOfDate,
  greetingOf,
  isIdle,
  isValidDate,
  monthDayOf,
  occursInRange,
  oneOffDateOf,
  ongoingAt,
  remaining,
  todayClasses,
} from "../today";
import { EMPTY_CLASS, type ClassEntity } from "../types";

function cls(id: number, patch: Partial<ClassEntity> = {}): ClassEntity {
  return {
    ...EMPTY_CLASS,
    id,
    title: `课${id}`,
    dayOfWeek: "Wednesday",
    startTime: "09:00",
    endTime: "10:40",
    ...patch,
  };
}

// 2026-10-28 是周三
const wednesday10 = new Date(2026, 9, 28, 10, 0).getTime();

describe("今天", () => {
  it("dayNameOf 用 Monday…Sunday，与库里一致", () => {
    expect(dayNameOf(wednesday10)).toBe("Wednesday");
    expect(dayNameOf(new Date(2026, 9, 25, 12).getTime())).toBe("Sunday");
  });

  it("dateOf 输出 yyyy-MM-dd（本地时区）", () => {
    expect(dateOf(wednesday10)).toBe("2026-10-28");
  });

  // 问候语的分档边界是这一版最容易写错的一处：整点必须归**后**一档，
  // 否则 09:00 还会说「早上好」、23:00 还会说「晚上好」。
  it("greetingOf 按作息分档，整点归后一档", () => {
    const at = (h: number) => new Date(2026, 9, 28, h, 0).getTime();
    expect(greetingOf(at(4))).toBe("夜深了。");
    expect(greetingOf(at(5))).toBe("早上好。");
    expect(greetingOf(at(8))).toBe("早上好。");
    expect(greetingOf(at(9))).toBe("午安。");
    expect(greetingOf(at(11))).toBe("午安。");
    expect(greetingOf(at(12))).toBe("下午好。");
    expect(greetingOf(at(17))).toBe("下午好。");
    expect(greetingOf(at(18))).toBe("晚上好。");
    expect(greetingOf(at(22))).toBe("晚上好。");
    expect(greetingOf(at(23))).toBe("夜深了。");
  });

  it("monthDayOf 月日不补零，与顶栏副标题一致", () => {
    expect(monthDayOf(wednesday10)).toBe("10 月 28 日");
    expect(monthDayOf(new Date(2026, 0, 5, 9).getTime())).toBe("1 月 5 日");
  });

  it("只取今天的课，并按开始时间排序", () => {
    const list = todayClasses(
      [
        cls(1, { startTime: "14:00", endTime: "15:40" }),
        cls(2, { startTime: "08:00", endTime: "09:40" }),
        cls(3, { dayOfWeek: "Thursday" }),
      ],
      null,
      wednesday10,
    );
    expect(list.map((c) => c.entity.id)).toEqual([2, 1]);
  });

  it("周次不匹配的课不出现", () => {
    const list = todayClasses([cls(1, { weeks: "1-4周" })], 8, wednesday10);
    expect(list).toHaveLength(0);
  });

  it("周次写不清时不过滤（不因为备注写得怪就隐藏整门课）", () => {
    const list = todayClasses([cls(1, { weeks: "待定" })], 8, wednesday10);
    expect(list).toHaveLength(1);
  });

  it("临时提醒只在那一天出现，且不受周次影响", () => {
    const list = todayClasses([cls(1, { date: "2026-10-28", weeks: "1-4周" })], 8, wednesday10);
    expect(list).toHaveLength(1);
    const other = todayClasses([cls(1, { date: "2026-10-29" })], 8, wednesday10);
    expect(other).toHaveLength(0);
  });

  it("跨零点的课算到第二天", () => {
    const list = todayClasses([cls(1, { startTime: "23:00", endTime: "00:30" })], null, wednesday10);
    expect(list[0].endMillis - list[0].startMillis).toBe(90 * 60_000);
  });

  it("时间解析不出来的课被跳过", () => {
    expect(todayClasses([cls(1, { startTime: "上午" })], null, wednesday10)).toHaveLength(0);
  });

  it("remaining 只留还没结束的", () => {
    const list = todayClasses(
      [cls(1, { startTime: "08:00", endTime: "09:00" }), cls(2, { startTime: "11:00", endTime: "12:00" })],
      null,
      wednesday10,
    );
    expect(remaining(list, wednesday10).map((c) => c.entity.id)).toEqual([2]);
  });

  it("正在上课时不算空闲", () => {
    const list = todayClasses([cls(1, { startTime: "09:30", endTime: "10:30" })], null, wednesday10);
    expect(ongoingAt(list[0], wednesday10)).toBe(true);
    expect(isIdle(list, wednesday10, 30)).toBe(false);
  });

  it("离下一节还有 40 分钟、预警 30 分钟时算空闲", () => {
    const list = todayClasses([cls(1, { startTime: "10:40", endTime: "12:00" })], null, wednesday10);
    expect(isIdle(list, wednesday10, 30)).toBe(true);
  });

  it("正好等于预警窗口时不算空闲（边界取严格大于）", () => {
    const list = todayClasses([cls(1, { startTime: "10:30", endTime: "12:00" })], null, wednesday10);
    expect(isIdle(list, wednesday10, 30)).toBe(false);
  });

  it("今天的课上完了不算空闲", () => {
    const list = todayClasses([cls(1, { startTime: "08:00", endTime: "09:00" })], null, wednesday10);
    expect(isIdle(list, wednesday10, 30)).toBe(false);
  });

  it("日期校验挡住 2026-02-31 这种自动进位的假日期", () => {
    expect(isValidDate("2026-02-31")).toBe(false);
    expect(isValidDate("2026-02-28")).toBe(true);
    expect(isValidDate("2026/02/28")).toBe(false);
  });

  it("dayNameOfDate 与日期一致", () => {
    expect(dayNameOfDate("2026-10-28")).toBe("Wednesday");
    expect(dayNameOfDate("不是日期")).toBeNull();
  });

  it("occursInRange 对长期课程恒为 true", () => {
    const from = new Date(2026, 9, 26).getTime();
    const to = from + 7 * 24 * 3600 * 1000;
    expect(occursInRange(cls(1), from, to)).toBe(true);
    expect(oneOffDateOf(cls(1))).toBeNull();
  });
});
