package com.example.classreminder.ui.fluent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.classreminder.data.sync.SyncEngine
import com.example.classreminder.data.sync.SyncPhase
import com.example.classreminder.data.sync.SyncState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 下发同步引擎，让设置页不必一路从 `Main.kt` 把参数传下来。
 *
 * 用 [staticCompositionLocalOf] 而不是 `compositionLocalOf`：它不跟踪读数，
 * 值在应用生命周期内不变，正好符合「一个引擎贯穿全局」的语义。
 */
val LocalSyncEngine = staticCompositionLocalOf<SyncEngine?> { null }

/**
 * **只给 dev/UiPreview 用**的状态注入口。
 *
 * 为什么不让预览去构造真的 [SyncEngine]：引擎状态由网络驱动，构造它就得连一次
 * 服务端才能看到「失败」「被覆盖 3 条」这些态 —— 而截图要的是稳定的、可重复的、
 * 不依赖服务端的画面。dev/UiPreview 用它直接摆状态，生产链路恒为 null。
 */
val LocalSyncPreviewState = staticCompositionLocalOf<SyncState?> { null }

/**
 * 设置页的「同步」卡片。
 *
 * ## 为什么单独一张卡而不是塞进账号卡里
 *
 * 同步的三个状态（进行中 / 已完成 / 被覆盖）需要**持续可见**，
 * 而账号卡里的每一行都是「点一下进对话框」的入口。
 * 塞进去会让那些状态文字混在一堆入口中间，用户反而看不见 ——
 * 而「上次同步覆盖了 N 条记录」这条提示**必须**被看到（设计 §5.5）。
 *
 * 状态取值顺序：[LocalSyncPreviewState]（仅预览注入）→ 引擎的真实状态 → 无（未登录）。
 */
@Composable
fun SyncCard(
    engine: SyncEngine?,
    onSignedOut: () -> Unit
) {
    FlCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp)) {
            // collectAsState 必须无条件调用（组合期不能按条件增删组合），
            // 所以引擎为 null 时也走同一条路 —— engine?.let 是 inline 的，安全。
            val liveState = engine?.let { it.state.collectAsState().value }
            val state = LocalSyncPreviewState.current ?: liveState

            FlSettingRow(
                label = "云同步",
                detail = "在多台设备之间同步课程表与便签。设置项不参与同步，" +
                    "未登录时数据只留在本机"
            ) {
                if (state == null) {
                    FlButton("去登录", onClick = onSignedOut, variant = FlButtonVariant.GHOST, compact = true)
                } else {
                    val busy = state.phase == SyncPhase.SYNCING
                    FlButton(
                        if (busy) "同步中…" else "立即同步",
                        onClick = { engine?.syncNow() },
                        variant = FlButtonVariant.GHOST,
                        compact = true,
                        enabled = !busy
                    )
                }
            }

            if (state != null) {
                FlDivider()
                SyncStatusBody(state)
            }
        }
    }
}

/**
 * 同步状态正文。
 *
 * 单独拆一个函数是为了让 [FlCard] 的 `padding` 只包住「入口行」，
 * 状态区的行距由自己控制 —— 两者需要的内边距不一样。
 *
 * @param state 已经取好的状态快照，不再自己订阅 —— 见 [SyncCard] 里关于
 *   「组合期不能条件增删组合」的说明。
 */
@Composable
private fun SyncStatusBody(state: SyncState) {
    val c = FluentTheme.colors

    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) {
        // 状态行：一个色点 + 文案。
        // 色点是唯一的「颜色编码」，且**同时**有文字说明 ——
        // 不能只靠颜色区分状态（色觉障碍用户读不出来）。
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(phase = state.phase)
            Spacer(Modifier.width(8.dp))
            Text(
                text = state.message,
                fontSize = 13.sp,
                color = if (state.phase == SyncPhase.FAILED) c.onSurface else c.onSurfaceVariant
            )
        }

        if (state.lastSyncedAt > 0L) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "上次同步：${formatTime(state.lastSyncedAt)}",
                fontSize = 12.sp,
                color = c.onSurfaceVariant
            )
        }

        // LWW 的已知限制必须如实告知（设计 §5.5 明确「不隐藏」）。
        // 两台设备离线改同一条记录时后同步的会覆盖先同步的，
        // 用户有权知道这件事存在，而不是事后才发现改动没了。
        if (state.overriddenCount > 0) {
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth()) {
                Text(
                    text = "有 ${state.overriddenCount} 条本地记录被服务端版本覆盖。" +
                        "两台设备离线时改同一门课，后同步的那台会赢。",
                    fontSize = 12.sp,
                    lineHeight = 17.sp,
                    color = c.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "启动时同步一次，改动后 30 秒内没有新改动就会自动同步",
                fontSize = 12.sp,
                lineHeight = 17.sp,
                color = c.onSurfaceVariant
            )
        }

        // 首端切换的自动备份（设计 §5.8 要求「在 UI 上明确告知路径」）。
        // 这一刻用户本地数据被清空过，不告诉他备份在哪，他会以为数据没了。
        state.backupPath?.let { path ->
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier
                    .fillMaxWidth()
                    // border 必须在 clip 之前，否则圆角会把描边切出缺口
                    .border(1.dp, c.borderRest, RoundedCornerShape(4.dp))
                    .clip(RoundedCornerShape(4.dp))
                    .background(c.surface)
                    .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
                Text(
                    text = "首次同步前已把本机原有数据备份到：",
                    fontSize = 12.sp,
                    color = c.onSurfaceVariant
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = path,
                    fontSize = 11.sp,
                    lineHeight = 16.sp,
                    color = c.onSurface
                )
            }
        }
    }
}

/**
 * 状态点。
 *
 * 用 `CircleShape` + 纯色填充而不是图标：图标要额外引入资源，
 * 而这里三个状态（正常 / 进行中 / 失败）用颜色 + 旁边的文字已经说清了。
 *
 * 进行中用**强调色**而不是转圈动画：动画在 StateFlow 更新时才重绘，
 * 一个静止的色点反而更诚实 —— 它不假装在动，只说「现在处于这个状态」。
 */
@Composable
private fun StatusDot(phase: SyncPhase) {
    val c = FluentTheme.colors
    val color = when (phase) {
        SyncPhase.SYNCING -> c.accent
        SyncPhase.FAILED -> c.error
        SyncPhase.SKIPPED -> c.onSurfaceVariant
        SyncPhase.IDLE -> c.success
    }
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, c.borderRest, CircleShape)
    )
}

private fun formatTime(epochMs: Long): String =
    DateTimeFormatter.ofPattern("M月d日 HH:mm", Locale.CHINA)
        .withZone(ZoneId.systemDefault())
        .format(Instant.ofEpochMilli(epochMs))
