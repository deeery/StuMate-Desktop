package com.example.classreminder.ui.fluent

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.classreminder.Prefs
import com.example.classreminder.data.ClassEntity
import com.example.classreminder.data.MainViewModel
import com.example.classreminder.data.NoteEntity
import com.example.classreminder.data.TodayNotePicker
import com.example.classreminder.data.TodaySchedule
import com.example.classreminder.data.deadlineCountdown
import com.example.classreminder.data.hasDeadline
import com.example.classreminder.data.typeLabel
import com.example.classreminder.ui.emptyTodaySubtitle
import com.example.classreminder.ui.emptyTodayTitle
import com.example.classreminder.ui.greetingFor
import com.example.classreminder.ui.remainingText
import com.example.classreminder.ui.todaySubtitle
import kotlinx.coroutines.delay
import java.util.Calendar
import java.util.Locale

/**
 * 「今天」页（桌面版）。
 *
 * 与手机版的差别：手机是一列往下滚，桌面是**两列** ——
 * 左边放「当前/下一节 + 今日时间轴」，右边固定 300dp 放便签面板。
 * 窗口宽了以后一列铺开会浪费一半横向空间，而且「今天还有什么课」和「有什么便签要交」
 * 本来就是一屏之内要同时看到的两件事。
 */
@Composable
fun TodayPage(
    viewModel: MainViewModel,
    onOpenNotes: () -> Unit,
    onNewClass: () -> Unit,
    onOpenClass: (ClassEntity) -> Unit
) {
    val c = FluentTheme.colors
    val classes by viewModel.classes.collectAsState()
    val notes by viewModel.notes.collectAsState()
    val currentWeek = remember { Prefs.currentWeek() }
    val advanceMinutes = remember { Prefs.getAdvanceMinutes() }

    // 「现在」每 30 秒走一格，和提醒引擎的轮询节奏一致
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000L)
            now = System.currentTimeMillis()
        }
    }

    val today = remember(classes, currentWeek, now) { TodaySchedule.today(classes, currentWeek, now) }
    val upcoming = remember(today, now) { today.filter { it.endMillis > now } }
    val ongoing = upcoming.firstOrNull { it.ongoingAt(now) }
    val featured = ongoing ?: upcoming.firstOrNull()
    val rest = upcoming.filter { it !== featured }
    val idle = remember(upcoming, now, advanceMinutes) { TodaySchedule.isIdle(upcoming, now, advanceMinutes) }
    val pickedNotes = remember(notes) { TodayNotePicker.pick(notes) }

    Column(Modifier.fillMaxSize()) {
        PageTopBar(
            title = greetingFor(now),
            subtitle = todaySubtitle(currentWeek, now)
        )

        Row(
            modifier = Modifier.fillMaxSize().padding(FluentTheme.dimens.pagePadding),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── 左列：主卡 + 今日时间轴 ──
            Column(
                modifier = Modifier.weight(1f).fillMaxHeight(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                when {
                    featured != null && featured.ongoingAt(now) -> HeroOngoing(featured, now)
                    featured != null -> HeroUpcoming(featured, now, idle)
                    else -> HeroEmpty(hasClassToday = today.isNotEmpty(), hasNotes = notes.isNotEmpty())
                }

                FlCard(modifier = Modifier.fillMaxWidth().weight(1f)) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                if (ongoing != null) "接下来的课" else "今天的课",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = c.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "共 ${upcoming.size} 节",
                                fontSize = 11.5.sp,
                                color = c.onSurfaceFaint
                            )
                            Spacer(Modifier.width(12.dp))
                            // 原来在页头右侧，现在下沉到卡片标题行 —— 动作贴着它作用的对象
                            FlButton(
                                "新建提醒",
                                onClick = onNewClass,
                                icon = Icons.Default.Add,
                                compact = true
                            )
                        }
                        FlDivider()
                        if (upcoming.isEmpty()) {
                            FlEmptyState(
                                title = if (today.isEmpty()) "今天没有课" else "今天的课上完了 🎉",
                                detail = emptyTodaySubtitle(today.isNotEmpty(), notes.isNotEmpty()),
                                modifier = Modifier.fillMaxSize()
                            )
                        } else {
                            ClassTableHeader()
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(upcoming, key = { it.entity.id }) { item ->
                                    ClassTableRow(
                                        item = item,
                                        now = now,
                                        onClick = { onOpenClass(item.entity) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── 右列：便签面板 ──
            Column(modifier = Modifier.width(300.dp).fillMaxHeight()) {
                FlCard(modifier = Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "便签 · ${notes.size} 条",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = c.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            Text(
                                "全部 ›",
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Medium,
                                color = c.accent,
                                modifier = Modifier.clickable { onOpenNotes() }
                            )
                            Spacer(Modifier.width(10.dp))
                            // 与左侧「今天的课」卡片标题行里的按钮**左右对齐**：
                            // 两行同为 top/bottom 8dp + end 12dp，按钮高度同为 28dp，中心线落在同一条水平线上
                            FlButton(
                                "添加便签",
                                onClick = onOpenNotes,
                                variant = FlButtonVariant.GHOST,
                                icon = Icons.Default.Add,
                                compact = true
                            )
                        }
                        FlDivider()
                        if (pickedNotes.isEmpty()) {
                            FlEmptyState("还没有便签", detail = "点「添加便签」随手记一条", modifier = Modifier.fillMaxSize())
                        } else {
                            LazyColumn(Modifier.fillMaxSize()) {
                                items(pickedNotes, key = { it.id }) { note ->
                                    NoteSummaryRow(note = note, now = now, onClick = onOpenNotes)
                                }
                                if (TodayNotePicker.needsMoreRow(notes)) {
                                    item {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(40.dp)
                                                .clickable { onOpenNotes() },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("…", fontSize = 14.sp, color = c.onSurfaceFaint)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── 主卡三态 ────────────────────────────────────────────────────

@Composable
private fun HeroOngoing(item: TodaySchedule.TodayClass, now: Long) {
    val c = FluentTheme.colors
    val left = ((item.endMillis - now) / 60_000L).coerceAtLeast(0L)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(FluentTheme.dimens.radiusCard))
            .background(c.accent)
            .padding(horizontal = 20.dp, vertical = 18.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(c.onAccent))
                Spacer(Modifier.width(8.dp))
                Text(
                    "正在上课 · 还剩 ${left / 60} 小时 ${left % 60} 分钟",
                    fontSize = 12.sp,
                    color = c.onAccent
                )
            }
            Spacer(Modifier.height(8.dp))
            Text(item.entity.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append("${hhmm(item.startMillis)} – ${hhmm(item.endMillis)}")
                    if (item.entity.room.isNotBlank()) append(" · ${item.entity.room}")
                    if (item.entity.teacher.isNotBlank()) append(" · ${item.entity.teacher}")
                },
                fontSize = 13.sp,
                color = c.onAccent.copy(alpha = 0.9f)
            )
        }
        Column(
            modifier = Modifier.align(Alignment.TopEnd),
            horizontalAlignment = Alignment.End
        ) {
            Text(hhmm(item.startMillis), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent)
            Text("开始", fontSize = 11.sp, color = c.onAccent.copy(alpha = 0.85f))
        }
    }
}

@Composable
private fun HeroUpcoming(item: TodaySchedule.TodayClass, now: Long, idle: Boolean) {
    val c = FluentTheme.colors
    FlCard(modifier = Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp)) {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(7.dp).clip(RoundedCornerShape(4.dp)).background(c.accent))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (idle) "当前空闲" else "即将上课",
                        fontSize = 12.sp,
                        color = c.accent
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(item.entity.title, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        if (idle) append("距离下一件事 ${remainingText(item.startMillis - now)}")
                        else append(remainingText(item.startMillis - now))
                        append(" · ${hhmm(item.startMillis)} – ${hhmm(item.endMillis)}")
                        if (item.entity.room.isNotBlank()) append(" · ${item.entity.room}")
                    },
                    fontSize = 13.sp,
                    color = c.onSurfaceVariant
                )
            }
            Column(
                modifier = Modifier.align(Alignment.TopEnd),
                horizontalAlignment = Alignment.End
            ) {
                Text(hhmm(item.startMillis), fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = c.accent)
                Text("开始", fontSize = 11.sp, color = c.onSurfaceFaint)
            }
        }
    }
}

@Composable
private fun HeroEmpty(hasClassToday: Boolean, hasNotes: Boolean) {
    val c = FluentTheme.colors
    FlCard(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 22.dp)) {
            Text(emptyTodayTitle(hasClassToday), fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = c.onSurface)
            Spacer(Modifier.height(6.dp))
            Text(emptyTodaySubtitle(hasClassToday, hasNotes), fontSize = 13.sp, color = c.onSurfaceVariant)
        }
    }
}

