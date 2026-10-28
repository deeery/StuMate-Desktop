import { describe, expect, it } from "vitest";
import {
  autoScrollVelocity,
  clampOffset,
  deadlineCountdown,
  deadlineTimeLabel,
  moveInFullOrder,
  resolveDropIndex,
  shiftFor,
  slotTops,
  typeLabel,
} from "../notes";
import { EMPTY_NOTE, type NoteEntity } from "../types";

const MIN = 60_000;
const HOUR = 60 * MIN;
const DAY = 24 * HOUR;

function note(id: number, patch: Partial<NoteEntity> = {}): NoteEntity {
  return { ...EMPTY_NOTE, id, title: `便签${id}`, ...patch };
}

describe("Deadline 倒计时", () => {
  const now = 1_000_000_000_000;

  it("已过期", () => {
    expect(deadlineCountdown(now - MIN, now)).toBe("已过期");
    expect(deadlineCountdown(now - 2 * DAY, now)).toBe("已过期 2 天");
  });

  it("即将到期 / N 分钟后", () => {
    expect(deadlineCountdown(now + 30_000, now)).toBe("即将到期");
    expect(deadlineCountdown(now + 25 * MIN, now)).toBe("25 分钟后");
  });

  it("N 小时 M 分后，M 为 0 时省略", () => {
    expect(deadlineCountdown(now + 2 * HOUR, now)).toBe("2 小时后");
    expect(deadlineCountdown(now + 2 * HOUR + 15 * MIN, now)).toBe("2 小时 15 分后");
  });

  it("N 天后 / N 个月后", () => {
    expect(deadlineCountdown(now + 3 * DAY, now)).toBe("3 天后");
    expect(deadlineCountdown(now + 65 * DAY, now)).toBe("2 个月后");
  });

  it("截止时刻显示：当年省略年份", () => {
    const sameYear = new Date(2026, 9, 28, 18, 5).getTime();
    const reference = new Date(2026, 9, 1).getTime();
    expect(deadlineTimeLabel(sameYear, reference)).toBe("10/28 18:05");
  });
});

describe("分类名", () => {
  it("无分类返回空串", () => {
    expect(typeLabel(note(1, { typeIndex: 0 }))).toBe("");
  });

  it("内置分类直接用标签", () => {
    expect(typeLabel(note(1, { typeIndex: 1 }))).toBe("工作");
    expect(typeLabel(note(1, { typeIndex: 4 }))).toBe("Deadline");
  });

  it("自定义分类走用户填的名字，空时退回占位", () => {
    expect(typeLabel(note(1, { typeIndex: 5, customLabel: "社团" }))).toBe("社团");
    expect(typeLabel(note(1, { typeIndex: 5, customLabel: "  " }))).toBe("自定义");
  });

  it("越界的 typeIndex 收敛到无分类，不会崩", () => {
    expect(typeLabel(note(1, { typeIndex: 99 }))).toBe("Deadline 自定义");
    expect(typeLabel(note(1, { typeIndex: -5 }))).toBe("");
  });
});

describe("拖动排序几何", () => {
  it("slotTops 逐行累加", () => {
    expect(slotTops([40, 40, 40])).toEqual([0, 40, 80]);
    expect(slotTops([38, 41, 41])).toEqual([0, 38, 79]);
  });

  it("槽位顶边随行高不同而不同（不再拿自己的行高当步长）", () => {
    const tops = slotTops([38, 41, 41]);
    expect(tops[2]).toBe(79);
  });

  it("位移不足半格时留在原位", () => {
    const tops = slotTops([40, 40, 40]);
    expect(resolveDropIndex(tops, 0, 19)).toBe(0);
    expect(resolveDropIndex(tops, 0, 21)).toBe(1);
  });

  it("向上拖也按最近的槽位吸附", () => {
    const tops = slotTops([40, 40, 40]);
    expect(resolveDropIndex(tops, 2, -19)).toBe(2);
    expect(resolveDropIndex(tops, 2, -21)).toBe(1);
  });

  it("平局（正好落在交界线上）优先留在原位", () => {
    const tops = slotTops([40, 40, 40]);
    // 20 与 0 / 40 都差 20，按「离原位更近」应留在 0
    expect(resolveDropIndex(tops, 0, 20)).toBe(0);
  });

  it("clampOffset 不允许拖出列表首尾", () => {
    const tops = slotTops([40, 40, 40]);
    expect(clampOffset(-999, tops, 1)).toBe(-40);
    expect(clampOffset(999, tops, 1)).toBe(40);
  });

  it("shiftFor 让位量恒等于被拖行高度", () => {
    expect(shiftFor(1, 0, 2, 41)).toBe(-41);
    expect(shiftFor(1, 2, 0, 41)).toBe(41);
    expect(shiftFor(0, 0, 2, 41)).toBe(0);
    expect(shiftFor(5, 0, 2, 41)).toBe(0);
  });

  it("自动滚动速度随进入热区的深度增长", () => {
    expect(autoScrollVelocity(100, 140, 600, 48, 900)).toBe(0);
    expect(autoScrollVelocity(10, 50, 600, 48, 900)).toBeLessThan(0);
    expect(autoScrollVelocity(560, 600, 600, 48, 900)).toBeGreaterThan(0);
    expect(autoScrollVelocity(0, 40, 600, 48, 900)).toBe(-900);
  });
});

describe("筛选态下的重排（拖 A 动 B 的回归用例）", () => {
  const all = [note(1), note(2), note(3), note(4), note(5)];

  it("无筛选时退化成普通移动", () => {
    const next = moveInFullOrder(all, all, all[0], 2);
    expect(next.map((n) => n.id)).toEqual([2, 3, 1, 4, 5]);
  });

  it("向上拖", () => {
    const next = moveInFullOrder(all, all, all[3], 0);
    expect(next.map((n) => n.id)).toEqual([4, 1, 2, 3, 5]);
  });

  it("筛选后拖可见行，隐藏行保持相对位置", () => {
    const visible = [all[0], all[2], all[4]]; // 1,3,5
    const next = moveInFullOrder(all, visible, all[0], 2);
    // 拖到可见列表的最后一格 = 变成可见列表的最后一个，隐藏的 2、4 原地不动
    expect(next.map((n) => n.id)).toEqual([2, 3, 4, 5, 1]);
  });

  it("拖到可见列表末尾 = 跟在最后一个可见行后面", () => {
    const visible = [all[0], all[2]];
    const next = moveInFullOrder(all, visible, all[0], 5);
    expect(next.map((n) => n.id)).toEqual([2, 3, 1, 4, 5]);
  });

  it("元素不在全量里时原样返回", () => {
    const ghost = note(99);
    expect(moveInFullOrder(all, [ghost], ghost, 0)).toBe(all);
  });
});
