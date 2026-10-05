package com.example.classreminder.data.update

/**
 * Release 上的一个下载资产（本项目只关心补丁包那一个）。
 *
 * 之所以是 `public` 而不是 `internal`：它出现在 [UpdateState.Available] 的属性里，
 * 而 Kotlin 不允许 public 函数暴露 internal 类型。为它把整个状态机降级成 internal
 * 更不划算 —— 状态机是要给 UI 层用的。
 *
 * @param sha256 GitHub 新版 API 会带 `digest` 字段；拿不到时为 null（老 Release 没有），
 *        此时跳过校验而不是报错 —— 宁可少一层校验，也不能因此装不上。
 */
data class UpdateAsset(
    val name: String,
    val url: String,
    val size: Long,
    val sha256: String?
)

/**
 * 更新检查的状态机。
 *
 * 刻意把「装不了」也做成一个**正常状态**（[UpdateState.NeedsFullPackage]）而不是异常：
 * 装在 Program Files 下、或者这个版本动了依赖，都不是 bug，是预期内的分支。
 * 用异常表达会让 UI 只能显示一句报错，而用户需要的是「那我该怎么办」。
 */
sealed interface UpdateState {

    /** 还没查过 */
    object Idle : UpdateState

    object Checking : UpdateState

    /** 已是最新。带着当前版本号，设置页可以直接显示「1.6.0 已是最新」 */
    data class UpToDate(val current: String) : UpdateState

    /**
     * 有新版本。
     *
     * @param patch 补丁包；为 null 说明这个 Release 没挂补丁（老版本可能只有整包）
     * @param selfInstallBlocked 非 null 表示**一定**装不了（如开发模式），
     *        此时 UI 只给「打开下载页」，不给「立即更新」按钮
     */
    data class Available(
        val version: String,
        val notes: String,
        val pageUrl: String,
        val patch: UpdateAsset?,
        val selfInstallBlocked: String?
    ) : UpdateState {
        val canSelfInstall: Boolean get() = patch != null && selfInstallBlocked == null
    }

    data class Downloading(val received: Long, val total: Long) : UpdateState {
        /** 0~100；总长度未知时返回 -1（UI 显示不确定进度） */
        val percent: Int get() = if (total <= 0) -1 else ((received * 100) / total).toInt()
    }

    /** 文件已替换完，等用户重启 */
    data class RestartPending(val version: String) : UpdateState

    /** 自己装不了，得走整包。reason 是给人看的 */
    data class NeedsFullPackage(
        val version: String,
        val reason: String,
        val pageUrl: String
    ) : UpdateState

    data class Failed(val message: String) : UpdateState
}
