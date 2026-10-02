package com.example.classreminder.ui.fluent

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.example.classreminder.platform.ReminderEngine
import com.example.classreminder.platform.WindowEffects
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Locale

/** 提醒卡尺寸（dp）。460 是「读得清课程名 + 放得下进度条」的下限。 */
private val CARD_WIDTH = 460.dp
private val CARD_HEIGHT = 176.dp

/** 距工作区上沿的留白。24 比系统通知的 16 略大 —— 顶部横条压得太贴边会显得是系统级 UI。 */
private val CARD_TOP_MARGIN = 24.dp

/**
 * 上课提醒：**屏幕正上方居中的置顶卡片**。
 *
 * 安卓靠「全屏 Intent 通知 + `setShowWhenLocked` + `setTurnScreenOn`」在锁屏上弹出来；
 * Windows 没有应用级的锁屏覆盖能力，等价物是「无边框 + 置顶」的窗口。
 *
 * ## 位置：正上方居中
 *
 * 最初的方案是「全屏 + 卡片以外透明」，但那条路在 Compose Desktop 上走不通
 * （详见下面「不做什么」）。退成小窗之后位置就自由了 —— 现在是**正上方居中**，
 * 理由：课程名和进度条是横向信息，顶部横条比右下角方块更好读，
 * 也不会和系统通知（右下角）打架。
 *
 * ## 自定义外观（不是「系统通知」的样式）
 *
 * 刻意做成一张**有品牌感的卡片**，而不是系统通知的灰底 + 左侧色条：
 *  - 左侧放 [stuMateMark] 品牌标识（课表九宫格 + 待提醒格 + 铃铛）；
 *  - 背景是 `accentTint → surface` 的竖向渐变，顶部带一层强调色氛围；
 *  - 中间一条**上课进度条** —— 一眼看出「这节上到哪了」，
 *    这是系统通知给不了的信息；
 *  - 右上角实时倒计时（「已上课 25 分钟」/「还有 12 分钟」）。
 *
 * ## 不做什么（都是实测踩过的）
 *
 * ⚠️ **不用 `Window(transparent = true)`**：实测没建出分层窗口
 * （`GWL_EXSTYLE` 里 `WS_EX_LAYERED` 从未置位），卡片以外是不透明纯黑。
 * ⚠️ **也不用色键透明**（`WS_EX_LAYERED` + `LWA_COLORKEY`）：API 层回 `ok=1` 看着成功，
 * 但实机仍然全屏不透明 —— 疑似被 AWT 的样式重写抹掉。且色键洞在 `BitBlt` 截图里
 * 取不到数据，**无法自证**，只能靠肉眼看。
 * → 结论：不依赖任何逐像素透明，窗口尺寸就是遮挡范围，可断言。
 *
 * 已知限制（写进设置页文案）：对独占全屏的游戏 / 播放器可能压不住，
 * 这与安卓上 ColorOS 拦截全屏弹窗是同一类平台限制。
 */
@Composable
fun OverlayWindow(
    alert: ReminderEngine.Alert,
    themeMode: ThemeMode,
    onDismiss: () -> Unit
) {
    val windowState = rememberWindowState(
        size = DpSize(CARD_WIDTH, CARD_HEIGHT),
        // 位置只算一次：屏幕几何不会变，重算反而会让窗口在重组时抖一下
        position = remember { cardPosition() },
        placement = WindowPlacement.Floating
    )

    Window(
        onCloseRequest = onDismiss,
        title = "StuMate 提醒",
        state = windowState,
        undecorated = true,
        // 提醒必须压在别的窗口上面，否则用户在浏览器里就看不见了
        alwaysOnTop = true,
        resizable = false
    ) {
        // 抬高 z 序 + 打上工具窗口标记。**不** requestFocus()：提醒不该把用户正在输入的
        // 光标抢走 —— 「不打断手头的事」正是改成小窗的初衷。
        //
        // ⚠️ 顺序不能反：`toFront()` / `setAlwaysOnTop` 会让 AWT 按它自己缓存的样式
        // **重写 GWL_EXSTYLE**，把 `WS_EX_TOOLWINDOW` 抹掉。所以标记必须排在后面。
        // 这也是色键透明在生产环境失效的嫌疑原因（`WS_EX_LAYERED` 被同样地抹掉）。
        LaunchedEffect(alert.classId) {
            runCatching { window.toFront() }
            repeat(6) {
                if (WindowEffects.makeToolWindow(window)) return@LaunchedEffect
                delay(120)
            }
        }
        FluentTheme(themeMode = themeMode) {
            // 窗口自身的底色跟着卡片走。
            // ⚠️ 不设的话，Compose 根没画到的地方会露出**白色**窗底 ——
            // 实测：卡片顶部的 accentTint 渐变压在那层白上，量出来是 (230,243,249) 的亮银，
            // 而不是预期的 (40,53,59)。进场的 alpha 渐隐也会跟着闪白。
            val surface = FluentTheme.colors.surface
            LaunchedEffect(surface) {
                runCatching { window.background = java.awt.Color(surface.toArgb(), true) }
            }
            // Win11 原生圆角 + 跟随主题的 1px 描边，与主窗口同一套（走 DWM）
            ApplyWindowCorners(window)
            ReminderCard(
                name = alert.title,
                start = alert.startMillis,
                end = alert.endMillis,
                room = alert.room,
                ongoing = alert.ongoing,
                onDismiss = onDismiss
            )
        }
    }
}

