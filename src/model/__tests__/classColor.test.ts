import { describe, expect, it } from "vitest";
import { CLASS_COLOR_COUNT, classColorIndex, classColorOf } from "../classColor";

describe("课程身份色", () => {
  it("落在 0..7 之间", () => {
    for (const id of [1, 2, 7, 13, 128, 9999, -1]) {
      const index = classColorIndex(id);
      expect(Number.isInteger(index)).toBe(true);
      expect(index).toBeGreaterThanOrEqual(0);
      expect(index).toBeLessThan(CLASS_COLOR_COUNT);
    }
  });

  it("同一个 id 永远同一档（重渲染、跨周、重启都不会变色）", () => {
    expect(classColorIndex(42)).toBe(classColorIndex(42));
    expect(classColorIndex("42")).toBe(classColorIndex(42));
  });

  it("相邻 id 不保证不同档，但不集中于少数档位", () => {
    // 色档只有 8 个，连续 id 撞档是正常的；这里只验证分布没有塌到一两档上
    const buckets = new Map<number, number>();
    for (let id = 1; id <= 200; id++) {
      const index = classColorIndex(id);
      buckets.set(index, (buckets.get(index) ?? 0) + 1);
    }
    expect(buckets.size).toBe(CLASS_COLOR_COUNT);
    for (const count of buckets.values()) {
      expect(count).toBeGreaterThan(5);
    }
  });

  it("给 CSS 的值是令牌引用，不是硬编码色值", () => {
    expect(classColorOf(3)).toMatch(/^var\(--class-[0-7]\)$/);
  });
});
