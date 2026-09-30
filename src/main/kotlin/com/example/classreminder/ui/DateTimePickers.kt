package com.example.classreminder.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.classreminder.ui.fluent.FluentTheme
import java.util.Calendar
import java.util.TimeZone

/**
 * 桌面端的日期 / 时间选择对话框。
 *
 * 安卓那边直接用平台自带的 `android.app.DatePickerDialog` / `TimePickerDialog`；
 * 桌面端没有对应 API，改用 Material 3 的 `DatePicker` / `TimePicker` ——
 * 它们是目前 Compose 生态里唯一开箱可用的日历 / 表盘控件，自己画一个代价太大。
 *
 * **但配色全部来自 Fluent 设计 token**：通过 [FluentMaterialScope] 把 M3 的 colorScheme
 * 临时换成 Fluent 色板，避免在一个 Windows 风格的应用里弹出 Material 默认的紫色对话框。
 *
 * 接口刻意做成「传入年月日 / 时分 → 回调年月日 / 时分」的形状，调用点不需要处理时区。
 */

/** 把 M3 的配色临时换成 Fluent 色板，只包住 picker 对话框 */
@Composable
private fun FluentMaterialScope(content: @Composable () -> Unit) {
    val fc = FluentTheme.colors
    val scheme = if (fc.isDark) {
        darkColorScheme(
            primary = fc.accent,
            onPrimary = fc.onAccent,
            primaryContainer = fc.accentTint,
            onPrimaryContainer = fc.onSurface,
            surface = fc.surface,
            onSurface = fc.onSurface,
            surfaceVariant = fc.surface3,
            onSurfaceVariant = fc.onSurfaceVariant,
            background = fc.surface,
            onBackground = fc.onSurface,
            outline = fc.outlineStrong,
            error = fc.error,
            onError = fc.onAccent
        )
    } else {
        lightColorScheme(
            primary = fc.accent,
            onPrimary = fc.onAccent,
            primaryContainer = fc.accentTint,
            onPrimaryContainer = fc.onSurface,
            surface = fc.surface,
            onSurface = fc.onSurface,
            surfaceVariant = fc.surface3,
            onSurfaceVariant = fc.onSurfaceVariant,
            background = fc.surface,
            onBackground = fc.onSurface,
            outline = fc.outlineStrong,
            error = fc.error,
            onError = fc.onAccent
        )
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 日期选择。回调给出用户选中的 年 / 月（0 基）/ 日 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppDatePickerDialog(
    initialYear: Int,
    initialMonth: Int,
    initialDay: Int,
    onDismiss: () -> Unit,
    onConfirm: (year: Int, month: Int, day: Int) -> Unit
) {
    // M3 的 DatePicker 内部按 **UTC 零点** 表示日期，所以进出都走 UTC 日历，
    // 否则东八区会整体偏移一天。
    val state = rememberDatePickerState(
        initialSelectedDateMillis = utcMillisOf(initialYear, initialMonth, initialDay)
    )

    FluentMaterialScope {
        DatePickerDialog(
            onDismissRequest = onDismiss,
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { millis ->
                        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { timeInMillis = millis }
                        onConfirm(
                            cal.get(Calendar.YEAR),
                            cal.get(Calendar.MONTH),
                            cal.get(Calendar.DAY_OF_MONTH)
                        )
                    }
                    onDismiss()
                }) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        ) {
            DatePicker(state = state)
        }
    }
}

/** 时间选择。回调给出用户选中的 时 / 分（24 小时制） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppTimePickerDialog(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit
) {
    val state = rememberTimePickerState(
        initialHour = initialHour.coerceIn(0, 23),
        initialMinute = initialMinute.coerceIn(0, 59),
        is24Hour = true
    )

    FluentMaterialScope {
        // M3 1.1.2 还没有 TimePickerDialog 这个组合函数（1.2 才加），
        // 所以自己用一个 Dialog + Surface 包一层。
        Dialog(onDismissRequest = onDismiss) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = FluentTheme.colors.surface,
                tonalElevation = 0.dp
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("选择时间", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.height(16.dp))
                    TimePicker(state = state)
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) { Text("取消") }
                        TextButton(onClick = {
                            onConfirm(state.hour, state.minute)
                            onDismiss()
                        }) { Text("确定") }
                    }
                }
            }
        }
    }
}

private fun utcMillisOf(year: Int, month: Int, day: Int): Long =
    Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
        clear()
        set(year, month, day)
    }.timeInMillis
