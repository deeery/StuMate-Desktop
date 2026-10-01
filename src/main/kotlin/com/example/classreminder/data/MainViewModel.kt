package com.example.classreminder.data

import com.example.classreminder.Prefs
import com.example.classreminder.data.backup.BackupCodec
import com.example.classreminder.data.backup.BackupDocument
import com.example.classreminder.data.backup.BackupFormat
import com.example.classreminder.data.backup.BackupModule
import com.example.classreminder.data.backup.JsonValue
import com.example.classreminder.data.backup.SettingsSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 应用状态与业务操作的唯一入口。
 *
 * 与安卓端的差异只在**平台接缝**上：
 *  - `AndroidViewModel` + `viewModelScope` → 自带 [CoroutineScope]（随 application 生命周期）
 *  - 数据库来自手写 DAO 而非 Room
 *  - PDF 导入的入参从 `Uri` 换成 `File`
 *  - `Prefs` 不再需要 `Context`
 *
 * 业务规则（回撤栈、分类归一、导入去重、备份模块）**逐行照搬**，没有改动。
 */
class MainViewModel {

    private val dao = ClassDao()
    private val noteDao = NoteDao()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _classes = MutableStateFlow<List<ClassEntity>>(emptyList())
    val classes: StateFlow<List<ClassEntity>> = _classes.asStateFlow()

    // ── 快速便签 ────────────────────────────────────────────────────

    private val _notes = MutableStateFlow<List<NoteEntity>>(emptyList())
    val notes: StateFlow<List<NoteEntity>> = _notes.asStateFlow()

    /**
     * 回撤栈：每次改动前压一份「改动前的整表快照」。
     * 快照式撤销的好处是四种操作（增 / 改 / 删 / 拖动排序）共用一个回撤按钮，不用各写一套反向逻辑。
     */
    private val undoStack = ArrayDeque<List<NoteEntity>>()

    private val _canUndo = MutableStateFlow(false)
    val canUndo: StateFlow<Boolean> = _canUndo.asStateFlow()

    /** 便签的写操作全部串行化：否则连点时快照可能基于过期的列表，回撤会撤错一步 */
    private val noteMutex = Mutex()

    /**
     * 数据变更回调，在**每次成功写库之后**触发。
     *
     * ## 为什么用回调而不是在 ViewModel 里直接依赖 SyncEngine
     *
     * `data/` 这一层是纯 Kotlin 逻辑、不含平台依赖，145 个单测全靠这一点。
     * 让 ViewModel 直接 import `SyncEngine` 会把「网络 + 30 秒防抖」拖进业务层，
     * 单测就得先造一个引擎出来。
     *
     * 所以 ViewModel 只声明「我改完了」这个**信号**，具体做什么由 `Main.kt` 注入。
     * 触发时机统一挂在写库之后 —— 挂之前会同步到一份还没落库的数据。
     */
    @Volatile
    var onDataChanged: (() -> Unit)? = null

    private fun notifyDataChanged() {
        onDataChanged?.invoke()
    }

    init {
        loadClasses()
        loadNotes()
    }

    fun close() {
        scope.cancel()
    }

    /**
     * 从库里重新读一遍，刷新界面。
     *
     * 同步引擎把远端变更落库后调用它 —— 否则数据进了库但界面还停在旧内容，
     * 用户点开同步按钮看到「已同步」，课表却纹丝不动。
     *
     * 刻意**不**触发 [notifyDataChanged]：那是「本地写了数据」的信号，
     * 会排一次防抖同步。而这里的数据本来就是从服务端拉来的，
     * 再推回去是无意义的往返。
     */
    fun reloadFromDb() {
        loadClasses()
        loadNotes()
    }

    private fun loadClasses() {
        scope.launch {
            _classes.value = dao.getAll()
        }
    }

    private fun loadNotes() {
        scope.launch {
            _notes.value = noteDao.getAll()
        }
    }

    /** Insert a new class or replace an existing one (by id). */
    fun save(entity: ClassEntity) {
        scope.launch {
            dao.insert(entity)
            _classes.value = dao.getAll()
            notifyDataChanged()
        }
    }

    /** Delete a class. */
    fun delete(entity: ClassEntity) {
        scope.launch {
            dao.delete(entity)
            _classes.value = dao.getAll()
            notifyDataChanged()
        }
    }

