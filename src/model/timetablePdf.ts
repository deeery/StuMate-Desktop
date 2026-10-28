/**
 * 教务系统课表 PDF 解析器。从原版 `data/TimetablePdfParser.kt` 逐行搬过来。
 *
 * 目标格式（`example.pdf` 那种）：
 *  - iText 生成，Rotate 90 的表格，2 页；
 *  - 内容流是 FlateDecode，解压后是 `x y ... Tm`（文字坐标）+ `(...)Tj`（文字）指令；
 *  - 字体是 Type0 / UniGB-UCS2-H，字符串按 2 字节大端 UCS-2 解码；
 *  - 每个"星期"列里的课程格 = 课程名（可能折成 2 行）+ 一段以 `(3-5节)` 开头、
 *    以 `/学分:x` 结尾的详情；折行是定宽硬折行，直接拼接即可还原。
 *
 * **不引第三方 PDF 库**，理由和原版一样：只需要"解压内容流 + 取带坐标的文字"，
 * 引一个通用 PDF 库（pdf.js 之类）为了这点事要多几百 KB，而且它的文本抽取
 * 会把坐标信息抹掉 —— 而坐标正是这个解析器定位"哪一列是星期几"的唯一依据。
 *
 * 解压用 WebView2 自带的 `DecompressionStream('deflate')`（zlib 格式，
 * 正好对应 PDF 的 FlateDecode），Node 18+ 也有，所以单测里同样能跑。
 *
 * 与原版的两处差异，都是为了在 JS 里避免同类坑：
 *  1. 原版用 `String(bytes, ISO_8859_1)` 把 PDF 当 latin1 读。JS 的
 *     `TextDecoder('latin1')` **不是** ISO-8859-1，而是 windows-1252
 *     （0x80 会变成 €），会让后续按字节还原的文字串错位。所以这里自己拼字符串。
 *  2. 原版注释里提到「不要用 `.*` 抓正文，因为内容流里会出现 0x0B 这类控制字符」——
 *     这里同样只做首尾切分，不用正则匹配正文。
 */

/** 解析出的一门课在某一天的一次课 */
export interface ParsedCourse {
  title: string;
  dayOfWeek: string;
  startTime: string;
  endTime: string;
  room: string;
  teacher: string;
  weeks: string;
}

/**
 * PDF 里只有"第几节"，没有具体时刻，只能按常见作息推算。
 * 导入后可以在列表里逐条改时间。
 */
const DEFAULT_PERIOD_TIMES: [string, string][] = [
  ["08:00", "08:45"], ["08:50", "09:35"],                    // 1, 2
  ["09:50", "10:35"], ["10:40", "11:25"], ["11:30", "12:15"], // 3, 4, 5
  ["13:30", "14:15"], ["14:20", "15:05"], ["15:10", "15:55"], ["16:00", "16:45"], // 6-9
  ["18:30", "19:15"], ["19:20", "20:05"], ["20:10", "20:55"], // 10, 11, 12
];

const DAY_LABELS: Record<string, string> = {
  星期一: "Monday",
  星期二: "Tuesday",
  星期三: "Wednesday",
  星期四: "Thursday",
  星期五: "Friday",
  星期六: "Saturday",
  星期日: "Sunday",
};

/** 详情段落的开头，例如 `(3-5节)` */
const DETAIL_START = /^\((\d+)-(\d+)节\)/;
const DETAIL_END = "/学分:";
const TM = /^([-\d.]+) ([-\d.]+) ([-\d.]+) ([-\d.]+) ([-\d.]+) ([-\d.]+) Tm$/;
const FIELD = /\/([^:]+):([^/]*)/g;
const WEEKS = /\(\d+-\d+节\)([^/]*)/;

/** 一段文字及其坐标 */
interface Run {
  x: number;
  y: number;
  text: string;
}

/**
 * 按 ISO-8859-1 把字节还原成字符串（每个字节 → 一个同码位字符）。
 *
 * 不能用 `TextDecoder("latin1")`：它实际是 windows-1252，0x80..0x9F 会被映射成
 * €‚ƒ… 这类字符，`charCodeAt` 拿回来就不是原来的字节了。UCS-2 文本正好落在这段区间里。
 */
function latin1(bytes: Uint8Array): string {
  const CHUNK = 0x8000; // 一次 32768 个参数，避开 fromCharCode 的参数上限
  let out = "";
  for (let i = 0; i < bytes.length; i += CHUNK) {
    out += String.fromCharCode(...bytes.subarray(i, i + CHUNK));
  }
  return out;
}

