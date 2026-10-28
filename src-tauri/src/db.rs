//! SQLite 访问层。
//!
//! ## 为什么 Lite 直接连原版的库
//!
//! 这是产品决定（用户明确选择）：两版共用 `%APPDATA%\StuMate\class_reminder.db`，
//! 任何一侧的改动另一侧立刻可见。代价是**两边同时写**时会争锁，所以这里做了三件事：
//!
//! 1. `busy_timeout = 5000` —— SQLite 默认 0ms，一撞锁立刻报 `database is locked`；
//!    给 5 秒重试窗口，桌面端的写入都是毫秒级，实际不会顶到上限。
//! 2. WAL —— 原版也是 WAL，读写不互斥，两个进程一读一写可以同时进行。
//! 3. 单连接 + 一把互斥锁 —— 进程内串行化，把 `SQLITE_BUSY` 这一整类问题挡在本进程外。
//!
//! ## 为什么不做迁移
//!
//! `user_version` 是原版（和安卓端）的领土。Lite 只在**空库**上建表，
//! 见到 `1..9` 的老库直接报错让用户先开一次原版 —— 自己写一份迁移等于制造第二套
//! schema 事实源，两边一旦分叉就是「谁的库能打开」的随机故障。
//! 见到 `10` 就直接用；见到 `> 10`（未来版本）也放行读，只提示版本更高。

use crate::models::{ClassEntity, KnowledgeEntity, NoteEntity};
use rusqlite::{params, Connection, OptionalExtension};
use std::path::{Path, PathBuf};
use std::sync::Mutex;

/// 与原版 `Db.SCHEMA_VERSION` 对齐。写死在这里是刻意的：
/// 它一旦变化，说明两端的数据契约变了，Lite 必须重新核对每一列。
pub const EXPECTED_SCHEMA: i32 = 10;

pub struct Db {
    conn: Mutex<Connection>,
    path: PathBuf,
}

impl Db {
    pub fn open(path: &Path) -> Result<Self, String> {
        if let Some(dir) = path.parent() {
            std::fs::create_dir_all(dir).map_err(|e| format!("创建数据目录失败: {e}"))?;
        }
        let conn = Connection::open(path).map_err(|e| format!("打开数据库失败: {e}"))?;
        conn.busy_timeout(std::time::Duration::from_millis(5000))
            .map_err(|e| e.to_string())?;
        // journal_mode 在 WAL 库上是幂等的；返回行必须消费掉，否则 PRAGMA 不生效
        let _: String = conn
            .query_row("PRAGMA journal_mode = WAL", [], |r| r.get(0))
            .unwrap_or_else(|_| "wal".to_string());
        conn.pragma_update(None, "synchronous", "NORMAL")
            .map_err(|e| e.to_string())?;

        let version: i32 = conn
            .query_row("PRAGMA user_version", [], |r| r.get(0))
            .unwrap_or(0);

        if version == 0 {
            create_tables(&conn)?;
            conn.pragma_update(None, "user_version", EXPECTED_SCHEMA)
                .map_err(|e| e.to_string())?;
        } else if version < EXPECTED_SCHEMA {
            return Err(format!(
                "这个数据库还是 schema v{version}，Lite 只认 v{EXPECTED_SCHEMA}。\n\
                 请先用原版 StuMate 打开一次完成升级，再回到 Lite —— \
                 Lite 不自己迁移，避免两版对同一份库产生两套 schema。"
            ));
        }

        Ok(Self {
            conn: Mutex::new(conn),
            path: path.to_path_buf(),
        })
    }

    pub fn path(&self) -> &Path {
        &self.path
    }

    pub fn schema_version(&self) -> i32 {
        self.with(|c| c.query_row("PRAGMA user_version", [], |r| r.get(0)))
            .unwrap_or(0)
    }

    fn with<T>(&self, f: impl FnOnce(&Connection) -> rusqlite::Result<T>) -> Result<T, String> {
        let guard = self
            .conn
            .lock()
            .map_err(|_| "数据库连接锁已损坏".to_string())?;
        f(&guard).map_err(|e| format!("数据库错误: {e}"))
    }

