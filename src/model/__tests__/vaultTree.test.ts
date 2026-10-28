import { describe, expect, it } from "vitest";
import {
  ancestorsOf,
  baseTitle,
  buildTree,
  compareName,
  findFolder,
  findNode,
  flattenNotes,
  joinPath,
  matchScore,
  movePath,
  parentOf,
  renamePath,
  subjectOf,
  takenNames,
  uniqueTitle,
  type VaultEntry,
} from "../vaultTree";

function e(path: string, isDir = false, mtime = 1000): VaultEntry {
  return { path, isDir, mtime, size: 10 };
}

describe("路径碎片", () => {
  it("parentOf / baseTitle 处理根下与嵌套", () => {
    expect(parentOf("a/b/c.md")).toBe("a/b");
    expect(parentOf("c.md")).toBe("");
    expect(baseTitle("a/b/c.md")).toBe("c");
    expect(baseTitle("a/b/物理.MD")).toBe("物理");
    expect(baseTitle("a/b/没有扩展名")).toBe("没有扩展名");
  });

  it("joinPath 在根目录不产生前导斜杠", () => {
    expect(joinPath("", "x.md")).toBe("x.md");
    expect(joinPath("a", "x.md")).toBe("a/x.md");
  });

  it("subjectOf 取第一段目录，根下的算未分类", () => {
    expect(subjectOf("大学物理/第一章.md")).toBe("大学物理");
    expect(subjectOf("大学物理/子目录/第一章.md")).toBe("大学物理");
    expect(subjectOf("散笔记.md")).toBe("未分类");
  });

  it("ancestorsOf 由近到远", () => {
    expect(ancestorsOf("a/b/c/d.md")).toEqual(["a/b/c", "a/b", "a"]);
    expect(ancestorsOf("d.md")).toEqual([]);
  });
});

describe("buildTree", () => {
  it("文件夹优先，嵌套正确", () => {
    const tree = buildTree([
      e("大学物理", true),
      e("大学物理/第一章.md"),
      e("大学物理/例题", true),
      e("大学物理/例题/斜面.md"),
      e("散笔记.md"),
    ]);
    expect(tree.map((n) => n.name)).toEqual(["大学物理", "散笔记.md"].map((x) => x.replace(/\.md$/, "")));
    expect(tree[0].kind).toBe("folder");
    const folder = tree[0];
    expect(folder.children.map((n) => n.name)).toEqual(["例题", "第一章"]);
    expect(folder.children[0].children[0].name).toBe("斜面");
  });

  it("非 md 文件不进树", () => {
    const tree = buildTree([e("a", true), e("a/x.png"), e("a/y.md")]);
    expect(tree[0].children.map((n) => n.name)).toEqual(["y"]);
  });

  it("父目录没被列出来时自动补建，笔记不会凭空消失", () => {
    const tree = buildTree([e("a/b/c.md")]);
    expect(tree.map((n) => n.path)).toEqual(["a"]);
    expect(tree[0].children[0].path).toBe("a/b");
    expect(tree[0].children[0].children[0].name).toBe("c");
  });

  it("排序：名称用自然序，第 2 章在第 10 章前", () => {
    const tree = buildTree([e("第10章.md"), e("第2章.md")]);
    expect(tree.map((n) => n.name)).toEqual(["第2章", "第10章"]);
  });

  it("排序：mtime 降序，但文件夹仍恒在文件之前", () => {
    const tree = buildTree([e("旧.md", false, 100), e("新.md", false, 900), e("目录", true, 1)], "mtime");
    expect(tree.map((n) => n.name)).toEqual(["目录", "新", "旧"]);
  });

  it("空的扫描结果 → 空树", () => {
    expect(buildTree([])).toEqual([]);
  });
});

describe("compareName", () => {
  it("数字按大小而非字典序", () => {
    expect(compareName("第2章", "第10章")).toBeLessThan(0);
    expect(compareName("a", "A")).toBe(0);
  });
});

