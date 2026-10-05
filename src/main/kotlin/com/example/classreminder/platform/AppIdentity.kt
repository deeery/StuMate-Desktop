package com.example.classreminder.platform

import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference

/**
 * 进程的 **AppUserModelID（AUMID）** 与应用显示名 —— 决定托盘气泡左上角
 * 「这条通知是哪个应用发的」那一行显示什么。
 *
 * ## 🔴 实测更正：气泡弹不弹，和 AUMID 无关
 *
 * 本文件早期版本写着「jpackage 启动器不带 AUMID → Windows 11 拒绝显示托盘气泡，
 * 调用不报错但屏幕上什么都不出现」。**这个说法是错的**，已用
 * [com.example.classreminder.dev.TrayProbeKt] 逐项对照推翻
 * （Win11 26200，打包同款 jlink 运行时，2026-10-05）：
 *
 * | 场景 | 气泡 | 标题那一行 |
 * |---|---|---|
 * | 不设 AUMID | **照弹** | `OpenJDK Platform binary`（宿主 exe 的文件描述） |
 * | 设 AUMID，但没注册显示名 | 照弹 | 原始 AUMID 字符串 |
 * | 设 AUMID + 注册 `DisplayName` | 照弹 | `StuMate` |
 *
 * 同一批对照还推翻了「设 AUMID 必须早于任何窗口」：先建一个真实原生窗口
 * （`setVisible(true)`，不是只 `setSize`）再调 `install()`，气泡依然正常。
 *
 * 所以**真正要修的只有「显示名」**。用户报的「中文显示成乱码」就是上表第二行：
 * 气泡上顶着一串机器标识符（`StuMate.Desktop.1`）—— 严格说不是乱码，
 * 但用户只能这么描述。
 *
 * ## 显示名从哪来
 *
 * Shell 去 `HK[LM|CU]\SOFTWARE\Classes\AppUserModelId\<AUMID>` 下找**值名恰好是
 * `DisplayName`** 的字符串来当应用名；找不到就退回显示 AUMID 本身、
 * 或者进程宿主 exe 的文件描述。`tools/postprocess_msi.py` 里那一项
 * **值名写成了 `StuMate`**（不是 `DisplayName`），等于没注册 ——
 * 走 MSI 安装的用户必然中招。那个脚本已一并修正。
 *
 * 这里在**进程内**再写一份 `HKCU` 的，覆盖三种安装方式：
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
     * 设置 AUMID + 注册应用显示名。建议在 `main()` 最前面调（见 `Main.kt`），
     * 但**不必**为「必须早于建窗口」而紧张 —— 那一条实测复现不出来，见类注释。
     *
     * 两步都是「失败就静默降级」：拿不到 shell32/advapi32（非 Windows、
     * 被裁剪的运行时）、或注册表被策略锁住时，应用该照常起来 ——
     * 最坏结果只是气泡标题退化成机器标识符，不能因此让整个软件起不来。
     *
     * @return true = 两步都成功
     */
    fun install(): Boolean {
        val aumidOk = installAumid()
        val nameOk = registerDisplayName()
        return aumidOk && nameOk
    }

    /**
     * 只设 AUMID，**不**注册显示名。
     *
     * ⚠️ **只给 `dev/TrayProbe.kt` 的对照组用**（`-Dstumate.probe.noDisplayName=1`）——
     * 它复现的是修复前用户的处境，用来确认气泡标题会退化成什么。
     * 生产路径永远走 [install]。
     */
    fun installAumidOnly(): Boolean = installAumid()

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