/**
 * 卡片本体。
 *
 * 尺寸由窗口给定，所以内部用 `fillMaxSize()` + `weight(1f)` 撑开，
 * 教室为空（`room == ""`）或时间区间退化时布局也不会跳。
 */
@Composable
fun ReminderCard(
    name: String,
    start: Long,
    end: Long,
    room: String,
    ongoing: Boolean = true,
    onDismiss: () -> Unit
) {
    val c = FluentTheme.colors

    // 从上方落下 200ms。够快，不会让人等；又比「啪一下出现」舒服。
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val enter by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 200),
        label = "cardEnter"
    )

    // 倒计时与进度都从时间区间现算，而不是读 `ongoing` 之外的状态。
    // 区间退化（end <= start）时一律降级成「只显示时间」，不做除零。
    val now = System.currentTimeMillis()
    val hasRange = end > start
    val inClass = hasRange && now in start..end
    val minutesIn = if (inClass) ((now - start) / 60_000L).toInt() else 0
    val minutesToStart = if (hasRange && now < start) {
        ((start - now + 59_999L) / 60_000L).toInt()
    } else 0
    val progress = if (inClass) ((now - start).toFloat() / (end - start)) else 0f

    val phase = when {
        ongoing && inClass -> "正在上课"
        ongoing -> "正在上课"
        else -> "即将上课"
    }
    val countdown = when {
        inClass -> "已上课 $minutesIn 分钟"
        minutesToStart > 0 -> "还有 $minutesToStart 分钟"
        else -> ""
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // ⚠️ 先铺一层**不透明**底色，再叠渐变。
            // 只写渐变的话，渐变里半透明的那些像素会与窗口底（白色）混合 ——
            // 顶部会变成一片亮银而不是「深色卡片顶部微亮」。这个坑实测踩过：
            // 量出来 (230,243,249)，预期 (40,53,59)。
            .background(c.surface)
            // 强调色氛围：顶部一层 accentTint 渐隐到透明。
            // 这是「自定义」最省力也最有效的一笔 —— 系统通知是纯色底。
            .background(
                Brush.verticalGradient(
                    0f to c.accentTint,
                    0.45f to Color.Transparent
                )
            )
            .graphicsLayer {
                alpha = enter
                translationY = (1f - enter) * -16.dp.toPx()
            }
            .onPreviewKeyEvent { event ->
                // 窗口默认不抢焦点，所以 Esc 只在用户点过卡片之后才有效；
                // 不写「按 Esc 也可以关闭」那行提示，免得说了做不到。
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape) {
                    onDismiss()
                    true
                } else false
            }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = 22.dp, end = 22.dp, top = 20.dp, bottom = 20.dp)
        ) {
            Row {
                // 品牌标识：课表九宫格 + 待提醒格 + 铃铛。
                // 多色 ImageVector 必须 tint = Color.Unspecified，否则会被刷成单色。
                Icon(
                    imageVector = stuMateMark(),
                    contentDescription = null,
                    tint = Color.Unspecified,
                    modifier = Modifier.size(46.dp)
                )
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            phase,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 0.6.sp,
                            color = c.accent
                        )
                        Spacer(Modifier.weight(1f))
                        if (countdown.isNotEmpty()) {
                            Text(
                                countdown,
                                fontSize = 12.sp,
                                color = c.onSurfaceVariant
                            )
                        }
                    }
                    Spacer(Modifier.height(5.dp))
                    Text(
                        name,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        buildString {
                            append("${formatTime(start)} – ${formatTime(end)}")
                            if (room.isNotBlank()) append("   ·   $room")
                        },
                        fontSize = 13.sp,
                        color = c.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(Modifier.weight(1f))

            // 上课进度条。只有「正在上课」时才有意义，其余情况留一条空槽保持布局稳定。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(c.surface3)
            ) {
                if (progress > 0f) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(c.accent)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Start
            ) {
                FlButton("知道了", onClick = onDismiss)
            }
        }
    }
}

/**
 * 正上方居中：工作区（已扣掉任务栏）水平中线，距上沿 [CARD_TOP_MARGIN]。
 *
 * ⚠️ `primaryWorkArea()` 返回的**已经是 dp**（AWT 用户空间 = 物理像素 / uiScale，
 * 而 Compose 的 density 恰好就是那个 uiScale），**不要再除 density**。
 * 实测探针：`density=2.0`、`screenSize=1400x636`、`gcBounds=1400x636`、
 * `insets.bottom=48` → 物理屏幕其实是 2800x1272。多除一次 density
 * 会把卡片摆到屏幕正中偏左（实测落在 608px 处）。
 */
private fun cardPosition(): WindowPosition {
    val area = primaryWorkArea()
    val left = area.x + (area.width - CARD_WIDTH.value) / 2f
    return WindowPosition(
        x = left.dp,
        y = (area.y + CARD_TOP_MARGIN.value).dp
    )
}

private fun formatTime(millis: Long): String {
    val cal = Calendar.getInstance().apply { timeInMillis = millis }
    return String.format(
        Locale.getDefault(),
        "%02d:%02d",
        cal.get(Calendar.HOUR_OF_DAY),
        cal.get(Calendar.MINUTE)
    )
}
