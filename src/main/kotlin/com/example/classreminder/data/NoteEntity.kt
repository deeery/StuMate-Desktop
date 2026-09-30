package com.example.classreminder.data

import java.util.Calendar
import java.util.Locale

/**
 * 快速便签。
 *
 * [position] 是便签在列表里的显示顺序（越小越靠上）：新增时取「当前最小值 - 1」，
 * 于是新便签天然排在最顶端；拖动排序时整批重写。
 * [createdAt] 只作为 position 相同时的稳定兜底，不参与排序语义。
 *
 * [colorIndex] 是便签左侧竖线的颜色，取值是调色盘的下标（0..7）。
 * **存下标而不是 ARGB**：调色盘是一份受控的固定色板，用户是在给定选项里挑，
 * 而不是自由取色；存下标能让「同一份数据在浅色/深色主题下各自取到合适的色值」
 * （见 NoteColors），将来要微调某个颜色的明度也不必改库。
 * 老数据（v5 及以前）没有这一列，迁移时统一补 0，即调色盘第一格。
 *
 * [typeIndex] 是便签的**分类**，取值是 [NOTE_TYPES] 的下标（默认 0 = 空）。
 * 同样存下标而不是字符串：分类是受控枚举，存下标才能让将来新增分类、
 * 或做多语言（分类名要翻译）时不至于要迁移全表。
 *
 * [customLabel] 只给「自定义 / Deadline 自定义」两类用 —— 用户自己起的名字。
 * 其余分类这一列恒为空字符串。
 *
 * [deadlineAt] 是 Deadline 类的截止时刻（epoch 毫秒）。**0 = 没设**。
 * 非 Deadline 类恒为 0；Deadline 类没填时间时也允许为 0（只是不显示倒计时）。
 *
 * 桌面端去掉了 Room 注解，字段与顺序与安卓端 `notes` 表逐列一致。
 */
data class NoteEntity(
    val id: Int,
    val text: String,
    val position: Int,
    val createdAt: Long = 0L,
    val colorIndex: Int = DEFAULT_NOTE_COLOR,
    val typeIndex: Int = NOTE_TYPE_NONE,
    val customLabel: String = "",
    val deadlineAt: Long = 0L
)

/** 调色盘里可选的 8 种颜色数量。UI 和取值都以此为准 */
const val NOTE_COLOR_COUNT = 8

/** 新建便签时的默认色号 */
const val DEFAULT_NOTE_COLOR = 0

// ── 便签分类 ────────────────────────────────────────────────────
//
// 分类的**语义**分三种：
//  - GENERAL：纯标签，只在便签上显示一个名字，不影响任何行为
//  - DEADLINE：带一个截止时刻，要在「今天」「便签」两页右侧显示时刻与倒计时
//  - NONE：没选分类（默认），不显示任何标签
//
// 「自定义 / Deadline 自定义」不是额外的语义，而是「名字由用户填」的两种一般/截止分类 ——
// 所以这里用 kind 而不是枚举值来区分行为，**新增一个分类只要加一行**，
// 所有按 kind 分支的逻辑（是否显示倒计时、是否要求填名字）都自动生效。

/** 分类的行为语义 */
enum class NoteTypeKind {
    /** 无分类 */
    NONE,

    /** 普通分类（工作 / 生活 / 学习 / 自定义…）：只是个标签 */
    GENERAL,

    /** 截止日期分类：标签 + 截止时刻 + 倒计时 */
    DEADLINE
}

/**
 * 一个便签分类。
 *
 * @param label 显示名。[editableLabel] 为 true 时这里只是**占位示例**，
 *   实际显示用户填的 [NoteEntity.customLabel]。
 * @param iconLabel 列表里那个小徽章上的短标记（1 个字最好，最多 2 个）。
 * @param editableLabel 名字是否由用户输入
 * @param kind 行为语义：决定是否显示倒计时、是否必须填时间
 */
data class NoteType(
    val label: String,
    val iconLabel: String,
    val editableLabel: Boolean = false,
    val kind: NoteTypeKind = NoteTypeKind.GENERAL
)

/**
 * 全部可选分类，**顺序即 typeIndex**。
 *
 * 第 0 项固定是「空」（默认值），所以老数据（v6 及以前没有这一列）迁移时补 0
 * 就正好落在「无分类」上，语义天然正确。
 *
 * 第 1~3 项是一般分类（用户需求里的「工作」「生活」等一般分类项），
 * 第 4 项是 Deadline 特殊项，第 5、6 项是两种自定义。
 */
