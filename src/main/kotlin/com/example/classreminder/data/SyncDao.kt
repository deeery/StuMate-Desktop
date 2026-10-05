package com.example.classreminder.data

/**
 * 同步专用的数据访问方法。
 *
 * ## 为什么不加进 [ClassDao] / [NoteDao]
 *
 * 那两个 DAO 的方法签名是**刻意与安卓端 Room DAO 逐字对齐**的，
 * 这样 `MainViewModel` 里的调用点一行都不用改。
 * 同步是桌面端独有的能力（安卓端同步引擎还没写），把它的方法塞进去会破坏这个对齐。
 *
 * 所以另起一个文件，两边各自干净。
 *
 * ## 与业务 DAO 的分工
 *
 * 业务 DAO 只处理「用户可见的」记录（`deletedAt = 0`）；
 * 同步要看到**全部**行 —— 包括已软删的，因为删除本身也要传播。
 */
class SyncDao {

    // ── 读：全量快照（含已软删） ─────────────────────────────────

    /** 课程全量。**含**已软删的行 —— 删除要传播给别的设备 */
    suspend fun allClasses(): List<ClassEntity> = Db.read { c ->
        c.query("SELECT * FROM classes ORDER BY id ASC") { rs -> rs.readClasses() }
    }

    /** 便签全量，同样含已软删 */
    suspend fun allNotes(): List<NoteEntity> = Db.read { c ->
        c.query("SELECT * FROM notes ORDER BY id ASC") { rs -> rs.readNotes() }
    }

    // ── 读：按 uid 查 ────────────────────────────────────────────

    suspend fun classByUid(uid: String): ClassEntity? = Db.read { c ->
        c.query("SELECT * FROM classes WHERE uid = ?", uid) { rs ->
            rs.readClasses().firstOrNull()
        }
    }

    suspend fun noteByUid(uid: String): NoteEntity? = Db.read { c ->
        c.query("SELECT * FROM notes WHERE uid = ?", uid) { rs ->
            rs.readNotes().firstOrNull()
        }
    }

    // ── 写：按 uid 落库（同步专用） ──────────────────────────────

    /**
     * 把服务端来的记录写进本地。
     *
     * ⚠️ 这里**不能**用 [ClassDao.insert]：它会无条件刷新 `updatedAt = now`，
     * 于是「刚从服务端拉下来的记录」会被打上本地当前时间，
     * 下次同步再推上去时又变成「客户端最新」→ 无休止的来回翻转。
     * 同步落库必须**原样保留服务端给的 `updatedAt`**。
     *
     * `id` 的分配：本地主键由应用分配（没有 autoGenerate），而两台设备各自新建
     * 必然撞号。所以同步来的记录若在本机没有对应行，就分配一个**当前未占用**的 id。
     * 撞号不可怕 —— 服务端和其他设备只认 `uid`，`id` 纯粹是本地索引。
     */
    suspend fun upsertClass(entity: ClassEntity): Unit = Db.write { c ->
        c.exec(
            """
            INSERT OR REPLACE INTO classes
                (id, title, dayOfWeek, startTime, endTime, room, notes, teacher, weeks, date,
                 uid, updatedAt, deletedAt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            entity.id, entity.title, entity.dayOfWeek, entity.startTime, entity.endTime,
            entity.room, entity.notes, entity.teacher, entity.weeks, entity.date,
            entity.uid, entity.updatedAt, entity.deletedAt
        )
    }

    suspend fun upsertNote(entity: NoteEntity): Unit = Db.write { c ->
        c.exec(
            """
            INSERT OR REPLACE INTO notes
                (id, title, content, position, createdAt, colorIndex, typeIndex, customLabel,
                 deadlineAt, uid, updatedAt, deletedAt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            entity.id, entity.title, entity.content, entity.position, entity.createdAt,
            entity.colorIndex, entity.typeIndex, entity.customLabel, entity.deadlineAt,
            entity.uid, entity.updatedAt, entity.deletedAt
        )
    }

    // ── 写：清空（首端切换时用） ─────────────────────────────────

    /**
     * 物理清空课程与便签。
     *
     * ⚠️ **只在首端切换（`replace_local = true`）时调用**，且调用方**必须**已经
     * 做过本地备份（设计 v1.3 §5.8 明确要求）——
     * 用户可能在移动端攒了一批只在手机存在的课，直接清空就是丢数据。
     *
     * 这里是物理 `DELETE` 而不是软删：这些行马上会被全量拉取的服务端数据替换掉，
     * 留着旧行只会让下一次同步把它们当成「本地新增」又推回服务端。
     */
    suspend fun purgeLocalData(): Unit = Db.write { c ->
        c.transaction {
            c.exec("DELETE FROM classes")
            c.exec("DELETE FROM notes")
        }
    }

    // ── 维护 ────────────────────────────────────────────────────

    /**
     * 物理清理软删除超过 [retentionDays] 天的行。
     *
     * 软删除让数据不会真正消失（这是同步正确性的前提），
     * 但代价是删除的东西会一直躺在库里。超过保留期就彻底清掉。
     *
     * 保留 30 天而不是服务端的 90 天：客户端空间更紧张，
     * 而且「删了 30 天还想找回」的用户本来就该去用备份文件恢复。
     */
    suspend fun purgeOldTombstones(retentionDays: Int = 30): Int = Db.write { c ->
        val cutoff = System.currentTimeMillis() - retentionDays * 86_400_000L
        val deleted = c.exec(
            "DELETE FROM classes WHERE deletedAt > 0 AND deletedAt < ?", cutoff
        )
        val deletedNotes = c.exec(
            "DELETE FROM notes WHERE deletedAt > 0 AND deletedAt < ?", cutoff
        )
        deleted + deletedNotes
    }

    /** 分配一个当前未占用的 id（同步落库时给新记录用） */
    suspend fun nextFreeClassId(): Int = Db.read { c ->
        c.query("SELECT COALESCE(MAX(id), 0) AS m FROM classes") { rs ->
            if (rs.next()) rs.getInt("m") + 1 else 1
        }
    }

    suspend fun nextFreeNoteId(): Int = Db.read { c ->
        c.query("SELECT COALESCE(MAX(id), 0) AS m FROM notes") { rs ->
            if (rs.next()) rs.getInt("m") + 1 else 1
        }
    }

    /**
     * 同步期间「是否存在」查询走这里，用一条 SQL 而不是两次往返。
     *
     * @return 该 uid 在本机的 id；不存在返回 null
     */
    suspend fun classIdOfUid(uid: String): Int? = Db.read { c ->
        c.query("SELECT id FROM classes WHERE uid = ?", uid) { rs ->
            if (rs.next()) rs.getInt("id") else null
        }
    }

    suspend fun noteIdOfUid(uid: String): Int? = Db.read { c ->
        c.query("SELECT id FROM notes WHERE uid = ?", uid) { rs ->
            if (rs.next()) rs.getInt("id") else null
        }
    }
}