// ── 课程表格 ────────────────────────────────────────────────────

@Composable
private fun ClassTableHeader() {
    val c = FluentTheme.colors
    Row(
        modifier = Modifier.fillMaxWidth().background(c.surface).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("时间", modifier = Modifier.width(126.dp), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = c.onSurfaceFaint)
        Text("课程", modifier = Modifier.weight(1f), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = c.onSurfaceFaint)
        Text("教室", modifier = Modifier.width(150.dp), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = c.onSurfaceFaint)
        Text("教师", modifier = Modifier.width(90.dp), fontSize = 11.5.sp, fontWeight = FontWeight.SemiBold, color = c.onSurfaceFaint)
        Spacer(Modifier.width(72.dp))
    }
    FlDivider()
}

@Composable
private fun ClassTableRow(item: TodaySchedule.TodayClass, now: Long, onClick: () -> Unit) {
    val c = FluentTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val ongoing = item.ongoingAt(now)
    val temporary = item.entity.date.isNotEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(FluentTheme.dimens.rowHeight)
            .background(if (hovered) c.hover else Color.Transparent)
            .hoverable(interaction)
            .clickable(interaction, indication = null) { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "${hhmm(item.startMillis)} – ${hhmm(item.endMillis)}",
            modifier = Modifier.width(126.dp),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = c.accent
        )
        FlOneLine(item.entity.title, 13.sp, c.onSurface, Modifier.weight(1f))
        FlOneLine(item.entity.room.ifBlank { "—" }, 12.5.sp, c.onSurfaceVariant, Modifier.width(150.dp))
        FlOneLine(item.entity.teacher.ifBlank { "—" }, 12.5.sp, c.onSurfaceVariant, Modifier.width(90.dp))
        Box(Modifier.width(72.dp), contentAlignment = Alignment.CenterEnd) {
            when {
                ongoing -> FlChip("进行中", color = c.accent, filled = true)
                temporary -> FlChip("临时", color = c.warning, filled = true)
                else -> Unit
            }
        }
    }
    FlDivider()
}

