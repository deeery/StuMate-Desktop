package com.example.classreminder

import com.example.classreminder.data.WeekSchedule
import com.example.classreminder.data.backup.JsonValue
import com.example.classreminder.data.backup.MiniJson
import com.example.classreminder.data.backup.bool
import com.example.classreminder.data.backup.int
import com.example.classreminder.data.backup.jsonObject
import com.example.classreminder.data.backup.long
import com.example.classreminder.data.backup.toJson
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 偏好设置。
 *
 * 安卓端用 SharedPreferences；桌面端换成 `%APPDATA%\StuMate\settings.json`，
 * 复用项目自研的 [MiniJson]（不引第三方库）。**默认值与夹取规则与安卓端逐条一致**，
 * 这样「设置」模块的备份在两端可以互相导入。
 *
 * 方法签名去掉了 `ctx` 参数，其余语义不变；写入是「内存缓存 + 整文件原子替换」，
 * 避免写一半崩溃导致配置文件损坏。
 */
object Prefs {

    // ── 默认值与约束（与安卓端一致） ─────────────────────────────────

    private const val DEFAULT_ADVANCE_MIN = 30
    private const val MIN_ADVANCE_MIN = 1
    private const val MAX_ADVANCE_MIN = 180

    /**
     * 课表默认按表格还是列表显示。
     *
     * **与安卓端不同**：安卓默认 false（列表，小屏更省空间），
     * 桌面屏幕宽，表格能一眼看完整周，所以默认 true。
     */
    private const val DEFAULT_WEEK_GRID = true

    /** 关闭窗口的行为：0 = 每次询问，1 = 最小化到托盘，2 = 直接退出 */
    const val CLOSE_ASK = 0
    const val CLOSE_TO_TRAY = 1
    const val CLOSE_QUIT = 2

    private val lock = Any()

    @Volatile
    private var cache: JsonValue.Obj? = null

    // ── 读写 ────────────────────────────────────────────────────────

    private fun obj(): JsonValue.Obj {
        cache?.let { return it }
        return synchronized(lock) {
            cache?.let { return it }
            val loaded = runCatching {
                val file = AppPaths.settingsFile
                if (file.isFile) MiniJson.parse(file.readText(Charsets.UTF_8)) as? JsonValue.Obj else null
            }.getOrNull() ?: jsonObject()
            loaded.also { cache = it }
        }
    }

    private fun put(key: String, value: JsonValue) {
        synchronized(lock) {
            val fields = LinkedHashMap(obj().fields)
            fields[key] = value
            val next = JsonValue.Obj(fields)
            cache = next
            writeAtomically(MiniJson.write(next, pretty = true))
        }
    }

    private fun writeAtomically(text: String) {
        runCatching {
            val file = AppPaths.settingsFile
            val tmp = java.io.File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(text, Charsets.UTF_8)
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }.onFailure { it.printStackTrace() }
    }

    /** 外部改了文件（例如导入设置后）时丢弃缓存，下次读取重新加载 */
    fun invalidate() {
        synchronized(lock) { cache = null }
    }

    // ── 提醒 ────────────────────────────────────────────────────────

    /**
     * 提前提醒的时间窗（分钟）。这里是唯一的读取入口，统一夹到 1~180：
     * 脏值（负数/超大）会让提醒判定要么永不命中、要么整周命中。
     */
    fun getAdvanceMinutes(): Int =
        obj().int("advanceMinutes", DEFAULT_ADVANCE_MIN).coerceIn(MIN_ADVANCE_MIN, MAX_ADVANCE_MIN)

    fun setAdvanceMinutes(minutes: Int) = put("advanceMinutes", minutes.toJson())

    fun getAutoStart(): Boolean = obj().bool("autoStart", false)

    fun setAutoStart(enabled: Boolean) = put("autoStart", enabled.toJson())

    fun getShowPopup(): Boolean = obj().bool("showPopup", true)

    fun setShowPopup(show: Boolean) = put("showPopup", show.toJson())

