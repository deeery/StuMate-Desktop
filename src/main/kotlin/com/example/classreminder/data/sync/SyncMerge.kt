package com.example.classreminder.data.sync

import com.example.classreminder.data.ClassEntity
import com.example.classreminder.data.backup.JsonValue
import com.example.classreminder.data.backup.long

/**
 * 同步合并的**纯逻辑**判定。
 *
 * ## 为什么要单独抽出来
 *
 * 「这条远端记录要不要覆盖本地？」「它该拿哪个 id？」是整个同步里最容易出错、
 * 也最难靠肉眼验证的两步：
 *  - 判错覆盖 → 数据在设备间来回翻转，或者改动被无声吞掉
 *  - 判错 id → UI 里冒出重复记录，或者把别的记录覆盖掉
 *
 * 把它们做成不依赖数据库、不依赖网络的纯函数，就能用单测把所有边界情况穷举出来
 * （见 `src/test/.../sync/SyncMergeTest.kt`）。
 */
internal object SyncMerge {

    /**
     * 远端记录要不要覆盖本地同 uid 的那一条。
     *
     * @param localUpdatedAt 本地那条的 `updatedAt`（0 = 老数据）
     * @param remoteUpdatedAt 远端那条的 `updatedAt`（0 = 缺字段，当最旧处理）
     * @return true = 覆盖本地
     *
     * ## 为什么是 `>` 而不是 `>=`
     *
     * 相等时**保留本地**。场景：本地刚从服务端 pull 过一条（updatedAt 原样保留），
     * 下次同步又拉到同一条 —— 若用 `>=` 就会覆盖一次，
     * 虽然值一样看不出问题，但会平白多一次写库，还会让 `applied` 计数虚高，
     * 用户看到「拉取 5 条」其实一条都没变。
     */
    fun shouldOverwrite(localUpdatedAt: Long, remoteUpdatedAt: Long): Boolean =
        remoteUpdatedAt > localUpdatedAt

    /**
     * 给一条外来记录挑一个本机没在用的 id。
     *
     * @param preferred 记录自带的 id（另一台设备分配的，本机可能没占用）
     * @param cursor    本机「下一个可用 id」的游标（通常是 `MAX(id) + 1`）
     * @param used      本机已被占用的 id 集合
     *
     * ## 策略：先试自带 id，撞了再顺着游标找空位
     *
     * 两端各自新建记录时 id **可能**恰好不撞（比如这台是 1,2,3，那台是 4,5,6），
     * 这时沿用自带 id 最符合直觉 —— 两边 UI 里的排序位置一致。
     *
     * 撞号不可怕：服务端与其他设备**只认 `uid`**，`id` 纯粹是本地索引，
     * 顶多让这条记录在本地的排序位置和另一台不同。
     *
     * @return 保证不在 [used] 里的 id
     */
    fun pickId(preferred: Int, cursor: Int, used: Set<Int>): Int {
        if (preferred > 0 && preferred !in used) return preferred
        var candidate = maxOf(cursor, 1)
        while (candidate in used) candidate++
        return candidate
    }

    /**
     * 决定一条远端记录落库后的最终形态：是否要改 id，以及最终 id 是多少。
     *
     * 把「查 uid → 比时间 → 选 id」三步合成一次判定，
     * 调用方就不会漏掉其中任何一步（漏掉「比时间」就是无限翻转的来源）。
     */
    fun resolve(
        localIdOfUid: Int?,
        localUpdatedAt: Long,
        remoteUpdatedAt: Long,
        remotePreferredId: Int,
        cursor: Int,
        used: Set<Int>
    ): Decision {
        // 本机有这条，且远端不比本地新 → 什么都不做
        if (localIdOfUid != null && !shouldOverwrite(localUpdatedAt, remoteUpdatedAt)) {
            return Decision.Skip
        }
        val id = localIdOfUid
            ?: pickId(remotePreferredId, cursor, used)
        return Decision.Write(id = id, nextCursor = maxOf(cursor, id + 1))
    }

    sealed interface Decision {
        /** 不动本地 */
        object Skip : Decision

        /** 写入，id 固定用这个值 */
        data class Write(val id: Int, val nextCursor: Int) : Decision
    }

    // ── 跨 uid 的「同一门课」 ────────────────────────────────────

    /**
     * 课程的内容指纹：**只取用户看得见的字段**。
     *
     * ## 为什么需要它
     *
     * 两台设备各自新建同一门课时会各自生成一个 uid。服务端只认 uid，
     * 于是同一门课在服务端变成两条记录；任何一台设备拉下来都会看到两份 ——
     * 这正是「云同步重复保存了数据完全一致的课程」的来源。
     *
     * `uid` / `id` / `updatedAt` / `deletedAt` **都不参与指纹**：
     * 它们要么是各机自造的，要么是同步过程写的，跟「是不是同一门课」无关。
     *
     * ## 为什么用「长度前缀」而不是直接拼分隔符
     *
     * `("ab", "c")` 与 `("a", "bc")` 用 `"|"` 拼出来是同一个串，
     * 两门不同的课就会被误判成同一门。长度前缀让拼接结果与字段切分一一对应。
     */
    fun courseKey(e: ClassEntity): String = key(
        e.title, e.dayOfWeek, e.startTime, e.endTime, e.room,
        e.notes, e.teacher, e.weeks, e.date
    )

