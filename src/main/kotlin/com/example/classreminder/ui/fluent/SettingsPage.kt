package com.example.classreminder.ui.fluent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.classreminder.AppPaths
import com.example.classreminder.Prefs
import com.example.classreminder.data.MainViewModel
import com.example.classreminder.data.backup.BackupCodec
import com.example.classreminder.data.backup.BackupDocument
import com.example.classreminder.data.backup.BackupFormat
import com.example.classreminder.data.backup.BackupModule
import com.example.classreminder.platform.AutoStart
import com.example.classreminder.platform.DesktopFileDialogs
import com.example.classreminder.platform.ReminderEngine
import com.example.classreminder.platform.ToastBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class SettingsSection(val label: String) {
    REMINDER("提醒"),
    APPEARANCE("外观"),
    STARTUP("通知与启动"),
    IMPORT("导入课表"),
    BACKUP("数据备份"),
    ABOUT("关于")
}

/**
 * 「设置」页（桌面版）。
 *
 * 手机版是一列往下滚的卡片；桌面版改成**左侧分类导航 + 右侧面板** ——
 * 设置项多了以后一列滚到底很难找，分类导航是桌面软件的惯例。
 * 所有开关的语义、默认值、写入位置与手机版完全一致。
 */
@Composable
fun FluentSettingsPage(
    viewModel: MainViewModel,
    themeModeOrdinal: Int,
    onThemeModeChanged: (Int) -> Unit,
    onTestNotification: () -> Unit,
    onOpenDataFolder: () -> Unit,
    onImportTimetable: () -> Unit
) {
    val c = FluentTheme.colors
    var section by remember { mutableStateOf(SettingsSection.REMINDER) }

    Column(Modifier.fillMaxSize()) {
        PageTopBar(
            title = "设置",
            subtitle = AppPaths.dataDir.absolutePath
        ) {
            FlButton("打开数据目录", onClick = onOpenDataFolder, variant = FlButtonVariant.GHOST, compact = true)
        }

        Row(
            modifier = Modifier.fillMaxSize().padding(FluentTheme.dimens.pagePadding),
            horizontalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Column(modifier = Modifier.width(176.dp).fillMaxHeight()) {
                SettingsSection.entries.forEach { item ->
                    SubNavItem(
                        label = item.label,
                        selected = item == section,
                        onClick = { section = item }
                    )
                }
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
            ) {
                when (section) {
                    SettingsSection.REMINDER -> ReminderSection()
                    SettingsSection.APPEARANCE -> AppearanceSection(themeModeOrdinal, onThemeModeChanged)
                    SettingsSection.STARTUP -> StartupSection(onTestNotification, onOpenDataFolder)
                    SettingsSection.IMPORT -> ImportSection(onImportTimetable)
                    SettingsSection.BACKUP -> BackupSection(viewModel)
                    SettingsSection.ABOUT -> AboutSection()
                }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun SubNavItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = FluentTheme.colors
    val d = FluentTheme.dimens
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .clip(RoundedCornerShape(d.radiusControl))
            .background(if (selected) c.accentTint else Color.Transparent)
            .clickable { onClick() }
    ) {
        if (selected) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .size(width = 3.dp, height = 16.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(c.accent)
            )
        }
        Box(
            modifier = Modifier.fillMaxSize().padding(start = 12.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            Text(
                label,
                fontSize = 13.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) c.onSurface else c.onSurfaceVariant
            )
        }
    }
}

// ── 提醒 ────────────────────────────────────────────────────────

@Composable
private fun ReminderSection() {
    val c = FluentTheme.colors
    var advance by remember { mutableStateOf(Prefs.getAdvanceMinutes().toString()) }
    var showPopup by remember { mutableStateOf(Prefs.getShowPopup()) }
    var notify by remember { mutableStateOf(Prefs.getNotifyEnabled()) }

    SectionBlock(
        title = "提醒",
        description = "到点会弹出置顶提醒卡片，并在系统托盘发一条气泡通知。"
    ) {
        FlCard(Modifier.fillMaxWidth()) {
            Column {
                FlSettingRow(
                    label = "提前提醒",
                    detail = "距离上课还有多少分钟时开始提醒（1 ~ 180）"
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        FlTextField(
                            value = advance,
                            onValueChange = { raw ->
                                advance = raw.filter { it.isDigit() }.take(3)
                                advance.toIntOrNull()?.let { Prefs.setAdvanceMinutes(it) }
                            },
                            modifier = Modifier.width(74.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text("分钟", fontSize = 12.5.sp, color = c.onSurfaceVariant)
                    }
                }
                FlDivider()
                FlSettingRow(
                    label = "置顶提醒弹窗",
                    detail = "到点时弹出全屏置顶卡片，需手动关闭"
                ) {
                    FlSwitch(showPopup) { showPopup = it; Prefs.setShowPopup(it) }
                }
                FlDivider()
                FlSettingRow(
                    label = "托盘气泡通知",
                    detail = "在系统通知中心留一条记录"
                ) {
                    FlSwitch(notify) { notify = it; Prefs.setNotifyEnabled(it) }
                }
            }
        }
    }
}

// ── 外观 ────────────────────────────────────────────────────────

@Composable
private fun AppearanceSection(themeModeOrdinal: Int, onThemeModeChanged: (Int) -> Unit) {
    SectionBlock(title = "外观", description = "主题即时生效，并写入本机设置文件。") {
        FlCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(6.dp)) {
                ThemeMode.entries.forEachIndexed { index, mode ->
                    val on = index == themeModeOrdinal
                    val c = FluentTheme.colors
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(34.dp)
                            .clip(RoundedCornerShape(FluentTheme.dimens.radiusControl))
                            .background(if (on) c.accentTint else Color.Transparent)
                            .clickable { onThemeModeChanged(index) }
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioDot(on)
                        Spacer(Modifier.width(10.dp))
                        Text(mode.label, fontSize = 13.sp, color = c.onSurface)
                    }
                }
            }
        }
    }
}

