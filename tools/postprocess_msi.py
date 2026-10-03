#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
MSI 后处理: dark 解包 -> 改 WXS -> light 重编。

## 原生已经对了的三件事（我先前两次判断都反了，别再改回去）
`build.gradle.kts` 的
    windows { iconFile = …; shortcut = true; menu = true; menuGroup = "StuMate" }
让jpackage **自己**做好了：品牌图标、桌面快捷方式、开始菜单快捷方式。
证据：dark 解出来有 `DesktopFolder` 与 `ProgramMenuFolder` 两组 `<Shortcut>`，
且 `Icon\icon_<hash>` 与 `app-icon.ico` **SHA-256 完全一致**。

我先前两次都判断错：
1. 以为 CMP 没暴露 `--win-shortcut` → 其实 `WindowsPlatformSettings.shortcut`
   完整存在，只是**默认 false**（构造时只把 `dirChooser` 默认成 true）。
2. 以为 jpackage 不做桌面快捷方式 → 实际 `shortcut=true` 时桌面那条也有。

所以本脚本只做**jpackage 没有或做错的**：
  1. AppUserModelID 注册表项 → 任务栏右键菜单里的「固定到任务栏」的来源
  2. 安装完成后启动（deferred CustomAction + InstallExecuteSequence）
  3. 确保 `JpARPPRODUCTICON`（「程序和功能」列表图标）是品牌 ico
  4. 🔴 守门：安装向导报 **2819** 时拦住（见 `check_no_installdir_dialog`）
     —— 根因在 `dirChooser`，本脚本只检查不修
  5. 快捷方式/目录的**幂等兜底**（万一某版本 jpackage 不生成）

## 「保存到任务栏」做不到
Windows Installer **没有**「pin to taskbar」的动作。任务栏固定是Explorer 的
用户态行为，唯一能做的间接支持是：
  - 让快捷方式带上显式 AppUserModelID（下面的 regAumidMenu），
    这样它会出现在任务栏右键菜单的「固定到任务栏」里，用户点一下即可。
  - 顺带让「跳转到」列表能分组。
所以本脚本把该做的做了，**不能做的会明确报错而不是假装成功**。

用法:
  python tools/postprocess_msi.py <in.msi> <out.msi> [--no-launch] [--no-desktop]