/**
 * zlib 解压（PDF 的 FlateDecode）。失败返回 null —— 不是所有 stream 都是文字流。
 *
 * ⚠️ 不能直接 `new Response(stream).arrayBuffer()`：PDF 的 `stream ... endstream`
 * 之间往往还夹着换行或填充字节，Node/Chromium 的 `DecompressionStream` 会抛
 * **"Trailing junk found after the end of the compressed stream"** 并丢掉全部输出，
 * 而 Java 的 `Inflater`（原版用的）对尾部垃圾是宽容的 —— 结果就是"原版能解析、这边 0 门课"。
 *
 * 这里改成手动读 chunk：解压器会先把数据全部产出，错误发生在**收尾**，
 * 所以把出错前读到的部分拼起来即可，等价于 `Inflater` 的宽容行为。
 * 如果一个字节都没产出，那才是真的不是 zlib（返回 null）。
 */
async function inflate(raw: Uint8Array): Promise<Uint8Array | null> {
  if (raw.length === 0) return new Uint8Array(0);
  try {
    const ds = new DecompressionStream("deflate");
    const writer = ds.writable.getWriter();
    // 写入端可能因为读端已关闭而 reject，吞掉即可
    void writer.write(new Uint8Array(raw)).catch(() => undefined);
    void writer.close().catch(() => undefined);

    const reader = ds.readable.getReader();
    const chunks: Uint8Array[] = [];
    let total = 0;
    for (;;) {
      try {
        const { done, value } = await reader.read();
        if (done) break;
        if (value && value.length > 0) {
          chunks.push(value);
          total += value.length;
        }
      } catch {
        break; // 尾部垃圾：数据已全部拿到，直接采用
      }
    }
    if (total === 0) return null;

    const out = new Uint8Array(total);
    let offset = 0;
    for (const chunk of chunks) {
      out.set(chunk, offset);
      offset += chunk.length;
    }
    return out;
  } catch {
    return null;
  }
}

/** 把所有能解压的内容流解出来 */
async function inflatedContent(pdf: Uint8Array): Promise<string[]> {
  const bytes = pdf;
  const asLatin = latin1(bytes);
  const result: string[] = [];
  let from = 0;
  for (;;) {
    const marker = asLatin.indexOf("stream", from);
    if (marker < 0) break;
    let start = marker + 6;
    if (start < asLatin.length && asLatin[start] === "\r") start++;
    if (start < asLatin.length && asLatin[start] === "\n") start++;
    const end = asLatin.indexOf("endstream", start);
    if (end < 0) break;
    // 注意：字符下标 == 字节下标，因为 latin1 是一字节一字符
    const data = await inflate(bytes.subarray(start, end));
    if (data) result.push(latin1(data));
    from = end + 9;
  }
  return result;
}

/** 还原 PDF 字符串字面量：处理 \\ \( \) 和 \ddd 八进制转义，再按字体编码解码 */
function decodePdfString(body: string, ucs2: boolean): string {
  const bytes: number[] = [];
  let i = 0;
  while (i < body.length) {
    const c = body[i];
    if (c === "\\" && i + 1 < body.length) {
      i++;
      const escaped = body[i];
      switch (escaped) {
        case "n": bytes.push(10); break;
        case "r": bytes.push(13); break;
        case "t": bytes.push(9); break;
        case "b": bytes.push(8); break;
        case "f": bytes.push(12); break;
        case "(":
        case ")":
        case "\\":
          bytes.push(escaped.charCodeAt(0) & 0xff);
          break;
        default:
          if (escaped >= "0" && escaped <= "7") {
            let octal = "";
            while (i < body.length && body[i] >= "0" && body[i] <= "7" && octal.length < 3) {
              octal += body[i];
              i++;
            }
            i--;
            bytes.push(Number.parseInt(octal, 8) & 0xff);
          } else {
            bytes.push(escaped.charCodeAt(0) & 0xff);
          }
      }
    } else {
      bytes.push(c.charCodeAt(0) & 0xff);
    }
    i++;
  }

  if (!ucs2) return latin1(Uint8Array.from(bytes));
  // Type0 / UniGB-UCS2-H：两字节大端
  let sb = "";
  for (let k = 0; k + 1 < bytes.length; k += 2) {
    sb += String.fromCharCode((bytes[k] << 8) | bytes[k + 1]);
  }
  return sb;
}