    // ── 首次运行与外观 ───────────────────────────────────────────────

    fun isFirstRun(): Boolean = obj().bool("firstRun", true)

    fun setFirstRunDone() = put("firstRun", false.toJson())

    /** 0 = 跟随系统，1 = 浅色，2 = 深色 */
    fun getThemeMode(): Int = obj().int("themeMode", 0).coerceIn(0, 2)

    fun setThemeMode(mode: Int) = put("themeMode", mode.toJson())

    // ── 周次校准 ────────────────────────────────────────────────────

    /** 第 1 周的周一（0 = 还没校准） */
    fun getWeek1Monday(): Long = obj().long("week1Monday", 0L)

    fun setWeek1Monday(mondayMillis: Long) = put("week1Monday", mondayMillis.toJson())

    /** 今天是第几周；没校准过返回 null（此时课表不按周次过滤） */
    fun currentWeek(): Int? {
        val week1Monday = getWeek1Monday()
        return if (week1Monday == 0L) null
        else WeekSchedule.weekNumber(week1Monday, System.currentTimeMillis())
    }

    // ── 界面状态（下次打开时接着上次看） ──────────────────────────────

    /** 课表默认按表格还是列表显示 */
    fun isWeekGrid(): Boolean = obj().bool("weekGrid", DEFAULT_WEEK_GRID)

    fun setWeekGrid(grid: Boolean) = put("weekGrid", grid.toJson())

    // ── 实验性功能 ──────────────────────────────────────────────────

    /**
     * 实验性：时间轴网格课表。默认开。
     *
     * 关掉后课表改用 v2.0 那版「按天分组、可折叠」的列表渲染。
     * 桌面端性能充裕，保留这个开关只是为了让两端的设置项一一对应。
     */
    fun isExperimentalGrid(): Boolean = obj().bool("experimentalGrid", true)

    fun setExperimentalGrid(enabled: Boolean) = put("experimentalGrid", enabled.toJson())

    // ── 桌面端特有 ──────────────────────────────────────────────────

    /**
     * 点窗口关闭按钮时的行为，见 [CLOSE_ASK] / [CLOSE_TO_TRAY] / [CLOSE_QUIT]。
     * 用户在关闭弹窗里勾了「下次不再提问」就会被写成 1 或 2。
     */
    fun getCloseAction(): Int = obj().int("closeAction", CLOSE_ASK).coerceIn(CLOSE_ASK, CLOSE_QUIT)

    fun setCloseAction(action: Int) = put("closeAction", action.coerceIn(CLOSE_ASK, CLOSE_QUIT).toJson())

    /** 通知栏气泡开关（对应安卓的通知权限，桌面端没有权限模型，只有用户意愿） */
    fun getNotifyEnabled(): Boolean = obj().bool("notifyEnabled", true)

    fun setNotifyEnabled(enabled: Boolean) = put("notifyEnabled", enabled.toJson())

    // ── 更新检查 ────────────────────────────────────────────────────

    /**
     * 启动时自动去 GitHub 查有没有新版本。默认开。
     *
     * 关掉后只在设置页「关于」里手动点「检查更新」。
     * **不做成「必须联网才能用」** —— 这个应用的核心功能完全离线，
     * 检查更新只是锦上添花，所以它必须能被彻底关掉。
     */
    fun getAutoCheckUpdate(): Boolean = obj().bool("autoCheckUpdate", true)

    fun setAutoCheckUpdate(enabled: Boolean) = put("autoCheckUpdate", enabled.toJson())

    /**
     * 查到新版本时**主动提醒**（关于页出现醒目卡片 + 启动时提示一次）。默认开。
     *
     * 关掉 ≠ 不检查：检查照旧，只是结果安静地躺在设置页里，不主动打扰。
     * 这是「提示可选」那条需求的落点。
     */
    fun getNotifyUpdate(): Boolean = obj().bool("notifyUpdate", true)

    fun setNotifyUpdate(enabled: Boolean) = put("notifyUpdate", enabled.toJson())
}
