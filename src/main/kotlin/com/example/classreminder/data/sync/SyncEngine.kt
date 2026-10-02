package com.example.classreminder.data.sync

import com.example.classreminder.AppPaths
import com.example.classreminder.data.SyncDao
import com.example.classreminder.data.backup.BackupCodec
import com.example.classreminder.data.backup.BackupDocument
import com.example.classreminder.data.backup.BackupFormat
import com.example.classreminder.data.backup.BackupModule
import com.example.classreminder.data.backup.JsonValue
import com.example.classreminder.data.backup.MiniJson
import com.example.classreminder.data.backup.jsonArray
import com.example.classreminder.data.backup.jsonObject
import com.example.classreminder.data.backup.long
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 云同步引擎（客户端侧，设计 v1.3 §5）。
 *
 * ## 一轮同步的四步，顺序不能换
 *
 * ```
 * 1. push  本地变更推上去
 * 2. pull  服务端变更拉下来
 * 3. apply 落到本地库
 * 4. 记游标
 * ```
 *
 * ## 为什么先 push 再 pull
 *
 * 反过来的话：先 pull 落库，这批记录的 `updatedAt` 是服务端的值，
 * 而客户端并不知道「它们已经同步过了」，下次 push 又会原样推上去。
 * 服务端靠 `baseVersion` 判成冲突而不重复写入，但白白多一轮往返，
 * 而且 `conflicts` 里会挤满本不该有的条目，用户会看到「覆盖了 N 条」的假警告。
 *
 * ## 为什么 `baseVersion` 一律填 0
 *
 * 服务端的 `version` 是**它自己的**版本号，本地无从得知（除非再建一张映射表，
 * 那样每次同步都要多存一份状态，收益不抵复杂度）。填 0 意味着：
 * 服务端永远走「按 `updatedAt` 比」的 LWW 分支 —— 这正是我们想要的语义。
 */
