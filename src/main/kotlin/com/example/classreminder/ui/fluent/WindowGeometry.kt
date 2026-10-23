package com.example.classreminder.ui.fluent

import java.awt.GraphicsConfiguration
import java.awt.Rectangle
import java.awt.Toolkit
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 无边框窗口的**几何决策**，纯函数、无 Compose 依赖，可脱离窗口做单元测试。
 *
 * ## 为什么要把算术从 Composable 里抽出来
 *
 * 原来的实现把 `coerceAtLeast(minW)` 之类的夹取写在 `detectDragGestures` 的
 * `onDrag` 里。它只在「真的用鼠标拖一次」时才会执行 —— 而拖出来的错误
 * （拖出屏幕、缩过头、高 DPI 下算错一档）恰好都发生在**极端路径**上，
 * 靠手点很难稳定复现，也就没法回归。抽成纯函数后用 JUnit 穷举即可。
 *
 * 本文件的所有坐标单位统一为**物理像素（屏幕坐标）**：
 * AWT 的 `window.x/y/width/height` 与 `MouseInfo` 都是这个单位，
 * 中间不掺 dp，避免此前踩过的「除两次 density」那类错误。
 */

// ── 单位换算 ──────────────────────────────────────────────────────

/**
 * 物理像素 → dp 的比例。
 *
 * 只有这一个方向需要算：`GraphicsConfiguration.defaultTransform` 给出的
 * scaleX 就是「物理 / 用户空间」，与 Compose 的 density 同源。
 *
 * 取不到 `GraphicsConfiguration`（窗口还没 realize）、或者返回 1.0 而系统其实是
 * 高 DPI（JDK 在部分远程桌面 / 多显示器混合 DPI 下会这样）时，用
 * [dpiFallbackScale] 兜底，避免 100% 以外的机器上窗口尺寸被算成两倍。
 */
fun uiScaleOf(gc: GraphicsConfiguration?): Double {
    val fromGc = gc?.defaultTransform?.scaleX ?: 0.0
    if (fromGc > 0.0 && fromGc != 1.0) return fromGc
    val fallback = dpiFallbackScale()
    // gc 说 1.0 且系统真实 DPI 也是 100% → 一致，用 1.0
    return if (fromGc > 0.0) fallback else 1.0
}

/**
 * 从系统 DPI 推一个缩放比例（仅用于 gc 不可靠时兜底）。
 *
 * `getScreenResolution()` 在 Windows 上返回的是 96 × 缩放比，
 * Linux/macOS 上历来不靠谱，所以只在返回 >96 时采信，否则按 100% 算。
 */
fun dpiFallbackScale(): Double {
    val dpi = runCatching { Toolkit.getDefaultToolkit().screenResolution }.getOrDefault(96)
    return if (dpi > 96) dpi / 96.0 else 1.0
}

// ── 尺寸常量 ──────────────────────────────────────────────────────

/** 窗口最小尺寸（物理像素基准 = dp 数值 × uiScale）。与 `ComposeWindow.minimumSize` 同源 */
const val MIN_WINDOW_WIDTH_DP = 900
const val MIN_WINDOW_HEIGHT_DP = 600

/**
 * 缩放下限的兜底值（物理像素）。
 *
 * 用户把窗口缩到最小之后，若把窗口拖到另一块 **DPI 更高** 的显示器上，
 * `uiScale` 会从 1.0 跳到 1.5/2.0，此时最小尺寸要求也会跟着涨；
 * 而 AWT 那边 `minimumSize` 是按**当时**的 scale 算的死数字。
 * 这里直接给一个与缩放无关的物理地板，保证任何路径下都不会出现
 * 「宽度 0 / 负数 / 窗口缩成一条线」这种不可恢复的状态。
 */
const val ABS_MIN_WINDOW_WIDTH_PX = 200
const val ABS_MIN_WINDOW_HEIGHT_PX = 150

