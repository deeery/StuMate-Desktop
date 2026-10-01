package com.example.classreminder.ui.fluent

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Windows 11 Fluent 控件库。
 *
 * 刻意不直接用 Material 3 的 Button / Switch / TextField：M3 的控件是「胶囊 + 阴影 + 大圆角」，
 * 和 Fluent 的「4dp 圆角 + 1px 描边 + 扁平」是两套语言，混在一起会很杂。
 * 这里每个控件都只用 foundation + 自绘，配色统一从 [FluentTheme.colors] 取。
 */

private const val HOVER_MS = 110

/** 在给定底色上选一个能看清的字色：亮底用深字，暗底用白字 */
private fun onColorFor(background: Color): Color {
    val luminance = 0.299f * background.red + 0.587f * background.green + 0.114f * background.blue
    return if (luminance > 0.62f) Color(0xFF1A1A1A) else Color.White
}

private fun Color.brighten(factor: Float): Color = if (factor == 1f) this else Color(
    red = (red * factor).coerceAtMost(1f),
    green = (green * factor).coerceAtMost(1f),
    blue = (blue * factor).coerceAtMost(1f),
    alpha = alpha
)

// ── 按钮 ────────────────────────────────────────────────────────

enum class FlButtonVariant { PRIMARY, GHOST, TEXT, DANGER }

@Composable
fun FlButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: FlButtonVariant = FlButtonVariant.PRIMARY,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    compact: Boolean = false
) {
    val c = FluentTheme.colors
    val d = FluentTheme.dimens
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    val base = when (variant) {
        FlButtonVariant.PRIMARY -> c.accent
        FlButtonVariant.GHOST -> c.surface
        FlButtonVariant.TEXT, FlButtonVariant.DANGER -> Color.Transparent
    }
    val textColor = when (variant) {
        FlButtonVariant.PRIMARY -> c.onAccent
        FlButtonVariant.GHOST -> c.onSurface
        FlButtonVariant.TEXT -> c.accent
        FlButtonVariant.DANGER -> c.error
    }
    val borderColor = if (variant == FlButtonVariant.GHOST || variant == FlButtonVariant.DANGER) c.outlineStrong else Color.Transparent

    val bg = when {
        !enabled -> base.copy(alpha = 0.35f)
        hovered -> when (variant) {
            FlButtonVariant.PRIMARY -> base
            FlButtonVariant.GHOST, FlButtonVariant.DANGER -> c.hover
            FlButtonVariant.TEXT -> c.accentHover
        }
        else -> base
    }
    val brighten by animateFloatAsState(
        if (hovered && variant == FlButtonVariant.PRIMARY && enabled) 1.12f else 1f,
        tween(HOVER_MS), label = "btnBright"
    )

    Row(
        modifier = modifier
            .height(if (compact) 28.dp else 32.dp)
            .clip(RoundedCornerShape(d.radiusControl))
            .background(if (variant == FlButtonVariant.PRIMARY) bg.brighten(brighten) else bg)
            .border(1.dp, borderColor, RoundedCornerShape(d.radiusControl))
            .hoverable(interaction, enabled)
            .clickable(interaction, indication = null, enabled = enabled) { onClick() }
            .padding(horizontal = if (compact) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = textColor, modifier = Modifier.size(if (compact) 14.dp else 15.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(
            text = text,
            fontSize = if (compact) 12.5.sp else 13.sp,
            fontWeight = if (variant == FlButtonVariant.PRIMARY) FontWeight.Medium else FontWeight.Normal,
            color = textColor.copy(alpha = if (enabled) 1f else 0.5f),
            maxLines = 1
        )
    }
}

/** 图标按钮。桌面端到处都用得上（行内编辑/删除、标题栏操作） */
@Composable
fun FlIconButton(
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
    size: Dp = 26.dp,
    contentDescription: String? = null
) {
    val c = FluentTheme.colors
    val d = FluentTheme.dimens
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(d.radiusControl))
            .background(if (hovered) c.press else Color.Transparent)
            .hoverable(interaction)
            .clickable(interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = tint ?: c.onSurfaceVariant,
            modifier = Modifier.size(size * 0.58f)
        )
    }
}

// ── 开关 ────────────────────────────────────────────────────────

