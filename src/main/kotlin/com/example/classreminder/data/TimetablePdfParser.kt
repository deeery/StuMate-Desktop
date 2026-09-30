package com.example.classreminder.data

import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * 教务系统课表 PDF 解析器（example.pdf 那种格式）。
 *
 * 目标格式特征：
 *  - iText 生成，Rotate 90 的表格，2 页；
 *  - 内容流是 FlateDecode，解压后是 `x y ... Tm`（文字坐标）+ `(...)Tj`（文字）指令；
 *  - 字体是 Type0 / UniGB-UCS2-H，字符串按 2 字节大端 UCS-2 解码；
 *  - 每个"星期"列里的课程格 = 课程名（可能折成 2 行）+ 一段以 `(3-5节)` 开头、
 *    以 `/学分:x` 结尾的详情；折行是定宽硬折行，直接拼接即可还原。
 *
 * 只用 java.util.zip + 正则，不引第三方 PDF 库。
 */
object TimetablePdfParser {

    /** 解析出的一门课在某一天的一次课 */
    data class Course(
        val title: String,
        val dayOfWeek: String,
        val startTime: String,
        val endTime: String,
        val room: String,
        val teacher: String,
        val weeks: String
    ) {
        /** 转成数据库行；教师和周次现在是独立字段，能编辑也能按周次过滤课表 */
        fun toEntity(id: Int) = ClassEntity(
            id = id,
            title = title,
            dayOfWeek = dayOfWeek,
            startTime = startTime,
            endTime = endTime,
            room = room,
            teacher = teacher,
            weeks = weeks
        )
    }

    /**
     * PDF 里只有"第几节"，没有具体时刻，只能按常见作息推算。
     * 导入后可以在列表里逐条改时间。
     */
    private val DEFAULT_PERIOD_TIMES = listOf(
        "08:00" to "08:45", "08:50" to "09:35",                    // 1, 2
        "09:50" to "10:35", "10:40" to "11:25", "11:30" to "12:15", // 3, 4, 5
        "13:30" to "14:15", "14:20" to "15:05", "15:10" to "15:55", "16:00" to "16:45", // 6-9
        "18:30" to "19:15", "19:20" to "20:05", "20:10" to "20:55"  // 10, 11, 12
    )

    private val DAY_LABELS = mapOf(
        "星期一" to "Monday", "星期二" to "Tuesday", "星期三" to "Wednesday",
        "星期四" to "Thursday", "星期五" to "Friday", "星期六" to "Saturday", "星期日" to "Sunday"
    )

    /** 详情段落的开头，例如 `(3-5节)` */
    private val DETAIL_START = Regex("^\\((\\d+)-(\\d+)节\\)")
    private val DETAIL_END = "/学分:"
    private val TM = Regex("^([-\\d.]+) ([-\\d.]+) ([-\\d.]+) ([-\\d.]+) ([-\\d.]+) ([-\\d.]+) Tm$")
    private val FIELD = Regex("/([^:]+):([^/]*)")
    private val WEEKS = Regex("\\(\\d+-\\d+节\\)([^/]*)")

    /** 一段文字及其坐标 */
    private data class Run(val x: Double, val y: Double, val text: String)

    fun parse(pdf: ByteArray): List<Course> {
        val ucs2 = String(pdf, Charsets.ISO_8859_1).contains("UCS2")
        val runs = inflatedContent(pdf).flatMap { textRuns(it, ucs2) }
        if (runs.isEmpty()) return emptyList()

        // 表头「星期一…星期日」的位置
        val dayColumns = runs.mapNotNull { run ->
            DAY_LABELS[run.text.trim()]?.let { it to run.x }
        }
        if (dayColumns.isEmpty()) return emptyList()

        // 课程列的 x：只有详情段落的行才落在真正的课程列上，
        // 左侧「1..12 节次」数字和页脚说明的 x 都不会命中
        val columnXs = runs.filter { DETAIL_START.containsMatchIn(it.text) }
            .map { it.x }
            .distinct()

        val courses = mutableListOf<Course>()
        for (columnX in columnXs) {
            val day = dayColumns.minByOrNull { kotlin.math.abs(it.second - columnX) }?.first ?: continue
            val lines = runs.filter { kotlin.math.abs(it.x - columnX) < 1.0 }.map { it.text }
            courses += coursesInColumn(lines, day)
        }
        return courses
    }

