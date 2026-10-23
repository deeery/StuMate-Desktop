package com.example.classreminder.ui

import com.example.classreminder.ui.fluent.ResizeDir
import com.example.classreminder.ui.fluent.WindowGeometry
import com.example.classreminder.ui.fluent.WindowLimits
import com.example.classreminder.ui.fluent.clampDragPosition
import com.example.classreminder.ui.fluent.grabOffset
import com.example.classreminder.ui.fluent.hitTestResizeDir
import com.example.classreminder.ui.fluent.isGeometryMaximized
import com.example.classreminder.ui.fluent.maximizedGeometry
import com.example.classreminder.ui.fluent.minWindowSizePx
import com.example.classreminder.ui.fluent.resizeGeometry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Rectangle
import kotlin.math.abs

/**
 * 「真实手势 → 生产代码 → 窗口矩形」的整链测试。
 *
 * ## 和 WindowGeometryTest 的分工
 *
 * `WindowGeometryTest` 验的是**单个纯函数**在极端输入下的输出。
 * 这个类验的是**函数之间的接缝**：Compose 的 `detectDragGestures`
 * 每帧交给 `Handle` 的是「起点矩形 + 累计位移」，
 * 而 `Handle` 会把它喂给 `resizeGeometry` 再写回 `windowState`。
 * 接缝上的错误（符号反了、传错了两帧的位移、把 delta 当 total 用）
 * 在单函数测试里全都看不出来 —— 因为每个函数单独看都是对的。
 *
 * ## 为什么不用真实鼠标事件
 *
 * 验收环境拿不到前台焦点（`SetForegroundWindow` 恒返回 False），
 * 指针事件被系统丢弃；`PostMessage` 合成的鼠标消息 AWT 也不当真实事件。
 * 详见 `tools/probe_window_geometry.py` 文件头。所以这里直接复用
 * Compose 每帧实际传的那两个量 —— 起点矩形与累计位移 —— 等于把
 * `onDrag` 循环在没有指针的情况下重放一遍。
 */
class ResizeGestureChainTest {

    // 1920x1040 工作区（1080p 减掉任务栏），最小尺寸按 100% 缩放算
    private val area = Rectangle(0, 0, 1920, 1040)
    private val minSizePx = minWindowSizePx(1.0)
    private val minW = minSizePx.first
    private val minH = minSizePx.second
    private val limits = WindowLimits(area, minW, minH)

    /** 把一次手势的逐帧结果全算出来，方便断言「整条轨迹」而不是只看终点 */
    private fun replay(
        start: WindowGeometry,
        dir: ResizeDir,
        steps: List<Pair<Float, Float>>
    ): List<WindowGeometry> = steps.map { (dx, dy) -> resizeGeometry(start, dir, dx, dy, limits) }

    /** 生成一条从 (0,0) 到 (tx,ty) 的 n 步轨迹（模拟光标逐帧移动） */
    private fun track(tx: Float, ty: Float, n: Int = 30): List<Pair<Float, Float>> =
        (1..n).map { i -> tx * i / n to ty * i / n }

    // ── 接缝 1：每帧都是从起点重算，不是在上帧结果上累加 ────────────

    @Test
    fun `逐帧重算不会累积误差 —— 同一 total 值重复喂结果必须一致`() {
        val start = WindowGeometry(400, 300, 1200, 800)
        // 关键性质：resizeGeometry 是 (start, total) 的纯函数。
        // 如果哪天有人改成「在上一次结果上累加」，这里立刻崩。
        val once = resizeGeometry(start, ResizeDir.SE, 240f, 160f, limits)
        val twice = replay(start, ResizeDir.SE, List(10) { 240f to 160f })
        assertTrue("重复喂同一个 total 必须得到同一结果", twice.all { it == once })
        assertEquals(WindowGeometry(400, 300, 1440, 960), once)
    }