@Composable
fun FlSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val c = FluentTheme.colors
    val track by animateColorAsState(if (checked) c.accent else c.surface3, tween(HOVER_MS), label = "swTrack")
    val border by animateColorAsState(if (checked) c.accent else c.outlineStrong, tween(HOVER_MS), label = "swBorder")
    val knobOffset by animateDpAsState(if (checked) 20.dp else 2.dp, tween(HOVER_MS), label = "swKnob")
    val knobColor by animateColorAsState(if (checked) c.onAccent else c.onSurfaceVariant, tween(HOVER_MS), label = "swKnobColor")

    Box(
        modifier = Modifier
            .size(width = 40.dp, height = 20.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(track)
            .border(1.dp, border, RoundedCornerShape(10.dp))
            .clickable { onCheckedChange(!checked) }
    ) {
        Box(
            modifier = Modifier
                .offset(x = knobOffset, y = 3.dp)
                .size(12.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(knobColor)
        )
    }
}

// ── 分段控件 ────────────────────────────────────────────────────

@Composable
fun FlSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val c = FluentTheme.colors
    val d = FluentTheme.dimens
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(d.radiusControl))
            .background(c.surface3)
            .padding(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        options.forEachIndexed { index, label ->
            val on = index == selectedIndex
            val interaction = remember { MutableInteractionSource() }
            val hovered by interaction.collectIsHoveredAsState()
            Box(
                modifier = Modifier
                    .height(26.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(if (on) c.surface else if (hovered) c.hover else Color.Transparent)
                    .hoverable(interaction)
                    .clickable(interaction, indication = null) { onSelect(index) }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    fontSize = 12.5.sp,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) c.onSurface else c.onSurfaceVariant
                )
            }
        }
    }
}

// ── 输入框 ──────────────────────────────────────────────────────

@Composable
fun FlTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    leadingIcon: ImageVector? = null,
    trailing: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true,
    enabled: Boolean = true,
    minHeight: Dp = 32.dp,
    /** 密码框传 `PasswordVisualTransformation()` */
    visualTransformation: VisualTransformation = VisualTransformation.None,
    /** 非空时按回车会触发它 —— 表单里「输完密码直接回车提交」 */
    onSubmit: (() -> Unit)? = null,
    /** 校验不通过时描边转红 */
    isError: Boolean = false
) {
    val c = FluentTheme.colors
    val d = FluentTheme.dimens
    var focused by remember { mutableStateOf(false) }
    val border by animateColorAsState(
        when {
            isError -> c.error
            focused -> c.accent
            else -> c.outlineStrong
        },
        tween(HOVER_MS), label = "fieldBorder"
    )

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        textStyle = TextStyle(fontSize = 13.sp, color = c.onSurface),
        cursorBrush = SolidColor(c.accent),
        visualTransformation = visualTransformation,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onSubmit?.invoke() }),
        modifier = modifier,
        onTextLayout = { },
        decorationBox = { inner ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .clip(RoundedCornerShape(d.radiusControl))
                    .background(c.surface)
                    .border(1.dp, border, RoundedCornerShape(d.radiusControl))
                    .padding(horizontal = 10.dp),
                verticalAlignment = if (singleLine) Alignment.CenterVertically else Alignment.Top
            ) {
                if (leadingIcon != null) {
                    Icon(leadingIcon, contentDescription = null, tint = c.onSurfaceFaint, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(8.dp))
                }
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = if (singleLine) 0.dp else 8.dp)
                ) {
                    if (value.isEmpty() && placeholder.isNotEmpty()) {
                        Text(placeholder, fontSize = 13.sp, color = c.onSurfaceFaint, maxLines = 1)
                    }
                    inner()
                }
                if (trailing != null) trailing()
            }
        }
    )
}

// ── 容器 ────────────────────────────────────────────────────────

@Composable
fun FlCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val c = FluentTheme.colors
    val d = FluentTheme.dimens
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(d.radiusCard))
            .background(c.surface)
            .border(1.dp, c.outline, RoundedCornerShape(d.radiusCard))
    ) { content() }
}

@Composable
fun FlDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(FluentTheme.colors.outline))
}

