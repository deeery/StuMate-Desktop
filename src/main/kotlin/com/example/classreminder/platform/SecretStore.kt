package com.example.classreminder.platform

import com.example.classreminder.AppPaths
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.WString
import com.sun.jna.ptr.PointerByReference
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 账号凭证（access / refresh 令牌）的本地加密存储。
 *
 * ## 为什么是 DPAPI，不是自研加密
 *
 * 设计文档 §4.3 定的是「Windows 用 DPAPI、Android 用 EncryptedSharedPreferences」——
 * 也就是**交给操作系统保管密钥**。自己造一个 AES 密钥再想办法藏起来是死路：
 * 密钥终究要落在磁盘上或者进程内存里，攻击者拿到同一台机器就能一起拿走，
 * 只是把问题挪了一层。DPAPI 的密钥由 Windows 用登录凭据派生并保管，
 * 同一个用户下的其它进程也**拿不到明文密钥**（只能调用 API 解密），
 * 而且这份密文**拷到别的机器 / 别的用户下就打不开** —— 正好符合「凭证不该跟着文件跑」。
 *
 * 参考 [WindowEffects] 的做法用 JNA 调系统 DLL，**零新增依赖**。
 *
 * ## 落盘格式
 *
 * ```
 * 偏移  长度  内容
 *  0     8   ASCII "STMACRED"（魔数，用来识别「这是我们的文件」）
 *  8     1   格式版本 '1'
 *  9     1   载荷编码 'D' = DPAPI 加密 / 'P' = 明文（降级，见下）
 * 10     n   载荷
 * ```
 *
 * 头部 10 字节是**明文**的，只为了能判断「文件是不是我们的、能不能解」；
 * 真正的机密只有第 10 字节往后那段。
 *
 * ## 降级路径（必须说清楚）
 *
 * 非 Windows（开发机跑 Linux/macOS）时 `crypt32` 加载不到，[isAvailable] 为 false，
 * 这时会退化成**明文存储**并打 'P' 标记。这条路径只为「开发机上能跑通流程」而存在，
 * 生产目标平台是 Windows（`nativeDistributions` 只出 MSI），不会走到。
 * 调用方可以用 [isEncryptedAtRest] 检查并在 UI 上给出提示。
 */
object SecretStore {

    // ── 文件格式 ────────────────────────────────────────────────────

    private const val MAGIC = "STMACRED"
    private const val HEADER_SIZE = 10
    private const val VERSION: Byte = '1'.code.toByte()
    private const val FMT_DPAPI: Byte = 'D'.code.toByte()
    private const val FMT_PLAIN: Byte = 'P'.code.toByte()

    /** 凭证文件（放在 `%APPDATA%\StuMate\`，与数据库同级） */
    val file: File get() = File(AppPaths.dataDir, "credentials.bin")

    // ── DPAPI 常量（dpapi.h） ───────────────────────────────────────

    /** 禁止弹任何系统 UI（否则无交互场景下会直接失败，或更糟——卡住） */
    private const val CRYPTPROTECT_UI_FORBIDDEN = 0x1

    /**
     * 附加熵（optional entropy）。
     *
     * 传了它，DPAPI 会把这段常量混进密钥派生里 —— 同一个用户下的**其它程序**
     * 就算拿到了我们的密文文件，不知道这个常量也解不开。属于白送的纵深防御。
     *
     * ⚠️ **一旦发布就不能再改**：改了等于换密钥，老用户存下的凭证全部失效
     * （表现是「更新后要求重新登录」，不算灾难，但没必要）。
     */
    private val ENTROPY = "StuMate/credentials/v1".toByteArray(Charsets.UTF_8)

    // ── JNA 绑定 ────────────────────────────────────────────────────