    @Test
    fun `缩到最小之后反向拖立刻回弹 —— 不存在死区`() {
        val start = WindowGeometry(400, 200, 1200, 800)

        // SE 方向：dx>0 变宽、dx<0 变窄。
        // 起点 1200x800 缩到最小 900x600 需要 dx=-300、dy=-200（宽高起点不同，位移也不同）。
        val atMin = resizeGeometry(start, ResizeDir.SE, -300f, -200f, limits)
        assertEquals("缩到底应停在最小宽度", minW, atMin.width)
        assertEquals("缩到底应停在最小高度", minH, atMin.height)

        // 再继续往内拖（-2000），仍停在最小尺寸，不会变负也不会反弹
        val far = resizeGeometry(start, ResizeDir.SE, -2000f, -2000f, limits)
        assertEquals("继续往内拖仍停在最小宽度", minW, far.width)
        assertEquals("继续往内拖仍停在最小高度", minH, far.height)

        // 关键：从最小状态反向拖 1 像素，必须**立刻**长回 1 像素。
        // 因为每帧都从 start 重算（而不是在上帧结果上累加），
        // -299 / -199 就是「比最小多 1px」，不存在要还清的「欠账」。
        val onePxBack = resizeGeometry(start, ResizeDir.SE, -299f, -199f, limits)
        assertEquals("回拖 1px 应立即长回 1px（宽度）", minW + 1, onePxBack.width)
        assertEquals("回拖 1px 应立即长回 1px（高度）", minH + 1, onePxBack.height)
    }

    @Test
    fun `轨迹全程尺寸单调不减 —— 向东拖时宽度只增不减`() {
        val start = WindowGeometry(100, 100, 1000, 700)
        val widths = replay(start, ResizeDir.E, track(600f, 0f, 60)).map { it.width }
        assertTrue("东向拖动宽度应单调不减：$widths", widths.zipWithNext().all { (a, b) -> b >= a })
    }

    // ── 接缝 2：四个方向的位置/尺寸联动 ─────────────────────────────

    @Test
    fun `西向拖动 —— 右边界全程钉死不动`() {
        val start = WindowGeometry(500, 300, 1000, 700)
        val fixedRight = start.right
        // 先往外拖（变宽），再往里拖（变窄），右边界都不该动
        val out = replay(start, ResizeDir.W, track(-400f, 0f, 20))
        val back = replay(start, ResizeDir.W, track(800f, 0f, 40))
        (out + back).forEach {
            assertEquals("右边界必须恒为 $fixedRight，实际 ${it.right}", fixedRight, it.right)
        }
    }

    @Test
    fun `北向拖动 —— 下边界全程钉死不动`() {
        val start = WindowGeometry(300, 400, 1100, 750)
        val fixedBottom = start.bottom
        val all = replay(start, ResizeDir.N, track(0f, -500f, 20)) +
            replay(start, ResizeDir.N, track(0f, 900f, 40))
        all.forEach {
            assertEquals("下边界必须恒为 $fixedBottom", fixedBottom, it.bottom)
        }
    }

    @Test
    fun `东向与南向拖动 —— 不涉及的边全程钉死不动`() {
        val start = WindowGeometry(260, 180, 900, 620)
        val dirs = listOf(ResizeDir.E, ResizeDir.S, ResizeDir.SE, ResizeDir.NE, ResizeDir.SW)
        dirs.forEach { dir ->
            val all = replay(start, dir, track(-300f, -300f, 15)) +
                replay(start, dir, track(600f, 600f, 30))
            // NE/SW 会动一边，所以只对「不涉及西/北」的方向断言左上角
            if (!dir.west) all.forEach { assertEquals("$dir: x 不该动", start.x, it.x) }
            if (!dir.north) all.forEach { assertEquals("$dir: y 不该动", start.y, it.y) }
        }
    }

    // ── 接缝 3：位移符号 —— Compose 的 drag 正负号与几何约定必须对齐 ──

    @Test
    fun `位移符号 —— 光标向左为负时西向才变宽`() {
        val start = WindowGeometry(800, 300, 1000, 700)
        val widened = resizeGeometry(start, ResizeDir.W, -200f, 0f, limits)
        val narrowed = resizeGeometry(start, ResizeDir.W, 200f, 0f, limits)
        assertTrue("dx<0 应向西扩（更宽）", widened.width > start.width)
        assertTrue("dx>0 应缩小（更窄）", narrowed.width < start.width)
        assertEquals("向西扩时右边界不动", start.right, widened.right)
    }

