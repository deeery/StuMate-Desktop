package com.example.classreminder.ui.fluent

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.unit.dp

/**
 * GitHub 官方标识（Octocat 剪影）。
 *
 * ## 为什么要手写矢量，而不是加依赖
 *
 * `material-icons-core` 里没有品牌图标（GitHub / Google / 微信都不在 Material 图标集里，
 * 那些在 `material-icons-extended`），而本项目的构建是 `--offline` 的，加不了新依赖。
 * 于是直接把官方 SVG 的 `path` 数据抄成 [ImageVector] —— 它是纯几何数据，
 * 不需要网络、不需要资源文件、不增加任何构建负担。
 *
 * 路径取自 GitHub 官方 `mark-github.svg`，viewBox 16×16，
 * 单一 path、非零填充规则，所以能直接被 [androidx.compose.material3.Icon] 的
 * `ColorFilter.tint` 整体染色 —— 深色主题下不会出现「黑猫压在深色底上看不见」。
 */
val GitHubMark: ImageVector by lazy {
    ImageVector.Builder(
        name = "GitHubMark",
        defaultWidth = 16.dp,
        defaultHeight = 16.dp,
        viewportWidth = 16f,
        viewportHeight = 16f
    ).apply {
        addPath(
            pathData = addPathNodes(GITHUB_MARK_PATH),
            // 实际颜色由调用方 Icon(tint = …) 覆盖，这里给什么都无所谓
            fill = SolidColor(Color.Black)
        )
    }.build()
}

private const val GITHUB_MARK_PATH =
    "M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 " +
        "0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13" +
        "-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66" +
        ".07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15" +
        "-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27" +
        "1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 " +
        "0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 " +
        "0 .21.15.46.55.38A8.01 8.01 0 0 0 16 8c0-4.42-3.58-8-8-8Z"
