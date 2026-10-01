package com.example.classreminder.data.backup

import com.example.classreminder.data.ClassEntity
import com.example.classreminder.data.NoteEntity

/**
 * 可以备份的内容模块。
 *
 * 每个模块**各自独立**：能单独导出成一个文件、也能单独从文件里导入，
 * 同时还能打包在一起整体导出 —— 所以这里的每一项都必须自带完整的编解码
 * （见 [BackupCodec]），模块之间不共享任何字段。
 *
 * [key] 是写进 JSON 的字段名，**一旦发布就不能改**（改了旧备份文件就读不出来了）。
 */
enum class BackupModule(val key: String, val title: String) {
    COURSES("courses", "课程表"),
    NOTES("notes", "便签"),
    SETTINGS("settings", "设置");

    companion object {
        /** 认不出来的模块名返回 null —— 旧版本导出的文件里可能有新版本才认识的模块 */
        fun byKey(key: String): BackupModule? = entries.firstOrNull { it.key == key }
    }
}

object BackupFormat {
    /** 文件里 `format` 字段的固定值，用来挡掉「随手选了个别的 JSON」 */
    const val FORMAT = "stumate-backup"

    /**
     * 结构版本。字段将来有增减就 +1，导入时按版本决定怎么读。
     *
     * v1 → v2：同步元数据（uid / updatedAt / deletedAt）。**三个字段全部可选**，
     * 导入 v1 旧备份时缺什么就取默认值（uid 空串 → 由 DAO 落库时补一个），旧文件继续可读。
     */
    const val SCHEMA = 2

    /** 导出文件的默认名（不含扩展名），后面接日期 */
    const val FILE_PREFIX = "StuMate-backup"

    const val APP_NAME = "StuMate-Android-Preview"
}

/**
 * 「设置」模块的快照。
 *
 * 抽成一个纯数据类（而不是直接读写 [com.example.classreminder.Prefs]），
 * 是为了让这一模块的编解码**不依赖 Android**，能和其它模块一样直接单测。
 *
 * 只收**用户偏好**，不收会话状态：`first_run`（首次运行标记）和 `last_tab`
 * （上次停在哪个页面）都是「这台设备的当前状态」，备份出去再导到另一台机器上
 * 没有意义，还会把对方的界面打乱。
 */
data class SettingsSnapshot(
    val advanceMinutes: Int = 30,
    val autoStart: Boolean = false,
    val showPopup: Boolean = true,
    val themeMode: Int = 0,
    val week1Monday: Long = 0L,
    val weekGrid: Boolean = false,
    val experimentalGrid: Boolean = true
)

/**
 * 一份备份文件的内容。
 *
 * [modules] 里放几个模块，这份文件就是「整体备份」还是「单模块备份」——
 * **格式完全一样**，所以解析器只有一条路径：
 * 单独导出课程表，和整体导出后再手动删掉别的模块，产出的文件是同一形状。
 */
data class BackupDocument(
    val schema: Int,
    val app: String,
    val exportedAt: Long,
    val modules: Map<BackupModule, JsonValue>
) {
    /** 只有 [modules] 里真的有数据的模块才算「包含」，空列表的模块会被算进来但条数为 0 */
    fun contains(module: BackupModule): Boolean = modules.containsKey(module)

    fun toJson(pretty: Boolean = true): String = MiniJson.write(
        jsonObject(
            "format" to BackupFormat.FORMAT.toJson(),
            "schema" to schema.toJson(),
            "app" to app.toJson(),
            "exportedAt" to exportedAt.toJson(),
            "modules" to JsonValue.Obj(modules.mapKeys { it.key.key })
        ),
        pretty
    )

    companion object {
        /**
         * 解析备份文件。**只做「是不是我们的文件」这一层校验**，
         * 具体字段的合法性交给各模块的解码器（它们会跳过救不回来的条目）。
         */
        fun parse(text: String): BackupDocument {
            val root = MiniJson.parse(text) as? JsonValue.Obj
                ?: throw IllegalArgumentException("文件内容不是一个 JSON 对象")

            val format = root.str("format")
            if (format != BackupFormat.FORMAT) {
                throw IllegalArgumentException("这不是 StuMate 的备份文件")
            }

            val modulesJson = root.objOrNull("modules")
                ?: throw IllegalArgumentException("备份文件里没有 modules 字段")

            val modules = LinkedHashMap<BackupModule, JsonValue>()
            modulesJson.fields.forEach { (key, value) ->
                // 认不出的模块名直接忽略：新版本导出的文件在旧版本上导入时，
                // 应该「能读几个读几个」，而不是整份拒绝
                BackupModule.byKey(key)?.let { modules[it] = value }
            }
            if (modules.isEmpty()) throw IllegalArgumentException("备份文件里没有任何可识别的模块")

            return BackupDocument(
                schema = root.int("schema", BackupFormat.SCHEMA),
                app = root.str("app"),
                exportedAt = root.long("exportedAt"),
                modules = modules
            )
        }
    }
}

/**
 * 各模块的编解码。
 *
 * 解码一律「**能救就救**」：单个条目缺关键字段（比如课程没标题）就跳过它，
 * 而不是让整份备份导入失败 —— 用户拿到的是一份能用的数据，外加一句「跳过了 N 条」。
 */
object BackupCodec {

    // ── 课程表 ────────────────────────────────────────────────────