// ── 便签摘要行 ──────────────────────────────────────────────────

@Composable
private fun NoteSummaryRow(note: NoteEntity, now: Long, onClick: () -> Unit) {
    val c = FluentTheme.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(if (hovered) c.hover else Color.Transparent)
            .hoverable(interaction)
            .clickable(interaction, indication = null) { onClick() }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = 3.dp, height = 18.dp).clip(RoundedCornerShape(2.dp)).background(fluentNoteColor(note.colorIndex)))
        Spacer(Modifier.width(10.dp))
        FlOneLine(note.text, 13.sp, c.onSurface, Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        val label = note.typeLabel()
        if (label.isNotEmpty()) {
            FlChip(label, color = if (note.hasDeadline) c.error else null, filled = note.hasDeadline)
        } else if (note.hasDeadline) {
            FlChip(deadlineCountdown(note.deadlineAt, now), color = c.error, filled = true)
        }
        if (label.isNotEmpty() && note.hasDeadline) {
            Spacer(Modifier.width(6.dp))
            FlChip(deadlineCountdown(note.deadlineAt, now), color = c.error, filled = true)
        }
    }
    FlDivider()
}

// ── 小工具 ──────────────────────────────────────────────────────

private val timeCal = Calendar.getInstance()

internal fun hhmm(millis: Long): String = synchronized(timeCal) {
    timeCal.timeInMillis = millis
    String.format(
        Locale.getDefault(),
        "%02d:%02d",
        timeCal.get(Calendar.HOUR_OF_DAY),
        timeCal.get(Calendar.MINUTE)
    )
}

@Composable
internal fun DateChip(millis: Long) {
    val c = FluentTheme.colors
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(FluentTheme.dimens.radiusControl))
            .background(c.surface3)
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.DateRange, contentDescription = null, tint = c.onSurfaceVariant, modifier = Modifier.size(13.dp))
        Spacer(Modifier.width(6.dp))
        Text(hhmm(millis), fontSize = 12.sp, color = c.onSurfaceVariant)
    }
}

/** 高度上限辅助：给详情面板里的长文本用 */
@Composable
internal fun LimitedHeight(max: Int, content: @Composable () -> Unit) {
    Box(Modifier.heightIn(max = max.dp)) { content() }
}