@Composable
private fun RadioDot(on: Boolean) {
    val c = FluentTheme.colors
    Box(
        modifier = Modifier.size(16.dp).clip(RoundedCornerShape(8.dp)).border(2.dp, if (on) c.accent else c.onSurfaceFaint, RoundedCornerShape(8.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (on) Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(c.accent))
    }
}

// ── 通知与启动 ──────────────────────────────────────────────────

@Composable
private fun StartupSection(onTestNotification: () -> Unit, onOpenDataFolder: () -> Unit) {
    val c = FluentTheme.colors
    var autoStart by remember { mutableStateOf(Prefs.getAutoStart()) }

    SectionBlock(
        title = "通知与启动",
        description = "提醒依赖应用进程常驻：关闭窗口会最小化到托盘继续运行，从托盘「退出」才会真正停止。"
    ) {
        FlCard(Modifier.fillMaxWidth()) {
            Column {
                FlSettingRow(
                    label = "开机自启",
                    detail = "写入 HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
                ) {
                    FlSwitch(autoStart) {
                        autoStart = it
                        Prefs.setAutoStart(it)
                        val ok = AutoStart.setEnabled(it)
                        if (!ok && it) ToastBus.show("写入开机自启失败：请检查注册表权限")
                        if (!ok && !it) ToastBus.show("已取消开机自启")
                    }
                }
                FlDivider()
                FlSettingRow(
                    label = "立即检查一次",
                    detail = "按当前时间重新算一遍今日课程，并弹一条测试通知"
                ) {
                    FlButton("发送测试通知", onClick = { ReminderEngine.checkNow(); onTestNotification() }, variant = FlButtonVariant.GHOST, compact = true)
                }
                FlDivider()
                FlSettingRow(
                    label = "数据目录",
                    detail = AppPaths.dataDir.absolutePath
                ) {
                    FlButton("在资源管理器中打开", onClick = onOpenDataFolder, variant = FlButtonVariant.GHOST, compact = true)
                }
            }
        }
    }
}

// ── 导入课表 ────────────────────────────────────────────────────

@Composable
private fun ImportSection(onImportTimetable: () -> Unit) {
    SectionBlock(
        title = "导入课表",
        description = "支持教务系统导出的课表 PDF；同一门课重复导入会覆盖，不会重复添加。" +
            "PDF 里没有具体时刻，导入后按默认作息推算（可在课表里逐条修改）。"
    ) {
        FlCard(Modifier.fillMaxWidth()) {
            FlSettingRow(label = "选择课表 PDF 导入") {
                FlButton("选择文件…", onClick = onImportTimetable, compact = true)
            }
        }
    }
}

// ── 数据备份 ────────────────────────────────────────────────────

@Composable
private fun BackupSection(viewModel: MainViewModel) {
    val c = FluentTheme.colors
    val scope = rememberCoroutineScope()
    val classes by viewModel.classes.collectAsState()
    val notes by viewModel.notes.collectAsState()

    var exportModules by remember { mutableStateOf<Set<BackupModule>>(emptySet()) }
    var importDoc by remember { mutableStateOf<BackupDocument?>(null) }
    var importSelected by remember { mutableStateOf<Set<BackupModule>>(emptySet()) }
    // null = 还没进入「逐个确认覆盖」阶段；空列表 = 全部确认完，可以执行导入了
    var confirmQueue by remember { mutableStateOf<List<BackupModule>?>(null) }
    var localCounts by remember { mutableStateOf<Map<BackupModule, Int?>>(emptyMap()) }

    fun resetImport() {
        importDoc = null
        importSelected = emptySet()
        confirmQueue = null
    }

    fun startExport(modules: Set<BackupModule>) {
        if (modules.isEmpty()) return
        scope.launch {
            val single = if (modules.size == BackupModule.entries.size) null else modules.first()
            val target = DesktopFileDialogs.saveFile("导出备份", backupFileName(single)) ?: return@launch
            val text = runCatching { viewModel.buildBackupText(modules) }.getOrNull()
            if (text == null) {
                ToastBus.show("导出失败：读不到本机数据")
                return@launch
            }
            val ok = withContext(Dispatchers.IO) { runCatching { target.writeText(text, Charsets.UTF_8) }.isSuccess }
            ToastBus.show(if (ok) "已导出 ${modules.joinToString("、") { it.title }}" else "导出失败：写不进所选文件")
        }
    }

    fun startImport() {
        scope.launch {
            val file = runCatching { DesktopFileDialogs.openFile("导入备份", listOf("json")) }.getOrNull() ?: return@launch
            val text = withContext(Dispatchers.IO) { runCatching { file.readText(Charsets.UTF_8) }.getOrNull() }
            if (text == null) {
                ToastBus.show("读取不到所选文件")
                return@launch
            }
            val parsed = runCatching { BackupDocument.parse(text) }
            val doc = parsed.getOrNull()
            if (doc == null) {
                ToastBus.show("导入失败：${parsed.exceptionOrNull()?.message ?: "文件格式不对"}")
                return@launch
            }
            importDoc = doc
            importSelected = doc.modules.keys
        }
    }

    // 进预览时查一次本机条数，对话框里显示「本机 X 条 → 文件 Y 条」
    LaunchedEffect(importDoc) {
        val doc = importDoc ?: return@LaunchedEffect
        localCounts = doc.modules.keys.associateWith { viewModel.localCount(it) }
    }

    // 确认队列被清空 = 每个勾选的模块都点头了 → 真正执行导入
    LaunchedEffect(confirmQueue, importDoc) {
        val doc = importDoc ?: return@LaunchedEffect
        val queue = confirmQueue ?: return@LaunchedEffect
        if (queue.isNotEmpty()) return@LaunchedEffect
        val modules = importSelected
        if (modules.isEmpty()) {
            resetImport()
            return@LaunchedEffect
        }
        viewModel.importBackup(doc, modules) { message ->
            ToastBus.show(message)
            resetImport()
        }
    }

    SectionBlock(
        title = "数据备份",
        description = "把数据导出成一个 JSON 文件，或者从文件恢复。每个模块都能单独导出 / 导入，" +
            "也可以一次全带走 —— 两种文件格式完全一样，与手机版互通。"
    ) {
        FlCard(Modifier.fillMaxWidth()) {
            Column {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FlButton("导出全部", onClick = { startExport(BackupModule.entries.toSet()) }, compact = true)
                    FlButton("从文件导入", onClick = { startImport() }, variant = FlButtonVariant.GHOST, compact = true)
                }
                FlDivider()
                BackupModuleRow("课程表", "${classes.size} 条") { startExport(setOf(BackupModule.COURSES)) }
                FlDivider()
                BackupModuleRow("便签", "${notes.size} 条") { startExport(setOf(BackupModule.NOTES)) }
                FlDivider()
                BackupModuleRow("设置", "提醒 / 主题 / 周次") { startExport(setOf(BackupModule.SETTINGS)) }
            }
        }
    }

    // ── 导入预览 ──
    val doc = importDoc
    if (doc != null && confirmQueue == null) {
        FlDialog(
            onDismiss = { resetImport() },
            title = "导入备份",
            width = 460.dp,
            content = {
                Column {
                    Text(
                        "文件导出时间：${formatBackupTime(doc.exportedAt)}。勾选要导入的模块：",
                        fontSize = 12.5.sp,
                        color = c.onSurfaceVariant
                    )
                    Spacer(Modifier.height(14.dp))
                    doc.modules.keys.forEach { module ->
                        val on = module in importSelected
                        val local = localCounts[module]
                        val incoming = BackupCodec.countOf(module, doc.modules.getValue(module))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(34.dp)
                                .clip(RoundedCornerShape(FluentTheme.dimens.radiusControl))
                                .clickable {
                                    importSelected = if (on) importSelected - module else importSelected + module
                                }
                                .padding(horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FlCheckbox(on)
                            Spacer(Modifier.width(10.dp))
                            Text(module.title, fontSize = 13.sp, color = c.onSurface)
                            Spacer(Modifier.weight(1f))
                            Text(
                                if (local == null) "偏好设置" else "本机 $local 条 → 文件 ${incoming ?: 0} 条",
                                fontSize = 12.sp,
                                color = c.onSurfaceFaint
                            )
                        }
                    }
                }
            },
            actions = {
                FlButton("取消", onClick = { resetImport() }, variant = FlButtonVariant.GHOST, compact = true)
                Spacer(Modifier.width(8.dp))
                FlButton(
                    "下一步",
                    onClick = { confirmQueue = importSelected.toList() },
                    enabled = importSelected.isNotEmpty(),
                    compact = true
                )
            }
        )
    }

    // ── 逐模块覆盖确认 ──
    val pending = confirmQueue?.firstOrNull()
    if (doc != null && pending != null) {
        val local = localCounts[pending]
        val incoming = BackupCodec.countOf(pending, doc.modules[pending] ?: doc.modules.values.first())
        ConfirmDialog(
            title = "覆盖「${pending.title}」？",
            message = if (local == null) {
                "本机的偏好设置将被文件里的设置整体替换，此操作不可撤销。"
            } else {
                "本机现有的 $local 条将被文件里的 ${incoming ?: 0} 条整体替换，此操作不可撤销。"
            },
            confirmText = "确认覆盖",
            danger = true,
            onConfirm = { confirmQueue = confirmQueue?.drop(1) ?: emptyList() },
            onDismiss = { resetImport() }
        )
    }
}