    fun encodeCourses(list: List<ClassEntity>): JsonValue = jsonObject(
        "count" to list.size.toJson(),
        "items" to jsonArray(
            list.map { c ->
                jsonObject(
                    "id" to c.id.toJson(),
                    "title" to c.title.toJson(),
                    "dayOfWeek" to c.dayOfWeek.toJson(),
                    "startTime" to c.startTime.toJson(),
                    "endTime" to c.endTime.toJson(),
                    "room" to c.room.toJson(),
                    "notes" to c.notes.toJson(),
                    "teacher" to c.teacher.toJson(),
                    "weeks" to c.weeks.toJson(),
                    "date" to c.date.toJson(),
                    "uid" to c.uid.toJson(),
                    "updatedAt" to c.updatedAt.toJson(),
                    "deletedAt" to c.deletedAt.toJson()
                )
            }
        )
    )

    fun decodeCourses(value: JsonValue): List<ClassEntity> {
        val items = (value as? JsonValue.Obj)?.array("items") ?: return emptyList()
        return items.mapNotNull { item ->
            val o = item as? JsonValue.Obj ?: return@mapNotNull null
            val title = o.str("title")
            // 没标题的课在表格里画不出来，直接跳过
            if (title.isBlank()) return@mapNotNull null
            ClassEntity(
                id = o.int("id"),
                title = title,
                dayOfWeek = o.str("dayOfWeek"),
                startTime = o.str("startTime"),
                endTime = o.str("endTime"),
                room = o.str("room"),
                notes = o.str("notes"),
                teacher = o.str("teacher"),
                weeks = o.str("weeks"),
                date = o.str("date"),
                uid = o.str("uid"),
                updatedAt = o.long("updatedAt"),
                deletedAt = o.long("deletedAt")
            )
        }
    }

    // ── 便签 ──────────────────────────────────────────────────────

    fun encodeNotes(list: List<NoteEntity>): JsonValue = jsonObject(
        "count" to list.size.toJson(),
        "items" to jsonArray(
            list.map { n ->
                jsonObject(
                    "id" to n.id.toJson(),
                    "text" to n.text.toJson(),
                    "position" to n.position.toJson(),
                    "createdAt" to n.createdAt.toJson(),
                    "colorIndex" to n.colorIndex.toJson(),
                    "typeIndex" to n.typeIndex.toJson(),
                    "customLabel" to n.customLabel.toJson(),
                    "deadlineAt" to n.deadlineAt.toJson(),
                    "uid" to n.uid.toJson(),
                    "updatedAt" to n.updatedAt.toJson(),
                    "deletedAt" to n.deletedAt.toJson()
                )
            }
        )
    )

    fun decodeNotes(value: JsonValue): List<NoteEntity> {
        val items = (value as? JsonValue.Obj)?.array("items") ?: return emptyList()
        return items.mapNotNull { item ->
            val o = item as? JsonValue.Obj ?: return@mapNotNull null
            val text = o.str("text")
            // 空内容的便签没有意义（UI 上也是拒绝保存的）
            if (text.isBlank()) return@mapNotNull null
            NoteEntity(
                id = o.int("id"),
                text = text,
                position = o.int("position"),
                createdAt = o.long("createdAt"),
                colorIndex = o.int("colorIndex"),
                typeIndex = o.int("typeIndex"),
                customLabel = o.str("customLabel"),
                deadlineAt = o.long("deadlineAt"),
                uid = o.str("uid"),
                updatedAt = o.long("updatedAt"),
                deletedAt = o.long("deletedAt")
            )
        }
    }

    // ── 设置 ──────────────────────────────────────────────────────

    fun encodeSettings(snapshot: SettingsSnapshot): JsonValue = jsonObject(
        "advanceMinutes" to snapshot.advanceMinutes.toJson(),
        "autoStart" to snapshot.autoStart.toJson(),
        "showPopup" to snapshot.showPopup.toJson(),
        "themeMode" to snapshot.themeMode.toJson(),
        "week1Monday" to snapshot.week1Monday.toJson(),
        "weekGrid" to snapshot.weekGrid.toJson(),
        "experimentalGrid" to snapshot.experimentalGrid.toJson()
    )

    fun decodeSettings(value: JsonValue): SettingsSnapshot {
        val o = value as? JsonValue.Obj ?: return SettingsSnapshot()
        val defaults = SettingsSnapshot()
        return SettingsSnapshot(
            advanceMinutes = o.int("advanceMinutes", defaults.advanceMinutes),
            autoStart = o.bool("autoStart", defaults.autoStart),
            showPopup = o.bool("showPopup", defaults.showPopup),
            themeMode = o.int("themeMode", defaults.themeMode),
            week1Monday = o.long("week1Monday", defaults.week1Monday),
            weekGrid = o.bool("weekGrid", defaults.weekGrid),
            experimentalGrid = o.bool("experimentalGrid", defaults.experimentalGrid)
        )
    }

    /**
     * 各模块的数据条数 —— 导入前给用户看的「文件里有几条」。
     * 设置模块没有条数概念，返回 null。
     */
    fun countOf(module: BackupModule, value: JsonValue): Int? = when (module) {
        BackupModule.COURSES -> decodeCourses(value).size
        BackupModule.NOTES -> decodeNotes(value).size
        BackupModule.SETTINGS -> null
    }
}
