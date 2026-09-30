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
        c.query("SELECT * FROM classes ORDER BY dayOfWeek, startTime") { rs -> rs.readClasses() }
    }

    /** 与 Room 的 `OnConflictStrategy.REPLACE` 等价 */
    suspend fun insert(entity: ClassEntity): Unit = Db.write { c ->
        c.exec(
            """
            INSERT OR REPLACE INTO classes
                (id, title, dayOfWeek, startTime, endTime, room, notes, teacher, weeks, date)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
            entity.date
        )
    }

    suspend fun delete(entity: ClassEntity): Unit = Db.write { c ->
        c.exec("DELETE FROM classes WHERE id = ?", entity.id)
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
            date = getString("date").orEmpty()
        )
    }
    return out
}