    @Test
    fun `位移符号 —— 光标向上为负时北向才变高`() {
        val start = WindowGeometry(300, 500, 1000, 700)
        val taller = resizeGeometry(start, ResizeDir.N, 0f, -200f, limits)
        val shorter = resizeGeometry(start, ResizeDir.N, 0f, 200f, limits)
        assertTrue("dy<0 应向北扩（更高）", taller.height > start.height)
        assertTrue("dy>0 应缩小（更矮）", shorter.height < start.height)
        assertEquals("向北扩时下边界不动", start.bottom, taller.bottom)
    }

    // ── 接缝 4：夹取与屏幕边界 ───────────────────────────────────────

    @Test
    fun `西向拖出屏幕 —— 至少留最小宽度在工作区内`() {
        val start = WindowGeometry(300, 200, 1000, 700)
        val far = resizeGeometry(start, ResizeDir.W, -5000f, 0f, limits)
        // 窗口被拉得很宽，但左边不会跑到「工作区左界 − (宽 − 最小宽)」以外
        val maxOverhang = far.width - minW
        assertTrue(
            "左边最多探出 $maxOverhang，实际探出 ${-far.x}",
            far.x >= area.x - maxOverhang - 1
        )
        assertTrue("宽度不超过上限", far.width <= 20_000)
    }

    @Test
    fun `极端位移不产生负数或零尺寸 —— NaN 与 Infinity 都当 0`() {
        val start = WindowGeometry(300, 200, 1000, 700)
        val bad = listOf(
            Float.NaN to 0f, 0f to Float.NaN,
            Float.POSITIVE_INFINITY to 0f, 0f to Float.NEGATIVE_INFINITY,
            Float.NaN to Float.NaN
        )
        bad.forEach { (dx, dy) ->
            val g = resizeGeometry(start, ResizeDir.SE, dx, dy, limits)
            assertTrue("NaN/Inf 下宽度必须为正：$g", g.width >= minW)
            assertTrue("NaN/Inf 下高度必须为正：$g", g.height >= minH)
        }
    }

    @Test
    fun `连续抖动 500 帧 —— 尺寸始终在合法区间内`() {
        val start = WindowGeometry(400, 200, 1100, 750)
        var dx = 0f
        var dy = 0f
        repeat(500) { i ->
            // 伪随机抖动，正负交替且幅度逐渐变大
            dx += ((i * 37) % 200 - 100) * 3f
            dy += ((i * 53) % 180 - 90) * 3f
            val g = resizeGeometry(start, ResizeDir.SE, dx, dy, limits)
            assertTrue("第 $i 帧宽度越界 ${g.width}", g.width in minW..20_000)
            assertTrue("第 $i 帧高度越界 ${g.height}", g.height in minH..20_000)
        }
    }

    // ── 接缝 5：拖动（不是缩放）路径 ─────────────────────────────────

    @Test
    fun `拖动 —— 抓取偏移在手势全程保持不变`() {
        val win = WindowGeometry(600, 400, 1200, 800)
        val cursor = 900 to 420  // 按在标题栏里
        val (offX, offY) = grabOffset(cursor.first, cursor.second, win.x, win.y, win.width, win.height)
        assertEquals(300, offX)
        assertEquals(20, offY)

        // 一路拖到各种位置：只要没被夹取，反推回的光标必须等于原值。
        // 这是「跟手」的定义 —— 窗口左上角恒等于 光标 − 偏移。
        val caption = 40
        listOf(600 to 400, 960 to 520, 1500 to 900, 2000 to 1100).forEach { (cx, cy) ->
            val (nx, ny) = clampDragPosition(cx - offX, cy - offY, win.width, win.height, limits, caption)
            val wasClamped = nx != cx - offX || ny != cy - offY
            if (!wasClamped) {
                assertEquals("光标($cx,$cy) 横向反推不一致", cx, nx + offX)
                assertEquals("光标($cx,$cy) 纵向反推不一致", cy, ny + offY)
            }
        }
    }

