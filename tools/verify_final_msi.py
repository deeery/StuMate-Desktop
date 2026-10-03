# -*- coding: utf-8 -*-
"""验收：直读最终 MSI 表，逐项确认 2819 / 快捷方式 / 完成后启动 / 图标。

用法: python tools/verify_final_msi.py <final.msi>

## 为什么不用 dark 反编译的 XML
dark 对 `WixUIExtension` 引入的 Dialog 会报 DARK1059「ControlEvent 引用了
不存在的 Control」并**丢弃**这些行 —— 实测那是**误报**（MSI API 直读证明
外键悬空 = 0）。本脚本全程走 msi.dll 的 MSI API，不经过 dark。

## MSI SQL 的两个坑（都撞过）
1. `Dialog` / `Control` / `UI` / `ControlEvent` 是**保留字**，
   即使加反引号 `` `Dialog` `` 也报 1615。本脚本因此改用虚表
   `SELECT Name FROM `_Tables`` 判断表是否存在 —— 比查表更稳。
2. 列名和表名不总是同名：`Shortcut.Directory_`、`Shortcut.Icon_`、
   `Component.Directory_`、`File.Component_`、`File.FileName`。
   拿不准就读虚表：`SELECT * FROM `_Columns``（列名是
   `Table` / `Column` / `ColNumber`）。
"""
import hashlib
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import msi_table

MSI = sys.argv[1]
bad = []


def q(sql):
    return msi_table.query(MSI, sql)


def need(cond, ok_msg, fail_msg):
    if cond:
        print("  OK   " + ok_msg)
    else:
        print("  FAIL " + fail_msg)
        bad.append(fail_msg)


def hdr(t):
    print()
    print("=" * 70)
    print(t)
    print("=" * 70)


tables = sorted(r[0] for r in q("SELECT Name FROM `_Tables`"))
props = dict((r[0], r[1]) for r in q("SELECT `Property`, Value FROM `Property`"))
icons = [r[0] for r in q("SELECT Name FROM `Icon`")]
files = dict((r[0], r[2]) for r in q("SELECT File, Component_, FileName FROM `File`"))
comps = dict((r[0], (r[2], r[4], r[5])) for r in
             q("SELECT Component, ComponentId, Directory_, Attributes, Condition, KeyPath FROM `Component`"))
dirs = dict((r[0], (r[1], r[2])) for r in q("SELECT Directory, Directory_Parent, DefaultDir FROM `Directory`"))


def dir_chain(d):
    """沿 Directory_Parent 往上走 -> (Id 链, 可读名链)。

    ⚠️ **必须同时看 Id 链**：开始菜单那层的 Id 是 `ProgramMenuFolder`、
    DefaultDir 只是 `.`，只看可读名会拼成 `SourceDir/./StuMate` 而认不出来
    （我第一版就是这么漏判的）。
    """
    ids, names = [], []
    cur = d
    while cur:
        parent, default = dirs.get(cur, ("", ""))
        ids.append(cur)
        if default:
            names.append(default)
        cur = parent
    return ids, "/".join(reversed(names))


hdr("1. 2819 —— UI 相关表必须整张不存在")
print("  共 %d 张表: %s" % (len(tables), ", ".join(t for t in tables if not t.startswith("_"))))
for t in ("UI", "Dialog", "Control", "ControlEvent"):
    need(t not in tables,
         "%s 表不存在 -> 该对话框压根不在包里" % t,
         "%s 表还在 -> 安装向导里仍可能报 2819" % t)
need("_Validation" in tables,
     "_Validation 表在（MSI 自带的引用完整性校验已通过）",
     "没有 _Validation 表（异常）")

hdr("2. Property 表")
for k, v in sorted(props.items()):
    print("  %-28s = %s" % (k, v))
need("ARPPRODUCTICON" in props,
     "ARPPRODUCTICON 属性在（「程序和功能」有图标）", "ARPPRODUCTICON 属性缺失")
need(props.get("ARPPRODUCTICON") in icons,
     "ARPPRODUCTICON=%s 在 Icon 表里有对应节点" % props.get("ARPPRODUCTICON"),
     "ARPPRODUCTICON 指向不存在的 Icon 节点")
need("JP_INSTALL_STARTMENU_SHORTCUT" in props,
     "JP_INSTALL_STARTMENU_SHORTCUT=1（开始菜单快捷方式开关已打开）",
     "JP_INSTALL_STARTMENU_SHORTCUT 缺失 -> 开始菜单快捷方式不会被创建")
need("JP_INSTALL_DESKTOP_SHORTCUT" in props,
     "JP_INSTALL_DESKTOP_SHORTCUT=1（桌面快捷方式开关已打开）",
     "JP_INSTALL_DESKTOP_SHORTCUT 缺失 -> 桌面快捷方式不会被创建")
need("WIXUI_INSTALLDIR" not in props,
     "没有 WIXUI_INSTALLDIR（WixUI 已整体移除，不该残留）",
     "Property 表里有 WIXUI_INSTALLDIR —— 后处理重复插入了")
need("INSTALLDIR_VALID" not in props,
     "没有 INSTALLDIR_VALID（必须由 wixhelper 运行时设置）",
     "Property 表里有 INSTALLDIR_VALID -> 「目录已存在」确认框会永远不弹")

