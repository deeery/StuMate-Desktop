import { describe, expect, it } from "vitest";
import { decideReminder, overlayView, pickFocus, reminderModeOf, type ReminderAlert } from "../reminder";
import { EMPTY_CLASS, type ClassEntity } from "../types";

function cls(id: number, patch: Partial<ClassEntity> = {}): ClassEntity {
  return {
    ...EMPTY_CLASS,
    id,
    title: `课${id}`,
    dayOfWeek: "Wednesday",
    startTime: "09:00",
    endTime: "10:40",
    room: `教室${id}`,
    ...patch,
  };
}

/** 2026-10-28 是周三 */
const at = (h: number, m = 0) => new Date(2026, 9, 28, h, m).getTime();

describe("提醒：挑哪节课", () => {
  it("有课正在上时，优先取正在上的那节", () => {
    // 08:00-09:40 已结束；09:00-10:40 正在上；11:00 的还没到
    const classes = [
      cls(1, { startTime: "08:00", endTime: "08:45" }),
      cls(2, { startTime: "09:00", endTime: "10:40" }),
      cls(3, { startTime: "13:00", endTime: "14:40" }),
    ];
    const now = at(10, 0);
    const decision = decideReminder({ classes, currentWeek: null, now, advanceMinutes: 30 });
    expect(decision.alert?.classId).toBe(2);
    expect(decision.alert?.ongoing).toBe(true);
    expect(decision.status.title).toBe("正在上课：课2");
  });

  it("重叠课取「开始最晚」的那节（刚打铃的最该提醒）", () => {
    const classes = [
      cls(1, { startTime: "09:00", endTime: "11:00" }),
      cls(2, { startTime: "09:30", endTime: "10:30" }),
    ];
    const now = at(9, 45);
    const decision = decideReminder({ classes, currentWeek: null, now, advanceMinutes: 30 });
    expect(decision.alert?.classId).toBe(2);
  });

  it("没课在上的，取提前提醒窗口内「开始最早」的那节", () => {
    const classes = [
      cls(1, { startTime: "11:00", endTime: "12:00" }),
      cls(2, { startTime: "10:20", endTime: "11:00" }),
    ];
    const now = at(9, 55); // 提前 30 分钟 → 10:20 那节刚好进窗，11:00 那节还没
    const decision = decideReminder({ classes, currentWeek: null, now, advanceMinutes: 30 });
    expect(decision.alert?.classId).toBe(2);
    expect(decision.alert?.ongoing).toBe(false);
  });

  it("窗口边界：正好剩 N 分钟算进窗，多 1 毫秒就不算", () => {
    const classes = [cls(1, { startTime: "10:00", endTime: "11:00" })];
    const start = at(10, 0);
    // 提前 30 分钟 → 09:30:00 整在窗内（startsWithin 取 >=）
    expect(pickFocus([{ entity: classes[0], startMillis: start, endMillis: at(11, 0) }], start - 30 * 60_000, 30)).not.toBeNull();
    expect(pickFocus([{ entity: classes[0], startMillis: start, endMillis: at(11, 0) }], start - 30 * 60_000 - 1, 30)).toBeNull();
  });

  it("今天没课：不提醒，状态是「今日无课」", () => {
    const decision = decideReminder({ classes: [], currentWeek: null, now: at(10), advanceMinutes: 30 });
    expect(decision.alert).toBeNull();
    expect(decision.status.detail).toBe("运行中 — 今日无课");
  });

  it("课都上完了：状态提示全部结束，且不提醒", () => {
    const classes = [cls(1, { startTime: "08:00", endTime: "09:00" })];
    const decision = decideReminder({ classes, currentWeek: null, now: at(15), advanceMinutes: 30 });
    expect(decision.alert).toBeNull();
    expect(decision.status.title).toBe("今日课程已全部结束！");
    expect(decision.status.detail).toBe("今天共 1 节课");
  });

  it("还有课没上：状态显示剩余节数与下一节", () => {
    const classes = [
      cls(1, { startTime: "08:00", endTime: "09:00" }),
      cls(2, { startTime: "16:00", endTime: "17:00" }),
    ];
    const decision = decideReminder({ classes, currentWeek: null, now: at(12), advanceMinutes: 30 });
    expect(decision.alert).toBeNull();
    expect(decision.status.title).toBe("StuMate Lite — 今日剩余 1 节课");
    expect(decision.status.detail).toContain("课2");
  });

  it("周次不匹配的课不参与判定", () => {
    // 只在第 1 周上，而当前是第 5 周
    const classes = [cls(1, { weeks: "1", startTime: "09:00", endTime: "10:00" })];
    const decision = decideReminder({ classes, currentWeek: 5, now: at(9, 30), advanceMinutes: 30 });
    expect(decision.alert).toBeNull();
  });

  it("临时提醒只在那一天生效", () => {
    const classes = [
      cls(1, { date: "2026-10-28", dayOfWeek: "Sunday", startTime: "09:00", endTime: "10:00" }),
    ];
    const onDay = decideReminder({ classes, currentWeek: null, now: at(9, 30), advanceMinutes: 30 });
    expect(onDay.alert?.classId).toBe(1);
    const nextDay = decideReminder({
      classes,
      currentWeek: null,
      now: new Date(2026, 9, 29, 9, 30).getTime(),
      advanceMinutes: 30,
    });
    expect(nextDay.alert).toBeNull();
  });
});