    /**
     * 从课表 PDF（教务系统导出的那种）导入。
     * 已经是同一门课（标题/星期/起止时间都一样）的会覆盖，重复导入不会翻倍。
     *
     * @param onResult 给 UI 显示的一句话结果
     */
    fun importTimetable(file: File, onResult: (String) -> Unit) {
        scope.launch {
            val message = try {
                val bytes = withContext(Dispatchers.IO) {
                    if (file.isFile) file.readBytes() else null
                }
                val parsed = withContext(Dispatchers.IO) {
                    bytes?.let { TimetablePdfParser.parse(it) }.orEmpty()
                }
                when {
                    bytes == null -> "读取不到所选文件"
                    parsed.isEmpty() -> "这份 PDF 里没有识别到课程（只支持教务系统导出的课表）"
                    else -> {
                        insertAll(parsed)
                        "已导入 ${parsed.size} 条课程；上课时间按默认作息推算，可在列表里逐条修改"
                    }
                }
            } catch (t: Throwable) {
                t.printStackTrace()
                "导入失败：${t.message ?: t.javaClass.simpleName}"
            }
            onResult(message)
        }
    }

    private suspend fun insertAll(courses: List<TimetablePdfParser.Course>) {
        val existing = dao.getAll().toMutableList()
        // 现有 id 都是毫秒时间戳量级，同一批导入共用一个基准再递增，保证批内不撞 id
        val base = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val takenIds = mutableSetOf<Int>()
        courses.forEachIndexed { index, course ->
            val match = existing.firstOrNull {
                it.id !in takenIds &&
                    it.title == course.title && it.dayOfWeek == course.dayOfWeek &&
                    it.startTime == course.startTime && it.endTime == course.endTime
            }
            val id = match?.id ?: (base + index)
            takenIds += id
            val entity = course.toEntity(id)
            dao.insert(entity)
            // 直接更新内存列表，省掉末尾又一次 DB 读取
            if (match != null) {
                val idx = existing.indexOfFirst { it.id == id }
                if (idx >= 0) existing[idx] = entity
            } else {
                existing.add(entity)
            }
        }
        _classes.value = existing.toList()
    }

    // ── 快速便签的增删改与排序 ────────────────────────────────────────

    /**
     * 便签的统一写入口：先在锁内压入「改动前的整表快照」，再执行 [op]，最后重新读一遍列表。
     * 快照从数据库读，而不是读 _notes：首屏还没加载完就点添加时，_notes 可能是空的，
     * 拿它当快照会让第一次回撤把已有便签清空。
     */
    private fun mutateNotes(op: suspend () -> Unit) {
        scope.launch {
            noteMutex.withLock {
                undoStack.addLast(noteDao.getAll())
                while (undoStack.size > MAX_UNDO_DEPTH) undoStack.removeFirst()
                _canUndo.value = true
                op()
                _notes.value = noteDao.getAll()
                // 触发点挂在**锁内**、落库之后。
                // 挂锁外的话，notifyDataChanged 可能在 op() 还没写完时就跑，
                // 同步引擎会把一份旧数据推上去。
                notifyDataChanged()
            }
        }
    }