/** 小标签（分类徽章、状态标记） */
@Composable
fun FlChip(
    text: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
    filled: Boolean = false
) {
    val c = FluentTheme.colors
    val d = FluentTheme.dimens
    val fg = color ?: c.onSurfaceVariant
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(d.radiusSmall))
            .background(if (filled) fg.copy(alpha = if (c.isDark) 0.24f else 0.13f) else c.surface3)
            .padding(horizontal = 7.dp, vertical = 2.dp)
    ) {
        Text(text, fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = fg, maxLines = 1)
    }
}

/** 可点击的胶囊标签（分类选择器、筛选器） */
@Composable
fun FlToggleChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    accent: Color? = null
) {
    val c = FluentTheme.colors
    val d = FluentTheme.dimens
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val on = accent ?: c.accent
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(d.radiusControl))
            .background(if (selected) on else if (hovered) c.hover else c.surface)
            .border(1.dp, if (selected) on else c.outlineStrong, RoundedCornerShape(d.radiusControl))
            .hoverable(interaction)
            .clickable(interaction, indication = null) { onClick() }
            .padding(horizontal = 11.dp, vertical = 4.dp)
    ) {
        Text(
            text,
            fontSize = 12.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) onColorFor(on) else c.onSurfaceVariant,
            maxLines = 1
        )
    }
}

/** 区块标题（「接下来的课」「本周统计」这种） */
@Composable
fun FlSectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        fontSize = 11.5.sp,
        fontWeight = FontWeight.SemiBold,
        color = FluentTheme.colors.onSurfaceFaint,
        modifier = modifier
    )
}

/** 单行截断文本（表格里用） */
@Composable
fun FlOneLine(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    color: Color,
    modifier: Modifier = Modifier,
    weight: FontWeight = FontWeight.Normal
) {
    Text(
        text = text,
        fontSize = fontSize,
        color = color,
        fontWeight = weight,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
    )
}

// ── 对话框 ──────────────────────────────────────────────────────

@Composable
fun FlDialog(
    onDismiss: () -> Unit,
    title: String,
    width: Dp = 440.dp,
    /**
     * 是否允许「点对话框外面」或「按 Esc」把它关掉。
     *
     * 登录 / 注册这类对话框要传 `false`：里面是用户刚敲进去的邮箱、密码、邀请码，
     * 误点一下空白处或误按 Esc 就全没了，只能靠「取消」显式关闭。
     *
     * 这两个关闭途径在 Compose 里是两个独立开关（`dismissOnClickOutside` 管点击、
     * `dismissOnBackPress` 管 Esc —— 桌面端 Esc 就是走 back-press 这条路的），
     * 这里合成一个语义「能不能被外部操作关掉」，避免调用点每次都要写两遍。
     */
    dismissible: Boolean = true,
    content: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit
) {
    val c = FluentTheme.colors
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            dismissOnBackPress = dismissible,
            dismissOnClickOutside = dismissible
        )
    ) {
        Column(
            modifier = Modifier
                .width(width)
                .clip(RoundedCornerShape(8.dp))
                .background(c.surface)
                .border(1.dp, c.outlineStrong, RoundedCornerShape(8.dp))
                .padding(20.dp)
        ) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
            Spacer(Modifier.height(16.dp))
            content()
            Spacer(Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) { actions() }
        }
    }
}

/** 表单行：左侧标签 + 右侧控件 */
@Composable
fun FlSettingRow(
    label: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    control: @Composable () -> Unit
) {
    val c = FluentTheme.colors
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 13.sp, color = c.onSurface)
            if (detail != null) {
                Spacer(Modifier.height(2.dp))
                Text(detail, fontSize = 11.5.sp, color = c.onSurfaceFaint)
            }
        }
        Spacer(Modifier.width(16.dp))
        control()
    }
}

/** 空态占位 */
@Composable
fun FlEmptyState(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    action: (@Composable () -> Unit)? = null
) {
    val c = FluentTheme.colors
    Column(
        modifier = modifier.padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(title, fontSize = 14.sp, color = c.onSurfaceVariant, fontWeight = FontWeight.Medium)
        if (detail != null) {
            Spacer(Modifier.height(6.dp))
            Text(detail, fontSize = 12.5.sp, color = c.onSurfaceFaint)
        }
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}