class SyncEngine(
    private val scope: CoroutineScope,
    private val syncDao: SyncDao = SyncDao(),
    private val prefs: SyncPrefs = SyncPrefs(),
    /**
     * 同步落库后通知外界刷新界面。
     *
     * 默认空实现 —— 纯逻辑测试不需要界面，`Main.kt` 才注入
     * `viewModel::reloadFromDb`。不设成必填参数是为了不让 `SyncEngine`
     * 依赖 `MainViewModel`（那会把数据层和 UI 层绑在一起）。
     */
    private val onApplied: (Int) -> Unit = {}
) {

    private val _state = MutableStateFlow(SyncState())
    val state: StateFlow<SyncState> = _state.asStateFlow()

    /**
     * 同一时刻只跑一轮。
     *
     * 没这把锁的话「启动同步」与「用户点手动同步」会并发跑，
     * 两轮各自记游标 → 后写的用旧游标盖掉新的 → 下次重复拉一堆。
     */
    private val running = Mutex()

    private var debounceJob: Job? = null

    /** [startOnLaunch] 的一次性守卫；见该方法的 KDoc */
    private var launchSyncDone = false

    companion object {
        /** 写操作后等这么久才真的同步（设计 §5.8） */
        const val DEBOUNCE_MS = 30_000L
    }

    // ── 触发 ────────────────────────────────────────────────────

    /**
     * 有本地写操作时调用。30 秒内没有新写入才真的同步。
     *
     * 每次调用都**重置**计时器 —— 连续改 10 分钟就一直不同步，
     * 停下 30 秒后才推送。这是防抖该有的行为。
     */
    fun scheduleSync() {
        if (!AccountSession.signedIn) return
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(DEBOUNCE_MS)
            runCatching { syncOnce() }
        }
    }

    /** 手动同步（设置页按钮）。立刻执行，不等防抖 */
    fun syncNow(onDone: (SyncState) -> Unit = {}) {
        if (!AccountSession.signedIn) {
            onDone(_state.value.copy(phase = SyncPhase.SKIPPED, message = "未登录，不会同步"))
            return
        }
        debounceJob?.cancel()
        scope.launch { onDone(syncOnce()) }
    }

    /**
     * 应用启动时调用。**同一个登录会话内最多生效一次**。
     *
     * ## 为什么要自己去重，而不是指望上层只调一次
     *
     * 上层是 `LaunchedEffect(signedIn)`，而实测（2026-10-02，Compose 1.5.10）
     * 该 effect 在**键值未变**的情况下也会被重复执行一次：
     * 探针日志里连续出现两条 `prev=true now=true`，而 `_user` 只发射了
     * `null → user` 两次（没有任何登出、`signOutLocally` 也没被调用）。
     * 结果就是一次启动跑两遍同步 —— 每遍都会 `push`（`collectLocalChanges()`
     * 是**无条件**把本地所有行推上去的，不是只推变更），于是服务端每遍都回
     * `replace_local=true`，本地被清空重拉两遍，落两个备份文件。
     *
     * 与其在 Compose 的重组语义上赌，不如把「一次启动一次同步」放在引擎自己身上：
     * 这里的守卫与重组、effect 重启、甚至上层将来多接几个触发点都无关。
     * [resetForSignOut] 会把它清掉，所以「登出再登录」仍会同步一次。
     */
    fun startOnLaunch() {
        if (launchSyncDone) return
        if (!AccountSession.signedIn) return
        launchSyncDone = true
        scope.launch { runCatching { syncOnce() } }
    }

    /**
     * **用户主动登录后**调用：把本地强制对齐到服务端（＝首端设备）的配置。
     *
     * ## 和 [startOnLaunch] 的区别只有一点：要不要对齐
     *
     * - 启动时恢复登录态 = 「接着用本机已有的数据」，**不动它**；
     * - 用户主动登录 = 「我要用这个账号的配置」，这时本地那份可能来自
     *   上一次登录的账号、也可能早就跟云端分叉了，所以要拉回正轨。
     *
     * ## 对齐的具体动作
     *
     * 见 [syncOnce] 的第 0 步：先问服务端有没有数据，
     * 有就**跳过 push** 直接「备份 → 清空 → 全量拉」。被覆盖掉的那份本地配置
     * 会写到 `StuMate-preinit-backup-<时间戳>.json`，路径显示在同步卡上。
     *
     * ## 为什么不用 Compose 的登录态当触发信号
     *
     * `signedIn` 在「启动恢复」和「主动登录」两种情况下都会从 false 变 true，
     * 拿它当信号会把「每次开软件」也变成「每次清库」—— 那正是刚修掉的缺陷。
     * 所以用 [AccountSession.loginEpoch]：只有 [AccountSession.login] /
     * [AccountSession.register] / 第三方登录成功才会自增，`restore()` 不动它。
     */
    fun syncAfterLogin() {
        if (!AccountSession.signedIn) return
        // 本次会话的「启动同步」已被它取代，别再让 startOnLaunch 多跑一遍
        launchSyncDone = true
        scope.launch { runCatching { syncOnce(forceAlign = true) } }
    }

    /** 退出登录时清掉游标 —— 换账号后不能接着上一个人的游标拉 */
    fun resetForSignOut() {
        debounceJob?.cancel()
        // 允许下一个登录会话再走一次启动同步
        launchSyncDone = false
        prefs.lastCursor = 0
        // 忘掉「本地数据属于哪个账号」：下一个账号的第一轮同步要重新走
        // §5.8 的归属判定，否则会拿上一个账号的资格去推新账号的数据。
        prefs.syncedAccountId = 0
        _state.value = SyncState(message = "未登录，不会同步")
    }

    // ── 主流程 ──────────────────────────────────────────────────

    private suspend fun syncOnce(forceAlign: Boolean = false): SyncState = running.withLock {
        _state.value = _state.value.copy(phase = SyncPhase.SYNCING, message = "同步中…")

        val token = AccountSession.accessToken()
        if (token == null) {
            return@withLock _state.value
                .copy(phase = SyncPhase.SKIPPED, message = "未登录，不会同步")
        }

        var pushedAny = false
        var alignedToServer = false
        try {
            // ── 0. 先决定「本地这份分歧能不能推上去」（§5.8 首端权威）──
            //
            // 两种情况必须先问服务端，不能上来就 push：
            //   · [syncAfterLogin]：用户主动登录，要求「让我这台显示首端那台的配置」；
            //   · 本设备还没跟这个账号同步过：本地这份数据的来历不明
            //     （上一台设备？上一个账号？上一次安装？），没有资格覆盖服务端。
            //
            // 判定只有一条规则：
            //   服务端没有数据         → 照常 push。**必须**这样，否则
            //                            `initial_device_id` 永远是 null，谁都成不了首端
            //   服务端有数据 + 我是首端 → 照常 push（本地就是权威本身，没什么可「对齐」的）
            //   服务端有数据 + 我不是首端 → **跳过 push**，直接「备份 → 清空 → 全量拉」，
            //                            本地这份分歧只留在备份文件里
            //
            // ## 为什么非要提前问
            //
            // 原来的写法是「先 push，再看服务端回的 `replace_local`」—— 可那时
            // 本地数据**已经推上去了**，§5.8「首端权威」形同虚设。
            // 实测后果：桌面端的演示数据被推成服务端 revision 140–154，与安卓数据并存。
            //
            // ⚠️ `isInitialDevice != true` 而不是 `== false`：这个字段是三态的，
            // null（还没有设备认领）和 false（首端是别人）要区别对待 ——
            // 见 `SyncStatus.isInitialDevice` 与 `boolOrNull`。
            val accountId = AccountSession.user.value?.id ?: 0
            val neverSyncedThisAccount = prefs.syncedAccountId != accountId
            var replaceNow = false
            if (forceAlign || neverSyncedThisAccount) {
                val status = SyncApi.status(token)
                val serverHasData = status.courses > 0 || status.notes > 0
                if (serverHasData && status.isInitialDevice != true) {
                    replaceNow = true
                    alignedToServer = true
                }
            }

            // ── 1. push ────────────────────────────────────────
            var push: PushResult? = null
            if (!replaceNow) {
                val outgoing = collectLocalChanges()
                push = if (outgoing.isEmpty()) null else SyncApi.push(outgoing, token)
                if (push != null) pushedAny = true

                // 服务端说「你不是第一台」→ 本地要备份后清空，再全量重拉（§5.8）
                //
                // ⚠️ 这里**必须**写 `== true`，不能写 `!= false` 或直接用。
                // 服务端的 `isInitialDevice` 是**三态**的：
                //   true  = 我就是首端 → 不清空
                //   false = 首端是别的设备 → 要清空
                //   null  = 还没任何设备认领过（用户刚注册）→ **绝不能清空**，会白丢数据
                // 而 pull / push 路由里写的是 `replaceLocal = (initial === false)`，
                // 把 true 和 null 合并成了 false。所以这里收到的 false 是
                // 「我是首端」或「还没人认领」两种情况 —— **都清不得**。
                // 反过来写（凡非 true 就清）会在用户刚注册首跑时把本地数据全丢掉。
                if (push?.replaceLocal == true) replaceNow = true
            }

            if (replaceNow) {
                // 备份路径要如实带到 UI 上：这一刻用户本地数据被清空了，
                // 他必须知道去哪找那份备份才安心（设计 §5.8 要求「明确告知路径」）
                val backup = handleReplaceLocal()
                if (backup != null) {
                    _state.value = _state.value.copy(backupPath = backup.absolutePath)
                }
            }

            // ── 2. pull（循环到拉干净） ─────────────────────────
            var cursor = prefs.lastCursor
            val incoming = ArrayList<RemoteChange>()
            var serverCursor = push?.cursor ?: cursor

            // `hasMore` 必须循环处理：服务端一次最多给 500 条，
            // 用户攒到上千条时一次拉不完。不循环就会静默丢掉后半截 ——
            // 而「同步了但少了数据」比「没同步」糟糕得多。
            //
            // 首端切换时 [handleReplaceLocal] 已把游标归零，所以这一轮的第一页就是全量，
            // 但**仍要翻页**（服务端每页上限 500，超过 500 条的账号第一页装不下）。
            var guard = 0
            var hasMore = true
            while (hasMore && guard++ < 1000) {
                val page = SyncApi.pull(cursor = cursor, token = token)
                incoming += page.changes
                cursor = page.cursor
                serverCursor = page.cursor
                hasMore = page.hasMore
            }

            // ── 3. apply ──────────────────────────────────────
            val applied = applyRemote(incoming)

            // 有东西落库就通知界面刷新 —— 否则数据进了库、界面还停在旧内容，
            // 用户看到「已同步」却发现课表纹丝不动。
            if (applied > 0) onApplied(applied)

            // ── 4. 记游标 ─────────────────────────────────────
            // 只在成功收尾后推进。中途失败保持原值，下次重来 —— 宁可多拉一次，不可漏数据。
            prefs.lastCursor = serverCursor
            // 记下「本地这份数据现在属于哪个账号」。下一轮起就不必再问服务端
            // 「我有没有资格 push」了 —— 这一轮已经按 §5.8 把归属理清了。
            // **只在成功收尾后写**：失败时保持原值，下一轮重新判定。
            prefs.syncedAccountId = accountId

            // 同步成功顺手清理 30 天前的墓碑
            val purged = syncDao.purgeOldTombstones()

            val overridden = push?.conflicts?.size ?: 0
            val result = SyncState(
                phase = SyncPhase.IDLE,
                lastSyncedAt = System.currentTimeMillis(),
                message = if (alignedToServer) {
                    "已对齐首端配置 · 拉取 $applied 条"
                } else {
                    describe(pushedAny, applied, overridden, purged)
                },
                overriddenCount = overridden,
                // ⚠️ 必须把备份路径**带过来**。`SyncState(...)` 是新建对象，
                // 不显式传就丢 —— 上面刚写进 `_state.value` 的 backupPath 会被这行抹掉，
                // 用户永远看不到「你的数据备份在哪」，而这正是他唯一能找回数据的地方。
                backupPath = _state.value.backupPath
            )
            _state.value = result
            result
        } catch (e: ApiException) {
            val result = _state.value.copy(
                phase = SyncPhase.FAILED,
                message = if (e.isNetwork) "连不上服务器，稍后会自动重试" else e.message
            )
            _state.value = result
            result
        } catch (e: Exception) {
            val result = _state.value.copy(
                phase = SyncPhase.FAILED,
                message = e.message ?: "同步失败"
            )
            _state.value = result
            result
        }
    }

    // ── 收集本地变更 ────────────────────────────────────────────

    /**
     * 收集本地待推送的记录。
     *
     * 推**全部**行（含已软删的）而不是「有变化的」：
     * 客户端没有「哪些比服务端新」的本地状态，靠服务端的 LWW 判定即可。
     * 代价是流量，但每条几百字节、10 人规模，完全可接受。
     *
     * ⚠️ `uid` 为空的行直接过滤掉：v7 迁移会给老数据补 uid，
     * 但万一行是在迁移前插入的（异常路径），空 uid 推上去会被服务端判非法。
     */
    private suspend fun collectLocalChanges(): List<LocalChange> {
        val out = ArrayList<LocalChange>()

        syncDao.allClasses().forEach { c ->
            if (c.uid.isBlank()) return@forEach
            out += LocalChange(
                entity = "class",
                uid = c.uid,
                data = oneItem(BackupCodec.encodeCourses(listOf(c))),
                updatedAt = c.updatedAt,
                deletedAt = c.deletedAt,
                baseVersion = 0
            )
        }

        syncDao.allNotes().forEach { n ->
            if (n.uid.isBlank()) return@forEach
            out += LocalChange(
                entity = "note",
                uid = n.uid,
                data = oneItem(BackupCodec.encodeNotes(listOf(n))),
                updatedAt = n.updatedAt,
                deletedAt = n.deletedAt,
                baseVersion = 0
            )
        }

        return out
    }

    /** 把 `{count, items:[x]}` 拆出 `x` —— 服务端要的是裸记录，不要那层包装 */
    private fun oneItem(v: JsonValue): JsonValue.Obj {
        val items = (v as? JsonValue.Obj)?.fields?.get("items") as? JsonValue.Arr
        return items?.items?.firstOrNull() as? JsonValue.Obj ?: JsonValue.Obj(emptyMap())
    }

    // ── 应用服务端变更 ──────────────────────────────────────────

    /**
     * 把服务端来的变更落到本地，返回真正改动了几行。
     *
     * 判定逻辑全在 [SyncMerge] 里（纯函数，有单测穷举边界），
     * 这里只负责「查库 → 调判定 → 落库」这三步胶水。
     */
    private suspend fun applyRemote(changes: List<RemoteChange>): Int {
        if (changes.isEmpty()) return 0

        var applied = 0

        val localClasses = syncDao.allClasses().associateBy { it.uid }
        val localNotes = syncDao.allNotes().associateBy { it.uid }
        var cursorClass = syncDao.nextFreeClassId()
        var cursorNote = syncDao.nextFreeNoteId()
        val usedClassIds = localClasses.values.map { it.id }.toMutableSet()
        val usedNoteIds = localNotes.values.map { it.id }.toMutableSet()

        for (change in changes) {
            val remoteUpdatedAt = change.data.syncUpdatedAt()
            val remoteDeletedAt = change.data.syncDeletedAt()

            if (change.entity == "class") {
                val decoded = BackupCodec.decodeCourses(
                    jsonObject("items" to jsonArray(listOf(change.data)))
                ).firstOrNull() ?: continue

                val local = localClasses[change.uid]
                val decision = SyncMerge.resolve(
                    localIdOfUid = local?.id,
                    localUpdatedAt = local?.updatedAt ?: 0L,
                    remoteUpdatedAt = remoteUpdatedAt,
                    remotePreferredId = decoded.id,
                    cursor = cursorClass,
                    used = usedClassIds
                )
                if (decision is SyncMerge.Decision.Skip) continue

                val id = (decision as SyncMerge.Decision.Write).id
                cursorClass = decision.nextCursor
                usedClassIds += id

                syncDao.upsertClass(
                    decoded.copy(
                        id = id,
                        uid = change.uid,
                        updatedAt = remoteUpdatedAt,
                        deletedAt = remoteDeletedAt
                    )
                )
                applied++
                continue
            }

            if (change.entity == "note") {
                val decoded = BackupCodec.decodeNotes(
                    jsonObject("items" to jsonArray(listOf(change.data)))
                ).firstOrNull() ?: continue

                val local = localNotes[change.uid]
                val decision = SyncMerge.resolve(
                    localIdOfUid = local?.id,
                    localUpdatedAt = local?.updatedAt ?: 0L,
                    remoteUpdatedAt = remoteUpdatedAt,
                    remotePreferredId = decoded.id,
                    cursor = cursorNote,
                    used = usedNoteIds
                )
                if (decision is SyncMerge.Decision.Skip) continue

                val id = (decision as SyncMerge.Decision.Write).id
                cursorNote = decision.nextCursor
                usedNoteIds += id

                syncDao.upsertNote(
                    decoded.copy(
                        id = id,
                        uid = change.uid,
                        updatedAt = remoteUpdatedAt,
                        deletedAt = remoteDeletedAt
                    )
                )
                applied++
            }
        }

        return applied
    }

    // ── 首端切换 ────────────────────────────────────────────────

    /**
     * 非首端设备：先备份，再清空，游标归零。
     *
     * ## 备份这一步绝不能省（设计 §5.8 明确要求）
     *
     * 用户可能在这台机器上攒了一批只在本地存在的课程。
     * 服务端说「你不是第一台」意味着服务端有另一份完整数据，
     * 但那两批数据的历史 uid 不同 —— 直接清空就等于丢弃本地这批。
     *
     * 所以先写一份完整备份到数据目录，并把路径如实显示在 UI 上。
     * 万一用户发现服务端的不是他想要的，还能从备份恢复。
     *
     * 返回备份文件路径，null 表示备份失败（**照样继续**，
     * 但要让 UI 知道这件事，因为此时用户的数据真的没有退路了）。
     */
    private suspend fun handleReplaceLocal(): File? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val file = AppPaths.dataDir.resolve("StuMate-preinit-backup-$stamp.json")

        val backup = runCatching {
            val doc = BackupDocument(
                schema = BackupFormat.SCHEMA,
                app = BackupFormat.APP_NAME,
                exportedAt = System.currentTimeMillis(),
                modules = linkedMapOf(
                    BackupModule.COURSES to BackupCodec.encodeCourses(syncDao.allClasses()),
                    BackupModule.NOTES to BackupCodec.encodeNotes(syncDao.allNotes())
                )
            )
            file.writeText(doc.toJson(pretty = true), Charsets.UTF_8)
            file
        }.getOrNull()

        syncDao.purgeLocalData()
        prefs.lastCursor = 0
        _state.value = _state.value.copy(needsFullResync = false)
        return backup
    }

    private fun describe(
        pushed: Boolean,
        applied: Int,
        overridden: Int,
        purged: Int
    ): String = buildString {
        if (overridden > 0) {
            append("已同步 · 服务端更新覆盖了 $overridden 条")
            if (applied > 0) append(" · 拉取 $applied 条")
        } else {
            append("已同步")
            if (applied > 0) append(" · 拉取 $applied 条")
            else if (!pushed) append(" · 已是最新")
        }
        if (purged > 0) append(" · 清理了 $purged 条删除记录")
    }
}

