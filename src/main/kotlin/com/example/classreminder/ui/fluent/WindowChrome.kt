package com.example.classreminder.ui.fluent

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.awt.ComposeWindow
import com.example.classreminder.platform.WindowEffects
import kotlinx.coroutines.delay
import java.awt.GraphicsConfiguration
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Point
import java.awt.Rectangle
import java.awt.Toolkit
import kotlin.math.roundToInt

/**
 * 自绘窗口边框（标题栏 + 缩放热区）。
 *
 * 为什么需要这一套：用户要求**隐藏系统标题栏**，把「最小化 / 最大化 / 关闭」搬到应用自己的
 * 顶栏右侧。Compose Desktop 里对应的做法是 `Window(undecorated = true)`，
 * 但无边框窗口会同时失去**拖动**与**缩放**能力，所以这两件事必须自己补回来：
 *
 *  - 拖动：[windowDragArea] —— 顶栏空白处按住即可拖动，双击切换最大化
 *  - 缩放：[WindowResizeHandles] —— 四条边 + 四个角共 8 个 5dp 热区
 *
 * 窗口的最小尺寸仍然由 `ComposeWindow.minimumSize` 兜底（见 Main.kt），
 * 缩放热区里再夹一次是为了拖动过程中不闪。
 */

/** 窗口控制按钮的尺寸：与 Windows 11 原生标题栏一致（46×32 @100% 缩放） */
private val CAPTION_BUTTON_WIDTH = 46.dp
private val CAPTION_BUTTON_HEIGHT = 32.dp

/** 关闭按钮悬停时的红底（Windows 11 用的是 #C42B1C） */
private val CLOSE_HOVER = Color(0xFFC42B1C)

/**
 * 关闭按钮 ✕ 的字色。
 *
 * 刻意用红色把它和「最小化 / 最大化」两个中性按钮区分开 —— 关闭是唯一有破坏性的操作，
 * 值得在视觉上先声夺人。浅色取 Windows 11 原生的 #C42B1C；
 * 深色下这个红压在 #2B2B2B 上会发闷，所以提亮一档。
 */
private val CLOSE_GLYPH_LIGHT = Color(0xFFC42B1C)
private val CLOSE_GLYPH_DARK = Color(0xFFFF6B6B)

/** ✕ 的笔画宽度。比另外两个图标粗一档，配合红色形成「加粗红叉」 */
private val CLOSE_STROKE = 2.dp

/** 缩放热区厚度：5dp 足够好点，又不会把内容挡得难受 */
private val RESIZE_BORDER = 5.dp

/**
 * 窗口级能力与回调。
 *
 * 通过 [LocalWindowChrome] 下发给 `PageTopBar`，避免把 window / 回调一层层透传到 4 个页面。
 */
@Immutable
class WindowChrome(
    val window: ComposeWindow,
    /** 拖动时必须同步它，否则最大化/最小化还原会退回初始的「居中」 */
    private val windowState: WindowState,
    /**
     * 用 lambda 而不是 Boolean：调用方 `remember(window)` 只建一次对象，
     * 每次读到的都是当前值。否则缩放拖动时 `WindowState.size` 每帧变化，
     * 会把整个 chrome 对象连同 CompositionLocal 一起重建。
     */
    val isMaximized: () -> Boolean,
    val onMinimize: () -> Unit,
    val onToggleMaximize: () -> Unit,
    /**
     * 最大化状态下按住标题栏拖动：先还原、再让窗口跟着光标走。
     *
     * 参数是光标在**最大化时**窗口内的相对位置（0..1，x/y），还原后按同样比例映射到
     * 还原后的窗口上，光标就落在标题栏同一相对高度 —— 与原生窗口的观感一致。
     * 返回还原后的窗口矩形（物理像素），让拖动方立刻重算抓取偏移。
     */
    val onDragFromMaximized: (Pair<Float, Float>) -> WindowGeometry? = { null },
    val onClose: () -> Unit
) {
    /**
     * 把窗口左上角移到屏幕坐标 `(xPx, yPx)`（物理像素）。
     *
     * 两处都要写，各管一件事：
     *  - `window.location` 是**执行器**，AWT 层立即生效；
     *  - `windowState.position` 是 Compose 记录的「窗口在哪」，**不写它的话**，
     *    最大化→还原、最小化→还原都会退回 `rememberWindowState` 的初始值（屏幕居中），
     *    用户拖到角落再还原就会莫名其妙跳回中间。
     */
    fun moveTo(xPx: Int, yPx: Int) {
        window.location = Point(xPx, yPx)
        val scale = uiScaleOf(window.graphicsConfiguration)
        windowState.position = WindowPosition((xPx / scale).dp, (yPx / scale).dp)
    }

    /**
     * 目标光标位置所在显示器的边界条件。
     *
     * 每次拖动都重新取，而不是在 `onDragStart` 时算一次 —— 窗口可以被拖到**另一块
     * 显示器**上，两块的缩放比与工作区都不一样。取一次的话，拖到 4K 副屏上
     * 会继续用 1080p 主屏的最小尺寸与工作区，夹取结果就是错的。
     */
    fun limitsAt(cursorX: Int, cursorY: Int): WindowLimits {
        val gc = deviceForPoint(window, cursorX, cursorY)
        val scale = uiScaleOf(gc)
        val (minW, minH) = minWindowSizePx(scale)
        val area: Rectangle = workAreaOf(gc)
        return WindowLimits(area, minW, minH)
    }

    /** 标题栏高度（物理像素），纵向拖动夹取时用来保证标题栏始终可点 */
    fun captionHeightPx(): Int = chromeCaptionPx(uiScaleOf(window.graphicsConfiguration))
}

