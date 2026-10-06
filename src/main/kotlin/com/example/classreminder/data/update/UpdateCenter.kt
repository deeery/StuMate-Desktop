package com.example.classreminder.data.update

import com.example.classreminder.BuildConfig
import com.example.classreminder.data.backup.JsonValue
import com.example.classreminder.data.backup.MiniJson
import com.example.classreminder.data.backup.array
import com.example.classreminder.data.backup.long
import com.example.classreminder.data.backup.str
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.zip.ZipInputStream
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * 基于 GitHub Releases 的更新检测与「就地替换」。
 *
 * ## 为什么不需要内置 token
 * 这个仓库是 **public**，`api.github.com/repos/.../releases/latest` 匿名可读。
 * 把 PAT 打进客户端等于把密码发出去，绝对不做。代价只是匿名限流
 * 60 次/小时/IP —— 而「启动时查一次 + 手动点一下」远远够用。
 *
 * ## 为什么能「就地替换」而不用 helper 进程
 * 桌面端升级通常只需要动两个文件：主 jar 和 `app/StuMate.cfg`（37 个依赖 jar
 * 跨版本逐字节不变，见 `docs/desktop-update-strategy.md`）。
 *
 * - 新 jar 的**文件名带新版本号** → 与正在运行的旧 jar 不冲突，可以直接写进去；
 * - `StuMate.cfg` 只在启动时被 jpackage launcher 读一次，**不会被常驻持有** → 可以覆盖。
 * - `runtime/lib/modules`（jlink 的模块镜像）**也能换** —— 实测 JVM 运行时它可改名、
 *   可写、甚至可删（JDK 打开它时带了 `FILE_SHARE_DELETE`）。见 [replaceRuntimeFiles]。
 *
 * 所以「下载 → 落盘 → 改 cfg」三步在**进程活着的时候**就能全部做完，
 * 只要最后重启一次即可。**不需要起 .bat / 辅助 JVM** —— 那类方案在中文路径、
 * 编码、杀软拦截上全是坑。
 *
 * ⚠️ 唯一的例外是 `runtime/bin/` 下的东西：那些 dll/exe 由 Windows loader 加载，
 * 是真的锁死的。所以补丁包**约定不带** `bin/`，模块集变化时只换 `lib/` 与 `conf/`。
 *
 * ## 什么时候做不到
 * - 装在 `%ProgramFiles%` 下 → 写 `app/` 要管理员权限 → `AccessDeniedException`
 *   → 降级为 [UpdateState.NeedsFullPackage]（引导去下载页）。
 * - 新版本改了**依赖**（不只是主 jar）→ 补丁包覆盖不了 → 同样降级为整包。
 *   这条由 [dependencyLines] 比对把关，**不靠人记得**。
 * - 新版本换了**运行时模块集**、但补丁包没带 `runtime/lib/modules`
 *   → 同样降级为整包（[runtimeGap] 是启动时的兜底自检）。
 */
object UpdateCenter {

    /** 公开仓库，匿名读 */
    private const val LATEST_URL =
        "https://api.github.com/repos/deeery/StuMate-Desktop/releases/latest"

    private const val USER_AGENT = "StuMate-Desktop-Updater"

    /**
     * 认得出「这是 TLS 信任链问题」的正则。
     *
     * 命中时 [retrying] 会把 [sslDiagnostics] 一起贴出来 —— 见那里的注释。
     * 三个词分别对应：PKIX 链构不出来 / 握手失败 / 别的证书类报错。
     */
    private val TLS_HINT = Regex("PKIX|certification path|SSLHandshake|certificate", RegexOption.IGNORE_CASE)

    /** 主 jar 文件名前缀。jpackage 打出来的名字是 `StuMate-Desktop-<版本>-<hash>.jar` */
    private const val MAIN_JAR_PREFIX = "StuMate-Desktop-"

    /**
     * jpackage 文件名末尾那段内容 hash（实测 31~32 位十六进制）。
     *
     * ⚠️ 长度阈值取 16 而不是精确值：jpackage 各版本截断长度不同，
     * 但**版本号不会是 16 位以上的纯十六进制**，所以这个阈值够安全。
     * 详见 [dependencyLines] 里那段「为什么必须抹掉 hash」。
     */
    private val HASH_SUFFIX = Regex("-[0-9a-fA-F]{16,}\\.jar$")