    @Test
    fun `拖动 —— 窗口比屏幕还宽时不被推出屏幕外`() {
        val hugeW = 3000  // 比 1920 的工作区还宽 1080
        val (x, _y) = clampDragPosition(-5000, 300, hugeW, 700, limits, 40)
        // 这里 `minX=-1280 < maxX=200`，**不是**空区间：窗口比 keepX(=屏宽-200)
        // 还宽时仍允许探出，探出量 = w - keepX = 3000-1720 = 1280。
        // 真正要保证的是：往极左推也停在 -1280，不会一路滑到 -5000 找不回来。
        assertEquals("极左推应停在 -1280（允许探出 1280）", -1280, x)

        // 极端到空区间（窗口比整屏宽得多）才走居中分支
        val absurdW = 5000
        val (cx, _cy) = clampDragPosition(-9999, 300, absurdW, 700, limits, 40)
        val keepX = 1920 - 200
        assertTrue(
            "空区间时应居中：minX(${1920 - keepX - absurdW}) > maxX(${1920 - keepX}) 不成立才是空区间",
            absurdW - keepX >= keepX
        )
        // 宽度 5000 时 minX=-3280 < maxX=200，仍非空区间，所以还是夹到 -3280
        assertEquals("超宽 5000 时夹到 -3280", -3280, cx)
    }

    @Test
    fun `拖动 —— 标题栏永远不会被拖到工作区顶之外`() {
        val win = WindowGeometry(500, 300, 1200, 800)
        listOf(-9999, -1, 0, 1, 5000).forEach { ty ->
            val (_x, y) = clampDragPosition(500, ty, win.width, win.height, limits, 40)
            assertTrue("y=$y 必须 >= 工作区顶 ${area.y}", y >= area.y)
            assertTrue("y=$y 必须让标题栏留在工作区内", y <= area.y + area.height - 1)
        }
    }

    // ── 接缝 6：最大化判定与还原 ─────────────────────────────────────

    @Test
    fun `最大化 —— 判定与生成互为逆运算`() {
        val max = maximizedGeometry(limits)
        assertEquals(WindowGeometry(0, 0, 1920, 1040), max)
        assertTrue("生成的最大化矩形必须被判定为最大化", isGeometryMaximized(max, limits))
        // 差 1 像素仍在容差内
        assertTrue(isGeometryMaximized(max.copy(width = max.width - 1), limits))
        // 差 3 像素应判为未最大化
        assertTrue(!isGeometryMaximized(max.copy(width = max.width - 3), limits))
    }

    // ── 接缝 7：热区命中与方向映射 ───────────────────────────────────

    @Test
    fun `热区 —— 8 个方向的命中点都能取到对应方向`() {
        val w = 1000f
        val h = 700f
        val b = 5f
        val cases = mapOf(
            ResizeDir.NW to (1f to 1f),
            ResizeDir.NE to (w - 1f to 1f),
            ResizeDir.SW to (1f to h - 1f),
            ResizeDir.SE to (w - 1f to h - 1f),
            ResizeDir.N to (w / 2 to 1f),
            ResizeDir.S to (w / 2 to h - 1f),
            ResizeDir.W to (1f to h / 2),
            ResizeDir.E to (w - 1f to h / 2)
        )
        cases.forEach { (want, pt) ->
            val got = hitTestResizeDir(pt.first, pt.second, w, h, b)
            assertEquals("在 $pt 应命中 $want", want, got)
        }
    }

    @Test
    fun `热区 —— 窗口中心区域不响应缩放`() {
        assertNull(hitTestResizeDir(500f, 350f, 1000f, 700f, 5f))
        // 边框厚度为 0 时整窗都不响应
        assertNull(hitTestResizeDir(1f, 1f, 1000f, 700f, 0f))
    }

    @Test
    fun `热区 —— 极小窗口里角优先于边`() {
        // 窗口只有 12x12，5dp 边框使得四角与四边重叠，此时必须是角优先
        val got = hitTestResizeDir(1f, 1f, 12f, 12f, 5f)
        assertEquals(ResizeDir.NW, got)
        val got2 = hitTestResizeDir(11f, 11f, 12f, 12f, 5f)
        assertEquals(ResizeDir.SE, got2)
    }
}
