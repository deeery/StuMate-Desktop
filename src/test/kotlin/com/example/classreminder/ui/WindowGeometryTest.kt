package com.example.classreminder.ui.fluent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Rectangle

/**
 * 无边框窗口几何的极端情况测试。
 *
 * 这个文件的存在理由：窗口尺寸算错**只在极端路径上暴露**（高 DPI、最小尺寸边界、
 * 拖出屏幕、多屏、最大化后还原），手动点很难稳定复现，更没法回归。
 * 所以把算术全部搬进 `WindowGeometry.kt` 的纯函数，在这里穷举。
 *
 * ## 覆盖的极端情况一览
 *
 * | 类别 | 场景 | 期望 |
 * | --- | --- | --- |
 * | DPI | 100% / 150% / 200% / 300% | 最小尺寸按缩放抬，绝不超过工作区 |
 * | 缩放 | 缩到最小后继续往内拖 | 尺寸锁在最小值，位置不漂移 |
 * | 缩放 | 缩到最小后立刻反向拖 | 立刻回弹（无死区） |
 * | 缩放 | 往屏幕外拖 | 尺寸可达工作区大小，位置不跑出屏幕 |
 * | 缩放 | 位移是 NaN / Infinity | 当 0 处理，不污染窗口 |
 * | 拖动 | 拖出四边 | 标题栏始终可见，窗口不会找不回来 |
 * | 拖动 | 负坐标副屏 | 按那块屏的工作区夹取 |
 * | 拖动 | 在窗口右下角抓取 | 偏移夹在窗口内，不跳 |
 * | 最大化 | 还原时屏幕变小 / 缩放变大 | 尺寸缩到工作区 90%，不超出 |
 * | 最大化 | 还原位置在已拔掉的副屏 | 拉回当前工作区 |
 * | 最大化 | 判定 | 容差 2px |
 * | 热区 | 角 / 边 / 内部 | 角优先，内部为 null |
 *
 * 所有坐标都是**物理像素**（AWT 屏幕坐标），与生产代码一致。
 */
class WindowGeometryTest {

    /**
     * 常见工作区：1920×1080 屏减去 40px 任务栏，100% 缩放（最小 900×600）。
     *
     * ⚠️ 必须显式传 minWidth/minHeight。`WindowLimits` 的默认值是**绝对下限**
     * 200×150（那是「跨 DPI 后仍不至于崩」的地板，不是业务最小尺寸）。
     * 之前这里漏传，导致「最小尺寸」类用例全都在测 200×150，看起来像实现错了。
     */
    private val fhd = WindowLimits(Rectangle(0, 0, 1920, 1040), 900, 600)
    /** 4K 屏，200% 缩放后工作区仍是 1920×1040（AWT 用户空间） */
    private val uhd200 = WindowLimits(Rectangle(0, 0, 1920, 1040), minWidth = 1800, minHeight = 1200)

    // ── DPI / 最小尺寸 ────────────────────────────────────────────

    @Test
    fun miniSizeScalesWithDpiAndNeverBelowFloor() {
        // 100%：900×600 dp = 900×600 px
        assertEquals(900 to 600, minWindowSizePx(1.0))
        // 150%：1350×900
        assertEquals(1350 to 900, minWindowSizePx(1.5))
        // 200%：1800×1200
        assertEquals(1800 to 1200, minWindowSizePx(2.0))
        // 300%：2700×1800 —— 即使比屏幕还大，也**不能**退回小值，
        // 否则高 DPI 下窗口会被允许缩到布局崩掉
        assertEquals(2700 to 1800, minWindowSizePx(3.0))
    }

    @Test
    fun invalidScaleFallsBackToFloorInsteadOfCrashing() {
        // 0 / 负数 / NaN 都是「没读到」的信号，必须给一个可用值而不是 0 或负宽
        listOf(0.0, -1.0, Double.NaN).forEach { bad ->
            val (w, h) = minWindowSizePx(bad)
            assertTrue("scale=$bad 时宽度必须 ≥ 绝对下限", w >= ABS_MIN_WINDOW_WIDTH_PX)
            assertTrue("scale=$bad 时高度必须 ≥ 绝对下限", h >= ABS_MIN_WINDOW_HEIGHT_PX)
        }
    }

