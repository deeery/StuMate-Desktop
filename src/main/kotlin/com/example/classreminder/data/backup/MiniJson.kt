package com.example.classreminder.data.backup

import kotlin.math.abs
import kotlin.math.floor

/**
 * 一个 JSON 值。
 *
 * 只做 6 种形态，够覆盖备份文件的需要；数字统一用 [Double] 装 ——
 * 备份里的数字只有时间戳（毫秒，约 1.7e12）和几个小整数，
 * Double 的 53 位尾数能精确表示到 9e15，不会丢精度。
 */
sealed interface JsonValue {
    data class Str(val value: String) : JsonValue
    data class Num(val value: Double) : JsonValue
    data class Bool(val value: Boolean) : JsonValue
    object Null : JsonValue
    data class Arr(val items: List<JsonValue>) : JsonValue
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue
}

// ── 构造 ────────────────────────────────────────────────────────

/** 构造对象。用 LinkedHashMap 保住字段顺序，导出的文件读起来跟写的时候一个样 */
fun jsonObject(vararg pairs: Pair<String, JsonValue>): JsonValue.Obj {
    val map = LinkedHashMap<String, JsonValue>(pairs.size)
    pairs.forEach { map[it.first] = it.second }
    return JsonValue.Obj(map)
}

fun jsonArray(items: List<JsonValue>): JsonValue.Arr = JsonValue.Arr(items)

fun String.toJson(): JsonValue.Str = JsonValue.Str(this)
fun Int.toJson(): JsonValue.Num = JsonValue.Num(this.toDouble())
fun Long.toJson(): JsonValue.Num = JsonValue.Num(this.toDouble())
fun Boolean.toJson(): JsonValue.Bool = JsonValue.Bool(this)

// ── 读取 ────────────────────────────────────────────────────────
//
// 下面这些访问器**全部带兜底值、且永不抛异常**。
// 备份文件可能来自旧版本（少字段）、也可能被用户手改过（类型写错），
// 这时候「少一个字段按默认值算」比「整个导入失败」有用得多。

fun JsonValue.Obj.str(key: String, fallback: String = ""): String =
    (fields[key] as? JsonValue.Str)?.value ?: fallback

fun JsonValue.Obj.int(key: String, fallback: Int = 0): Int =
    (fields[key] as? JsonValue.Num)?.value?.toInt() ?: fallback

fun JsonValue.Obj.long(key: String, fallback: Long = 0L): Long =
    (fields[key] as? JsonValue.Num)?.value?.toLong() ?: fallback

fun JsonValue.Obj.bool(key: String, fallback: Boolean = false): Boolean =
    (fields[key] as? JsonValue.Bool)?.value ?: fallback

fun JsonValue.Obj.array(key: String): List<JsonValue> =
    (fields[key] as? JsonValue.Arr)?.items ?: emptyList()

/** 取对象字段；不是对象时返回 null，而不是抛异常 */
fun JsonValue.Obj.objOrNull(key: String): JsonValue.Obj? = fields[key] as? JsonValue.Obj

/**
 * 极简 JSON 读写（纯 Kotlin，不依赖 Android，可直接单测）。
 *
 * **为什么不引第三方库**：本项目构建必须 `--offline`，拉不到 Gson / Moshi /
 * kotlinx-serialization；而备份文件的结构是自己定的、字段固定，用不上通用库的全部能力。
 * 自己实现还多两个好处：解析行为完全可控（脏数据要尽量救，不要直接崩），
 * 以及能在 JVM 上直接跑单测。
 *
 * 支持的子集：对象 / 数组 / 字符串（含 `U+XXXX` 形式的 unicode 转义）/ 数字 / `true` / `false` / `null`。
 * 不支持（JSON 规范里也没有的）：注释、单引号字符串、`NaN` / `Infinity`。
 */
object MiniJson {

    /** 解析。文本非法时抛 [IllegalArgumentException]，调用方负责转成用户能看懂的话 */
    fun parse(text: String): JsonValue = Parser(text).parseDocument()

    /** 生成。[pretty] = true 时按 2 空格缩进，导出后用文本编辑器打开就能读 */
    fun write(value: JsonValue, pretty: Boolean = true): String =
        StringBuilder().also { Writer(it, pretty).writeValue(value, 0) }.toString()

    private class Parser(private val src: String) {
        private var pos = 0

        fun parseDocument(): JsonValue {
            skipWhitespace()
            val value = parseValue()
            skipWhitespace()
            if (pos < src.length) fail("末尾还有多余内容")
            return value
        }

        private fun parseValue(): JsonValue {
            skipWhitespace()
            if (pos >= src.length) fail("内容意外结束")
            return when (src[pos]) {
                '{' -> parseObject()
                '[' -> parseArray()
                '"' -> JsonValue.Str(parseString())
                't' -> { expect("true"); JsonValue.Bool(true) }
                'f' -> { expect("false"); JsonValue.Bool(false) }
                'n' -> { expect("null"); JsonValue.Null }
                else -> parseNumber()
            }
        }

