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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
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

/**
 * 提醒小窗的尺寸（dp）。按 Windows 11 通知卡的体量取 —— 再宽会显得像对话框。
 */
private val TOAST_WIDTH = 380.dp
private val TOAST_HEIGHT = 168.dp

/** 距工作区右下角的留白。Windows 自己的通知也是 16 左右。 */
private val TOAST_MARGIN = 16.dp

/**
 * 上课提醒：**右下角置顶小卡片**，替代安卓的 `LockOverlayActivity`。
 *
 * 安卓靠「全屏 Intent 通知 + `setShowWhenLocked` + `setTurnScreenOn`」在锁屏上弹出来；
 * Windows 没有应用级的锁屏覆盖能力，等价物是「无边框 + 置顶」的窗口。
 *
 * ## 为什么是「右下角小窗」而不是「全屏透明覆盖层」
 *
 * 曾经做成**最大化 + 卡片以外全透明**（用户的原话是「置顶卡片会让屏幕其他部分变黑，
 * 让其他部分保持透明」）。透明这条路在本项目上**走不通**，两个方案都试过了：
 *
 * 1. `Window(transparent = true)` —— 实测**没有真的建出分层窗口**，
 *    `GetWindowLongW(hwnd, GWL_EXSTYLE)` 里 `WS_EX_LAYERED` 从未置位，卡片以外是不透明的纯黑。
 *    讽刺的是 `isWindowTranslucencySupported(PERPIXEL_TRANSLUCENT)` 返回 `true`
 *    —— 平台支持，是 Compose 这条渲染路径没走到。
 * 2. 自己上**色键透明**（`WS_EX_LAYERED` + `LWA_COLORKEY`）—— 连
 *    `GetLayeredWindowAttributes` 都回 `ok=1 key=0xFF00FF`，看起来是对的，
 *    但**用户实机验收仍是「卡片完全覆盖整个桌面」**。
 *    推测是 AWT 在 `alwaysOnTop` / `toFront()` 时会按自己缓存的样式重写 `GWL_EXSTYLE`，
 *    把 `WS_EX_LAYERED` 抹掉；也可能是 Skia 的呈现路径不走 GDI 表面，色键根本没被应用。
 *
 * 更关键的是：**这条路没法自证**。色键挖出来的洞在 `BitBlt` 截图里是未初始化的白，
 * 我拿不到「透出底下画面」的证据，只能请用户肉眼看 —— 已经因此白跑一轮。
 *
 * 所以换成**小窗**：不依赖任何透明能力，窗口本身就只占右下角一块，
 * 「不挡住用户正在看的东西」由**尺寸和位置**保证，而不是由透明保证。
 * 这也正好是 Windows 自己的通知（右下角）的形态，截图可验证、失败模式可预测。
 *
 * ## 与安卓的差异（写进设置页文案）
 *
 * 安卓是**全屏覆盖**且能压在锁屏上；Windows 这里只是右下角一块。
 * 对独占全屏的游戏 / 播放器，置顶窗口仍可能被压住 —— 与安卓上 ColorOS
 * 拦截全屏弹窗是同一类平台限制。
 */
@Composable
fun OverlayWindow(
    alert: ReminderEngine.Alert,
    themeMode: ThemeMode,
    onDismiss: () -> Unit
) {
    val windowState = rememberWindowState(
        size = DpSize(TOAST_WIDTH, TOAST_HEIGHT),
        // 位置只算一次：屏幕几何不会变，重算反而会让窗口在重组时抖一下
        position = remember { toastPosition() },
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
        // 光标抢走 —— 「不打断手头的事」正是这次改成小窗的初衷。
        //
        // ⚠️ 顺序不能反：`toFront()` / `setAlwaysOnTop` 会让 AWT 按它自己缓存的样式
        // **重写 GWL_EXSTYLE**，把 `WS_EX_TOOLWINDOW` 抹掉。所以标记必须排在后面。
        // 这也是上一版色键透明在生产环境失效的嫌疑原因（`WS_EX_LAYERED` 被同样地抹掉）。
        LaunchedEffect(alert.classId) {
            runCatching { window.toFront() }
            repeat(6) {
                if (WindowEffects.makeToolWindow(window)) return@LaunchedEffect
                delay(120)
            }
        }
        FluentTheme(themeMode = themeMode) {
            // Win11 原生圆角 + 跟随主题的 1px 描边，与主窗口同一套（走 DWM）
            ApplyWindowCorners(window)
            ReminderToast(
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
 * 卡片本体。左边一条强调色（与主界面卡片同一套语言），右边是内容。
 *
 * 尺寸由窗口给定，所以这里用 `fillMaxSize()` + `weight(1f)` 把按钮钉在底部，
 * 教室为空时（`room == ""`）按钮位置也不会跳。
 */
@Composable
fun ReminderToast(
    name: String,
    start: Long,
    end: Long,
    room: String,
    ongoing: Boolean = true,
    onDismiss: () -> Unit
) {
    val c = FluentTheme.colors

    // 从右侧滑入 180ms。够快，不会让人等；又比「啪一下出现」舒服。
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val enter by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(durationMillis = 180),
        label = "toastEnter"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(c.surface)
            .graphicsLayer {
                alpha = enter
                translationX = (1f - enter) * 24.dp.toPx()
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
        Row(modifier = Modifier.fillMaxSize()) {
            // 左侧强调条：主界面的卡片也是这个语言
            Box(Modifier.width(3.dp).fillMaxHeight().background(c.accent))

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 17.dp, end = 18.dp, top = 15.dp, bottom = 15.dp)
            ) {
                Text(
                    if (ongoing) "正在上课" else "即将上课",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.6.sp,
                    color = c.accent
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = c.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    buildString {
                        append("${formatTime(start)} – ${formatTime(end)}")
                        if (room.isNotBlank()) append("  ·  教室 $room")
                    },
                    fontSize = 13.sp,
                    color = c.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.weight(1f))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    FlButton("知道了", onClick = onDismiss)
                }
            }
        }
    }
}

/**
 * 右下角位置：**工作区**（已扣掉任务栏）的右下角，留 [TOAST_MARGIN]。
 *
 * 不能用 `WindowPosition(Alignment.BottomEnd)` —— 它按**屏幕**算，
 * 卡片会有一半压在任务栏上（与主窗口最大化时必须自己算工作区是同一个坑）。
 *
 * ⚠️ `primaryWorkArea()` 返回的**已经是 dp**（AWT 用户空间 = 物理像素 / uiScale，
 * 而 Compose 的 density 恰好就是那个 uiScale），**不要再除 density**。
 * 实测探针：`density=2.0`、`screenSize=1400x636`、`gcBounds=1400x636`、
 * `insets.bottom=48` → 物理屏幕其实是 2800x1272。多除一次 density
 * 会把卡片摆到屏幕正中偏左（实测落在 608px 处，而正确值是 2008px）。
 */
private fun toastPosition(): WindowPosition {
    val area = primaryWorkArea()
    return WindowPosition(
        x = (area.x + area.width - TOAST_WIDTH.value - TOAST_MARGIN.value).dp,
        y = (area.y + area.height - TOAST_HEIGHT.value - TOAST_MARGIN.value).dp
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
