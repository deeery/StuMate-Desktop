import { describe, expect, it } from "vitest";
import type { NoteEntity, NoteLink } from "../types";
import {
  dropNoteLink,
  emptyLink,
  linkOf,
  notesOfClass,
  notesOfRule,
  upsertLink,
} from "../noteLinks";

function note(id: number, title = `n${id}`): NoteEntity {
  return {
    id,
    title,
    content: "",
    position: 0,
    createdAt: 0,
    colorIndex: 0,
    typeIndex: 0,
    customLabel: "",
    deadlineAt: 0,
    uid: "",
    updatedAt: 0,
    deletedAt: 0,
  };
}

describe("便签关联 · 读写", () => {
  it("没关联时返回空值，不改动原数组", () => {
    const links: NoteLink[] = [];
    expect(linkOf(links, 7)).toEqual({ noteId: 7, classId: 0, ruleId: "" });
    expect(links).toHaveLength(0);
  });

  it("写入后能取回；再写同一条是替换而不是追加", () => {
    let links = upsertLink([], { noteId: 1, classId: 3, ruleId: "r1" });
    links = upsertLink(links, { noteId: 1, classId: 4, ruleId: "" });
    expect(links).toEqual([{ noteId: 1, classId: 4, ruleId: "" }]);
    expect(linkOf(links, 1).classId).toBe(4);
  });

  it("两个字段都空 = 解除关联，不留空记录", () => {
    const links = upsertLink([{ noteId: 1, classId: 3, ruleId: "r1" }], emptyLink(1));
    expect(links).toEqual([]);
  });

  it("删便签时清掉它的关联", () => {
    const links: NoteLink[] = [
      { noteId: 1, classId: 3, ruleId: "" },
      { noteId: 2, classId: 3, ruleId: "r1" },
    ];
    expect(dropNoteLink(links, 1)).toEqual([{ noteId: 2, classId: 3, ruleId: "r1" }]);
  });
});

describe("便签关联 · 反查", () => {
  const notes = [note(1), note(2), note(3)];
  const links: NoteLink[] = [
    { noteId: 1, classId: 3, ruleId: "r1" },
    { noteId: 2, classId: 3, ruleId: "" },
    { noteId: 3, classId: 0, ruleId: "r1" },
  ];

  it("按课程反查，保持便签原顺序", () => {
    expect(notesOfClass(notes, links, 3).map((n) => n.id)).toEqual([1, 2]);
  });

  it("按规则反查", () => {
    expect(notesOfRule(notes, links, "r1").map((n) => n.id)).toEqual([1, 3]);
  });

  it("id 为空时返回空数组（别把所有便签都倒出来）", () => {
    expect(notesOfClass(notes, links, 0)).toEqual([]);
    expect(notesOfRule(notes, links, "")).toEqual([]);
  });
});