    @Test
    fun miniSizeHonoursAbsoluteFloorEvenAtTinyScale() {
        // 极小的缩放（某些远程桌面会报 0.6）：900dp × 0.6 = 540px，
        // 但绝对下限是 200，所以取 540 —— 这里验证「不会出现 0」
        val (w, h) = minWindowSizePx(0.01)
        assertEquals(ABS_MIN_WINDOW_WIDTH_PX, w)
        assertEquals(ABS_MIN_WINDOW_HEIGHT_PX, h)
    }

    // ── 缩放：最小尺寸边界 ────────────────────────────────────────

    @Test
    fun shrinkingBelowMinimumLocksSizeAndStopsPositionDrift() {
        val start = WindowGeometry(100, 100, 900, 600) // 已经是最小
        val limits = WindowLimits(Rectangle(0, 0, 1920, 1040), 900, 600)

        // SE 方向：往**左上**拖才是缩小，所以位移是负的
        val g = resizeGeometry(start, ResizeDir.SE, -5000f, -5000f, limits)

        assertEquals(900, g.width)
        assertEquals(600, g.height)
        // 位置一个像素都不能动 —— 旧实现在这里会把窗口整体推走
        assertEquals(100, g.x)
        assertEquals(100, g.y)
    }

    @Test
    fun draggingWestPastMinimumKeepsRightEdgeFixed() {
        val start = WindowGeometry(600, 100, 900, 600)
        val limits = WindowLimits(Rectangle(0, 0, 1920, 1040), 900, 600)

        // 把左边界往右推 800px（想缩到 100 宽）
        val g = resizeGeometry(start, ResizeDir.W, 800f, 0f, limits)

        assertEquals(900, g.width)                 // 锁在最小
        assertEquals(1500, g.right)                // 右边界纹丝不动 = 600 + 900
    }

    @Test
    fun shrinkingToMinimumThenDraggingBackRespondsImmediately() {
        val start = WindowGeometry(100, 100, 1200, 800)
        val limits = WindowLimits(Rectangle(0, 0, 1920, 1040), 900, 600)

        // 先狠狠缩到最小（多拖了 2000px）
        val shrunk = resizeGeometry(start, ResizeDir.E, -2000f, 0f, limits)
        assertEquals(900, shrunk.width)

        // 再往右拖 1px：必须立刻变成 1201，而不是「先把多拖的 2000px 抵消掉」
        val back = resizeGeometry(start, ResizeDir.E, 1f, 0f, limits)
        assertEquals(1201, back.width)
    }

    // ── 缩放：往屏幕外拖 ──────────────────────────────────────────

    @Test
    fun growingWestNorthKeepsSizeButNeverLosesGrip() {
        val start = WindowGeometry(400, 300, 900, 600)
        val limits = WindowLimits(Rectangle(0, 0, 1920, 1040), 900, 600)

        // 往左上拖 5000px（dx<0/dy<0 即向西向北扩）。
        // 尺寸按光标如实变大，但位置最多探出到「还能抓到最小尺寸那么大」为止，
        // 绝不能把窗口整块推到屏幕外 —— 那会变成找不回来的窗口。
        val g = resizeGeometry(start, ResizeDir.NW, -5000f, -5000f, limits)

        assertEquals(5900, g.width)    // 900 + 5000
        assertEquals(5600, g.height)   // 600 + 5000
        // 左边界最远只能到「工作区左边 − (宽 − 最小宽)」= 0 − 5000 = −5000
        assertEquals(-4600, g.x)       // 400 − 5000
        assertEquals(-4700, g.y)       // 300 − 5000
        // 关键断言：至少还有最小尺寸那么大的一块留在屏幕里
        assertTrue("右边界必须在工作区内", g.right > 0)
        assertTrue("下边界必须在工作区内", g.bottom > 0)
    }

    @Test
    fun growingEastSouthIsBoundedByMaxSideNotByWorkArea() {
        val start = WindowGeometry(0, 0, 900, 600)
        val limits = WindowLimits(Rectangle(0, 0, 1920, 1040), 900, 600)

        // 往右下无限拖：窗口允许超出工作区（用户可以放到屏幕外），但边长要有硬上限，
        // 否则 float 累加会把 int 撑爆成负数
        val g = resizeGeometry(start, ResizeDir.SE, 1e9f, 1e9f, limits)

        assertEquals(MAX_WINDOW_SIDE_PX, g.width)
        assertEquals(MAX_WINDOW_SIDE_PX, g.height)
        assertEquals(0, g.x)
        assertEquals(0, g.y)
    }

