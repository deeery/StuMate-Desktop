package com.example.classreminder.ui.fluent

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// ── 主题模式 ────────────────────────────────────────────────────

enum class ThemeMode(val label: String) {
    FOLLOW_SYSTEM("跟随系统"),
    LIGHT("浅色"),
    DARK("深色")
}

// ── 设计 token ──────────────────────────────────────────────────
//
// 皮肤：Windows 11 Fluent。
// 与手机版 Material 3 的关键差异：
//  - 强调色走 Windows 的系统蓝（浅色 #0067C0 / 深色 #60CDFF），不是 Google Blue
//  - 控件圆角 4dp、卡片 6dp（Material 版是 8/12dp），整体更「方」
//  - 分层靠「底色 + 1px 描边」，而不是 elevation 阴影
//  - 字号整体下调一档（正文 13sp），行高收到 34dp —— 桌面一屏要装更多信息

@Immutable
data class FluentColors(
    /** 窗口主区底色（Mica 观感的近似） */
    val bg: Color,
    /** 卡片 / 面板底 */
    val surface: Color,
    /** 侧边栏底 */
    val surface2: Color,
    /** 悬停填充、胶囊底 */
    val surface3: Color,
    val onSurface: Color,
    val onSurfaceVariant: Color,
    val onSurfaceFaint: Color,
    /** 常规分隔线 */
    val outline: Color,
    /** 控件描边（比 outline 明显） */
    val outlineStrong: Color,
    /**
     * **常驻描边**：课表格子里每一个课程块的默认边框（见 `WeekPage.CourseBlock`）。
     *
     * 为什么不复用 [outline] / [outlineStrong]：那两个是给「容器边界」用的，
     * 差值只有 5–7 级，压在 [surface3] 上等于看不见 —— 实测过，肉眼真的找不到。
     * 这里要的是 12–15 级：**边界能读出来，但不像刻上去的硬槽**。
     *
     * 方向也是反的：深色下比卡片面**亮**（发光，像 Fluent 的分层），
     * 浅色下比卡片面**暗**（凹陷，符合纸质表格直觉）。
     * 早先取 `bg`（深色 #141414）在 surface3 上差 31 级，边界虽清楚但过于硬，
     * 整张表看着像一堆挖空的小格子 —— 深色主题里「亮边」才是不扎眼的那个方向。
     */
    val borderRest: Color,
    /**
     * 表格内部的**时间网格线**（每小时一条的横线、每天的竖向分栏线）。
     *
     * 为什么单独一个 token，不复用 [borderRest] / [outline]：
     *  - [borderRest] 是给「课程块」那种**实心小面**用的，要能读成一块的边界；
     *    网格线是**背景纹理**，用它会把表格切成硬格，看着像电子表格而不是课表。
     *  - [outline] 是容器边界（卡片本身），比网格线更淡一档，压在卡片上就等于没有。
     *
     * 深色下比卡片面略亮（+6 级左右）—— 深色里「亮一点」是最不扎眼的加线方式；
     * 浅色下比卡片面略暗，符合纸质表格的直觉。**两端都刻意做得很淡**：
     * 表格的层次感应该主要来自斑马纹和课程块，网格线只负责「定位」。
     */
    val gridLine: Color,
    /**
     * 表格斑马纹（每小时交替的横向色带）。
     *
     * 只用极淡的 alpha，不引入新色相 —— 它的作用是「让眼睛扫行时有参照物」，
     * 一浓就会和课程块抢注意力，整张表变得花。深色下用白、浅色下用黑，
     * 这样在两种主题里都是「提亮 / 压暗」同一个方向：**离卡片面更远**。
     */
    val gridZebra: Color,
    /** 强调色 */
    val accent: Color,
    val onAccent: Color,
    /** 选中项底色 */
    val accentTint: Color,
    /** 强调色悬停底 */
    val accentHover: Color,
    val error: Color,
    val warning: Color,
    val success: Color,
    /** 通用悬停 / 按下填充 */
    val hover: Color,
    val press: Color,
    val isDark: Boolean
)

val FluentLightColors = FluentColors(
    bg = Color(0xFFF3F3F3),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFEBEBEB),
    surface3 = Color(0xFFE0E0E0),
    onSurface = Color(0xFF1A1A1A),
    onSurfaceVariant = Color(0xFF5C5C5C),
    onSurfaceFaint = Color(0xFF8A8A8A),
    outline = Color(0xFFE5E5E5),
    outlineStrong = Color(0xFFC8C8C8),
    borderRest = Color(0xFFD2D2D2),
    // 浅色：卡片面是纯白 #FFFFFF，网格线压到 #E8E8E8 —— 能看见线，但不会把白面切成格子。
    gridLine = Color(0xFFE8E8E8),
    // 浅色斑马纹用黑、alpha 4%：约等于 #F5F5F5，比纯白微微沉一点，扫行时刚好有参照。
    gridZebra = Color(0x0A000000),
    accent = Color(0xFF0067C0),
    onAccent = Color(0xFFFFFFFF),
    accentTint = Color(0x170067C0),
    accentHover = Color(0x0F0067C0),
    error = Color(0xFFC42B1C),
    warning = Color(0xFF9D5D00),
    success = Color(0xFF0F7B0F),
    hover = Color(0x09000000),
    press = Color(0x0F000000),
    isDark = false
)

