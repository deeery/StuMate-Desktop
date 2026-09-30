package com.example.classreminder.platform

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import java.io.File
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter

/**
 * 桌面端的文件选择器，替代安卓的 SAF（`ActivityResultContracts.OpenDocument` / `CreateDocument`）。
 *
 * 用 `JFileChooser` 而不是 `java.awt.FileDialog`：后者的扩展名过滤在 Windows 上很弱，
 * 而课表导入需要「只看 PDF」、备份需要「默认名 + .json」。
 *
 * 必须在 Swing 事件线程上弹窗，所以统一走 `Dispatchers.Swing`。
 */
object DesktopFileDialogs {

    /**
     * 选一个已存在的文件。[extensions] 为空表示不过滤。
     * 用户取消返回 null。
     */
    suspend fun openFile(title: String, extensions: List<String> = emptyList()): File? =
        withContext(Dispatchers.Swing) {
            val chooser = JFileChooser().apply {
                dialogTitle = title
                fileSelectionMode = JFileChooser.FILES_ONLY
                isMultiSelectionEnabled = false
                isAcceptAllFileFilterUsed = true
                if (extensions.isNotEmpty()) {
                    fileFilter = FileNameExtensionFilter(
                        extensions.joinToString(" / ") { it.uppercase() },
                        *extensions.toTypedArray()
                    )
                }
                // 默认落在用户的数据目录旁边，方便找刚导出的备份
                currentDirectory = defaultDirectory()
            }
            if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile
            } else null
        }

    /**
     * 选一个保存位置。[defaultName] 是预填的文件名。
     * 用户取消返回 null。
     */
    suspend fun saveFile(title: String, defaultName: String): File? =
        withContext(Dispatchers.Swing) {
            val chooser = JFileChooser().apply {
                dialogTitle = title
                fileSelectionMode = JFileChooser.FILES_ONLY
                isAcceptAllFileFilterUsed = true
                selectedFile = File(defaultDirectory(), defaultName)
            }
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile
            } else null
        }

    private fun defaultDirectory(): File {
        val downloads = File(System.getProperty("user.home") ?: ".", "Downloads")
        return when {
            downloads.isDirectory -> downloads
            else -> File(System.getProperty("user.home") ?: ".")
        }
    }
}