/** 标题栏高度（物理像素）。拖动夹取时用来保证标题栏始终留在屏幕内 */
fun chromeCaptionPx(uiScale: Double): Int {
    val s = if (uiScale > 0.0) uiScale else 1.0
    return (CAPTION_BUTTON_HEIGHT.value * s).roundToInt().coerceAtLeast(1)
}

val LocalWindowChrome = staticCompositionLocalOf<WindowChrome?> { null }

/**
 * 把 Windows 11 的原生圆角与描边套到主窗口上。
 *
 * 窗口是 `undecorated = true` 的，Win11 对无边框窗口默认给**直角**，
 * 必须通过 DWM 显式声明圆角偏好（见 [WindowEffects]）。描边取当前主题的
 * [FluentColors.outlineStrong]，所以切换浅色/深色时边框会跟着变 —— 这个
 * `LaunchedEffect` 的 key 就是颜色本身，主题一变就重新下发。
 *
 * 必须在 [FluentTheme] 里面调用（要读配色），并且窗口已 realize（要拿 HWND），
 * 所以带一小段重试。
 */
@Composable
fun ApplyWindowCorners(window: ComposeWindow) {
    val c = FluentTheme.colors
    val border = c.outlineStrong.toArgb()
    val dark = c.isDark
    LaunchedEffect(border, dark) {
        repeat(10) {
            if (WindowEffects.applyRoundedCorners(window, borderArgb = border, dark = dark)) {
                return@LaunchedEffect
            }
            delay(120)
        }
    }
}

/**
 * 某块显示器的工作区（屏幕减去任务栏 / 停靠栏）。
 *
 * 无边框窗口不能用 `WindowPlacement.Maximized`：那个走的是 AWT 的
 * `MAXIMIZED_BOTH`，对没有边框的窗口会把任务栏一起盖掉（实测 2560×1440 全屏，
 * 底部任务栏被遮住）。所以最大化改成「自己算工作区 + 直接设 size/position」。
 *
 * ⚠️ **返回值已经是 dp**，不要再除 density。`GraphicsConfiguration.bounds` 在
 * AWT 用户空间里，而 AWT 用户空间 = 物理像素 / uiScale；Compose Desktop 的
 * `LocalDensity.density` 恰好就是那个 uiScale，所以两者单位相同。
 * 实测探针（2026-10-02，本机）：`density=2.0`、`screenSize=1400x636`、
 * `gcBounds=1400x636`、`insets.bottom=48`，物理屏幕实为 2800x1272。
 * 曾经这里被外面除过一次 density，结果「最大化」只铺满左上四分之一屏。
 */
