package com.example.classreminder.data.update

import com.example.classreminder.BuildConfig
import com.example.classreminder.data.backup.JsonValue
import com.example.classreminder.data.backup.MiniJson
import com.example.classreminder.data.backup.array
import com.example.classreminder.data.backup.long
import com.example.classreminder.data.backup.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration
import java.util.zip.ZipInputStream

/**
 * 基于 GitHub Releases 的更新检测与「就地替换」。
 *
 * ## 为什么不需要内置 token
 * 这个仓库是 **public**，`api.github.com/repos/.../releases/latest` 匿名可读。
 * 把 PAT 打进客户端等于把密码发出去，绝对不做。代价只是匿名限流
 * 60 次/小时/IP —— 而「启动时查一次 + 手动点一下」远远够用。
 *
 * ## 为什么能「就地替换」而不用 helper 进程
 * 桌面端升级只需要动两个文件：主 jar 和 `app/StuMate.cfg`（其余 37 个依赖 jar 与
 * `runtime/` 跨版本逐字节不变，见 `docs/desktop-update-strategy.md`）。
 *
 * - 新 jar 的**文件名带新版本号** → 与正在运行的旧 jar 不冲突，可以直接写进去；
 * - `StuMate.cfg` 只在启动时被 jpackage launcher 读一次，**不会被常驻持有** → 可以覆盖。
 *
 * 所以「下载 → 落盘 → 改 cfg」三步在**进程活着的时候**就能全部做完，
 * 只要最后重启一次即可。**不需要起 .bat / 辅助 JVM** —— 那类方案在中文路径、
 * 编码、杀软拦截上全是坑。
 *
 * ## 什么时候做不到
 * - 装在 `%ProgramFiles%` 下 → 写 `app/` 要管理员权限 → `AccessDeniedException`
 *   → 降级为 [UpdateState.NeedsFullPackage]（引导去下载页）。
 * - 新版本改了**依赖**（不只是主 jar）→ 补丁包覆盖不了 → 同样降级为整包。
 *   这条由 [dependencyLines] 比对把关，**不靠人记得**。
 */
object UpdateCenter {

    /** 公开仓库，匿名读 */
    private const val LATEST_URL =
        "https://api.github.com/repos/deeery/StuMate-Desktop/releases/latest"

    private const val USER_AGENT = "StuMate-Desktop-Updater"

    /** 主 jar 文件名前缀。jpackage 打出来的名字是 `StuMate-Desktop-<版本>-<hash>.jar` */
    private const val MAIN_JAR_PREFIX = "StuMate-Desktop-"

    val currentVersion: String get() = VERSION_OVERRIDE ?: BuildConfig.VERSION

