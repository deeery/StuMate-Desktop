package com.example.classreminder.data.sync

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
}

/** 从远端 JSON 里取 `updatedAt` / `deletedAt`，缺字段按 0（最旧、未删）处理 */
internal fun JsonValue.Obj.syncUpdatedAt(): Long = long("updatedAt")

internal fun JsonValue.Obj.syncDeletedAt(): Long = long("deletedAt")