/** 窗口在任一方向上允许的最大边长（物理像素）。防止累加溢出成天文数字 */
const val MAX_WINDOW_SIDE_PX = 20_000

/**
 * 拖动时允许丢失在屏幕外的宽度（物理像素）。
 *
 * 见 [clampDragPosition]：横向故意留一条「可以让窗口探出去」的口子，
 * 因为用户常常把窗口拉到左右半屏并排；纵向则不留，标题栏必须可点。
 */
const val OFFSCREEN_GRAB_PX = 200

/** 判断「窗口是否（几乎）等于工作区」时允许的误差。物理像素 */
const val MAXIMIZED_TOLERANCE_PX = 2

// ── 几何数据 ──────────────────────────────────────────────────────

/** 缩放的八个方向。`west/east/north/south` 表示该方向是否参与拉伸 */
enum class ResizeDir(val west: Boolean, val east: Boolean, val north: Boolean, val south: Boolean) {
    N(false, false, true, false),
    S(false, false, false, true),
    W(true, false, false, false),
    E(false, true, false, false),
    NW(true, false, true, false),
    NE(false, true, true, false),
    SW(true, false, false, true),
    SE(false, true, false, true)
}

/** 一次拖动/缩放之后的窗口位置与尺寸（物理像素，屏幕坐标） */
data class WindowGeometry(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right: Int get() = x + width
    val bottom: Int get() = y + height
}

/**
 * 当前窗口所处的**边界条件**，供 [clampDragPosition] / [resizeGeometry] 使用。
 *
 * @param workArea 目标显示器的工作区（已扣掉任务栏）
 * @param minWidth 允许的最小宽度（物理像素，至少 [ABS_MIN_WINDOW_WIDTH_PX]）
 * @param minHeight 允许的最小高度（物理像素，至少 [ABS_MIN_WINDOW_HEIGHT_PX]）
 */
data class WindowLimits(
    val workArea: Rectangle,
    val minWidth: Int = ABS_MIN_WINDOW_WIDTH_PX,
    val minHeight: Int = ABS_MIN_WINDOW_HEIGHT_PX
)

/** 由 dp 与当前缩放算出物理像素最小尺寸，并套上绝对地板与上限 */
fun minWindowSizePx(uiScale: Double): Pair<Int, Int> {
    val s = if (uiScale > 0.0) uiScale else 1.0
    val w = max(ABS_MIN_WINDOW_WIDTH_PX, (MIN_WINDOW_WIDTH_DP * s).roundToInt())
    val h = max(ABS_MIN_WINDOW_HEIGHT_PX, (MIN_WINDOW_HEIGHT_DP * s).roundToInt())
    return w to h
}

// ── 拖动 ─────────────────────────────────────────────────────────

/**
 * 光标被按下那一刻，光标相对窗口左上角的偏移。
 *
 * 整个拖拽过程保持不变 —— 这是「跟手」的充要条件：
 * 窗口左上角 = 光标 − 这个偏移，是光标位置的纯函数，不会自己和自己打架。
 *
 * 夹到窗口范围内：在极细的边条上按下（比如 5dp 缩放热区以外的 0 像素区域），
 * 或者光标已被系统夹到屏幕边缘时，偏移可能落到窗口外，不夹的话
 * 窗口会在第一帧跳一大截。
 */
fun grabOffset(cursorX: Int, cursorY: Int, windowX: Int, windowY: Int, width: Int, height: Int): Pair<Int, Int> {
    val dx = (cursorX - windowX).coerceIn(0, max(0, width - 1))
    val dy = (cursorY - windowY).coerceIn(0, max(0, height - 1))
    return dx to dy
}

