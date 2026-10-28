import { describe, expect, it } from "vitest";
import { composeNote, parseDate, parseNote } from "../vaultNote";

const FULL = `---
stumate: true
stumate_uid: 3f2a1b
created: 2026-10-28T14:22:00
updated: 2026-10-29T09:00:00
tags: [物理, 复习]
aliases:
  - 第一章
---

正文第一行
正文第二行
`;

describe("parseNote", () => {
  it("没有 frontmatter 时整篇都是正文", () => {
    const n = parseNote("随手写的一段\n第二行");
    expect(n.uid).toBe("");
    expect(n.created).toBe(0);
    expect(n.extra).toEqual([]);
    expect(n.body).toBe("随手写的一段\n第二行");
  });

  it("解析出 uid 与时间", () => {
    const n = parseNote(FULL);
    expect(n.uid).toBe("3f2a1b");
    expect(n.created).toBe(new Date(2026, 9, 28, 14, 22, 0).getTime());
    expect(n.updated).toBe(new Date(2026, 9, 29, 9, 0, 0).getTime());
    expect(n.body).toBe("正文第一行\n正文第二行\n");
  });

  it("不认识的键原样保留（含多行续行与数组写法）", () => {
    const n = parseNote(FULL);
    expect(n.extra).toEqual(["tags: [物理, 复习]", "aliases:", "  - 第一章"]);
  });

  it("stumate 标记本身不保留（写回时重新生成）", () => {
    expect(parseNote(FULL).extra.some((l) => l.startsWith("stumate:"))).toBe(false);
  });

  it("容忍 uid 这个别名（手写文件常用）", () => {
    expect(parseNote("---\nuid: abc\n---\n\nx").uid).toBe("abc");
  });

  it("带引号的值去掉引号并还原转义", () => {
    expect(parseNote('---\nstumate_uid: "a \\"b\\""\n---\n\nx').uid).toBe('a "b"');
  });

  it("CRLF 与 BOM 都不影响解析", () => {
    const crlf = FULL.replace(/\n/g, "\r\n");
    expect(parseNote(crlf).uid).toBe("3f2a1b");
    expect(parseNote(`\ufeff${FULL}`).uid).toBe("3f2a1b");
  });

  it("frontmatter 没闭合时整篇当正文（宁可不解析，也不能吃掉正文）", () => {
    const raw = "---\nstumate_uid: abc\n正文没有分隔线";
    const n = parseNote(raw);
    expect(n.uid).toBe("");
    expect(n.body).toBe(raw);
  });

  it("空文件不炸", () => {
    expect(parseNote("").body).toBe("");
  });
});

describe("composeNote", () => {
  it("写出的结构与解析器互逆", () => {
    const meta = {
      uid: "u-1",
      created: new Date(2026, 9, 28, 14, 22, 0).getTime(),
      updated: new Date(2026, 9, 29, 9, 0, 0).getTime(),
      extra: ["tags: [物理]"],
    };
    const back = parseNote(composeNote(meta, "第一行\n\n第二段"));
    expect(back.uid).toBe("u-1");
    expect(back.created).toBe(meta.created);
    expect(back.updated).toBe(meta.updated);
    expect(back.extra).toEqual(["tags: [物理]"]);
    expect(back.body).toBe("第一行\n\n第二段\n");
  });

  it("分隔线与正文之间恰好一个空行", () => {
    const text = composeNote({ uid: "u", created: 0, updated: 0, extra: [] }, "正文");
    expect(text).toContain("---\n\n正文");
    expect(text).not.toContain("---\n\n\n");
  });

  it("没有时间就不写这两行", () => {
    const text = composeNote({ uid: "u", created: 0, updated: 0, extra: [] }, "");
    expect(text).not.toContain("created:");
    expect(text).not.toContain("updated:");
  });

  it("剥掉行尾空白与结尾空行，末尾恰好一个换行", () => {
    const text = composeNote({ uid: "u", created: 0, updated: 0, extra: [] }, "a   \nb\n\n\n");
    expect(parseNote(text).body).toBe("a\nb\n");
    expect(text.endsWith("b\n")).toBe(true);
    expect(text.endsWith("\n\n")).toBe(false);
  });

  it("正文中间的连续空行原样保留（那是排版，不是噪音）", () => {
    const text = composeNote({ uid: "u", created: 0, updated: 0, extra: [] }, "第一段\n\n\n第二段");
    expect(parseNote(text).body).toBe("第一段\n\n\n第二段\n");
  });

  it("空正文不留两行空白", () => {
    const text = composeNote({ uid: "u", created: 0, updated: 0, extra: [] }, "");
    expect(text.endsWith("---\n\n")).toBe(true);
    expect(text.endsWith("\n\n\n")).toBe(false);
    expect(parseNote(text).body).toBe("");
  });

  it("特殊 uid 会被引起来（YAML 里裸写会坏）", () => {
    const text = composeNote({ uid: "a: b", created: 0, updated: 0, extra: [] }, "");
    expect(text).toContain('stumate_uid: "a: b"');
    expect(parseNote(text).uid).toBe("a: b");
  });
});

describe("parseDate", () => {
  it("接受三种写法", () => {
    const want = new Date(2026, 9, 28, 14, 22, 0).getTime();
    expect(parseDate("2026-10-28T14:22:00")).toBe(want);
    expect(parseDate("2026-10-28 14:22")).toBe(want);
    expect(parseDate("2026-10-28")).toBe(new Date(2026, 9, 28).getTime());
  });

  it("按本地时区解释，不按 UTC（跨设备同步时日期不漂）", () => {
    expect(parseDate("2026-10-28")).toBe(new Date(2026, 9, 28, 0, 0, 0).getTime());
  });

  it("看不懂就返回 0", () => {
    expect(parseDate("昨天")).toBe(0);
    expect(parseDate("")).toBe(0);
  });
});