fun workAreaOf(gc: GraphicsConfiguration?): Rectangle {
    val toolkit = Toolkit.getDefaultToolkit()
    if (gc == null) {
        val size = toolkit.screenSize
        return Rectangle(0, 0, size.width, size.height)
    }
    val insets = toolkit.getScreenInsets(gc)
    val bounds = gc.bounds
    return Rectangle(
        bounds.x + insets.left,
        bounds.y + insets.top,
        bounds.width - insets.left - insets.right,
        bounds.height - insets.top - insets.bottom
    )
}

/** 窗口当前所在显示器的工作区。等价于 `workAreaOf(window.graphicsConfiguration)` */
fun workAreaOf(window: ComposeWindow): Rectangle = workAreaOf(window.graphicsConfiguration)

/**
 * 找出**屏幕坐标 `(x, y)` 落在哪块显示器**上，返回那块屏的 GraphicsConfiguration。
 *
 * 拖动时用它来换工作区与缩放比：多显示器下把窗口从 100% 主屏拖到 200% 副屏，
 * 最小尺寸、最大化矩形、坐标换算全都要换一套参数，沿用主屏那套必然算错。
 * 找不到（点在所有屏幕之外，或设备枚举失败）时退回窗口自己那块。
 */
fun deviceForPoint(window: ComposeWindow, x: Int, y: Int): GraphicsConfiguration? {
    val own = window.graphicsConfiguration
    val devices = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
    }.getOrNull() ?: return own
    // 按「包含该点」直接命中；命中不了再按最近的一块算（光标刚好在两屏缝隙里）
    val hit = devices.firstOrNull { it.defaultConfiguration.bounds.contains(x, y) }
    if (hit != null) return hit.defaultConfiguration
    val nearest = devices.minByOrNull { d ->
        val b = d.defaultConfiguration.bounds
        val dx = when {
            x < b.x -> b.x - x
            x > b.x + b.width -> x - (b.x + b.width)
            else -> 0
        }
        val dy = when {
            y < b.y -> b.y - y
            y > b.y + b.height -> y - (b.y + b.height)
            else -> 0
        }
        dx + dy
    }
    return nearest?.defaultConfiguration ?: own
}

/**
 * **主屏**工作区（屏幕减去任务栏）。给「还没拿到窗口」的场合用 ——
 * 比如在 `rememberWindowState` 里就要先算好提醒小窗的位置。
 *
 * 提醒小窗固定落在主屏：多屏时跟着鼠标跑反而让人找不到，系统通知也是固定主屏。
 */
fun primaryWorkArea(): Rectangle {
    val toolkit = Toolkit.getDefaultToolkit()
    val gc = runCatching {
        GraphicsEnvironment.getLocalGraphicsEnvironment().defaultScreenDevice.defaultConfiguration
    }.getOrNull() ?: run {
        val size = toolkit.screenSize
        return Rectangle(0, 0, size.width, size.height)
    }
    val insets = toolkit.getScreenInsets(gc)
    val bounds = gc.bounds
    return Rectangle(
        bounds.x + insets.left,
        bounds.y + insets.top,
        bounds.width - insets.left - insets.right,
        bounds.height - insets.top - insets.bottom
    )
}

// ── 标题栏按钮 ──────────────────────────────────────────────────

