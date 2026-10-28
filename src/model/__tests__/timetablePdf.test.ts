import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { parseTimetablePdf, type ParsedCourse } from "../timetablePdf";

/**
 * 用**真实**教务系统导出的课表 PDF 验证（`fixtures/example.pdf`，2 页 15 个课程格）。
 *
 * 这份 fixture 是从原版 `Stumate-desktop-v1.7.5/src/test/resources/example.pdf`
 * 原样拷过来的，断言也照着原版 `TimetablePdfParserTest.kt` 逐条搬 ——
 * 这样"Lite 的解析结果和原版一致"就不是靠肉眼看，而是两边跑同一组期望。
 */
const pdf = new Uint8Array(
  readFileSync(new URL("./fixtures/example.pdf", import.meta.url)),
);

/** 解析一次给所有用例复用（解析是 async 且要解压，没必要每个用例重跑） */
const courses: ParsedCourse[] = await parseTimetablePdf(pdf);

describe("课表 PDF：整份解析", () => {
  it("15 个课程格全部解析出来", () => {
    expect(courses).toHaveLength(15);
  });

  it("每门课都有名字、星期、时间和教室", () => {
    for (const c of courses) {
      expect(c.title.trim()).not.toBe("");
      expect(c.dayOfWeek).not.toBe("");
      expect(c.startTime).toMatch(/^\d{2}:\d{2}$/);
      expect(c.endTime).toMatch(/^\d{2}:\d{2}$/);
      expect(c.room).not.toBe("");
    }
  });

  it("字段与折行标题都能还原", () => {
    const p = courses.find((c) => c.title === "概率论与数理统计II");
    expect(p).toBeDefined();
    expect(p).toMatchObject({
      dayOfWeek: "Monday",
      startTime: "09:50", // 3-5 节
      endTime: "12:15",
      room: "A420",
      teacher: "孙利荣",
      weeks: "1-16周",
    });

    // 标题被折成「计算思维与人工智能应」+「用◆」，要拼回完整名字并去掉标记
    const wrapped = courses.find((c) => c.title === "计算思维与人工智能应用");
    expect(wrapped).toBeDefined();
    expect(wrapped?.dayOfWeek).toBe("Monday");
    expect(wrapped?.room).toBe("A124");
  });

  it("只有周次不同的同名课都保留", () => {
    // 星期三 10-12 节有两个周次段（第 6 周 / 1-5 周），两条都要在
    const deepSeek = courses.filter((c) => c.title === "解密DeepSeek：从基础到创意实践");
    expect(deepSeek).toHaveLength(2);
    expect(new Set(deepSeek.map((c) => c.weeks))).toEqual(new Set(["第6周", "1-5周"]));
  });

  it("页眉、节次数字、页脚说明都不会被当成课程", () => {
    for (const c of courses) {
      expect(c.title).not.toContain("课表");
      expect(c.title).not.toContain("学号");
      expect(c.title).not.toContain("打印时间");
      expect(c.title.startsWith("其他课程")).toBe(false);
      expect(c.title).not.toMatch(/^\d+$/);
    }
  });
});

describe("课表 PDF：异常输入", () => {
  it("不是 PDF 的字节返回空数组，不抛异常", async () => {
    await expect(parseTimetablePdf(new TextEncoder().encode("hello"))).resolves.toEqual([]);
  });

  it("空输入返回空数组", async () => {
    await expect(parseTimetablePdf(new Uint8Array(0))).resolves.toEqual([]);
  });
});