val FluentDarkColors = FluentColors(
    // 窗口最底层的那块底板 —— 也就是承载卡片、「最后面」的那一层。
    // 按验收意见从 #202020 调深到 #141414：底板压暗后，叠在上面的
    // 卡片（surface #2B2B2B）与侧栏（surface2 #272727）层次才拉得开。
    // 注意：页头 [PageTopBar] 与它共用这个 token，所以页头会一起变深；
    // 若想让页头保持浅一档，需要单独拆一个 token 出来。
    bg = Color(0xFF141414),
    surface = Color(0xFF2B2B2B),
    surface2 = Color(0xFF272727),
    surface3 = Color(0xFF333333),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFFC5C5C5),
    onSurfaceFaint = Color(0xFF8A8A8A),
    outline = Color(0xFF3A3A3A),
    outlineStrong = Color(0xFF4A4A4A),
    borderRest = Color(0xFF404040),
    // 深色：卡片面 #2B2B2B，网格线提到 #363636 —— 比 outline(#3A3A3A) 略淡，
    // 但比「底板色当线」那版（#141414，差 23 级，会像刻出来的硬槽）柔和得多。
    gridLine = Color(0xFF363636),
    // 深色斑马纹用白、alpha 3%：约 +7 级，不至于和课程块（#333333）糊在一起。
    gridZebra = Color(0x08FFFFFF),
    accent = Color(0xFF60CDFF),
    onAccent = Color(0xFF003A5C),
    accentTint = Color(0x1F60CDFF),
    accentHover = Color(0x1260CDFF),
    error = Color(0xFFFF99A4),
    warning = Color(0xFFFCE100),
    success = Color(0xFF6CCB5F),
    hover = Color(0x0EFFFFFF),
    press = Color(0x16FFFFFF),
    isDark = true
)

@Immutable
data class FluentDimens(
    val radiusCard: Dp = 6.dp,
    val radiusControl: Dp = 4.dp,
    val radiusSmall: Dp = 3.dp,
    val rowHeight: Dp = 34.dp,
    val sidebarWidth: Dp = 212.dp,
    val topBarHeight: Dp = 52.dp,
    val pagePadding: Dp = 16.dp
)

val LocalFluentColors = staticCompositionLocalOf { FluentLightColors }
val LocalFluentDimens = staticCompositionLocalOf { FluentDimens() }

object FluentTheme {
    val colors: FluentColors
        @Composable @ReadOnlyComposable get() = LocalFluentColors.current

    val dimens: FluentDimens
        @Composable @ReadOnlyComposable get() = LocalFluentDimens.current
}

/** 偏好里的 0/1/2 → [ThemeMode] */
fun Int.toThemeMode(): ThemeMode = when (this) {
    1 -> ThemeMode.LIGHT
    2 -> ThemeMode.DARK
    else -> ThemeMode.FOLLOW_SYSTEM
}

fun ThemeMode.ordinalValue(): Int = ordinal

@Composable
fun FluentTheme(themeMode: ThemeMode, content: @Composable () -> Unit) {
    val isDark = when (themeMode) {
        ThemeMode.FOLLOW_SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    CompositionLocalProvider(
        LocalFluentColors provides if (isDark) FluentDarkColors else FluentLightColors,
        LocalFluentDimens provides FluentDimens()
    ) {
        content()
    }
}

// ── 便签调色盘 ──────────────────────────────────────────────────
//
// 8 种可选颜色，顺序即色号（与手机版一致，所以备份里的 colorIndex 直接通用）。
// 色值换成 Windows 11 的 Fluent 色板：浅色底用 600 档，深色底用 300 档。

private val NotePaletteLight = listOf(
    Color(0xFF0067C0),   // 0 Windows 蓝
    Color(0xFF038387),   // 1 青
    Color(0xFF0F7B0F),   // 2 绿
    Color(0xFFC07800),   // 3 琥珀
    Color(0xFFCA5010),   // 4 橙
    Color(0xFFC42B1C),   // 5 红
    Color(0xFF8430CE),   // 6 紫
    Color(0xFF5C5C5C)    // 7 灰
)

private val NotePaletteDark = listOf(
    Color(0xFF60CDFF),   // 0 Windows 蓝（深色强调色）
    Color(0xFF4CC2C4),   // 1 青
    Color(0xFF6CCB5F),   // 2 绿
    Color(0xFFFCE100),   // 3 黄
    Color(0xFFF7630C),   // 4 橙
    Color(0xFFFF99A4),   // 5 红
    Color(0xFFC586C0),   // 6 紫
    Color(0xFF9A9A9A)    // 7 灰
)

/** 取便签色号对应的颜色。越界收敛到 0，避免脏数据把 UI 打挂 */
@Composable
fun fluentNoteColor(index: Int): Color {
    val palette = if (FluentTheme.colors.isDark) NotePaletteDark else NotePaletteLight
    return palette[index.coerceIn(0, palette.lastIndex)]
}

/** 整个调色盘，顺序即色号 */
@Composable
fun fluentNotePalette(): List<Color> =
    if (FluentTheme.colors.isDark) NotePaletteDark else NotePaletteLight
