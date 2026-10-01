package com.example.classreminder.data.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 同步合并的判定逻辑。
 *
 * 这两个判定（要不要覆盖、给什么 id）是同步里出错代价最高的地方：
 * 判错覆盖会让数据在设备间无限翻转，判错 id 会让 UI 冒出重复记录。
 */
class SyncMergeTest {

    // ── shouldOverwrite ─────────────────────────────────────────

    @Test
    fun `远端比本地新才覆盖`() {
        assertTrue(SyncMerge.shouldOverwrite(localUpdatedAt = 1000L, remoteUpdatedAt = 2000L))
    }

    @Test
    fun `远端不比本地新时不覆盖`() {
        assertFalse(SyncMerge.shouldOverwrite(localUpdatedAt = 2000L, remoteUpdatedAt = 1000L))
    }

    /**
     * 相等时**不**覆盖。
     *
     * 场景：本地刚从服务端 pull 过一条（updatedAt 原样保留），
     * 下次同步又拉到同一条。若用 `>=` 会白白覆盖一次 —— 值没变，
     * 但 `applied` 计数虚高，用户会看到「拉取 5 条」其实一条都没变。
     */
    @Test
    fun `时间相等时不覆盖`() {
        assertFalse(SyncMerge.shouldOverwrite(localUpdatedAt = 1000L, remoteUpdatedAt = 1000L))
    }

    /** 老数据 updatedAt = 0，服务端那份只要有真实时间戳就该覆盖 */
    @Test
    fun `本地是老数据时远端覆盖`() {
        assertTrue(SyncMerge.shouldOverwrite(localUpdatedAt = 0L, remoteUpdatedAt = 1L))
    }

    /** 两边都是 0（远端缺字段）→ 不覆盖，保留本地已有的 */
    @Test
    fun `两边都是零时不覆盖`() {
        assertFalse(SyncMerge.shouldOverwrite(localUpdatedAt = 0L, remoteUpdatedAt = 0L))
    }

    // ── pickId ──────────────────────────────────────────────────

    @Test
    fun `自带id没被占用就直接用`() {
        assertEquals(7, SyncMerge.pickId(preferred = 7, cursor = 10, used = setOf(1, 2, 3)))
    }

    @Test
    fun `自带id撞了就从游标往后找`() {
        assertEquals(10, SyncMerge.pickId(preferred = 2, cursor = 10, used = setOf(1, 2, 3)))
    }

    @Test
    fun `游标位置也被占用则继续往后找`() {
        assertEquals(11, SyncMerge.pickId(preferred = 10, cursor = 10, used = setOf(10, 1)))
    }

    /** 空表时优先用自带 id —— 两端各自从 1 开始，恰好不撞，保留它最符合直觉 */
    @Test
    fun `空表时沿用自带id`() {
        assertEquals(1, SyncMerge.pickId(preferred = 1, cursor = 1, used = emptySet()))
    }

    /** 自带 id 非正数（脏数据）→ 走游标 */
    @Test
    fun `自带id非法时用游标`() {
        assertEquals(5, SyncMerge.pickId(preferred = 0, cursor = 5, used = setOf(1)))
        assertEquals(5, SyncMerge.pickId(preferred = -3, cursor = 5, used = setOf(1)))
    }

    /** 游标比 0 小（空表 MAX(id)+1 不该出现，但防御一下）→ 从 1 起 */
    @Test
    fun `游标非法时从一开始找`() {
        assertEquals(1, SyncMerge.pickId(preferred = 0, cursor = 0, used = emptySet()))
        assertEquals(1, SyncMerge.pickId(preferred = -1, cursor = -5, used = emptySet()))
    }

    // ── resolve ─────────────────────────────────────────────────

    @Test
    fun `本机已有且远端更新则沿用原id`() {
        val d = SyncMerge.resolve(
            localIdOfUid = 3,
            localUpdatedAt = 1000L,
            remoteUpdatedAt = 2000L,
            remotePreferredId = 99,
            cursor = 50,
            used = setOf(3)
        )
        // id 必须是 3 —— 换成 99 会让 UI 里这条记录「跳位置」，
        // 甚至覆盖掉本机 id=99 的另一条课
        assertEquals(SyncMerge.Decision.Write(3, 50), d)
    }

    @Test
    fun `本机已有但远端更旧则跳过`() {
        val d = SyncMerge.resolve(
            localIdOfUid = 3,
            localUpdatedAt = 5000L,
            remoteUpdatedAt = 2000L,
            remotePreferredId = 99,
            cursor = 50,
            used = setOf(3)
        )
        assertEquals(SyncMerge.Decision.Skip, d)
    }

    @Test
    fun `本机没有这条则分配不冲突的id`() {
        val d = SyncMerge.resolve(
            localIdOfUid = null,
            localUpdatedAt = 0L,
            remoteUpdatedAt = 2000L,
            remotePreferredId = 2,
            cursor = 10,
            used = setOf(1, 2, 3)
        ) as SyncMerge.Decision.Write
        assertEquals(10, d.id)
        assertEquals(11, d.nextCursor)
    }

    /**
     * 关键回归：连续拉同一条远端记录两次，游标必须递增。
     *
     * 若游标不前进，第二条新记录会被分到与第一条相同的 id →
     * `INSERT OR REPLACE` 把刚落的记录覆盖掉 → **静默丢数据**。
     */
    @Test
    fun `连续分配不会给出重复id`() {
        val used = mutableSetOf<Int>()
        var cursor = 1

        repeat(3) {
            val d = SyncMerge.resolve(
                localIdOfUid = null,
                localUpdatedAt = 0L,
                remoteUpdatedAt = 1000L,
                remotePreferredId = 1,   // 三条记录都自带 id=1（另一台设备撞号了）
                cursor = cursor,
                used = used
            ) as SyncMerge.Decision.Write
            assertTrue("第 $it 次分配出的 id 必须未被占用", d.id !in used)
            used += d.id
            cursor = d.nextCursor
        }
        assertEquals(setOf(1, 2, 3), used)
    }
}
