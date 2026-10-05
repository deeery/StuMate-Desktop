package com.example.classreminder.data.backup

import com.example.classreminder.data.ClassEntity
import com.example.classreminder.data.NoteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** 备份的编解码与文件校验 */
class BackupCodecTest {

    private val course = ClassEntity(
        id = 7,
        title = "高等数学",
        dayOfWeek = "Monday",
        startTime = "08:00",
        endTime = "09:35",
        room = "教二 305",
        notes = "带教材",
        teacher = "王海燕",
        weeks = "1-16周",
        date = ""
    )

    private val note = NoteEntity(
        id = 3,
        title = "交作业",
        content = "周五前交给王老师，记得带实验报告",
        position = 1,
        createdAt = 1_700_000_000_000L,
        colorIndex = 5,
        typeIndex = 4,
        customLabel = "",
        deadlineAt = 1_800_000_000_000L
    )

    // ── 往返 ────────────────────────────────────────────────────

    @Test
    fun coursesRoundTrip() {
        val back = BackupCodec.decodeCourses(BackupCodec.encodeCourses(listOf(course)))
        assertEquals(listOf(course), back)
    }

    @Test
    fun notesRoundTrip() {
        val back = BackupCodec.decodeNotes(BackupCodec.encodeNotes(listOf(note)))
        assertEquals(listOf(note), back)
    }

    @Test
    fun settingsRoundTrip() {
        val snapshot = SettingsSnapshot(
            advanceMinutes = 45,
            autoStart = true,
            showPopup = false,
            themeMode = 2,
            week1Monday = 1_700_000_000_000L,
            weekGrid = true,
            experimentalGrid = false
        )
        assertEquals(snapshot, BackupCodec.decodeSettings(BackupCodec.encodeSettings(snapshot)))
    }

    @Test
    fun emptyListsRoundTrip() {
        assertTrue(BackupCodec.decodeCourses(BackupCodec.encodeCourses(emptyList())).isEmpty())
        assertTrue(BackupCodec.decodeNotes(BackupCodec.encodeNotes(emptyList())).isEmpty())
    }

    // ── 解码的「能救就救」 ──

    @Test
    fun settingsMissingFieldsFallBackToDefaults() {
        val back = BackupCodec.decodeSettings(jsonObject("themeMode" to 2.toJson()))
        assertEquals(2, back.themeMode)
        assertEquals(SettingsSnapshot().advanceMinutes, back.advanceMinutes)
        assertEquals(SettingsSnapshot().experimentalGrid, back.experimentalGrid)
    }

    @Test
    fun settingsDecodesNonObjectToDefaults() {
        assertEquals(SettingsSnapshot(), BackupCodec.decodeSettings(JsonValue.Null))
    }

    @Test
    fun coursesWithoutTitleAreSkipped() {
        val payload = jsonObject(
            "items" to jsonArray(
                listOf(
                    jsonObject("title" to "".toJson()),
                    jsonObject("id" to 1.toJson()),
                    jsonObject("title" to "有效课".toJson(), "id" to 2.toJson())
                )
            )
        )
        val decoded = BackupCodec.decodeCourses(payload)
        assertEquals(1, decoded.size)
        assertEquals("有效课", decoded.first().title)
    }

    @Test
    fun notesWithBlankTitleAreSkipped() {
        val payload = jsonObject(
            "items" to jsonArray(
                listOf(
                    jsonObject("title" to "   ".toJson()),
                    jsonObject("title" to "有用".toJson())
                )
            )
        )
        val decoded = BackupCodec.decodeNotes(payload)
        assertEquals(1, decoded.size)
        assertEquals("有用", decoded.first().title)
    }

    /**
     * v2 及更早的备份里只有 `text`、没有 `title`。
     * 按约定老便签的全文整条进标题，正文留空 —— 这条测试钉住这个回退，
     * 否则用户导一份旧备份进来会发现便签**全部消失**（标题空 → 被跳过）。
     */
    @Test
    fun legacyBackupWithoutTitleFallsBackToText() {
        val payload = jsonObject(
            "items" to jsonArray(
                listOf(jsonObject("id" to 1.toJson(), "text" to "老便签".toJson()))
            )
        )
        val decoded = BackupCodec.decodeNotes(payload)
        assertEquals(1, decoded.size)
        assertEquals("老便签", decoded.first().title)
        assertEquals("", decoded.first().content)
    }

    /** `title` 存在时以它为准，不去读兼容字段 `text` */
    @Test
    fun titleWinsOverLegacyTextField() {
        val payload = jsonObject(
            "items" to jsonArray(
                listOf(
                    jsonObject(
                        "title" to "新标题".toJson(),
                        "content" to "新正文".toJson(),
                        "text" to "旧镜像".toJson()
                    )
                )
            )
        )
        val decoded = BackupCodec.decodeNotes(payload)
        assertEquals("新标题", decoded.first().title)
        assertEquals("新正文", decoded.first().content)
    }