/** 同步状态 */
data class SyncState(
    val phase: SyncPhase = SyncPhase.IDLE,
    val lastSyncedAt: Long = 0L,
    val message: String = "还没同步过",
    /** 上次同步覆盖掉了多少条本地记录（设计 §5.5 要求如实告知，不隐藏） */
    val overriddenCount: Int = 0,
    /** 首端切换时写下的自动备份路径，UI 要把它显示给用户 */
    val backupPath: String? = null,
    val needsFullResync: Boolean = false
)

enum class SyncPhase { IDLE, SYNCING, FAILED, SKIPPED }

/**
 * 同步的本地游标，单独存一个小文件。
 *
 * 为什么不塞进 `settings.json`：同步状态不该被「恢复设置备份」这类操作连带覆盖。
 * 单独一个文件，最坏情况就是丢了 → 退化成全量重拉，而不是数据出错。
 */
class SyncPrefs(
    private val file: File = AppPaths.dataDir.resolve("sync.json")
) {
    var lastCursor: Int
        get() = (read().get("cursor") as? JsonValue.Num)?.value?.toInt() ?: 0
        set(value) {
            val fields = LinkedHashMap(read())
            fields["cursor"] = JsonValue.Num(value.toDouble())
            runCatching { file.writeText(MiniJson.write(JsonValue.Obj(fields), pretty = false)) }
        }

    /**
     * 本地这份数据 / 这个游标属于哪个账号（`users.id`）。0 = 还没同步过任何账号。
     *
     * 用途只有一个：判断「本设备跟这个账号同步过没有」。没同步过就意味着
     * 本地这份数据的来历不明，**没有资格覆盖服务端** —— 见 `SyncEngine.syncOnce`
     * 第 0 步与设计 §5.8「首端权威」。
     *
     * 和游标放同一个文件、同生共死：两者都是「本地同步到哪了」的一部分，
     * 单独存会出现「游标是新的、账号还是旧的」这种自相矛盾的状态。
     */
    var syncedAccountId: Int
        get() = (read().get("accountId") as? JsonValue.Num)?.value?.toInt() ?: 0
        set(value) {
            val fields = LinkedHashMap(read())
            fields["accountId"] = JsonValue.Num(value.toDouble())
            runCatching { file.writeText(MiniJson.write(JsonValue.Obj(fields), pretty = false)) }
        }

    private fun read(): Map<String, JsonValue> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            (MiniJson.parse(file.readText()) as? JsonValue.Obj)?.fields ?: emptyMap()
        }.getOrElse { emptyMap() }
    }
}
