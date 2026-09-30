package com.example.classreminder.ui.fluent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.classreminder.data.ClassEntity
import com.example.classreminder.data.TodaySchedule
import com.example.classreminder.data.WeekSchedule
import com.example.classreminder.ui.AppDatePickerDialog
import com.example.classreminder.ui.AppTimePickerDialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

private val DAY_ORDER = listOf("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
private val DAY_LABEL = mapOf(
    "Monday" to "周一", "Tuesday" to "周二", "Wednesday" to "周三",
    "Thursday" to "周四", "Friday" to "周五", "Saturday" to "周六", "Sunday" to "周日"
)

/**
 * 课程的新增 / 编辑对话框。
 *
 * 与手机版相比只改了「呈现方式」：手机上是底部弹出的 AlertDialog，桌面端改成居中的
 * Fluent 对话框，字段从左到右一行一个（桌面横向空间够），不再需要限高滚动。
 * 校验规则与文案与手机版**完全一致**。
 */
@Composable
fun CourseEditDialog(
    initial: ClassEntity?,
    oneOff: Boolean,
    onSave: (ClassEntity) -> Unit,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null
) {
    val c = FluentTheme.colors
    var title by remember { mutableStateOf(initial?.title ?: "") }
    var day by remember { mutableStateOf(initial?.dayOfWeek ?: "Monday") }
    var dayExpanded by remember { mutableStateOf(false) }
    var startTime by remember { mutableStateOf(initial?.startTime ?: "09:00") }
    var endTime by remember { mutableStateOf(initial?.endTime ?: "10:00") }
    var room by remember { mutableStateOf(initial?.room ?: "") }
    var teacher by remember { mutableStateOf(initial?.teacher ?: "") }
    var weeks by remember { mutableStateOf(initial?.weeks ?: "") }
    var date by remember {
        mutableStateOf(initial?.date?.takeIf { it.isNotEmpty() } ?: TodaySchedule.dateOf(System.currentTimeMillis()))
    }
    var showDatePicker by remember { mutableStateOf(false) }
    var showStartPicker by remember { mutableStateOf(false) }
    var showEndPicker by remember { mutableStateOf(false) }

    val dateValid = TodaySchedule.isValidDate(date)
    val timeRegex = Regex("^(?:[01]\\d|2[0-3]):[0-5]\\d$")
    val startValid = timeRegex.matches(startTime)
    val endValid = timeRegex.matches(endTime)
    fun toMinutes(t: String): Int? {
        val parts = t.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: return null
        val m = parts.getOrNull(1)?.toIntOrNull() ?: return null
        return h * 60 + m
    }
    val startBeforeEnd = if (startValid && endValid) (toMinutes(endTime) ?: -1) > (toMinutes(startTime) ?: -1) else false
    val titleOk = title.isNotBlank()
    val canSave = titleOk && startValid && endValid && startBeforeEnd && (!oneOff || dateValid)

    val dialogTitle = when {
        initial == null && oneOff -> "添加临时提醒"
        initial == null -> "添加长期提醒"
        oneOff -> "编辑临时提醒"
        else -> "编辑长期提醒"
    }

    FlDialog(
        onDismiss = onDismiss,
        title = dialogTitle,
        width = 460.dp,
        content = {
            Column(modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                FieldRow("课程名") {
                    FlTextField(
                        value = title,
                        onValueChange = { title = it },
                        placeholder = "例如「高等数学」",
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                if (oneOff) {
                    FieldRow("日期") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            FlTextField(
                                value = date,
                                onValueChange = { date = it },
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            FlButton(
                                "选日期",
                                onClick = { showDatePicker = true },
                                variant = FlButtonVariant.GHOST,
                                icon = Icons.Default.DateRange,
                                compact = true
                            )
                        }
                    }
                    Hint("临时提醒只在这一天生效一次")
                    if (!dateValid) Hint("日期格式应为 yyyy-MM-dd", error = true)
                } else {
                    FieldRow("星期") {
                        Box {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(FluentTheme.dimens.radiusControl))
                                    .background(c.surface)
                                    .border(1.dp, c.outlineStrong, RoundedCornerShape(FluentTheme.dimens.radiusControl))
                                    .clickable { dayExpanded = true }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(DAY_LABEL[day] ?: day, fontSize = 13.sp, color = c.onSurface)
                                Spacer(Modifier.weight(1f))
                                Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = c.onSurfaceFaint, modifier = Modifier.size(14.dp))
                            }
                            DropdownMenu(expanded = dayExpanded, onDismissRequest = { dayExpanded = false }) {
                                DAY_ORDER.forEach { d ->
                                    DropdownMenuItem(
                                        text = { Text(DAY_LABEL[d] ?: d, fontSize = 13.sp) },
                                        onClick = { day = d; dayExpanded = false }
                                    )
                                }
                            }
                        }
                    }
                }

                FieldRow("开始时间") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FlTextField(value = startTime, onValueChange = { startTime = it }, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        FlButton("选时间", onClick = { showStartPicker = true }, variant = FlButtonVariant.GHOST, compact = true)
                    }
                }
                FieldRow("结束时间") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FlTextField(value = endTime, onValueChange = { endTime = it }, modifier = Modifier.weight(1f))
                        Spacer(Modifier.width(8.dp))
                        FlButton("选时间", onClick = { showEndPicker = true }, variant = FlButtonVariant.GHOST, compact = true)
                    }
                }
                if (!startValid) Hint("开始时间格式应为 HH:mm", error = true)
                if (!endValid) Hint("结束时间格式应为 HH:mm", error = true)
                if (startValid && endValid && !startBeforeEnd) Hint("结束时间必须晚于开始时间", error = true)

                FieldRow("教室") {
                    FlTextField(value = room, onValueChange = { room = it }, placeholder = "例如「教二 305」", modifier = Modifier.fillMaxWidth())
                }
                FieldRow("教师") {
                    FlTextField(value = teacher, onValueChange = { teacher = it }, placeholder = "选填", modifier = Modifier.fillMaxWidth())
                }
                if (!oneOff) {
                    FieldRow("周数") {
                        FlTextField(
                            value = weeks,
                            onValueChange = { weeks = it },
                            placeholder = "如 1-16周 / 第6周 / 1-16周(单)，留空不限",
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        actions = {
            if (onDelete != null) {
                FlButton("删除", onClick = onDelete, variant = FlButtonVariant.DANGER, compact = true)
            }
            Spacer(Modifier.weight(1f))
            FlButton("取消", onClick = onDismiss, variant = FlButtonVariant.GHOST, compact = true)
            Spacer(Modifier.width(8.dp))
            FlButton(
                "保存",
                onClick = {
                    val id = initial?.id ?: (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
                    onSave(
                        ClassEntity(
                            id = id,
                            title = title.trim(),
                            dayOfWeek = if (oneOff) TodaySchedule.dayNameOfDate(date) ?: day else day,
                            startTime = startTime.trim(),
                            endTime = endTime.trim(),
                            room = room.trim(),
                            notes = initial?.notes ?: "",
                            teacher = teacher.trim(),
                            weeks = if (oneOff) "" else weeks.trim(),
                            date = if (oneOff) date.trim() else ""
                        )
                    )
                },
                enabled = canSave,
                compact = true
            )
        }
    )

    if (showDatePicker) {
        val cal = remember(date) {
            Calendar.getInstance().apply {
                runCatching { SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(date) }
                    .getOrNull()?.let { timeInMillis = it.time }
            }
        }
        AppDatePickerDialog(
            initialYear = cal.get(Calendar.YEAR),
            initialMonth = cal.get(Calendar.MONTH),
            initialDay = cal.get(Calendar.DAY_OF_MONTH),
            onDismiss = { showDatePicker = false },
            onConfirm = { y, m, d ->
                date = String.format(Locale.getDefault(), "%04d-%02d-%02d", y, m + 1, d)
            }
        )
    }
    if (showStartPicker) {
        AppTimePickerDialog(
            initialHour = startTime.split(":").getOrNull(0)?.toIntOrNull() ?: 9,
            initialMinute = startTime.split(":").getOrNull(1)?.toIntOrNull() ?: 0,
            onDismiss = { showStartPicker = false },
            onConfirm = { h, m -> startTime = String.format(Locale.getDefault(), "%02d:%02d", h, m) }
        )
    }
    if (showEndPicker) {
        AppTimePickerDialog(
            initialHour = endTime.split(":").getOrNull(0)?.toIntOrNull() ?: 10,
            initialMinute = endTime.split(":").getOrNull(1)?.toIntOrNull() ?: 0,
            onDismiss = { showEndPicker = false },
            onConfirm = { h, m -> endTime = String.format(Locale.getDefault(), "%02d:%02d", h, m) }
        )
    }
}

@Composable
private fun FieldRow(label: String, content: @Composable () -> Unit) {
    val c = FluentTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
        Text(label, fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = c.onSurfaceFaint)
        Spacer(Modifier.height(6.dp))
        content()
    }
}

@Composable
private fun Hint(text: String, error: Boolean = false) {
    val c = FluentTheme.colors
    Text(
        text,
        fontSize = 11.5.sp,
        color = if (error) c.error else c.onSurfaceFaint,
        modifier = Modifier.padding(bottom = 10.dp)
    )
}

/**
 * 校准周数。填「本周是第几周」，反推出第 1 周的周一。
 * 规则与手机版一致：1~30 的整数。
 */
@Composable
fun CalibrateWeekDialog(
    initial: Long,
    onConfirm: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val c = FluentTheme.colors
    val currentWeek = remember {
        if (initial == 0L) null else WeekSchedule.weekNumber(initial, System.currentTimeMillis())
    }
    var input by remember { mutableStateOf(currentWeek?.toString() ?: "") }
    val parsed = input.toIntOrNull()
    val valid = parsed != null && parsed in 1..30

    FlDialog(
        onDismiss = onDismiss,
        title = "校准周数",
        width = 400.dp,
        content = {
            Column {
                Text(
                    "填「本周是第几周」，应用就能算出整学期的周次，并按周次显示课表和发提醒。",
                    fontSize = 12.5.sp,
                    color = c.onSurfaceVariant
                )
                Spacer(Modifier.height(14.dp))
                FlTextField(
                    value = input,
                    onValueChange = { raw -> input = raw.filter { it.isDigit() }.take(2) },
                    placeholder = "本周是第几周",
                    modifier = Modifier.width(160.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (input.isNotEmpty() && !valid) "请填 1~30 之间的整数" else "例如这周是第 4 周，就填 4",
                    fontSize = 11.5.sp,
                    color = if (input.isNotEmpty() && !valid) c.error else c.onSurfaceFaint
                )
            }
        },
        actions = {
            FlButton("取消", onClick = onDismiss, variant = FlButtonVariant.GHOST, compact = true)
            Spacer(Modifier.width(8.dp))
            FlButton(
                "确定",
                onClick = {
                    val week = parsed ?: return@FlButton
                    val thisMonday = WeekSchedule.mondayOf(System.currentTimeMillis())
                    val week1 = thisMonday - (week - 1).toLong() * 7L * 24 * 60 * 60 * 1000
                    onConfirm(week1)
                },
                enabled = valid,
                compact = true
            )
        }
    )
}

/** 通用确认对话框（删除课程、覆盖导入等） */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    danger: Boolean = false
) {
    val c = FluentTheme.colors
    FlDialog(
        onDismiss = onDismiss,
        title = title,
        width = 400.dp,
        content = { Text(message, fontSize = 13.sp, color = c.onSurfaceVariant) },
        actions = {
            FlButton("取消", onClick = onDismiss, variant = FlButtonVariant.GHOST, compact = true)
            Spacer(Modifier.width(8.dp))
            FlButton(
                confirmText,
                onClick = onConfirm,
                variant = if (danger) FlButtonVariant.DANGER else FlButtonVariant.PRIMARY,
                compact = true
            )
        }
    )
}