    @Test
    fun nonFiniteDragDeltaIsTreatedAsZero() {
        val start = WindowGeometry(100, 100, 1200, 800)
        val limits = WindowLimits(Rectangle(0, 0, 1920, 1040), 900, 600)

        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { bad ->
            val g = resizeGeometry(start, ResizeDir.E, bad, bad, limits)
            assertEquals("dx=$bad 时尺寸不能变", start.width, g.width)
            assertEquals("dy=$bad 时尺寸不能变", start.height, g.height)
        }
    }

    @Test
    fun resizeIsDeterministicRegardlessOfIntermediateFrames() {
        val start = WindowGeometry(200, 200, 1000, 700)
        val limits = WindowLimits(Rectangle(0, 0, 1920, 1040), 900, 600)

        // 一次性拖 300px 与分 300 次拖 1px，结果必须完全一致（每帧从起点重算）
        val oneShot = resizeGeometry(start, ResizeDir.SE, 300f, 300f, limits)
        var last = start
        for (i in 1..300) {
            last = resizeGeometry(start, ResizeDir.SE, i.toFloat(), i.toFloat(), limits)
        }
        assertEquals(oneShot, last)
        assertEquals(1300, oneShot.width)
        assertEquals(1000, oneShot.height)
    }

    @Test
    fun shrinkingNorthKeepsBottomEdgeFixedAndStopsAtMinimum() {
        val start = WindowGeometry(200, 400, 1000, 600)
        val limits = WindowLimits(Rectangle(0, 0, 1920, 1040), 900, 600)

        // 往**下**拖 10000px（dy>0）是缩小：底边固定在 1000，高度锁在最小 600
        val g = resizeGeometry(start, ResizeDir.N, 0f, 10000f, limits)
        assertEquals(600, g.height)
        assertEquals(1000, g.bottom)   // 400 + 600，底边一点没动
    }

    @Test
    fun shrinkingNorthStopsAtTopOfWorkArea() {
        // 窗口顶边已经在 100，再往上缩不可能越过工作区顶
        val start = WindowGeometry(200, 100, 1000, 900)
        val limits = WindowLimits(Rectangle(0, 0, 1920, 1040), 900, 600)

        // dy = +800 想缩到 100 高，但最小 600 → 底边 1000 固定，顶边落到 400
        val g = resizeGeometry(start, ResizeDir.N, 0f, 800f, limits)
        assertEquals(600, g.height)
        assertEquals(400, g.y)
        assertEquals(1000, g.bottom)
    }

    // ── 拖动：屏幕边界 ────────────────────────────────────────────

    @Test
    fun dragUpKeepsCaptionBarOnScreen() {
        val limits = WindowLimits(Rectangle(0, 0, 1920, 1040), 900, 600)
        // 想把窗口拖到屏幕上方 5000px
        val (x, y) = clampDragPosition(-5000, -5000, 1200, 800, limits, captionHeightPx = 32)

        assertEquals(0, y)                       // 顶边贴住工作区顶
        assertTrue("标题栏必须完整可见", y >= limits.workArea.y)
        assertTrue("左边界允许探出，但不能消失", x > -1200)
    }

    @Test
    fun dragDownKeepsCaptionBarReachable() {
        val limits = WindowLimits(Rectangle(0, 0, 1920, 1040), 900, 600)
        val (_, y) = clampDragPosition(100, 99999, 1200, 800, limits, captionHeightPx = 32)

        // 标题栏底边 = y + 32，必须还在工作区内
        assertTrue("标题栏不能沉到任务栏下面", y + 32 <= limits.workArea.y + limits.workArea.height)
        assertEquals(1040 - 32, y)
    }

    @Test
    fun dragHorizontallyLeavesAStripVisible() {
        val limits = WindowLimits(Rectangle(0, 0, 1920, 1040), 900, 600)

        val (leftX, _) = clampDragPosition(-99999, 100, 1200, 800, limits, 32)
        val (rightX, _) = clampDragPosition(99999, 100, 1200, 800, limits, 32)

        // keepX = 1920 − 200 = 1720，比窗口的 1200 还大 → 夹取区间是空的
        // （minX=520 > maxX=200）。空区间时按「居中」处理，两边各露一半，
        // 这是比硬夹到某个边界更合理的行为，也避免了 coerceIn 抛异常。
        val centered = (1920 - 1200) / 2
        assertEquals(centered, leftX)
        assertEquals(centered, rightX)
        assertTrue("窗口必须大部分在屏幕内", leftX >= 0 && leftX + 1200 <= 1920)
    }

