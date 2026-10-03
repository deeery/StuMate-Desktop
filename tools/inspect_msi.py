# -*- coding: utf-8 -*-
"""
校验 MSI：快捷方式 / 自定义动作 / 图标 / 目录 / AppUserModelId / 完成后启动

## 为什么用 dark 反编译 + XML 解析，而不是 MSI API（msi.dll）
先写的版本走 `MsiDatabaseOpenViewW` + `MsiRecordGetStringW`，踩了一堆坑，最后稳定在
    OSError: exception: access violation reading 0x0000000000000400
（ctypes 绑定的 HANDLE 宽度/生命周期问题，debug 成本远高于收益）。
而 dark 解出来的 WXS 就是 MSI 表内容的**权威视图**，还能顺便：
  - 把 `<File>` 引用的二进制解出来（`-x` 目录）→ 可直接算 SHA 验图标
  - 保留 WiX 的结构化校验（Duplicate symbol 之类一眼可见）
代价是 70 MB 包会解出几百 MB 到临时目录，所以结束时清掉。

用法:
  python tools/inspect_msi.py <msi> [--keep <解包目录>]
退出码 0 = 全部检查通过，1 = 有问题。
"""
import os
import shutil
import subprocess
import sys
import tempfile
import xml.etree.ElementTree as ET

WIX = r"C:\Program Files (x86)\WiX Toolset v3.14\bin"
NS = "{http://schemas.microsoft.com/wix/2006/wi}"


def run(cmd):
    r = subprocess.run(cmd, capture_output=True)
    out = r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")
    if r.returncode != 0:
        raise SystemExit("命令失败 (%s): %s\n%s" % (r.returncode, " ".join(cmd), out[-2000:]))
    return out


def show(title):
    print()
    print("=" * 72)
    print(title)
    print("=" * 72)


def decompile(msi, work):
    """dark 反编译。

    ⚠️ dark 的 DARK1059 对 WixUIExtension引入的 Dialog 是**误报**
    （实测原包报 9 条，而用 msi_table 直读证明 ControlEvent 外键悬空 = 0），
    且它会**丢弃**这些 Control 行 → 2819 的判据不能用它，改走MSI API。
    """
    wxs = os.path.join(work, "out.wxs")
    p = subprocess.run([os.path.join(WIX, "dark.exe"), "-x", work, "-o", wxs, msi],
                       capture_output=True)
    out = p.stdout.decode("utf-8", "replace") + p.stderr.decode("utf-8", "replace")
    if p.returncode != 0:
        raise SystemExit("dark 失败 (%s)\n%s" % (p.returncode, out[-2000:]))
    return ET.parse(wxs).getroot(), out


