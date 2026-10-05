package com.example.classreminder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 今天页便签摘要的挑选规则测试。
 *
 * 两条规则各自的边界都在这里钉住：
 *  1. 排序 —— Deadline 优先，但**同组内维持用户排定的顺序**
 *  2. 截断 —— 超量时留一格给省略栏，且「正好等于上限」不该出现省略栏
 */
class TodayNotePickerTest {

    /** 造一条便签。position 即传入顺序，模拟 DAO 已按 position 排好 */
    private fun note(
        id: Int,
        position: Int,
        typeIndex: Int = NOTE_TYPE_NONE,
        deadlineAt: Long = 0L,
        customLabel: String = ""
    ) = NoteEntity(
        id = id,
        title = "便签 $id",
        position = position,
        colorIndex = 0,
        typeIndex = typeIndex,
        customLabel = customLabel,
        deadlineAt = deadlineAt
    )

    /** 一条 Deadline 便签（内置 Deadline 类，带截止时刻） */
    private fun deadlineNote(id: Int, position: Int, at: Long = 1_000_000L) =
        note(id, position, typeIndex = NOTE_TYPE_DEADLINE, deadlineAt = at)

    /** 一条普通便签（工作） */
    private fun plainNote(id: Int, position: Int) =
        note(id, position, typeIndex = 1)

    // ── 上限的基本行为 ──

    @Test
    fun limitIsFive() {
        assertEquals(5, TodayNotePicker.TODAY_NOTE_LIMIT)
    }

    @Test
    fun fewerThanLimitShowsAll() {
        val notes = (1..3).map { plainNote(it, it) }
        assertEquals(listOf(1, 2, 3), TodayNotePicker.pick(notes).map { it.id })
        assertFalse(TodayNotePicker.needsMoreRow(notes))
    }

    @Test
    fun exactlyAtLimitShowsAllAndNoMoreRow() {
        // 正好 5 条：全都在下面列着了，没有「还有更多」，不该出现省略栏
        val notes = (1..5).map { plainNote(it, it) }
        assertEquals(5, TodayNotePicker.pick(notes).size)
        assertFalse(TodayNotePicker.needsMoreRow(notes))
    }

    @Test
    fun overLimitLeavesOneSlotForMoreRow() {
        // 6 条：留一格给省略栏，所以正文只有 4 条
        val notes = (1..6).map { plainNote(it, it) }
        assertEquals(4, TodayNotePicker.pick(notes).size)
        assertTrue(TodayNotePicker.needsMoreRow(notes))
    }

    @Test
    fun bodyPlusMoreRowNeverExceedsLimit() {
        // 不变量：正文条数 + 省略栏 <= LIMIT，任何规模都成立
        for (n in 1..12) {
            val notes = (1..n).map { plainNote(it, it) }
            val body = TodayNotePicker.pick(notes).size
            val more = if (TodayNotePicker.needsMoreRow(notes)) 1 else 0
            assertTrue("n=$n 时 $body + $more 超过了上限", body + more <= TodayNotePicker.TODAY_NOTE_LIMIT)
        }
    }

    // ── Deadline 优先 ──

    @Test
    fun deadlineNotesComeFirst() {
        // 普通便签在前、Deadline 在后 —— 结果里 Deadline 应该被提前
        val notes = listOf(
            plainNote(1, 1),
            plainNote(2, 2),
            deadlineNote(3, 3),
            deadlineNote(4, 4)
        )
        assertEquals(listOf(3, 4, 1, 2), TodayNotePicker.pick(notes).map { it.id })
    }

    @Test
    fun deadlinePriorityIsStableWithinGroups() {
        // 用户按 position 排过序，组内不能乱 —— 这里是稳定排序的核心保证
        val notes = listOf(
            plainNote(10, 1),
            deadlineNote(11, 2),
            plainNote(12, 3),
            deadlineNote(13, 4),
            plainNote(14, 5)
        )
        assertEquals(listOf(11, 13, 10, 12, 14), TodayNotePicker.pick(notes).map { it.id })
    }

