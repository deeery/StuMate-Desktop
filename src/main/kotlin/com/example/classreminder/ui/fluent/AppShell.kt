package com.example.classreminder.ui.fluent

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.classreminder.Prefs
import com.example.classreminder.data.ClassEntity
import com.example.classreminder.data.MainViewModel
import com.example.classreminder.platform.ReminderEngine

/** 四个主页面。顺序与 `Prefs.getLastTab()` 的 0/1/2 对齐（设置页不记录） */
enum class AppPage(val label: String, val icon: ImageVector) {
    TODAY("今天", Icons.Default.Home),
    WEEK("课表", Icons.Default.DateRange),
    NOTES("便签", Icons.Default.Edit),
    SETTINGS("设置", Icons.Default.Settings)
}

/** 窄窗口阈值：低于它就只留图标侧栏 */
private val SIDEBAR_COLLAPSE_BELOW = 900.dp
private val SIDEBAR_RAIL_WIDTH = 56.dp

@Composable
fun AppShell(
    viewModel: MainViewModel,
    themeModeOrdinal: Int,
    onThemeModeChanged: (Int) -> Unit,
    onTestNotification: () -> Unit,
    onOpenDataFolder: () -> Unit,
    onImportTimetable: () -> Unit
) {
    val c = FluentTheme.colors
    var page by remember { mutableStateOf(AppPage.entries[Prefs.getLastTab().coerceIn(0, 2)]) }
    val notes by viewModel.notes.collectAsState()

    // 课程编辑对话框的状态提到这里：「今天」页和「课表」页都要用，
    // 而且从任意一页点「新建提醒」都要能弹出来
    var editingClass by remember { mutableStateOf<ClassEntity?>(null) }
    var newClassRequest by remember { mutableStateOf(0) }
    var calibrateRequest by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().background(c.bg)) {
        val collapsed = maxWidth < SIDEBAR_COLLAPSE_BELOW
        Row(Modifier.fillMaxSize()) {
            AppSidebar(
                page = page,
                noteCount = notes.size,
                collapsed = collapsed,
                onSelect = {
                    page = it
                    // 设置页不记录落点（与手机版一致）
                    if (it != AppPage.SETTINGS) Prefs.setLastTab(it.ordinal)
                }
            )
            Box(Modifier.weight(1f).fillMaxHeight()) {
                when (page) {
                    AppPage.TODAY -> TodayPage(
                        viewModel = viewModel,
                        onOpenNotes = { page = AppPage.NOTES },
                        onNewClass = { newClassRequest += 1 },
                        onOpenClass = { editingClass = it }
                    )
                    AppPage.WEEK -> WeekPage(
                        viewModel = viewModel,
                        onRequestCalibrate = { calibrateRequest = true },
                        onRequestNewClass = { newClassRequest += 1 },
                        onOpenClass = { editingClass = it }
                    )
                    AppPage.NOTES -> NotesPage(viewModel)
                    AppPage.SETTINGS -> FluentSettingsPage(
                        viewModel = viewModel,
                        themeModeOrdinal = themeModeOrdinal,
                        onThemeModeChanged = onThemeModeChanged,
                        onTestNotification = onTestNotification,
                        onOpenDataFolder = onOpenDataFolder,
                        onImportTimetable = onImportTimetable
                    )
                }
            }
        }
    }

    if (newClassRequest > 0) {
        CourseEditDialog(
            initial = null,
            oneOff = false,
            onSave = { viewModel.save(it); newClassRequest = 0 },
            onDismiss = { newClassRequest = 0 }
        )
    }
    editingClass?.let { target ->
        CourseEditDialog(
            initial = target,
            oneOff = target.date.isNotEmpty(),
            onSave = { viewModel.save(it); editingClass = null },
            onDelete = { viewModel.delete(target); editingClass = null },
            onDismiss = { editingClass = null }
        )
    }
    if (calibrateRequest) {
        CalibrateWeekDialog(
            initial = Prefs.getWeek1Monday(),
            onConfirm = { Prefs.setWeek1Monday(it); calibrateRequest = false },
            onDismiss = { calibrateRequest = false }
        )
    }
}

// ── 侧边栏 ──────────────────────────────────────────────────────