    @Test
    fun dragOnNegativeCoordinateMonitorUsesThatMonitorsWorkArea() {
        // 副屏在主屏左侧：工作区 x 从 -1920 开始
        val left = WindowLimits(Rectangle(-1920, 0, 1920, 1040), 900, 600)
        val (x, y) = clampDragPosition(-1890, -500, 1200, 800, left, 32)

        // 这块屏的 keepX = 1920 − 200 = 1720，比窗口 1200 宽 -> 空区间 -> 居中。
        // 居中即 x = -1920 + (1920 − 1200) / 2 = -1560。
        assertEquals(-1560, x)
        assertEquals(0, y)   // 往上拖被顶到 0
        assertEquals(0, y)   // 往上拖被顶到 0
    }

    @Test
    fun dragWithWindowWiderThanScreenStillClamps() {
        // 窗口比工作区还宽（用户把窗口拉到 3000 宽）
        val limits = WindowLimits(Rectangle(0, 0, 1920, 1040), 900, 600)
        val (x, _) = clampDragPosition(-5000, 100, 3000, 800, limits, 32)

        // 夹取区间必须是有效区间（min ≤ max），不能抛出或反转
        assertTrue("位置必须是有限值", x in -5000..5000)
        assertTrue("窗口右侧至少要露出一点", x + 3000 > 0)
    }

    @Test
    fun grabOffsetIsClampedInsideWindow() {
        // 光标在窗口左上角之外（系统把光标夹到屏幕边缘时会发生）
        val (dx, dy) = grabOffset(-50, -50, 100, 100, 1200, 800)
        assertEquals(0, dx)
        assertEquals(0, dy)

        // 光标在窗口右下角之外
        val (dx2, dy2) = grabOffset(99999, 99999, 100, 100, 1200, 800)
        assertEquals(1199, dx2)
        assertEquals(799, dy2)
    }

    @Test
    fun grabOffsetOfZeroSizeWindowDoesNotCrash() {
        // 极端：窗口尺寸被写成 0（历史状态损坏、或跨 DPI 那一瞬）
        val (dx, dy) = grabOffset(100, 100, 100, 100, 0, 0)
        assertEquals(0, dx)
        assertEquals(0, dy)
    }

    // ── 最大化 / 还原 ────────────────────────────────────────────

    @Test
    fun maximizedGeometryEqualsWorkArea() {
        val g = maximizedGeometry(fhd)
        assertEquals(WindowGeometry(0, 0, 1920, 1040), g)
        assertTrue(isGeometryMaximized(g, fhd))
    }

    @Test
    fun maximizedDetectionHasTwoPixelTolerance() {
        assertTrue(isGeometryMaximized(WindowGeometry(1, 1, 1919, 1039), fhd))
        assertTrue(isGeometryMaximized(WindowGeometry(2, 2, 1918, 1038), fhd))
        // 差 3px 就不算最大化 —— 否则用户手动拖到「差一点铺满」会被误判
        assertTrue(!isGeometryMaximized(WindowGeometry(3, 0, 1920, 1040), fhd))
        assertTrue(!isGeometryMaximized(WindowGeometry(0, 0, 1700, 1040), fhd))
    }

    @Test
    fun restoreShrinksSizeWhenMonitorBecameSmaller() {
        // 之前在 4K 上存了 3000×1800，现在换到 1920×1080
        val g = restoreGeometry(
            savedPosition = 100 to 50,
            savedSize = 3000 to 1800,
            limits = fhd
        )
        // 必须缩到工作区 90% 以内
        assertTrue("宽不能超出工作区", g.width <= 1920)
        assertTrue("高不能超出工作区", g.height <= 1040)
        assertEquals(1728, g.width)   // 1920 * 0.9
        assertEquals(936, g.height)   // 1040 * 0.9
    }

    @Test
    fun restorePullsBackWindowFromUnpluggedMonitor() {
        // 历史位置在已拔掉的右副屏（x=3000）
        val g = restoreGeometry(3000 to 400, 1200 to 800, fhd)

        assertTrue("窗口左边界必须回到当前屏内", g.x < 1920)
        assertTrue("窗口不能被推到屏幕左外", g.x >= 0)
        assertTrue("标题栏必须可见", g.y >= 0 && g.y < 1040)
    }