/**
 * 把拖动目标位置夹进「仍可操作」的范围。
 *
 * 规则分两个方向，故意不对称：
 *
 *  - **横向**：允许窗口探出屏幕 [OFFSCREEN_GRAB_PX]，但至少留
 *    `工作区宽 − OFFSCREEN_GRAB_PX` 在屏幕内。这样左右半屏并排、窗口比屏宽时
 *    仍能往外推一点看边缘。
 *  - **纵向**：`y` 必须落在 `[工作区顶 − 0, 工作区底 − 标题栏高度]` 内。
 *    上限保证**标题栏永远留在屏幕里**，否则窗口拖到屏幕上方消失、用户再也抓不回来。
 *
 * 边界按「工作区」而不是「整块屏幕」算：任务栏所在的那条不能停靠，否则窗口停上去
 * 会盖住任务栏并且标题栏贴着屏幕边缘，双击最大化都点不准。
 */
fun clampDragPosition(
    targetX: Int,
    targetY: Int,
    width: Int,
    height: Int,
    limits: WindowLimits,
    captionHeightPx: Int
): Pair<Int, Int> {
    val area = limits.workArea
    val w = width.coerceIn(1, MAX_WINDOW_SIDE_PX)
    val h = height.coerceIn(1, MAX_WINDOW_SIDE_PX)

    // 横向：至少保留 `keepX` 宽在屏幕内。
    //
    // 窗口比 `keepX` 还宽时 min > max 会形成空区间 —— 此时取**居中**：
    // 这扇窗太宽了，让它横跨屏幕中间、两边各露一半，比硬夹到一个边界更合理。
    // 特别注意不能写 `x.coerceIn(maxX, minX)`（反序）或先 AtMost 再 AtLeast 的链式写法，
    // 那两种在空区间下都会把窗口推到屏幕外。
    val keepX = max(1, area.width - OFFSCREEN_GRAB_PX)
    val minX = area.x + keepX - w
    val maxX = area.x + area.width - keepX
    val x = if (minX > maxX) {
        area.x + (area.width - w) / 2
    } else {
        targetX.coerceIn(minX, maxX)
    }

    // 纵向：标题栏整条必须可见。
    //
    // 窗口比屏幕还高时不允许「底边露出」，只能保证标题栏贴在工作区顶上 ——
    // 否则 y 会是负数、标题栏被推到屏幕外，用户再也抓不回来。
    val caption = captionHeightPx.coerceIn(1, h)
    val minY = area.y
    val maxY = area.y + area.height - caption
    val y = if (minY > maxY) area.y else targetY.coerceIn(minY, maxY)

    return x to y
}

/**
 * 缩放拖拽的**位移换算**。
 *
 * 这里不能像拖动那样直接用光标绝对坐标，因为缩放时窗口左上角会动，
 * 本地坐标系跟着动，`dragAmount` 已经是 `Δ光标 − Δ窗口`。
 * 但缩放方向不同，重复扣减的方式也不同，所以按下瞬间就要**冻结**一次窗口矩形，
 * 之后每次都从「冻结矩形 + 累计位移」重新算，而不是在上一帧结果上累加 ——
 * 只有重算才不会累积误差，也才在夹到最小尺寸后反向拖动时立刻回弹。
 *
 * @param start 按下瞬间的窗口矩形（物理像素）
 * @param totalDx 从按下到现在的累计光标位移
 * @param totalDy 同上
 */