    /**
     * Win32 `DATA_BLOB { DWORD cbData; BYTE *pbData; }`，JNA 会按平台自动处理对齐。
     *
     * ⚠️ 这里**只能有隐式主构造器**，不能再写次级构造器：
     * 头部已经写了 `: Structure()`（超类型初始化），一旦同时声明次级构造器，
     * Kotlin 会报 `Supertype initialization is impossible without primary constructor`。
     */
    @Structure.FieldOrder("cbData", "pbData")
    class DataBlob : Structure() {
        @JvmField var cbData: Int = 0
        @JvmField var pbData: Pointer? = null

        companion object {
            /** 把一段字节拷进本机内存，做成可传给 DPAPI 的 blob */
            fun of(bytes: ByteArray): DataBlob {
                val blob = DataBlob()
                blob.cbData = bytes.size
                blob.pbData = if (bytes.isEmpty()) {
                    null
                } else {
                    // Memory 由 pbData 这个字段持有引用，只要 blob 活着它就不会被回收
                    Memory(bytes.size.toLong()).also { it.write(0, bytes, 0, bytes.size) }
                }
                blob.write()
                return blob
            }
        }

        /** 读出本机内存里的字节。**必须在 LocalFree 之前调用** */
        fun bytes(): ByteArray {
            val size = cbData
            val pointer = pbData
            return if (size <= 0 || pointer == null) ByteArray(0) else pointer.getByteArray(0, size)
        }
    }

    private interface Crypt32 : Library {
        /**
         * `BOOL CryptProtectData(DATA_BLOB*, LPCWSTR, DATA_BLOB*, PVOID, CRYPTPROTECT_PROMPTSTRUCT*, DWORD, DATA_BLOB*)`
         *
         * 输出 blob 的 `pbData` 是 DPAPI 用 `LocalAlloc` 分配的 —— 调用方负责 `LocalFree`。
         */
        fun CryptProtectData(
            pDataIn: DataBlob,
            szDataDescr: WString?,
            pOptionalEntropy: DataBlob?,
            pvReserved: Pointer?,
            pPromptStruct: Pointer?,
            dwFlags: Int,
            pDataOut: DataBlob
        ): Int

        /** 逆运算。`ppszDataDescr` 传 null 表示不要描述串（要了还得自己 LocalFree 一次） */
        fun CryptUnprotectData(
            pDataIn: DataBlob,
            ppszDataDescr: PointerByReference?,
            pOptionalEntropy: DataBlob?,
            pvReserved: Pointer?,
            pPromptStruct: Pointer?,
            dwFlags: Int,
            pDataOut: DataBlob
        ): Int
    }

    private interface Kernel32 : Library {
        fun LocalFree(hMem: Pointer?): Pointer?
    }

    /** 只在 Windows 上能加载；其它平台为 null，走明文降级 */
    private val crypt32: Crypt32? by lazy {
        runCatching { Native.load("crypt32", Crypt32::class.java) }.getOrNull()
    }

    private val kernel32: Kernel32? by lazy {
        runCatching { Native.load("kernel32", Kernel32::class.java) }.getOrNull()
    }

    /** DPAPI 是否可用（Windows 上为 true） */
    fun isAvailable(): Boolean = crypt32 != null && kernel32 != null

    // ── 内存缓存 ────────────────────────────────────────────────────
    //
    // 一次启动里凭证最多读写几次，但 DPAPI 每次都要走一遍系统调用 + 内存分配；
    // 更重要的是缓存能让 `read()` 变成纯内存操作，方便在 Compose 的重组里被调用。

    private val lock = Any()

    @Volatile private var cached: ByteArray? = null

    @Volatile private var loaded = false

    // ── 对外 API ────────────────────────────────────────────────────

    /**
     * 读出凭证载荷。没有文件、文件损坏、解不开，一律返回 null ——
     * 调用方看到的语义统一是「本机没有可用凭证」，不需要区分是哪一种。
     */
    fun read(): ByteArray? {
        cached?.let { return it }
        return synchronized(lock) {
            cached?.let { return it }
            if (loaded) return null
            loaded = true
            val payload = runCatching { decode(file.readBytes()) }.getOrNull()
            cached = payload
            payload
        }
    }

