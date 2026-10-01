package com.example.classreminder.platform

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import java.awt.Window

/**
 * Windows 窗口外观：圆角 + 描边。
 *
 * ## 为什么不用「透明窗口 + 自己画圆角」
 *
 * 那条路看着更「纯 Compose」，但代价很大：`transparent = true` 会创建分层窗口，
 * Windows 对分层窗口的命中测试是**按 alpha 逐像素**的 —— 圆角切掉的那块是 alpha=0，
 * 鼠标事件会直接穿透到下面的窗口。而 [com.example.classreminder.ui.fluent.WindowResizeHandles]
 * 的四个角热区恰好就在那块区域，角上拖动缩放会整个失效。
 * 另外还会丢掉系统投影，并且透明窗口在 Windows 上一直有闪烁与性能问题。
 *
 * ## 所以走 DWM
 *
 * Windows 11（build 22000+）允许应用通过 `dwmapi!DwmSetWindowAttribute` 声明窗口外观：
 *  - `DWMWA_WINDOW_CORNER_PREFERENCE` 让 DWM 给**无边框**窗口做原生圆角，
 *    半径与其它系统窗口一致，还会带上系统投影；窗口本身仍然是不透明的，命中测试完全不变。
 *  - `DWMWA_BORDER_COLOR` 指定 1px 描边颜色，可以跟随应用主题。
 *
 * 低版本 Windows（Win10）不认识这两个属性，`DwmSetWindowAttribute` 返回 E_INVALIDARG，
 * 这里会安静地返回 false，窗口退化成直角 —— 不报错、不影响功能。
 */
object WindowEffects {

    // ── dwmapi 常量（dwmapi.h） ──────────────────────────────────────

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_WINDOW_CORNER_PREFERENCE = 33
    private const val DWMWA_BORDER_COLOR = 34

    /** 圆角偏好 */
    const val CORNER_DEFAULT = 0
    const val CORNER_DONOTROUND = 1
    const val CORNER_ROUND = 2
    const val CORNER_ROUND_SMALL = 3

    /** 描边色哨兵值：不画边框 / 交回系统决定 */
    private const val DWMWA_COLOR_NONE = 0xFFFFFFFE.toInt()
    private const val DWMWA_COLOR_DEFAULT = 0xFFFFFFFF.toInt()

    /** `DwmSetWindowAttribute(HWND, DWORD, LPCVOID, DWORD)` */
    private interface DwmApi : Library {
        fun DwmSetWindowAttribute(
            hwnd: Pointer,
            attribute: Int,
            value: IntByReference,
            size: Int
        ): Int
    }

    /** 只在 Windows 上能加载；其它平台返回 null，调用点一律走降级分支 */
    private val dwm: DwmApi? by lazy {
        runCatching { Native.load("dwmapi", DwmApi::class.java) }.getOrNull()
    }

    private val supported: Boolean by lazy { dwm != null }

    /**
     * 给窗口套上 Windows 11 原生圆角与描边。
     *
     * @param borderArgb 描边色（0xRRGGBB，忽略 alpha）。传 null 表示用系统默认描边。
     * @param dark 是否深色主题 —— 一并告诉 DWM，让系统投影 / 边框按深色渲染。
     * @return 是否成功（Win10 或非 Windows 返回 false，窗口保持直角，属预期降级）
     */
    fun applyRoundedCorners(
        window: Window,
        borderArgb: Int? = null,
        dark: Boolean = false,
        corner: Int = CORNER_ROUND
    ): Boolean {
        val api = dwm ?: return false
        // 窗口还没 realize 时拿不到 HWND，直接跳过，由调用方稍后重试
        val hwnd = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return false

        var ok = true
        // ① 圆角：无边框窗口 Win11 默认给直角，必须显式要求圆角
        ok = set(api, hwnd, DWMWA_WINDOW_CORNER_PREFERENCE, corner) && ok
        // ② 深色标记：影响 DWM 绘制的投影与默认边框
        ok = set(api, hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, if (dark) 1 else 0) && ok
        // ③ 描边色
        ok = if (borderArgb == null) {
            set(api, hwnd, DWMWA_BORDER_COLOR, DWMWA_COLOR_DEFAULT) && ok
        } else {
            set(api, hwnd, DWMWA_BORDER_COLOR, toColorRef(borderArgb)) && ok
        }
        return ok
    }

    /** 关掉描边（部分场景下想完全隐去窗口边缘） */
    fun removeBorder(window: Window): Boolean {
        val api = dwm ?: return false
        val hwnd = runCatching { Native.getWindowPointer(window) }.getOrNull() ?: return false
        return set(api, hwnd, DWMWA_BORDER_COLOR, DWMWA_COLOR_NONE)
    }

    /** DWM 当前是否可用（Windows 11 上为 true） */
    fun isSupported(): Boolean = supported

    private fun set(api: DwmApi, hwnd: Pointer, attribute: Int, value: Int): Boolean =
        runCatching {
            api.DwmSetWindowAttribute(hwnd, attribute, IntByReference(value), 4) == 0
        }.getOrDefault(false)

    /**
     * 0xRRGGBB → COLORREF（0x00BBGGRR）。
     * DWM 用的是 Win32 的 COLORREF，**字节序与 ARGB 相反**，直接传会红蓝互换。
     */
    private fun toColorRef(argb: Int): Int {
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return (b shl 16) or (g shl 8) or r
    }
}