    // ── 课程表 ────────────────────────────────────────────────────

    pub fn classes(&self) -> Result<Vec<ClassEntity>, String> {
        self.with(|c| {
            let mut stmt = c.prepare(
                "SELECT id, title, dayOfWeek, startTime, endTime, room, notes, teacher, \
                 weeks, date, uid, updatedAt, deletedAt FROM classes \
                 WHERE deletedAt = 0 ORDER BY dayOfWeek, startTime",
            )?;
            let rows = stmt.query_map([], |r| {
                Ok(ClassEntity {
                    id: r.get(0)?,
                    title: r.get(1)?,
                    day_of_week: r.get(2)?,
                    start_time: r.get(3)?,
                    end_time: r.get(4)?,
                    room: r.get(5)?,
                    notes: r.get(6)?,
                    teacher: r.get(7)?,
                    weeks: r.get(8)?,
                    date: r.get(9)?,
                    uid: r.get(10)?,
                    updated_at: r.get(11)?,
                    deleted_at: r.get(12)?,
                })
            })?;
            rows.collect()
        })
    }

    /// 保存一节课。返回落库后的实体（补好了 id / uid）。
    ///
    /// 改课名时**同事务**把知识库的 `subject` 一起改掉 —— 与原版 `MainViewModel.save`
    /// 的口径一致。分两次写会留下「课名改了、知识库还挂旧名」的错位，界面上无处提示。
    pub fn save_class(&self, mut e: ClassEntity) -> Result<ClassEntity, String> {
        if e.title.trim().is_empty() {
            return Err("课名不能为空".into());
        }
        self.with(|c| {
            let tx = c.unchecked_transaction()?;
            let old_title: Option<String> = if e.id > 0 {
                tx.query_row(
                    "SELECT title FROM classes WHERE id = ?1",
                    params![e.id],
                    |r| r.get(0),
                )
                .optional()?
            } else {
                None
            };
            if e.id <= 0 {
                e.id = next_free_id(&tx, "classes")?;
            }
            if e.uid.trim().is_empty() {
                e.uid = uuid::Uuid::new_v4().to_string();
            }
            e.updated_at = now_ms();
            tx.execute(
                "INSERT OR REPLACE INTO classes \
                 (id, title, dayOfWeek, startTime, endTime, room, notes, teacher, weeks, date, \
                  uid, updatedAt, deletedAt) \
                 VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13)",
                params![
                    e.id,
                    e.title,
                    e.day_of_week,
                    e.start_time,
                    e.end_time,
                    e.room,
                    e.notes,
                    e.teacher,
                    e.weeks,
                    e.date,
                    e.uid,
                    e.updated_at,
                    e.deleted_at
                ],
            )?;
            if let Some(old) = old_title {
                if !old.trim().is_empty() && old != e.title {
                    tx.execute(
                        "UPDATE knowledge SET subject = ?1, updatedAt = ?2 \
                         WHERE subject = ?3 AND deletedAt = 0",
                        params![e.title, now_ms(), old],
                    )?;
                }
            }
            tx.commit()?;
            Ok(e.clone())
        })
    }

    pub fn delete_class(&self, id: i32) -> Result<(), String> {
        self.with(|c| {
            let now = now_ms();
            c.execute(
                "UPDATE classes SET deletedAt = ?1, updatedAt = ?2 WHERE id = ?3",
                params![now, now, id],
            )?;
            Ok(())
        })
    }

    pub fn clear_classes(&self) -> Result<(), String> {
        self.with(|c| c.execute("DELETE FROM classes", []).map(|_| ()))
    }

    // ── 便签 ──────────────────────────────────────────────────────