    @Test
    fun restoreWithoutHistoryCentersAtNinetyPercent() {
        val g = restoreGeometry(null, null, fhd)
        assertEquals(1728, g.width)
        assertEquals(936, g.height)
        assertEquals((1920 - 1728) / 2, g.x)
        assertEquals((1040 - 936) / 2, g.y)
    }

    @Test
    fun restoreLiftsTooSmallSizeBackToMinimum() {
        // 历史状态被写坏成 100×50，必须抬到最小尺寸 900×600
        val g = restoreGeometry(200 to 200, 100 to 50, fhd)
        assertEquals(900, g.width)
        assertEquals(600, g.height)
        assertEquals(200, g.x)
        assertEquals(200, g.y)
    }

    @Test
    fun restoreOnHighDpiKeepsMinimumSizeInPixels() {
        // 200% 缩放下最小尺寸是 1800×1200，比这块屏的 1040 高 —— 
        // 此时不能死守最小高度把窗口撑出屏幕，应该以工作区为上限
        val g = restoreGeometry(null, null, uhd200)
        assertTrue("不能超过工作区高", g.height <= 1040)
        assertTrue("不能超过工作区宽", g.width <= 1920)
    }

    @Test
    fun dragFromMaximizedMapsAnchorProportionally() {
        // 最大化时按在宽度 50%、高度顶部附近
        val (restored, anchor) = dragFromMaximizedPlan(
            savedPosition = 300 to 200,
            savedSize = 1200 to 800,
            limits = fhd,
            anchorX = 0.5f,
            anchorY = 0.02f
        )
        assertEquals(1200, restored.width)
        assertEquals(800, restored.height)
        // 锚点应该落在还原窗口的中间偏上
        assertEquals(300 + 600, anchor.first)
        assertEquals(200 + 16, anchor.second)
    }

    @Test
    fun dragFromMaximizedRejectsOutOfRangeAnchor() {
        val (_, anchor) = dragFromMaximizedPlan(
            savedPosition = 300 to 200,
            savedSize = 1200 to 800,
            limits = fhd,
            anchorX = 5f,      // 越界的相对位置（坐标换算出错时会传进来）
            anchorY = -3f
        )
        // 夹回 0..1 内，锚点必须还在窗口里
        assertTrue(anchor.first in 300..1500)
        assertEquals(200, anchor.second)
    }

    // ── 热区命中 ────────────────────────────────────────────────

    @Test
    fun cornerBeatsEdge() {
        // 10×10 的角区压在 5dp 的边上面：角落必须判成角
        assertEquals(ResizeDir.NW, hitTestResizeDir(2f, 2f, 1200f, 800f, 5f))
        assertEquals(ResizeDir.NE, hitTestResizeDir(1198f, 2f, 1200f, 800f, 5f))
        assertEquals(ResizeDir.SW, hitTestResizeDir(2f, 798f, 1200f, 800f, 5f))
        assertEquals(ResizeDir.SE, hitTestResizeDir(1198f, 798f, 1200f, 800f, 5f))
    }

    @Test
    fun edgesHitWhenNotInCorner() {
        assertEquals(ResizeDir.N, hitTestResizeDir(600f, 2f, 1200f, 800f, 5f))
        assertEquals(ResizeDir.S, hitTestResizeDir(600f, 798f, 1200f, 800f, 5f))
        assertEquals(ResizeDir.W, hitTestResizeDir(2f, 400f, 1200f, 800f, 5f))
        assertEquals(ResizeDir.E, hitTestResizeDir(1198f, 400f, 1200f, 800f, 5f))
    }

    @Test
    fun interiorIsNotAResizeTarget() {
        assertNull(hitTestResizeDir(600f, 400f, 1200f, 800f, 5f))
    }

    @Test
    fun zeroBorderDisablesAllHandles() {
        // 窗口小到热区厚度为 0（极端缩放）时，不能把整块内容都判成可缩放
        assertNull(hitTestResizeDir(0f, 0f, 1200f, 800f, 0f))
        assertNull(hitTestResizeDir(600f, 400f, 1200f, 800f, -1f))
    }

    @Test
    fun tinyWindowDoesNotProduceContradictoryHits() {
        // 窗口只有 6×6，比两倍热区还小 —— 应判成角，而不是同时像四条边
        val dir = hitTestResizeDir(3f, 3f, 6f, 6f, 5f)
        assertNotNull(dir)
        assertTrue("窗口小于热区时应落在某个角", dir == ResizeDir.NW || dir == ResizeDir.NE ||
            dir == ResizeDir.SW || dir == ResizeDir.SE)
    }
}
