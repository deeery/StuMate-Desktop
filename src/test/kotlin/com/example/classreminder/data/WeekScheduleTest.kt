package com.example.classreminder.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class WeekScheduleTest {

    private fun at(year: Int, month: Int, day: Int, hour: Int = 0) = Calendar.getInstance().apply {
        set(year, month - 1, day, hour, 0, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // 2026-09-14 是周一，2026-09-15 是周二
    private val monday = at(2026, 9, 14)

    @Test
    fun parsesRangesSinglesAndLists() {
        assertEquals((1..16).toSet(), WeekSchedule.parse("1-16周"))
        assertEquals(setOf(6), WeekSchedule.parse("第6周"))
        assertEquals((1..8).toSet() + (10..12).toSet(), WeekSchedule.parse("1-8周,10-12周"))
        assertEquals(setOf(1, 3, 5), WeekSchedule.parse("1,3,5周"))
        assertEquals((3..15).toSet(), WeekSchedule.parse("3-15周"))
    }

    @Test
    fun parsesOddAndEvenWeeks() {
        assertEquals(setOf(1, 3, 5, 7, 9, 11, 13, 15), WeekSchedule.parse("1-16周(单)"))
        assertEquals(setOf(2, 4, 6, 8, 10, 12, 14, 16), WeekSchedule.parse("1-16周(双)"))
    }

    @Test
    fun unparsableWeeksMeanNoRestriction() {
        assertNull(WeekSchedule.parse(""))
        assertNull(WeekSchedule.parse("待定"))
        // 写不出周次时不能把课藏起来
        assertTrue(WeekSchedule.contains("", 99))
        assertTrue(WeekSchedule.contains("待定", 99))
        assertTrue(WeekSchedule.contains("1-16周", 1))
        assertTrue(WeekSchedule.contains("1-16周", 16))
        assertFalse(WeekSchedule.contains("1-16周", 17))
        assertFalse(WeekSchedule.contains("1-8周", 9))
    }

    @Test
    fun mondayOfNormalizesAnyDayOfWeek() {
        assertEquals(monday, WeekSchedule.mondayOf(at(2026, 9, 14, 8)))   // 周一
        assertEquals(monday, WeekSchedule.mondayOf(at(2026, 9, 15, 15)))  // 周二
        assertEquals(monday, WeekSchedule.mondayOf(at(2026, 9, 19, 23)))  // 周六
        assertEquals(monday, WeekSchedule.mondayOf(at(2026, 9, 20, 6)))   // 周日
        assertEquals(at(2026, 9, 21), WeekSchedule.mondayOf(at(2026, 9, 21, 1)))
    }

    @Test
    fun computesWeekNumberFromCalibration() {
        // 校准：9-14 那一周是第 1 周
        assertEquals(1, WeekSchedule.weekNumber(monday, at(2026, 9, 14, 9)))
        assertEquals(1, WeekSchedule.weekNumber(monday, at(2026, 9, 20, 9)))  // 同一周的周日
        assertEquals(2, WeekSchedule.weekNumber(monday, at(2026, 9, 21, 9)))
        assertEquals(3, WeekSchedule.weekNumber(monday, at(2026, 9, 29, 9)))
        assertEquals(at(2026, 9, 28), WeekSchedule.weekStart(monday, 3))
    }
}