    /**
     * 新增便签。
     *
     * [aboveNoteId] 能在库里找到时，新便签插到这条便签的**上方**（首页选中某条便签后新建就走这条路）；
     * 传 null 或找不到就置顶。
     *
     * 插到中间后把 position 整体重写成 0..n-1，而不是取前后中点——反复往同一条上方插，
     * 中点法几次就把整数空间耗光，重写则永远有位置可用（便签量很小，这点代价可以忽略）。
     *
     * [typeIndex] / [customLabel] / [deadlineAt] 见 [NoteEntity]：这三个是「分类」相关字段，
     * 统一用 [sanitizeType] 归一，避免 UI 传进来越界下标或「非 Deadline 却带着时刻」这类脏组合。
     */
    fun addNote(
        text: String,
        aboveNoteId: Int? = null,
        colorIndex: Int = DEFAULT_NOTE_COLOR,
        typeIndex: Int = NOTE_TYPE_NONE,
        customLabel: String = "",
        deadlineAt: Long = 0L
    ) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val type = sanitizeType(typeIndex, customLabel, deadlineAt)
        mutateNotes {
            val current = noteDao.getAll()
            val anchor = aboveNoteId?.let { id -> current.indexOfFirst { it.id == id } } ?: -1
            val insertIndex = if (anchor >= 0) anchor else 0
            val fresh = NoteEntity(
                id = newNoteId(),
                text = trimmed,
                position = insertIndex,
                createdAt = System.currentTimeMillis(),
                colorIndex = colorIndex.coerceIn(0, NOTE_COLOR_COUNT - 1),
                typeIndex = type.first,
                customLabel = type.second,
                deadlineAt = type.third
            )
            // 先落库：下面的 updateAll 只更新已存在的行，新行必须先存在
            noteDao.insert(fresh)
            val reordered = current.toMutableList().apply { add(insertIndex, fresh) }
            noteDao.updateAll(reordered.mapIndexed { index, note -> note.copy(position = index) })
        }
    }

    /**
     * 改便签的文字、颜色与分类，顺序不动。
     *
     * 文字为空时**只改颜色 / 分类**、保留原文 —— 用户可能只想换个色号或挂个标签，
     * 不该因为输入框被清空就丢掉内容（保存按钮的 enabled 也按这个语义来）。
     */
    fun updateNote(
        id: Int,
        text: String,
        colorIndex: Int,
        typeIndex: Int = NOTE_TYPE_NONE,
        customLabel: String = "",
        deadlineAt: Long = 0L
    ) {
        val trimmed = text.trim()
        val color = colorIndex.coerceIn(0, NOTE_COLOR_COUNT - 1)
        val type = sanitizeType(typeIndex, customLabel, deadlineAt)
        val before = _notes.value.firstOrNull { it.id == id } ?: return
        // 内容和颜色/分类都没变就别占一格回撤
        val nextText = trimmed.ifEmpty { before.text }
        if (before.text == nextText && before.colorIndex == color &&
            before.typeIndex == type.first && before.customLabel == type.second &&
            before.deadlineAt == type.third
        ) return
        mutateNotes {
            val current = noteDao.getById(id) ?: return@mutateNotes
            noteDao.insert(
                current.copy(
                    text = nextText,
                    colorIndex = color,
                    typeIndex = type.first,
                    customLabel = type.second,
                    deadlineAt = type.third
                )
            )
        }
    }

    /**
     * 归一「分类」三兄弟，去处三种脏组合：
     *  - 下标越界 → 收敛到合法范围
     *  - 自定义类没填名字 → 存空串（显示时回落到占位示例，不必往库里塞「自定义」三个字）
     *  - 非 Deadline 类却带着时刻 → 时刻清零。否则用户从 Deadline 切回「工作」后，
     *    库里的旧时刻还在，下次再切回 Deadline 会「凭空冒出」一个早就过期的时间。
     */
    private fun sanitizeType(typeIndex: Int, customLabel: String, deadlineAt: Long): Triple<Int, String, Long> {
        val index = typeIndex.coerceIn(0, NOTE_TYPES.lastIndex)
        val type = noteTypeAt(index)
        val label = if (type.editableLabel) customLabel.trim() else ""
        val deadline = if (type.kind == NoteTypeKind.DEADLINE) deadlineAt.coerceAtLeast(0L) else 0L
        return Triple(index, label, deadline)
    }

    /** 只换色号。竖条即时预览之外，若单独调用也走这里 */
    fun updateNoteColor(id: Int, colorIndex: Int) {
        val color = colorIndex.coerceIn(0, NOTE_COLOR_COUNT - 1)
        val before = _notes.value.firstOrNull { it.id == id } ?: return
        if (before.colorIndex == color) return
        mutateNotes {
            val current = noteDao.getById(id) ?: return@mutateNotes
            noteDao.insert(current.copy(colorIndex = color))
        }
    }

    fun deleteNote(id: Int) {
        mutateNotes { noteDao.deleteById(id) }
    }

    /**
     * 拖动排序：[fromIndex] → [toIndex]（都是当前列表里的下标）。
     * 重排后把 position 整体重写成 0..n-1，避免长期累加后越界或撞车。
     */
    fun moveNote(fromIndex: Int, toIndex: Int) {
        if (fromIndex == toIndex) return
        mutateNotes {
            val current = noteDao.getAll()
            if (fromIndex !in current.indices || toIndex !in current.indices) return@mutateNotes
            val reordered = current.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
            noteDao.updateAll(reordered.mapIndexed { index, note -> note.copy(position = index) })
        }
    }

    /**
     * 回撤上一次便签操作。回撤本身不入栈——撤完还能继续往前撤，直到栈空按钮自动隐藏。
     */
    fun undoNote() {
        scope.launch {
            noteMutex.withLock {
                val snapshot = undoStack.removeLastOrNull()
                _canUndo.value = undoStack.isNotEmpty()
                if (snapshot == null) return@withLock
                // 整表替换：先清空再写快照，增删改排序四种情况都能还原
                noteDao.deleteAll()
                if (snapshot.isNotEmpty()) noteDao.insertAll(snapshot)
                _notes.value = noteDao.getAll()
            }
        }
    }

    /** 毫秒级 id，并避开当前已有的 id，免得 REPLACE 把别人的便签顶掉 */
    private suspend fun newNoteId(): Int {
        val taken = noteDao.getAll().mapTo(mutableSetOf()) { it.id }
        var id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        while (id in taken) id++
        return id
    }

    // ── 备份：模块化导入 / 导出 ──────────────────────────────────────
    //
    // 导出与导入共用同一份「模块」定义（BackupModule）：传一个模块就是单独导出 / 导入
    // 那一块，传全部就是整体 —— 两种产出的**文件格式完全一样**，
    // 所以不存在「整体」和「单模块」两套代码，解析路径只有一条。

    /**
     * 生成备份文本。
     *
     * 空模块集合会产出一份没有任何 modules 的文件、解析时会被拒 ——
     * 所以 UI 那边保证不会用空集合调进来。
     */
    suspend fun buildBackupText(modules: Set<BackupModule>): String = withContext(Dispatchers.IO) {
        val payload = LinkedHashMap<BackupModule, JsonValue>()
        if (BackupModule.COURSES in modules) {
            payload[BackupModule.COURSES] = BackupCodec.encodeCourses(dao.getAll())
        }
        if (BackupModule.NOTES in modules) {
            payload[BackupModule.NOTES] = BackupCodec.encodeNotes(noteDao.getAll())
        }
        if (BackupModule.SETTINGS in modules) {
            payload[BackupModule.SETTINGS] = BackupCodec.encodeSettings(readSettings())
        }
        BackupDocument(
            schema = BackupFormat.SCHEMA,
            app = BackupFormat.APP_NAME,
            exportedAt = System.currentTimeMillis(),
            modules = payload
        ).toJson()
    }

    /**
     * 本机某个模块当前的条数，给导入对话框显示「本机 X 条 → 文件 Y 条」。
     * 设置模块没有条数概念，返回 null。
     */
    suspend fun localCount(module: BackupModule): Int? = withContext(Dispatchers.IO) {
        when (module) {
            BackupModule.COURSES -> dao.getAll().size
            BackupModule.NOTES -> noteDao.getAll().size
            BackupModule.SETTINGS -> null
        }
    }

    /**
     * 应用一份备份。[modules] 是用户勾选、并且**已经逐个确认过要覆盖**的模块。
     *
     * 每个模块都是整体替换（先清空再写入），不做增量合并 —— 这正是
     * 「覆盖前逐个确认」要保护的那件事，所以这里不再二次询问。
     */
    fun importBackup(document: BackupDocument, modules: Set<BackupModule>, onDone: (String) -> Unit) {
        scope.launch {
            val message = try {
                withContext(Dispatchers.IO) {
                    if (BackupModule.COURSES in modules) {
                        document.modules[BackupModule.COURSES]?.let { value ->
                            val incoming = BackupCodec.decodeCourses(value)
                            dao.deleteAll()
                            incoming.forEach { dao.insert(it) }
                        }
                    }
                    if (BackupModule.NOTES in modules) {
                        document.modules[BackupModule.NOTES]?.let { value ->
                            val incoming = BackupCodec.decodeNotes(value)
                            // 整体替换：清空 + 写回（和「回撤」同一条路径）。
                            // 顺手清掉回撤栈 —— 撤回到导入前的数据会让人以为导入没生效
                            noteDao.deleteAll()
                            if (incoming.isNotEmpty()) noteDao.insertAll(incoming)
                            undoStack.clear()
                            _canUndo.value = false
                        }
                    }
                    if (BackupModule.SETTINGS in modules) {
                        document.modules[BackupModule.SETTINGS]?.let { value ->
                            writeSettings(BackupCodec.decodeSettings(value))
                        }
                    }
                    _classes.value = dao.getAll()
                    _notes.value = noteDao.getAll()
                }
                "已导入：" + modules.joinToString("、") { it.title }
            } catch (t: Throwable) {
                t.printStackTrace()
                "导入失败：${t.message ?: t.javaClass.simpleName}"
            }
            onDone(message)
        }
    }

    private fun readSettings(): SettingsSnapshot = SettingsSnapshot(
        advanceMinutes = Prefs.getAdvanceMinutes(),
        autoStart = Prefs.getAutoStart(),
        showPopup = Prefs.getShowPopup(),
        themeMode = Prefs.getThemeMode(),
        week1Monday = Prefs.getWeek1Monday(),
        weekGrid = Prefs.isWeekGrid(),
        experimentalGrid = Prefs.isExperimentalGrid()
    )

    private fun writeSettings(snapshot: SettingsSnapshot) {
        Prefs.setAdvanceMinutes(snapshot.advanceMinutes)
        Prefs.setAutoStart(snapshot.autoStart)
        Prefs.setShowPopup(snapshot.showPopup)
        Prefs.setThemeMode(snapshot.themeMode)
        Prefs.setWeek1Monday(snapshot.week1Monday)
        Prefs.setWeekGrid(snapshot.weekGrid)
        Prefs.setExperimentalGrid(snapshot.experimentalGrid)
    }

    companion object {
        /** 回撤栈深度上限：便签体积很小，留 50 步足够用，也不会一直占内存 */
        private const val MAX_UNDO_DEPTH = 50
    }
}
