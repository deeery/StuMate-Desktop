package com.example.classreminder.ui.fluent

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.classreminder.data.MainViewModel
import com.example.classreminder.data.NOTE_COLOR_COUNT
import com.example.classreminder.data.NOTE_TYPES
import com.example.classreminder.data.NoteEntity
import com.example.classreminder.data.NoteTypeKind
import com.example.classreminder.data.deadlineCountdown
import com.example.classreminder.data.deadlineTimeLabel
import com.example.classreminder.data.hasDeadline
import com.example.classreminder.data.noteTypeAt
import com.example.classreminder.data.typeLabel
import com.example.classreminder.ui.AppDatePickerDialog
import com.example.classreminder.ui.AppTimePickerDialog
import java.util.Calendar
import java.util.Locale
import kotlin.math.roundToInt

private val NOTE_ROW_HEIGHT = 38.dp
private val GRIP_WIDTH = 26.dp
private val COLOR_COL_WIDTH = 20.dp
private val TYPE_COL_WIDTH = 96.dp
private val DEADLINE_COL_WIDTH = 168.dp
private val ACTION_COL_WIDTH = 64.dp

/**
 * 「便签」页（桌面版）。
 *
 * 手机版是「单列列表 + 双击弹对话框编辑」；桌面版改成**表格 + 右侧常驻编辑面板**：
 *  - 表格能一眼扫完所有便签的分类与截止时间，还能按列对齐，比卡片列表信息密度高得多
 *  - 右侧面板常驻，点哪条改哪条，不用反复开对话框
 *  - 排序改成**左侧把手直接拖**（鼠标下比「长按拖动」直观），删除改成**行内悬停按钮 + 右键菜单**
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun NotesPage(
    viewModel: MainViewModel,
    /**
     * 预览专用：一进来就打开右侧编辑面板（空的新建表单）。
     * 验收环境鼠标注入不可用，点不到「新建便签」按钮 —— 见 [AppShell.previewNotesEditor]。
     */
    initialCreating: Boolean = false,
    /**
     * 预览专用：一进来就选中第一条便签，于是编辑面板里是**已填好**的标题与正文。
     * 和 [initialCreating] 二选一，用来分别验「空表单」和「回填」两种状态。
     */
    initialSelectedFirst: Boolean = false
) {
    val c = FluentTheme.colors
    val notes by viewModel.notes.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()

    var search by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf(-1) }
    var sortByDeadline by remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<Int?>(null) }
    var creating by remember { mutableStateOf(initialCreating) }
    var filterMenu by remember { mutableStateOf(false) }
    var menuNoteId by remember { mutableStateOf<Int?>(null) }

    // 便签是异步从库里读出来的，第一帧还是空的 —— 所以要等 notes 到货再挑第一条
    LaunchedEffect(notes, initialSelectedFirst) {
        if (initialSelectedFirst && selectedId == null) selectedId = notes.firstOrNull()?.id
    }

    val filtered = remember(notes, search, typeFilter, sortByDeadline) {
        var list = notes
        // 标题和正文**都参与匹配**：用户记不清一句话写在标题还是正文里，
        // 只搜其中一个就会出现「明明记得写过却搜不到」
        if (search.isNotBlank()) {
            list = list.filter {
                it.title.contains(search, ignoreCase = true) ||
                    it.content.contains(search, ignoreCase = true)
            }
        }
        if (typeFilter >= 0) list = list.filter { it.typeIndex == typeFilter }
        if (sortByDeadline) {
            list = list.sortedWith(
                compareBy({ if (it.hasDeadline) 0 else 1 }, { if (it.hasDeadline) it.deadlineAt else Long.MAX_VALUE })
            )
        }
        list
    }
    val selected = notes.firstOrNull { it.id == selectedId }
    val deadlineCount = notes.count { it.hasDeadline }

    // 选中的便签被删掉 / 被过滤掉时，编辑面板跟着收起
    LaunchedEffect(filtered, selectedId) {
        if (selectedId != null && filtered.none { it.id == selectedId }) selectedId = null
    }

    Column(Modifier.fillMaxSize()) {
        PageTopBar(
            title = "便签",
            subtitle = "共 ${notes.size} 条 · $deadlineCount 条带截止时间"
        ) {
            FlButton(
                "撤销",
                onClick = { viewModel.undoNote() },
                variant = FlButtonVariant.GHOST,
                icon = Icons.Default.Refresh,
                enabled = canUndo,
                compact = true
            )
            FlButton(
                "新建便签",
                onClick = { creating = true; selectedId = null },
                icon = Icons.Default.Add,
                compact = true
            )
        }

        Row(
            modifier = Modifier.fillMaxSize().padding(FluentTheme.dimens.pagePadding),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                // ── 工具行 ──
                Row(
                    modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    FlTextField(
                        value = search,
                        onValueChange = { search = it },
                        placeholder = "搜索便签…",
                        leadingIcon = Icons.Default.Search,
                        modifier = Modifier.width(260.dp)
                    )
                    Box {
                        Row(
                            modifier = Modifier
                                .height(32.dp)
                                .clip(RoundedCornerShape(FluentTheme.dimens.radiusControl))
                                .background(c.surface)
                                .border(1.dp, c.outlineStrong, RoundedCornerShape(FluentTheme.dimens.radiusControl))
                                .clickable { filterMenu = true }
                                .padding(horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (typeFilter < 0) "分类：全部" else "分类：${noteTypeAt(typeFilter).label}",
                                fontSize = 13.sp,
                                color = c.onSurface
                            )
                            Spacer(Modifier.width(8.dp))
                            Icon(Icons.Default.KeyboardArrowDown, contentDescription = null, tint = c.onSurfaceFaint, modifier = Modifier.size(14.dp))
                        }
                        DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("全部", fontSize = 13.sp) },
                                onClick = { typeFilter = -1; filterMenu = false }
                            )
                            NOTE_TYPES.forEachIndexed { index, type ->
                                DropdownMenuItem(
                                    text = { Text(type.label, fontSize = 13.sp) },
                                    onClick = { typeFilter = index; filterMenu = false }
                                )
                            }
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    FlButton(
                        if (sortByDeadline) "按截止时间排序 ✓" else "按截止时间排序",
                        onClick = { sortByDeadline = !sortByDeadline },
                        variant = FlButtonVariant.TEXT,
                        compact = true
                    )
                }

                // ── 表格 ──
                FlCard(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        NoteTableHeader()
                        FlDivider()
                        if (filtered.isEmpty()) {
                            FlEmptyState(
                                title = if (notes.isEmpty()) "还没有便签" else "没有匹配的便签",
                                detail = if (notes.isEmpty()) "点右上角「新建便签」随手记一条"
                                else "换个关键词，或把分类筛选改回「全部」",
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                itemsIndexed(filtered, key = { _, n -> n.id }) { index, note ->
                                    NoteTableRow(
                                        note = note,
                                        index = index,
                                        total = filtered.size,
                                        selected = note.id == selectedId,
                                        menuOpen = menuNoteId == note.id,
                                        onSelect = { selectedId = note.id; creating = false },
                                        onEdit = { selectedId = note.id; creating = false },
                                        onDelete = { viewModel.deleteNote(note.id); if (selectedId == note.id) selectedId = null },
                                        onOpenMenu = { menuNoteId = note.id },
                                        onCloseMenu = { menuNoteId = null },
                                        onMove = { from, to -> viewModel.moveNote(from, to) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── 右侧编辑面板 ──
            if (creating || selected != null) {
                NoteEditorPanel(
                    key = if (creating) "new" else "edit-${selected!!.id}",
                    initial = if (creating) null else selected,
                    onSave = { title, content, colorIndex, typeIndex, customLabel, deadlineAt ->
                        if (creating) {
                            viewModel.addNote(title, content, null, colorIndex, typeIndex, customLabel, deadlineAt)
                        } else {
                            viewModel.updateNote(selected!!.id, title, content, colorIndex, typeIndex, customLabel, deadlineAt)
                        }
                        creating = false
                    },
                    onDelete = if (creating) null else ({ viewModel.deleteNote(selected!!.id); selectedId = null }),
                    onCancel = { creating = false; selectedId = null }
                )
            }
        }
    }
}

// ── 表头 ────────────────────────────────────────────────────────

@Composable
private fun NoteTableHeader() {
    val c = FluentTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().background(c.surface2).padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Spacer(Modifier.width(GRIP_WIDTH))
        Spacer(Modifier.width(COLOR_COL_WIDTH))
        Text("标题 / 内容", modifier = Modifier.weight(1f), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = c.onSurfaceFaint)
        Text("分类", modifier = Modifier.width(TYPE_COL_WIDTH), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = c.onSurfaceFaint)
        Text("截止", modifier = Modifier.width(DEADLINE_COL_WIDTH), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = c.onSurfaceFaint)
        Spacer(Modifier.width(ACTION_COL_WIDTH))
    }
}

// ── 表格行 ──────────────────────────────────────────────────────

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun NoteTableRow(
    note: NoteEntity,
    index: Int,
    total: Int,
    selected: Boolean,
    menuOpen: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenMenu: () -> Unit,
    onCloseMenu: () -> Unit,
    onMove: (Int, Int) -> Unit
) {
    val c = FluentTheme.colors
    val density = LocalDensity.current
    val rowPx = with(density) { NOTE_ROW_HEIGHT.toPx() }
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()

    var dragOffset by remember(note.id) { mutableStateOf(0f) }
    var dragging by remember(note.id) { mutableStateOf(false) }
    var measuredHeight by remember(note.id) { mutableStateOf(0) }

    val bg = when {
        dragging -> c.accentTint
        selected -> c.accentTint
        hovered -> c.hover
        else -> Color.Transparent
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .offset(y = with(density) { dragOffset.toDp() })
            .background(bg)
            .onSizeChanged { measuredHeight = it.height }
            .onPointerEvent(PointerEventType.Press) { event ->
                if (event.buttons.isSecondaryPressed) {
                    onSelect()
                    onOpenMenu()
                }
            }
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = NOTE_ROW_HEIGHT)
                .hoverable(interaction)
                .clickable(interaction, indication = null) { onSelect() }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 拖动把手：鼠标按住这里上下拖即可排序
            Box(
                modifier = Modifier
                    .width(GRIP_WIDTH)
                    .height(NOTE_ROW_HEIGHT)
                    .pointerInput(note.id, total) {
                        detectDragGestures(
                            onDragStart = { dragging = true; dragOffset = 0f },
                            onDrag = { change, delta ->
                                change.consume()
                                dragOffset += delta.y
                            },
                            onDragEnd = {
                                val step = if (measuredHeight > 0) measuredHeight.toFloat() else rowPx
                                val target = (index + (dragOffset / step).roundToInt()).coerceIn(0, total - 1)
                                if (target != index) onMove(index, target)
                                dragging = false
                                dragOffset = 0f
                            },
                            onDragCancel = { dragging = false; dragOffset = 0f }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                Text("⋮⋮", fontSize = 12.sp, color = c.onSurfaceFaint)
            }

            Box(Modifier.width(COLOR_COL_WIDTH), contentAlignment = Alignment.CenterStart) {
                Box(
                    Modifier
                        .size(width = 3.dp, height = 18.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(fluentNoteColor(note.colorIndex))
                )
            }

            // 标题 + 正文摘要两行。正文为空时**第二行整个不占位** ——
            // 否则只有标题的便签下面会挂一段空白，表格行高看起来忽高忽低。
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(
                    note.title,
                    fontSize = 13.sp,
                    color = c.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (note.content.isNotBlank()) {
                    Text(
                        note.content,
                        fontSize = 11.5.sp,
                        color = c.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Box(Modifier.width(TYPE_COL_WIDTH)) {
                val label = note.typeLabel()
                if (label.isNotEmpty()) {
                    FlChip(label, color = if (note.hasDeadline) c.error else null, filled = note.hasDeadline)
                }
            }

            Box(Modifier.width(DEADLINE_COL_WIDTH)) {
                if (note.hasDeadline) {
                    val now = System.currentTimeMillis()
                    Text(
                        "${deadlineTimeLabel(note.deadlineAt, now)} · ${deadlineCountdown(note.deadlineAt, now)}",
                        fontSize = 12.sp,
                        color = if (note.deadlineAt < now) c.error else c.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text("—", fontSize = 12.sp, color = c.onSurfaceFaint)
                }
            }

            Row(
                modifier = Modifier.width(ACTION_COL_WIDTH),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hovered || menuOpen) {
                    FlIconButton(Icons.Default.Edit, onClick = onEdit, size = 24.dp, contentDescription = "编辑便签")
                    FlIconButton(
                        Icons.Default.Delete,
                        onClick = onDelete,
                        size = 24.dp,
                        tint = c.error,
                        contentDescription = "删除便签"
                    )
                } else {
                    Box(Modifier.width(24.dp))
                }
                Box {
                    FlIconButton(Icons.Default.MoreVert, onClick = onOpenMenu, size = 24.dp, contentDescription = "更多操作")
                    DropdownMenu(expanded = menuOpen, onDismissRequest = onCloseMenu) {
                        DropdownMenuItem(
                            text = { Text("编辑便签", fontSize = 13.sp) },
                            onClick = { onCloseMenu(); onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text("在此上方插入", fontSize = 13.sp) },
                            onClick = { onCloseMenu(); onSelect() }
                        )
                        DropdownMenuItem(
                            text = { Text("删除便签", fontSize = 13.sp, color = c.error) },
                            onClick = { onCloseMenu(); onDelete() }
                        )
                    }
                }
            }
        }
        FlDivider(modifier = Modifier.align(Alignment.BottomStart))
    }
}

// ── 右侧编辑面板 ────────────────────────────────────────────────

@Composable
private fun NoteEditorPanel(
    key: String,
    initial: NoteEntity?,
    onSave: (String, String, Int, Int, String, Long) -> Unit,
    onDelete: (() -> Unit)?,
    onCancel: () -> Unit
) {
    val c = FluentTheme.colors
    val d = FluentTheme.dimens

    var title by remember(key) { mutableStateOf(initial?.title ?: "") }
    var content by remember(key) { mutableStateOf(initial?.content ?: "") }
    var colorIndex by remember(key) { mutableStateOf(initial?.colorIndex ?: 0) }
    var typeIndex by remember(key) { mutableStateOf(initial?.typeIndex ?: 0) }
    var customLabel by remember(key) { mutableStateOf(initial?.customLabel ?: "") }
    var deadlineAt by remember(key) { mutableStateOf(initial?.deadlineAt ?: 0L) }
    var showDate by remember { mutableStateOf(false) }
    var showTime by remember { mutableStateOf(false) }

    val type = noteTypeAt(typeIndex)
    val isDeadline = type.kind == NoteTypeKind.DEADLINE
    // 编辑已有便签时永远可保存：标题留空会**保留原标题**（见 MainViewModel.updateNote），
    // 所以「清空标题」不是非法输入，用户可能只是想改个颜色
    val canSave = title.isNotBlank() || initial != null

    Column(
        modifier = Modifier
            .width(340.dp)
            .fillMaxHeight()
            .padding(start = 16.dp)
            .border(width = 0.dp, color = Color.Transparent)
    ) {
        Text(
            if (initial == null) "新建便签" else "编辑便签",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.onSurface
        )
        Spacer(Modifier.height(14.dp))

        // ⚠️ 表单部分必须可滚。
        // 加了「标题」之后字段从 4 组变 5 组，而窗口最小高度是 600dp ——
        // 选上「Deadline」+「自定义」（两组各多出一个输入框）时
        // 内容会超出面板高度。原先靠 `Spacer(weight(1f))` 兜底，
        // 溢出时 spacer 先归零、再往下就把按钮挤出屏幕（保存按钮点不到）。
        // 改成「表单滚动 + 按钮固定在底部」，任何窗口高度下按钮都够得着。
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            FlSectionLabel("标题")
            Spacer(Modifier.height(6.dp))
            FlTextField(
                value = title,
                onValueChange = { title = it },
                placeholder = "一句话说清这条便签",
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(14.dp))
            FlSectionLabel("内容")
            Spacer(Modifier.height(6.dp))
            FlTextField(
                value = content,
                onValueChange = { content = it },
                placeholder = "补充细节，可留空",
                singleLine = false,
                minHeight = 72.dp,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(14.dp))
            FlSectionLabel("颜色")
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(NOTE_COLOR_COUNT) { index ->
                    val color = fluentNoteColor(index)
                    val on = index == colorIndex
                    Box(
                        modifier = Modifier
                            .size(26.dp)
                            .clip(RoundedCornerShape(d.radiusControl))
                            .background(color)
                            .border(
                                width = if (on) 2.dp else 0.dp,
                                color = if (on) c.onSurface else Color.Transparent,
                                shape = RoundedCornerShape(d.radiusControl)
                            )
                            .clickable { colorIndex = index }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            FlSectionLabel("分类")
            Spacer(Modifier.height(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                NOTE_TYPES.chunked(4).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        row.forEachIndexed { i, t ->
                            val index = NOTE_TYPES.indexOf(t)
                            FlToggleChip(
                                text = t.label,
                                selected = index == typeIndex,
                                accent = fluentNoteColor(colorIndex),
                                onClick = {
                                    typeIndex = index
                                    if (!t.editableLabel) customLabel = ""
                                }
                            )
                            if (i == row.lastIndex) Spacer(Modifier.width(0.dp))
                        }
                    }
                }
            }

            if (type.editableLabel) {
                Spacer(Modifier.height(8.dp))
                FlTextField(
                    value = customLabel,
                    onValueChange = { customLabel = it },
                    placeholder = if (isDeadline) "例如「期末论文」" else "例如「科研」",
                    modifier = Modifier.fillMaxWidth()
                )
            }

            if (isDeadline) {
                Spacer(Modifier.height(14.dp))
                FlSectionLabel("截止时间")
                Spacer(Modifier.height(6.dp))
                val cal = remember(deadlineAt) {
                    Calendar.getInstance().apply { timeInMillis = if (deadlineAt > 0) deadlineAt else System.currentTimeMillis() }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FlButton(
                        if (deadlineAt > 0) String.format(
                            Locale.getDefault(), "%04d-%02d-%02d",
                            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
                        ) else "选日期",
                        onClick = { showDate = true },
                        variant = FlButtonVariant.GHOST,
                        icon = Icons.Default.DateRange,
                        compact = true
                    )
                    FlButton(
                        if (deadlineAt > 0) String.format(
                            Locale.getDefault(), "%02d:%02d",
                            cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE)
                        ) else "选时间",
                        onClick = { showTime = true },
                        variant = FlButtonVariant.GHOST,
                        compact = true
                    )
                    if (deadlineAt > 0) {
                        FlButton("清除", onClick = { deadlineAt = 0L }, variant = FlButtonVariant.TEXT, compact = true)
                    }
                }
                if (deadlineAt > 0) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "${deadlineTimeLabel(deadlineAt, System.currentTimeMillis())} · ${deadlineCountdown(deadlineAt, System.currentTimeMillis())}",
                        fontSize = 12.sp,
                        color = c.error
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
        }

        FlDivider()
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (onDelete != null) {
                FlButton("删除", onClick = onDelete, variant = FlButtonVariant.DANGER, compact = true)
            }
            Spacer(Modifier.weight(1f))
            FlButton("取消", onClick = onCancel, variant = FlButtonVariant.GHOST, compact = true)
            Spacer(Modifier.width(8.dp))
            FlButton(
                "保存",
                onClick = {
                    val finalDeadline = if (isDeadline) deadlineAt else 0L
                    val finalLabel = if (type.editableLabel) customLabel else ""
                    onSave(title, content, colorIndex, typeIndex, finalLabel, finalDeadline)
                },
                enabled = canSave,
                compact = true
            )
        }
        Spacer(Modifier.height(4.dp))
    }

    if (showDate) {
        val cal = Calendar.getInstance().apply { timeInMillis = if (deadlineAt > 0) deadlineAt else System.currentTimeMillis() }
        AppDatePickerDialog(
            initialYear = cal.get(Calendar.YEAR),
            initialMonth = cal.get(Calendar.MONTH),
            initialDay = cal.get(Calendar.DAY_OF_MONTH),
            onDismiss = { showDate = false },
            onConfirm = { y, m, day ->
                val next = Calendar.getInstance().apply {
                    timeInMillis = if (deadlineAt > 0) deadlineAt else System.currentTimeMillis()
                    set(Calendar.YEAR, y); set(Calendar.MONTH, m); set(Calendar.DAY_OF_MONTH, day)
                }
                deadlineAt = next.timeInMillis
            }
        )
    }
    if (showTime) {
        val cal = Calendar.getInstance().apply { timeInMillis = if (deadlineAt > 0) deadlineAt else System.currentTimeMillis() }
        AppTimePickerDialog(
            initialHour = cal.get(Calendar.HOUR_OF_DAY),
            initialMinute = cal.get(Calendar.MINUTE),
            onDismiss = { showTime = false },
            onConfirm = { h, min ->
                val next = Calendar.getInstance().apply {
                    timeInMillis = if (deadlineAt > 0) deadlineAt else System.currentTimeMillis()
                    set(Calendar.HOUR_OF_DAY, h); set(Calendar.MINUTE, min)
                    set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                }
                deadlineAt = next.timeInMillis
            }
        )
    }
}