    @Test
    fun deadlineWithoutTimeIsNotPrioritized() {
        // 选了 Deadline 分类但没填时刻：hasDeadline 为 false，不该享受优先 ——
        // 它会落进「非 Deadline」那一组，和其他普通便签**一起**按原顺序排，
        // 而不是被丢到最后（所以这里仍然是 1 在前，和传入顺序一致）
        val noTime = note(1, 1, typeIndex = NOTE_TYPE_DEADLINE, deadlineAt = 0L)
        val normal = plainNote(2, 2)
        assertEquals(listOf(1, 2), TodayNotePicker.pick(listOf(noTime, normal)).map { it.id })

        // 换一下传入顺序，确认它确实没有优先权（若被当成 Deadline，1 会跳到前面）
        assertEquals(listOf(2, 1), TodayNotePicker.pick(listOf(normal, noTime)).map { it.id })
    }

    @Test
    fun onlyScheduledDeadlinesJumpAhead() {
        // 同一批里有填了时间的 Deadline 和没填时间的 —— 只有前者提前
        val timed = deadlineNote(1, 1)
        val untimed = note(2, 2, typeIndex = NOTE_TYPE_DEADLINE, deadlineAt = 0L)
        val normal = plainNote(3, 3)
        assertEquals(listOf(1, 2, 3), TodayNotePicker.pick(listOf(untimed, normal, timed)).map { it.id })
    }

    @Test
    fun customDeadlineTypeIsAlsoPrioritized() {
        val custom = note(
            1, 1,
            typeIndex = 6,              // Deadline 自定义
            deadlineAt = 5_000L,
            customLabel = "开题"
        )
        val normal = plainNote(2, 2)
        assertEquals(listOf(1, 2), TodayNotePicker.pick(listOf(custom, normal)).map { it.id })
    }

    @Test
    fun generalCustomTypeIsNotPrioritized() {
        // 「自定义」是 GENERAL，只是改个名字，不享受优先
        val custom = note(1, 1, typeIndex = 5, customLabel = "买菜")
        val normal = plainNote(2, 2)
        assertEquals(listOf(1, 2), TodayNotePicker.pick(listOf(custom, normal)).map { it.id })
    }

    // ── 超量 + Deadline 优先 组合 ──

    @Test
    fun deadlinesAreKeptWhenOverLimit() {
        // 6 条里前 2 条是 Deadline：超量截断后，正文 4 条必须包含这两条
        val notes = listOf(
            plainNote(1, 1),
            plainNote(2, 2),
            plainNote(3, 3),
            deadlineNote(4, 4),
            deadlineNote(5, 5),
            plainNote(6, 6)
        )
        val picked = TodayNotePicker.pick(notes).map { it.id }
        assertEquals(4, picked.size)
        assertTrue("Deadline 便签被截掉了", picked.containsAll(listOf(4, 5)))
        // 顺序：先 Deadline，再按 position 的普通便签
        assertEquals(listOf(4, 5, 1, 2), picked)
        assertTrue(TodayNotePicker.needsMoreRow(notes))
    }

    @Test
    fun omitsTheTailWhenOverLimit() {
        // 6 条全普通：截断发生在末尾（保留最靠前的 4 条）
        val notes = (1..6).map { plainNote(it, it) }
        assertEquals(listOf(1, 2, 3, 4), TodayNotePicker.pick(notes).map { it.id })
    }

    // ── 空输入 ──

    @Test
    fun emptyInputPicksNothingAndShowsNoMoreRow() {
        assertTrue(TodayNotePicker.pick(emptyList()).isEmpty())
        assertFalse(TodayNotePicker.needsMoreRow(emptyList()))
    }

    @Test
    fun doesNotMutateTheInputList() {
        // pick 内部做了排序，不能把调用方（DAO 的结果）的顺序改掉
        val notes = listOf(plainNote(1, 1), deadlineNote(2, 2))
        val before = notes.map { it.id }
        TodayNotePicker.pick(notes)
        assertEquals(before, notes.map { it.id })
    }
}