@Composable
private fun BackupModuleRow(title: String, detail: String, onExport: () -> Unit) {
    FlSettingRow(label = title, detail = detail) {
        FlButton("导出", onClick = onExport, variant = FlButtonVariant.GHOST, compact = true)
    }
}

@Composable
private fun FlCheckbox(checked: Boolean) {
    val c = FluentTheme.colors
    Box(
        modifier = Modifier
            .size(16.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(if (checked) c.accent else c.surface)
            .border(1.dp, if (checked) c.accent else c.outlineStrong, RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center
    ) {
        if (checked) {
            androidx.compose.material3.Icon(
                Icons.Default.Check,
                contentDescription = null,
                tint = c.onAccent,
                modifier = Modifier.size(11.dp)
            )
        }
    }
}

// ── 关于 ────────────────────────────────────────────────────────

@Composable
private fun AboutSection() {
    val c = FluentTheme.colors
    SectionBlock(title = "关于", description = "StuMate 桌面版 · 离线运行的课表提醒客户端。") {
        FlCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                InfoRow("版本", "1.0.0")
                InfoRow("数据目录", AppPaths.dataDir.absolutePath)
                InfoRow("数据库", AppPaths.dbFile.name)
                InfoRow("配置文件", AppPaths.settingsFile.name)
                InfoRow("备份格式", "${BackupFormat.FORMAT} (schema ${BackupFormat.SCHEMA})")
                Spacer(Modifier.height(12.dp))
                Text(
                    "数据全部保存在本机，不联网。备份文件与手机版互通，可以互相导入。",
                    fontSize = 12.sp,
                    color = c.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    val c = FluentTheme.colors
    Row(Modifier.padding(vertical = 4.dp)) {
        Text(label, fontSize = 12.5.sp, color = c.onSurfaceFaint, modifier = Modifier.width(76.dp))
        Text(value, fontSize = 12.5.sp, color = c.onSurface)
    }
}

// ── 通用区块 ────────────────────────────────────────────────────

@Composable
private fun SectionBlock(
    title: String,
    description: String? = null,
    content: @Composable () -> Unit
) {
    val c = FluentTheme.colors
    Column(Modifier.fillMaxWidth().padding(bottom = 22.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
        if (description != null) {
            Spacer(Modifier.height(4.dp))
            Text(description, fontSize = 12.5.sp, color = c.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

private fun backupFileName(module: BackupModule?): String {
    val date = SimpleDateFormat("yyyyMMdd", Locale.getDefault()).format(Date())
    return "${BackupFormat.FILE_PREFIX}-${module?.key ?: "all"}-$date.json"
}

private fun formatBackupTime(millis: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(millis))
