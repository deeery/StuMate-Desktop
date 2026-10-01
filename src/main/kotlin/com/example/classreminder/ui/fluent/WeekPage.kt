package com.example.classreminder.ui.fluent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.classreminder.Prefs
import com.example.classreminder.data.ClassEntity
import com.example.classreminder.data.MainViewModel
import com.example.classreminder.data.TimeAxis
import com.example.classreminder.data.TodaySchedule
import com.example.classreminder.data.WeekSchedule
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val AXIS_WIDTH = 58.dp
private val GRID_HEADER_HEIGHT = 30.dp
private const val DAY_MILLIS = 24L * 60L * 60L * 1000L

/**
 * 课程块「双击编辑」的判定窗口。
 *
 * 之所以自己数时间，而不用 `detectTapGestures(onTap, onDoubleTap)` / `combinedClickable`：
 * 那两个 API 为了让「单击」不与「双击」冲突，必须等双击超时（Compose 默认 300ms）才能
 * 确认这是一次单击 —— 于是**每一次单击都要僵住 300ms**，这就是「点击延迟极大」的根因。
 * 这里改成单击**立即**生效（选中本身是幂等的，重复触发无副作用），双击额外触发编辑。
 */
private const val DOUBLE_CLICK_MS = 420L

/** 与 `ClassEntity.dayOfWeek` 的取值一一对应（周一 → 周日） */
internal val DAY_NAMES = listOf(
    "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"
)

/**
 * 「课表」页（桌面版）。
 *
 * 桌面窗口够宽，所以**默认就是全周表格**，而且把课程详情做成**右侧固定面板**而不是
 * 手机版那种从底部浮出来的详情条 —— 鼠标点击的视线落点在右侧，信息出现在同一侧最自然。
 */
