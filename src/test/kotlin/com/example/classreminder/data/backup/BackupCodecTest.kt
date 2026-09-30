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
        text = "交作业",
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
    fun notesWithBlankTextAreSkipped() {
        val payload = jsonObject(
            "items" to jsonArray(
                listOf(jsonObject("text" to "   ".toJson()), jsonObject("text" to "有用".toJson()))
            )
        )
        val decoded = BackupCodec.decodeNotes(payload)
        assertEquals(1, decoded.size)
        assertEquals("有用", decoded.first().text)
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
