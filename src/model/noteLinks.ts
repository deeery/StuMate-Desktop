/**
 * 便签 ↔ 课程 / 循环提醒的关联（Lite 专属，落 `note_links.json`）。
 *
 * 纯函数，不读写盘。用途有三处：
 *   · 便签编辑面板：拿「这一条现在挂在谁身上」做下拉的初值；
 *   · 保存便签：把两个下拉折成一条 `NoteLink`（都空则从列表里删掉）；
 *   · 课程 / 循环提醒详情栏：反查「挂在我身上的便签」。
 *
 * 一条便签最多挂 1 门课 + 1 条提醒（产品口径，2026-11-16 确认）。课程可以被
 * 很多条便签挂着，所以这里的索引方向是 noteId → (classId, ruleId)。
 */

import type { NoteEntity, NoteLink } from "./types";

/** 未关联任何东西的初始值 */
export function emptyLink(noteId: number): NoteLink {
  return { noteId, classId: 0, ruleId: "" };
}

/** 取某条便签的关联；没有就返回「什么都没挂」，调用处不必判空 */
export function linkOf(links: NoteLink[], noteId: number): NoteLink {
  return links.find((l) => l.noteId === noteId) ?? emptyLink(noteId);
}

/**
 * 写入一条关联。
 * 两个字段都空 = 解除关联：直接把这行删掉，而不是留一条空记录 ——
 * 空记录会让「有没有关联」在别处多一种判断分支。
 */
export function upsertLink(links: NoteLink[], link: NoteLink): NoteLink[] {
  const rest = links.filter((l) => l.noteId !== link.noteId);
  if (link.classId === 0 && !link.ruleId) return rest;
  return [...rest, { ...link }];
}

/** 某门课挂着的便签（按便签原有顺序，表格里怎么排这里就怎么排） */
export function notesOfClass(notes: NoteEntity[], links: NoteLink[], classId: number): NoteEntity[] {
  if (!classId) return [];
  const ids = new Set(links.filter((l) => l.classId === classId).map((l) => l.noteId));
  return notes.filter((n) => ids.has(n.id));
}

/** 某条循环提醒规则挂着的便签 */
export function notesOfRule(notes: NoteEntity[], links: NoteLink[], ruleId: string): NoteEntity[] {
  if (!ruleId) return [];
  const ids = new Set(links.filter((l) => l.ruleId === ruleId).map((l) => l.noteId));
  return notes.filter((n) => ids.has(n.id));
}

/** 删掉一条便签时顺手清掉它的关联，免得文件里攒一堆指向不存在 id 的行 */
export function dropNoteLink(links: NoteLink[], noteId: number): NoteLink[] {
  return links.filter((l) => l.noteId !== noteId);
}