    /** 长度前缀拼接；`null` 与空串不同（这里不出现 null，但保持语义明确） */
    private fun key(vararg parts: String): String = buildString {
        for (p in parts) {
            append(p.length).append(':').append(p).append('|')
        }
    }

    /**
     * 两条内容一致的记录里谁活下来。
     *
     * ## 为什么必须**确定性**
     *
     * 如果各机都保留「自己本地那条」，A 机留下 uid1、B 机留下 uid2，
     * 两边都把对方那条删掉 → 双方互删 → **这门课彻底消失**。
     * 取 uid 字典序较小者，两台设备算出的是同一个答案，
     * 于是败者恒为同一个 uid，删除方向唯一，收敛到同一条。
     */
    fun electSurvivor(uidA: String, uidB: String): String =
        if (uidA <= uidB) uidA else uidB

    /**
     * 远端来的一条记录和本地某条内容一致时该怎么办。
     *
     * 抽成纯函数是为了让「谁留谁删」这个最危险的判定能被单测穷举 ——
     * 判错的方向会让数据在设备间互删或者永远去不掉重复。
     */
    fun planTwin(
        remoteUid: String,
        remoteAlive: Boolean,
        localAliveUid: String?
    ): TwinPlan {
        // 墓碑不参与去重：删除本来就要原样传播
        if (!remoteAlive) return TwinPlan.None
        // 本地没有内容一致的活行 → 不是重复
        if (localAliveUid == null) return TwinPlan.None
        // 同一条（同 uid）→ 走正常的 LWW 路径
        if (localAliveUid == remoteUid) return TwinPlan.None
        return if (electSurvivor(remoteUid, localAliveUid) == remoteUid) {
            TwinPlan.AdoptRemote(localUid = localAliveUid)
        } else {
            TwinPlan.TombstoneRemote
        }
    }

    sealed interface TwinPlan {
        /** 不是重复，照常处理 */
        object None : TwinPlan

        /** 远端 uid 胜出：本地那条改挂远端 uid，旧 uid 写墓碑 */
        data class AdoptRemote(val localUid: String) : TwinPlan

        /** 本地 uid 胜出：远端 uid 写墓碑，不插入新行 */
        object TombstoneRemote : TwinPlan
    }
}

/**
 * 「本机 uid → 行的 id 与 updatedAt」的**活索引**。
 *
 * ## 为什么不能只用循环外的一次快照
 *
 * `applyRemote` 原来在循环外取一次 `allClasses().associateBy { it.uid }`，
 * 循环里**从不更新**。可服务端 `pull` 返回的是**原始变更流水** ——
 * 同一个 uid 改过几次就有几条（实测某账号 72 个 uid 里 52 个有 2~4 条）。
 * 于是一批里第二次遇到同一个 uid 时，快照里查不到它刚写进去的那一行，
 * 就被当成「本机没有这条」→ `pickId` 另分配一个 id → `INSERT` 出**第二行**。
 *
 * 后果：任何一次全量重拉（首次同步该账号、`syncAfterLogin`、首端切换）
 * 都会把每条记录写成 2~4 份，且几份的可见字段完全一样 ——
 * 用户看到的就是「课表里多了一堆一模一样的课」。
 *
 * 索引必须**随写随更新**，这是本类存在的唯一理由。
 */
internal class SyncMergeIndex {

    /** 一行的定位信息。`updatedAt` 一起存是为了让批内后续变更能正确比时间 */
    data class Row(val id: Int, val updatedAt: Long)

    private val byUid = HashMap<String, Row>()
    private val usedIds = HashSet<Int>()

    /** 用库里已有的行初始化 */
    fun seed(uid: String, id: Int, updatedAt: Long) {
        if (uid.isNotBlank()) byUid[uid] = Row(id, updatedAt)
        usedIds += id
    }

    fun row(uid: String): Row? = byUid[uid]

    /** 已被占用的 id 集合；直接交给 [SyncMerge.pickId] 用 */
    fun ids(): Set<Int> = usedIds

    /** 刚落库一行后立刻登记 —— 漏掉这一步就退回成「快照不更新」的老 bug */
    fun record(uid: String, id: Int, updatedAt: Long) {
        if (uid.isNotBlank()) byUid[uid] = Row(id, updatedAt)
        usedIds += id
    }
}

/** 从远端 JSON 里取 `updatedAt` / `deletedAt`，缺字段按 0（最旧、未删）处理 */
internal fun JsonValue.Obj.syncUpdatedAt(): Long = long("updatedAt")

internal fun JsonValue.Obj.syncDeletedAt(): Long = long("deletedAt")
