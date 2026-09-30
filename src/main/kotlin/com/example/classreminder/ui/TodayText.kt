package com.example.classreminder.ui

import java.util.Calendar

/**
 * 「今天」页用到的纯文案函数。
 *
 * 从原来的 `TodayScreen.kt` 里抽出来单独放一个文件：桌面端重做 UI 后，
 * 页面布局整个换掉了，但这几条**规则**（问候语怎么分档、空态怎么说、日期副标题怎么拼）
 * 与平台无关、也有单测钉着，所以原样保留。
 */

/** 今天日期 + 星期 + 周次的那行副标题（给顶栏用） */
fun todaySubtitle(currentWeek: Int?, now: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = now }
    val month = cal.get(Calendar.MONTH) + 1
    val day = cal.get(Calendar.DAY_OF_MONTH)
    val weekday = when (cal.get(Calendar.DAY_OF_WEEK)) {
        Calendar.MONDAY -> "周一"
        Calendar.TUESDAY -> "周二"
        Calendar.WEDNESDAY -> "周三"
        Calendar.THURSDAY -> "周四"
        Calendar.FRIDAY -> "周五"
        Calendar.SATURDAY -> "周六"
        else -> "周日"
    }
    return buildString {
        append("$month 月 $day 日 $weekday")
        if (currentWeek != null) append(" · 第 $currentWeek 周")
    }
}

/**
 * 按当前时刻给出的问候语。
 *
 * 分档按一天的作息节奏，而不是均分 24 小时 —— 均分出来的边界（比如 12:00 一刀切）
 * 会让人觉得「中午 12 点还算上午」很别扭。这里取的是日常口语的边界：
 *
 * - 05:00–08:59 **早上好** —— 起床到出门
 * - 09:00–11:59 **午安**   —— 上午到中午
 * - 12:00–17:59 **下午好** —— 午休后到傍晚
 * - 18:00–22:59 **晚上好** —— 入夜
 * - 23:00–04:59 **夜深了** —— 深夜，语气从问候变成关照
 *
 * 边界值（整点）归**后一档**：08:59 还是早上好，09:00 就切到午安。
 */
fun greetingFor(millis: Long): String {
    val hour = Calendar.getInstance().apply { timeInMillis = millis }
        .get(Calendar.HOUR_OF_DAY)
    return when (hour) {
        in 5..8 -> "早上好。"
        in 9..11 -> "午安。"
        in 12..17 -> "下午好。"
        in 18..22 -> "晚上好。"
        else -> "夜深了。"
    }
}

/**
 * 今天完全没课时 / 今天的课上完了。
 *
 * 副文案有两种口径，按**有没有便签**分流：
 *  - 有便签 → 「好好休息，或者看看便签。」（引导去便签页）
 *  - 没便签 → 「放松一下吧！」
 * [hasClassToday] 为 false 时（今天压根没课）用「享受闲暇的一天」——
 * 「上完了」和「本来就没有」是两种不同的状态，措辞要分开。
 */
internal fun emptyTodayTitle(hasClassToday: Boolean): String =
    if (hasClassToday) "今天的课上完了 🎉" else "今天没有课"

internal fun emptyTodaySubtitle(hasClassToday: Boolean, hasNotes: Boolean): String = when {
    !hasClassToday -> "享受闲暇的一天"
    hasNotes -> "好好休息，或者看看便签。"
    else -> "放松一下吧！"
}

/** 距下一件事还有多久。负数（已经过去了）夹到 0 */
internal fun remainingText(deltaMillis: Long): String {
    val minutes = (deltaMillis / 60_000L).coerceAtLeast(0L)
    return when {
        minutes <= 1L -> "马上开始"
        minutes < 60L -> "$minutes 分钟后"
        else -> "${minutes / 60} 小时 ${minutes % 60} 分钟后"
    }
}