def check_dir_dialog_2819(msi):
    """🔴 检查安装向导会不会报 **2819**。

    2819 的官方释义：``Control [3] on dialog [2] needs a property linked to it.``
    本机 Temp\\\\MSI*.LOG 抓到的实参是 `InstallDirDlg, Folder`
    → 即 WixUI 的 `InstallDirDlg` 上的 `Folder`（PathEdit）控件。

    ## 🔴 判据是「Dialog 表里有没有 InstallDirDlg」，**不是** DARK1059
    用 `tools/msi_table.py` 直读原包 MSI 表实测：

    | 表 | 行数 | 事实 |
    |---|---|---|
    | Dialog | 23 | InstallDirDlg **在** |
    | Control | 218 | InstallDirDlg/Folder: PathEdit, Attributes=11, Property=WIXUI_INSTALLDIR |
    | Property | 17 | **WIXUI_INSTALLDIR=INSTALLDIR 早就存在** |
    | ControlEvent | 135 | 对 218 个 Control，**外键悬空 = 0** |

    → 属性不缺、外键不缺、控件也不缺，所以「补属性」这条路无效
      （我先后栽在这上面两次：一次补 Yes/No 按钮的假属性、
        一次补 INSTALLDIR_VALID、一次补重复的 WIXUI_INSTALLDIR）。

    **DARK1059 是 dark 的误报**：它对 WixUIExtension 引入的 Dialog
    会报「ControlEvent 引用了不存在的 Control」并**丢弃**这些行，
    原包 9 条而实际悬空为 0。别拿它的数量当判据。

    也别拿「MSI 二进制里某字符串的偏移量落在哪个区段」当判据 ——
    light 链接会重排表布局，同一份包两次构建偏移基线能差 40 万字节。
    """
    import msi_table

    dlg_names = [r[0] for r in msi_table.query(msi, "SELECT * FROM `Dialog`")]
    has = "InstallDirDlg" in dlg_names

    show("7. 安装目录对话框（2819 风险）")
    print("  Dialog 表共 %d 条: %s" % (len(dlg_names), ", ".join(sorted(dlg_names))))
    if has:
        print("  ✗ 存在 InstallDirDlg —— 向导进到「选择安装位置」那一步会报 2819")
        print("    修法: build.gradle.kts 设 dirChooser = false 后**重新完整出包**")
        return False
    print("  → 无 InstallDirDlg，无 2819 风险")
    print("    （代价：向导里不能改安装目录，固定装到 Program Files）")
    return True


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    keep = None
    if "--keep" in sys.argv:
        keep = sys.argv[sys.argv.index("--keep") + 1]
    if not args:
        raise SystemExit(__doc__)
    msi = os.path.abspath(args[0])

    work = keep or tempfile.mkdtemp(prefix="stumate-inspect-")
    try:
        print("dark 解包 %s（%s）" % (os.path.basename(msi), work))
        root, dark_out = decompile(msi, work)

        problems = []
        dirs = {e.get("Id"): e for e in root.iter(NS + "Directory")}
        comps = {e.get("Id"): e for e in root.iter(NS + "Component")}
        icons = {e.get("Id"): e.get("SourceFile", "") for e in root.iter(NS + "Icon")}
        props = {e.get("Id"): e.get("Value", "") for e in root.iter(NS + "Property")}
        cas = {e.get("Id"): e for e in root.iter(NS + "CustomAction")}
        seqs = {e.get("Action"): e for e in root.iter(NS + "Custom")}

        # ── 1. 快捷方式 ──────────────────────────────────────────
        show("1. 快捷方式")
        shortcuts = list(root.iter(NS + "Shortcut"))
        if not shortcuts:
            problems.append("一个快捷方式都没有 —— 装完找不到入口")
        by_dir = {}
        for sc in shortcuts:
            d = sc.get("Directory", "")
            # 解析目录 Id → 人类可读位置
            chain = []
            cur = dirs.get(d)
            while cur is not None:
                chain.append(cur.get("Name") or cur.get("Id"))
                parent = cur.get("Id")
                cur = None
                for dd, de in dirs.items():
                    if de is not None and parent in [c.get("Id") for c in de]:
                        cur = de
                        break
            where = "/".join(reversed([c for c in chain if c]))
            by_dir.setdefault(where, []).append(sc)
            print("  %-22s dir=%-18s icon=%-22s name=%s"
                  % (sc.get("Id", "")[:22], d, sc.get("Icon", "(无)"), sc.get("Name")))
        print("  --- 按位置汇总 ---")
        for where, items in by_dir.items():
            print("  %-28s %d 条" % (where, len(items)))
        if not any("Desktop" in w or "桌面" in w for w in by_dir):
            print("  ⚠️ 没看到桌面快捷方式")
        if not any("Menu" in w or "开始" in w for w in by_dir):
            print("  ⚠️ 没看到开始菜单快捷方式")

        # ── 2. 完成后启动 ───────────────────────────────────────
        show("2. 安装完成后启动")
        ca = cas.get("LaunchStuMate")
        if ca is None:
            problems.append("没有 LaunchStuMate 自定义动作 —— 装完不会自动启动")
        else:
            print("  CustomAction: dll=%s entry=%s exec=%s imp=%s ret=%s"
                  % (ca.get("BinaryKey"), ca.get("DllEntry"), ca.get("Execute"),
                     ca.get("Impersonate"), ca.get("Return")))
            if ca.get("DllEntry") != "WixQuietExec":
                problems.append("LaunchStuMate 的 DllEntry 不是 WixQuietExec")
        entry = seqs.get("LaunchStuMate")
        if entry is None:
            problems.append("LaunchStuMate 没排进执行序列 —— 装完不会自动启动")
        else:
            s = entry.get("Sequence")
            cond = (entry.text or "").strip()
            print("  Sequence=%s  Condition=%s" % (s, cond or "(无)"))
            try:
                if not (1500 < int(s) < 6600):
                    problems.append("Sequence=%s 不在 InstallInitialize~InstallFinalize"
                                    "（1500~6600）之间，ICE77 会拦" % s)
            except (TypeError, ValueError):
                problems.append("Sequence 不是整数: %r" % s)
            if "REMOVE" not in cond.upper():
                problems.append("条件里没有 NOT REMOVE —— 卸载时会把程序又弹起来")

        # ── 3. AppUserModelID ───────────────────────────────────
        show("3. AppUserModelId（任务栏「固定到任务栏」的来源）")
        found = 0
        for rv in root.iter(NS + "RegistryValue"):
            if "AppUserModelId" in (rv.get("Key") or ""):
                print("  Root=%-5s Key=%s  Name=%s  Value=%s"
                      % (rv.get("Root"), rv.get("Key"), rv.get("Name"), rv.get("Value")))
                found += 1
        if not found:
            problems.append("没有 AppUserModelId 注册表项 —— 快捷方式在任务栏右键里"
                            "不会出现「固定到任务栏」")

        # ── 4. 图标 ─────────────────────────────────────────────
        show("4. 图标")
        brand = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))),
                             "app-icon.ico")
        brand_sha = None
        if os.path.exists(brand):
            import hashlib
            h = hashlib.sha256()
            with open(brand, "rb") as f:
                h.update(f.read())
            brand_sha = h.hexdigest()
            print("  参照 app-icon.ico: %d 字节 sha=%s…" % (os.path.getsize(brand), brand_sha[:16]))
        else:
            problems.append("找不到 app-icon.ico（无法核对图标）")
        for iid, src in icons.items():
            p = src.replace("\\", "/")
            real = os.path.join(work, os.path.relpath(p, work)) if os.path.isabs(p) else p
            info = ""
            if brand_sha and os.path.exists(real):
                import hashlib
                h = hashlib.sha256()
                with open(real, "rb") as f:
                    h.update(f.read())
                same = h.hexdigest() == brand_sha
                info = "%d 字节 %s" % (os.path.getsize(real),
                                      "== 品牌图标 ✓" if same else "≠ 品牌图标 ✗")
                if not same:
                    problems.append("Icon %s 不是品牌图标（%s）" % (iid, info))
            print("  %-22s %s" % (iid, info or "(文件未解出)"))
        arp = props.get("ARPPRODUCTICON")
        print("  ARPPRODUCTICON =", arp or "(空)")
        if not arp:
            problems.append("ARPPRODUCTICON 属性为空 —— 「程序和功能」列表没图标")
        elif arp not in icons:
            problems.append("ARPPRODUCTICON=%s 在 Icon 表里没有对应节点" % arp)

        # ── 4.5 安装目录对话框：2819 ────────────────────────────
        # 旧的「Button 控件缺 Property」判据是**错的**（照着错误根因写的）：
        #   jpackage 自绘的 InstallDirNotEmptyDlg 里 Yes/No 走
        #   `<Publish Event="NewDialog">`，本来就不需要 Property；
        #   2819 报的是 WixUI 的 `InstallDirDlg` / `Folder`。
        # 正解见 check_dir_dialog_2819()，详细输出放在最后一节（7）。
        # ⚠️ 顺手确认：INSTALLDIR_VALID **不该**出现在 Property 表里——
        #   它由 wixhelper.dll 的 CheckInstallDir 在运行时设置。
        #   谁把它声明成 Value="1"，「目录已存在」的确认框就永远不弹。
        if "INSTALLDIR_VALID" in props:
            print("  [问题] Property 表里有 INSTALLDIR_VALID=%r —— 它应由 "
                  "wixhelper.dll 运行时设置，预先声明会让「目录已存在」"
                  "确认框永远不弹" % props["INSTALLDIR_VALID"])
            problems.append("INSTALLDIR_VALID 被预先声明，会屏蔽目录非空确认框")

        # ── 5. 目录 ─────────────────────────────────────────────
        show("5. 目录")
        for want in ("TARGETDIR", "ProgramFiles64Folder", "ProgramMenuFolder",
                     "DesktopFolder"):
            if want in dirs:
                e = dirs[want]
                print("  %-22s Name=%-14s DefaultDir=%s"
                      % (want, e.get("Name") or "(继承)", e.get("DefaultDir") or "(根)"))
            else:
                print("  %-22s (不存在)" % want)

        # ── 6. 关键文件 ─────────────────────────────────────────
        show("6. File 表里的 exe / ico")
        for f in root.iter(NS + "File"):
            fn = (f.get("Name") or "")
            if fn.lower().endswith(".exe") or fn.lower().endswith(".ico"):
                print("  %-32s comp=%s" % (fn, f.get("Id")))

        # ── 7. 2819 风险 ───────────────────────────────────────
        if not check_dir_dialog_2819(msi):
            problems.append("MSI 里还有 InstallDirDlg —— 安装向导会报 2819")

        show("结论")
        if problems:
            for p in problems:
                print("  [问题] " + p)
            return 1
        print("  全部检查通过。")
        return 0
    finally:
        if not keep:
            shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    sys.exit(main())
