package com.example.classreminder.data.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** [MiniJson] 的解析与生成。纯 JVM，不依赖 Android */
class MiniJsonTest {

    // ── 解析 ────────────────────────────────────────────────────

    @Test
    fun parsesEveryValueKind() {
        val json = """
            {
              "s": "hi",
              "n": 12,
              "f": -3.5,
              "yes": true,
              "no": false,
              "nil": null,
              "arr": [1, 2, 3],
              "obj": { "k": "v" }
            }
        """.trimIndent()

        val o = MiniJson.parse(json) as JsonValue.Obj
        assertEquals("hi", o.str("s"))
        assertEquals(12, o.int("n"))
        assertEquals(-3.5, (o.fields["f"] as JsonValue.Num).value, 0.0001)
        assertEquals(true, o.bool("yes"))
        assertEquals(false, o.bool("no"))
        assertEquals(JsonValue.Null, o.fields["nil"])
        assertEquals(3, o.array("arr").size)
        assertEquals("v", o.objOrNull("obj")?.str("k"))
    }

    @Test
    fun parsesEmptyObjectAndArray() {
        assertEquals(0, (MiniJson.parse("{}") as JsonValue.Obj).fields.size)
        assertEquals(0, (MiniJson.parse("[]") as JsonValue.Arr).items.size)
        assertEquals(0, (MiniJson.parse("  {  }  ") as JsonValue.Obj).fields.size)
    }

    @Test
    fun parsesEscapesInStrings() {
        val o = MiniJson.parse("""{"s":"a\"b\\c\/d\ne\tf\u0041"}""") as JsonValue.Obj
        assertEquals("a\"b\\c/d\ne\tfA", o.str("s"))
    }

    @Test
    fun parsesNestedStructures() {
        val o = MiniJson.parse("""{"a":[{"b":[1,{"c":"d"}]}]}""") as JsonValue.Obj
        val inner = ((o.array("a")[0] as JsonValue.Obj).array("b")[1] as JsonValue.Obj)
        assertEquals("d", inner.str("c"))
    }

    @Test
    fun parsesNegativeAndExponentNumbers() {
        val o = MiniJson.parse("""{"a":-1,"b":1.5e3,"c":-2E-2}""") as JsonValue.Obj
        assertEquals(-1, o.int("a"))
        assertEquals(1500.0, (o.fields["b"] as JsonValue.Num).value, 0.0001)
        assertEquals(-0.02, (o.fields["c"] as JsonValue.Num).value, 0.0001)
    }

    // ── 非法输入：一律抛 IllegalArgumentException，不静默返回半截数据 ──

    @Test
    fun rejectsTrailingContent() {
        assertThrows(IllegalArgumentException::class.java) { MiniJson.parse("{} {}") }
    }

    @Test
    fun rejectsUnterminatedString() {
        assertThrows(IllegalArgumentException::class.java) { MiniJson.parse("{\"a\":\"b") }
    }

    @Test
    fun rejectsUnknownEscape() {
        assertThrows(IllegalArgumentException::class.java) { MiniJson.parse("{\"a\":\"\\x\"}") }
    }

    @Test
    fun rejectsMissingColon() {
        assertThrows(IllegalArgumentException::class.java) { MiniJson.parse("{\"a\" 1}") }
    }

    @Test
    fun rejectsEmptyInput() {
        assertThrows(IllegalArgumentException::class.java) { MiniJson.parse("   ") }
    }

    // ── 生成 ────────────────────────────────────────────────────

    @Test
    fun writesIntegersWithoutDecimalPoint() {
        assertEquals("""{"id":12}""", MiniJson.write(jsonObject("id" to 12.toJson()), pretty = false))
    }

    @Test
    fun writesLargeTimestampsExactly() {
        val millis = 1_700_000_000_000L
        assertEquals(
            """{"t":$millis}""",
            MiniJson.write(jsonObject("t" to millis.toJson()), pretty = false)
        )
    }

    @Test
    fun escapesControlCharacters() {
        assertEquals("\"a\\nb\"", MiniJson.write(JsonValue.Str("a\nb"), pretty = false))
        assertEquals("\"a\\tb\"", MiniJson.write(JsonValue.Str("a\tb"), pretty = false))
        assertEquals("\"a\\\"b\"", MiniJson.write(JsonValue.Str("a\"b"), pretty = false))
    }

    @Test
    fun roundTripKeepsStructure() {
        val original = jsonObject(
            "text" to "中文和 emoji 🎓".toJson(),
            "list" to jsonArray(listOf(1.toJson(), 2.toJson())),
            "flag" to true.toJson()
        )
        assertEquals(original, MiniJson.parse(MiniJson.write(original)))
    }

    @Test
    fun prettyOutputIsIndentedAndStillParses() {
        val original = jsonObject("a" to jsonObject("b" to 1.toJson()))
        val pretty = MiniJson.write(original, pretty = true)
        assertTrue("pretty 输出应当换行缩进", pretty.contains("\n  "))
        assertEquals(original, MiniJson.parse(pretty))
    }

    // ── 访问器的兜底行为：脏数据不该把 UI 打挂 ──

    @Test
    fun accessorsFallBackWhenTypeMismatches() {
        val o = jsonObject("s" to 5.toJson(), "n" to "abc".toJson())
        assertEquals("", o.str("s"))
        assertEquals(7, o.int("n", 7))
        assertEquals(false, o.bool("missing"))
        assertEquals(0, o.array("missing").size)
        assertEquals(null, o.objOrNull("missing"))
    }
}
