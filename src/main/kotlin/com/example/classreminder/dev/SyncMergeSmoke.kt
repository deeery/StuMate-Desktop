package com.example.classreminder.dev

import com.example.classreminder.AppPaths
import com.example.classreminder.data.ClassEntity
import com.example.classreminder.data.NoteEntity
import com.example.classreminder.data.SyncDao
import com.example.classreminder.data.backup.JsonValue
import com.example.classreminder.data.backup.MiniJson
import com.example.classreminder.data.sync.RemoteChange
import com.example.classreminder.data.sync.SyncEngine
import com.example.classreminder.data.sync.SyncPrefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 「云同步把同一门课存成好几份」这个 bug 的**离线端到端冒烟**。
 *
 * ## 为什么不去连真实服务端
 *
 * 这个 bug 的根因在**客户端** `SyncEngine.applyRemote`：
 *  - 循环外取一次 `associateBy { uid }`，循环里从不更新 →
 *    服务端把同一 uid 的多个修订都发下来时，第二条被当成新记录又 INSERT 一行；
 *  - 两台设备各自建的同一门课 uid 不同 → 服务端存两条，谁拉下来都看到两份。
 *
 * 两件事都只跟「喂进来的一批变更」和「本地库里原本有什么」有关，跟网络无关。
 * 所以这里**构造**出一批变更（含同 uid 多修订、含跨 uid 双胞胎）直接喂给
 * `applyRemote`，再逐条断言落库结果 —— 比连服务端更精确，也能反复重跑。
 *
 * ## 库落在哪
 *
 * 走 `AppPaths.dataDir`（`%APPDATA%\StuMate\`），所以 Gradle 任务
 * `syncMergeSmoke` 会把 **APPDATA 指到一个临时目录**，
 * **绝不碰用户正在用的那份库**。见 build.gradle.kts 里那个任务的注释。
 *
 * 跑法：`./gradlew syncMergeSmoke`
 */
fun main() = runBlocking {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val dao = SyncDao()
    val engine = SyncEngine(scope, dao, SyncPrefs(File(AppPaths.dataDir, "sync.json")))

    var passed = 0
    var failed = 0
    fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) {
            passed++
            println("  √ $name")
        } else {
            failed++
            println("  × $name${if (detail.isNotBlank()) "  → $detail" else ""}")
        }
    }

    println("═".repeat(64))
    println(" StuMate 同步重复课程 —— 离线端到端冒烟")
    println(" 数据目录：${AppPaths.dataDir.absolutePath}")
    println("═".repeat(64))
    println(" （本脚本不会联网，也不会碰 %APPDATA%\\StuMate 里你正在用的那份库）")

    // 确保库文件与表结构就位（第一次访问会走 Db.migrate 建表）
    check("空库可用（表结构建好）", dao.allClasses().isEmpty(), "临时库一开始就该是空的")

    // ══ 场景 A：同一个 uid 在一批里出现三次（服务端原始变更流水） ═══════
    //
    // 这是 bug 1 的**直接复现**。服务端 `pull` 给的是 `sync_log` 原始流水，
    // 同一个 uid 改过几次就有几条（实测某账号 72 个 uid 里 52 个有 2~4 条）。
    // 旧实现循环里用循环外的旧快照查 uid，第二条查不到刚写进去的行 →
    // 当成新记录 → pickId 另分配 id → INSERT 出第二、第三行。
    println("\n【A. 同一 uid 三个修订 → 只该落一行】")
    val uidA = "aaaaaaaa-0000-4000-8000-000000000001"
    val batchA = listOf(
        change(uidA, "线性代数", 1_000L),
        change(uidA, "线性代数", 2_000L),
        change(uidA, "线性代数", 3_000L),
    )
    val appliedA = engine.applyRemote(batchA)
    val rowsA = dao.allClasses().filter { it.uid == uidA }
    check("同 uid 只有一行", rowsA.size == 1, "实际 ${rowsA.size} 行 —— 旧实现会写 3 行")
    check("留下的是最新的那一版", rowsA.firstOrNull()?.updatedAt == 3_000L, "实际 ${rowsA.firstOrNull()?.updatedAt}")
    // ⚠️ `applied` 数的是「写了几次」，不是「新增了几行」——
    // 同一个 uid 的三个修订会**覆盖同一行**，所以这里本来就是 3。
    // 真正的判别式是上面那条「只有一行」（旧实现这里会是 3 行）。
    check("三个修订都覆盖在同一行上（id 没变）", rowsA.firstOrNull()?.id == 1, "实际 id=${rowsA.firstOrNull()?.id}")
    check("applyRemote 报了 3 次写入（3 个修订各写一次）", appliedA == 3, "实际 $appliedA")
    check("整库只有这一行课程（没被写成多行）", dao.allClasses().size == 1, "实际 ${dao.allClasses().size} 行")
    check("库里没有任何 uid 出现两次", noDuplicateUid(dao.allClasses()))

    // ══ 场景 B：远端 uid 更大 → 本地那条活，远端写墓碑 ══════════════════
    //
    // 两台设备各自新建同一门课 → 两个 uid。赢家取 uid 字典序较小者
    // （`SyncMerge.electSurvivor`），这样两台设备算出同一个答案，
    // 不会变成「你删我、我删你」把课删没。
    println("\n【B. 跨 uid 双胞胎（远端 uid 更大）→ 留一条 + 给远端写墓碑】")
    val twinLow = "11111111-0000-4000-8000-000000000001"
    val twinHigh = "99999999-0000-4000-8000-000000000002"
    val appliedB = engine.applyRemote(
        listOf(change(twinLow, "大数据基础", 5_000L), change(twinHigh, "大数据基础", 6_000L))
    )
    val aliveB = dao.allClasses().filter { it.deletedAt == 0L && it.title == "大数据基础" }
    val tombB = dao.allClasses().filter { it.deletedAt != 0L && it.title == "大数据基础" }
    check("这门课只剩一条活行", aliveB.size == 1, "实际 ${aliveB.size} 行")
    check("活下来的是 uid 较小的那个", aliveB.firstOrNull()?.uid == twinLow, "实际 ${aliveB.firstOrNull()?.uid}")
    check("败者留下一条墓碑（删除要传播出去）", tombB.size == 1 && tombB.first().uid == twinHigh, tombB.toString())
    check("墓碑的 deletedAt 非 0", tombB.firstOrNull()?.deletedAt?.let { it > 0L } == true)
    check("applyRemote 记了两条落库（一活一碑）", appliedB == 2, "实际 $appliedB")
    check("库里没有任何 uid 出现两次", noDuplicateUid(dao.allClasses()))

    // ══ 场景 C：远端 uid 更小 → 本地那条改挂远端 uid ════════════════════
    //
    // 方向反过来：本地已有 uid=zzz（字典序大），远端来的 uid=aaa 更小 → 远端胜。
    // 留下的那行**换 uid 但保留 id**（界面里的位置不跳），旧 uid 写墓碑。
    println("\n【C. 跨 uid 双胞胎（远端 uid 更小）→ 本机改挂远端 uid】")
    val localBig = "zzzzzzzz-0000-4000-8000-000000000001"
    val remoteSmall = "00000000-0000-4000-8000-000000000002"
    // 先把「本机那份」放进库
    dao.upsertClass(
        cls(id = 900, uid = localBig, title = "数据结构实验", updatedAt = 7_000L)
    )
    val appliedC = engine.applyRemote(listOf(change(remoteSmall, "数据结构实验", 8_000L)))
    val aliveC = dao.allClasses().filter { it.deletedAt == 0L && it.title == "数据结构实验" }
    val tombC = dao.allClasses().filter { it.deletedAt != 0L && it.title == "数据结构实验" }
    check("这门课只剩一条活行", aliveC.size == 1, "实际 ${aliveC.size} 行")
    check("活行改挂到较小的远端 uid", aliveC.firstOrNull()?.uid == remoteSmall, "实际 ${aliveC.firstOrNull()?.uid}")
    check("活行保留了原来的 id（界面位置不跳）", aliveC.firstOrNull()?.id == 900, "实际 ${aliveC.firstOrNull()?.id}")
    check("旧 uid 留下墓碑", tombC.size == 1 && tombC.first().uid == localBig, tombC.toString())
    check("applyRemote 记了两条落库", appliedC == 2, "实际 $appliedC")
    check("库里没有任何 uid 出现两次", noDuplicateUid(dao.allClasses()))

    // ══ 场景 D：清理库里**已经存在**的同 uid 重复行 ═════════════════════
    //
    // 上面三个场景修的是「以后不再写重复」。但用户库里**现在**就躺着旧版写出来的
    // 重复行，得有一趟把它们收敛掉 —— 这就是 `collapseSameUidDuplicates`。
    // 这里手工塞三行同 uid 的脏数据（不同 id，绕过 INSERT OR REPLACE 的主键去重）。
    println("\n【D. 历史脏数据：同一 uid 三行 → 收敛成一行】")
    val dirtyUid = "dddddddd-0000-4000-8000-000000000003"
    dao.upsertClass(cls(id = 901, uid = dirtyUid, title = "形势与政策(4)", updatedAt = 100L))
    dao.upsertClass(cls(id = 902, uid = dirtyUid, title = "形势与政策(4)", updatedAt = 300L))
    dao.upsertClass(cls(id = 903, uid = dirtyUid, title = "形势与政策(4)", updatedAt = 200L))
    val beforeD = dao.allClasses().count { it.uid == dirtyUid }
    check("塞进去了三行（前提成立）", beforeD == 3, "实际 $beforeD")
    val collapsedD = engine.collapseSameUidDuplicates()
    val afterD = dao.allClasses().filter { it.uid == dirtyUid }
    check("收敛后只剩一行", afterD.size == 1, "实际 ${afterD.size}")
    check("留下的是 updatedAt 最大的那行", afterD.firstOrNull()?.id == 902, "实际 id=${afterD.firstOrNull()?.id}")
    check("collapse 报出删了 2 行", collapsedD == 2, "实际 $collapsedD")
    check("库里没有任何 uid 出现两次", noDuplicateUid(dao.allClasses()))

    // ══ 场景 E：墓碑不参与去重 ══════════════════════════════════════════
    //
    // 删除本来就要原样传播。若把墓碑也当成「内容一致的重复」去合并，
    // 删除会被吃掉 —— 用户在某台设备删掉的课会「复活」。
    println("\n【E. 墓碑不参与去重】")
    val aliveE = "eeeeeeee-0000-4000-8000-000000000001"
    val deadE = "eeeeeeee-0000-4000-8000-000000000002"
    engine.applyRemote(listOf(change(aliveE, "大学英语", 10_000L)))
    engine.applyRemote(listOf(change(deadE, "大学英语", 11_000L, deletedAt = 11_000L)))
    val aliveERows = dao.allClasses().filter { it.deletedAt == 0L && it.title == "大学英语" }
    val deadERows = dao.allClasses().filter { it.deletedAt != 0L && it.title == "大学英语" }
    check("活行仍在（没被墓碑误合并）", aliveERows.size == 1, "实际 ${aliveERows.size}")
    check("墓碑原样落库（uid 没被改写）", deadERows.size == 1 && deadERows.first().uid == deadE, deadERows.toString())

    // ══ 场景 F：便签不做内容去重，但同 uid 多行照样收敛 ═════════════════
    println("\n【F. 便签：不做内容去重，同 uid 多行仍收敛】")
    val noteA = "ffffffff-0000-4000-8000-000000000001"
    val noteB = "ffffffff-0000-4000-8000-000000000002"
    engine.applyRemote(
        listOf(
            noteChange(noteA, "买书", 1_000L),
            noteChange(noteB, "买书", 2_000L),   // 内容一致但 uid 不同 → 两条都该留下
        )
    )
    val notesAlive = dao.allNotes().filter { it.deletedAt == 0L && it.title == "买书" }
    check("内容一致的便签不合并（两条都留）", notesAlive.size == 2, "实际 ${notesAlive.size}")
    dao.upsertNote(note(801, noteA, "买书", 100L))
    dao.upsertNote(note(802, noteA, "买书", 200L))
    val notesBefore = dao.allNotes().count { it.uid == noteA }
    val collapsedNotes = engine.collapseSameUidDuplicates()
    check("塞了两行同 uid 的便签（前提成立）", notesBefore == 3, "实际 $notesBefore")
    check("便签同 uid 也收敛到一行", dao.allNotes().count { it.uid == noteA } == 1, "实际 ${dao.allNotes().count { it.uid == noteA }}")
    check("collapse 报出删了 2 行便签", collapsedNotes == 2, "实际 $collapsedNotes")

    // ══ 收尾：全局不变量 ════════════════════════════════════════════════
    println("\n【G. 收尾不变量】")
    val allClasses = dao.allClasses()
    check("全库没有任何 uid 出现两次", noDuplicateUid(allClasses))
    check("全库没有任何 uid 为空", allClasses.none { it.uid.isBlank() }, "空 uid 的行无法参与同步")
    val aliveTitles = allClasses.filter { it.deletedAt == 0L }.map { it.title }.groupingBy { it }.eachCount()
    val stillDup = aliveTitles.filterValues { it > 1 }
    check("活行里没有「同名多份」", stillDup.isEmpty(), "仍然重复：$stillDup")

    println("\n" + "═".repeat(64))
    println(" 通过 $passed / 失败 $failed / 合计 ${passed + failed}")
    println("═".repeat(64))
    if (failed > 0) throw AssertionError("有 $failed 条用例失败")
}

// ── 夹具 ────────────────────────────────────────────────────────────────

/** 任意 uid 在活行里出现两次就算脏 —— 这是本轮修复的核心不变量 */
private fun noDuplicateUid(rows: List<ClassEntity>): Boolean =
    rows.filter { it.uid.isNotBlank() }.groupingBy { it.uid }.eachCount().none { it.value > 1 }

private fun cls(
    id: Int,
    uid: String,
    title: String,
    updatedAt: Long,
    deletedAt: Long = 0L
): ClassEntity = ClassEntity(
    id = id,
    title = title,
    dayOfWeek = "Monday",
    startTime = "08:00",
    endTime = "09:35",
    room = "教二 305",
    notes = "",
    teacher = "王海燕",
    weeks = "1-16周",
    date = "",
    uid = uid,
    updatedAt = updatedAt,
    deletedAt = deletedAt
)

private fun note(id: Int, uid: String, title: String, updatedAt: Long): NoteEntity = NoteEntity(
    id = id,
    title = title,
    content = "",
    position = 0,
    createdAt = 0L,
    colorIndex = 0,
    typeIndex = 0,
    customLabel = "",
    deadlineAt = 0L,
    uid = uid,
    updatedAt = updatedAt,
    deletedAt = 0L
)

/** 造一条课程变更（`data` 的字段名与大小写必须与服务端下发的完全一致） */
private fun change(uid: String, title: String, updatedAt: Long, deletedAt: Long = 0L): RemoteChange =
    RemoteChange(
        entity = "class",
        uid = uid,
        op = if (deletedAt == 0L) "upsert" else "delete",
        data = MiniJson.parse(
            """{"id":1,"title":"$title","dayOfWeek":"Monday","startTime":"08:00",
               "endTime":"09:35","room":"教二 305","notes":"","teacher":"王海燕",
               "weeks":"1-16周","date":"","uid":"$uid",
               "updatedAt":$updatedAt,"deletedAt":$deletedAt}"""
        ) as JsonValue.Obj,
        version = 1
    )

private fun noteChange(uid: String, title: String, updatedAt: Long): RemoteChange =
    RemoteChange(
        entity = "note",
        uid = uid,
        op = "upsert",
        data = MiniJson.parse(
            """{"id":1,"title":"$title","content":"","position":0,"createdAt":0,
               "colorIndex":0,"typeIndex":0,"customLabel":"","deadlineAt":0,
               "uid":"$uid","updatedAt":$updatedAt,"deletedAt":0}"""
        ) as JsonValue.Obj,
        version = 1
    )