fun resizeGeometry(
    start: WindowGeometry,
    dir: ResizeDir,
    totalDx: Float,
    totalDy: Float,
    limits: WindowLimits
): WindowGeometry {
    // 位移可能是 NaN / Infinity（远程会话里偶发），直接当 0 处理，别让它污染窗口尺寸。
    // ⚠️ 用 `java.lang.Float.isFinite` 而不是 Kotlin 的 `Float.isFinite()`：
    // 扩展版在这条调用链上被 JIT 内联后行为不稳定（实测 5000f 被判成「非有限」，
    // 于是位移被清零、窗口完全不动）。显式调 java.lang 的静态方法不依赖内联决策。
    val dx = if (java.lang.Float.isFinite(totalDx)) totalDx else 0f
    val dy = if (java.lang.Float.isFinite(totalDy)) totalDy else 0f

    val minW = max(ABS_MIN_WINDOW_WIDTH_PX, limits.minWidth)
    val minH = max(ABS_MIN_WINDOW_HEIGHT_PX, limits.minHeight)

    var x = start.x
    var y = start.y
    // ⚠️ 起点尺寸本身可能小于最小尺寸（跨 DPI 后旧尺寸没跟上），先抬到最小值再算，
    // 否则下面用 start 计算的边界会与 w/h 自相矛盾
    val baseW = start.width.coerceIn(minW, MAX_WINDOW_SIDE_PX)
    val baseH = start.height.coerceIn(minH, MAX_WINDOW_SIDE_PX)
    var w = baseW
    var h = baseH
    val startRight = start.x + baseW
    val startBottom = start.y + baseH

    // 尺寸夹取统一走 [clampSize]（那里有踩坑记录）。
    //
    // 位移符号约定（与光标移动方向一致）：
    //   west  : dx < 0 → 向西扩；dx > 0 → 缩（右边界不动，左边界右移）
    //   east  : dx > 0 → 向东扩
    //   north : dy < 0 → 向北扩；dy > 0 → 缩（下边界不动，上边界下移）
    //   south : dy > 0 → 向南扩
    if (dir.west) {
        w = clampSize(baseW - dx, minW)
        x = startRight - w
    }
    if (dir.east) {
        w = clampSize(baseW + dx, minW)
    }
    if (dir.north) {
        h = clampSize(baseH - dy, minH)
        y = startBottom - h
    }
    if (dir.south) {
        h = clampSize(baseH + dy, minH)
    }

    // 西/北方向把窗口往外拖时会走出工作区。这里**只推位置、不动尺寸**：
    // 尺寸已经按光标算好了，再把窗口拽回来会得到「拖了却不变大」的诡异手感。
    // 允许左上角略微探出（最多探到「工作区边 + 窗口尺寸 − 最小尺寸」），
    // 保证至少还有最小尺寸那么大的一块在工作区内，用户抓得回来。
    val area = limits.workArea
    if (dir.west) {
        val minX = area.x - (w - minW)
        val maxX = startRight - minW
        x = if (minX > maxX) maxX else x.coerceIn(minX, maxX)
    }
    if (dir.north) {
        val minY = area.y - (h - minH)
        val maxY = startBottom - minH
        y = if (minY > maxY) maxY else y.coerceIn(minY, maxY)
    }

    return WindowGeometry(x, y, w, h)
}

// ── 最大化 / 还原 ─────────────────────────────────────────────────

/**
 * 最大化目标矩形 = 工作区（扣掉任务栏）。
 *
 * 无边框窗口不能用 `WindowPlacement.Maximized`（AWT 的 MAXIMIZED_BOTH 会把任务栏一起盖掉），
 * 所以自己给一块矩形。
 */
fun maximizedGeometry(limits: WindowLimits): WindowGeometry =
    WindowGeometry(limits.workArea.x, limits.workArea.y, limits.workArea.width, limits.workArea.height)

/**
 * 「当前窗口是不是已经铺满工作区」。
 *
 * 无边框窗口没有系统的最大化状态，只能比矩形。容差取
 * [MAXIMIZED_TOLERANCE_PX]，因为 DPI 换算取整会差 1~2 像素。
 */
fun isGeometryMaximized(geom: WindowGeometry, limits: WindowLimits): Boolean {
    val a = limits.workArea
    return kotlin.math.abs(geom.x - a.x) <= MAXIMIZED_TOLERANCE_PX &&
        kotlin.math.abs(geom.y - a.y) <= MAXIMIZED_TOLERANCE_PX &&
        kotlin.math.abs(geom.width - a.width) <= MAXIMIZED_TOLERANCE_PX &&
        kotlin.math.abs(geom.height - a.height) <= MAXIMIZED_TOLERANCE_PX
}

