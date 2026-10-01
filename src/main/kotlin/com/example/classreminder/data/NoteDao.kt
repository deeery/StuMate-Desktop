package com.example.classreminder.data

import java.sql.ResultSet

/**
 * 便签的数据访问对象。
 *
 * 方法签名与安卓端的 Room `NoteDao` **完全一致**，`MainViewModel` 零改动。
 */
class NoteDao {

    /** 按显示顺序取全部便签；position 撞车时用创建时间兜底，保证顺序稳定不跳 */
    suspend fun getAll(): List<NoteEntity> = Db.read { c ->
        c.query(
            "SELECT * FROM notes WHERE deletedAt = 0 ORDER BY position ASC, createdAt ASC"
        ) { rs -> rs.readNotes() }
    }

    /** 按主键查单条，避免 updateNote 里跑全表扫描；已软删的不再返回 */
    suspend fun getById(id: Int): NoteEntity? = Db.read { c ->
        c.query("SELECT * FROM notes WHERE id = ? AND deletedAt = 0", id) { rs ->
            rs.readNotes().firstOrNull()
        }
    }

    /** 与 Room 的 `OnConflictStrategy.REPLACE` 等价 */
    suspend fun insert(entity: NoteEntity): Unit = Db.write { c ->
        c.insertNote(entity)
    }

    /** 回撤时把快照整批写回（一个事务内完成） */
    suspend fun insertAll(entities: List<NoteEntity>): Unit = Db.write { c ->
        c.transaction { entities.forEach { c.insertNote(it) } }
    }

    /** 拖动排序后整批写回新的 position */
    suspend fun updateAll(entities: List<NoteEntity>): Unit = Db.write { c ->
        c.transaction { entities.forEach { c.insertNote(it) } }
    }

    /**
     * 软删除：只打 `deletedAt` 标记，不物理删行。
     * 物理删行会让同步把「别的设备早已删掉、本机还没收到通知」的便签又推回来（数据复活）。
     */
    suspend fun deleteById(id: Int): Unit = Db.write { c ->
        val now = System.currentTimeMillis()
        c.exec("UPDATE notes SET deletedAt = ?, updatedAt = ? WHERE id = ?", now, now, id)
    }

    /** 回撤用：先清空再写快照，等价于整表替换 */
    suspend fun deleteAll(): Unit = Db.write { c ->
        c.exec("DELETE FROM notes")
    }
}

private fun java.sql.Connection.insertNote(note: NoteEntity) {
    exec(
        """
        INSERT OR REPLACE INTO notes
            (id, text, position, createdAt, colorIndex, typeIndex, customLabel, deadlineAt,
         uid, updatedAt, deletedAt)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        note.id,
        note.text,
        note.position,
        note.createdAt,
        note.colorIndex,
        note.typeIndex,
        note.customLabel,
        note.deadlineAt,
        note.uid.ifBlank { newUid() },
        System.currentTimeMillis(),
        note.deletedAt
    )
}

internal fun ResultSet.readNotes(): List<NoteEntity> {
    val out = ArrayList<NoteEntity>()
    while (next()) {
        out += NoteEntity(
            id = getInt("id"),
            text = getString("text").orEmpty(),
            position = getInt("position"),
            createdAt = getLong("createdAt"),
            colorIndex = getInt("colorIndex"),
            typeIndex = getInt("typeIndex"),
            customLabel = getString("customLabel").orEmpty(),
            deadlineAt = getLong("deadlineAt"),
            uid = getString("uid").orEmpty(),
            updatedAt = getLong("updatedAt"),
            deletedAt = getLong("deletedAt")
        )
    }
    return out
}