    pub fn notes(&self) -> Result<Vec<NoteEntity>, String> {
        self.with(|c| {
            let mut stmt = c.prepare(
                "SELECT id, title, content, position, createdAt, colorIndex, typeIndex, \
                 customLabel, deadlineAt, uid, updatedAt, deletedAt FROM notes \
                 WHERE deletedAt = 0 ORDER BY position ASC, createdAt ASC",
            )?;
            let rows = stmt.query_map([], |r| {
                Ok(NoteEntity {
                    id: r.get(0)?,
                    title: r.get(1)?,
                    content: r.get(2)?,
                    position: r.get(3)?,
                    created_at: r.get(4)?,
                    color_index: r.get(5)?,
                    type_index: r.get(6)?,
                    custom_label: r.get(7)?,
                    deadline_at: r.get(8)?,
                    uid: r.get(9)?,
                    updated_at: r.get(10)?,
                    deleted_at: r.get(11)?,
                })
            })?;
            rows.collect()
        })
    }

    pub fn save_note(&self, mut e: NoteEntity) -> Result<NoteEntity, String> {
        if e.title.trim().is_empty() {
            return Err("标题不能为空".into());
        }
        self.with(|c| {
            if e.id <= 0 {
                e.id = next_free_id(c, "notes")?;
                // 新便签取「当前最小值 - 1」，于是天然排在最顶端（与原版一致）
                let min_pos: Option<i32> =
                    c.query_row("SELECT MIN(position) FROM notes", [], |r| r.get(0))?;
                e.position = min_pos.map(|p| p - 1).unwrap_or(0);
            }
            if e.created_at == 0 {
                e.created_at = now_ms();
            }
            if e.uid.trim().is_empty() {
                e.uid = uuid::Uuid::new_v4().to_string();
            }
            e.updated_at = now_ms();
            c.execute(
                "INSERT OR REPLACE INTO notes \
                 (id, title, content, position, createdAt, colorIndex, typeIndex, customLabel, \
                  deadlineAt, uid, updatedAt, deletedAt) \
                 VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12)",
                params![
                    e.id,
                    e.title,
                    e.content,
                    e.position,
                    e.created_at,
                    e.color_index,
                    e.type_index,
                    e.custom_label,
                    e.deadline_at,
                    e.uid,
                    e.updated_at,
                    e.deleted_at
                ],
            )?;
            Ok(e.clone())
        })
    }

    pub fn delete_note(&self, id: i32) -> Result<(), String> {
        self.with(|c| {
            let now = now_ms();
            c.execute(
                "UPDATE notes SET deletedAt = ?1, updatedAt = ?2 WHERE id = ?3",
                params![now, now, id],
            )?;
            Ok(())
        })
    }

    /// 拖动排序：按传入顺序把 position 重写成 0..n-1。
    ///
    /// 重写而不是「交换两行」，是为了避免长期累加后出现负数或撞车；
    /// 原版 `MainViewModel.moveNote` 也是整批重写。
    pub fn reorder_notes(&self, ids: Vec<i32>) -> Result<(), String> {
        self.with(|c| {
            let tx = c.unchecked_transaction()?;
            let now = now_ms();
            for (index, id) in ids.iter().enumerate() {
                tx.execute(
                    "UPDATE notes SET position = ?1, updatedAt = ?2 WHERE id = ?3",
                    params![index as i32, now, id],
                )?;
            }
            tx.commit()
        })
    }

    pub fn clear_notes(&self) -> Result<(), String> {
        self.with(|c| c.execute("DELETE FROM notes", []).map(|_| ()))
    }

    // ── 知识库 ────────────────────────────────────────────────────

    pub fn knowledge(&self) -> Result<Vec<KnowledgeEntity>, String> {
        self.with(|c| {
            let mut stmt = c.prepare(
                "SELECT id, subject, title, content, position, createdAt, uid, updatedAt, \
                 deletedAt FROM knowledge WHERE deletedAt = 0 \
                 ORDER BY subject ASC, position ASC, createdAt ASC",
            )?;
            let rows = stmt.query_map([], |r| {
                Ok(KnowledgeEntity {
                    id: r.get(0)?,
                    subject: r.get(1)?,
                    title: r.get(2)?,
                    content: r.get(3)?,
                    position: r.get(4)?,
                    created_at: r.get(5)?,
                    uid: r.get(6)?,
                    updated_at: r.get(7)?,
                    deleted_at: r.get(8)?,
                })
            })?;
            rows.collect()
        })
    }