@Composable
fun WeekPage(
    viewModel: MainViewModel,
    onRequestCalibrate: () -> Unit,
    onRequestNewClass: () -> Unit,
    onOpenClass: (ClassEntity) -> Unit
) {
    val c = FluentTheme.colors
    val classes by viewModel.classes.collectAsState()

    val week1Monday = remember { Prefs.getWeek1Monday() }
    val calibrated = week1Monday != 0L
    val currentWeek = remember(week1Monday) { Prefs.currentWeek() }
    var shownWeek by remember(currentWeek) { mutableStateOf(currentWeek ?: 1) }
    var mode by remember { mutableStateOf(if (Prefs.isWeekGrid()) 0 else 1) }
    var selectedId by remember { mutableStateOf<Int?>(null) }

    val shownMonday = remember(week1Monday, shownWeek, calibrated) {
        if (calibrated) WeekSchedule.weekStart(week1Monday, shownWeek)
        else WeekSchedule.mondayOf(System.currentTimeMillis())
    }
    val dayNames = DAY_NAMES
    val dates = remember(shownMonday) { (0..6).map { TodaySchedule.dateOf(shownMonday + it * DAY_MILLIS) } }
    val todayIndex = remember(dates) { dates.indexOf(TodaySchedule.dateOf(System.currentTimeMillis())) }

    // 每一天该显示哪些课：临时提醒按日期命中，长期课程按「星期 + 周次」
    val dayClasses = remember(classes, dates, calibrated, shownWeek) {
        (0..6).map { i ->
            classes.filter { cls ->
                if (cls.date.isNotEmpty()) cls.date == dates[i]
                else cls.dayOfWeek == dayNames[i] &&
                    (!calibrated || WeekSchedule.contains(cls.weeks, shownWeek))
            }
        }
    }
    val total = dayClasses.sumOf { it.size }
    val selected = remember(selectedId, classes) { classes.firstOrNull { it.id == selectedId } }
    val weekStats = remember(dayClasses) { computeWeekStats(dayClasses) }

    Column(Modifier.fillMaxSize()) {
        PageTopBar(
            title = "课表",
            subtitle = weekRangeLabel(shownMonday)
        ) {
            FlButton("校准周数", onClick = onRequestCalibrate, variant = FlButtonVariant.GHOST, compact = true)
            FlButton("添加课程", onClick = onRequestNewClass, icon = Icons.Default.Add, compact = true)
        }

        Row(
            modifier = Modifier.fillMaxSize().padding(FluentTheme.dimens.pagePadding),
            horizontalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                // ── 工具行 ──
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconNavButton(Icons.Default.KeyboardArrowLeft) { shownWeek -= 1 }
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (calibrated) "第 $shownWeek 周" else "未校准",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = c.onSurface
                    )
                    Spacer(Modifier.width(6.dp))
                    IconNavButton(Icons.Default.KeyboardArrowRight) { shownWeek += 1 }
                    Spacer(Modifier.width(10.dp))
                    Text(weekRangeLabel(shownMonday), fontSize = 12.5.sp, color = c.onSurfaceVariant)
                    if (calibrated && currentWeek != null && shownWeek != currentWeek) {
                        Spacer(Modifier.width(10.dp))
                        FlButton("回到本周", onClick = { shownWeek = currentWeek }, variant = FlButtonVariant.TEXT, compact = true)
                    }
                    Spacer(Modifier.weight(1f))
                    FlSegmented(
                        options = listOf("表格", "列表"),
                        selectedIndex = mode,
                        onSelect = {
                            mode = it
                            Prefs.setWeekGrid(it == 0)
                        }
                    )
                }

                if (!calibrated) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(FluentTheme.dimens.radiusControl))
                            .background(c.accentTint)
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text(
                            "周数未校准 · 校准后课表和提醒都会只算本周的课",
                            fontSize = 12.sp,
                            color = c.accent
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }

                if (total == 0) {
                    FlCard(Modifier.fillMaxSize()) {
                        FlEmptyState(
                            title = "这一周没有课",
                            detail = "换个周次看看，或者添加一门课程",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else if (mode == 0) {
                    WeekGrid(
                        dayClasses = dayClasses,
                        todayIndex = todayIndex,
                        selectedId = selectedId,
                        onSelect = { selectedId = it.id },
                        onEdit = onOpenClass,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    WeekDayList(
                        dayClasses = dayClasses,
                        todayIndex = todayIndex,
                        selectedId = selectedId,
                        onSelect = { selectedId = it.id },
                        onEdit = onOpenClass,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // ── 右侧详情面板 ──
            selected?.let { sel ->
                ClassDetailPanel(
                    cls = sel,
                    stats = weekStats,
                    onEdit = { onOpenClass(sel) },
                    onDelete = {
                        viewModel.delete(sel)
                        selectedId = null
                    },
                    onClose = { selectedId = null }
                )
            }
        }
    }
}

private fun weekRangeLabel(monday: Long): String {
    val fmt = SimpleDateFormat("MM/dd", Locale.getDefault())
    val from = fmt.format(Date(monday))
    val to = fmt.format(Date(monday + 6 * DAY_MILLIS))
    return "$from ~ $to"
}

private data class WeekStats(val courses: Int, val hours: String, val busiest: String)

private fun computeWeekStats(dayClasses: List<List<ClassEntity>>): WeekStats {
    val labels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    val minutes = dayClasses.flatten().sumOf { cls ->
        val s = TimeAxis.minutesOf(cls.startTime) ?: return@sumOf 0
        val e = TimeAxis.minutesOf(cls.endTime) ?: return@sumOf 0
        (e - s).coerceAtLeast(0)
    }
    val busiestIdx = dayClasses.indices.maxByOrNull { dayClasses[it].size } ?: 0
    val hours = minutes / 60.0
    return WeekStats(
        courses = dayClasses.sumOf { it.size },
        hours = if (hours % 1.0 == 0.0) hours.toInt().toString() else String.format(Locale.getDefault(), "%.1f", hours),
        busiest = if (dayClasses[busiestIdx].isEmpty()) "—" else "${labels[busiestIdx]} · ${dayClasses[busiestIdx].size} 节"
    )
}

@Composable
private fun IconNavButton(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    val c = FluentTheme.colors
    val d = FluentTheme.dimens
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(d.radiusControl))
            .background(if (hovered) c.hover else c.surface)
            .border(1.dp, c.outlineStrong, RoundedCornerShape(d.radiusControl))
            .hoverable(interaction)
            .clickable(interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

// ── 表格模式 ────────────────────────────────────────────────────

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun WeekGrid(
    dayClasses: List<List<ClassEntity>>,
    todayIndex: Int,
    selectedId: Int?,
    onSelect: (ClassEntity) -> Unit,
    onEdit: (ClassEntity) -> Unit,
    modifier: Modifier
) {
    val c = FluentTheme.colors
    val d = FluentTheme.dimens
    val density = LocalDensity.current
    // 鼠标当前悬停在哪一天（-1 = 不在任何一天上）。表头与整列共用同一个值，
    // 这样「悬停表头 → 整列高亮」和「悬停列 → 表头跟着亮」是一致的。
    var hoveredDay by remember { mutableStateOf(-1) }
    val all = remember(dayClasses) { dayClasses.flatten() }
    val span = remember(all) { TimeAxis.spanOf(all) ?: TimeAxis.Span(8 * 60, 18 * 60) }
    val mapping = remember(span) { TimeAxis.mappingOf(span, null) }
    val marks = remember(span) { TimeAxis.roundMarks(span, 60) }
    val bands = remember(span) { TimeAxis.zebraBands(span) }
    val labels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

    val lineColor = c.outline
    // 时间格的描边线，用**底板色**（就是刚调深的那一层）：
    //  · 深色下它是 #141414，比卡片面 (#2B2B2B) 暗 —— 每个整点把这一列切开，
    //    格子底色本来和卡片面一模一样，不描边就是一大片，分不出节次；
    //  · 浅色下它就是底板那层 #F3F3F3，比纯白卡片面更浅一档，即「更白的线段」。
    // 直接复用 c.bg 而不是另立 token，是因为需求就是「和底板同色」；
    // 哪天要让网格线与底板脱钩，这里换成独立 token 即可。
    val timeLine = c.bg
    val zebraColor = if (c.isDark) Color(0x0AFFFFFF) else Color(0x06000000)
    val todayTint = c.accentTint

    /**
     * 把「分钟 → 纵坐标（像素）」算出来后**吸附到整像素**，再交给 [drawRect] 画 1 像素高的实心块。
     *
     * 为什么不用 `drawLine(y = 小数)`：抗锯齿会把 1px 的线按小数部分摊到相邻两行上，
     * 各占约一半浓度。而这条线本身只比底色深 20 来级（深色 `#141414` vs 卡片面 `#2B2B2B`），
     * 摊薄一半就基本看不出来了 —— 实测恰好有一半的整点落在半像素位置上。
     * 吸附之后，1 行就是实打实的 1 行，浓淡不打折。
     */
    fun lineY(heightPx: Float, minute: Int): Float =
        (heightPx * mapping.fractionOf(minute)).roundToInt().toFloat()

    BoxWithConstraints(
        modifier = modifier
            .clip(RoundedCornerShape(d.radiusCard))
            .background(c.surface)
            .border(1.dp, c.outline, RoundedCornerShape(d.radiusCard))
    ) {
        val gridWidth = maxWidth
        Column(
            modifier = Modifier
                .fillMaxSize()
                // 整张表的列 hover：拿指针 x 反算落在第几列。
                // 比给每个单元格挂 hoverable/Enter/Exit 更稳 —— 后者在「表头 → 同一天列」这种
                // 相邻节点间穿梭时，Exit 与 Enter 的派发顺序会让高亮闪断。
                .onPointerEvent(PointerEventType.Move) { event ->
                    val x = event.changes.first().position.x
                    val axisPx = with(density) { AXIS_WIDTH.toPx() }
                    val sepPx = with(density) { 1.dp.toPx() }
                    val colPx = (with(density) { gridWidth.toPx() } - axisPx - sepPx * 7f) / 7f
                    val rel = x - axisPx - sepPx
                    hoveredDay = if (colPx <= 0f || rel < 0f) -1
                    else (rel / (colPx + sepPx)).toInt().takeIf { it in 0..6 } ?: -1
                }
                .onPointerEvent(PointerEventType.Exit) { hoveredDay = -1 }
        ) {
            // 表头
            Row(Modifier.fillMaxWidth().height(GRID_HEADER_HEIGHT)) {
                Box(Modifier.width(AXIS_WIDTH).fillMaxHeight())
                labels.forEachIndexed { i, label ->
                    val isToday = i == todayIndex
                    val hovered = i == hoveredDay
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(if (isToday) todayTint else Color.Transparent)
                            .drawBehind { if (hovered) drawRect(c.hover) },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            fontSize = 12.sp,
                            fontWeight = if (isToday) FontWeight.Bold else FontWeight.SemiBold,
                            color = when {
                                isToday -> c.accent
                                hovered -> c.onSurface
                                else -> c.onSurfaceVariant
                            }
                        )
                        if (isToday) {
                            Box(
                                Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(bottom = 3.dp)
                                    .size(width = 26.dp, height = 2.dp)
                                    .clip(RoundedCornerShape(2.dp))
                                    .background(c.accent)
                            )
                        }
                    }
                    if (i < 6) Box(Modifier.width(1.dp).fillMaxHeight().background(lineColor))
                }
            }
            FlDivider()

            BoxWithConstraints(Modifier.fillMaxSize()) {
                val bodyHeight = maxHeight
                Row(Modifier.fillMaxSize()) {
                    // 纵轴刻度
                    Box(
                        Modifier
                            .width(AXIS_WIDTH)
                            .fillMaxHeight()
                            .drawBehind {
                                marks.forEach { m ->
                                    // 用 timeLine 而不是 lineColor：这条刻度线在视觉上
                                    // 与右边横跨各列的网格线是**同一条线**，两段不同色会像画错了
                                    drawRect(
                                        color = timeLine,
                                        topLeft = Offset(0f, lineY(size.height, m)),
                                        size = Size(size.width, 1f)
                                    )
                                }
                            }
                    ) {
                        marks.forEach { m ->
                            val y = bodyHeight * mapping.fractionOf(m)
                            Text(
                                TimeAxis.labelOf(m),
                                fontSize = 10.5.sp,
                                color = c.onSurfaceFaint,
                                modifier = Modifier
                                    .offset(y = y - 7.dp)
                                    // 给标签垫一层与卡片同色的底，让刻度线在文字处**断开**。
                                    // 刻度线改用底板色之后比原来的 outline 深了一倍多
                                    // （#141414 vs #3A3A3A），而标签是垂直居中压在刻度线上的，
                                    // 不垫底就会有一条深色横线从「08:00」正中间穿过去，像删除线。
                                    // 垫底后读起来就是标准的「08:00 ————」样式。
                                    .background(c.surface)
                                    .padding(start = 8.dp, end = 5.dp)
                            )
                        }
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(lineColor))

                    dayClasses.forEachIndexed { dayIndex, list ->
                        val isToday = dayIndex == todayIndex
                        val hovered = dayIndex == hoveredDay
                        BoxWithConstraints(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .background(if (isToday) todayTint else Color.Transparent)
                                .drawBehind {
                                    bands.forEach { band ->
                                        val top = size.height * mapping.fractionOf(band.startMinute)
                                        val bottom = size.height * mapping.fractionOf(band.endMinute)
                                        drawRect(
                                            color = zebraColor,
                                            topLeft = Offset(0f, top),
                                            size = Size(size.width, bottom - top)
                                        )
                                    }
                                    // 每个整点拉一条横线，把这一列切成一个个时间格。
                                    // 画在斑马纹之上、课程块之下（drawBehind 天然在内容之下），
                                    // 于是跨节的课会把线盖住，不会出现「线穿过课程卡片」。
                                    marks.forEach { m ->
                                        drawRect(
                                            color = timeLine,
                                            topLeft = Offset(0f, lineY(size.height, m)),
                                            size = Size(size.width, 1f)
                                        )
                                    }
                                    // 列高亮画在斑马纹之上、课程块之下
                                    if (hovered) drawRect(c.hover)
                                }
                        ) {
                            val colW = maxWidth
                            val colH = maxHeight
                            TimeAxis.layout(list, mapping).forEach { placed ->
                                val top = colH * placed.topFraction
                                val hgt = (colH * placed.heightFraction).coerceAtLeast(26.dp)
                                val left = colW * placed.leftFraction
                                val w = colW * placed.widthFraction
                                CourseBlock(
                                    cls = placed.cls,
                                    selected = placed.cls.id == selectedId,
                                    onSelect = { onSelect(placed.cls) },
                                    onEdit = { onEdit(placed.cls) },
                                    modifier = Modifier
                                        .offset(x = left + 3.dp, y = top + 2.dp)
                                        .size(width = (w - 6.dp).coerceAtLeast(20.dp), height = (hgt - 4.dp).coerceAtLeast(22.dp))
                                )
                            }
                        }
                        if (dayIndex < 6) Box(Modifier.width(1.dp).fillMaxHeight().background(lineColor))
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun CourseBlock(
    cls: ClassEntity,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    modifier: Modifier
) {
    val c = FluentTheme.colors
    val temporary = cls.date.isNotEmpty()
    val barColor = if (temporary) c.warning else c.accent
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    // 手动数双击：单击立刻生效（选中是幂等的），双击额外触发编辑。
    var lastPressAt by remember(cls.id) { mutableStateOf(0L) }

    val bg = if (selected) c.accentTint else c.surface3
    val hoverTint = if (hovered && !selected) c.accentHover else Color.Transparent

    // 描边分两层，职责不同：
    //  1) borderRest —— **常驻**描边，默认态就有。没有它，一节课在 `surface3` 上
    //     和旁边那节课、和底板都分不开，整张表看起来是「散落几块颜色」而不是「一节节课」。
    //  2) borderColor —— **交互态**描边，只在选中 / 悬浮时盖在上面提亮。
    val borderColor = when {
        selected -> c.accent
        hovered -> c.accent.copy(alpha = 0.65f)
        else -> c.borderRest
    }
    // 常驻描边 1dp；选中加粗到 1.5dp，让「选中」在视觉上压得住底下的常驻线。
    val borderWidth = if (selected) 1.5.dp else 1.dp

    Box(
        modifier = modifier
            // ⚠️ border 必须排在 clip **之前**：border 画在组件内侧，若跟在 clip 后面，
            //    4dp 圆角处会把它切出缺口，四角描边连不上（表现为四个小小的断口）。
            //    放到 clip 之前，描边完整地绕一圈，圆角处也是连续的。
            .border(borderWidth, borderColor, RoundedCornerShape(4.dp))
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            // 悬停染色叠在底色之上（src-over），比直接换一个实色更不容易在深浅色下翻车
            .drawBehind { if (hoverTint.alpha > 0f) drawRect(hoverTint) }
            .hoverable(interaction)
            .onPointerEvent(PointerEventType.Press) {
                val now = System.currentTimeMillis()
                if (now - lastPressAt in 1..DOUBLE_CLICK_MS) {
                    lastPressAt = 0L
                    onEdit()
                } else {
                    lastPressAt = now
                }
            }
            .clickable(interaction, indication = null) { onSelect() }
    ) {
        Box(
            Modifier
                .align(Alignment.CenterStart)
                .size(width = 3.dp, height = 26.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(barColor)
        )
        Column(Modifier.fillMaxSize().padding(start = 9.dp, end = 6.dp, top = 4.dp)) {
            Text(
                cls.title,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = c.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (cls.room.isNotBlank()) {
                Text(
                    cls.room,
                    fontSize = 10.5.sp,
                    color = c.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

// ── 列表模式 ────────────────────────────────────────────────────

@Composable
private fun WeekDayList(
    dayClasses: List<List<ClassEntity>>,
    todayIndex: Int,
    selectedId: Int?,
    onSelect: (ClassEntity) -> Unit,
    onEdit: (ClassEntity) -> Unit,
    modifier: Modifier
) {
    val c = FluentTheme.colors
    val labels = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    var collapsed by remember { mutableStateOf(setOf<Int>()) }

    FlCard(modifier) {
        LazyColumn(Modifier.fillMaxSize()) {
            dayClasses.forEachIndexed { index, list ->
                item(key = "h$index") {
                    val isToday = index == todayIndex
                    val headerInteraction = remember { MutableInteractionSource() }
                    val headerHovered by headerInteraction.collectIsHoveredAsState()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .background(if (isToday) c.accentTint else c.surface)
                            .drawBehind { if (headerHovered) drawRect(c.hover) }
                            .hoverable(headerInteraction)
                            .clickable(headerInteraction, indication = null) {
                                collapsed = if (index in collapsed) collapsed - index else collapsed + index
                            }
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            labels[index],
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isToday) c.accent else c.onSurface
                        )
                        if (isToday) {
                            Spacer(Modifier.width(8.dp))
                            FlChip("今日", color = c.accent, filled = true)
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            if (list.isEmpty()) "无课" else "${list.size} 节",
                            fontSize = 12.sp,
                            color = c.onSurfaceFaint
                        )
                        Spacer(Modifier.weight(1f))
                        Icon(
                            if (index in collapsed) Icons.Default.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
                            contentDescription = null,
                            tint = c.onSurfaceFaint,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    FlDivider()
                }
                if (index !in collapsed) {
                    if (list.isEmpty()) {
                        item(key = "e$index") {
                            Box(Modifier.fillMaxWidth().height(38.dp).padding(horizontal = 28.dp), contentAlignment = Alignment.CenterStart) {
                                Text("今天没有课，休息一下", fontSize = 12.5.sp, color = c.onSurfaceFaint)
                            }
                        }
                    } else {
                        items(list, key = { it.id }) { cls ->
                            val rowInteraction = remember { MutableInteractionSource() }
                            val rowHovered by rowInteraction.collectIsHoveredAsState()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp)
                                    .background(if (cls.id == selectedId) c.accentTint else Color.Transparent)
                                    .drawBehind { if (rowHovered && cls.id != selectedId) drawRect(c.hover) }
                                    .hoverable(rowInteraction)
                                    .clickable(rowInteraction, indication = null) { onSelect(cls) }
                                    .padding(start = 28.dp, end = 14.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.width(84.dp)) {
                                    Text(
                                        cls.startTime,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = c.accent
                                    )
                                    Text(cls.endTime, fontSize = 11.5.sp, color = c.onSurfaceFaint)
                                }
                                Box(Modifier.width(3.dp).height(24.dp).clip(RoundedCornerShape(2.dp)).background(if (cls.date.isNotEmpty()) c.warning else c.accent))
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(cls.title, fontSize = 13.sp, color = c.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        buildString {
                                            if (cls.room.isNotBlank()) append(cls.room)
                                            if (cls.teacher.isNotBlank()) {
                                                if (isNotEmpty()) append(" · ")
                                                append(cls.teacher)
                                            }
                                            if (cls.weeks.isNotBlank()) {
                                                if (isNotEmpty()) append(" · ")
                                                append(cls.weeks)
                                            }
                                        }.ifEmpty { "—" },
                                        fontSize = 11.5.sp,
                                        color = c.onSurfaceVariant,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                if (cls.date.isNotEmpty()) FlChip("临时", color = c.warning, filled = true)
                                Spacer(Modifier.width(6.dp))
                                FlIconButton(Icons.Default.Edit, onClick = { onEdit(cls) }, size = 24.dp)
                            }
                            FlDivider()
                        }
                    }
                }
            }
        }
    }
}

// ── 详情面板 ────────────────────────────────────────────────────

@Composable
private fun ClassDetailPanel(
    cls: ClassEntity,
    stats: WeekStats,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit
) {
    val c = FluentTheme.colors
    val dayLabel = mapOf(
        "Monday" to "周一", "Tuesday" to "周二", "Wednesday" to "周三",
        "Thursday" to "周四", "Friday" to "周五", "Saturday" to "周六", "Sunday" to "周日"
    )[cls.dayOfWeek] ?: cls.dayOfWeek

    Column(modifier = Modifier.width(268.dp).fillMaxHeight()) {
        FlCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(width = 4.dp, height = 30.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(if (cls.date.isNotEmpty()) c.warning else c.accent)
                    )
                    Spacer(Modifier.weight(1f))
                    FlIconButton(Icons.Default.Close, onClick = onClose, size = 24.dp, contentDescription = "取消选中")
                }
                Spacer(Modifier.height(10.dp))
                Text(cls.title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    if (cls.date.isNotEmpty()) "${cls.date} ${cls.startTime} – ${cls.endTime}"
                    else "$dayLabel ${cls.startTime} – ${cls.endTime}",
                    fontSize = 12.5.sp,
                    color = c.onSurfaceVariant
                )
                Spacer(Modifier.height(12.dp))
                DetailRow("教室", cls.room.ifBlank { "—" })
                DetailRow("教师", cls.teacher.ifBlank { "—" })
                DetailRow("周次", cls.weeks.ifBlank { "不限" })
                DetailRow("类型", if (cls.date.isNotEmpty()) "临时提醒" else "长期提醒")
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlButton("编辑", onClick = onEdit, compact = true)
                    FlButton("删除", onClick = onDelete, variant = FlButtonVariant.DANGER, icon = Icons.Default.Delete, compact = true)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        WeekSummaryCard(stats)
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    val c = FluentTheme.colors
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(label, fontSize = 12.5.sp, color = c.onSurfaceFaint, modifier = Modifier.width(48.dp))
        Text(value, fontSize = 12.5.sp, color = c.onSurface)
    }
}

@Composable
private fun WeekSummaryCard(stats: WeekStats) {
    val c = FluentTheme.colors
    FlCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            FlSectionLabel("本周统计")
            Spacer(Modifier.height(8.dp))
            DetailRow("课程", "${stats.courses} 节")
            DetailRow("课时", "${stats.hours} 小时")
            DetailRow("最忙", stats.busiest)
            Spacer(Modifier.height(14.dp))
            FlSectionLabel("操作提示")
            Spacer(Modifier.height(6.dp))
            Text("单击课程块查看详情，双击直接编辑。", fontSize = 12.sp, color = c.onSurfaceVariant)
        }
    }
}
