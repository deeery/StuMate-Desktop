package com.example.classreminder.platform

import java.io.File

/**
 * 开机自启，替代安卓的 `BootReceiver` + `RECEIVE_BOOT_COMPLETED`。
 *
 * Windows 上最省事、且不需要管理员权限的做法是写用户级注册表 Run 键：
 * `HKCU\Software\Microsoft\Windows\CurrentVersion\Run`。登录时由系统自动拉起。
 *
 * 用 `reg.exe` 而不是 JNA/`java.util.prefs`：`reg` 是系统自带命令，零依赖；
 * 而 `java.util.prefs` 在 Windows 上会把键写到 `HKCU\Software\JavaSoft\Prefs\...`
 * 这种带转义前缀的位置，不是系统认可的启动项，写了也不会自启。
 */
object AutoStart {

    private const val RUN_KEY = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Run"
    private const val VALUE_NAME = "StuMate"

    /** 当前进程的启动命令；打包后指向 app-image 里的 exe，开发期指向 java.exe */
    private fun launchCommand(): String? {
        // jpackage 生成的启动器会注入这个属性
        System.getProperty("jpackage.app-path")?.let { return "\"$it\"" }

        val javaHome = System.getProperty("java.home") ?: return null
        val javaw = File(javaHome, "bin/javaw.exe").takeIf { it.isFile } ?: return null
        val classpath = System.getProperty("java.class.path") ?: return null
        return "\"${javaw.absolutePath}\" -cp \"$classpath\" com.example.classreminder.MainKt"
    }

    fun isEnabled(): Boolean = runCatching {
        val process = ProcessBuilder("reg", "query", RUN_KEY, "/v", VALUE_NAME)
            .redirectErrorStream(true)
            .start()
        process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor() == 0
    }.getOrDefault(false)

    /**
     * 写入 / 删除自启项。
     * 返回是否成功；调用方负责把结果提示给用户（失败通常是权限或环境异常）。
     */
    fun setEnabled(enabled: Boolean): Boolean = runCatching {
        val command = if (enabled) {
            val target = launchCommand() ?: return@runCatching false
            listOf("reg", "add", RUN_KEY, "/v", VALUE_NAME, "/t", "REG_SZ", "/d", target, "/f")
        } else {
            listOf("reg", "delete", RUN_KEY, "/v", VALUE_NAME, "/f")
        }
        val process = ProcessBuilder(command).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor() == 0
    }.getOrDefault(false)
}
