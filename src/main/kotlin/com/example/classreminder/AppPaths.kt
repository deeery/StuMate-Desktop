package com.example.classreminder

import java.io.File

/**
 * 应用的数据目录。
 *
 * 默认放 `%APPDATA%\StuMate\`（Windows 标准位置，卸载/重装不影响数据）；
 * 若 `APPDATA` 取不到（非 Windows 或裁剪过的环境），退回 `~/.stumate/`。
 */
object AppPaths {

    /** 数据根目录，首次访问时自动创建 */
    val dataDir: File by lazy {
        val base = System.getenv("APPDATA")?.takeIf { it.isNotBlank() }
            ?: System.getProperty("user.home")
        File(base, "StuMate").apply { mkdirs() }
    }

    /** SQLite 数据库文件，与安卓端同名 */
    val dbFile: File get() = File(dataDir, "class_reminder.db")

    /** 偏好设置 JSON */
    val settingsFile: File get() = File(dataDir, "settings.json")

    /** 用户数据目录（导出备份时的默认落点） */
    val backupDir: File get() = File(dataDir, "backup").apply { mkdirs() }

    /** 打开数据目录（设置页「打开数据目录」按钮） */
    fun openInExplorer(): Boolean = try {
        val desktop = java.awt.Desktop.getDesktop()
        if (desktop.isSupported(java.awt.Desktop.Action.OPEN)) {
            desktop.open(dataDir)
            true
        } else false
    } catch (t: Throwable) {
        t.printStackTrace()
        false
    }
}
