import { describe, expect, it } from "vitest";
import {
  hoursPerRow,
  labelOf,
  layout,
  mappingOf,
  minutesOf,
  roundMarks,
  rowCount,
  spanOf,
  zebraBands,
  type Span,
} from "../timeAxis";
import { EMPTY_CLASS, type ClassEntity } from "../types";

function cls(id: number, start: string, end: string, day = "Monday"): ClassEntity {
  return { ...EMPTY_CLASS, id, title: `课${id}`, startTime: start, endTime: end, dayOfWeek: day };
}

describe("时间轴", () => {
  const span: Span = { startMinute: 8 * 60, endMinute: 18 * 60 };

  it("spanOf 取最早开始与最晚结束", () => {
    expect(spanOf([cls(1, "09:00", "10:40"), cls(2, "08:00", "09:40")])).toEqual({
      startMinute: 8 * 60,
      endMinute: 10 * 60 + 40,
    });
  });

  it("没有可解析时间的课返回 null", () => {
    expect(spanOf([cls(1, "上午", "中午")])).toBeNull();
    expect(spanOf([])).toBeNull();
  });

  it("结束早于开始的脏数据按开始 +1 分钟算，不会画成 0 高度", () => {
    const s = spanOf([cls(1, "10:00", "09:00")]);
    expect(s).toEqual({ startMinute: 600, endMinute: 601 });
  });

  it("线性映射：比例等于时间占比", () => {
    const m = mappingOf(span, null);
    expect(m.fractionOf(8 * 60)).toBeCloseTo(0, 5);
    expect(m.fractionOf(13 * 60)).toBeCloseTo(0.5, 5);
    expect(m.fractionOf(18 * 60)).toBeCloseTo(1, 5);
  });

  it("放大某段后总高度不变，且该段占比变大", () => {
    const m = mappingOf(span, { startMinute: 9 * 60, endMinute: 10 * 60 });
    const base = 1 / 10;
    const zoomed = m.fractionOf(10 * 60) - m.fractionOf(9 * 60);
    expect(zoomed).toBeGreaterThan(base);
    expect(m.fractionOf(18 * 60)).toBeCloseTo(1, 5);
    expect(m.fractionOf(8 * 60)).toBeCloseTo(0, 5);
  });

  it("progress=0 时精确退化成线性", () => {
    const m = mappingOf(span, { startMinute: 9 * 60, endMinute: 10 * 60 }, 0);
    expect(m.fractionOf(9 * 60)).toBeCloseTo(0.1, 5);
    expect(m.fractionOf(12 * 60)).toBeCloseTo(0.4, 5);
  });

  it("focus 超出 span 时退化成线性", () => {
    const m = mappingOf(span, { startMinute: 7 * 60, endMinute: 9 * 60 });
    expect(m.fractionOf(13 * 60)).toBeCloseTo(0.5, 5);
  });

  it("每格小时数：行数不超过上限，且从最小步长试起", () => {
    expect(hoursPerRow(span, 20)).toBe(1);
    expect(hoursPerRow(span, 5)).toBe(2);
    expect(rowCount(span, 2)).toBe(5);
  });

  it("纵轴刻度对齐整点", () => {
    const marks = roundMarks({ startMinute: 7 * 60 + 43, endMinute: 9 * 60 + 5 }, 60);
    expect(marks).toEqual([8 * 60, 9 * 60]);
  });

  it("跨度里一个整点都没有时退回起点", () => {
    expect(roundMarks({ startMinute: 7 * 60 + 43, endMinute: 7 * 60 + 50 }, 60)).toEqual([
      7 * 60 + 43,
    ]);
  });

  it("斑马纹第一小时永远有带（相位锚在 span 起点）", () => {
    const bands = zebraBands({ startMinute: 7 * 60 + 43, endMinute: 9 * 60 + 43 });
    expect(bands.length).toBeGreaterThan(0);
    expect(bands[0].startMinute).toBe(7 * 60 + 43);
  });

  it("斑马纹与 span 求交，不越界", () => {
    const bands = zebraBands(span);
    expect(bands[0].startMinute).toBeGreaterThanOrEqual(span.startMinute);
    expect(bands[bands.length - 1].endMinute).toBeLessThanOrEqual(span.endMinute);
  });

  it("不重叠的课各自占满整列", () => {
    const placed = layout([cls(1, "08:00", "09:00"), cls(2, "10:00", "11:00")], mappingOf(span, null));
    expect(placed).toHaveLength(2);
    expect(placed.every((p) => p.leftFraction === 0 && p.widthFraction === 1)).toBe(true);
  });

  it("重叠的课左右等分并排", () => {
    const placed = layout([cls(1, "09:00", "10:00"), cls(2, "09:30", "10:30")], mappingOf(span, null));
    expect(placed).toHaveLength(2);
    expect(placed[0].widthFraction).toBeCloseTo(0.5, 5);
    expect(placed[1].leftFraction).toBeCloseTo(0.5, 5);
  });

  it("传递重叠（A∩B、B∩C）归为同一组，三栏并排", () => {
    const placed = layout(
      [cls(1, "09:00", "10:00"), cls(2, "09:30", "10:30"), cls(3, "10:00", "11:00")],
      mappingOf(span, null),
    );
    expect(placed).toHaveLength(3);
    expect(placed.every((p) => Math.abs(p.widthFraction - 1 / 3) < 1e-5)).toBe(true);
  });

  it("时间解析不出来的课不参与布局", () => {
    expect(layout([cls(1, "上午", "中午")], mappingOf(span, null))).toHaveLength(0);
  });

  it("labelOf 处理跨零点", () => {
    expect(labelOf(8 * 60 + 5)).toBe("08:05");
    expect(labelOf(24 * 60 + 60)).toBe("01:00");
  });

  it("minutesOf 挡住非法时间", () => {
    expect(minutesOf("24:00")).toBeNull();
    expect(minutesOf("08:60")).toBeNull();
    expect(minutesOf("8:5")).toBe(485);
  });
});
