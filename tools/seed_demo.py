"""给桌面端灌演示数据（仅用于 UI 验收截图）。

用法：
  python seed_demo.py            # 写入演示数据
  python seed_demo.py --reset    # 清空数据，恢复成全新状态

直接写 SQLite，字段与 Room v7 的表结构逐列一致。
"""
import datetime as dt
import json
import os
import sqlite3
import sys

DATA_DIR = os.path.join(os.environ.get("APPDATA", os.path.expanduser("~")), "StuMate")
DB = os.path.join(DATA_DIR, "class_reminder.db")
SETTINGS = os.path.join(DATA_DIR, "settings.json")

# (title, dayOfWeek, start, end, room, teacher, weeks)
COURSES = [
    ("高等数学", "Monday", "08:00", "09:35", "教二 305", "王海燕", "1-16周"),
    ("大学英语", "Monday", "10:40", "12:15", "外语楼 208", "李娟", "1-16周"),
    ("数据结构", "Tuesday", "08:50", "10:25", "计算机楼 401", "张伟", "1-16周"),
    ("线性代数", "Tuesday", "14:20", "15:55", "教三 112", "陈立", "1-16周"),
    ("计算机网络", "Wednesday", "09:50", "11:25", "计算机楼 302", "刘洋", "1-16周"),
    ("大学物理", "Wednesday", "14:20", "15:55", "理科楼 505", "赵敏", "1-16周"),
    ("体育（羽毛球）", "Thursday", "16:00", "17:35", "体育馆", "孙涛", "1-16周"),
    ("操作系统", "Thursday", "18:30", "20:05", "计算机楼 208", "周凯", "1-16周"),
    ("马克思主义基本原理", "Friday", "10:40", "12:15", "文科楼 301", "吴迪", "1-16周"),
    ("编译原理", "Friday", "14:20", "15:55", "计算机楼 405", "郑楠", "1-16周"),
]

# (text, colorIndex, typeIndex, customLabel, deadlineOffsetDays)
NOTES = [
    ("周五前交实验报告", 5, 4, "", 2),        # Deadline
    ("复习高数第三章：多元函数微分", 0, 3, "", None),
    ("买牛奶和面包", 2, 2, "", None),
    ("读完《人类简史》第 4 章", 6, 5, "读书", None),
    ("预约图书馆研讨间", 1, 1, "", None),
    ("准备下周的英语演讲", 3, 4, "", 5),       # Deadline
    ("交社团报名表", 4, 6, "社团", 1),         # Deadline 自定义
]


def monday_of(day: dt.date) -> dt.date:
    return day - dt.timedelta(days=day.weekday())


def seed():
    os.makedirs(DATA_DIR, exist_ok=True)

    # 本周是第 4 周 → 第 1 周周一 = 本周一 - 3 周
    week1_monday = monday_of(dt.date.today()) - dt.timedelta(weeks=3)
    settings = {
        "advanceMinutes": 30,
        "autoStart": False,
        "showPopup": True,
        "firstRun": False,
        "themeMode": 0,
        "week1Monday": int(dt.datetime.combine(week1_monday, dt.time()).timestamp() * 1000),
        "lastTab": 0,
        "weekGrid": True,
        "experimentalGrid": True,
        "closeAction": 1,
        "notifyEnabled": True,
    }
    with open(SETTINGS, "w", encoding="utf-8") as f:
        json.dump(settings, f, ensure_ascii=False, indent=2)

    conn = sqlite3.connect(DB)
    cur = conn.cursor()
    cur.execute("DELETE FROM classes")
    cur.execute("DELETE FROM notes")

    base = int(dt.datetime.now().timestamp() * 1000) % 2147483647
    for i, (title, day, start, end, room, teacher, weeks) in enumerate(COURSES):
        cur.execute(
            "INSERT OR REPLACE INTO classes"
            "(id,title,dayOfWeek,startTime,endTime,room,notes,teacher,weeks,date)"
            " VALUES(?,?,?,?,?,?,?,?,?,?)",
            (base + i, title, day, start, end, room, "", teacher, weeks, ""),
        )

    now = dt.datetime.now()
    for i, (text, color, type_index, custom, deadline_days) in enumerate(NOTES):
        deadline = 0
        if deadline_days is not None:
            target = now + dt.timedelta(days=deadline_days)
            deadline = int(target.timestamp() * 1000)
        cur.execute(
            "INSERT OR REPLACE INTO notes"
            "(id,text,position,createdAt,colorIndex,typeIndex,customLabel,deadlineAt)"
            " VALUES(?,?,?,?,?,?,?,?)",
            (base + 1000 + i, text, i, int(now.timestamp() * 1000), color, type_index, custom, deadline),
        )

    conn.commit()
    conn.close()
    print(f"已写入 {len(COURSES)} 门课程、{len(NOTES)} 条便签")
    print(f"第 1 周周一 = {week1_monday}（今天为第 4 周）")


def reset():
    if os.path.exists(DB):
        os.remove(DB)
    for suffix in ("-wal", "-shm"):
        p = DB + suffix
        if os.path.exists(p):
            os.remove(p)
    if os.path.exists(SETTINGS):
        os.remove(SETTINGS)
    print("已清空数据，应用下次启动会是全新状态")


if __name__ == "__main__":
    if "--reset" in sys.argv:
        reset()
    else:
        seed()