        private fun parseObject(): JsonValue.Obj {
            pos++ // '{'
            val fields = LinkedHashMap<String, JsonValue>()
            skipWhitespace()
            if (peek() == '}') { pos++; return JsonValue.Obj(fields) }
            while (true) {
                skipWhitespace()
                if (peek() != '"') fail("对象的键必须是字符串")
                val key = parseString()
                skipWhitespace()
                if (peek() != ':') fail("键「$key」后面缺少 ':'")
                pos++
                fields[key] = parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    '}' -> { pos++; return JsonValue.Obj(fields) }
                    else -> fail("对象里缺少 ',' 或 '}'")
                }
            }
        }

        private fun parseArray(): JsonValue.Arr {
            pos++ // '['
            val items = ArrayList<JsonValue>()
            skipWhitespace()
            if (peek() == ']') { pos++; return JsonValue.Arr(items) }
            while (true) {
                items += parseValue()
                skipWhitespace()
                when (peek()) {
                    ',' -> pos++
                    ']' -> { pos++; return JsonValue.Arr(items) }
                    else -> fail("数组里缺少 ',' 或 ']'")
                }
            }
        }

        private fun parseString(): String {
            pos++ // 开头的引号
            val sb = StringBuilder()
            while (true) {
                if (pos >= src.length) fail("字符串没有闭合")
                when (val c = src[pos++]) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (pos >= src.length) fail("转义符后面没有字符")
                        when (val esc = src[pos++]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            '/' -> sb.append('/')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                if (pos + 4 > src.length) fail("\\u 转义不完整")
                                val hex = src.substring(pos, pos + 4)
                                val code = hex.toIntOrNull(16)
                                    ?: fail("\\u 后面不是十六进制：$hex")
                                pos += 4
                                sb.append(code.toChar())
                            }
                            else -> fail("不认识的转义：\\$esc")
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        private fun parseNumber(): JsonValue.Num {
            val start = pos
            if (peek() == '-') pos++
            while (pos < src.length && src[pos].isDigit()) pos++
            if (peek() == '.') {
                pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            if (peek() == 'e' || peek() == 'E') {
                pos++
                if (peek() == '+' || peek() == '-') pos++
                while (pos < src.length && src[pos].isDigit()) pos++
            }
            val raw = src.substring(start, pos)
            val parsed = raw.toDoubleOrNull() ?: fail("不是合法的数字：$raw")
            return JsonValue.Num(parsed)
        }

        private fun peek(): Char = if (pos < src.length) src[pos] else '\u0000'

        private fun skipWhitespace() {
            while (pos < src.length && src[pos].isWhitespace()) pos++
        }

        private fun expect(word: String) {
            if (!src.startsWith(word, pos)) fail("期望 '$word'")
            pos += word.length
        }

        private fun fail(message: String): Nothing =
            throw IllegalArgumentException("JSON 解析失败（第 $pos 个字符）：$message")
    }

    private class Writer(private val sb: StringBuilder, private val pretty: Boolean) {

        fun writeValue(value: JsonValue, depth: Int) {
            when (value) {
                is JsonValue.Str -> writeString(value.value)
                is JsonValue.Num -> writeNumber(value.value)
                is JsonValue.Bool -> sb.append(if (value.value) "true" else "false")
                JsonValue.Null -> sb.append("null")
                is JsonValue.Arr -> writeArray(value.items, depth)
                is JsonValue.Obj -> writeObject(value.fields, depth)
            }
        }

        private fun writeArray(items: List<JsonValue>, depth: Int) {
            if (items.isEmpty()) { sb.append("[]"); return }
            sb.append('[')
            items.forEachIndexed { index, item ->
                if (index > 0) sb.append(',')
                newline(depth + 1)
                writeValue(item, depth + 1)
            }
            newline(depth)
            sb.append(']')
        }

        private fun writeObject(fields: Map<String, JsonValue>, depth: Int) {
            if (fields.isEmpty()) { sb.append("{}"); return }
            sb.append('{')
            var first = true
            fields.forEach { (key, value) ->
                if (!first) sb.append(',')
                first = false
                newline(depth + 1)
                writeString(key)
                sb.append(':')
                if (pretty) sb.append(' ')
                writeValue(value, depth + 1)
            }
            newline(depth)
            sb.append('}')
        }

        private fun newline(depth: Int) {
            if (!pretty) return
            sb.append('\n')
            repeat(depth) { sb.append("  ") }
        }

        /** 整数值不带小数点，导出的文件里 `"id": 12` 比 `"id": 12.0` 顺眼 */
        private fun writeNumber(value: Double) {
            if (value.isFinite() && value == floor(value) && abs(value) < 1e15) {
                sb.append(value.toLong().toString())
            } else {
                sb.append(value.toString())
            }
        }

        private fun writeString(value: String) {
            sb.append('"')
            value.forEach { c ->
                when (c) {
                    '"' -> sb.append("\\\"")
                    '\\' -> sb.append("\\\\")
                    '\n' -> sb.append("\\n")
                    '\r' -> sb.append("\\r")
                    '\t' -> sb.append("\\t")
                    '\b' -> sb.append("\\b")
                    '\u000C' -> sb.append("\\f")
                    // 其余控制字符按规范走 unicode 转义（U+XXXX），否则生成的文件不是合法 JSON
                    else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
                }
            }
            sb.append('"')
        }
    }
}
