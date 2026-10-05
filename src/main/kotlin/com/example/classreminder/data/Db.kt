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

    /**
     * 与安卓端 Room 的 version 对齐。
     * v7：classes 10 列 + notes 8 列；v8：各再加 3 列同步元数据；
     * v9：notes 的 `text` 拆成 `title` + `content`。
     */
    const val SCHEMA_VERSION = 9

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
        if (current < 1) {
            createTables(c)
        } else {
            if (current < 8) migrateToV8(c)
            if (current < 9) migrateToV9(c)
        }
        c.createStatement().use { it.execute("PRAGMA user_version = $SCHEMA_VERSION") }
    }

    /**
     * v7 → v8：给 `classes` / `notes` 各补三列同步元数据（uid / updatedAt / deletedAt）。
     *
     * 老库里的行没有 uid，这里逐行补一个 UUIDv4，**补完即固化**。
     * 不能留到运行时惰性生成：那样每次启动都会换一批 uid，同步层会把它们当成新记录。
     */
    private fun migrateToV8(c: Connection) {
        for (table in listOf("classes", "notes")) {
            addColumnIfMissing(c, table, "uid", "TEXT NOT NULL DEFAULT ''")
            addColumnIfMissing(c, table, "updatedAt", "INTEGER NOT NULL DEFAULT 0")
            addColumnIfMissing(c, table, "deletedAt", "INTEGER NOT NULL DEFAULT 0")
            backfillUids(c, table)
        }
    }

    /**
     * v8 → v9：把便签的单字段 `text` 拆成 `title` + `content`。
     *
     * **只能重建表，不能只 `ADD COLUMN`** —— 这次要的不是「多两列」而是「少一列」。
     * SQLite 到 3.25 才有 `RENAME COLUMN`、3.35 才有 `DROP COLUMN`，
     * 而安卓端 minSdk 21 自带的是 3.8.6；两端要维持「`.db` 可互开」，
     * 迁移写法就必须一致 —— 所以两边都走标准的
     * 「建新表 → 搬数据 → 删旧表 → 改名」。
     *
     * 搬运规则：`title = text`、`content = ''`。
     * 用户的原话是「原先的内容直接加入标题」，所以老便签整条文本落进标题，
     * 正文从空串起步 —— 不会有「迁移后标题为空」的存量数据。
     *
     * ⚠️ 整段包在一个事务里。中途失败必须整体回滚：
     * 否则会留下「`user_version` 已写成 9、但列还是旧结构」的库，
     * 下次启动直接跳过迁移，之后每次查询都报 no such column。
     */
    private fun migrateToV9(c: Connection) {
        // 全新库走的是 createTables（已是新结构），或本函数被重复调用
        if (!c.hasColumn("notes", "text")) return
        c.transaction {
            c.ddl(
                """
                CREATE TABLE `notes_new`(
                    `id` INTEGER NOT NULL,
                    `title` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `position` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `colorIndex` INTEGER NOT NULL,
                    `typeIndex` INTEGER NOT NULL,
                    `customLabel` TEXT NOT NULL,
                    `deadlineAt` INTEGER NOT NULL,
                    `uid` TEXT NOT NULL DEFAULT '',
                    `updatedAt` INTEGER NOT NULL DEFAULT 0,
                    `deletedAt` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            c.ddl(
                """
                INSERT INTO `notes_new`
                    (`id`, `title`, `content`, `position`, `createdAt`, `colorIndex`,
                     `typeIndex`, `customLabel`, `deadlineAt`, `uid`, `updatedAt`, `deletedAt`)
                SELECT `id`, `text`, '', `position`, `createdAt`, `colorIndex`,
                       `typeIndex`, `customLabel`, `deadlineAt`, `uid`, `updatedAt`, `deletedAt`
                FROM `notes`
                """.trimIndent()
            )
            c.ddl("DROP TABLE `notes`")
            c.ddl("ALTER TABLE `notes_new` RENAME TO `notes`")
        }
    }

    /** 表里有没有这一列。`PRAGMA table_info` 是 SQLite 唯一可靠的「列存在性」查询 */
    private fun Connection.hasColumn(table: String, column: String): Boolean =
        query("PRAGMA table_info(`$table`)") { rs ->
            var found = false
            while (rs.next()) if (rs.getString("name") == column) found = true
            found
        }

    private fun addColumnIfMissing(c: Connection, table: String, column: String, declaration: String) {
        if (!c.hasColumn(table, column)) {
            c.ddl("ALTER TABLE `$table` ADD COLUMN `$column` $declaration")
        }
    }

    private fun backfillUids(c: Connection, table: String) {
        val ids = c.query("SELECT `id` FROM `$table` WHERE `uid` IS NULL OR `uid` = ''") { rs ->
            val out = ArrayList<Int>()
            while (rs.next()) out += rs.getInt("id")
            out
        }
        if (ids.isEmpty()) return
        c.transaction {
            ids.forEach { id ->
                c.exec("UPDATE `$table` SET `uid` = ? WHERE `id` = ?", newUid(), id)
            }
        }
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
                    `uid` TEXT NOT NULL DEFAULT '',
                    `updatedAt` INTEGER NOT NULL DEFAULT 0,
                    `deletedAt` INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY(`id`)
                )
                """.trimIndent()
            )
            st.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS `notes`(
                    `id` INTEGER NOT NULL,
                    `title` TEXT NOT NULL,
                    `content` TEXT NOT NULL,
                    `position` INTEGER NOT NULL,
                    `createdAt` INTEGER NOT NULL,
                    `colorIndex` INTEGER NOT NULL,
                    `typeIndex` INTEGER NOT NULL,
                    `customLabel` TEXT NOT NULL,
                    `deadlineAt` INTEGER NOT NULL,
                    `uid` TEXT NOT NULL DEFAULT '',
                    `updatedAt` INTEGER NOT NULL DEFAULT 0,
                    `deletedAt` INTEGER NOT NULL DEFAULT 0,
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
