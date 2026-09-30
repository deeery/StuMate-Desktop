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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.awt.ComposeWindow
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
    /**
     * 用 lambda 而不是 Boolean：调用方 `remember(window)` 只建一次对象，
     * 每次读到的都是当前值。否则缩放拖动时 `WindowState.size` 每帧变化，
     * 会把整个 chrome 对象连同 CompositionLocal 一起重建。
     */
    val isMaximized: () -> Boolean,
    val onMinimize: () -> Unit,
    val onToggleMaximize: () -> Unit,
    val onClose: () -> Unit
)

val LocalWindowChrome = staticCompositionLocalOf<WindowChrome?> { null }

/**
 * 窗口所在显示器的**工作区**（屏幕减去任务栏 / 停靠栏）。
 *
 * 无边框窗口不能用 `WindowPlacement.Maximized`：那个走的是 AWT 的
 * `MAXIMIZED_BOTH`，对没有边框的窗口会把任务栏一起盖掉（实测 2560×1440 全屏，
 * 底部任务栏被遮住）。所以最大化改成「自己算工作区 + 直接设 size/position」。
 */
fun workAreaOf(window: ComposeWindow): Rectangle {
    val toolkit = Toolkit.getDefaultToolkit()
    val gc = window.graphicsConfiguration
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
            glyphColor = c.onSurface,
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
    val stroke = 1.dp.toPx()
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
 * 用「记录按下时的窗口位置 + 累计位移」而不是「每次事件叠加增量」：
 * 后者在窗口跟随鼠标移动时会自己把自己甩飞（位移被重复计入）。
 */
fun Modifier.windowDragArea(chrome: WindowChrome?): Modifier {
    if (chrome == null) return this
    val window = chrome.window
    return this
        .pointerInput(window) {
            var startLocation = Point(0, 0)
            var total = Offset.Zero
            detectDragGestures(
                onDragStart = {
                    startLocation = window.location
                    total = Offset.Zero
                },
                onDrag = { change, drag ->
                    change.consume()
                    // 最大化状态下不响应拖动：否则窗口会跟着鼠标"跑出"屏幕
                    if (chrome.isMaximized()) return@detectDragGestures
                    total += drag
                    window.location = Point(
                        startLocation.x + total.x.roundToInt(),
                        startLocation.y + total.y.roundToInt()
                    )
                }
            )
        }
        .pointerInput(window, chrome.onToggleMaximize) {
            detectTapGestures(onDoubleTap = { chrome.onToggleMaximize() })
        }
}

// ── 缩放热区 ────────────────────────────────────────────────────

private enum class ResizeDir(val west: Boolean, val east: Boolean, val north: Boolean, val south: Boolean) {
    N(false, false, true, false),
    S(false, false, false, true),
    W(true, false, false, false),
    E(false, true, false, false),
    NW(true, false, true, false),
    NE(false, true, true, false),
    SW(true, false, false, true),
    SE(false, true, false, true)
}

/** 最小窗口尺寸，与 `ComposeWindow.minimumSize` 保持一致 */
private val MIN_WINDOW_WIDTH = 900.dp
private val MIN_WINDOW_HEIGHT = 600.dp

/**
 * 8 向缩放热区。叠在内容最上层，但只在最外侧 [RESIZE_BORDER] 宽的一圈里响应指针，
 * 所以不会挡住里面的按钮。
 *
 * 四条边铺满整条边（会盖住角），**角放在最后绘制** —— Box 里后加的兄弟节点既在上层，
 * 也先拿到指针事件，于是角上的 10×10 区域自然优先判给角，不必给边做裁剪。
 */
@Composable
fun WindowResizeHandles(chrome: WindowChrome?, state: WindowState) {
    if (chrome == null) return
    val window = chrome.window
    val b = RESIZE_BORDER
    val corner = RESIZE_BORDER * 2

    Box(Modifier.fillMaxSize()) {
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

@Composable
private fun Handle(
    modifier: Modifier,
    dir: ResizeDir,
    chrome: WindowChrome,
    state: WindowState
) {
    val density = LocalDensity.current
    val window = chrome.window
    Box(
        modifier = modifier.pointerInput(dir, window) {
            var startWidth = 0f
            var startHeight = 0f
            var startX = 0
            var startY = 0
            var total = Offset.Zero

            detectDragGestures(
                onDragStart = {
                    startWidth = window.width.toFloat()
                    startHeight = window.height.toFloat()
                    startX = window.x
                    startY = window.y
                    total = Offset.Zero
                },
                onDrag = { change, drag ->
                    change.consume()
                    // 最大化状态下不缩放：先把窗口还原成浮动尺寸再说
                    if (chrome.isMaximized()) return@detectDragGestures
                    total += drag

                    val scale = density.density
                    val minW = MIN_WINDOW_WIDTH.value * scale
                    val minH = MIN_WINDOW_HEIGHT.value * scale

                    var x = startX.toFloat()
                    var y = startY.toFloat()
                    var w = startWidth
                    var h = startHeight

                    if (dir.west) {
                        val next = (w - total.x).coerceAtLeast(minW)
                        x = startX + (w - next)
                        w = next
                    }
                    if (dir.east) w = (w + total.x).coerceAtLeast(minW)
                    if (dir.north) {
                        val next = (h - total.y).coerceAtLeast(minH)
                        y = startY + (h - next)
                        h = next
                    }
                    if (dir.south) h = (h + total.y).coerceAtLeast(minH)

                    state.size = DpSize((w / scale).dp, (h / scale).dp)
                    state.position = WindowPosition((x / scale).dp, (y / scale).dp)
                }
            )
        }
    )
}
