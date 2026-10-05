package com.example.classreminder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * 便签分类与 Deadline 倒计时的纯逻辑测试。
 *
 * 这两块都是「不依赖 Android 的计算」——分类是下标到语义的映射，
 * 倒计时是时间差到人话文案的映射。它们最容易出错的地方不是逻辑本身，
 * 而是**边界**：整点该归哪一档、刚过期算不算过期、自定义类没填名字怎么办。
 * 所以下面每个函数都专门钉了边界。
 */
class NoteTypeTest {

    // ── 分类表本身 ──────────────────────────────────────────────

    @Test
    fun `第 0 项是空分类，老数据迁移后语义正确`() {
        // v6 及以前没有 typeIndex 列，迁移时补 0 —— 必须正好是「无分类」，
        // 否则所有老便签升级后都会凭空挂上一个标签
        assertEquals(NoteTypeKind.NONE, NOTE_TYPES[NOTE_TYPE_NONE].kind)
        assertEquals("空", NOTE_TYPES[NOTE_TYPE_NONE].label)
    }

    @Test
    fun `分类表顺序即下标，并且至少包含一般的三类与两档自定义`() {
        val labels = NOTE_TYPES.map { it.label }
        assertEquals("工作", labels[1])
        assertEquals("生活", labels[2])
        assertEquals("学习", labels[3])
        assertEquals("Deadline", labels[NOTE_TYPE_DEADLINE])
        assertTrue(NOTE_TYPES.any { it.editableLabel && it.kind != NoteTypeKind.DEADLINE })
        assertTrue(NOTE_TYPES.any { it.editableLabel && it.kind == NoteTypeKind.DEADLINE })
    }

    @Test
    fun `只有 Deadline 类带截止语义`() {
        val deadlineCount = NOTE_TYPES.count { it.kind == NoteTypeKind.DEADLINE }
        assertEquals(2, deadlineCount)  // 「Deadline」与「Deadline 自定义」
    }

    @Test
    fun `下标越界时收敛到合法范围，不打挂 UI`() {
        assertEquals(NOTE_TYPES.first(), noteTypeAt(-5))
        assertEquals(NOTE_TYPES.last(), noteTypeAt(999))
    }

    // ── 分类名的显示 ────────────────────────────────────────────

    @Test
    fun `无分类的便签不显示任何分类名`() {
        val note = noteOf(typeIndex = NOTE_TYPE_NONE)
        assertEquals("", note.typeLabel())
    }

    @Test
    fun `内置分类直接用分类表里的名字`() {
        assertEquals("工作", noteOf(typeIndex = 1).typeLabel())
        assertEquals("Deadline", noteOf(typeIndex = NOTE_TYPE_DEADLINE).typeLabel())
    }

    @Test
    fun `自定义分类显示用户填的名字`() {
        val custom = NOTE_TYPES.indexOfFirst { it.editableLabel && it.kind == NoteTypeKind.GENERAL }
        assertEquals("科研", noteOf(typeIndex = custom, customLabel = "科研").typeLabel())
    }

    @Test
    fun `自定义分类没填名字时回落到占位示例，不会显示空白`() {
        val custom = NOTE_TYPES.indexOfFirst { it.editableLabel && it.kind == NoteTypeKind.GENERAL }
        assertEquals("自定义", noteOf(typeIndex = custom, customLabel = "").typeLabel())
        assertEquals("自定义", noteOf(typeIndex = custom, customLabel = "   ").typeLabel())
    }

    // ── hasDeadline：必须同时满足「是 Deadline 类」且「设了时刻」 ──

    @Test
    fun `Deadline 类设了时刻才算有截止时间`() {
        val at = System.currentTimeMillis() + 3_600_000L
        assertTrue(noteOf(typeIndex = NOTE_TYPE_DEADLINE, deadlineAt = at).hasDeadline)
    }

    @Test
    fun `Deadline 类没设时刻时不算有截止时间`() {
        assertFalse(noteOf(typeIndex = NOTE_TYPE_DEADLINE, deadlineAt = 0L).hasDeadline)
    }

