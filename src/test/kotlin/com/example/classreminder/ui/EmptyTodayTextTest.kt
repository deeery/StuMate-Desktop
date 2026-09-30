package com.example.classreminder.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「今天页空态卡」文案分流的测试。
 *
 * 三句话各管一种状态，混淆了用户就会看到前后矛盾的提示：
 *
 * | 今天有课 | 有便签 | 标题              | 副文案                  |
 * |---------|-------|-------------------|------------------------|
 * | 是      | 是    | 今天的课上完了 🎉 | 好好休息，或者看看便签。 |
 * | 是      | 否    | 今天的课上完了 🎉 | 放松一下吧！            |
 * | 否      | 是    | 今天没有课        | 享受闲暇的一天          |
 * | 否      | 否    | 今天没有课        | 享受闲暇的一天          |
 *
 * 注意最后两行：**今天压根没课**时不看便签，因为「享受闲暇的一天」本身已经
 * 把话说完了，再往上叠「看看便签」就啰嗦了。
 */
class EmptyTodayTextTest {

    @Test
    fun titleSaysClassesAreOverOnlyWhenThereWereClasses() {
        assertEquals("今天的课上完了 🎉", emptyTodayTitle(hasClassToday = true))
        assertEquals("今天没有课", emptyTodayTitle(hasClassToday = false))
    }

    @Test
    fun subtitleWithNotesPointsToNotesPage() {
        assertEquals("好好休息，或者看看便签。", emptyTodaySubtitle(hasClassToday = true, hasNotes = true))
    }

    @Test
    fun subtitleWithoutNotesDropsTheHint() {
        // 「或者看看便签」是给有便签的人看的 —— 没便签就别指路了
        assertEquals("放松一下吧！", emptyTodaySubtitle(hasClassToday = true, hasNotes = false))
    }

    @Test
    fun noClassTodayIgnoresNotes() {
        // 今天没课：有没有便签都是同一句话
        assertEquals("享受闲暇的一天", emptyTodaySubtitle(hasClassToday = false, hasNotes = true))
        assertEquals("享受闲暇的一天", emptyTodaySubtitle(hasClassToday = false, hasNotes = false))
    }

    @Test
    fun subtitleAlwaysEndsWithProperPunctuation() {
        // 「好好休息，或者看看便签。」这句是这轮特意补的句号，钉一下别被改回去
        val noted = emptyTodaySubtitle(hasClassToday = true, hasNotes = true)
        assertEquals(true, noted.endsWith("。"))
        assertEquals(true, emptyTodaySubtitle(hasClassToday = true, hasNotes = false).endsWith("！"))
    }
}