describe("提醒卡：倒计时与进度", () => {
  const alert = (patch: Partial<ReminderAlert> = {}): ReminderAlert => ({
    classId: 1,
    title: "大学物理",
    room: "教三 201",
    startMillis: at(10, 0),
    endMillis: at(11, 40),
    ongoing: false,
    ...patch,
  });

  it("还没开始：向上取整显示「还有 N 分钟」", () => {
    // 剩 30 秒也要显示 1 分钟 —— 显示 0 分钟会让人以为已经上课了
    const view = overlayView(alert(), at(10, 0) - 30_000);
    expect(view.phase).toBe("即将上课");
    expect(view.countdown).toBe("还有 1 分钟");
    expect(view.progress).toBe(0);
  });

  it("正在上课：显示「已上课 N 分钟」并按比例给进度", () => {
    const a = alert({ ongoing: true });
    const view = overlayView(a, at(10, 50)); // 100 分钟里过了 50 分钟
    expect(view.phase).toBe("正在上课");
    expect(view.countdown).toBe("已上课 50 分钟");
    expect(view.progress).toBeCloseTo(0.5, 5);
  });

  it("已下课：不显示倒计时（卡片可能还停在屏幕上）", () => {
    const view = overlayView(alert(), at(12, 30));
    expect(view.countdown).toBe("");
  });

  it("区间退化（end <= start）不除零", () => {
    const broken = alert({ startMillis: at(10, 0), endMillis: at(10, 0) });
    const view = overlayView(broken, at(10, 0));
    expect(view.countdown).toBe("");
    expect(Number.isFinite(view.progress)).toBe(true);
    expect(view.progress).toBe(0);
  });

  it("进度不超过 1", () => {
    const view = overlayView(alert({ ongoing: true }), at(11, 40));
    expect(view.progress).toBeLessThanOrEqual(1);
  });
});


describe("提醒方式：三档解析（v1.7.28）", () => {
  it("显式三档原样返回", () => {
    expect(reminderModeOf({ reminderMode: 0 })).toBe(0);
    expect(reminderModeOf({ reminderMode: 1 })).toBe(1);
    expect(reminderModeOf({ reminderMode: 2 })).toBe(2);
  });

  it("手改设置文件写成字符串也认", () => {
    expect(reminderModeOf({ reminderMode: "1" })).toBe(1);
    expect(reminderModeOf({ reminderMode: "2" })).toBe(2);
  });

  it("旧设置没有 reminderMode 时按 showPopup 迁移", () => {
    // 升级前的语义：开关打开 = 全局浮窗，关掉 = 不弹
    expect(reminderModeOf({ showPopup: true })).toBe(0);
    expect(reminderModeOf({ showPopup: false })).toBe(2);
  });

  it("越界 / 乱写 / 缺字段都落到一个合法档位，不会静默不提醒", () => {
    expect(reminderModeOf({ reminderMode: 7 })).toBe(0);
    expect(reminderModeOf({ reminderMode: null })).toBe(0);
    expect(reminderModeOf({ reminderMode: "窗口内" })).toBe(0);
    expect(reminderModeOf({})).toBe(0);
    expect(reminderModeOf(null)).toBe(0);
    expect(reminderModeOf(undefined)).toBe(0);
  });

  it("显式档位优先于旧的 showPopup", () => {
    expect(reminderModeOf({ reminderMode: 1, showPopup: false })).toBe(1);
  });
});