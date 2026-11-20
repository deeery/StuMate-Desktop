import { describe, expect, it } from "vitest";
import { ACCENTS, DEFAULT_ACCENT, accentOf, accentSwatch } from "../accent";

describe("主题色 · 取值收敛", () => {
  it("认识的 id 原样返回", () => {
    expect(accentOf("teal").id).toBe("teal");
    expect(accentOf("orange").name).toBe("橙");
  });

  it("认不出来的一律回默认（旧设置、手改的备份、未来的新键）", () => {
    expect(accentOf(undefined).id).toBe(DEFAULT_ACCENT);
    expect(accentOf(null).id).toBe(DEFAULT_ACCENT);
    expect(accentOf("").id).toBe(DEFAULT_ACCENT);
    expect(accentOf("chartreuse").id).toBe(DEFAULT_ACCENT);
    expect(accentOf(3).id).toBe(DEFAULT_ACCENT);
  });

  it("默认色必须在表里，否则回落会落空", () => {
    expect(ACCENTS.some((a) => a.id === DEFAULT_ACCENT)).toBe(true);
  });
});

describe("主题色 · 表本身", () => {
  it("id 唯一、名字不为空、深浅两套都是 #rrggbb", () => {
    const ids = ACCENTS.map((a) => a.id);
    expect(new Set(ids).size).toBe(ids.length);
    for (const a of ACCENTS) {
      expect(a.name.trim()).not.toBe("");
      expect(a.light).toMatch(/^#[0-9a-f]{6}$/);
      expect(a.dark).toMatch(/^#[0-9a-f]{6}$/);
    }
  });

  it("深浅两套必须不同：同一个值不可能既压得住白字又能当深底文字色", () => {
    for (const a of ACCENTS) expect(a.light).not.toBe(a.dark);
  });
});

describe("主题色 · 色块颜色", () => {
  it("跟当前主题走，深色下别拿浅色值充数", () => {
    const teal = accentOf("teal");
    expect(accentSwatch(teal, "light")).toBe(teal.light);
    expect(accentSwatch(teal, "dark")).toBe(teal.dark);
  });
});
