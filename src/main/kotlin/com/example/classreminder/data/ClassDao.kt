package com.example.classreminder.data

import java.sql.ResultSet

/**
 * 课程表的数据访问对象。
 *
 * 方法签名与安卓端的 Room `ClassDao` **完全一致**（含 suspend 修饰），
 * 所以 `MainViewModel` 里所有 `dao.xxx()` 调用点一行都不用改。
 */
class ClassDao {

    /** 与 Room 一致：按星期（字符串字典序恰好 Monday<Tuesday<…）再按开始时间升序 */
    suspend fun getAll(): List<ClassEntity> = Db.read { c ->
        c.query(
            "SELECT * FROM classes WHERE deletedAt = 0 ORDER BY dayOfWeek, startTime"
        ) { rs -> rs.readClasses() }
    }

    /** 与 Room 的 `OnConflictStrategy.REPLACE` 等价；落库时补 uid、刷新 updatedAt */
    suspend fun insert(entity: ClassEntity): Unit = Db.write { c ->
        c.exec(
            """
            INSERT OR REPLACE INTO classes
                (id, title, dayOfWeek, startTime, endTime, room, notes, teacher, weeks, date,
                 uid, updatedAt, deletedAt)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            entity.id,
            entity.title,
            entity.dayOfWeek,
            entity.startTime,
            entity.endTime,
            entity.room,
            entity.notes,
            entity.teacher,
            entity.weeks,
            entity.date,
            entity.uid.ifBlank { newUid() },
            System.currentTimeMillis(),
            entity.deletedAt
        )
    }

    /**
     * 软删除：不再物理删行，只打 `deletedAt` 标记。
     * 物理删行会让同步把「别的设备早已删掉、本机还没收到通知」的课程又推回来（数据复活）。
     */
    suspend fun delete(entity: ClassEntity): Unit = Db.write { c ->
        val now = System.currentTimeMillis()
        c.exec("UPDATE classes SET deletedAt = ?, updatedAt = ? WHERE id = ?", now, now, entity.id)
    }

    /** 覆盖导入用：清空整表再写入备份里的课程 */
    suspend fun deleteAll(): Unit = Db.write { c ->
        c.exec("DELETE FROM classes")
    }
}

internal fun ResultSet.readClasses(): List<ClassEntity> {
    val out = ArrayList<ClassEntity>()
    while (next()) {
        out += ClassEntity(
            id = getInt("id"),
            title = getString("title").orEmpty(),
            dayOfWeek = getString("dayOfWeek").orEmpty(),
            startTime = getString("startTime").orEmpty(),
            endTime = getString("endTime").orEmpty(),
            room = getString("room").orEmpty(),
            notes = getString("notes").orEmpty(),
            teacher = getString("teacher").orEmpty(),
            weeks = getString("weeks").orEmpty(),
            date = getString("date").orEmpty(),
            uid = getString("uid").orEmpty(),
            updatedAt = getLong("updatedAt"),
            deletedAt = getLong("deletedAt")
        )
    }
    return out
}
