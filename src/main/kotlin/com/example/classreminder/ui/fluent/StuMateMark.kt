package com.example.classreminder.ui.fluent

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * StuMate 品牌标识：圆角方块 + 课表九宫格 + 高亮待提醒课格 + Lucide 铃铛。
 *
 * ## 设计意图
 *
 * **不用字母**，纯图形表意「课表 + 到点提醒」：
 *
 * - **外轮廓**沿用旧版侧栏品牌方块的比例：圆角 118/512 ≈ 23%，与旧版
 *   26dp 方块上的 `RoundedCornerShape(6.dp)` 同比例，视觉上不跳。
 * - **九宫格**＝课表的时间格。
 * - **右上角实心琥珀格**＝即将开始、等着被提醒的那一节课。
 * - **铃铛**＝到点提醒本身，**逐字取自 Lucide `bell`，保持原版描边样式**。
 *
 * ## 铃铛来源与许可
 *
 * 两条 path（主体 + 摆锤弧）逐字取自 [Lucide `bell`](https://lucide.dev/icons/bell)，
 * 描边参数也照搬原图：`stroke-width="2"`、`stroke-linecap="round"`、`stroke-linejoin="round"`、
 * `fill="none"`。Lucide 采用 **ISC** 许可（`bell` 不在其 Feather/MIT 衍生清单内，
 * 故无 MIT 附加义务）：
 *
 * ```
 * ISC License
 *
 * Copyright (c) 2026 Lucide Icons and Contributors
 *
 * Permission to use, copy, modify, and/or distribute this software for any purpose
 * with or without fee is hereby granted, provided that the above copyright notice
 * and this permission notice appear in all copies.
 *
 * THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES WITH
 * REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT,
 * INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM LOSS
 * OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT, NEGLIGENCE OR OTHER
 * TORTIOUS ACTION, ARISING OUT OF OR IN CONNECTION WITH THE USE OR PERFORMANCE OF
 * THIS SOFTWARE.
 * ```
 *
 * 同一份声明也放在项目根目录 `THIRD-PARTY-NOTICES.md` —— ISC 要求
 * 「appear in all copies」，两处都留才稳妥。
 *
 * ## 相对原图只改了「缩放与落位」
 *
 * 形状、描边宽度、端点样式全部保持 Lucide 原样，**没有**改写成填充剪影。
 * 唯一的加工是把 24×24 坐标系整体映射到本图标的 512 画布上（见 [BELL_SCALE]）。
 *
 * ## 铃铛比例（为什么不是「圣诞树」）
 *
 * 铃铛读起来像锥形圣诞树的根因是**圆顶相对底沿太窄**，跟高宽比无关。
 * Lucide 本身就在主流开源铃铛的健康区间内：
 *
 * | 指标 | 本图标（Lucide 原值） | Lucide | Bootstrap | Heroicons |
 * |---|---|---|---|---|
 * | 圆顶直径 ÷ 底沿宽 | **68.7%** | 68.7% | 71% | 76% |
 * | 铃身高 ÷ 底沿宽 | **0.86** | 0.86 | 0.86 | 0.83 |
 *
 * ⚠️ 改比例时**先保证圆顶 ≥65% 底沿宽**，否则又会退化成锥形。
 * 早期自绘版本圆顶只有底沿的 46%，侧腰从 108 斜张到 192，渲染出来就是个锥体。
 *
 * ## 为什么手写矢量，而不是加依赖
 *
 * 与 [GitHubMark] 同样的理由：本项目构建是 `--offline` 的，加不了矢量图依赖，
 * 也没有资源目录。`ImageVector` 是纯几何数据，不需要网络、不需要资源文件。
 *
 * ## 用色
 *
 * 底色与墨色取当前主题的 `accent` / `onAccent`（见 [FluentTheme]），所以深浅两套
 * 主题各自成立；高亮格固定用琥珀 [HighlightSlot]，**不**跟主题的 `warning`
 * ——浅色主题的 `warning` 是 `#9D5D00`（深褐），压在 `#0067C0` 上会糊成一团。
 *
 * 渲染时必须 `Icon(..., tint = Color.Unspecified)` 关掉整体染色，
 * 否则 Material3 会把整张图刷成单色，琥珀高亮格和底色一起消失。
 */
val StuMateBrandTile = Color(0xFF60CDFF)
val StuMateBrandInk = Color(0xFF003A5C)

/** 待提醒课格的高亮色。琥珀在两种底色上都够跳，所以不跟主题走 */
private val HighlightSlot = Color(0xFFFFB900)

/** 当前主题下的品牌标识。换主题时会重建（key 是配色） */
@Composable
fun stuMateMark(): ImageVector {
    val c = FluentTheme.colors
    return remember(c.accent, c.onAccent) { stuMateMark(c.accent, c.onAccent) }
}

/**
 * 指定配色的品牌标识。
 *
 * @param tile 圆角方块底色
 * @param ink 九宫格与铃铛的墨色
 */
fun stuMateMark(tile: Color, ink: Color): ImageVector {
    val builder = ImageVector.Builder(
        name = "StuMateMark",
        // 侧栏由 `Modifier.size(26.dp)` 覆盖；托盘 / 任务栏图标直接用这个固有尺寸，
        // 给 64 而不是 512，免得系统托盘拿到一张 512px 的位图
        defaultWidth = 64.dp,
        defaultHeight = 64.dp,
        viewportWidth = 512f,
        viewportHeight = 512f
    )

    // 1) 外轮廓：圆角方块
    builder.addPath(
        pathData = addPathNodes(TILE_PATH),
        fill = SolidColor(tile)
    )

    // 2) 课表九宫格（高亮格留空，单独画）
    for (y in GRID_STOPS) {
        for (x in GRID_STOPS) {
            if (x == HIGHLIGHT_X && y == HIGHLIGHT_Y) continue
            builder.addPath(
                pathData = addPathNodes(roundedRect(x, y, CELL, CELL, CELL_RADIUS)),
                fill = SolidColor(ink),
                fillAlpha = 0.18f
            )
        }
    }

    // 3) 待提醒的课格
    builder.addPath(
        pathData = addPathNodes(roundedRect(HIGHLIGHT_X, HIGHLIGHT_Y, CELL, CELL, CELL_RADIUS)),
        fill = SolidColor(HighlightSlot)
    )

    // 4) 铃铛。几何留在 Lucide 的 24×24 坐标系里原样引用，
    //    缩放 / 定位交给 group 变换，这样 path 数据能与上游逐字对齐、改尺寸只动两个数。
    //
    //    注意 Compose 1.5.10 的 `addGroup` **没有尾随 lambda**，它把 group 挂到当前 builder 上，
    //    然后返回一个「作用域指向该 group」的新 builder —— addPath 落在哪个 builder 上就画进哪个 group。
    //
    //    group 变换语义（已反编译 ui-desktop-1.5.10 的 GroupComponent.updateMatrix 确认）：
    //    `translate(tx+pivotX, ty+pivotY) · rotateZ · scale(sx,sy) · translate(-pivotX,-pivotY)`，
    //    pivot 取 0 时即 `p → s·p + t`；且该矩阵被 concat 进画布变换，
    //    所以 group 内的 strokeLineWidth 也会按 s 一起缩放。
    val bell = builder.addGroup(
        name = "lucide-bell",
        scaleX = BELL_SCALE,
        scaleY = BELL_SCALE,
        translationX = BELL_TX,
        translationY = BELL_TY
    )
    val bellStroke = SolidColor(ink)
    // 摆锤弧。Lucide 把摆锤画在主体路径之前，两条描边参数完全相同
    bell.addPath(
        pathData = addPathNodes(BELL_CLAPPER),
        stroke = bellStroke,
        strokeLineWidth = BELL_STROKE,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round
    )
    // 主体。Lucide 原图不闭合（没有 Z），这里也不加 —— 加了会在底沿多出一条封口线
    bell.addPath(
        pathData = addPathNodes(BELL_BODY),
        stroke = bellStroke,
        strokeLineWidth = BELL_STROKE,
        strokeLineCap = StrokeCap.Round,
        strokeLineJoin = StrokeJoin.Round
    )

    return builder.build()
}

// ── 几何常量（512×512 画布） ────────────────────────────────────

private const val CELL = 96f
private const val CELL_RADIUS = 24f
private val GRID_STOPS = listOf(88f, 208f, 328f)
private const val HIGHLIGHT_X = 328f
private const val HIGHLIGHT_Y = 88f

/** 圆角方块：四段直线 + 四段 1/4 圆弧 */
private const val TILE_PATH =
    "M118 0 H394 A118 118 0 0 1 512 118 V394 A118 118 0 0 1 394 512 " +
        "H118 A118 118 0 0 1 0 394 V118 A118 118 0 0 1 118 0 Z"

/**
 * 铃铛主体 —— **Lucide `bell`（ISC）原 path，逐字搬运，一个字符都没改**。
 *
 * 坐标系是 Lucide 的 24×24。形状要点：圆顶是 `A6 6 0 0 0 6 8`（半径 6、直径 12），
 * 底沿最宽处 17.478（x 3.262 → 20.74），故圆顶占底沿 **68.7%**；
 * 铃身高 15（y 2 → 17），高宽比 **0.86**。
 *
 * ⚠️ 这是**描边**路径（Lucide 原图 `fill="none"`），末尾故意不加 `Z`：
 * 加了闭合会在底沿多画一条封口线，和 Lucide 的样子对不上。
 */
private const val BELL_BODY =
    "M3.262 15.326A1 1 0 0 0 4 17h16a1 1 0 0 0 .74-1.673" +
        "C19.41 13.956 18 12.499 18 8A6 6 0 0 0 6 8c0 4.499-1.411 5.956-2.738 7.326"

/** 铃铛摆锤 —— Lucide `bell` 的第二条 path，同样逐字搬运 */
private const val BELL_CLAPPER = "M10.268 21a2 2 0 0 0 3.464 0"

/** 描边宽度，沿用 Lucide 原图的 `stroke-width="2"`（单位是 24 空间） */
private const val BELL_STROKE = 2f

/**
 * 铃铛缩放系数与落位（24 空间 → 512 空间）。
 *
 * Lucide 铃铛的**视觉**外接框（含 2 单位描边各外扩 1）是 19.478 × 22，中心 (12.001, 12)。
 * 取 `12.6` 让视觉宽落在 **245**，比 v2.1 的 236 略大一点 ——
 * 描边版比填充版的视觉分量轻，不放大一点在 26dp 下会显得比旧图标小一圈。
 * 落到 512 画布后铃铛占 x 133→379、y 117→395，描边实际宽 25.2 单位（26dp 下约 1.3px）。
 *
 * `tx` / `ty` 就是把外接框中心平移到画布中心 (256, 256)。
 */
private const val BELL_SCALE = 12.6f
private const val BELL_TX = 104.79f
private const val BELL_TY = 104.8f

/** 生成圆角矩形的 path 数据 */
private fun roundedRect(x: Float, y: Float, w: Float, h: Float, r: Float): String {
    val right = x + w
    val bottom = y + h
    return "M${x + r} $y " +
        "H${right - r} A$r $r 0 0 1 $right ${y + r} " +
        "V${bottom - r} A$r $r 0 0 1 ${right - r} $bottom " +
        "H${x + r} A$r $r 0 0 1 $x ${bottom - r} " +
        "V${y + r} A$r $r 0 0 1 ${x + r} $y Z"
}
