package com.example.classreminder.data

import com.example.classreminder.ui.greetingFor
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar

/**
 * 顶栏问候语的分档。
 *
 * 重点是**边界**：每一档的起始整点和前一档的末尾一分钟必须落在不同档里，
 * 否则用户会看到「9 点整还显示早上好」这种别扭的现场。
 */
class GreetingTest {

    private fun at(hour: Int, minute: Int = 0): Long = Calendar.getInstance().apply {
        set(2026, Calendar.SEPTEMBER, 29, hour, minute, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    @Test
    fun earlyMorning() {
        // 05:00–08:59
        assertEquals("早上好。", greetingFor(at(5)))
        assertEquals("早上好。", greetingFor(at(7, 30)))
        assertEquals("早上好。", greetingFor(at(8, 59)))
    }

    @Test
    fun lateMorning() {
        // 09:00–11:59 —— 用户指定这一档叫「午安」
        assertEquals("午安。", greetingFor(at(9)))
        assertEquals("午安。", greetingFor(at(11, 59)))
    }

    @Test
    fun afternoon() {
        // 12:00–17:59
        assertEquals("下午好。", greetingFor(at(12)))
        assertEquals("下午好。", greetingFor(at(15, 20)))
        assertEquals("下午好。", greetingFor(at(17, 59)))
    }

    @Test
    fun evening() {
        // 18:00–22:59
        assertEquals("晚上好。", greetingFor(at(18)))
        assertEquals("晚上好。", greetingFor(at(21)))
        assertEquals("晚上好。", greetingFor(at(22, 59)))
    }

    @Test
    fun lateNight() {
        // 23:00–04:59 —— 跨过午夜，跨天也要连上
        assertEquals("夜深了。", greetingFor(at(23)))
        assertEquals("夜深了。", greetingFor(at(23, 59)))
        assertEquals("夜深了。", greetingFor(at(0)))
        assertEquals("夜深了。", greetingFor(at(3, 15)))
        assertEquals("夜深了。", greetingFor(at(4, 59)))
    }

    @Test
    fun boundariesSwitchToNextBucket() {
        // 整点归后一档：到点就换，不该有「差一分钟还写着上一档」的滞留感
        assertEquals("早上好。", greetingFor(at(8, 59)))
        assertEquals("午安。", greetingFor(at(9)))

        assertEquals("午安。", greetingFor(at(11, 59)))
        assertEquals("下午好。", greetingFor(at(12)))

        assertEquals("下午好。", greetingFor(at(17, 59)))
        assertEquals("晚上好。", greetingFor(at(18)))

        assertEquals("晚上好。", greetingFor(at(22, 59)))
        assertEquals("夜深了。", greetingFor(at(23)))

        assertEquals("夜深了。", greetingFor(at(4, 59)))
        assertEquals("早上好。", greetingFor(at(5)))
    }

    @Test
    fun coversEveryHourOfDay() {
        // 全天 24 小时都必须有档可落，不能出现空白或抛异常
        val valid = setOf("早上好。", "午安。", "下午好。", "晚上好。", "夜深了。")
        (0..23).forEach { hour ->
            val text = greetingFor(at(hour))
            assertEquals("$hour 点没有落进任何一档", true, text in valid)
        }
    }
}