    /**
     * 编码时**必须**同时写出 `text`（= `title` 的镜像）。
     *
     * 这是给还没升级的旧客户端留的口子：它们的解码器只认 `text`，
     * 且带一个「`text` 为空就跳过这条」的守卫 —— 镜像缺了，旧端会静默丢掉全部便签。
     * 以后要清理这个兼容字段时，先把这条测试删掉，并确认线上没有旧版本在跑。
     */
    @Test
    fun encodedNotesKeepLegacyTextFieldAsTitleMirror() {
        val json = MiniJson.write(BackupCodec.encodeNotes(listOf(note)), pretty = false)
        assertTrue("缺少兼容字段 text", json.contains("\"text\":\"交作业\""))
        assertTrue("缺少 title", json.contains("\"title\":\"交作业\""))
        assertTrue("缺少 content", json.contains("\"content\":\"周五前交给王老师，记得带实验报告\""))
    }

    @Test
    fun missingItemsFieldYieldsEmptyList() {
        assertTrue(BackupCodec.decodeCourses(jsonObject("count" to 3.toJson())).isEmpty())
        assertTrue(BackupCodec.decodeNotes(JsonValue.Null).isEmpty())
    }

    @Test
    fun countOfReportsItemCounts() {
        assertEquals(1, BackupCodec.countOf(BackupModule.COURSES, BackupCodec.encodeCourses(listOf(course))))
        assertEquals(1, BackupCodec.countOf(BackupModule.NOTES, BackupCodec.encodeNotes(listOf(note))))
        assertNull(BackupCodec.countOf(BackupModule.SETTINGS, BackupCodec.encodeSettings(SettingsSnapshot())))
    }

    // ── 文件校验 ────────────────────────────────────────────────

    @Test
    fun rejectsForeignFile() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupDocument.parse("""{"format":"something-else","modules":{}}""")
        }
    }

    @Test
    fun rejectsFileWithoutModulesField() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupDocument.parse("""{"format":"stumate-backup"}""")
        }
    }

    @Test
    fun rejectsFileWithOnlyUnknownModules() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupDocument.parse(
                """{"format":"stumate-backup","modules":{"futureThing":{"a":1}}}"""
            )
        }
    }

    @Test
    fun rejectsNonObjectRoot() {
        assertThrows(IllegalArgumentException::class.java) { BackupDocument.parse("[1,2]") }
    }

    @Test
    fun unknownModuleKeysAreIgnoredButKnownOnesSurvive() {
        val text = """
            { "format": "stumate-backup", "schema": 1, "app": "x", "exportedAt": 1,
              "modules": { "courses": { "items": [] }, "futureThing": { "a": 1 } } }
        """.trimIndent()
        val doc = BackupDocument.parse(text)
        assertEquals(setOf(BackupModule.COURSES), doc.modules.keys)
    }

    @Test
    fun readsMetadataBack() {
        val doc = BackupDocument(
            schema = BackupFormat.SCHEMA,
            app = BackupFormat.APP_NAME,
            exportedAt = 1_700_000_000_000L,
            modules = mapOf(BackupModule.NOTES to BackupCodec.encodeNotes(listOf(note)))
        )
        val back = BackupDocument.parse(doc.toJson())
        assertEquals(BackupFormat.SCHEMA, back.schema)
        assertEquals(BackupFormat.APP_NAME, back.app)
        assertEquals(1_700_000_000_000L, back.exportedAt)
        assertEquals(listOf(note), BackupCodec.decodeNotes(back.modules.getValue(BackupModule.NOTES)))
    }

    @Test
    fun singleModuleAndWholeBackupShareOneFormat() {
        val single = BackupDocument(
            BackupFormat.SCHEMA, BackupFormat.APP_NAME, 1L,
            mapOf(BackupModule.NOTES to BackupCodec.encodeNotes(listOf(note)))
        )
        val whole = BackupDocument(
            BackupFormat.SCHEMA, BackupFormat.APP_NAME, 1L,
            mapOf(
                BackupModule.NOTES to BackupCodec.encodeNotes(listOf(note)),
                BackupModule.COURSES to BackupCodec.encodeCourses(listOf(course))
            )
        )
        // 两份文件的结构完全同形，区别只在 modules 里有几个键
        assertEquals(setOf(BackupModule.NOTES), BackupDocument.parse(single.toJson()).modules.keys)
        assertEquals(
            setOf(BackupModule.COURSES, BackupModule.NOTES),
            BackupDocument.parse(whole.toJson()).modules.keys
        )
    }

    @Test
    fun moduleKeysAreStable() {
        // 这几个 key 是写进文件的契约，改了旧备份就读不出来 —— 用测试钉住
        assertEquals("courses", BackupModule.COURSES.key)
        assertEquals("notes", BackupModule.NOTES.key)
        assertEquals("settings", BackupModule.SETTINGS.key)
        assertEquals(BackupModule.COURSES, BackupModule.byKey("courses"))
        assertNull(BackupModule.byKey("nope"))
    }
}