/** 从内容流里取「带坐标的文字段」 */
function textRuns(content: string, ucs2: boolean): Run[] {
  const runs: Run[] = [];
  let x = 0;
  let y = 0;
  for (const raw of content.split(/\r?\n/)) {
    const line = raw.trim();
    const tm = TM.exec(line);
    if (tm) {
      const nx = Number.parseFloat(tm[5]);
      const ny = Number.parseFloat(tm[6]);
      if (Number.isFinite(nx)) x = nx;
      if (Number.isFinite(ny)) y = ny;
      continue;
    }
    // 只做首尾切分，不用正则匹配正文（内容流里可能出现 0x0B 这类控制字符）
    if (line.length <= 3 || !line.startsWith("(") || !line.endsWith("Tj")) continue;
    const text = decodePdfString(line.slice(1, line.length - 3), ucs2);
    if (text.trim().length > 0) runs.push({ x, y, text });
  }
  return runs;
}

function field(detail: string, name: string): string {
  FIELD.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = FIELD.exec(detail)) !== null) {
    if (m[1] === name) return (m[2] ?? "").trim();
  }
  return "";
}

function toCourse(title: string, detail: string, day: string): ParsedCourse | null {
  if (!title) return null;
  const head = DETAIL_START.exec(detail);
  if (!head) return null;
  const start = Number.parseInt(head[1], 10);
  const end = Number.parseInt(head[2], 10);
  if (!Number.isFinite(start) || !Number.isFinite(end)) return null;
  const startAt = DEFAULT_PERIOD_TIMES[start - 1];
  const endAt = DEFAULT_PERIOD_TIMES[end - 1];
  if (!startAt || !endAt) return null;
  const weeks = WEEKS.exec(detail);
  return {
    title,
    dayOfWeek: day,
    startTime: startAt[0],
    endTime: endAt[1],
    room: field(detail, "场地"),
    teacher: field(detail, "教师"),
    weeks: weeks ? (weeks[1] ?? "").trim() : "",
  };
}

/** 同一列里：若干行标题 + 一段详情 = 一门课 */
function coursesInColumn(lines: string[], day: string): ParsedCourse[] {
  const result: ParsedCourse[] = [];
  let titleLines: string[] = [];
  let i = 0;
  while (i < lines.length) {
    if (!DETAIL_START.test(lines[i])) {
      titleLines.push(lines[i]);
      i++;
      continue;
    }
    let detail = lines[i];
    while (
      !detail.includes(DETAIL_END) &&
      i + 1 < lines.length &&
      !DETAIL_START.test(lines[i + 1])
    ) {
      i++;
      detail += lines[i];
    }
    const title = titleLines.join("").trim().replace(/[◆◇●○□]+$/, "").trim();
    titleLines = [];
    const course = toCourse(title, detail, day);
    if (course) result.push(course);
    i++;
  }
  return result;
}

/** 解析整份课表 PDF。 */
export async function parseTimetablePdf(pdf: Uint8Array): Promise<ParsedCourse[]> {
  // 字体名里带 UCS2 就按 2 字节大端解，否则按单字节
  const ucs2 = latin1(pdf).includes("UCS2");
  const contents = await inflatedContent(pdf);
  const runs: Run[] = [];
  for (const content of contents) runs.push(...textRuns(content, ucs2));
  if (runs.length === 0) return [];

  // 表头「星期一…星期日」的位置
  const dayColumns: [string, number][] = [];
  for (const run of runs) {
    const day = DAY_LABELS[run.text.trim()];
    if (day) dayColumns.push([day, run.x]);
  }
  if (dayColumns.length === 0) return [];

  // 课程列的 x：只有详情段落的行才落在真正的课程列上，
  // 左侧「1..12 节次」数字和页脚说明的 x 都不会命中
  const columnXs = [...new Set(runs.filter((r) => DETAIL_START.test(r.text)).map((r) => r.x))];

  const courses: ParsedCourse[] = [];
  for (const columnX of columnXs) {
    let bestDay: string | null = null;
    let bestDist = Number.POSITIVE_INFINITY;
    for (const [day, dx] of dayColumns) {
      const dist = Math.abs(dx - columnX);
      if (dist < bestDist) {
        bestDist = dist;
        bestDay = day;
      }
    }
    if (!bestDay) continue;
    const lines = runs.filter((r) => Math.abs(r.x - columnX) < 1.0).map((r) => r.text);
    courses.push(...coursesInColumn(lines, bestDay));
  }
  return courses;
}