    /** 同一列里：若干行标题 + 一段详情 = 一门课 */
    private fun coursesInColumn(lines: List<String>, day: String): List<Course> {
        val result = mutableListOf<Course>()
        var titleLines = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            if (!DETAIL_START.containsMatchIn(lines[i])) {
                titleLines += lines[i]
                i++
                continue
            }
            val detail = StringBuilder(lines[i])
            while (!detail.contains(DETAIL_END) && i + 1 < lines.size && !DETAIL_START.containsMatchIn(lines[i + 1])) {
                i++
                detail.append(lines[i])
            }
            val title = titleLines.joinToString("")
                .trim()
                .trimEnd('◆', '◇', '●', '○', '□')
                .trim()
            titleLines = mutableListOf()
            toCourse(title, detail.toString(), day)?.let { result += it }
            i++
        }
        return result
    }

    private fun toCourse(title: String, detail: String, day: String): Course? {
        if (title.isEmpty()) return null
        val head = DETAIL_START.find(detail) ?: return null
        val start = head.groupValues[1].toIntOrNull() ?: return null
        val end = head.groupValues[2].toIntOrNull() ?: return null
        val startAt = DEFAULT_PERIOD_TIMES.getOrNull(start - 1) ?: return null
        val endAt = DEFAULT_PERIOD_TIMES.getOrNull(end - 1) ?: return null
        return Course(
            title = title,
            dayOfWeek = day,
            startTime = startAt.first,
            endTime = endAt.second,
            room = field(detail, "场地"),
            teacher = field(detail, "教师"),
            weeks = WEEKS.find(detail)?.groupValues?.get(1)?.trim().orEmpty()
        )
    }

    private fun field(detail: String, name: String): String =
        FIELD.findAll(detail).firstOrNull { it.groupValues[1] == name }?.groupValues?.get(2)?.trim().orEmpty()

    // ── PDF 底层：解压内容流、取带坐标的文字 ──────────────────────────

    private fun inflatedContent(pdf: ByteArray): List<String> {
        val latin = String(pdf, Charsets.ISO_8859_1)
        val result = mutableListOf<String>()
        var from = 0
        while (true) {
            val marker = latin.indexOf("stream", from)
            if (marker < 0) break
            var start = marker + 6
            if (start < latin.length && latin[start] == '\r') start++
            if (start < latin.length && latin[start] == '\n') start++
            val end = latin.indexOf("endstream", start)
            if (end < 0) break
            val data = runCatching { inflate(pdf.copyOfRange(start, end)) }.getOrNull()
            if (data != null) result += String(data, Charsets.ISO_8859_1)
            from = end + 9
        }
        return result
    }

    /** FlateDecode 就是 zlib 流（含 2 字节头和尾部校验），Inflater 默认按 zlib 解析即可 */
    private fun inflate(raw: ByteArray): ByteArray {
        if (raw.isEmpty()) return ByteArray(0)
        val inflater = Inflater()
        try {
            inflater.setInput(raw)
            val out = ByteArrayOutputStream(raw.size * 4)
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun textRuns(content: String, ucs2: Boolean): List<Run> {
        val runs = mutableListOf<Run>()
        var x = 0.0
        var y = 0.0
        for (raw in content.lineSequence()) {
            val line = raw.trim()
            val tm = TM.find(line)
            if (tm != null) {
                x = tm.groupValues[5].toDoubleOrNull() ?: x
                y = tm.groupValues[6].toDoubleOrNull() ?: y
                continue
            }
            // 故意不用正则：内容流里会出现 0x0B 这类控制字符（UCS-2 文本的某个字节），
            // Android(ICU) 的 "." 不匹配它们、JVM 的 "." 匹配 —— 用 .* 抓正文会在设备上静默丢行。
            // 反正这里只是「去掉首尾的 ( 和 )Tj」，直接切字符串最稳。
            if (line.length <= 3 || !line.startsWith("(") || !line.endsWith("Tj")) continue
            val text = decodePdfString(line.substring(1, line.length - 3), ucs2)
            if (text.isNotBlank()) runs += Run(x, y, text)
        }
        return runs
    }

    /** 还原 PDF 字符串字面量：处理 \\ \\( \\) 和 \\ddd 八进制转义，再按字体编码解码 */
    private fun decodePdfString(body: String, ucs2: Boolean): String {
        val bytes = ArrayList<Byte>(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length) {
                i++
                when (val escaped = body[i]) {
                    'n' -> bytes += 10
                    'r' -> bytes += 13
                    't' -> bytes += 9
                    'b' -> bytes += 8
                    'f' -> bytes += 12
                    '(', ')', '\\' -> bytes += escaped.code.toByte()
                    else -> if (escaped in '0'..'7') {
                        var octal = ""
                        while (i < body.length && body[i] in '0'..'7' && octal.length < 3) {
                            octal += body[i]
                            i++
                        }
                        i--
                        bytes += octal.toInt(8).toByte()
                    } else {
                        bytes += escaped.code.toByte()
                    }
                }
            } else {
                bytes += c.code.toByte()
            }
            i++
        }
        if (!ucs2) return String(bytes.toByteArray(), Charsets.ISO_8859_1)
        val sb = StringBuilder(bytes.size / 2)
        var k = 0
        while (k + 1 < bytes.size) {
            val code = ((bytes[k].toInt() and 0xFF) shl 8) or (bytes[k + 1].toInt() and 0xFF)
            sb.append(code.toChar())
            k += 2
        }
        return sb.toString()
    }
}