    /**
     * 写入凭证载荷。
     *
     * @return 是否写成功。写失败（磁盘满 / 权限）时**内存缓存仍会更新**，
     *         这样本次运行至少是自洽的，只是下次启动要重新登录。
     */
    fun write(payload: ByteArray): Boolean {
        val encoded = encode(payload)
        synchronized(lock) {
            cached = payload
            loaded = true
        }
        return writeAtomically(encoded)
    }

    /** 抹掉凭证（退出登录 / 令牌被吊销）。文件删不掉也把缓存清了，语义优先 */
    fun erase() {
        synchronized(lock) {
            cached = null
            loaded = true
        }
        runCatching { if (file.isFile) file.delete() }
    }

    /** 当前磁盘上的凭证是不是加密的（非 Windows 降级时为 false，UI 可据此提示） */
    fun isEncryptedAtRest(): Boolean = runCatching {
        val raw = file.readBytes()
        raw.size > HEADER_SIZE && raw[9] == FMT_DPAPI
    }.getOrDefault(false)

    // ── 编码 / 解码 ─────────────────────────────────────────────────

    private fun encode(payload: ByteArray): ByteArray {
        val sealed = protect(payload)
        val format = if (sealed != null) FMT_DPAPI else FMT_PLAIN
        val body = sealed ?: payload

        val out = ByteArray(HEADER_SIZE + body.size)
        MAGIC.toByteArray(Charsets.US_ASCII).copyInto(out, 0)
        out[8] = VERSION
        out[9] = format
        body.copyInto(out, HEADER_SIZE)
        return out
    }

    private fun decode(raw: ByteArray): ByteArray? {
        if (raw.size <= HEADER_SIZE) return null
        if (String(raw, 0, MAGIC.length, Charsets.US_ASCII) != MAGIC) return null
        if (raw[8] != VERSION) return null

        val body = raw.copyOfRange(HEADER_SIZE, raw.size)
        return when (raw[9]) {
            FMT_DPAPI -> unprotect(body)
            FMT_PLAIN -> body
            else -> null
        }
    }

    private fun writeAtomically(bytes: ByteArray): Boolean = runCatching {
        val target = file
        val tmp = File(target.parentFile, "${target.name}.tmp")
        tmp.writeBytes(bytes)
        Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        true
    }.getOrDefault(false)

    // ── DPAPI 调用 ──────────────────────────────────────────────────

    private fun protect(plain: ByteArray): ByteArray? {
        val api = crypt32 ?: return null
        val free = kernel32 ?: return null

        val input = DataBlob.of(plain)
        val entropy = DataBlob.of(ENTROPY)
        val output = DataBlob()

        val ok = runCatching {
            api.CryptProtectData(
                input,
                WString("StuMate 账号凭证"),
                entropy,
                null,
                null,
                CRYPTPROTECT_UI_FORBIDDEN,
                output
            ) != 0
        }.getOrDefault(false)
        if (!ok) return null

        // JNA 把结构体按引用传给 native，但**不保证**回来时把内存同步到字段上；
        // 显式 read() 一次，然后**先拷走字节再 LocalFree** —— 顺序反了就是 use-after-free
        output.read()
        val result = output.bytes()
        output.pbData?.let { runCatching { free.LocalFree(it) } }
        return result
    }

    private fun unprotect(cipher: ByteArray): ByteArray? {
        val api = crypt32 ?: return null
        val free = kernel32 ?: return null

        val input = DataBlob.of(cipher)
        val entropy = DataBlob.of(ENTROPY)
        val output = DataBlob()

        val ok = runCatching {
            api.CryptUnprotectData(
                input,
                null,
                entropy,
                null,
                null,
                CRYPTPROTECT_UI_FORBIDDEN,
                output
            ) != 0
        }.getOrDefault(false)
        if (!ok) return null

        output.read()
        val result = output.bytes()
        output.pbData?.let { runCatching { free.LocalFree(it) } }
        return result
    }
}
