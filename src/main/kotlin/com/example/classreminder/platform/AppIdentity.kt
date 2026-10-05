package com.example.classreminder.platform

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/**
 * 进程的 **AppUserModelID（AUMID）** —— Windows 用它把「一个进程」和
 * 「一个已安装的应用」对起来。
 *
 * ## 为什么必须显式设置
 *
 * jpackage 打出来的启动器 `StuMate.exe` **本身不带 AUMID**（实测：整个 exe 里
 * 搜不到任何 AUMID 字样）。不设的后果不是「难看」，是**功能直接没了**：
 * Windows 11 的 Shell 拒绝为没有身份标识的进程显示托盘气泡 ——
 * `TrayIcon.displayMessage` 调用返回正常，**屏幕上什么都不出现**。
 * 实测对照（Win11 26200，打包同款 jlink 运行时，见 `dev/TrayProbe.kt`）：
 *  - 不设 AUMID → 气泡完全不弹
 *  - 设了 AUMID → 气泡正常弹出
 *
 * ## 为什么还要写注册表（`DisplayName`）
 *
 * **这才是「中文显示成乱码」的真正来源。** 只设 AUMID 时气泡是能弹的，
 * 但标题那一行会显示**原始的 AUMID 字符串**：
 *
 * ```
 * ┌──────────────────────────────────┐
 * │ StuMate.Desktop.1            …  ×│   ← 这一行不是给人看的
 * │  ⓘ  即将上课：高等数学            │
 * │     教二 305  08:00 - 09:35      │
 * └──────────────────────────────────┘
 * ```
 *
 * 用户看到的就是 `StuMate.Desktop.1` —— 一个机器标识符，只能描述成「乱码」。
 *
 * 原因是 Shell 去 `HK[LM|CU]\SOFTWARE\Classes\AppUserModelId\<AUMID>` 下找
 * **值名恰好是 `DisplayName`** 的字符串来当应用名；找不到就退回显示 AUMID 本身。
 * `tools/postprocess_msi.py` 里那一项**值名写成了 `StuMate`**（不是 `DisplayName`），
 * 等于没注册 —— 所以走 MSI 安装、从快捷方式启动（快捷方式带 AUMID）的用户
 * 一定会看到那串原始 AUMID。那个脚本的写法已一并修正。
 *
 * 这里在**进程内**写一份 `HKCU` 的，覆盖三种安装方式：
 *  - 便携包（`StuMate.exe` 直接双击）—— 没有 MSI 去写 HKLM，只能靠这里
 *  - `gradlew run` / IDE 里跑 —— 同上
 *  - MSI 安装 —— `HKCU` 优先级高于 `HKLM`，写同一份内容，结果一致
 *
 * `HKCU` 不需要管理员权限；`HKLM` 那份仍然由安装器负责（别的用户账户也能受益）。
 *
 * ## 为什么用这个值
 *
 * [AUMID] 必须与 [tools/postprocess_msi.py] 里的 `AUMID` 常量**逐字一致**，
 * [DISPLAY_NAME] 必须与那里 `DisplayName` 的值一致。
 * 不一致**不会有任何报错**，只会静默退化成「显示原始 AUMID」——
 * 一致性由 `AppIdentityTest` 跨文件断言，不靠人记。
 *
 * ## 为什么走 JNA
 *
 * 这两个 API 都没有 Java 绑定。JNA 已经是本项目的直接依赖，
 * `WindowEffects.kt`（user32）与 `SecretStore.kt`（advapi32）走的就是同一条路。
 * x64 上只有一种调用约定，所以直接继承 `Library` 即可（与那两个文件一致）。
 */
object AppIdentity {

    /** 与 `tools/postprocess_msi.py` 的 `AUMID` 常量必须逐字一致 */
    const val AUMID = "StuMate.Desktop.1"

    /** 气泡标题 / 任务栏 / 跳转列表里显示的应用名。与 `postprocess_msi.py` 的 `DISPLAY_NAME` 一致 */
    const val DISPLAY_NAME = "StuMate"

    private interface Shell32 : Library {
        /**
         * 设置当前进程的显式 AUMID。
         *
         * @return HRESULT，`0`（S_OK）为成功
         */
        fun SetCurrentProcessExplicitAppUserModelID(appId: WString): Int
    }