    pub fn save_knowledge(&self, mut e: KnowledgeEntity) -> Result<KnowledgeEntity, String> {
        let title = e.title.trim().to_string();
        if title.is_empty() {
            return Err("标题不能为空".into());
        }
        e.title = title;
        e.subject = e.subject.trim().to_string();
        self.with(|c| {
            if e.id <= 0 {
                e.id = next_free_id(c, "knowledge")?;
                let max_pos: Option<i32> = c.query_row(
                    "SELECT MAX(position) FROM knowledge WHERE subject = ?1",
                    params![e.subject],
                    |r| r.get(0),
                )?;
                e.position = max_pos.map(|p| p + 1).unwrap_or(0);
            }
            if e.created_at == 0 {
                e.created_at = now_ms();
            }
            if e.uid.trim().is_empty() {
                e.uid = uuid::Uuid::new_v4().to_string();
            }
            e.updated_at = now_ms();
            c.execute(
                "INSERT OR REPLACE INTO knowledge \
                 (id, subject, title, content, position, createdAt, uid, updatedAt, deletedAt) \
                 VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9)",
                params![
                    e.id,
                    e.subject,
                    e.title,
                    e.content,
                    e.position,
                    e.created_at,
                    e.uid,
                    e.updated_at,
                    e.deleted_at
                ],
            )?;
            Ok(e.clone())
        })
    }

    pub fn delete_knowledge(&self, id: i32) -> Result<(), String> {
        self.with(|c| {
            let now = now_ms();
            c.execute(
                "UPDATE knowledge SET deletedAt = ?1, updatedAt = ?2 WHERE id = ?3",
                params![now, now, id],
            )?;
            Ok(())
        })
    }

    pub fn clear_knowledge(&self) -> Result<(), String> {
        self.with(|c| c.execute("DELETE FROM knowledge", []).map(|_| ()))
    }

    /// 科目下有多少条（活的）。改课名时用来提示「将合并 N 条」。
    pub fn knowledge_counts(&self) -> Result<Vec<(String, i32)>, String> {
        self.with(|c| {
            let mut stmt = c.prepare(
                "SELECT subject, COUNT(*) FROM knowledge WHERE deletedAt = 0 GROUP BY subject",
            )?;
            let rows = stmt.query_map([], |r| Ok((r.get(0)?, r.get(1)?)))?;
            rows.collect()
        })
    }
}

fn now_ms() -> i64 {
    chrono::Utc::now().timestamp_millis()
}

fn next_free_id(c: &Connection, table: &str) -> rusqlite::Result<i32> {
    let sql = format!("SELECT COALESCE(MAX(id), 0) + 1 FROM `{table}`");
    c.query_row(&sql, [], |r| r.get(0))
}

/// 建表语句。**逐列对齐**原版 `Db.createTables`（schema v10），
/// 连列顺序都不改 —— `INSERT OR REPLACE` 用的是显式列名，但备份/导出工具
/// 以及「两端 .db 可互开」的核对脚本会按列序比对。
fn create_tables(c: &Connection) -> Result<(), String> {
    c.execute_batch(
        r#"
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
        );

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
        );

        CREATE TABLE IF NOT EXISTS `knowledge`(
            `id` INTEGER NOT NULL,
            `subject` TEXT NOT NULL DEFAULT '',
            `title` TEXT NOT NULL DEFAULT '',
            `content` TEXT NOT NULL DEFAULT '',
            `position` INTEGER NOT NULL DEFAULT 0,
            `createdAt` INTEGER NOT NULL DEFAULT 0,
            `uid` TEXT NOT NULL DEFAULT '',
            `updatedAt` INTEGER NOT NULL DEFAULT 0,
            `deletedAt` INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(`id`)
        );
        "#,
    )
    .map_err(|e| format!("建表失败: {e}"))
}
