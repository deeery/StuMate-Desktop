"""验证 v8 → v9 的便签迁移：老库的 `text` 必须**整条进 `title`**，正文留空。

## 为什么这样做，而不是在脚本里复述一遍迁移 SQL

复述 SQL 只能证明「我以为的 SQL 是对的」，证明不了「代码里跑的是这个 SQL」。
所以这里**让真实代码去迁移**：

  1. 造一个临时 `APPDATA`，里面放一个 **v8 结构**、带老格式便签的库
  2. 用这个 `APPDATA` 起一次 UI 预览（`notes` 场景）——
     预览走的是和生产完全相同的 `AppPaths` / `Db.migrate`，一启动就把库迁到 v9
  3. 直接读迁移后的库断言：`user_version = 9`、列是 `title`/`content`（没有 `text`）、
     每条 `title` 都等于它原来的 `text`、`content` 为空
  4. 顺手把便签页截下来当验收图

## 为什么不直接动 %APPDATA% 里那个真库

它开着 WAL（实测主库 20 KB、`-wal` 有 4 MB），`cp` 只会拿到主库、丢掉 WAL 里的数据。
用临时 `APPDATA` 既不碰真数据，跑的又是真代码。

用法：
  python verify_v9_migration.py [输出目录]
"""
import datetime as dt
import json
import os
import shutil
import sqlite3
import subprocess
import sys

PROJECT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SANDBOX = r"F:/DownloadQQ/stumate-v9-migration"

# v8 的建表语句（迁移前）。**必须逐列照抄当时的 Db.createTables** ——
# 少一列、多一列都会让「迁移前」的库不真实，验证就失去意义。
V8_NOTES = """
CREATE TABLE IF NOT EXISTS `notes`(
    `id` INTEGER NOT NULL,
    `text` TEXT NOT NULL,
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
"""

V8_CLASSES = """
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
"""

# 老格式便签：只有一条 `text`。这些值就是断言基准。
# 刻意做得有长有短，且其中一条带引号 / 换行 —— 迁移是纯 SQL 搬运，
# 但换行和引号最容易在「拼字符串」式的迁移里被吃掉。
LEGACY_NOTES = [
    (9001, "周五前交实验报告", 0, 5, 4, "", 2),
    (9002, "复习高数第三章", 1, 0, 3, "", None),
    (9003, "买牛奶和面包", 2, 2, 2, "", None),
    (9004, '含"引号"与\n换行的老便签', 3, 6, 5, "读书", None),
    (9005, "交社团报名表", 4, 4, 6, "社团", 1),
]

SETTINGS = {
    "advanceMinutes": 30,
    "autoStart": False,
    "showPopup": True,
    "firstRun": False,
    "themeMode": 0,
    "lastTab": 2,
    "weekGrid": True,
    "experimentalGrid": True,
    "closeAction": 1,
    "notifyEnabled": True,
}

failures = []


def check(ok, what, detail=""):
    print(("  [OK] " if ok else "  [!!] ") + what + ("  " + detail if detail else ""))
    if not ok:
        failures.append(what)


def build_legacy_db(appdata):
    """造一个 v8 的库：老结构 + 老格式便签 + user_version=8"""
    data_dir = os.path.join(appdata, "StuMate")
    os.makedirs(data_dir, exist_ok=True)
    db = os.path.join(data_dir, "class_reminder.db")
    if os.path.exists(db):
        os.remove(db)

    monday = dt.date.today() - dt.timedelta(days=dt.date.today().weekday())
    week1 = monday - dt.timedelta(weeks=3)
    settings = dict(SETTINGS)
    settings["week1Monday"] = int(
        dt.datetime.combine(week1, dt.time()).timestamp() * 1000
    )
    with open(os.path.join(data_dir, "settings.json"), "w", encoding="utf-8") as f:
        json.dump(settings, f, ensure_ascii=False, indent=2)

    conn = sqlite3.connect(db)
    cur = conn.cursor()
    cur.execute(V8_CLASSES)
    cur.execute(V8_NOTES)
    now = dt.datetime.now()
    for nid, text, pos, color, type_index, custom, deadline_days in LEGACY_NOTES:
        deadline = 0
        if deadline_days is not None:
            deadline = int(
                (now + dt.timedelta(days=deadline_days)).timestamp() * 1000
            )
        cur.execute(
            "INSERT OR REPLACE INTO notes"
            "(id,text,position,createdAt,colorIndex,typeIndex,customLabel,deadlineAt,uid)"
            " VALUES(?,?,?,?,?,?,?,?,?)",
            (
                nid,
                text,
                pos,
                int(now.timestamp() * 1000),
                color,
                type_index,
                custom,
                deadline,
                f"legacy-uid-{nid}",
            ),
        )
    # 关键：把库标成 v8，否则 migrate() 会走「全新库」分支直接建新表，测不到迁移
    cur.execute("PRAGMA user_version = 8")
    conn.commit()
    conn.close()
    return db


def shoot(scenario, out_path, appdata, theme):
    """用指定 APPDATA / 主题起一次预览并截图（复用 shoot_scenarios.py）"""
    env = dict(os.environ)
    env["APPDATA"] = appdata
    env["STUMATE_THEME"] = theme
    return subprocess.run(
        [sys.executable, "tools/shoot_scenarios.py", os.path.dirname(out_path),
         f"{scenario}={os.path.basename(out_path)}"],
        cwd=PROJECT, env=env, capture_output=True, text=True
    ).returncode == 0