@Composable
fun WindowControls(chrome: WindowChrome?) {
    if (chrome == null) return
    val c = FluentTheme.colors
    Row(Modifier.height(CAPTION_BUTTON_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        CaptionButton(
            hoverBackground = c.hover,
            glyphColor = c.onSurface,
            onHoverGlyphColor = c.onSurface,
            onClick = chrome.onMinimize,
            glyph = CaptionGlyph.MINIMIZE
        )
        CaptionButton(
            hoverBackground = c.hover,
            glyphColor = c.onSurface,
            onHoverGlyphColor = c.onSurface,
            onClick = chrome.onToggleMaximize,
            glyph = if (chrome.isMaximized()) CaptionGlyph.RESTORE else CaptionGlyph.MAXIMIZE
        )
        CaptionButton(
            hoverBackground = CLOSE_HOVER,
            glyphColor = if (c.isDark) CLOSE_GLYPH_DARK else CLOSE_GLYPH_LIGHT,
            onHoverGlyphColor = Color.White,
            onClick = chrome.onClose,
            glyph = CaptionGlyph.CLOSE
        )
    }
}

private enum class CaptionGlyph { MINIMIZE, MAXIMIZE, RESTORE, CLOSE }

@Composable
private fun CaptionButton(
    hoverBackground: Color,
    glyphColor: Color,
    onHoverGlyphColor: Color,
    onClick: () -> Unit,
    glyph: CaptionGlyph
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg = if (hovered) hoverBackground else Color.Transparent
    val fg = if (hovered) onHoverGlyphColor else glyphColor

    Box(
        modifier = Modifier
            .width(CAPTION_BUTTON_WIDTH)
            .height(CAPTION_BUTTON_HEIGHT)
            .background(bg)
            .hoverable(interaction)
            .pointerInput(glyph, onClick) {
                detectTapGestures(onTap = { onClick() })
            }
            .drawBehind {
                when (glyph) {
                    CaptionGlyph.MINIMIZE -> drawMinimizeGlyph(fg)
                    CaptionGlyph.MAXIMIZE -> drawMaximizeGlyph(fg)
                    CaptionGlyph.RESTORE -> drawRestoreGlyph(fg, bg)
                    CaptionGlyph.CLOSE -> drawCloseGlyph(fg)
                }
            }
    )
}

// Windows 11 的标题栏图标是 10×10、1px 描边、方角

private fun DrawScope.drawMinimizeGlyph(color: Color) {
    val w = 10.dp.toPx()
    val h = 1.dp.toPx()
    drawRect(
        color = color,
        topLeft = Offset((size.width - w) / 2f, (size.height - h) / 2f),
        size = Size(w, h)
    )
}

private fun DrawScope.drawMaximizeGlyph(color: Color) {
    val s = 10.dp.toPx()
    val stroke = 1.dp.toPx()
    drawRect(
        color = color,
        topLeft = Offset((size.width - s) / 2f, (size.height - s) / 2f),
        size = Size(s, s),
        style = Stroke(stroke)
    )
}

/** 还原图标：两个错位方框。前层用按钮底色填掉重叠部分，免得两条边叠成一根粗线 */
private fun DrawScope.drawRestoreGlyph(color: Color, background: Color) {
    val s = 8.dp.toPx()
    val stroke = 1.dp.toPx()
    val offset = 2.dp.toPx()
    val left = (size.width - s) / 2f
    val top = (size.height - s) / 2f

    drawRect(
        color = color,
        topLeft = Offset(left + offset, top - offset),
        size = Size(s, s),
        style = Stroke(stroke)
    )
    drawRect(
        color = background,
        topLeft = Offset(left - stroke, top - stroke),
        size = Size(s + stroke * 2, s + stroke * 2)
    )
    drawRect(
        color = color,
        topLeft = Offset(left, top),
        size = Size(s, s),
        style = Stroke(stroke)
    )
}

private fun DrawScope.drawCloseGlyph(color: Color) {
    val s = 10.dp.toPx()
    // 比最小化/最大化粗一档（那两个是 1dp），配合红色形成视觉重量
    val stroke = CLOSE_STROKE.toPx()
    val cx = size.width / 2f
    val cy = size.height / 2f
    val half = s / 2f
    drawLine(color, Offset(cx - half, cy - half), Offset(cx + half, cy + half), stroke)
    drawLine(color, Offset(cx + half, cy - half), Offset(cx - half, cy + half), stroke)
}

// ── 拖动区 ──────────────────────────────────────────────────────

/**
 * 把这个区域变成「窗口拖动把手」。
 *
 * ## 为什么必须用「屏幕绝对光标位置」而不是拖拽增量
 *
 * `detectDragGestures` 给的 `dragAmount` 是**指针在节点本地坐标系里的位移**，
 * 而节点是跟着窗口一起走的。设 `u` = 光标屏幕 x、`w` = 窗口 x，则
 * `dragAmount = Δu − Δw`。把它累加进窗口位置（`Δw = total`）会得到：
 *
 * ```
 * totalₙ = totalₙ₋₁ + (Δuₙ − Δtotalₙ)  ⟹  Δtotalₙ = Δuₙ / 2
 * ```
 *
 * 也就是**窗口只以光标一半的速度跟手**，且窗口自身的位移会被下一帧当成反向增量扣回来，
 * 于是边走边回弹 —— 表现出来就是「持续抽搐」。
 * （实测：光标移 120px，窗口只走 60px，中途方向反转 6 次；光标一停就立刻稳定。）
 *
 * 改成用 `MouseInfo` 读光标的**屏幕坐标**，窗口位置就是光标位置的纯函数：
 * 同一个光标位置永远算出同一个窗口位置，天然幂等，不会自己和自己打架。
 */
fun Modifier.windowDragArea(chrome: WindowChrome?): Modifier {
    if (chrome == null) return this
    val window = chrome.window
    return this
        .pointerInput(window) {
            // 按下瞬间「光标 − 窗口左上角」的偏移，整个拖拽过程保持不变
            var grab = 0 to 0
            var draggingFromMaximized = false
            // 按下点在窗口内的**相对位置**（0..1）。最大化状态下拖动还原时，
            // 按同一相对位置映射到还原后的窗口上，光标才不会「从脚底被抽走」
            var anchor = 0.5f to 0f
            // 是否已经越过「可能是双击」的门槛。双击的第一下也会走 onDragStart，
            // 若一上来就搬窗口，快速双击最大化时窗口会先抖一下
            var armed = false

            detectDragGestures(
                onDragStart = {
                    val cursor = MouseInfo.getPointerInfo()?.location
                    draggingFromMaximized = chrome.isMaximized()
                    armed = false
                    grab = if (cursor != null && !draggingFromMaximized) {
                        val (dx, dy) = grabOffset(
                            cursor.x, cursor.y, window.x, window.y, window.width, window.height
                        )
                        dx to dy
                    } else {
                        0 to 0
                    }
                    anchor = if (cursor != null && window.width > 0 && window.height > 0) {
                        ((cursor.x - window.x).toFloat() / window.width).coerceIn(0f, 1f) to
                            ((cursor.y - window.y).toFloat() / window.height).coerceIn(0f, 1f)
                    } else {
                        0.5f to 0f
                    }
                },
                onDrag = { change, drag ->
                    change.consume()
                    // 位移非零才算真拖动；双击的抖动不搬窗口
                    if (!armed && (drag.x != 0f || drag.y != 0f)) armed = true
                    if (!armed) return@detectDragGestures

                    val cursor = MouseInfo.getPointerInfo()?.location ?: return@detectDragGestures

                    if (draggingFromMaximized) {
                        // 最大化时拖标题栏 = 「还原并跟随光标」，这是 Windows 的既定行为。
                        // 原实现在这里直接 return，用户会觉得窗口「粘死」在最大化状态，
                        // 只能去点还原按钮 —— 而原生窗口是允许直接拖下来的。
                        val restored = chrome.onDragFromMaximized(anchor)
                        if (restored == null) return@detectDragGestures
                        draggingFromMaximized = false
                        val (dx, dy) = grabOffset(
                            cursor.x, cursor.y, restored.x, restored.y, restored.width, restored.height
                        )
                        grab = dx to dy
                        // 关键：这一帧不能只还原不移动。还原后立刻按新偏移摆一次，
                        // 否则窗口会停在还原位置、等下一次 onDrag 才突然跳到光标下
                        val limits = chrome.limitsAt(cursor.x, cursor.y)
                        val (x, y) = clampDragPosition(
                            targetX = cursor.x - grab.first,
                            targetY = cursor.y - grab.second,
                            width = restored.width,
                            height = restored.height,
                            limits = limits,
                            captionHeightPx = chrome.captionHeightPx()
                        )
                        chrome.moveTo(x, y)
                        return@detectDragGestures
                    }

                    val limits = chrome.limitsAt(cursor.x, cursor.y)
                    val (x, y) = clampDragPosition(
                        targetX = cursor.x - grab.first,
                        targetY = cursor.y - grab.second,
                        width = window.width,
                        height = window.height,
                        limits = limits,
                        captionHeightPx = chrome.captionHeightPx()
                    )
                    chrome.moveTo(x, y)
                }
            )
        }
        .pointerInput(window, chrome.onToggleMaximize) {
            detectTapGestures(onDoubleTap = { chrome.onToggleMaximize() })
        }
}

// ── 缩放热区 ────────────────────────────────────────────────────

/** 最小窗口尺寸，与 `ComposeWindow.minimumSize` 保持一致（换算见 [minWindowSizePx]） */
private val MIN_WINDOW_WIDTH = MIN_WINDOW_WIDTH_DP.dp
private val MIN_WINDOW_HEIGHT = MIN_WINDOW_HEIGHT_DP.dp

/**
 * 8 向缩放热区。叠在内容最上层，但只在最外侧 [RESIZE_BORDER] 宽的一圈里响应指针，
 * 所以不会挡住里面的按钮。
 *
 * 四条边铺满整条边（会盖住角），**角放在最后绘制** —— Box 里后加的兄弟节点既在上层，
 * 也先拿到指针事件，于是角上的 10×10 区域自然优先判给角，不必给边做裁剪。
 *
 * ⚠️ 最大化状态下整层**不响应指针**：窗口已经铺满工作区，此时还能拖边的话
 * 会把最大化矩形改掉，而 `maximized` 标记仍是 true，接下来点「还原」会跳到一个
 * 尺寸对不上的历史值。原生窗口在最大化时也是禁止缩放的。
 */
@Composable
fun WindowResizeHandles(chrome: WindowChrome?, state: WindowState) {
    if (chrome == null) return
    val maximized = chrome.isMaximized()
    val b = RESIZE_BORDER
    val corner = RESIZE_BORDER * 2

    Box(Modifier.fillMaxSize()) {
        if (maximized) return@Box
        Handle(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(b), ResizeDir.N, chrome, state)
        Handle(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(b), ResizeDir.S, chrome, state)
        Handle(Modifier.align(Alignment.CenterStart).width(b).fillMaxHeight(), ResizeDir.W, chrome, state)
        Handle(Modifier.align(Alignment.CenterEnd).width(b).fillMaxHeight(), ResizeDir.E, chrome, state)

        Handle(Modifier.align(Alignment.TopStart).size(corner), ResizeDir.NW, chrome, state)
        Handle(Modifier.align(Alignment.TopEnd).size(corner), ResizeDir.NE, chrome, state)
        Handle(Modifier.align(Alignment.BottomStart).size(corner), ResizeDir.SW, chrome, state)
        Handle(Modifier.align(Alignment.BottomEnd).size(corner), ResizeDir.SE, chrome, state)
    }
}

/**
 * 单个缩放把手。
 *
 * ## 三个必须在极端情况下才对得上的点
 *
 * 1. **起点冻结、每帧重算**。`start` 是按下瞬间的窗口矩形，之后每帧都用
 *    「起点 + 累计位移」重算，而不是在上一帧结果上累加。累加会在夹到最小尺寸后
 *    产生「死区」：缩到 900 再往回拖，窗口要等光标退回 300px 才动。
 * 2. **换算用的是窗口所在屏的缩放，不是光标位置那块屏的**。缩放过程中窗口还没
 *    跨屏，光标可能已经先一步到了另一块屏上（两块屏 DPI 不同），此时切缩放会把
 *    窗口尺寸整体跳一档。
 * 3. **西/北方向按右/下边固定**。左上角跟着光标走，而不是「先算尺寸再把位置往前推」——
 *    后者在夹到最小尺寸时会把窗口整体推走。
 */
@Composable
private fun Handle(
    modifier: Modifier,
    dir: ResizeDir,
    chrome: WindowChrome,
    state: WindowState
) {
    val window = chrome.window
    Box(
        modifier = modifier.pointerInput(dir, window) {
            var start = WindowGeometry(0, 0, 0, 0)
            var total = Offset.Zero
            // 按下时窗口所在屏的参数，整个手势内保持不变
            var limits = WindowLimits(Rectangle(0, 0, 0, 0))
            var scale = 1.0

            detectDragGestures(
                onDragStart = {
                    start = WindowGeometry(window.x, window.y, window.width, window.height)
                    total = Offset.Zero
                    val gc = window.graphicsConfiguration
                    scale = uiScaleOf(gc)
                    val (minW, minH) = minWindowSizePx(scale)
                    limits = WindowLimits(workAreaOf(gc), minW, minH)
                },
                onDrag = { change, drag ->
                    change.consume()
                    total += drag
                    val geom = resizeGeometry(start, dir, total.x, total.y, limits)
                    state.size = DpSize((geom.width / scale).dp, (geom.height / scale).dp)
                    state.position = WindowPosition((geom.x / scale).dp, (geom.y / scale).dp)
                }
            )
        }
    )
}