val NOTE_TYPES: List<NoteType> = listOf(
    NoteType("空", "", kind = NoteTypeKind.NONE),
    NoteType("工作", "工"),
    NoteType("生活", "生"),
    NoteType("学习", "学"),
    NoteType("Deadline", "期", kind = NoteTypeKind.DEADLINE),
    NoteType("自定义", "自", editableLabel = true),
    NoteType("Deadline 自定义", "期", editableLabel = true, kind = NoteTypeKind.DEADLINE)
)

/** 默认分类下标 = 「空」 */
const val NOTE_TYPE_NONE = 0

/** 内置的 Deadline 下标（新建便签时「Deadline」那一项） */
const val NOTE_TYPE_DEADLINE = 4

/** 越界收敛，避免脏数据把 UI 打挂（分类可能来自旧库或手改的备份） */
fun noteTypeAt(index: Int): NoteType = NOTE_TYPES[index.coerceIn(0, NOTE_TYPES.lastIndex)]

/** 这条便签实际显示的分类名：自定义类走用户填的名字，为空时退回占位示例 */
fun NoteEntity.typeLabel(): String {
    val type = noteTypeAt(typeIndex)
    if (type.kind == NoteTypeKind.NONE) return ""
    return if (type.editableLabel) customLabel.ifBlank { type.label } else type.label
}

// ── Deadline 倒计时 ─────────────────────────────────────────────
//
// 这一份是**纯函数**，不依赖 Android 任何东西，所以能直接单测
// （和 WeekSchedule / TodaySchedule 一个路子）。
// 倒计时文案的档位刻意跟「人怎么读时间」走，而不是每档都精确到分钟：
// 还剩 3 天时没人关心还剩几小时，还剩 2 小时时也不关心还剩几分。

/** 这条便签是不是带截止时刻的 */
val NoteEntity.hasDeadline: Boolean
    get() = noteTypeAt(typeIndex).kind == NoteTypeKind.DEADLINE && deadlineAt > 0L

/**
 * 距截止还剩多久的人话文案。
 *
 * - 已过：`已过期` / `已过期 2 天`
 * - < 1 分钟：`即将到期`
 * - < 1 小时：`N 分钟后`
 * - < 1 天：`N 小时 M 分后`（M 为 0 时省掉）
 * - < 30 天：`N 天后`
 * - 更远：`N 个月后`
 */
fun deadlineCountdown(deadlineAt: Long, now: Long): String {
    val left = deadlineAt - now
    if (left <= 0L) {
        val overdue = -left
        val days = overdue / DAY_MILLIS
        return if (days >= 1L) "已过期 $days 天" else "已过期"
    }
    val minutes = left / 60_000L
    return when {
        minutes < 1L -> "即将到期"
        minutes < 60L -> "$minutes 分钟后"
        left < DAY_MILLIS -> {
            val hours = left / HOUR_MILLIS
            val mins = minutes % 60L
            if (mins == 0L) "$hours 小时后" else "$hours 小时 $mins 分后"
        }
        left < 30L * DAY_MILLIS -> "${left / DAY_MILLIS} 天后"
        else -> "${left / (30L * DAY_MILLIS)} 个月后"
    }
}

/** 截止时刻的显示文案：`MM/dd HH:mm`（当年省略年份，跨年补上） */
fun deadlineTimeLabel(deadlineAt: Long, now: Long): String {
    val target = Calendar.getInstance().apply { timeInMillis = deadlineAt }
    val today = Calendar.getInstance().apply { timeInMillis = now }
    val month = target.get(Calendar.MONTH) + 1
    val day = target.get(Calendar.DAY_OF_MONTH)
    val hour = target.get(Calendar.HOUR_OF_DAY)
    val minute = target.get(Calendar.MINUTE)
    val sameYear = target.get(Calendar.YEAR) == today.get(Calendar.YEAR)
    val md = String.format(Locale.getDefault(), "%d/%d %02d:%02d", month, day, hour, minute)
    return if (sameYear) md else "${target.get(Calendar.YEAR)}/$md"
}

private const val HOUR_MILLIS = 60L * 60L * 1000L
private const val DAY_MILLIS = 24L * HOUR_MILLIS