    /**
     * 裁剪运行时里**必须存在**的模块 —— 少了就说明这个 runtime 不是给当前版本用的。
     *
     * 每一条都对应一次真实事故（异常被吞掉、只在打包产物里暴露）：
     * - `java.sql`：1.5.x 漏过，症状是 SQLite 驱动起不来；
     * - `jdk.crypto.mscapi`：1.6.1 漏过，症状是「检查更新」永远报 PKIX
     *   （读不了 `Windows-ROOT`，静默退回默认信任链，扛不住 SteamTools 这类中间人）。
     *
     * 加模块的同时**必须**在 [JlinkModulesTest] 里加断言，否则下次还会漏。
     */
    internal val REQUIRED_MODULES = listOf("java.sql", "jdk.crypto.mscapi")

    /** jlink 把模块集写在这里，格式：`MODULES="java.base java.xml ..."` */
    internal const val RUNTIME_RELEASE_PATH = "runtime/release"

    /** 模块镜像本体。模块集变了要换的就是它 */
    internal const val RUNTIME_MODULES_PATH = "runtime/lib/modules"

    /** `runtime/release` 里那一行 `MODULES="..."` */
    private val MODULES_LINE = Regex("MODULES=\"([^\"]*)\"")

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
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
        sslContext()?.let(builder::sslContext)
        builder.build()
    }

    /**
     * 更新检查用的 SSLContext：**JDK 自带 CA ∪ 操作系统根证书**。
     *
     * ## 为什么不能只用 JDK 的 `cacerts`
     *
     * 本机实测踩到：装了 SteamTools / Watt Toolkit（`Steamcommunity302`）这类
     * 「加速 GitHub」的工具之后，`api.github.com` 的 TLS 会被它**透明中间人**，
     * 它换上的根证书只装进了 **Windows 证书库**，没进 JDK 的 `cacerts`。
     * 于是浏览器、Python、curl 全都正常，只有这个 Java 应用报
     * `PKIX path building failed: unable to find valid certification path`。
     *
     * 这类工具在中文用户里装机量很大，而且**恰好就是为了访问 GitHub** ——
     * 不处理的话「自动更新」对这批用户是**静默失效**的（静默检查失败不留痕，
     * 用户只会看到「点检查更新没反应」）。
     *
     * ## 为什么是「合并 KeyStore」而不是「拼两个 TrustManager」
     *
     * ⚠️ `SSLContext.init(null, arrayOf(tmA, tmB), random)` **不是「两个都试」**：
     * JDK 内部 `SSLContextImpl.chooseTrustManager()` 只取数组里**第一个**
     * `X509TrustManager`，后面的静默丢弃。踩过这个坑 —— 拼完照样 PKIX 失败，
     * 现象和没改一样。正确做法是把两边的根证书**倒进同一个 KeyStore**，
     * 再基于它建唯一一个 TrustManagerFactory。
     *
     * ## 这样做会不会降低安全性
     *
     * 不会。系统根证书库本来就是这台机器的信任基线 —— 用户装了什么根证书，
     * 这台机器上**每一个**原生应用（浏览器、Electron 应用、Windows 自身）
     * 都已经在信任了。Java 只信自己那份 `cacerts` 反而是个**不一致**：
     * 同一台机器上不同程序对「谁可信」的答案不一样，只会制造这种
     * 「只有它连不上」的诡异故障。这里是把 Java 对齐到操作系统，不是额外放宽。
     *
     * 拿不到系统库时（非 Windows、或受限环境）返回 null，调用方退回默认 context，
     * 行为与不加这段代码完全一致。
     */
    /**
     * 上一次 [sslContext] 的结论 —— 成功是 `jdk=148 os=73`，失败是原因。
     *
     * ## 为什么要有它
     *
     * 这段逻辑**失败是静默的**：`runCatching` 吞掉异常 → 返回 null → 退回默认
     * SSLContext。而它唯一的下游症状就是那句 `PKIX path building failed` ——
     * 那句话既可能是「真被中间人了」，也可能是「这里压根没生效」，**指不出根因**。
     *
     * 实测踩过：打包运行时缺 `jdk.crypto.mscapi`（`SunMSCAPI` provider 所在模块），
     * `KeyStore.getInstance("Windows-ROOT")` 抛 `KeyStoreException: Windows-ROOT not found`
     * 被吞掉，界面上只剩 PKIX。IDE 里跑（全量 JDK）完全正常，**只有打包产物中招**。
     *
     * 把结论带进错误信息（见 [retrying]），下次一眼能分辨是证书还是缺模块。
     */
    internal var sslDiagnostics: String = "尚未构建"
        private set

    /** `internal` 而不是 `private`：单测要直接跑它并断言 [sslDiagnostics] 的形状 */
    internal fun sslContext(): SSLContext? = runCatching {
        val algorithm = TrustManagerFactory.getDefaultAlgorithm()

        // ① JDK 自带 cacerts 的全部根
        val jdk = TrustManagerFactory.getInstance(algorithm)
            .apply { init(null as KeyStore?) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .firstOrNull()
            ?: run {
                sslDiagnostics = "拿不到 JDK 自带 cacerts 的 TrustManager"
                return@runCatching null
            }

        val merged = KeyStore.getInstance(KeyStore.getDefaultType()).apply { load(null, null) }
        jdk.acceptedIssuers.forEachIndexed { i, cert -> merged.setCertificateEntry("jdk-$i", cert) }

        // ② 操作系统根证书库
        //    ⚠️ 这一步需要 `jdk.crypto.mscapi` 模块（`SunMSCAPI` provider）。
        //    打包时漏了它 → 这里抛 KeyStoreException，被 runCatching 吞掉 →
        //    静默退回默认 context → 用户只看到 PKIX。由 `JlinkModulesTest` 守着。
        val os = KeyStore.getInstance("Windows-ROOT").apply { load(null, null) }
        var n = 0
        for (alias in os.aliases()) {
            val cert = os.getCertificate(alias) as? X509Certificate ?: continue
            merged.setCertificateEntry("os-${n++}", cert)
        }
        if (n == 0) {
            sslDiagnostics = "系统根证书库读到了但是空的（jdk=${jdk.acceptedIssuers.size} os=0）"
            return@runCatching null
        }

        val tmf = TrustManagerFactory.getInstance(algorithm).apply { init(merged) }
        SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, SecureRandom()) }
            .also { sslDiagnostics = "jdk=${jdk.acceptedIssuers.size} os=$n" }
    }.onFailure {
        sslDiagnostics = "构建失败：${it.javaClass.simpleName}: ${it.message}"
    }.getOrNull()

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
        val body = retrying(what = "查询最新版本") {
            val request = HttpRequest.newBuilder(URI(LATEST_URL))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", USER_AGENT)
                .GET()
                .build()
            val response = http.send(request, HttpResponse.BodyHandlers.ofString(Charsets.UTF_8))
            when (response.statusCode()) {
                200 -> response.body()
                404 -> throw IllegalStateException("仓库还没有发布过任何版本")
                403 -> throw IllegalStateException("GitHub 限流了，过一会儿再试")
                // 5xx 是可重试的，抛出去让 retrying 再试一次
                else -> throw IllegalStateException("GitHub 返回 HTTP ${response.statusCode()}")
            }
        }
        val root = MiniJson.parse(body) as? JsonValue.Obj
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
        result.onSuccess { runtimeSwapped ->
            _state.value = UpdateState.RestartPending(available.version, runtimeSwapped)
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

    /**
     * 真正的「就地替换」。
     *
     * @return 这次是否连 `runtime/` 一起换了（UI 据此把「必须重启」说重一点）。
     */
    private suspend fun applyPatch(
        layout: InstallLayout,
        patch: UpdateAsset,
        onProgress: (Long, Long) -> Unit
    ): Boolean {
        val staging = File(System.getProperty("java.io.tmpdir"), "StuMate-update").apply {
            deleteRecursively()
            mkdirs()
        }
        val zipFile = File(staging, patch.name)

        // ① 下载。补丁包通常只有 1.7 MB，但还是按流写盘 —— 免得以后换成整包时炸内存。
        retrying(what = "下载补丁包") { downloadTo(patch, zipFile, onProgress) }

        return applyPatchZip(layout, zipFile, patch.sha256, patch.name)
    }

    /**
     * 「补丁 zip 到手之后」的全部步骤 —— 与它**从哪来**无关。
     *
     * 拆出来是为了让冒烟能喂一个本地 zip：验证「补丁包能换 runtime」必须在
     * **发布之前**做，而 [applyPatch] 的第一步就是去 GitHub 拿包，本地 zip 进不去。
     * 见 [smokeApplyLocalPatchTo]。
     */
    private fun applyPatchZip(
        layout: InstallLayout,
        zipFile: File,
        expectedSha256: String?,
        patchName: String
    ): Boolean {
        val staging = zipFile.parentFile
            ?: throw IllegalStateException("补丁 zip 没有父目录：$zipFile")

        // ② 校验。下载下来的东西在被写进安装目录之前必须过这三关。
        expectedSha256?.let { expected ->
            val actual = sha256Of(zipFile)
            if (!actual.equals(expected, ignoreCase = true)) {
                throw IllegalStateException("补丁包校验失败（sha256 对不上）")
            }
        }
        val extracted = extractPatch(zipFile, staging)
        if (compareVersions(versionOfJar(extracted.jar.name), patchVersionOf(patchName)) != 0) {
            throw IllegalStateException("补丁包里的 jar 版本号和文件名对不上")
        }
        if (!hasMainClass(extracted.jar)) {
            throw IllegalStateException("补丁包里的 jar 不是 StuMate 主程序")
        }

        // ③ 依赖比对。补丁不换依赖 jar，所以依赖清单必须**逐行一致**；
        //    不一致说明这个版本动了依赖，必须走整包。
        val localCfg = layout.cfg.readText(Charsets.UTF_8)
        val localDeps = dependencyLines(localCfg)
        val patchDeps = dependencyLines(extracted.cfg)
        if (localDeps != patchDeps) {
            throw IllegalStateException("这个版本更新了依赖，需要重新安装整包")
        }

        // ④ 运行时（jlink 模块镜像）。**放在主 jar 之前** ——
        //    万一它失败，此时还什么都没动，用户重试即可；
        //    反过来先换了 jar 再失败，就变成「新 jar 配旧 runtime」，
        //    正好是 1.6.1 那个「检查更新永远报 PKIX」的形态。
        val runtimeSwapped = syncRuntime(layout, extracted)

        // ⑤ 先把新 jar 放进去（新文件名，与运行中的旧 jar 不冲突），
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

        return runtimeSwapped
    }

    /**
     * 该不该换运行时、以及换完没有。
     *
     * ## 判据
     *
     * 补丁包**总是**带 `runtime/release`（247 字节）—— 它写着「这个版本期望的模块集」。
     * 拿它和本地的比：
     *
     * | 情况 | 处理 |
     * |---|---|
     * | 补丁没带 `runtime/`（1.6.1 之前的老包） | 无从判断，不动 |
     * | 本地读不出 `release` | 安装不完整，换 |
     * | 模块集不同 | 换 |
     * | 模块集相同 | 不动（省下 48 MB 流量） |
     *
     * 该换但补丁里没有 `lib/modules` → **明确报错**，让用户去装整包。
     * 这条是 [REQUIRED_MODULES] 之外的第二道闸：打包端忘了附 modules 时，
     * 用户看到的是「需要重新安装整包」，而不是「更新成功」之后照旧报 PKIX。
     */
    private fun syncRuntime(layout: InstallLayout, extracted: Extracted): Boolean {
        val wanted = extracted.runtime[RUNTIME_RELEASE_PATH]
            ?.let { runCatching { runtimeModules(it.readText(Charsets.UTF_8)) }.getOrNull() }
        val local = localRuntimeModules(layout.root)

        if (!needRuntimeSwap(wanted, local)) return false

        if (extracted.runtime[RUNTIME_MODULES_PATH] == null) {
            throw IllegalStateException("这个版本换了运行时，需要重新安装整包")
        }
        replaceRuntimeFiles(layout.root, extracted.runtime)
        return true
    }

    /**
     * 该不该换运行时。
     *
     * 抽成纯函数是为了能单测这三条边界 —— 它们在真实链路上要么很难触发
     * （本地读不出 `release`），要么代价很大（**该换却不换 = 用户白更新一次**，
     * 正是 1.6.1 那个 bug 的形态）。
     */
    internal fun needRuntimeSwap(patchModules: List<String>?, localModules: List<String>?): Boolean =
        when {
            patchModules == null -> false       // 老补丁包没带 runtime 信息，无从判断
            localModules == null -> true        // 本地连 release 都读不出 → 安装不完整
            patchModules != localModules -> true
            else -> false
        }

    /**
     * 把补丁里 `runtime/` 下的文件覆盖进安装目录，返回替换掉的相对路径。
     *
     * ## 为什么能替换一个「正在被 JVM 用着」的文件
     *
     * `runtime/lib/modules` 是 jlink 的模块镜像，启动时被打开并**按需映射**。
     * 直觉上它应该被锁死 —— 实测**不是**：JDK 在 Windows 上打开它时带了
     * `FILE_SHARE_DELETE`，于是改名、写入、甚至删除都允许
     * （`os_windows.cpp` 的 `os::open()` 就是这个共享模式）。
     *
     * 但「允许」不等于「可以随便写」：
     * - ❌ **不能直接覆盖内容** —— 本进程还映射着它，改写会让运行中的 JVM
     *   读到半新半旧的镜像。jimage 是懒加载的，后面才加载的类会直接炸。
     * - ✅ **改名是安全的** —— 改名只动目录项，旧 inode 继续被映射，
     *   本进程一切照旧；新文件从下次启动开始生效。
     *
     * 所以这里是「先全部写 `.new`，再统一改名」，而不是逐个覆盖。
     *
     * ## 为什么 `release` 必须最后换
     *
     * `release` 里写着模块集，是 [runtimeGapOfSelf] 唯一的判断依据。
     * 如果它先被换掉、而 `modules` 换失败了，自检就会以为运行时是健康的 ——
     * 一个「谎报健康」的安装，比一个明显坏掉的安装更难查。所以它排在最后，
     * 充当「这次替换整体成功」的提交标记。
     */
    internal fun replaceRuntimeFiles(root: File, files: Map<String, File>): List<String> {
        val ordered = files.entries.sortedBy { if (it.key == RUNTIME_RELEASE_PATH) 1 else 0 }

        // ① 全部先落到同目录的 `.new`。必须同卷 —— 跨卷改名会退化成「复制 + 删除」，
        //    那就不是原子的了。
        val staged = ordered.map { (rel, src) ->
            val target = File(root, rel)

            // loader 区（`bin/`、`lib/server/`）里的 dll/exe 被 Windows 真锁死，
            // 覆盖不了。打包端只会在**新增**时带上它们（补 mscapi 会多出
            // `bin/sunmscapi.dll`），所以「目标已存在」就意味着这个版本改了
            // native 库 —— 那必须走整包。这里明确报错，而不是等改名失败抛一个
            // 看不懂的共享冲突。
            if (target.isFile && isLoaderLocked(rel)) {
                throw IllegalStateException(
                    "runtime/$rel 已存在且属于被系统锁定的 native 库，无法就地替换 —— " +
                        "需要重新安装整包"
                )
            }

            target.parentFile?.mkdirs()
            val fresh = File(target.parentFile, "${target.name}.new")
            src.copyTo(fresh, overwrite = true)
            rel to (fresh to target)
        }

        // ② 统一改名：先把旧文件让开，再把 `.new` 顶上。
        val done = mutableListOf<String>()
        for ((rel, pair) in staged) {
            val (fresh, target) = pair
            if (target.isFile) {
                val retired = File(target.parentFile, "${target.name}.old")
                runCatching { retired.delete() }
                runCatching { java.nio.file.Files.move(target.toPath(), retired.toPath()) }
            }
            java.nio.file.Files.move(fresh.toPath(), target.toPath())
            done += rel
        }
        return done
    }

    /**
     * 被 Windows loader 加载、运行中**真锁死**的部分：`bin/` 与 `lib/server/` 下的
     * native 库（jvm.dll 及其依赖）。
     *
     * 注意 `lib/modules` **不在**这一类 —— 它由 JDK 自己用 `FILE_SHARE_DELETE` 打开，
     * 实测可改名可删，是能换的（见 [replaceRuntimeFiles] 的注释）。
     */
    private fun isLoaderLocked(rel: String): Boolean =
        rel.startsWith("runtime/bin/") || rel.startsWith("runtime/lib/server/")

    /**
     * 补丁包解出来的东西。
     *
     * @param runtime 补丁里 `runtime/` 下的文件，键是相对路径（如 `runtime/lib/modules`）。
     *        正常版本这里只有一条 `runtime/release`（247 字节，就是「期望的模块集」）；
     *        模块集真变了才会有 `runtime/lib/modules`（约 48 MB）。
     */
    private class Extracted(
        val jar: File,
        val cfg: String,
        val runtime: Map<String, File>
    )

    private fun extractPatch(zip: File, staging: File): Extracted {
        var jar: File? = null
        var cfg: String? = null
        val runtime = mutableMapOf<String, File>()
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

                    // runtime/ 下的文件：按原目录结构解出来。
                    //
                    // `runtime/bin/` 这里**不拦** —— 补 mscapi 这类修复会**新增**
                    // `bin/sunmscapi.dll`，那是安全的（没有旧文件要覆盖）。
                    // 「能不能落盘」交给 [replaceRuntimeFiles]：目标已存在才报错。
                    name.startsWith("runtime/") -> {
                        val target = File(outDir, name)
                        target.parentFile?.mkdirs()
                        target.outputStream().use { zin.copyTo(it) }
                        runtime[name] = target
                    }
                    else -> Unit
                }
            }
        }
        val j = jar ?: throw IllegalStateException("补丁包里没有主 jar")
        val c = cfg ?: throw IllegalStateException("补丁包里没有 StuMate.cfg")
        return Extracted(j, c, runtime)
    }

    /** 把补丁包流式写到 [dest]。失败时把半截文件删掉，免得重试时 `outputStream()` 追加在后面 */
    private fun downloadTo(patch: UpdateAsset, dest: File, onProgress: (Long, Long) -> Unit) {
        val request = HttpRequest.newBuilder(URI(patch.url))
            .timeout(Duration.ofMinutes(5))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()
        try {
            val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
            if (response.statusCode() != 200) {
                throw IllegalStateException("下载补丁失败：HTTP ${response.statusCode()}")
            }
            val declared = response.headers().firstValueAsLong("content-length").orElse(patch.size)
            response.body().use { input ->
                dest.outputStream().use { out ->
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
        } catch (t: Throwable) {
            dest.delete()
            throw t
        }
    }

    /**
     * 网络抖动重试。
     *
     * ## 为什么必须有
     *
     * 实测这条链路上瞬时失败**很常见**：一次 HTTP 502、一次 HTTP/2 连接被重置
     * （`Http2Connection` 里抛出来），四次尝试里坏了两回。国内直连
     * `objects.githubusercontent.com` 本来就不稳，很多用户还挂着各种加速工具。
     * 没有重试的话，用户点「立即更新」得到的是「下载失败」，
     * 而他唯一的办法就是再点一次 —— 那不如替他把这一次点掉。
     *
     * ## 为什么不区分异常类型
     *
     * 「哪些异常可重试」这个判断在这里做不准（连接重置、读超时、代理 502、
     * DNS 抖动……表现各异）。统一重试 3 次、间隔递增，代价是多花一两秒，
     * 而最坏情况只是**把真实的错误信息延后 3 次**才抛出来 —— 错误信息本身不变。
     * 唯一的例外是文件系统错误（写不进去），那种重试也没用，但它本来就不在这里。
     */
    private suspend fun <T> retrying(times: Int = 3, what: String, block: suspend () -> T): T {
        var last: Throwable? = null
        repeat(times) { attempt ->
            runCatching { block() }
                .onSuccess { return it }
                .onFailure {
                    last = it
                    if (attempt < times - 1) delay(800L * (attempt + 1))
                }
        }
        throw IllegalStateException(
            buildString {
                append("$what 失败（已重试 $times 次）：")
                append(last?.message ?: last?.javaClass?.simpleName)
                // TLS 类失败单独补一句：这类错误里「证书真被中间人」和
                // 「系统根证书库压根没并进来」长得一模一样，只有把 [sslDiagnostics]
                // 带出来才分得清。见 [sslDiagnostics] 的注释。
                if (TLS_HINT.containsMatchIn(last?.message.orEmpty())) {
                    append("\n（本机信任库：$sslDiagnostics）")
                }
            }
        )
    }

    /**
     * 冒烟专用：对**指定安装目录**跑一遍真实的「查最新 Release → 下载 → 校验 → 就地替换」。
     *
     * 为什么要开这个口子：`detectInstall()` 是从**自身 class 的位置**反推安装目录的，
     * 而冒烟进程跑的是 `build/classes`，永远反推不出安装目录 —— 生产路径在冒烟里
     * 根本走不到「替换」那一步。所以这里只把「目录从哪来」变成参数，
     * **后面每一步（sha256、版本号断言、主类探测、依赖行比对、落盘顺序、原子改名）
     * 都是生产用的同一段代码**，不存在「测试版逻辑」。
     *
     * 冒烟脚本会把真实安装目录**复制一份**再传进来，绝不碰用户正在用的那份。
     *
     * @return 替换完成后 `app.classpath=` 指向的主 jar 文件名
     */
    internal suspend fun smokeApplyPatchTo(root: File): String {
        val release = withContext(Dispatchers.IO) { fetchLatest() }
        val patch = release.patch ?: throw IllegalStateException("最新 Release 没有补丁包")

        val layout = layoutOf(root)
        withContext(Dispatchers.IO) { applyPatch(layout, patch) { _, _ -> } }
        return mainJarLineOf(layout)
    }

    /**
     * 冒烟专用：用**本地补丁 zip** 走完 [applyPatchZip] 的全部真实步骤。
     *
     * 为什么要开这个口子：验证「补丁包能换 runtime」必须在**发布之前**做 ——
     * 等 Release 建好了再验，等于拿线上当测试环境。而生产路径的第一步就是
     * 去 GitHub 拿补丁包，本地 zip 根本进不去。
     *
     * 只把「zip 从哪来」变成参数：后面的 sha256、版本号断言、主类探测、
     * 依赖行比对、runtime 模块集比对、落盘顺序**全是生产用的同一段代码**。
     *
     * @return 这次是否连 `runtime/` 一起换了
     */
    internal fun smokeApplyLocalPatchTo(root: File, zip: File): Boolean {
        val layout = layoutOf(root)
        return applyPatchZip(layout, zip, expectedSha256 = null, patchName = zip.name)
    }

    /** 从一个安装目录根反推 [InstallLayout]；不像安装目录就抛错 */
    private fun layoutOf(root: File): InstallLayout {
        val appDir = File(root, "app")
        val cfg = File(appDir, "StuMate.cfg")
        val launcher = File(root, "StuMate.exe")
        if (!cfg.isFile) throw IllegalStateException("不是安装目录（缺 app/StuMate.cfg）：$root")
        if (!launcher.isFile) throw IllegalStateException("不是安装目录（缺 StuMate.exe）：$root")

        val runningJar = appDir.listFiles { f ->
            f.isFile && f.name.startsWith(MAIN_JAR_PREFIX) && f.name.endsWith(".jar")
        }?.firstOrNull()?.name.orEmpty()

        return InstallLayout(root, appDir, cfg, launcher, runningJar)
    }

    private fun mainJarLineOf(layout: InstallLayout): String =
        layout.cfg.readText(Charsets.UTF_8).lines()
            .firstOrNull { it.startsWith("app.classpath=") && it.contains(MAIN_JAR_PREFIX) }
            ?.substringAfterLast('\\')
            ?: throw IllegalStateException("替换后 cfg 里找不到主 jar 那一行")

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
     *
     * ## 🔴 为什么必须先把文件名末尾的 hash 抹掉再比
     *
     * jpackage 给每个 jar 起的名字是 `<名字>-<版本>-<内容hash>.jar`，而**这个 hash 并不稳定**。
     * 实测：`skiko-awt-runtime-windows-x64-0.7.85` 那个包只有 **293 字节**（一个空壳 +
     * 一个 25 字节的 MANIFEST），Compose 插件每次构建都重新压一遍，zip 里**条目的时间戳
     * 跟着构建时间走**，于是字节不同 → hash 不同：
     *
     * ```
     * 1.6.0  …-0.7.85-bfa84136b65ded4ecdfbe715e20e91a.jar
     * 1.6.1  …-0.7.85-841d382d82e6fdf9897ae89b773e4c9.jar   ← 同名同版本，只是构建时间不同
     * ```
     *
     * 逐字比较的后果是**跨版本的真补丁永远被拒**，用户看到
     * 「这个版本更新了依赖，需要重新安装整包」—— 而实际一个依赖都没动。
     *
     * 之前的冒烟测试**发现不了**这个问题：它把**同一次构建**的产物复制一份当「老安装」，
     * 两边 cfg 天然一致。必须拿一个**真的旧版本安装**（如 `dist/StuMate-portable-1.6.0.zip`
     * 解出来的那份）去跑，才会撞上。
     *
     * 抹掉 hash 之后，比的是「依赖的**名字 + 版本**集合」：
     * 加依赖、删依赖、升版本照样能认出来，纯粹的内容/时间戳差异不再误报。
     *
     * 抹 hash 是**安全**的：改写 cfg 时以**本地** cfg 为基底（见 [applyPatch] 第 ④ 步），
     * 本地依赖行原样保留，所以本地那些 jar 的名字怎么变都不影响升级后的启动。
     */
    internal fun dependencyLines(cfg: String): List<String> =
        cfg.lines()
            .filter { it.startsWith("app.classpath=") && !it.contains(MAIN_JAR_PREFIX) }
            .map { HASH_SUFFIX.replace(it, ".jar") }

    /**
     * 从 `runtime/release` 的内容里解析模块集。
     *
     * 那个文件是 jlink 生成的 properties 风格文本，其中一行形如：
     * ```
     * MODULES="java.base java.xml java.sql jdk.crypto.mscapi"
     * ```
     * 认不出来返回 null（**不要返回空列表** —— 空列表会被当成「一个模块都没有」，
     * 从而在比对时误判成「模块集变了」）。
     */
    internal fun runtimeModules(releaseText: String): List<String>? =
        MODULES_LINE.find(releaseText)
            ?.groupValues?.get(1)
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            ?.takeIf { it.isNotEmpty() }

    /** 读安装目录下 `runtime/release` 的模块集。文件不在或认不出返回 null */
    internal fun localRuntimeModules(root: File): List<String>? {
        val f = File(root, RUNTIME_RELEASE_PATH)
        if (!f.isFile) return null
        return runCatching { runtimeModules(f.readText(Charsets.UTF_8)) }.getOrNull()
    }

    /**
     * 启动自检：当前**装好的**运行时缺哪些必需模块（空列表 = 健康）。
     *
     * 为什么需要它 —— 1.6.1 的用户装 1.6.2 补丁时，那个补丁是给「认识 runtime 条目」
     * 的新更新器准备的，而 1.6.1 自带的更新器**不认识** `runtime/` 条目，会静默跳过。
     * 于是主 jar 变成 1.6.2、runtime 还是缺 `mscapi` 的那个 —— 用户点了更新、
     * 重启了、问题照旧，而且没有任何提示。这个自检就是那张安全网：
     * 从 1.6.2 起，缺模块的安装会在设置页明确说「需要重新安装整包」。
     *
     * 开发模式（`detectInstall()` 返回 null）与读不出 `release` 的情况都返回空列表 ——
     * **宁可漏报也不误报**，否则开发时每次都要看一条假警告。
     */
    fun runtimeGapOfSelf(): List<String> =
        detectInstall()
            ?.let { localRuntimeModules(it.root) }
            ?.let { have -> REQUIRED_MODULES.filterNot { it in have } }
            ?: emptyList()

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