    /**
     * 验收工具用：假装自己是别的版本。
     *
     * 「有新版本」这条路径**没法靠摆拍验证** —— 得真的有一个比自己高的 Release
     * 才会走到。所以截图脚本把当前版本压到比线上低一档（`-Dstumate.currentVersion=1.5.9`），
     * 于是检测、下载、替换、重启整条链路跑的都是真代码。
     *
     * 生产调用点不设这个属性，取值恒为 null。
     */
    private val VERSION_OVERRIDE: String?
        get() = System.getProperty("stumate.currentVersion")?.takeIf { it.isNotBlank() }

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build()
    }

    // ── 安装形态识别 ────────────────────────────────────────────────

    /** 一次「就地替换」要碰的东西 */
    internal data class InstallLayout(
        val root: File,
        val appDir: File,
        val cfg: File,
        val launcher: File,
        val jarName: String
    )

    /**
     * 从**自身 class 的来源路径**反推安装目录。
     *
     * 开发态（`./gradlew run`）class 来自 `build/classes/kotlin/main` 这种目录，
     * 不是 jar → 返回 null → 上层显示「开发模式，不适用」。
     * 这比写死路径可靠：换 buildDirectory 打包、绿色版放任意目录都能认出来。
     */
    internal fun detectInstall(): InstallLayout? {
        val source = UpdateCenter::class.java.protectionDomain?.codeSource?.location ?: return null
        val self = runCatching { File(source.toURI()) }.getOrNull() ?: return null
        if (!self.isFile || !self.name.endsWith(".jar")) return null
        val appDir = self.parentFile ?: return null
        val root = appDir.parentFile ?: return null
        val cfg = File(appDir, "StuMate.cfg")
        val launcher = File(root, "StuMate.exe")
        if (!cfg.isFile || !launcher.isFile) return null
        return InstallLayout(root, appDir, cfg, launcher, self.name)
    }

    /** 能不能自己装。装不了时给出人话理由 */
    fun selfInstallBlocker(): String? {
        val layout = detectInstall() ?: return "开发模式下不适用（当前不是从安装目录启动）"
        if (!layout.appDir.canWrite()) return "安装目录不可写（可能装在 Program Files 下），需要管理员权限"
        return null
    }

    // ── 检查 ────────────────────────────────────────────────────────

    /**
     * 查最新 Release。
     *
     * @param manual 用户主动点的。
     *   - `true`：失败要如实回执（「检查失败 + 原因」），用户点了就得给答案；
     *   - `false`：启动时的静默检查，失败**回到 [UpdateState.Idle]**，不留错误痕。
     *     没网、公司代理拦了、GitHub 抽风都不是用户该看到的报错 ——
     *     他既没要求联网，也不该因为「顺便查了个更新」而在设置页看到一片红。
     *     想查的时候手动点一下即可。
     *
     * 「要不要主动提醒」不在这里决定 —— 那是 UI 层读 `Prefs.getNotifyUpdate()` 的事。
     */
    suspend fun check(manual: Boolean) {
        if (_state.value is UpdateState.Checking) return
        _state.value = UpdateState.Checking
        val result = runCatching { fetchLatest() }
        result.onFailure { t ->
            _state.value = if (manual) {
                UpdateState.Failed(t.message ?: t::class.java.simpleName)
            } else {
                UpdateState.Idle
            }
            return
        }
        val release = result.getOrThrow()

        if (compareVersions(release.version, currentVersion) <= 0) {
            _state.value = UpdateState.UpToDate(currentVersion)
            return
        }
        _state.value = UpdateState.Available(
            version = release.version,
            notes = release.notes,
            pageUrl = release.pageUrl,
            patch = release.patch,
            selfInstallBlocked = selfInstallBlocker()
        )
    }

    private data class Release(
        val version: String,
        val notes: String,
        val pageUrl: String,
        val patch: UpdateAsset?
    )

    private suspend fun fetchLatest(): Release = withContext(Dispatchers.IO) {
        val request = HttpRequest.newBuilder(URI(LATEST_URL))
            .timeout(Duration.ofSeconds(20))
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
        when (response.statusCode()) {
            200 -> Unit
            404 -> throw IllegalStateException("仓库还没有发布过任何版本")
            403 -> throw IllegalStateException("GitHub 限流了，过一会儿再试")
            else -> throw IllegalStateException("GitHub 返回 HTTP ${response.statusCode()}")
        }
        val root = MiniJson.parse(response.body()) as? JsonValue.Obj
            ?: throw IllegalStateException("GitHub 返回的不是 JSON 对象")

        val tag = root.str("tag_name")
        if (tag.isBlank()) throw IllegalStateException("Release 没有 tag_name")
        val version = normalizeVersion(tag)
        val assets = root.array("assets")

        // 补丁包按 `StuMate-patch-<版本>.zip` 认。找不到就只给下载页 ——
        // 老 Release 可能只挂了整包，不能因此报错。
        val patch = assets.mapNotNull { it as? JsonValue.Obj }.firstOrNull { asset ->
            asset.str("name") == "StuMate-patch-$version.zip"
        }?.let { asset ->
            val digest = asset.str("digest").takeIf { it.startsWith("sha256:") }
                ?.removePrefix("sha256:")
            UpdateAsset(
                name = asset.str("name"),
                url = asset.str("browser_download_url"),
                size = asset.long("size"),
                sha256 = digest?.takeIf { it.isNotBlank() }
            )
        }

        Release(
            version = version,
            notes = root.str("body").trim(),
            pageUrl = root.str("html_url"),
            patch = patch?.takeIf { it.url.isNotBlank() }
        )
    }

    // ── 下载 + 就地替换 ─────────────────────────────────────────────

    suspend fun downloadAndApply() {
        val available = _state.value as? UpdateState.Available ?: return
        val layout = detectInstall()
        if (layout == null) {
            _state.value = UpdateState.NeedsFullPackage(
                available.version, "当前不是从安装目录启动的（开发模式）", available.pageUrl
            )
            return
        }
        val patch = available.patch
        if (patch == null) {
            _state.value = UpdateState.NeedsFullPackage(
                available.version, "这个版本没有提供补丁包", available.pageUrl
            )
            return
        }

        _state.value = UpdateState.Downloading(0L, patch.size)
        val result = runCatching {
            withContext(Dispatchers.IO) { applyPatch(layout, patch) { got, total ->
                _state.value = UpdateState.Downloading(got, total)
            } }
        }
        result.onSuccess {
            _state.value = UpdateState.RestartPending(available.version)
        }.onFailure { t ->
            val reason = t.message ?: t::class.java.simpleName
            _state.value = if (t is java.nio.file.AccessDeniedException) {
                UpdateState.NeedsFullPackage(
                    available.version,
                    "安装目录不可写（装在 Program Files 下需要管理员权限）",
                    available.pageUrl
                )
            } else {
                UpdateState.Failed(reason)
            }
        }
    }

    private fun applyPatch(
        layout: InstallLayout,
        patch: UpdateAsset,
        onProgress: (Long, Long) -> Unit
    ) {
        val staging = File(System.getProperty("java.io.tmpdir"), "StuMate-update").apply {
            deleteRecursively()
            mkdirs()
        }
        val zipFile = File(staging, patch.name)

        // ① 下载。补丁包只有 1.7 MB，但还是按流写盘 —— 免得以后换成整包时炸内存。
        val request = HttpRequest.newBuilder(URI(patch.url))
            .timeout(Duration.ofMinutes(5))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() != 200) {
            throw IllegalStateException("下载补丁失败：HTTP ${response.statusCode()}")
        }
        val declared = response.headers().firstValueAsLong("content-length").orElse(patch.size)
        response.body().use { input ->
            zipFile.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var got = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    got += n
                    onProgress(got, declared)
                }
            }
        }

        // ② 校验。下载下来的东西在被写进安装目录之前必须过这三关。
        patch.sha256?.let { expected ->
            val actual = sha256Of(zipFile)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw IllegalStateException("补丁包校验失败（sha256 对不上）")
            }
        }
        val extracted = extractPatch(zipFile, staging)
        if (compareVersions(versionOfJar(extracted.jar.name), patchVersionOf(patch.name)) != 0) {
            throw IllegalStateException("补丁包里的 jar 版本号和文件名对不上")
        }
        if (!hasMainClass(extracted.jar)) {
            throw IllegalStateException("补丁包里的 jar 不是 StuMate 主程序")
        }

        // ③ 依赖比对。补丁只换主 jar，所以依赖清单必须**逐行一致**；
        //    不一致说明这个版本动了依赖，必须走整包。
        val localCfg = layout.cfg.readText(Charsets.UTF_8)
        val localDeps = dependencyLines(localCfg)
        val patchDeps = dependencyLines(extracted.cfg)
        if (localDeps != patchDeps) {
            throw IllegalStateException("这个版本更新了依赖，需要重新安装整包")
        }

        // ④ 先把新 jar 放进去（新文件名，与运行中的旧 jar 不冲突），
        //    再把 cfg 指过去。**顺序不能反** —— 反过来会出现
        //    「cfg 指着一个还不存在的 jar」的瞬间，此时崩溃就再也起不来了。
        val targetJar = File(layout.appDir, extracted.jar.name)
        if (!targetJar.isFile) extracted.jar.copyTo(targetJar, overwrite = true)

        val oldCfg = File(layout.appDir, "StuMate.cfg.bak")
        if (!oldCfg.isFile) layout.cfg.copyTo(oldCfg, overwrite = true)  // 手动回滚用

        // 只换主 jar 那一行，其余 37 行 classpath 原样保留。
        // 不用字符串 replace（第一参要靠拼，拼错了会静默不动），按行重写更直白。
        val rewritten = localCfg.lines().joinToString("\n") { line ->
            if (line.startsWith("app.classpath=") && line.contains(MAIN_JAR_PREFIX)) {
                "app.classpath=\$APPDIR\\${extracted.jar.name}"
            } else line
        }
        check(rewritten.contains(extracted.jar.name)) { "改写 cfg 失败" }

        val tmp = File(layout.appDir, "StuMate.cfg.new")
        tmp.writeText(rewritten, Charsets.UTF_8)
        java.nio.file.Files.move(
            tmp.toPath(), layout.cfg.toPath(),
            java.nio.file.StandardCopyOption.REPLACE_EXISTING
        )
    }

    private class Extracted(val jar: File, val cfg: String)

    private fun extractPatch(zip: File, staging: File): Extracted {
        var jar: File? = null
        var cfg: String? = null
        val outDir = File(staging, "pkg").apply { mkdirs() }
        ZipInputStream(zip.inputStream().buffered()).use { zin ->
            while (true) {
                val entry = zin.nextEntry ?: break
                if (entry.isDirectory) continue
                val name = entry.name.replace('\\', '/')
                when {
                    name.startsWith("app/") && name.endsWith(".jar")
                            && name.substringAfterLast('/').startsWith(MAIN_JAR_PREFIX) -> {
                        val target = File(outDir, name.substringAfterLast('/'))
                        target.outputStream().use { zin.copyTo(it) }
                        jar = target
                    }
                    name == "app/StuMate.cfg" -> cfg = zin.readBytes().toString(Charsets.UTF_8)
                    else -> Unit
                }
            }
        }
        val j = jar ?: throw IllegalStateException("补丁包里没有主 jar")
        val c = cfg ?: throw IllegalStateException("补丁包里没有 StuMate.cfg")
        return Extracted(j, c)
    }

    /** 启动时清掉历史版本残留的 jar。**运行中的那个删不掉，跳过即可** */
    fun cleanupStaleJars() {
        val layout = detectInstall() ?: return
        val keep = layout.jarName
        layout.appDir.listFiles { f ->
            f.isFile && f.name.startsWith(MAIN_JAR_PREFIX) && f.name.endsWith(".jar") && f.name != keep
        }?.forEach { runCatching { it.delete() } }
    }

    /** 用装好的新版本重启自己。调用后本进程立刻退出 */
    fun restartNow() {
        val layout = detectInstall() ?: return
        runCatching {
            ProcessBuilder(layout.launcher.absolutePath)
                .directory(layout.root)
                .start()
        }.onFailure { it.printStackTrace() }
        kotlin.system.exitProcess(0)
    }

    fun openReleasePage(url: String) {
        runCatching {
            val desktop = java.awt.Desktop.getDesktop()
            if (desktop.isSupported(java.awt.Desktop.Action.BROWSE)) desktop.browse(URI(url))
        }
    }

    // ── 纯函数（可单测） ────────────────────────────────────────────

    /** 去掉前缀 `v`，并砍掉 `-beta` 这类后缀 */
    internal fun normalizeVersion(raw: String): String =
        raw.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')

    /** 语义化比较：a > b 返回 1，a < b 返回 -1，相等 0。缺失的段按 0 算（`1.6` == `1.6.0`） */
    internal fun compareVersions(a: String, b: String): Int {
        val pa = parseVersion(a)
        val pb = parseVersion(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return if (x > y) 1 else -1
        }
        return 0
    }

    private fun parseVersion(v: String): List<Int> =
        normalizeVersion(v).split('.').map { it.toIntOrNull() ?: 0 }

    /** `StuMate-Desktop-1.6.0-abc123.jar` → `1.6.0`；认不出来返回空串 */
    internal fun versionOfJar(jarName: String): String {
        val body = jarName.removePrefix(MAIN_JAR_PREFIX).removeSuffix(".jar")
        val parts = body.split('-')
        return if (parts.size >= 2) parts.dropLast(1).joinToString("-") else ""
    }

    /** `StuMate-patch-1.6.0.zip` → `1.6.0`；认不出来返回空串 */
    internal fun patchVersionOf(patchName: String): String =
        patchName.removePrefix("StuMate-patch-").removeSuffix(".zip")

    /**
     * 取 cfg 里**除主 jar 之外**的 classpath 行，用来判断依赖有没有变。
     *
     * 这是「能不能走补丁」的唯一判据 —— 比人去核对 38 个文件名可靠。
     */
    internal fun dependencyLines(cfg: String): List<String> =
        cfg.lines().filter { it.startsWith("app.classpath=") && !it.contains(MAIN_JAR_PREFIX) }

    private fun hasMainClass(jar: File): Boolean {
        var found = false
        runCatching {
            ZipInputStream(jar.inputStream().buffered()).use { zin ->
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.name == "com/example/classreminder/MainKt.class") { found = true; break }
                }
            }
        }
        return found
    }

    private fun sha256Of(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
