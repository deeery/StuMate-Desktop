/**
 * 文件库 → 导图数据源的适配层。
 *
 * v1.7.7 把知识库改成「一篇笔记一个 .md」之后，`model/mindMap.ts` 的
 * `reconcileMind` 就没人喂数据了 —— 它吃的是旧 DB 的 `KnowledgeEntity[]`。
 * 这里把扫描结果折成同样形状的**只读投影**，导图模型一行都不用改。
 *
 * 关键取舍：uid 用 `vault:<相对路径>`，而不是 frontmatter 里的 `stumate_uid`。
 * 派生导图要扫全库，为拿 uid 去读几百个文件太贵；路径本来就是文件库里
 * 唯一的稳定身份（标题即文件名）。代价是改名会让那一个 entry 重建一次，
 * 它下面手工挂的节点按 `reconcileMind` 规则 4 降级保留，不会凭空消失。
 */

import type { KnowledgeEntity } from "./types";
import type { VaultEntry } from "./vaultTree";
import { baseTitle } from "./vaultTree";

export const VAULT_UID_PREFIX = "vault:";

export function vaultUid(path: string): string {
  return `${VAULT_UID_PREFIX}${path}`;
}

/** 导图节点点回笔记时用；不是文件库节点返回 null */
export function pathOfVaultUid(uid: string): string | null {
  return uid.startsWith(VAULT_UID_PREFIX) && uid.length > VAULT_UID_PREFIX.length
    ? uid.slice(VAULT_UID_PREFIX.length)
    : null;
}

/**
 * `vault_scan` 的结果 → `reconcileMind` 的输入。
 *
 * 只借用 9 个字段里的身份与排序信息，`content` 恒为空 —— 导图不显示正文，
 * 也没有理由把几百篇全文拉进渲染进程。
 */
export function toKnowledgeEntities(entries: VaultEntry[]): KnowledgeEntity[] {
  return entries
    .filter((e) => !e.isDir && e.path.toLowerCase().endsWith(".md"))
    .slice()
    .sort((a, b) => a.path.localeCompare(b.path, "zh-Hans-CN"))
    .map((e, index) => ({
      id: index + 1,
      // 根目录下的笔记算「未分类」：与旧 DB 行的空 subject 同一口径
      subject: e.path.includes("/") ? e.path.slice(0, e.path.indexOf("/")) : "",
      title: baseTitle(e.path),
      content: "",
      position: index,
      createdAt: e.mtime,
      uid: vaultUid(e.path),
      updatedAt: e.mtime,
      deletedAt: 0,
    }));
}