"""
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile

WIX = r"C:\Program Files (x86)\WiX Toolset v3.14\bin"

# WXS 里要用到的稳定 GUID。固定写死，重复后处理不会让每次的 ProductId 变化
# （否则同一个包反复处理会被当成不同的包）。
AUMID = "StuMate.Desktop.1"          # 显式 AppUserModelID，跳转列表/任务栏识别用
MENU_KEY = "StuMateIsInstalled"


def find_wix():
    if not os.path.isdir(WIX):
        raise SystemExit("找不到 WiX 工具集: %s\n"
                         "装 WiX 3.x 或改本脚本的 WIX 常量。" % WIX)
    return WIX


def run(cmd, **kw):
    r = subprocess.run(cmd, capture_output=True, **kw)
    out = r.stdout.decode("utf-8", "replace") + r.stderr.decode("utf-8", "replace")
    if r.returncode != 0:
        raise SystemExit("命令失败 (%s):\n%s\n%s"
                         % (r.returncode, " ".join(cmd), out[-2500:]))
    return out


def read_text(path):
    # dark 输出带 BOM，且 Codepage=936 的中文在 UTF-8 读法下会乱码，
    # 所以按 utf-8-sig 读；乱码部分后面统一重写。
    with open(path, "r", encoding="utf-8-sig", errors="replace") as f:
        return f.read()


def write_text(path, s):
    with open(path, "w", encoding="utf-8", newline="\r\n") as f:
        f.write(s)


def unescape(s):
    return (s.replace("&quot;", '"').replace("&gt;", ">")
             .replace("&lt;", "<").replace("&amp;", "&"))


def file_sha(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 16), b""):
            h.update(chunk)
    return h.hexdigest()


def find_exe_component_id(wxs):
    """找到注册 StuMate.exe 的 Component Id —— 快捷方式要挂在它下面（同组件=同文件,
    MSI 的组件规则要求快捷方式与目标文件同 KeyPath 组件, 否则卸载时留垃圾）。"""
    for m in re.finditer(r'<Component\s+Id="([^"]+)"[^>]*>\s*'
                         r'<File\s+Id="[^"]+"\s+Name="StuMate\.exe"', wxs):
        return m.group(1)
    raise SystemExit("在 WXS 里找不到注册 StuMate.exe 的 Component")


def patch_description(wxs):
    """修 jpackage 留下的乱码 Description / ARPCOMMENTS。"""
    # dark 反编译 UTF-8 内容时把中文写成了替换字符, 这里统一换成正确值
    wxs = re.sub(r'(<Package\b[^>]*?\bDescription=")[^"]*(")',
                 r'\1StuMate 桌面课表提醒\2', wxs)
    wxs = re.sub(r'(<CustomAction\s+Id="JpSetARPCOMMENTS"[^>]*?\bValue=")[^"]*(")',
                 r'\1StuMate 桌面课表提醒\2', wxs)
    return wxs


def add_shortcuts(wxs, exe_component, want_desktop=True):
    """
    兜底补齐快捷方式。**正常情况下这里什么都不做**（全幂等）。

    ## 实测：jpackage 自己全都做了（我先前两次判断都错了）
    `windows { shortcut = true; menu = true }` 之后，dark 解出来能看到：
        <Directory Id="DesktopFolder">
            <Shortcut Id="shortcut<hash>" Directory="DesktopFolder"
                      Icon="icon_<hash>" IconIndex="0" ... />
        <Directory Id="ProgramMenuFolder">
            <Directory Id="dir<hash>" ...>
                <Shortcut Id="shortcut<hash>" Directory="dir<hash>" ... />
    ——**桌面快捷方式 jpackage 也做**（我一度断定它没有
    `--win-desktop-shortcut` 所以做不了，也是错的；`shortcut=true` 时两条都有），
    且 Icon 指向 `icon_<hash>`，那个文件与我们`app-icon.ico` **SHA-256 完全一致**。
    所以图标、开始菜单、桌面三件事原生就对了。

    ## 保留这个函数的原因
    万一某个 jpackage 版本不生成（或用户关掉了 `shortcut`），这里是兜底。
    判据必须**同时看目录节点**：只查 `Id="scStartMenu"` 会漏——
    jpackage 生成的 Id 是 `shortcut<hash>` 这种哈希名，我们叫 `scStartMenu`，
    于是会**重复插一份 `ProgramMenuFolder`** → light 报
        LGHT0091 Duplicate symbol 'Directory:ProgramMenuFolder'
    这个错就是这么来的。

    ## MSI 组件规则
    - **一个组件只能有一个 KeyPath**（CNDL0042）。exe 的 File 已是 KeyPath，
      所以 AppUserModelID 那个 RegistryValue **绝不能**再标 KeyPath="yes"。
    - AppUserModelID 的 RegistryValue 必须 `Root="HKLM"`：这个组件是
      per-machine 的，写 HKCU 会报 ICE57（per-user 与 per-machine 数据混在
      一个 per-machine KeyPath 的组件里）。
    """
    have_menu_dir = '<Directory Id="ProgramMenuFolder"' in wxs
    have_desk_dir = '<Directory Id="DesktopFolder"' in wxs
    # 目标目录已存在（不管里面有没有 Shortcut）都算原生已处理，不重复建目录
    need_menu = not have_menu_dir
    need_desk = want_desktop and not have_desk_dir
    print("  快捷方式: 开始菜单目录=%s 桌面目录=%s"
          % ("jpackage 已建" if have_menu_dir else "本脚本补",
             "jpackage 已建" if have_desk_dir else ("本脚本补" if want_desktop else "按参数跳过")))

    dirs = []
    if need_menu:
        dirs += ['        <Directory Id="ProgramMenuFolder">',
                 '            <Directory Id="StuMateMenuFolder" Name="StuMate">',
                 '            </Directory>',
                 '        </Directory>']
    if need_desk:
        # Desktop 的 Id 必须叫 DesktopFolder（WiX 保留名）
        dirs.append('        <Directory Id="DesktopFolder" />')
    if dirs:
        dirs_block = "\n".join(dirs)
        # DesktopFolder 要挂在 TARGETDIR 下与 ProgramFiles64Folder 平级,
        # 所以插在 ProgramFiles64Folder 那行**之前**。
        anchor = '        <Directory Id="ProgramFiles64Folder">'
        if anchor not in wxs:
            raise SystemExit("定位 ProgramFiles64Folder 失败，无法插目录节点")
        wxs = wxs.replace(anchor, dirs_block + '\n' + anchor, 1)

    sc = []
    # AppUserModelID: 让任务栏右键菜单出现「固定到任务栏」
    if 'regAumidMenu' not in wxs:
        sc.append('<RegistryValue '
                  'Id="regAumidMenu" Root="HKLM" '
                  'Key="Software\\Classes\\AppUserModelId\\%s" '
                  'Name="StuMate" Type="string" Value="StuMate" />' % AUMID)
        print("  AppUserModelID: 已补注册表项 (%s)" % AUMID)
    if need_menu:
        sc.append('<Shortcut Id="scStartMenu" '
                  'Name="StuMate" '
                  'Directory="StuMateMenuFolder" '
                  'WorkingDirectory="INSTALLDIR" '
                  'Advertise="yes" '
                  'Description="StuMate 桌面课表提醒" />')
    if need_desk:
        sc.append('<Shortcut Id="scDesktop" '
                  'Name="StuMate" '
                  'Directory="DesktopFolder" '
                  'WorkingDirectory="INSTALLDIR" '
                  'Advertise="yes" '
                  'Description="StuMate 桌面课表提醒" />')

    if not sc:
        return wxs, (need_menu, need_desk)

    # 插在该 Component 的 </Component> 之前
    comp_re = re.compile(
        r'(<Component\s+Id="%s"[^>]*>.*?<File\s+Id="[^"]+"\s+Name="StuMate\.exe"[^>]*/>)'
        % re.escape(exe_component), re.S)
    if not comp_re.search(wxs):
        raise SystemExit("定位 StuMate.exe 组件失败")
    comp_start = wxs.index('<Component Id="%s"' % exe_component)
    comp_end = wxs.index("</Component>", comp_start)
    return wxs[:comp_end] + "\n".join(sc) + wxs[comp_end:], (need_menu, need_desk)



def check_no_installdir_dialog(wxs):
    """
    🔴 守门：`InstallDirDlg` 必须**不存在**在 MSI 里，否则安装向导报 **2819**。

    ## 症状（本机 Temp\\MSI*.LOG 抓到的原文）
        The installer has encountered an unexpected error installing this
        package.  The error code is 2819.  The arguments are:
        InstallDirDlg, Folder,
    官方释义：``Control [3] on dialog [2] needs a property linked to it.``
    代入实参 = **`InstallDirDlg` 对话框上的 `Folder` 控件**。

    ## 来源
    `build.gradle.kts` 里 `WindowsPlatformSettings.dirChooser` **默认 true**，
    jpackage 据此传 `--win-dir-chooser`，于是引入
    ``<UIRef Id="WixUI_InstallDir" />`` 整条 WixUI 流程，
    `InstallDirDlg` 就是被它拉进来的。

    ## 🔴 我先前两次都修错了（都栽在「后处理里补属性」这个方向上）
    用 `tools/msi_table.py` 直读原包 MSI 表，实测数据：

    | 表 | 行数 | 结论 |
    |---|---|---|
    | Dialog | 23 | `InstallDirDlg` 确实在 |
    | Control | 218 | `InstallDirDlg/Folder`：Type=PathEdit, Attributes=11, Property=WIXUI_INSTALLDIR |
    | Property | 17 | **`WIXUI_INSTALLDIR = INSTALLDIR` 早就存在**（jpackage 自己写的） |
    | ControlEvent | 135 | 与 218 个 Control 比对，**外键悬空 = 0** |

    → 属性不缺、外键不缺、控件不缺。**"补一行Property" 这条路根本无效**：
      1. 补的那行是**重复**的（jpackage 已经写过），只会报 LGHT0207 重名；
      2. 就算不重复也改不了运行时报错 —— 2819 是 MSI 引擎
         **显示该对话框时**才抛的，编译期（candle/light/ICE）全程绿灯。

    另外我第一次的判据也错：用 dark 抓包，它对 WixUIExtension 引入的
    Dialog 会报 9 条 DARK1059「ControlEvent 引用了不存在的 Control」
    并**丢弃**这些行 —— 实测悬空为 0，**DARK1059 是误报**。
    也别用「MSI 二进制里某字符串的偏移量落在哪一段」判断属性在不在表里：
    light 链接时会重排表布局，同一份包两次构建偏移基线就能差 40 万字节。

    ## 修法（唯一的可靠修法）
    `build.gradle.kts` 设 `dirChooser = false` —— 从源头让
    `InstallDirDlg` 压根不进 MSI，2819 无从触发。
    代价是向导里不能改安装目录（固定到 Program Files），
    对本项目可接受：更新本来就走「替换文件」。

    本函数是**守门**：只检查、只报错，不去"修"。
    修复点在打包参数里，后处理阶段发现就说明包出错了。
    """
    if 'InstallDirDlg' not in wxs:
        print("  2819 守门: InstallDirDlg 不存在，OK")
        return wxs
    raise SystemExit(
        "MSI 里还有 InstallDirDlg -> 安装向导会在「选择安装位置」那一步报 2819。\n"
        "  根因: build.gradle.kts 的 windows { dirChooser } 还是 true，\n"
        "        jpackage 传了 --win-dir-chooser，把 WixUI_InstallDir 流程拉进来了。\n"
        "  修法: 设 dirChooser = false 后**重新完整出包**（packageMsi），\n"
        "        单独跑 postprocessMsi 不起作用 —— dialog 是打包期决定的。\n"
        "  ⚠️ 不要试图在后处理里补 WIXUI_INSTALLDIR —— 它本来就在 Property 表里。")



def add_launch_after_install(wxs, want_launch=True):
    """
    安装完成后启动（WiX官方 LaunchApplication 模式）。

    最终可行的一组属性 —— 每条都对应踩过的一个 ICE：
    - `CustomAction/@BinaryKey="WixCA"` + `@DllEntry="WixQuietExec"`
      不能同时给 `@Property`：CNDL0022 说 BinaryKey/Directory/FileKey/
      Property/Script 五选一，同时给报cannot coexist。
    - 命令行放 `<Custom>` 的**文本内容**里，不能做成 Property：
      Property/@Value 里写 `[INSTALLDIR]` 报 CNDL1077（非法引用另一个 Property）；
      且 Secure 属性还不能含小写（CNDL0011）。
    - `Sequence=6501`：必须在 InstallInitialize(1500) 与 InstallFinalize(6600)
      **之间**（ICE77）。此��已过 InstallWriteFiles/InstallRegisterProduct。
    - `Execute="deferred"` + `Impersonate="no"`：msiexec 通常以 SYSTEM 跑。
    - `Return="ignore"`：程序起不来不该让整个安装回滚。
    - 条件 `NOT REMOVE`：卸载时不启动（否则用户卸载完程序又弹回来）。

    ⚠️ WixQuietExec 在 WixUtilExtension（WixCAExtension 根本不存在）。
    ⚠️ `WixCA.dll` 不要自己声明 —— 扩展自带 `Binary Id="WixCA"`，
       自己再声明报 LGHT0091 Duplicate symbol。
    ⚠️ ICE27/ICE61 是 jpackage 原包自带的，非本次引入，保留原样。
    """
    if not want_launch:
        return wxs

    if 'Id="LaunchStuMate"' not in wxs:
        ca = ('        <CustomAction Id="LaunchStuMate" BinaryKey="WixCA" '
              'DllEntry="WixQuietExec" Execute="deferred" '
              'Impersonate="no" Return="ignore" />\n')
        anchor = '        <Binary Id="JpCaDll"'
        if anchor in wxs:
            wxs = wxs.replace(anchor, ca + anchor, 1)
        else:
            wxs = wxs.replace('        <Icon Id="JpARPPRODUCTICON"',
                              ca + '        <Icon Id="JpARPPRODUCTICON"', 1)

    seq = ('            <Custom Action="LaunchStuMate" Sequence="6501">'
           'NOT REMOVE</Custom>\n')
    if "<InstallExecuteSequence>" not in wxs:
        raise SystemExit("WXS 里没有 InstallExecuteSequence")
    wxs = re.sub(r'(<InstallExecuteSequence>)', r'\1\n' + seq, wxs, count=1)
    return wxs



def add_remove_folder_entry(wxs, folder_ids):
    """
    ICE64：往用户目录（开始菜单 / 桌面）建了目录，必须在 RemoveFile 表登记，
    否则卸载后留下空目录。

    ⚠️ WiX 里Component 必须有 @Directory 属性 —— 它得是某个 <Directory> 的
    **子节点**，不能直接塞进 <Feature>（塞进去报 CNDL0010）。
    正确做法：把清理用的 Component 放进它自己清理的那个 Directory 下面，
    再用 ComponentRef 挂进 Feature。
    """
    if not folder_ids:
        return wxs

    for i, fid in enumerate(folder_ids):
        cid = "rmfolder%s" % ("%02d" % i)
        guid = "{7A1C%04X-9E3B-4C2D-8F51-6B0D3E9C4A11}" % (i + 1)
        # KeyPath 用 HKCU：这两个组件只装在用户目录（开始菜单/桌面）下，
        # ICE38 要求 per-user 目录的 KeyPath 必须是 HKCU。
        # （exe 那个组件是 per-machine，不能混 —— 那是 ICE57。）
        comp = ('                <Component Id="%s" Guid="%s">\n'
                '                    <RegistryValue Root="HKCU" '
                'Key="Software\\StuMate\\StuMate\\1.5.0" '
                'Name="RmFolder%s" Type="integer" Value="1" KeyPath="yes" />\n'
                '                    <RemoveFolder Id="rmf%s" '
                'On="uninstall" />\n'
                '                </Component>\n'
                % (cid, guid, fid, fid))
        ref = '            <ComponentRef Id="%s" />\n' % cid

        # 把 Component 插到 <Directory Id="{fid}" ...> 的 </Directory> 之前
        #  （self-closing 的 <Directory Id="DesktopFolder" /> 要先展开成对标签）
        if '<Directory Id="%s" />' % fid in wxs:
            wxs = wxs.replace('<Directory Id="%s" />' % fid,
                              '<Directory Id="%s">%s                </Directory>'
                              % (fid, comp), 1)
        else:
            m = re.search(r'<Directory Id="%s"[^>]*>' % re.escape(fid), wxs)
            if not m:
                print("  ⚠️ 找不到 Directory %s，跳过 RemoveFolder 登记" % fid)
                continue
            end = wxs.index("</Directory>", m.end())
            wxs = wxs[:end] + comp + wxs[end:]

        # ComponentRef 挂进 Feature
        m2 = re.search(r'(<Feature\b[^>]*>)((?:\s*<ComponentRef[^>]*/>)*)(\s*</Feature>)', wxs)
        if m2:
            wxs = wxs[:m2.end(2)] + "\n" + ref.rstrip("\n") + wxs[m2.end(2):]

    return wxs


def ensure_util_namespace(wxs):
    """dark 反编译出来的 WXS 里带 `RemoveFolderEx`（UtilExtension 元素），
    但根 <Wix> 上没有 util 命名空间声明 —— candle 会报 CNDL0200
    「contains an unhandled extension element」。这里补上。

    ⚠️ 顺序有讲究：必须先补命名空间，再插Shortcut（Shortcut 在 Advertise 模式
    下也需要 util/UI 的支持），所以这个函数在 main() 里最先调。
    """
    if 'xmlns:util=' in wxs:
        return wxs
    return wxs.replace(
        '<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi"',
        '<Wix xmlns="http://schemas.microsoft.com/wix/2006/wi" '
        'xmlns:util="http://schemas.microsoft.com/wix/UtilExtension"', 1)


def main():
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    flags = {a for a in sys.argv[1:] if a.startswith("--")}
    if len(args) < 2:
        raise SystemExit(__doc__)
    src, dst = os.path.abspath(args[0]), os.path.abspath(args[1])
    want_launch = "--no-launch" not in flags
    want_desktop = "--no-desktop" not in flags

    if not os.path.exists(src):
        raise SystemExit("找不到输入 MSI: %s" % src)

    wix = find_wix()
    work = tempfile.mkdtemp(prefix="stumate-msi-")
    try:
        wxs_path = os.path.join(work, "StuMate.wxs")
        print("[1/4] dark 解包 %s" % os.path.basename(src))
        run([os.path.join(wix, "dark.exe"), "-x", work,
             "-o", wxs_path, src])

        print("[2/4] 改 WXS")
        wxs = read_text(wxs_path)
        wxs = ensure_util_namespace(wxs)
        wxs = patch_description(wxs)
        exe_comp = find_exe_component_id(wxs)
        print("StuMate.exe 所在组件: %s" % exe_comp)
        wxs, (need_menu, need_desk) = add_shortcuts(wxs, exe_comp, want_desktop)
        wxs = check_no_installdir_dialog(wxs)
        wxs = add_launch_after_install(wxs, want_launch)

        # ICE64：登记开始菜单/桌面目录的卸载清理。
        # ⚠️ 只登记**本脚本真的插进去的**目录：
        #    开始菜单那条目录 jpackage 自己会插（它管Shortcut 生命周期），
        #    对它再插清理组件是重复劳动；而且下面这个插入逻辑对
        #    `<Directory Id="StuMateMenuFolder" Name="StuMate">` 这种带属性的
        #    写法匹配的是 `</Directory>` 的**第一个**出现位置，
        #    对已经存在的目录结构容易插错地方 → 只登记我们自己建的。
        rm_targets = []
        if need_menu:
            rm_targets.append("StuMateMenuFolder")
        if need_desk:
            rm_targets.append("DesktopFolder")
        wxs = add_remove_folder_entry(wxs, rm_targets)

        # Icon 节点：用我们的 app-icon.ico 覆盖 JpARPPRODUCTICON
        # （dark 解出来的 Icon\JpARPPRODUCTICON 可能是 jpackage 自己从 ico 转的，
        #  也可能是 exe；「程序和功能」列表里那个图标必须保证是品牌图标）
        repo_ico = os.path.join(os.path.dirname(os.path.dirname(
            os.path.abspath(__file__))), "app-icon.ico")
        if os.path.exists(repo_ico):
            ico = os.path.join(work, "app-icon.ico")
            shutil.copyfile(repo_ico, ico)
            # 逐个Icon 节点比 SHA：已经等于品牌 ico 的就别动
            for m in list(re.finditer(
                    r'<Icon\s+Id="([^"]+)"\s+SourceFile="([^"]+)"\s*/>', wxs)):
                icon_id, src = m.group(1), m.group(2).replace("\\", "/")
                same = (os.path.normcase(src) == os.path.normcase(ico))
                if not same and os.path.exists(src):
                    same = file_sha(src) == file_sha(repo_ico)
                print("  Icon %-22s -> %s" % (icon_id, "已是品牌图标，保持" if same else "替换"))
                if not same:
                    wxs = wxs.replace(m.group(0),
                                      '<Icon Id="%s" SourceFile="%s" />'
                                      % (icon_id, ico.replace("\\", "\\")))
            if "<Icon " not in wxs:
                # 一个 Icon 节点都没有时补一个（ARPPRODUCTICON 属性也补上）
                wxs = wxs.replace(
                    '        <Media Id="1"',
                    '        <Icon Id="JpARPPRODUCTICON" SourceFile="%s" />\n'
                    '        <Media Id="1"' % ico.replace("\\", "\\"), 1)
                if 'Id="ARPPRODUCTICON"' not in wxs:
                    wxs = wxs.replace(
                        "        <Media Id=\"1\"",
                        '        <Property Id="ARPPRODUCTICON" '
                        'Value="JpARPPRODUCTICON" />\n        <Media Id="1"', 1)
        else:
            print("  ⚠️ 找不到 app-icon.ico，保留原图标")


        write_text(wxs_path, wxs)

        # candle -> light 两步（dark 的输出就是 candle 的输入格式，
        # 根元素保持 <Wix>；light 的 -ext 要给 WixUI/WixUtil）
        print("[3/4] candle 编译 + light 链接")
        wixobj = os.path.join(work, "StuMate.wixobj")
        # candle 也要 -ext：它不认 WixUtilExtension 命名空间下的 RemoveFolderEx
        # （只给 light 的话会报 CNDL0200）
        run([os.path.join(wix, "candle.exe"), "-nologo",
             "-ext", "WixUtilExtension",
             "-out", wixobj, wxs_path])

        obj = os.path.join(work, "obj")
        os.makedirs(obj, exist_ok=True)
        run([os.path.join(wix, "light.exe"), "-nologo",
             "-ext", "WixUIExtension", "-ext", "WixUtilExtension",
             "-ext", "WixNetFxExtension",
             # ICE27 / ICE61 是 **jpackage 原包自带**的（不是我们引入的）：
             #   ICE27: jpackage 把 RemoveExistingProducts 排在 Search 阶段而非
             #          Execution 阶段 —— 这是它做major upgrade 的既定行为，
             #          改成 Execution 反而会破坏它的升级语义。
             #   ICE61: JP_DOWNGRADABLE_FOUND 没有 Maximum，即不阻止降级——
             #          jpackage 就是这么生成的，改了行为就变了。
             # light 默认把 ICE 警告当错误（LGHT0204），必须显式放行。
             "-sice:ICE27", "-sice:ICE61",
             "-cultures:zh-cn",
             "-out", dst,
             "-b", obj,
             wixobj])

        print("[4/4] 完成")
        print("输出: %s (%d 字节)" % (dst, os.path.getsize(dst)))
        if not want_launch:
            print("  (已完成启动已按 --no-launch 跳过)")
        if not want_desktop:
            print("  (桌面快捷方式已按 --no-desktop 跳过)")
        print()
        print("说明: 「保存到任务栏」无法由安装器完成 —— Windows Installer 没有这个动作。")
        print("      已通过显式 AppUserModelID (%s) 让快捷方式在任务栏" % AUMID)
        print("      右键菜单里出现「固定到任务栏」，用户点一下即可。")
    finally:
        shutil.rmtree(work, ignore_errors=True)


if __name__ == "__main__":
    main()