/**
 * 还原时要把窗口放回哪里。
 *
 * 三种异常都要接住，否则用户会得到一扇**找不回来的窗口**：
 *
 *  1. 还原尺寸比当前屏幕还大（换到更小的显示器 / 改过缩放）→ 夹到工作区 90%。
 *  2. 还原位置整个落在屏幕外（拔掉副屏）→ 拉回工作区内。
 *  3. 还原尺寸小于最小尺寸（理论上不会，但历史状态可能被写坏）→ 抬到最小尺寸。
 *
 * 第 2 条是真实场景：笔记本 + 外接双屏，窗口在副屏最大化，拔掉线再点还原，
 * 原来那套「直接 `restorePosition`」会让窗口落在已经不存在的坐标上。
 */
fun restoreGeometry(
    savedPosition: Pair<Int, Int>?,
    savedSize: Pair<Int, Int>?,
    limits: WindowLimits
): WindowGeometry {
    val area = limits.workArea
    // ⚠️ 高 DPI 下可能出空区间：200% 缩放要求最小高 1200，而这块屏工作区只有 1040。
    // 这时**工作区优先** —— 死守最小尺寸会把窗口撑出屏幕，用户反而更没法用。
    // 也正因为可能出现空区间，所有 coerceIn 都要先自己兜住，Kotlin 的
    // `coerceIn` 在 min > max 时会直接抛 IllegalArgumentException。
    // 上限先取「工作区 90%」，但不得高于工作区本身，也不得低于最小尺寸
    val capW = (area.width * 0.9).roundToInt().coerceIn(1, area.width)
    val capH = (area.height * 0.9).roundToInt().coerceIn(1, area.height)
    val minW = max(ABS_MIN_WINDOW_WIDTH_PX, limits.minWidth).coerceAtMost(capW)
    val minH = max(ABS_MIN_WINDOW_HEIGHT_PX, limits.minHeight).coerceAtMost(capH)

    // 尺寸：没有历史就用「工作区 90%」，太大就缩到 90%，太小就抬到最小尺寸
    val rawW = savedSize?.first ?: capW
    val rawH = savedSize?.second ?: capH
    val w = rawW.coerceIn(minW, capW)
    val h = rawH.coerceIn(minH, capH)

    // 位置：没有历史就居中；有历史但整体不可见就拉回工作区
    val x = if (savedPosition == null) area.x + (area.width - w) / 2 else savedPosition.first
    val y = if (savedPosition == null) area.y + (area.height - h) / 2 else savedPosition.second

    val maxX = max(area.x, area.x + area.width - w)
    val caption = captionReservePx(h)
    val maxY = max(area.y, area.y + area.height - caption)
    return WindowGeometry(x.coerceIn(area.x, maxX), y.coerceIn(area.y, maxY), w, h)
}

/** 还原时纵向至少要让标题栏露出来。给个保守值，避免为了算它去读 UI 常量 */
private fun captionReservePx(windowHeight: Int): Int =
    min(windowHeight, max(32, windowHeight / 6))

// ── 历史窗口状态（dp ↔ 物理像素） ─────────────────────────────────

/**
 * 最大化之前记下的窗口位置与尺寸，单位是 **dp**（`WindowState` 的原生单位）。
 *
 * 存 dp 而不是像素：显示器缩放可能在两次点击之间被改掉（插拔外接屏、
 * 用户在系统设置里调缩放），存像素的话「还原」会按新缩放解释旧像素数，窗口大小跳一档。
 */
data class SavedWindowRect(
    val xDp: Float,
    val yDp: Float,
    val widthDp: Float,
    val heightDp: Float
)

