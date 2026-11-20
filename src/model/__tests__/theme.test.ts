import { describe, expect, it } from "vitest";
import { THEME_OPTIONS, themeNameOf, tintOf } from "../theme";

describe("主题档位 · 明暗", () => {
  it("显式三档：浅色 / 深色 / 主题色（亮/暗）", () => {
    expect(themeNameOf(1, false)).toBe("light");
    expect(themeNameOf(2, false)).toBe("dark");
    expect(themeNameOf(3, true)).toBe("light");
    expect(themeNameOf(4, true)).toBe("dark");
  });

  it("0 跟随系统", () => {
    expect(themeNameOf(0, true)).toBe("dark");
    expect(themeNameOf(0, false)).toBe("light");
  });

  it("认不出来的值当「跟随系统」，不锁死一套", () => {
    expect(themeNameOf(undefined, true)).toBe("dark");
    expect(themeNameOf(null, false)).toBe("light");
    expect(themeNameOf(9, true)).toBe("dark");
    expect(themeNameOf(-1, false)).toBe("light");
  });
});

describe("主题档位 · 是否掺主题色", () => {
  it("只有 3 / 4 掺色", () => {
    expect(tintOf(3)).toBe("accent");
    expect(tintOf(4)).toBe("accent");
  });

  it("其余都是中性面（含脏数据）", () => {
    for (const m of [0, 1, 2, undefined, null, 7]) expect(tintOf(m)).toBe("neutral");
  });
});

describe("主题档位 · 下拉选项", () => {
  it("五档、值与标签一一对应，且顺序就是展示顺序", () => {
    expect(THEME_OPTIONS.map((o) => o.value)).toEqual([0, 1, 2, 3, 4]);
    expect(THEME_OPTIONS.map((o) => o.label)).toEqual([
      "跟随系统",
      "浅色",
      "深色",
      "主题色（亮）",
      "主题色（暗）",
    ]);
  });

  it("每个档位都能被两个判定函数接住（不会出现「选了却没反应」）", () => {
    for (const o of THEME_OPTIONS) {
      expect(themeNameOf(o.value, false)).toMatch(/^(light|dark)$/);
      expect(tintOf(o.value)).toMatch(/^(neutral|accent)$/);
    }
  });
});
