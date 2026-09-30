package com.example.classreminder.data

import java.util.Calendar

/**
 * 「今天的课」相关的纯计算：HH:mm → 今天的时间戳、还剩几节、是否已全部结束。
 *
 * 前台 Service 的通知文案用这份逻辑，避免各写一套时间换算。
 */
object TodaySchedule {

    private val DAY_NAMES = listOf(
        "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
    )

    /** 日期格式化器（只在单线程调用，用 lazy 即可保证安全） */
    private val DATE_FORMAT = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())

    /** 今天的一次课；起止时间已经换算成今天的时间戳 */
    data class TodayClass(val entity: ClassEntity, val startMillis: Long, val endMillis: Long) {
        fun ongoingAt(now: Long): Boolean = now in startMillis..endMillis

        fun startsWithin(now: Long, windowMillis: Long): Boolean =
            now >= startMillis - windowMillis && now < startMillis

        /** 还没下课 */
        fun remainingAt(now: Long): Boolean = endMillis > now
    }

    /** 设备当前是星期几（Monday…Sunday，跟数据库里存的一致） */
    fun dayNameOf(millis: Long): String {
        val dayOfWeek = Calendar.getInstance().apply { timeInMillis = millis }.get(Calendar.DAY_OF_WEEK)
        return DAY_NAMES[(dayOfWeek + 5) % 7]
    }

    /** 今天要上的课：长期课程按星期+周次筛，临时提醒只认自己的日期；按开始时间排序 */
    fun today(classes: List<ClassEntity>, currentWeek: Int?, now: Long): List<TodayClass> {
        val todayName = dayNameOf(now)
        val todayDate = dateOf(now)
        return classes.asSequence()
            .filter { occursOn(it, todayName, todayDate, currentWeek) }
            .mapNotNull { toTodayClass(it, now) }
            .sortedBy { it.startMillis }
            .toList()
    }

    /** 这门课今天上不上 */
    private fun occursOn(
        entity: ClassEntity,
        todayName: String,
        todayDate: String,
        currentWeek: Int?
    ): Boolean = if (entity.date.isNotEmpty()) {
        // 临时提醒：只在那一天生效一次，跟周次校准无关
        entity.date == todayDate
    } else {
        entity.dayOfWeek == todayName &&
            // 只算本周真的会上课的那些（周次没校准/写不清时不限制）
            (currentWeek == null || WeekSchedule.contains(entity.weeks, currentWeek))
    }

    /** 一次性提醒的日期；不是一次性提醒或格式不对时返回 null */
    fun oneOffDateOf(entity: ClassEntity): Long? =
        if (entity.date.isEmpty()) null else dateMillisOf(entity.date)

    /** 一次性提醒的日期是否落在 [fromMillis, toMillis) 之内（课表按周显示时用） */
    fun occursInRange(entity: ClassEntity, fromMillis: Long, toMillis: Long): Boolean {
        val date = oneOffDateOf(entity) ?: return true
        return date >= fromMillis && date < toMillis
    }

    /** yyyy-MM-dd */
    fun dateOf(millis: Long): String = DATE_FORMAT.format(java.util.Date(millis))

    /** 日期字符串是否合法（yyyy-MM-dd） */
    fun isValidDate(date: String): Boolean = dateMillisOf(date) != null

    /** 某一天是星期几（Monday…Sunday） */
    fun dayNameOfDate(date: String): String? = dateMillisOf(date)?.let { dayNameOf(it) }

    private fun dateMillisOf(date: String): Long? = runCatching {
        DATE_FORMAT.parse(date)?.time
    }.getOrNull()

    /** 今天还没结束的课 —— 空闲时段里的「今日剩余」 */
    fun remaining(today: List<TodayClass>, now: Long): List<TodayClass> =
        today.filter { it.remainingAt(now) }

    /**
     * 「此刻是否空闲」——这是「今天」页主卡的三种状态之一，单独抽出来是为了能单测。
     *
     * 判定口径直接对齐设置里那个**提前提醒窗口**：[advanceMinutes] 的语义本来就是
     * 「离上课还有 N 分钟就该提示我了」。所以「还没进预警窗」就等于「现在确实还闲着」。
     *
     * 三个必要条件，缺一不可：
     *  1. 现在没有课正在进行（有课就是「正在上课」，不该说空闲）
     *  2. 今天还有没上完的课（全上完了是另一种状态，走「今天的课上完了」）
     *  3. 离最近那节还没开始的课，间隔 **严格大于** 预警窗口
     *
     * 边界取严格大于：正好剩 30 分钟而预警也是 30 分钟时，应该已经开始提示了，
     * 不该同时说「空闲」—— 那和提醒业务是矛盾的。
     *
     * @param upcoming 今天还没结束的课（升序）。传 [remaining] 的结果即可。
     */
    fun isIdle(upcoming: List<TodayClass>, now: Long, advanceMinutes: Int): Boolean {
        if (upcoming.any { it.ongoingAt(now) }) return false
        val next = upcoming.firstOrNull { it.startMillis > now } ?: return false
        val window = advanceMinutes.coerceAtLeast(0).toLong() * 60_000L
        return (next.startMillis - now) > window
    }

    private fun toTodayClass(entity: ClassEntity, now: Long): TodayClass? {
        val startMinutes = minutesOf(entity.startTime) ?: return null
        val endMinutes = minutesOf(entity.endTime) ?: return null

        // 复用同一个 Calendar 实例：每次设置后 timeInMillis 自动更新，不需要重新 getInstance
        val cal = Calendar.getInstance().apply { timeInMillis = now }
        fun at(minutes: Int): Long {
            cal.set(Calendar.HOUR_OF_DAY, minutes / 60)
            cal.set(Calendar.MINUTE, minutes % 60)
            cal.set(Calendar.SECOND, 0)
            cal.set(Calendar.MILLISECOND, 0)
            return cal.timeInMillis
        }

        val startMillis = at(startMinutes)
        var endMillis = at(endMinutes)
        // 跨零点的课（end <= start）算到第二天
        if (endMillis <= startMillis) endMillis += 24L * 60L * 60L * 1000L
        return TodayClass(entity, startMillis, endMillis)
    }

    private fun minutesOf(hhmm: String): Int? {
        val parts = hhmm.split(":")
        val hour = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val minute = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return if (hour in 0..23 && minute in 0..59) hour * 60 + minute else null
    }
}