hdr("3. 快捷方式（开始菜单 + 桌面）")
scs = q("SELECT Shortcut, Directory_, Name, Component_, Target, Icon_ FROM `Shortcut`")
print("  Shortcut 表共 %d 条" % len(scs))
menu = []
desk = []
for r in scs:
    sc_id, d, name, comp, target, icon = r
    ids, chain = dir_chain(d)
    cond = comps.get(comp, ("", "", ""))[1]
    key = target[2:-1] if target.startswith("[#") and target.endswith("]") else ""
    target_name = files.get(key, "?")
    print("    %s" % name)
    print("      id      = %s" % sc_id)
    print("      挂在    = %s" % "/".join(ids))
    print("      位置    = %s" % chain)
    print("      目标    = %s" % target_name)
    print("      图标    = %s" % icon)
    print("      条件    = %s" % (cond or "(无条件)"))
    if "ProgramMenuFolder" in ids:
        menu.append((chain, cond))
    if "DesktopFolder" in ids:
        desk.append((chain, cond))
    need(target_name.lower().endswith(".exe"),
         "目标 %s 是可执行文件" % target_name, "目标不是 exe：%s" % target_name)
    need(icon in icons, "图标 %s 在 Icon 表里" % icon, "图标 %s 不在 Icon 表里" % icon)
need(len(menu) >= 1, "开始菜单快捷方式存在（用户明确要的）", "没有开始菜单快捷方式")
need(len(desk) >= 1, "桌面快捷方式存在", "没有桌面快捷方式")
for chain, cond in menu:
    need("StuMate" in chain, "开始菜单挂在 %s 组下" % chain,
         "开始菜单没挂在 StuMate 组下：%s" % chain)
    need(cond.strip() in props and props[cond.strip()] == "1",
         "条件属性 %s=1 -> 该快捷方式会被真正创建" % cond.strip(),
         "条件属性 %s 不为 1 -> 快捷方式不会创建" % cond.strip())
for chain, cond in desk:
    need(cond.strip() in props and props[cond.strip()] == "1",
         "桌面快捷方式条件属性 %s=1" % cond.strip(),
         "桌面快捷方式条件属性 %s 不为 1" % cond.strip())

hdr("4. 安装完成后启动")
# CustomAction/Type 是位掩码（MSDN msidbCustomActionType*）
CA_BITS = [(0x0001, "DllEntry"), (0x0002, "Exe"), (0x0005, "JScript"),
           (0x0006, "VBScript"), (0x0007, "Install"),
           (0x0010, "Directory"), (0x0020, "Property"), (0x0040, "BinaryData"),
           (0x0080, "SourceFile"), (0x0100, "DirectoryProperty"),
           (0x0400, "InScript(=deferred)"), (0x0800, "NoImpersonate"),
           (0x1000, "Rollback"), (0x2000, "Commit")]


def ca_flags(t):
    t = int(t)
    return "+".join(n for b, n in CA_BITS if t & b)


cas = q("SELECT Action, Type, Source, Target, ExtendedType FROM `CustomAction`")
hit = [r for r in cas if "QuietExec" in " ".join(r)]
for r in hit:
    print("    action   = %s" % r[0])
    print("    type     = %s  [%s]" % (r[1], ca_flags(r[1])))
    print("    source   = %s" % r[2])
    print("    dllEntry = %s" % r[3])
    print("    extended = %s" % r[4])
need(len(hit) >= 1, "WixQuietExec CustomAction 在", "没有完成后启动的 CustomAction")
for r in hit:
    flags = ca_flags(r[1])
    need("InScript(=deferred)" in flags, "deferred 执行（InScript 位）", "不是 deferred")
    need("NoImpersonate" in flags, "Impersonate=no（msiexec 通常以 SYSTEM 跑）",
         "会 impersonate SYSTEM，可能启动失败")
    need("BinaryData" in flags, "参数存Binary 表（Source=%s）" % r[2], "参数不在 Binary 表里")

seq = dict((r[0], (r[1], r[2])) for r in
           q("SELECT Action, Condition, Sequence FROM `InstallExecuteSequence`"))
for r in hit:
    act = r[0]
    if act in seq:
        cond, order = seq[act]
        print("    sequence = %s   condition = %r" % (order, cond))
        need(1500 < int(order) < 6600,
             "%s 在 InstallExecuteSequence 的合法窗口（1500~6600）内" % act,
             "%s 的序号 %s 不在 1500~6600（ICE77 会拦）" % (act, order))
        need("REMOVE" in cond.upper(),
             "条件 %r 含 NOT REMOVE 保护（卸载时不启动）" % cond,
             "条件里没有 NOT REMOVE -> 卸载完程序会又弹回来")

hdr("5. 文件指纹")
size = os.path.getsize(MSI)
h = hashlib.sha256(open(MSI, "rb").read()).hexdigest()
print("  %s" % os.path.basename(MSI))
print("  %d 字节 (%.2f MB)" % (size, size / 1024.0 / 1024.0))
print("  sha256 %s" % h)
print("  File 表 %d 个文件" % len(files))
ico = [f for f in files.values() if f.lower().endswith(".ico")]
exe = [f for f in files.values() if f.lower().endswith(".exe")]
print("  ico: %s" % ", ".join(ico))
print("  exe: %s" % ", ".join(exe))

hdr("结论")
if bad:
    print("%d 项不通过:" % len(bad))
    for b in bad:
        print("  - " + b)
    sys.exit(1)
print("全部通过")