# 移动端 P3 数据层改造 · 验证报告 v1.0

> 日期：2026-09-30
> 范围：`ClassReminderNewest`（StuMate 安卓端）**数据层**，为云同步打地基
> 依据：`account-and-sync-design-v1.3.md` §5.2 / §5.3 / §5.6 / §7
> 对应交接书任务：`handover-private-agent-v1.0.md` §8.1 的 **P3**（桌面端已由另一会话完成）

---

## 1. 结论

| 项 | 结果 |
|---|---|
| 编译 | ✅ `BUILD SUCCESSFUL`，改动文件 **0 告警**（仅 `MainScreen.kt` 的历史告警） |
| 单元测试 | ✅ **145 / 145 通过**，0 失败 0 错误 0 跳过（10 个测试类） |
| Room 注解处理 | ✅ `kaptDebugKotlin` 通过，`insertRaw` / `markDeleted` / `insertAllRaw` / `updateAllRaw` 均生成了实现 |
| 迁移实证（v7 库 → v8） | ✅ **5 项检查全通过**（老数据零丢失 / schema 对齐 / uid 回填唯一 / 新列默认值 / 软删语义） |
| 与桌面端列对齐 | ✅ `uid` / `updatedAt` / `deletedAt` 列名、顺序、类型、NOT NULL 逐列一致 |

---

## 2. 改动清单

| 文件 | 改动 |
|---|---|
| `data/Uid.kt` | **新增**。`newUid()` + `ClassEntity.stamped()` / `NoteEntity.stamped()` |
| `data/ClassEntity.kt` | 追加 `uid: String` / `updatedAt: Long` / `deletedAt: Long` |
| `data/NoteEntity.kt` | 同上（列名与顺序与桌面端一致） |
| `data/AppDatabase.kt` | `version 7 → 8`；新增 `MIGRATION_7_8` + `backfillUids()` |
| `data/ClassDao.kt` | `interface` → **`abstract class`**；查询加 `deletedAt = 0`；`insert` 补 uid；`delete` 改软删除 |
| `data/NoteDao.kt` | 同上 + `insertAll` / `updateAll` / `deleteById` |
| `data/backup/Backup.kt` | `BackupFormat.SCHEMA 1 → 2`；encode/decode 各加 3 字段 |

**`MainViewModel`（10 处 DAO 调用）与 `ClassReminderService` 一行未改** —— 所有对外方法签名保持不变。

---

## 3. 三个关键实现决定

### 3.1 DAO 从 `interface` 改成 `abstract class`

需要一层「落库前补 uid 与 updatedAt」的包装方法。**不能用 Kotlin 接口的默认方法**：

Room 的 DAO 实现是 kapt 生成的 **Java** 类。Kotlin 在 `-Xjvm-default=disable`（1.9 的默认值）下，
接口默认方法会编译成 `DefaultImpls` 静态类 + 接口里的**抽象方法**，而 kapt 生成的 Java 实现类
不会带桥接 —— 调用时直接 `AbstractMethodError`。

抽象类的具体方法没有这个问题（Room 官方文档里的 `@Transaction` 包装方法就是这个形态）。
验证：生成的 `ClassDao_Impl.java` 里 `insertRaw` / `markDeleted` 都有实现，编译与运行路径都通。

### 3.2 uid 在**迁移里固化落库**，不做运行时惰性生成

迁移给每一条老行补一个 UUIDv4 并 `UPDATE` 写回。

如果留到运行时惰性生成，每次启动都会换一批 uid，同步层会把它们当成一批**新记录**，
历史数据就被复制了。这条是设计文档 §5.2 特意点名的坑。

同样地，**没有**把 `UUID.randomUUID()` 写成实体构造参数的默认值 ——
那样每次 `copy()` 都会换新 uid，而 `MainViewModel` 里有
`note.copy(position = index)`（拖动排序）和 `note.copy(colorIndex = color)`，
记录身份会当场断掉。

### 3.3 `deleteAll()` 保持物理删除

它是「备份覆盖导入」用的**本地恢复**，不是同步删除，不该留墓碑 —— 与桌面端一致。
只有 `delete` / `deleteById` 走软删除。

---

## 4. 迁移实证（v7 库 → v8）

用 `probe_migration_v7_v8.py` 在一个真实的 v7 库上跑了一遍迁移：

- **迁移 SQL 是从 `AppDatabase.kt` 里正则解析出来的**，不是手抄 —— 避免"测的和写的不一样"
- **Room 期望的 v8 schema 是从 kapt 生成的 `AppDatabase_Impl.java` 里读出来的**

