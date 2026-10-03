# -*- coding: utf-8 -*-
"""
把 dark 解出来的哈希名文件还原成 MSI 安装后的目录树。

## 为什么需要它
`msiexec /a <msi> /qn TARGETDIR=…` 是最直接的验证手段，但本机**用不了**：
    rc=1619，/l*v 日志里 Note 1: 2203 C:\WINDOWS\Installer\inprogressinstallinfo.ipi
即 Windows Installer 服务被上一次没收尾的安装占着 —— 那是**服务级状态**，
杀 msiexec 进程解不开，要重启 `msiserver` 服务或机器。
在不能重启机器的验收环境里，得有第二条路。

## 这条路
`dark -x` 已经把 MSI 里所有 File / Icon / Binary 流都解到磁盘，只是名字被换成
`file<hash>`。而 dark 同时输出的 WXS 里记着每个 File 的 `Name` 以及它的目录归属，
照着把文件挪回去，得到的目录树与安装后**逐文件一致**（内容本来就是同一批流）。

用途：拿还原出来的 `StuMate.exe` 直接跑，验证「换图标没破坏启动」。

## 🔴 Component 怎么找到它所属的目录（这里踩了两次）
WXS 里 **Component 没有 `@Directory` 属性**。dark 的结构是：
    <Directory Id="dir…INSTALLDIR" Name="StuMate">
      <Directory Id="dir…app" Name="app">
        <Directory Id="dir…resources" Name="resources">
          <Component Id="cfile…">
            <File Id="file…" Name="…" Source="…/File/file…" />
按 `Component/@Directory` 查 → **一个都查不到**，197 个文件被全平铺到目标根目录，
`runtime/lib/modules` 根本不存在。一眼看着像「MSI 里没有 runtime」，其实是映射写错。
→ 只能**递归下降真实树**：从顶层 Directory 往下走，每个 Directory 的直接
   `<Component>` 子节点属于它自己，`File` 就落在这一层。

用法:
  python tools/unpack_msi.py <msi> <目标目录>
"""
import os
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET

WIX = r"C:\Program Files (x86)\WiX Toolset v3.14\bin"
NS = "{http://schemas.microsoft.com/wix/2006/wi}"


def has_parent_dir(el, parent_map):
    return el.get("Id") in parent_map


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    msi, dst = os.path.abspath(sys.argv[1]), os.path.abspath(sys.argv[2])

    work = os.path.join(os.path.dirname(dst), "_dark_" + os.path.basename(dst))
    if os.path.isdir(work):
        shutil.rmtree(work, ignore_errors=True)
    os.makedirs(work, exist_ok=True)

    wxs = os.path.join(work, "out.wxs")
    print("[1/3] dark 解包 %s" % os.path.basename(msi))
    subprocess.run([os.path.join(WIX, "dark.exe"), "-x", work, "-o", wxs, msi],
                   capture_output=True)
    root = ET.parse(wxs).getroot()

    print("[2/3] 按 WXS 目录树还原文件")
    # 记录每个 Directory 的父，用它来识别顶层（TARGETDIR）
    parent_map = {}
    for d in root.iter(NS + "Directory"):
        for sub in d.findall(NS + "Directory"):
            parent_map[sub.get("Id")] = d.get("Id")

    n_file = n_skip = 0

    def walk_dir(d_el, rel):
        """把该 Directory 直接持有的 Component 的 File 落到 rel 下，再递归子目录。"""
        nonlocal n_file, n_skip
        for c in d_el.findall(NS + "Component"):
            for f in c.findall(NS + "File"):
                src = f.get("Source", "")
                name = f.get("Name") or ""
                if not src or not name:
                    n_skip += 1
                    continue
                src_abs = os.path.join(work, src.replace("\\", "/"))
                if not os.path.exists(src_abs):
                    n_skip += 1
                    continue
                target_dir = os.path.join(dst, rel) if rel else dst
                os.makedirs(target_dir, exist_ok=True)
                shutil.copyfile(src_abs, os.path.join(target_dir, name))
                n_file += 1
        for sub in d_el.findall(NS + "Directory"):
            sub_name = sub.get("Name") or ""
            walk_dir(sub, os.path.join(rel, sub_name) if (rel and sub_name)
                     else (rel or sub_name))

    tops = [d for d in root.iter(NS + "Directory")
            if not has_parent_dir(d, parent_map)]
    for d in tops:
        # TARGETDIR 的 Name 是 "SourceDir"（管理安装的源名），不是安装路径的一部分，
        # 落到磁盘上应该是 %ProgramFiles%\\StuMate —— 即 INSTALLDIR 的 Name。
        # 所以顶层一律传空 rel，让子目录自己决定名字。
        walk_dir(d, "")

    # Icon / Binary 流单独落到 _msi_*便于核对图标
    for sub in ("Icon", "Binary"):
        s = os.path.join(work, sub)
        if os.path.isdir(s):
            shutil.copytree(s, os.path.join(dst, "_msi_" + sub), dirs_exist_ok=True)

    print("[3/3] 完成：还原 %d 个文件（跳过 %d 个无名/缺失的流）" % (n_file, n_skip))
    print("目标: %s" % dst)
    shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()
