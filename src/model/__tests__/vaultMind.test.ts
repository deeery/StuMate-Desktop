import { describe, expect, it } from "vitest";
import { pathOfVaultUid, toKnowledgeEntities, vaultUid, VAULT_UID_PREFIX } from "../vaultMind";
import { reconcileMind } from "../mindMap";
import type { VaultEntry } from "../vaultTree";

const entry = (path: string, isDir = false): VaultEntry => ({
  path,
  isDir,
  mtime: 1730000000000,
  size: 12,
});

describe("文件库 → 导图投影", () => {
  it("只收 .md 笔记，目录与非 md 附件都不进导图", () => {
    const rows = toKnowledgeEntities([
      entry("物理", true),
      entry("物理/牛顿.md"),
      entry("物理/图.png"),
      entry("随笔.MD"),
    ]);
    // 按完整路径排：随笔.MD (sui) 在 物理/牛顿.md (wu) 前面，
    // 大写 .MD 也算笔记；目录与 .png 都被滤掉。
    expect(rows.map((r) => r.title)).toEqual(["随笔", "牛顿"]);
  });

  it("uid 用路径往返，subject 取顶层目录，根目录算未分类", () => {
    const rows = toKnowledgeEntities([
      entry("随笔.md"),
      entry("物理/力学/牛顿.md"),
    ]);
    const plain = rows.find((r) => r.title === "随笔")!;
    const physics = rows.find((r) => r.title === "牛顿")!;
    expect(plain.subject).toBe("");
    expect(physics.subject).toBe("物理");
    expect(physics.uid).toBe(vaultUid("物理/力学/牛顿.md"));
    expect(pathOfVaultUid(physics.uid)).toBe("物理/力学/牛顿.md");
    expect(physics.content).toBe("");
    expect(physics.deletedAt).toBe(0);
  });

  it("不是文件库节点的 uid 一律不认", () => {
    expect(pathOfVaultUid("db-uid-123")).toBeNull();
    expect(pathOfVaultUid(VAULT_UID_PREFIX)).toBeNull();
    expect(pathOfVaultUid("id:7")).toBeNull();
  });

  it("按路径排序，id / position 稳定", () => {
    const rows = toKnowledgeEntities([
      entry("乙.md"),
      entry("甲.md"),
    ]);
    expect(rows.map((r) => r.title)).toEqual(["甲", "乙"]); // zh-Hans-CN 按拼音：jia < yi
    expect(rows[0].id).toBe(1);
    expect(rows[1].id).toBe(2);
    expect(rows[0].position).toBe(0);
  });

  it("能直接喂给 reconcileMind，且二次调用幂等", () => {
    const rows = toKnowledgeEntities([
      entry("物理/牛顿.md"),
      entry("数学/极限.md"),
    ]);
    const first = reconcileMind([], rows, () => "unused");
    expect(first.changed).toBe(true);
    expect(first.nodes.filter((n) => n.kind === "entry")).toHaveLength(2);
    expect(first.nodes.filter((n) => n.kind === "subject")).toHaveLength(2);

    const second = reconcileMind(first.nodes, rows, () => "unused");
    expect(second.changed).toBe(false);

    // 笔记删掉之后，entry 节点也要跟着消失
    const shrunk = reconcileMind(first.nodes, rows.slice(0, 1), () => "unused");
    expect(shrunk.nodes.filter((n) => n.kind === "entry")).toHaveLength(1);
  });
});