    /**
     * 注册表读写。只用到三个函数，所以不去引 `jna-platform`
     * （那是一个几 MB 的额外依赖，只为 `Advapi32Util` 一个辅助类不值得）。
     */
    private interface Advapi32 : Library {
        fun RegCreateKeyExW(
            hKey: Pointer,
            subKey: WString,
            reserved: Int,
            /** `LPWSTR lpClass`，恒传 null */
            cls: Pointer?,
            options: Int,
            samDesired: Int,
            /** `LPSECURITY_ATTRIBUTES`，恒传 null（继承默认安全描述符） */
            secAttr: Pointer?,
            result: PointerByReference,
            disposition: IntByReference
        ): Int

        fun RegSetValueExW(
            hKey: Pointer,
            valueName: WString,
            reserved: Int,
            type: Int,
            data: Pointer,
            cbData: Int
        ): Int

        fun RegCloseKey(hKey: Pointer): Int
    }

    // HKEY_CURRENT_USER 是个**伪句柄常量**，不是真的指针 —— 直接按值传。
    private const val HKEY_CURRENT_USER = 0x80000001L
    private const val KEY_WRITE = 0x20006
    private const val REG_SZ = 1

    private val shell32: Shell32? by lazy {
        runCatching { Native.load("shell32", Shell32::class.java) }.getOrNull()
    }

    private val advapi32: Advapi32? by lazy {
        runCatching { Native.load("advapi32", Advapi32::class.java) }.getOrNull()
    }

    /**
     * 设置 AUMID + 注册应用显示名。**必须在创建任何窗口/托盘图标之前调用** ——
     * Shell 是在窗口登记那一刻把进程身份定下来的，之后再改对已建好的窗口无效。
     *
     * 两步都是「失败就静默降级」：拿不到 shell32/advapi32（非 Windows、
     * 被裁剪的运行时）、或注册表被策略锁住时，应用该照常起来 ——
     * 最坏结果只是气泡标题显示成 AUMID，不能因此让整个软件起不来。
     *
     * @return true = 两步都成功
     */
    fun install(): Boolean {
        val aumidOk = installAumid()
        val nameOk = registerDisplayName()
        return aumidOk && nameOk
    }

    private fun installAumid(): Boolean {
        val api = shell32 ?: return false
        return runCatching {
            api.SetCurrentProcessExplicitAppUserModelID(WString(AUMID)) == 0
        }.getOrDefault(false)
    }

    /**
     * 把 `HKCU\SOFTWARE\Classes\AppUserModelId\<AUMID>` 的 `DisplayName` 写成应用名。
     *
     * 少了这一步，气泡标题就是原始 AUMID —— 用户会当成乱码。见类注释。
     */
    private fun registerDisplayName(): Boolean = runCatching {
        writeHkcuString(
            "SOFTWARE\\Classes\\AppUserModelId\\$AUMID",
            "DisplayName",
            DISPLAY_NAME
        )
    }.getOrDefault(false)

    /** 建键（已存在则打开）并写一个 `REG_SZ` 值 */
    private fun writeHkcuString(subKey: String, name: String, value: String): Boolean {
        val api = advapi32 ?: return false
        val handle = PointerByReference()
        val disposition = IntByReference()
        val rc = api.RegCreateKeyExW(
            Pointer(HKEY_CURRENT_USER), WString(subKey), 0, null,
            0, KEY_WRITE, null, handle, disposition
        )
        if (rc != 0) return false

        val key = handle.value
        try {
            // RegSetValueExW 收的是**字节数**（不是字符数），且要含结尾的 NUL。
            // 长度算错只会写进去半截字符串，不报错 —— 所以这里显式 +1 个 NUL。
            val bytes = (value + "\u0000").toByteArray(Charsets.UTF_16LE)
            val mem = Memory(bytes.size.toLong())
            mem.write(0, bytes, 0, bytes.size)
            return api.RegSetValueExW(key, WString(name), 0, REG_SZ, mem, bytes.size) == 0
        } finally {
            api.RegCloseKey(key)
        }
    }
}