@Composable
private fun AppSidebar(
    page: AppPage,
    noteCount: Int,
    collapsed: Boolean,
    onSelect: (AppPage) -> Unit
) {
    val c = FluentTheme.colors
    val d = FluentTheme.dimens
    val running by ReminderEngine.running.collectAsState()
    val status by ReminderEngine.status.collectAsState()
    val week = remember { Prefs.currentWeek() }

    Column(
        modifier = Modifier
            .width(if (collapsed) SIDEBAR_RAIL_WIDTH else d.sidebarWidth)
            .fillMaxHeight()
            .background(c.surface2)
            .padding(vertical = 10.dp)
    ) {
        // 品牌行
        Row(
            modifier = Modifier.fillMaxWidth().padding(
                start = if (collapsed) 0.dp else 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 16.dp
            ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start
        ) {
            Box(
                modifier = Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)).background(c.accent),
                contentAlignment = Alignment.Center
            ) {
                Text("S", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = c.onAccent)
            }
            if (!collapsed) {
                Spacer(Modifier.width(10.dp))
                Text("StuMate", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
                Spacer(Modifier.weight(1f))
                Text("1.0", fontSize = 11.sp, color = c.onSurfaceFaint)
            }
        }

        AppPage.entries.forEach { item ->
            SidebarItem(
                page = item,
                selected = item == page,
                collapsed = collapsed,
                badge = if (item == AppPage.NOTES && noteCount > 0) noteCount.toString() else null,
                onClick = { onSelect(item) }
            )
        }

        Spacer(Modifier.weight(1f))

        // 底部：提醒服务状态（对应手机版的常驻通知）
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = if (collapsed) 0.dp else 16.dp, vertical = 10.dp),
            horizontalAlignment = if (collapsed) Alignment.CenterHorizontally else Alignment.Start
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(7.dp).clip(RoundedCornerShape(4.dp))
                        .background(if (running) c.success else c.error)
                )
                if (!collapsed) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (running) "提醒服务运行中" else "提醒服务未启动",
                        fontSize = 11.5.sp,
                        color = c.onSurfaceVariant,
                        maxLines = 1
                    )
                }
            }
            if (!collapsed) {
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(status.title)
                    },
                    fontSize = 11.sp,
                    color = c.onSurfaceFaint,
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    buildString {
                        if (week != null) append("第 $week 周 · ")
                        append("提前 ${Prefs.getAdvanceMinutes()} 分钟提醒")
                    },
                    fontSize = 11.sp,
                    color = c.onSurfaceFaint,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SidebarItem(
    page: AppPage,
    selected: Boolean,
    collapsed: Boolean,
    badge: String?,
    onClick: () -> Unit
) {
    val c = FluentTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .background(if (selected) c.accentTint else if (hovered) c.hover else Color.Transparent)
            .hoverable(interaction)
            .clickable(interaction, indication = null) { onClick() }
    ) {
        // 选中指示条（Fluent NavigationView 的标志性细节）
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 0.dp)
                    .size(width = 3.dp, height = 18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(c.accent)
            )
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = if (collapsed) 0.dp else 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = if (collapsed) Arrangement.Center else Arrangement.Start
        ) {
            Icon(
                page.icon,
                contentDescription = page.label,
                tint = if (selected) c.onSurface else c.onSurfaceVariant,
                modifier = Modifier.size(17.dp)
            )
            if (!collapsed) {
                Spacer(Modifier.width(12.dp))
                Text(
                    page.label,
                    fontSize = 13.5.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) c.onSurface else c.onSurfaceVariant
                )
                if (badge != null) {
                    Spacer(Modifier.weight(1f))
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(10.dp))
                            .background(c.surface3)
                            .padding(horizontal = 7.dp, vertical = 1.dp)
                    ) {
                        Text(badge, fontSize = 11.sp, color = c.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

// ── 页头 ────────────────────────────────────────────────────────

/**
 * 页头。**它同时充当自绘标题栏**（窗口是无边框的，见 [WindowChrome]）。
 *
 * 结构从左到右：
 *  `[标题 + 副标题]` ── 弹性空白（可拖动窗口、双击切换最大化）── `[页面自己的操作]` `[最小化/最大化/关闭]`
 *
 * 标题区刻意用 `weight(1f)` 吃掉所有空白：一来空白处天然成了拖动把手，
 * 二来页面操作与窗口控制按钮被稳定推到右端，**在四个页面之间保持同一水平位置**。
 */
@Composable
fun PageTopBar(
    title: String,
    subtitle: String? = null,
    actions: @Composable () -> Unit = {}
) {
    val c = FluentTheme.colors
    val d = FluentTheme.dimens
    val chrome = LocalWindowChrome.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(d.topBarHeight)
            .background(c.bg),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 标题 + 拖动区：整块空白都能按住拖窗口
        Row(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .windowDragArea(chrome),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Spacer(Modifier.width(20.dp))
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
            if (subtitle != null) {
                Spacer(Modifier.width(12.dp))
                Text(subtitle, fontSize = 12.5.sp, color = c.onSurfaceVariant, maxLines = 1)
            }
        }

        // 页面自己的操作
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            actions()
        }

        Spacer(Modifier.width(12.dp))

        // 窗口控制：贴右边缘，与系统标题栏的位置一致
        WindowControls(chrome)
    }
    FlDivider()
}