/** dp → 物理像素。缩放非法时按 1.0 处理 */
fun savedToPx(saved: SavedWindowRect?, scale: Double): Pair<Pair<Int, Int>?, Pair<Int, Int>?> {
    if (saved == null) return null to null
    val s = if (scale > 0.0) scale else 1.0
    val pos = (saved.xDp * s).roundToInt() to (saved.yDp * s).roundToInt()
    val size = (saved.widthDp * s).roundToInt() to (saved.heightDp * s).roundToInt()
    return pos to size
}

/**
 * 「最大化状态下按住标题栏拖动」的完整决策：先算还原矩形，再算光标锚点。
 *
 * 与 Windows 一致的行为是「一拖就还原，且光标仍按在标题栏的相对位置」。
 * 难点在于还原后窗口比最大化时小得多，若把光标直接放在还原窗口左上角，
 * 用户会觉得窗口从脚底下被抽走了。所以按**相对比例**映射：
 * 最大化时按在宽度 60% 处，还原后也让它落在 60% 处。
 *
 * 返回 `(还原矩形, 光标应处的锚点)`，两者都在物理像素坐标系。
 */
fun dragFromMaximizedPlan(
    savedPosition: Pair<Int, Int>?,
    savedSize: Pair<Int, Int>?,
    limits: WindowLimits,
    anchorX: Float,
    anchorY: Float
): Pair<WindowGeometry, Pair<Int, Int>> {
    val restored = restoreGeometry(savedPosition, savedSize, limits)
    val ax = anchorX.coerceIn(0f, 1f)
    val ay = anchorY.coerceIn(0f, 1f)
    val px = restored.x + (restored.width * ax).roundToInt()
    val py = restored.y + (restored.height * ay).roundToInt()
    return restored to (px to py)
}

// ── 热区命中 ──────────────────────────────────────────────────────

/**
 * 判断某个本地坐标该落到哪个缩放方向。
 *
 * 和 Compose 里「边铺满 + 角最后叠上去」的兄弟节点顺序保持一致：
 * 先判角（角优先），再判边，都不是就返回 null。
 *
 * @param localX/localY 相对窗口左上角的坐标（dp 或 px 都行，只要 pocket 同单位）
 */
fun hitTestResizeDir(localX: Float, localY: Float, width: Float, height: Float, border: Float): ResizeDir? {
    if (border <= 0f) return null
    val nearLeft = localX <= border
    val nearRight = localX >= width - border
    val nearTop = localY <= border
    val nearBottom = localY >= height - border

    if (nearLeft && nearTop) return ResizeDir.NW
    if (nearRight && nearTop) return ResizeDir.NE
    if (nearLeft && nearBottom) return ResizeDir.SW
    if (nearRight && nearBottom) return ResizeDir.SE
    if (nearTop) return ResizeDir.N
    if (nearBottom) return ResizeDir.S
    if (nearLeft) return ResizeDir.W
    if (nearRight) return ResizeDir.E
    return null
}

/**
 * 把「期望边长」夹到 `[min, MAX_WINDOW_SIDE_PX]`。
 *
 * ⚠️ 这段代码踩过一个很隐蔽的坑，改动前务必跑 `WindowGeometryTest`：
 * 初版写成 `val upper = MAX_WINDOW_SIDE_PX.toFloat()` 再 `minOf(upper, want)`，
 * Kotlin 把这个局部 val 当成了**闭包捕获**（编译成 `Ref.FloatRef` 装箱），
 * 在 `Math.min` 那一层被内联后边界值失效 —— 「往内拖缩小」会算成 5900。
 * 现在全部用内联字面量，不引入任何中间局部变量。
 */
internal fun clampSize(want: Float, min: Int): Int {
    val clamped = minOf(MAX_WINDOW_SIDE_PX.toFloat(), want)
    val floored = maxOf(min.toFloat(), clamped)
    return floored.roundToInt().coerceIn(min, MAX_WINDOW_SIDE_PX)
}

/** 把 double 向下取整成 int，避免 `roundToInt` 在 .5 处跳一像素导致的抖动 */
internal fun floorToInt(v: Double): Int = floor(v).toInt()
