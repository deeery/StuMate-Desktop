package com.example.classreminder.platform

import com.example.classreminder.Prefs
import com.example.classreminder.data.ClassDao
import com.example.classreminder.data.TodaySchedule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.Locale

/**
 * 提醒引擎，替代安卓端的 `ClassReminderService`。
 *
 * 安卓版是「前台 Service + 30 秒轮询」；桌面端进程不会被系统主动杀掉，
 * 所以退化成「一个常驻协程 + 30 秒轮询」，不需要保活、不需要权限、不需要通知渠道。
 * **判定逻辑与文案与安卓端逐行对齐**，都复用 [TodaySchedule]。
 *
 * 触达方式：
 *  - 托盘气泡通知（[onNotify]，由 `Main.kt` 接到 Compose `Tray` 上）
 *  - 置顶全屏弹窗（[alert] 被 UI 观察，`showPopup` 为真时弹出，对应安卓的 `LockOverlayActivity`）
 */
object ReminderEngine {

    /** 轮询周期，与安卓端一致 */
    private const val POLL_SECONDS = 30L

    /** 首次检查延迟，与安卓端一致 */
    private const val INITIAL_DELAY_MS = 5_000L

    /** 超过这个间隔没检查过，认为刚从休眠中醒来，立即补检一次 */
    private const val SLEEP_GAP_MS = 90_000L

    // ── 对外状态 ────────────────────────────────────────────────────

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    var isRunning: Boolean
        get() = _running.value
        private set(value) {
            _running.value = value
        }

    /** 需要弹置顶卡片时会被置为非空；UI 关闭后置回 null */
    private val _alert = MutableStateFlow<Alert?>(null)
    val alert: StateFlow<Alert?> = _alert.asStateFlow()

    /** 托盘 tooltip / 菜单用的状态文案，对应安卓的动态前台通知 */
    private val _status = MutableStateFlow(Status("StuMate", "未启动"))
    val status: StateFlow<Status> = _status.asStateFlow()

    /** 由 `Main.kt` 注入：发一条系统通知（托盘气泡） */
    var onNotify: ((title: String, body: String) -> Unit)? = null

    data class Alert(
        val classId: Int,
        val title: String,
        val room: String,
        val startMillis: Long,
        val endMillis: Long,
        val ongoing: Boolean
    )

    data class Status(val title: String, val detail: String)

    // ── 内部 ────────────────────────────────────────────────────────

    private val classDao = ClassDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null
    private var lastShownId: Int? = null
    private var lastCheckAt = 0L
    private val timeCal = Calendar.getInstance()

    fun dismissAlert() {
        _alert.value = null
    }

    /** 启动轮询。重复调用是安全的（已在跑就直接返回） */
    fun start() {
        if (isRunning) return
        isRunning = true
        _status.value = Status("StuMate", "运行中 — 监控课程提醒")
        loop = scope.launch {
            delay(INITIAL_DELAY_MS)
            while (isActive) {
                runCatching { checkAndNotify() }
                delay(POLL_SECONDS * 1000L)
            }
        }
    }

    fun stop() {
        loop?.cancel()
        loop = null
        isRunning = false
        _status.value = Status("StuMate", "未启动")
        _alert.value = null
    }

    /** 设置页 / 托盘「立即检查」用 */
    fun checkNow() {
        scope.launch { runCatching { checkAndNotify() } }
    }

    private suspend fun checkAndNotify() {
        val now = System.currentTimeMillis()
        // 休眠唤醒补检：delay 在睡眠期间会被挂起，醒来后间隔会远大于轮询周期
        val wokeUp = lastCheckAt != 0L && now - lastCheckAt > SLEEP_GAP_MS
        lastCheckAt = now
        if (wokeUp) lastShownId = null

        val all = classDao.getAll()
        val currentWeek = Prefs.currentWeek()
        val todayClasses = TodaySchedule.today(all, currentWeek, now)

        val ongoing = todayClasses.filter { it.ongoingAt(now) }
        val advance = Prefs.getAdvanceMinutes()
        val upcoming = todayClasses.filter { it.startsWithin(now, advance * 60_000L) }

        val chosen = when {
            ongoing.isNotEmpty() -> ongoing.maxByOrNull { it.startMillis }
            upcoming.isNotEmpty() -> upcoming.minByOrNull { it.startMillis }
            else -> null
        }
        val remaining = TodaySchedule.remaining(todayClasses, now)

        // ① 托盘状态文案（对应安卓的动态前台通知）
        _status.value = when {
            chosen != null -> {
                val prefix = if (chosen.ongoingAt(now)) "正在上课" else "即将上课"
                Status(
                    "$prefix：${chosen.entity.title}",
                    "${chosen.entity.room}  ${formatTime(chosen.startMillis)} - ${formatTime(chosen.endMillis)}"
                )
            }
            todayClasses.isEmpty() -> Status("StuMate", "运行中 — 今日无课")
            remaining.isEmpty() -> Status("今日课程已全部结束！", "今天共 ${todayClasses.size} 节课")
            else -> {
                val next = remaining.first()
                Status(
                    "StuMate — 今日剩余 ${remaining.size} 节课",
                    "下一节：${next.entity.title} ${formatTime(next.startMillis)} ${next.entity.room}"
                )
            }
        }

        // ② 到点提醒：同一节课只提醒一次（对齐安卓的 lastShownId 去重）
        val picked = chosen ?: run {
            lastShownId = null
            return
        }
        val id = picked.entity.id
        if (lastShownId == id) return

        val prefix = if (now in picked.startMillis..picked.endMillis) "正在上课" else "即将上课"
        val title = "$prefix：${picked.entity.title}"
        val body = "${picked.entity.room}  ${formatTime(picked.startMillis)} - ${formatTime(picked.endMillis)}"

        if (Prefs.getNotifyEnabled()) onNotify?.invoke(title, body)
        if (Prefs.getShowPopup()) {
            _alert.value = Alert(
                classId = id,
                title = picked.entity.title,
                room = picked.entity.room,
                startMillis = picked.startMillis,
                endMillis = picked.endMillis,
                ongoing = now in picked.startMillis..picked.endMillis
            )
        }
        lastShownId = id
    }

    private fun formatTime(millis: Long): String = synchronized(timeCal) {
        timeCal.timeInMillis = millis
        String.format(
            Locale.getDefault(),
            "%02d:%02d",
            timeCal.get(Calendar.HOUR_OF_DAY),
            timeCal.get(Calendar.MINUTE)
        )
    }

    /** 应用退出时调用 */
    fun shutdown() {
        stop()
        scope.cancel()
    }
}