describe("查找与展平", () => {
  const tree = buildTree([
    e("a", true),
    e("a/b", true),
    e("a/b/深.md"),
    e("a/浅.md"),
  ]);

  it("findFolder / findNode", () => {
    expect(findFolder(tree, "a/b")?.path).toBe("a/b");
    expect(findFolder(tree, "nope")).toBeNull();
    expect(findNode(tree, "a/浅.md")?.name).toBe("浅");
    expect(findNode(tree, "a")?.kind).toBe("folder");
  });

  it("flattenNotes 按显示顺序、只出笔记", () => {
    expect(flattenNotes(tree).map((n) => n.path)).toEqual(["a/b/深.md", "a/浅.md"]);
  });
});

describe("命名与去重", () => {
  it("uniqueTitle 撞名时追加 2、3", () => {
    const taken = new Set(["未命名.md", "未命名 2.md"]);
    expect(uniqueTitle("未命名", taken)).toBe("未命名 3");
  });

  it("uniqueTitle 大小写不敏感（Windows 上是一个文件）", () => {
    expect(uniqueTitle("note", new Set(["NOTE.md"]))).toBe("note 2");
  });

  it("uniqueTitle 清洗非法字符并回落到「未命名」", () => {
    expect(uniqueTitle("a/b:c", new Set())).toBe("a_b_c");
    expect(uniqueTitle("   ", new Set())).toBe("未命名");
  });

  it("takenNames 小写化且笔记带扩展名", () => {
    const tree = buildTree([e("目录", true), e("Note.md")]);
    expect(takenNames(tree, "")).toEqual(new Set(["目录", "note.md"]));
  });
});

describe("movePath", () => {
  const tree = buildTree([e("a", true), e("b", true), e("x.md"), e("a/x.md")]);

  it("挪进目标目录，保留文件名", () => {
    expect(movePath("x.md", "b", tree)).toBe("b/x.md");
  });

  it("原地不动时原样返回（不当成移动处理）", () => {
    expect(movePath("a/x.md", "a", tree)).toBe("a/x.md");
  });

  it("撞名时顺延", () => {
    expect(movePath("x.md", "a", tree)).toBe("a/x 2.md");
  });

  it("挪回根目录", () => {
    expect(movePath("a/x.md", "", tree)).toBe("x 2.md");
  });
});

describe("renamePath", () => {
  const taken = new Set(["a.md", "b.md"]);

  it("笔记自动补 .md，且不会变成 .md.md", () => {
    expect(renamePath("c.md", "新名字", new Set())).toBe("新名字.md");
    expect(renamePath("c.md", "新名字.md", new Set())).toBe("新名字.md");
  });

  it("文件夹不补扩展名", () => {
    expect(renamePath("a/b", "新目录", new Set())).toBe("a/新目录");
  });

  it("没改（或只改大小写到自身）时原样返回", () => {
    expect(renamePath("a.md", "a", taken)).toBe("a.md");
  });

  it("撞名时顺延，不会覆盖别人", () => {
    expect(renamePath("c.md", "a", taken)).toBe("a 2.md");
  });

  it("改大小写不算撞自己", () => {
    expect(renamePath("a.md", "A", new Set(["a.md"]))).toBe("A.md");
  });

  it("清洗非法字符", () => {
    expect(renamePath("c.md", "a/b", new Set())).toBe("a_b.md");
  });
});

describe("matchScore", () => {
  it("前缀 > 子串 > 子序列", () => {
    const prefix = matchScore("物理", "物理第一章");
    const sub = matchScore("物理", "第一章物理");
    const seq = matchScore("物理", "物体 理论");
    expect(prefix).toBeGreaterThan(sub);
    expect(sub).toBeGreaterThan(seq);
    expect(seq).toBeGreaterThan(0);
  });

  it("不匹配返回 -1，空查询返回 0", () => {
    expect(matchScore("xyz", "大学物理")).toBe(-1);
    expect(matchScore("  ", "任意")).toBe(0);
  });

  it("支持跳字输入（记得其中几个字也能命中）", () => {
    expect(matchScore("电波式", "电磁波公式")).toBeGreaterThan(0);
  });

  it("大小写不敏感", () => {
    expect(matchScore("NOTE", "note/abc")).toBeGreaterThan(0);
  });
});