def main():
    out_dir = sys.argv[1] if len(sys.argv) > 1 else os.path.join(
        PROJECT, "build", "preview"
    )
    os.makedirs(out_dir, exist_ok=True)

    appdata = SANDBOX
    if os.path.exists(appdata):
        shutil.rmtree(appdata)
    db = build_legacy_db(appdata)
    print(f"沙箱 APPDATA = {appdata}")
    print(f"已造好 v8 老库：{len(LEGACY_NOTES)} 条便签，user_version=8\n")

    # ── 阶段一：迁移前的库长什么样（先断言一次，否则后面「迁移成功」没有对照） ──
    print("① 迁移前（应当有 text 列、没有 title）")
    conn = sqlite3.connect(db)
    cur = conn.cursor()
    cols = [r[1] for r in cur.execute("PRAGMA table_info(notes)")]
    ver = cur.execute("PRAGMA user_version").fetchone()[0]
    check("text" in cols and "title" not in cols, "v8 库是 text 结构", f"列={cols}")
    check(ver == 8, "user_version = 8", f"实际 {ver}")
    conn.close()

    # ── 阶段二：起一次真预览，让真代码迁移 ──
    print("\n② 起预览（真代码执行 migrateToV9）")
    for theme in ("light", "dark"):
        ok = shoot("notes", os.path.join(out_dir, f"notes-v9-migrated-{theme}.png"),
                   appdata, theme)
        check(ok, f"{theme} 主题便签页截图", "notes-v9-migrated-%s.png" % theme)

    # ── 阶段三：读迁移后的库 ──
    print("\n③ 迁移后（应当只剩 title/content，且数据一条不丢）")
    conn = sqlite3.connect(db)
    cur = conn.cursor()
    cols = [r[1] for r in cur.execute("PRAGMA table_info(notes)")]
    ver = cur.execute("PRAGMA user_version").fetchone()[0]
    check("title" in cols and "content" in cols, "库已是 title + content 结构", f"列={cols}")
    check("text" not in cols, "老的 text 列已被移除", f"列={cols}")
    check(ver == 9, "user_version = 9", f"实际 {ver}")
    check(
        cols == ["id", "title", "content", "position", "createdAt", "colorIndex",
                 "typeIndex", "customLabel", "deadlineAt", "uid", "updatedAt", "deletedAt"],
        "列顺序与实体声明一致（两端 .db 才能互开）",
        f"实际={cols}",
    )

    rows = list(cur.execute("SELECT id, title, content, colorIndex, typeIndex, customLabel FROM notes ORDER BY id"))
    check(len(rows) == len(LEGACY_NOTES), "便签条数不变", f"{len(rows)}/{len(LEGACY_NOTES)}")
    for (nid, text, _pos, color, type_index, custom, _dl), row in zip(LEGACY_NOTES, rows):
        got_id, got_title, got_content, got_color, got_type, got_custom = row
        check(got_id == nid, f"id={nid} 保持", f"实际 {got_id}")
        check(got_title == text, f"id={nid} 的 text 整条进了 title", repr(got_title))
        check(got_content == "", f"id={nid} 的 content 为空", repr(got_content))
        check((got_color, got_type, got_custom) == (color, type_index, custom),
              f"id={nid} 的分类 / 颜色 / 标签原样保留",
              f"{got_color},{got_type},{got_custom!r}")
    conn.close()

    # ── 阶段四：换一份「新格式」数据，验证两行渲染 ──
    # 上一步的库里每条便签都没有正文，截图看不出「标题 + 内容摘要」两行。
    # 这一步用 seed_demo（已经是新结构）灌一份带正文的，再截一组。
    print("\n④ 灌新格式演示数据，验证「标题 + 内容摘要」两行渲染")
    env = dict(os.environ)
    env["APPDATA"] = appdata
    subprocess.run([sys.executable, "tools/seed_demo.py"], cwd=PROJECT, env=env,
                   capture_output=True, text=True)
    for theme in ("light", "dark"):
        ok = shoot("notes", os.path.join(out_dir, f"notes-v9-content-{theme}.png"),
                   appdata, theme)
        check(ok, f"{theme} 主题便签页截图（含正文）", "notes-v9-content-%s.png" % theme)

    # ── 阶段五：编辑面板的两种状态 ──
    # 面板要靠点「新建便签」或点表格行才出现，而验收环境点不动鼠标，
    # 所以走 UiPreview 的 notescreate / notesedit 场景把目标状态直接摆出来。
    print("\n⑤ 编辑面板：空表单 / 回填")
    for scenario, name in (("notescreate", "editor-create"), ("notesedit", "editor-edit")):
        for theme in ("light", "dark"):
            ok = shoot(scenario, os.path.join(out_dir, f"notes-v9-{name}-{theme}.png"),
                       appdata, theme)
            check(ok, f"{theme} 主题 {name} 截图", f"notes-v9-{name}-{theme}.png")

    print()
    if failures:
        print(f"❌ {len(failures)} 项未通过：")
        for f in failures:
            print("   - " + f)
        return 1
    print("全部通过：v8 老库迁移后便签一条不丢，text 整条进了 title。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
