import { describe, expect, it } from "vitest";
import {
  buildVaultFiles,
  localIso,
  sanitizeSegment,
  UNCLASSIFIED,
  VAULT_ROOT,
  yamlScalar,
} from "../vault";
import { EMPTY_KNOWLEDGE, type KnowledgeEntity } from "../types";

let seq = 0;
function k(patch: Partial<KnowledgeEntity> = {}): KnowledgeEntity {
  seq += 1;
  return {
    ...EMPTY_KNOWLEDGE,
    id: seq,
    uid: `uid-${seq}`,
    title: `条目${seq}`,
    content: "正文",
    subject: "大学物理",
    position: 0,
    createdAt: 1_700_000_000_000,
    ...patch,
  };
}

describe("文件名净化", () => {
  it("Windows 非法字符一律换成下划线", () => {
    expect(sanitizeSegment('a\\b/c:d*e?f"g<h>i|j')).toBe("a_b_c_d_e_f_g_h_i_j");
  });

  it("控制字符也换掉（否则建文件直接失败）", () => {
    expect(sanitizeSegment("a\u0000b\u001fc")).toBe("a_b_c");
  });

  it("结尾的点和空格必须去掉：Windows 上建不出来", () => {
    expect(sanitizeSegment("第四章 动量.")).toBe("第四章 动量");
    expect(sanitizeSegment("标题   ")).toBe("标题");
  });

  it("空名或纯空白走兜底", () => {
    expect(sanitizeSegment("")).toBe("未命名");
    expect(sanitizeSegment("   ")).toBe("未命名");
    expect(sanitizeSegment("...")).toBe("未命名");
    expect(sanitizeSegment("", "未命名条目")).toBe("未命名条目");
  });

  it("超长按字符截断到 60（中文按 1 个算）", () => {
    const long = "物".repeat(80);
    expect([...sanitizeSegment(long)].length).toBe(60);
  });

  it("截断后若落到点上，再去一次", () => {
    // 第 60 个字符是点：截断后就成了「以点结尾」
    const s = "a".repeat(59) + "." + "b";
    expect(sanitizeSegment(s).endsWith(".")).toBe(false);
  });
});

describe("YAML 标量", () => {
  it("普通中文裸写，不加引号", () => {
    expect(yamlScalar("大学物理")).toBe("大学物理");
  });

  it("会被解析成布尔/数字/null 的字面量要加引号", () => {
    expect(yamlScalar("true")).toBe('"true"');
    expect(yamlScalar("No")).toBe('"No"');
    expect(yamlScalar("12")).toBe('"12"');
    expect(yamlScalar("~")).toBe('"~"');
  });

  it("含冒号+空格、井号、或以指示符开头要加引号", () => {
    expect(yamlScalar("第 3 章: 动量")).toBe('"第 3 章: 动量"');
    expect(yamlScalar("a #b")).toBe('"a #b"');
    expect(yamlScalar("#标签")).toBe('"#标签"');
    expect(yamlScalar("- 列表")).toBe('"- 列表"');
  });

  it("引号与反斜杠要转义", () => {
    expect(yamlScalar('a"b')).toBe('"a\\"b"');
    expect(yamlScalar("a\\b: c")).toBe('"a\\\\b: c"');
  });

  it("空串给一对空引号", () => {
    expect(yamlScalar("")).toBe('""');
  });
});

describe("时间格式", () => {
  it("0 或负数不出时间", () => {
    expect(localIso(0)).toBe("");
    expect(localIso(-1)).toBe("");
  });

  it("本地时间、不含时区", () => {
    const s = localIso(new Date(2026, 9, 28, 19, 5, 7).getTime());
    expect(s).toBe("2026-10-28T19:05:07");
  });
});

describe("导出铺排", () => {
  it("路径是 Stumate/<科目>/<标题>.md", () => {
    const files = buildVaultFiles([k({ subject: "大学物理", title: "牛顿第二定律" })]);
    expect(files).toHaveLength(1);
    expect(files[0].path).toBe(`${VAULT_ROOT}/大学物理/牛顿第二定律.md`);
  });

  it("空科目落到「未分类」目录", () => {
    const files = buildVaultFiles([k({ subject: "  ", title: "随手记" })]);
    expect(files[0].path).toBe(`${VAULT_ROOT}/${UNCLASSIFIED}/随手记.md`);
    expect(files[0].content).toContain(`subject: ${UNCLASSIFIED}`);
  });

  it("frontmatter 带 stumate 标记、uid、科目、position", () => {
    const files = buildVaultFiles([
      k({ subject: "高数", title: "极限", uid: "abc-123", position: 4 }),
    ]);
    const c = files[0].content;
    expect(c.startsWith("---\n")).toBe(true);
    expect(c).toContain("stumate: true");
    expect(c).toContain("stumate_uid: abc-123");
    expect(c).toContain("subject: 高数");
    expect(c).toContain("position: 4");
  });

  it("正文原样跟在 frontmatter 后面，末尾补一个换行", () => {
    const files = buildVaultFiles([k({ content: "第一行\n第二行\n\n" })]);
    expect(files[0].content.endsWith("第二行\n")).toBe(true);
    expect(files[0].content).toContain("---\n\n第一行");
  });

  it("同名条目递增 -2 -3，且大小写不敏感", () => {
    const files = buildVaultFiles([
      k({ subject: "高数", title: "极限" }),
      k({ subject: "高数", title: "极限" }),
      k({ subject: "高数", title: "极限" }),
    ]);
    expect(files.map((f) => f.path)).toEqual([
      `${VAULT_ROOT}/高数/极限.md`,
      `${VAULT_ROOT}/高数/极限-2.md`,
      `${VAULT_ROOT}/高数/极限-3.md`,
    ]);

    const ci = buildVaultFiles([
      k({ subject: "高数", title: "ABC" }),
      k({ subject: "高数", title: "abc" }),
    ]);
    expect(ci[1].path).toBe(`${VAULT_ROOT}/高数/abc-2.md`);
  });

  it("不同科目下的同名不互相挤占", () => {
    const files = buildVaultFiles([
      k({ subject: "高数", title: "极限" }),
      k({ subject: "线代", title: "极限" }),
    ]);
    expect(files.map((f) => f.path)).toEqual([
      `${VAULT_ROOT}/线代/极限.md`,
      `${VAULT_ROOT}/高数/极限.md`,
    ]);
  });

  it("软删除的条目不导出", () => {
    const files = buildVaultFiles([k({ deletedAt: 1 }), k({ title: "留下" })]);
    expect(files).toHaveLength(1);
    expect(files[0].path).toContain("留下");
  });

  it("未分类排在最前，其余按科目名与 position 排", () => {
    const files = buildVaultFiles([
      k({ subject: "高数", title: "A", position: 2 }),
      k({ subject: "", title: "B" }),
      k({ subject: "高数", title: "C", position: 1 }),
    ]);
    expect(files.map((f) => f.path)).toEqual([
      `${VAULT_ROOT}/${UNCLASSIFIED}/B.md`,
      `${VAULT_ROOT}/高数/C.md`,
      `${VAULT_ROOT}/高数/A.md`,
    ]);
  });

  it("没有标题的条目有兜底文件名", () => {
    const files = buildVaultFiles([k({ title: "  " })]);
    expect(files[0].path.endsWith("未命名条目.md")).toBe(true);
  });
});