```
解析出的迁移 SQL
  ALTER TABLE <t> ADD COLUMN `uid`       TEXT    NOT NULL DEFAULT ''
  ALTER TABLE <t> ADD COLUMN `updatedAt` INTEGER NOT NULL DEFAULT 0
  ALTER TABLE <t> ADD COLUMN `deletedAt` INTEGER NOT NULL DEFAULT 0
  涉及表: ['classes', 'notes']   含 uid 回填: True

迁移前: user_version=7   classes=3 行   notes=3 行

检查 1 · schema 是否等于 Room 期望的 v8 schema
  ✓ classes: 13 列全部匹配（名称 / 类型 / NOT NULL）
  ✓ notes:   11 列全部匹配（名称 / 类型 / NOT NULL）

检查 2 · 老数据是否完好
  ✓ classes: 3 行，10 个业务字段逐值相同
  ✓ notes:   3 行，8 个业务字段逐值相同

检查 3 · uid 回填
  ✓ classes: 3 个 uid 全部非空且两两不同
  ✓ notes:   3 个 uid 全部非空且两两不同
  ✓ 跨表也不撞（6 个 uid 全局唯一）

检查 4 · 新增列在老行上的取值
  ✓ classes: 3 行的 updatedAt / deletedAt 都是 0（= 老数据、未删除）
  ✓ notes:   3 行的 updatedAt / deletedAt 都是 0

检查 5 · 软删除语义
  ✓ 软删 id=2 后：列表可见 [1, 7]，墓碑 [2]（行仍在库里，不会被同步复活）

结果: 全部通过 ✅
```

---

## 5. 顺带查清的一个 Room 坑（重要）

迁移里 `ALTER TABLE ... ADD COLUMN ... NOT NULL DEFAULT ''`，而实体**没有**声明
`@ColumnInfo(defaultValue = ...)`。这看起来会触发 Room 的
`Migration didn't properly handle` 校验失败 —— 实测**不会**。

反编译 `room-runtime-2.6.1` 的 `TableInfo$Column.equals` 字节码：

```
91:  getfield createdFrom      // this.createdFrom
95:  iconst_1                  // CREATED_FROM_ENTITY
96:  if_icmpne 139
99:  ... other.createdFrom
106: iconst_2                  // CREATED_FROM_DATABASE
107: if_icmpne 139
110: getfield defaultValue
114: ifnull   139              ← ★ 实体侧默认值为 null 时，直接跳过默认值比较
117: Companion.defaultValueEquals(this.defaultValue, other.defaultValue)
```

**实体不声明 `defaultValue` 时，Room 会完全忽略数据库里的默认值。**
所以不必为了通过校验而加 `@ColumnInfo`，保持与项目既有迁移（5→6、6→7）的写法一致即可。

---

## 6. 遗留与下一步

### 6.1 交给 P4（同步引擎）时必须处理的两点

**(a) 客户端还缺一个「服务端 version」的落点。**
设计文档 §5.5 规定服务端维护 `version`（每次写入 +1），客户端推送时带 `baseVersion`。
那客户端就得记住自己上次看到的 version —— 现在实体里只有 `updatedAt`，
没有 `version`，也没有单独的 sync-state 表。**如果不趁现在定下来，P4 会需要第二次 schema 迁移**，
而按约定两端必须同步升级（v8 的库 v7 客户端打不开）。

**(b) 从服务端下发记录时，必须保留远端的 `updatedAt`。**
`stamped()` 会把 `updatedAt` 刷成本机当前时刻 —— 这对本地修改是对的，
但**同步应用远端记录时不能这么做**，否则 LWW 比的是本机时间，冲突解决会算错。
P4 需要一条「同步专用 upsert」路径，不复用 `stamped()`。

### 6.2 一个安全项建议（未改动，等你决定）

`AppDatabase.getInstance()` 里挂着 `.fallbackToDestructiveMigration()`。
它的含义是：**只要有一个迁移缺失，Room 会直接清库重建**。
现在加了 P3 迁移，这个开关的风险比之前更高了 —— 一旦将来漏写一个迁移，
用户数据会被静默清空，而不是抛异常。

移除它会让「缺迁移」变成显式崩溃（可定位），代价是 v1 及更早的库无法升级
（项目最早的迁移是 `MIGRATION_2_3`，没有 1→2）。
**我没有动它**，因为这是行为变更，需要你确认。

### 6.3 与另一端的一致性

- 桌面端 P3 由**另一个会话**完成，目前**未提交**
- 两端的列定义已逐列核对一致，`.db` 可以互开
- ⚠️ 升级后 **v8 的库 v7 客户端打不开** → 两端必须同时发版
