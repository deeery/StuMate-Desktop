package com.example.classreminder.data

import java.util.Calendar

/**
 * 周次工具：解析课表的「周数」文本，并换算学期周次。
 *
 * 支持教务系统和手写里常见的写法：
 *  - `1-16周`、`第6周`、`1-8周,10-12周`、`1,3,5周`
 *  - 单双周：`1-16周(单)`、`2-16周(双)`
 *
 * 解析不出周次时返回 null，含义是「不确定」——此时不限制显示和提醒，
 * 免得用户随手写点别的备注就把整门课隐藏掉。
 */
object WeekSchedule {

    private const val WEEK_MILLIS = 7L * 24 * 60 * 60 * 1000
    private const val MAX_WEEK = 60

    private val RANGE = Regex("(\\d+)\\s*-\\s*(\\d+)")
    private val SINGLE = Regex("\\d+")

    /** ThreadLocal Calendar：避免每次新建实例的同步锁开销 */
    private val threadLocalCalendar = object : ThreadLocal<Calendar>() {
        override fun initialValue(): Calendar = Calendar.getInstance()
    }

    /** 解析周次集合；文本里没有可用的周次数字时返回 null */
    fun parse(text: String): Set<Int>? {
        if (text.isBlank()) return null
        val weeks = sortedSetOf<Int>()
        // 先把区间吃掉，剩下的数字按单周算，避免 "1-16" 里的 1、16 又被当成单周重复统计
        var rest = text
        RANGE.findAll(text).forEach { match ->
            val from = match.groupValues[1].toIntOrNull() ?: return@forEach
            val to = match.groupValues[2].toIntOrNull() ?: return@forEach
            if (from in 1..MAX_WEEK && to in from..MAX_WEEK) for (week in from..to) weeks += week
            rest = rest.replace(match.value, " ")
        }
        SINGLE.findAll(rest).forEach { match ->
            match.value.toIntOrNull()?.takeIf { it in 1..MAX_WEEK }?.let { weeks += it }
        }
        if (weeks.isEmpty()) return null

        val oddOnly = text.contains("单")
        val evenOnly = text.contains("双")
        val filtered = weeks.filter { week ->
            when {
                oddOnly -> week % 2 == 1
                evenOnly -> week % 2 == 0
                else -> true
            }
        }
        return filtered.toSortedSet().ifEmpty { null }
    }

    /** 这门课在第 [week] 周是否有课；周次写不出来时一律算「有」 */
    fun contains(weeksText: String, week: Int): Boolean = parse(weeksText)?.contains(week) ?: true

    /** 把任意时刻归一到它所在那一周的周一 00:00 */
    fun mondayOf(millis: Long): Long {
        // 避免每次调用都新建 Calendar（内部有同步锁），用 ThreadLocal 提升吞吐
        val cal = threadLocalCalendar.get()
        cal.timeInMillis = millis
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        // Calendar: 周日=1 … 周六=7 → 换算成「距离本周周一几天」
        cal.add(Calendar.DAY_OF_MONTH, -((cal.get(Calendar.DAY_OF_WEEK) + 5) % 7))
        return cal.timeInMillis
    }

    /** 第 [week] 周的周一（[week1Monday] 是第 1 周的周一） */
    fun weekStart(week1Monday: Long, week: Int): Long = week1Monday + (week - 1) * WEEK_MILLIS

    /** 今天是第几周（[week1Monday] 是第 1 周的周一） */
    fun weekNumber(week1Monday: Long, today: Long): Int =
        ((mondayOf(today) - mondayOf(week1Monday)) / WEEK_MILLIS).toInt() + 1
}