    @Test
    fun `非 Deadline 类即使带着时刻也不算有截止时间`() {
        // 这是防脏数据的那道闸：库里可能有「切回工作类但时刻没清干净」的历史数据
        val at = System.currentTimeMillis() + 3_600_000L
        assertFalse(noteOf(typeIndex = 1, deadlineAt = at).hasDeadline)
    }

    // ── 倒计时文案 ──────────────────────────────────────────────

    @Test
    fun `已过期按超过一天与否分两档`() {
        val now = 1_700_000_000_000L
        assertEquals("已过期", deadlineCountdown(now - 60_000L, now))
        assertEquals("已过期", deadlineCountdown(now - 23 * 3_600_000L, now))
        assertEquals("已过期 2 天", deadlineCountdown(now - 2 * 86_400_000L - 60_000L, now))
    }

    @Test
    fun `正好到点算已过期而不是即将到期`() {
        val now = 1_700_000_000_000L
        assertEquals("已过期", deadlineCountdown(now, now))
    }

    @Test
    fun `不到一分钟显示即将到期`() {
        val now = 1_700_000_000_000L
        assertEquals("即将到期", deadlineCountdown(now + 1L, now))
        assertEquals("即将到期", deadlineCountdown(now + 59_000L, now))
    }

    @Test
    fun `一小时内按分钟显示`() {
        val now = 1_700_000_000_000L
        assertEquals("1 分钟后", deadlineCountdown(now + 60_000L, now))
        assertEquals("59 分钟后", deadlineCountdown(now + 59 * 60_000L, now))
    }

    @Test
    fun `一天内按小时加分钟显示，整小时时省略分钟`() {
        val now = 1_700_000_000_000L
        assertEquals("1 小时后", deadlineCountdown(now + 3_600_000L, now))
        assertEquals("2 小时 30 分后", deadlineCountdown(now + 2 * 3_600_000L + 30 * 60_000L, now))
        assertEquals("23 小时后", deadlineCountdown(now + 23 * 3_600_000L, now))
    }

    @Test
    fun `一天到三十天之间按天显示`() {
        val now = 1_700_000_000_000L
        assertEquals("1 天后", deadlineCountdown(now + 86_400_000L, now))
        assertEquals("29 天后", deadlineCountdown(now + 29 * 86_400_000L, now))
    }

    @Test
    fun `超过三十天按月显示`() {
        val now = 1_700_000_000_000L
        assertEquals("1 个月后", deadlineCountdown(now + 30 * 86_400_000L, now))
        assertEquals("2 个月后", deadlineCountdown(now + 75 * 86_400_000L, now))
    }

    // ── 截止时刻文案 ────────────────────────────────────────────

    @Test
    fun `同年只显示月日时分`() {
        val now = millis(2026, 9, 29, 12, 0)
        val deadline = millis(2026, 12, 31, 23, 59)
        assertEquals("12/31 23:59", deadlineTimeLabel(deadline, now))
    }

    @Test
    fun `跨年时补上年份，避免看成今年`() {
        val now = millis(2026, 9, 29, 12, 0)
        val deadline = millis(2027, 1, 5, 9, 30)
        assertEquals("2027/1/5 09:30", deadlineTimeLabel(deadline, now))
    }

    @Test
    fun `分钟补零，小时超过十点时也补零`() {
        val now = millis(2026, 9, 29, 12, 0)
        assertEquals("9/30 08:05", deadlineTimeLabel(millis(2026, 9, 30, 8, 5), now))
    }

    // ── 辅助 ────────────────────────────────────────────────────

    private fun noteOf(
        typeIndex: Int = NOTE_TYPE_NONE,
        customLabel: String = "",
        deadlineAt: Long = 0L
    ) = NoteEntity(
        id = 1,
        title = "测试",
        position = 0,
        typeIndex = typeIndex,
        customLabel = customLabel,
        deadlineAt = deadlineAt
    )

    private fun millis(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month - 1)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
