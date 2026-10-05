package com.example.classreminder.data.sync

import com.example.classreminder.data.ClassEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

    // ── SyncMergeIndex：活索引 ──────────────────────────────────

    /**
     * **本文件最重要的一条测试。**
     *
     * 服务端 `pull` 给的是原始变更流水，同一个 uid 改过几次就有几条
     * （实测某账号 72 个 uid 里 52 个有 2~4 条）。旧代码在循环外取一次
     * uid→行 快照且从不更新，于是批内第二次遇到同一 uid 时查不到刚写进去的那行，
     * 被当成新记录 → 另分配一个 id → **又 INSERT 一行**。
     *
     * 后果就是用户报的「云同步重复保存了数据完全一致的课程」：
     * 一次全量重拉把每条记录写成 2~4 份，几份的可见字段一模一样。
     */
    @Test
    fun `同 uid 在一批里出现两次只占一个 id`() {
        val index = SyncMergeIndex()
        var cursor = 1
        val assigned = ArrayList<Int>()

        // 同一个 uid 的两条修订（服务端流水里就是两条）
        for (i in 0 until 2) {
            val uid = "u-1"
            val known = index.row(uid)
            val d = SyncMerge.resolve(
                localIdOfUid = known?.id,
                localUpdatedAt = known?.updatedAt ?: 0L,
                remoteUpdatedAt = 1000L + i,
                remotePreferredId = 1,       // 两条修订都自带 id=1
                cursor = cursor,
                used = index.ids()
            ) as SyncMerge.Decision.Write
            cursor = d.nextCursor
            // ⚠️ 这一行就是修复本体：写一行就登记一行
            index.record(uid, d.id, 1000L + i)
            assigned += d.id
        }

        assertEquals("同一 uid 必须落在同一个 id 上", listOf(1, 1), assigned)
        assertEquals("库里只该有这一行", setOf(1), index.ids())
        assertEquals("后一条修订的时间要覆盖前一条", 1001L, index.row("u-1")!!.updatedAt)
    }

    /**
     * 上面那条测试的「反向守卫」：证明它抓的确实是真问题。
     *
     * 用旧写法（循环外快照 + 只在循环里往 used 里塞 id）跑同一批数据，
     * 会**真的**多写一行。没有这条测试，上面那条可能只是碰巧通过。
     */
    @Test
    fun `用循环外的旧快照会多写一行（证明上面的测试不是空跑）`() {
        val snapshotIdOfUid = emptyMap<String, Int>()   // 循环外取一次，之后不更新
        val used = mutableSetOf<Int>()
        var cursor = 1
        val assigned = ArrayList<Int>()

        for (i in 0 until 2) {
            val d = SyncMerge.resolve(
                localIdOfUid = snapshotIdOfUid["u-1"],
                localUpdatedAt = 0L,
                remoteUpdatedAt = 1000L + i,
                remotePreferredId = 1,
                cursor = cursor,
                used = used
            ) as SyncMerge.Decision.Write
            cursor = d.nextCursor
            used += d.id
            assigned += d.id
        }

        assertEquals(listOf(1, 2), assigned)
        assertEquals(setOf(1, 2), used)
    }

    @Test
    fun `索引按 uid 覆盖而不是叠加`() {
        val index = SyncMergeIndex()
        index.seed("u-1", 7, 100L)
        index.seed("u-1", 8, 200L)
        assertEquals(8, index.row("u-1")!!.id)
        assertEquals(200L, index.row("u-1")!!.updatedAt)
        // 两个 id 都算被占用 —— 旧的 7 可能还在库里（同 uid 多行的历史脏数据）
        assertEquals(setOf(7, 8), index.ids())
    }

    @Test
    fun `空 uid 的行不进 uid 索引但仍占着 id`() {
        val index = SyncMergeIndex()
        index.seed("", 1, 100L)
        // 空 uid 的行没有身份，不能进 uid 索引 —— 否则所有空 uid 行会互相覆盖
        assertNull(index.row(""))
        // 但它在库里确实占着 id=1，新分配 id 时必须避开它，
        // 否则 upsert 会把这行悄悄覆盖掉
        assertEquals(setOf(1), index.ids())
    }

    // ── 内容指纹 ────────────────────────────────────────────────

    private fun cls(
        id: Int = 1,
        title: String = "高等数学",
        dayOfWeek: String = "Monday",
        startTime: String = "08:00",
        endTime: String = "09:35",
        room: String = "教二 305",
        notes: String = "",
        teacher: String = "",
        weeks: String = "1-16周",
        date: String = "",
        uid: String = "u-1",
        updatedAt: Long = 0L,
        deletedAt: Long = 0L
    ) = ClassEntity(
        id, title, dayOfWeek, startTime, endTime, room, notes, teacher, weeks, date,
        uid, updatedAt, deletedAt
    )

    /**
     * 指纹**不能**把同步元数据算进去。
     *
     * 两台设备各自新建的同一门课，uid / id / updatedAt 必然不同 ——
     * 要是这些参与了指纹，重复就永远认不出来，去重等于没做。
     */
    @Test
    fun `内容指纹不受 uid id 与时间戳影响`() {
        val a = cls(id = 1, uid = "aaa", updatedAt = 0L, deletedAt = 0L)
        val b = cls(id = 999, uid = "zzz", updatedAt = 1791023372600L, deletedAt = 0L)
        assertEquals(SyncMerge.courseKey(a), SyncMerge.courseKey(b))
    }

    @Test
    fun `内容指纹认得出一字段之差`() {
        val base = cls()
        val fields = listOf(
            base.copy(title = "大学英语"),
            base.copy(dayOfWeek = "Tuesday"),
            base.copy(startTime = "09:50"),
            base.copy(endTime = "12:15"),
            base.copy(room = "A101"),
            base.copy(notes = "带教材"),
            base.copy(teacher = "张老师"),
            base.copy(weeks = "第6周"),
            base.copy(date = "2026-10-05")
        )
        fields.forEach { other ->
            assertNotEquals(SyncMerge.courseKey(base), SyncMerge.courseKey(other))
        }
    }

    /**
     * 拼接必须用长度前缀。
     *
     * 直接拿 `"|"` 拼的话 `("ab","c")` 与 `("a","bc")` 会撞成同一个串，
     * 两门**不同**的课就被当成同一门，去重会把其中一门删掉。
     */
    @Test
    fun `内容指纹区分字段切分位置`() {
        val a = cls(title = "ab", room = "c")
        val b = cls(title = "a", room = "bc")
        assertNotEquals(SyncMerge.courseKey(a), SyncMerge.courseKey(b))
    }

    // ── 谁活下来 ────────────────────────────────────────────────

    /**
     * 选举必须**确定性**：两台设备对同一对 uid 必须算出同一个赢家。
     *
     * 若各留各的本地那条，双方都会把对方删掉 → 这门课彻底消失。
     */
    @Test
    fun `两台设备对同一对 uid 选出同一个赢家`() {
        val uid1 = "0f3a"
        val uid2 = "b7c1"
        // A 机本地是 uid1、收到 uid2；B 机本地是 uid2、收到 uid1
        assertEquals(uid1, SyncMerge.electSurvivor(uid1, uid2))
        assertEquals(uid1, SyncMerge.electSurvivor(uid2, uid1))
    }

    @Test
    fun `本机没有内容一致的活行时不算重复`() {
        assertEquals(
            SyncMerge.TwinPlan.None,
            SyncMerge.planTwin(remoteUid = "aaa", remoteAlive = true, localAliveUid = null)
        )
    }

    @Test
    fun `墓碑不参与去重`() {
        // 远端是删除 → 删除必须原样传播，不能被「内容一样」吞掉
        assertEquals(
            SyncMerge.TwinPlan.None,
            SyncMerge.planTwin(remoteUid = "aaa", remoteAlive = false, localAliveUid = "bbb")
        )
    }

    @Test
    fun `同 uid 走正常覆盖路径不算重复`() {
        assertEquals(
            SyncMerge.TwinPlan.None,
            SyncMerge.planTwin(remoteUid = "aaa", remoteAlive = true, localAliveUid = "aaa")
        )
    }

    @Test
    fun `远端 uid 更小时本机改挂远端 uid`() {
        val plan = SyncMerge.planTwin(
            remoteUid = "aaa", remoteAlive = true, localAliveUid = "bbb"
        )
        assertEquals(SyncMerge.TwinPlan.AdoptRemote(localUid = "bbb"), plan)
    }

    @Test
    fun `远端 uid 更大时给远端写墓碑`() {
        assertEquals(
            SyncMerge.TwinPlan.TombstoneRemote,
            SyncMerge.planTwin(remoteUid = "bbb", remoteAlive = true, localAliveUid = "aaa")
        )
    }
}
