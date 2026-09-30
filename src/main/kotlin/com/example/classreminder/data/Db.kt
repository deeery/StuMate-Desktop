package com.example.classreminder.data

import com.example.classreminder.AppPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Statement

/**
 * 桌面端的数据库入口，替代安卓端的 Room `AppDatabase`。
 *
 * 设计取舍：
 *  - 用 `org.xerial:sqlite-jdbc` + 手写 SQL，不引 SQLDelight —— 本项目一共只有 11 条查询，
 *    手写成本极低，而且能保持 DAO 方法签名与安卓端完全一致（`MainViewModel` 调用点零改动）。
 *  - **单连接 + 一把互斥锁**：SQLite 的多写并发本来就要靠锁，桌面端的数据量（几百条）
 *    完全不需要连接池；串行化反而消灭了 `SQLITE_BUSY` 这一整类问题。
 *  - 建表 SQL 与 Room v7 导出的 schema **逐列一致**，因此两端的 `.db` 文件可以互相打开。
 */
object Db {

    /** 与安卓端 Room 的 version 对齐（classes + notes 共 18 列） */
    const val SCHEMA_VERSION = 7

    private val gate = Mutex()

    @Volatile
    private var connection: Connection? = null

    private fun conn(): Connection {
        connection?.let { if (!it.isClosed) return it }
        return synchronized(this) {
            connection?.let { if (!it.isClosed) return@synchronized it }
            open().also { connection = it }
        }
    }

    private fun open(): Connection {
        // 显式加载驱动，避免裁剪过的运行时里 SPI 自动发现失效
        Class.forName("org.sqlite.JDBC")
        val c = DriverManager.getConnection("jdbc:sqlite:${AppPaths.dbFile.absolutePath}")
        c.createStatement().use { st ->
            st.execute("PRAGMA journal_mode = WAL")
            st.execute("PRAGMA synchronous = NORMAL")
        }
        migrate(c)
        return c
    }

    private fun userVersion(c: Connection): Int =
        c.createStatement().use { st ->
            st.executeQuery("PRAGMA user_version").use { rs ->
                if (rs.next()) rs.getInt(1) else 0
            }
        }

    private fun migrate(c: Connection) {
        val current = userVersion(c)
        if (current >= SCHEMA_VERSION) return
        // 全新库直接建到最新版；桌面端是新库，不存在安卓端那 2→3…6→7 的历史迁移
        if (current < 1) createTables(c)
        c.createStatement().use { it.execute("PRAGMA user_version = $SCHEMA_VERSION") }
    }

    private fun createTables(c: Connection) {
        c.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS `classes`(
                    `id` INTEGER NOT NULL,
                    `title` TEXT NOT NULL,
                    `dayOfWeek` TEXT NOT NULL,
                    `startTime` TEXT NOT NULL,
                    `endTime` TEXT NOT NULL,
                    `room` TEXT NOT NULL,
                    `notes` TEXT NOT NULL,
                    `teacher` TEXT NOT NULL,
                    `weeks` TEXT NOT NULL,
                    `date` TEXT NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS `notes`(
                    `id` INTEGER NOT NULL,
                    `text` TEXT NOT NULL,
                    `position` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `colorIndex` INTEGER NOT NULL,
                    `typeIndex` INTEGER NOT NULL,
                    `customLabel` TEXT NOT NULL,
                    `deadlineAt` INTEGER NOT NULL,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
        }
    }

    // ── 访问入口 ────────────────────────────────────────────────────
    //
    // read / write 目前实现相同（单连接必须串行），分开命名是为了让调用点自述意图，
    // 将来若换成读写分离或加只读缓存，不必改 DAO。

    suspend fun <T> read(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        gate.withLock { block(conn()) }
    }

    suspend fun <T> write(block: (Connection) -> T): T = withContext(Dispatchers.IO) {
        gate.withLock { block(conn()) }
    }

    /** 退出应用时调用，让 WAL 落盘 */
    fun close() {
        synchronized(this) {
            runCatching { connection?.close() }
            connection = null
        }
    }
}

// ── JDBC 小工具 ────────────────────────────────────────────────────

/** 执行一条带参数的更新语句，返回受影响行数 */
internal fun Connection.exec(sql: String, vararg args: Any?): Int =
    prepareStatement(sql).use { st ->
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
        st.executeUpdate()
    }

/** 执行一条查询，把结果集交给 [map] 消费（结果集在块返回后关闭） */
internal fun <T> Connection.query(sql: String, vararg args: Any?, map: (ResultSet) -> T): T =
    prepareStatement(sql).use { st ->
        args.forEachIndexed { i, a -> st.setObject(i + 1, a) }
        st.executeQuery().use { rs -> map(rs) }
    }

/** 在事务里跑一批写操作；任一步抛异常则整体回滚 */
internal fun <T> Connection.transaction(block: () -> T): T {
    val previous = autoCommit
    autoCommit = false
    try {
        val result = block()
        commit()
        return result
    } catch (t: Throwable) {
        runCatching { rollback() }
        throw t
    } finally {
        autoCommit = previous
    }
}

/** 建表时用的通用执行 */
internal fun Connection.ddl(sql: String) {
    createStatement().use { st: Statement -> st.executeUpdate(sql) }
}
