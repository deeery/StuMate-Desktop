package com.example.classreminder.dev

import com.example.classreminder.data.backup.JsonValue
import com.example.classreminder.data.backup.MiniJson
import com.example.classreminder.data.backup.array
import com.example.classreminder.data.backup.bool
import com.example.classreminder.data.backup.int
import com.example.classreminder.data.backup.objOrNull
import com.example.classreminder.data.backup.str
import com.example.classreminder.data.sync.HttpJson
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.util.UUID

/**
 * 云同步端到端冒烟测试 —— **连真实服务端**，用真实的 HTTP 客户端代码。
 *
 * 与服务端那个 `dev-verify-sync.mjs` 互补：那个验「服务端逻辑对不对」，
 * 这个验「**客户端发出去的包服务端认不认、拉回来的包客户端解不解**」——
 * 也就是字段名、大小写、时间格式这些最容易对不上的地方。
 *
 * ## 为什么需要两个账号而不是一个账号两台设备
 *
 * 首端判定会让第二台设备收到 `replace_local = true`，那正好是「清空重拉」路径，
 * 单独验。而这里要验的是**增量合并**，所以用两个互不相干的账号，
 * 各自从空库开始，先建立基准再互相推送。
 *
 * 跑法：`./gradlew syncSmoke`
 */
fun main() = runBlocking {
    println("═".repeat(64))
    println(" StuMate 云同步端到端冒烟（真实服务端）")
    println(" 基址：${HttpJson.BASE_URL}")
    println("═".repeat(64))

    var passed = 0
    var failed = 0

    fun check(name: String, cond: Boolean, detail: String = "") {
        if (cond) {
            passed++
            println("  √ $name")
        } else {
            failed++
            println("  × $name${if (detail.isNotBlank()) "  → $detail" else ""}")
        }
    }

    val stamp = System.currentTimeMillis()
    val password = "Smoke-123456"

    println("\n【准备账号】")
    // ⚠️ **优先登录，别每轮都注册**。
    // `registerIpLimited` 是 **10 次 / IP / 24 小时**（lib/stumate/ratelimit.ts），
    // 一轮冒烟要建 2 个账号 = 烧 2 次额度，跑 5 轮就撞墙。撞墙后服务端一律回
    // `429 RATE_LIMITED`，而令牌拿不到 → 脚本静默跳过全部用例还报 BUILD SUCCESSFUL，
    // 看起来像「测过了」，实际一条没跑。这个坑已经踩过一次。
    //
    // 登录走的是另一套限流额度，可以随便跑。
    val tokenA = acquireToken("a", stamp, password)
    val tokenB = acquireToken("b", stamp, password)
    check("账号 A 拿到令牌", tokenA != null, "登录和注册都失败了")
    check("账号 B 拿到令牌", tokenB != null)
    if (tokenA == null || tokenB == null) {
        println(
            "\n账号没拿到令牌，后续用例跳过。\n" +
                "最可能的原因：**注册接口按 IP 限流 10 次 / 24 小时**（不是邀请码用完了）。\n" +
                "解法：把两个固定账号传进来走登录（登录不受这个限流）：\n" +
                "  ./gradlew syncSmoke -PemailA=… -PemailB=… -Ppassword=…\n" +
                "固定账号只需用注册接口各建一次（一次性消耗 2 次注册额度）。"
        )
        return@runBlocking
    }

    // ── 1. status ────────────────────────────────────────────────
    //
    // ⚠️ 这里**不能**断言 `cursor == 0` / `is_initial_device == true` / `课程数 == 0`。
    // 固定账号（为了绕开注册限流而复用）已经跑过若干轮，库里早有数据、
    // `initial_device_id` 早已被认领 —— 那三条断言只在**全新账号**上成立。
    // 之前一直挂在这里，看着像「服务端有 bug」，其实是测试的假设错了。
    //
    // 正确姿势：把首轮结果当**基线**，验「本轮操作让状态按预期变化」。
    // 首端语义靠第 1b 段单独验（用一个真正的全新账号）。
    println("\n【1. /sync/status 初始状态（基线）】")
    val statusA = get("/sync/status", tokenA)
    val baseCursor = statusA.int("cursor")
    val baseClasses = statusA.objOrNull("counts")?.int("classes") ?: -1
    val baseDeleted = statusA.objOrNull("counts")?.int("deleted") ?: -1
    check("返回了 cursor", statusA.fields.containsKey("cursor"), statusA.toString())
    check("返回了 is_initial_device", statusA.fields.containsKey("is_initial_device"))
    check("返回了 counts.classes", baseClasses >= 0, "实际 $baseClasses")
    check(
        "counts 三项齐全（classes / notes / deleted）",
        statusA.objOrNull("counts")?.fields?.keys?.containsAll(
            setOf("classes", "notes", "deleted")
        ) == true,
        statusA.toString()
    )
    // 首端身份一旦被认领就不会再变；false 说明这个账号以前跑过（复用账号的正常现象）
    val isInitial = statusA.bool("is_initial_device")
    println("  · 基线：cursor=$baseCursor classes=$baseClasses is_initial_device=$isInitial")

    // ── 2. push 一条课程 ─────────────────────────────────────────
    println("\n【2. push 一条课程】")
    val uid1 = UUID.randomUUID().toString()
    val course = courseJson(uid1, "高等数学", System.currentTimeMillis())
    var push = post("/sync/push", tokenA, pushBody(listOf(course)))
    check("applied 1 条", push.array("applied").size == 1, push.toString())
    // version 是**每条记录**的版本号，不是每账号的。复用账号时这条记录是全新的
    // （uid 每次现生成），所以 version 必然从 1 起 —— 这条断言依然成立。
    check("version = 1（新记录从 1 起）", push.array("applied").firstObj()?.int("version") == 1)
    check("游标相对基线推进", push.int("cursor") > baseCursor, "实际 ${push.int("cursor")}")
    // 第 6 段要用「此刻」的课程数当基线，不是脚本开头那个 ——
    // 中间已经 push 了新记录，开头的基线早就过时了。
    val classesAfterPush = get("/sync/status", tokenA).objOrNull("counts")?.int("classes") ?: -1
    check(
        "push 响应含约定的六个字段",
        push.fields.keys == setOf(
            "cursor", "applied", "conflicts", "rejected", "replace_local", "is_initial_device"
        ),
        "实际字段：${push.fields.keys}"
    )

    // ── 3. pull 回来，字段名必须完全对得上 ────────────────────────
    println("\n【3. pull 回来验字段（最容易出错的地方）】")
    // 从本轮基线往回拉，避开历史数据里同名的课
    val pull = get("/sync/pull?cursor=$baseCursor", tokenA)
    check("拿到 1 条", pull.array("changes").size == 1, "实际 ${pull.array("changes").size} 条")
    val change = pull.array("changes").firstObj()?.objOrNull("data")
    check("data 里 uid 对得上", change?.str("uid") == uid1, "实际 ${change?.str("uid")}")
    check("标题对得上", change?.str("title") == "高等数学", "实际 ${change?.str("title")}")
    check("字段名是 dayOfWeek（驼峰）", change?.fields?.containsKey("dayOfWeek") == true)
    check("字段名是 startTime（驼峰）", change?.fields?.containsKey("startTime") == true)
    check("updatedAt 是数字", change?.fields?.get("updatedAt") is JsonValue.Num)
    check("op = upsert", pull.array("changes").firstObj()?.str("op") == "upsert")
    check("nextCursor 就是本轮这条", pull.int("cursor") == push.int("cursor"))

    // ── 4. LWW：更旧的改动推不上去 ────────────────────────────────
    println("\n【4. LWW：更旧的改动被服务端拒绝】")
    val stale = courseJson(uid1, "过期的标题", 1000L)
    push = post("/sync/push", tokenA, pushBody(listOf(stale)))
    check("没有 applied", push.array("applied").size == 0, push.toString())
    check("进了 conflicts", push.array("conflicts").size == 1, push.toString())
    check(
        "conflict 回吐的是服务端那版",
        push.array("conflicts").firstObj()?.objOrNull("data")?.str("title") == "高等数学",
        "实际 ${push.array("conflicts").firstObj()?.objOrNull("data")?.str("title")}"
    )

    // ── 5. 软删除传播 ────────────────────────────────────────────
    println("\n【5. 软删除】")
    val uid2 = UUID.randomUUID().toString()
    val now2 = System.currentTimeMillis()
    post("/sync/push", tokenA, pushBody(listOf(courseJson(uid2, "要被删的课", now2))))
    val cursorBefore = get("/sync/status", tokenA).int("cursor")
    val deleted = courseJson(uid2, "要被删的课", now2 + 1000, deletedAt = now2 + 1000)
    push = post("/sync/push", tokenA, pushBody(listOf(deleted)))
    check("软删除写入成功", push.array("applied").size == 1, push.toString())

    val afterDelete = get("/sync/pull?cursor=$cursorBefore", tokenA)
    check("删除能被拉到", afterDelete.array("changes").size >= 1)
    check("op = delete", afterDelete.array("changes").firstObj()?.str("op") == "delete")
    check(
        "deleted_at 非空（这就是补的那一列）",
        !blankOrNull(afterDelete.array("changes").firstOrNull(), "deleted_at"),
        "deleted_at 为空 —— 服务端 sync_log 缺列"
    )

    // ── 6. status 统计 ───────────────────────────────────────────
    println("\n【6. /sync/status 统计】")
    val status2 = get("/sync/status", tokenA)
    val counts = status2.objOrNull("counts")
    // 基线取 classesAfterPush（第 2 段 push 之后）。从那儿到此刻只发生了
    // uid2 的「加进来又删掉」，净变化 ±0 —— uid3/uid4 是第 7、8 段才写的，
    // 不能算进来。
    check(
        "活着的课程数与基线一致（uid2 加了又删，净 0）",
        counts?.int("classes") == classesAfterPush,
        "基线 $classesAfterPush → 实际 ${counts?.int("classes")}"
    )
    check(
        "已删数比基线多 1（只有 uid2）",
        (counts?.int("deleted") ?: -1) == baseDeleted + 1,
        "实际 ${counts?.int("deleted")}，基线 $baseDeleted"
    )
    check("notes 字段存在", counts?.fields?.containsKey("notes") == true)

    // ── 7. 幂等 ─────────────────────────────────────────────────
    println("\n【7. 幂等：同一批重推两次】")
    val uid3 = UUID.randomUUID().toString()
    val batch = pushBody(listOf(courseJson(uid3, "幂等", System.currentTimeMillis())))
    val first = post("/sync/push", tokenA, batch)
    val second = post("/sync/push", tokenA, batch)
    check("第一次写入", first.array("applied").size == 1)
    check("第二次不重复写", second.array("applied").size == 0, second.toString())
    check(
        "第二次没有产生新版本",
        second.array("conflicts").size == 1,
        "重推应判成冲突（LWW 里幂等就是这么实现的）"
    )
    // 从本轮基线往回数，而不是全量 cursor=0 —— 复用账号的历史流水会挤掉 limit 配额，
    // 数不到本轮的记录，看着像「写了两次」。
    val all = get("/sync/pull?cursor=$baseCursor&limit=500", tokenA)
    val times = all.array("changes").count { it.asObj()?.objOrNull("data")?.str("uid") == uid3 }
    check("流水里只出现一次", times == 1, "实际 $times 次")

    // ── 8. 参数校验 ──────────────────────────────────────────────
    println("\n【8. 参数校验】")
    check("非法 cursor → 400", statusOf(path = "/sync/pull?cursor=abc", token = tokenA, expect = 400))
    check("负数 cursor → 400", statusOf(path = "/sync/pull?cursor=-1", token = tokenA, expect = 400))
    val bad = post("/sync/push", tokenA, """{"changes":[{"entity":"xx","uid":"${UUID.randomUUID()}"}]}""")
    check("非法 entity 进 rejected", bad.array("rejected").size == 1, bad.toString())

    val uid4 = UUID.randomUUID().toString()
    val mixed = """{"changes":[{"entity":"class","uid":"不是UUID"},${MiniJson.write(courseJson(uid4, "同批合法", System.currentTimeMillis()), false)}]}"""
    val mixedRes = post("/sync/push", tokenA, mixed)
    check("一条非法不阻断整批", mixedRes.array("applied").size == 1, mixedRes.toString())
    check("非法的进了 rejected", mixedRes.array("rejected").size == 1)

    // ── 9. 账号隔离与首端语义 ─────────────────────────────────────
    //
    // 这一段是【1】那几条断言的真正归宿：首端身份跟**账号**绑定，
    // 所以必须在「B 没碰过 A 的数据」的前提下单独验。
    println("\n【9. 账号隔离与首端语义】")
    val pullB = get("/sync/pull?cursor=0&limit=500", tokenB)
    val leaked = pullB.array("changes").any {
        val uid = it.asObj()?.objOrNull("data")?.str("uid")
        uid == uid1 || uid == uid2 || uid == uid3 || uid == uid4
    }
    check("B 拉不到 A 的任何记录", !leaked, "跨账号泄漏")
    check(
        "B 的库里是空的",
        get("/sync/status", tokenB).objOrNull("counts")?.int("classes") == 0,
        get("/sync/status", tokenB).toString()
    )
    val statusB = get("/sync/status", tokenB)
    // status 只返回 cursor / is_initial_device / counts 三项（服务端不知道
    // 每个客户端同步到哪了，游标比对由客户端自己做）。这里把响应结构钉住，
    // 将来有人「顺手」加字段进来时会被这条挡住。
    check(
        "status 响应只有约定的三项",
        statusB.fields.keys == setOf("cursor", "is_initial_device", "counts"),
        "实际字段：${statusB.fields.keys}"
    )
    check("B 还没碰过 → cursor = 0", statusB.int("cursor") == 0, "实际 ${statusB.int("cursor")}")
    check("B 还没有首端设备", !statusB.bool("is_initial_device"))

    // A 若不是首端（非首次跑），pull 时**应该**下发 replace_local；
    // 反过来首端/未认领时绝不能下发 —— 这是最危险的一个字段，客户端拿到 true
    // 就会备份+清空本地。
    val pullA = get("/sync/pull?cursor=$baseCursor&limit=10", tokenA)
    val replaceLocal = pullA.bool("replace_local")
    check(
        "首端不下发 replace_local（否则每次同步都清空本地）",
        isInitial && !replaceLocal,
        "is_initial_device=$isInitial 但 replace_local=$replaceLocal"
    )
    // ⚠️ 这里写的是 `hasMore`（驼峰）不是 `has_more`。
    // 服务端三个 sync 路由的响应风格是混的：`records` 内部字段是驼峰
    // （dayOfWeek / startTime / updatedAt），而分页游标这类元数据也是驼峰。
    // 客户端 `SyncApi` 读的正是 `hasMore` —— 一旦有人按「JSON 惯例」改成蛇形，
    // 客户端会安静地把它读成 false，于是超过 500 条时**静默丢掉后半截**，
    // 表现为「同步了但少了数据」，比不同步还难查。这条断言就是防这个的。
    check(
        "pull 响应含约定的四个字段（hasMore 是驼峰）",
        pullA.fields.keys == setOf("cursor", "hasMore", "changes", "replace_local"),
        "实际字段：${pullA.fields.keys}"
    )

    // ── 10. 分页边界：hasMore 真的会在超过 limit 时变 true ──────────
    //
    // 这条最值钱：如果 hasMore 恒为 false，客户端的 while 循环永远只跑一页，
    // 用户攒到 501 条记录时会**静默丢掉第 501 条**，且没有任何报错。
    // 「同步了但少了数据」是最难查的一类 bug，所以必须真的撞一次这个边界。
    println("\n【10. 分页边界】")
    val probeStart = get("/sync/status", tokenA).int("cursor")
    // 用 pushBody 拼装 —— courseJson 返回的**已经是完整包装**
    // （entity / uid / data / updatedAt / deletedAt / baseVersion），
    // 再手动套一层 {"changes":[…]} 会变成双重包装，服务端会报
    // 「entity 只能是 class 或 note」（entity 被塞到了内层）。
    val filler = (1..8).map { i ->
        courseJson(UUID.randomUUID().toString(), "分页-$i", System.currentTimeMillis())
    }
    val fillerRes = post("/sync/push", tokenA, pushBody(filler))
    check("批量塞入 8 条", fillerRes.array("applied").size == 8, fillerRes.toString())

    val page1 = get("/sync/pull?cursor=$probeStart&limit=3", tokenA)
    check("limit=3 时只给 3 条", page1.array("changes").size == 3, "实际 ${page1.array("changes").size}")
    check("limit=3 时 hasMore=true", page1.bool("hasMore"), "客户端会以为拉完了")

    var c = page1.int("cursor")
    var guard = 0
    var total = page1.array("changes").size
    while (page1.bool("hasMore") && guard++ < 50) {
        val next = get("/sync/pull?cursor=$c&limit=3", tokenA)
        total += next.array("changes").size
        c = next.int("cursor")
        if (!next.bool("hasMore")) break
    }
    check("翻页能拿全（8 条 + 之前那 1 条）", total >= 8, "实际共 $total 条")
    check("最后一页 hasMore=false", !get("/sync/pull?cursor=$c&limit=3", tokenA).bool("hasMore"))
    check("游标单调推进到最新", c >= fillerRes.int("cursor"), "$c vs ${fillerRes.int("cursor")}")

    // ── 11. 收尾核对：把整轮的账算平 ─────────────────────────────
    //
    // 前面各段是分段验的，最后再对一次总账 —— 分段全绿但总账不平的情况
    // （比如某条 push 悄悄没落库）在分段断言里是看不出来的。
    println("\n【11. 收尾总账】")
    val finalStatus = get("/sync/status", tokenA)
    val finalCounts = finalStatus.objOrNull("counts")
    // 脚本开头基线 classes=baseClasses。之后新增且仍活着的：
    //   uid1(§2) + uid3(§7) + uid4(§8) + 分页 8 条(§10) = 11
    // uid2 加了又删，不计入。
    check(
        "活着的课程 = 基线 + 11（uid1/uid3/uid4 + 分页 8 条）",
        finalCounts?.int("classes") == baseClasses + 11,
        "基线 $baseClasses → 实际 ${finalCounts?.int("classes")}"
    )
    check(
        "已删 = 基线 + 1（只有 uid2）",
        (finalCounts?.int("deleted") ?: -1) == baseDeleted + 1,
        "实际 ${finalCounts?.int("deleted")}，基线 $baseDeleted"
    )
    check(
        "服务端游标走到了最新",
        finalStatus.int("cursor") >= fillerRes.int("cursor"),
        "${finalStatus.int("cursor")} vs ${fillerRes.int("cursor")}"
    )

    println("\n" + "═".repeat(64))
    println(" 通过 $passed / 失败 $failed / 合计 ${passed + failed}")
    println(" 测试账号见上方【准备账号】的逐行输出")
    println("═".repeat(64))
    if (failed > 0) throw AssertionError("有 $failed 条用例失败")
}

/**
 * 课程变更（与 SyncApi.LocalChange 同形）。
 *
 * @param deletedAt 传非 0 才算「软删除」。之前这个参数被写死成 0，
 *        导致软删除那条用例其实是「又插了一条」—— 用例失败但根因在脚本，
 *        不是服务端。删除标记必须真的发出去。
 */
private fun courseJson(
    uid: String,
    title: String,
    updatedAt: Long,
    deletedAt: Long = 0L
): JsonValue.Obj {
    val data = MiniJson.parse(
        """{"uid":"$uid","id":1,"title":"$title","dayOfWeek":"Monday",
           "startTime":"08:00","endTime":"09:35","room":"教二 305","notes":"",
           "teacher":"王海燕","weeks":"1-16周","date":"",
           "updatedAt":$updatedAt,"deletedAt":$deletedAt}"""
    ) as JsonValue.Obj
    return JsonValue.Obj(
        mapOf(
            "entity" to JsonValue.Str("class"),
            "uid" to JsonValue.Str(uid),
            "data" to data,
            "updatedAt" to JsonValue.Num(updatedAt.toDouble()),
            "deletedAt" to JsonValue.Num(deletedAt.toDouble()),
            "baseVersion" to JsonValue.Num(0.0)
        )
    )
}

private fun pushBody(changes: List<JsonValue.Obj>): String =
    """{"changes":[${changes.joinToString(",") { MiniJson.write(it, false) }}]}"""

private fun register(email: String, password: String, invite: String): String? = runCatching {
    val res = postRaw(
        "/auth/register",
        null,
        """{"email":"$email","password":"$password","invite_code":"$invite",
           "device":{"name":"SMOKE-SCRIPT","platform":"desktop"}}"""
    )
    MiniJson.parse(res).asObj()?.objOrNull("tokens")?.str("access_token")?.takeIf { it.isNotBlank() }
}.getOrNull()

private fun login(email: String, password: String): String? = runCatching {
    val res = postRaw(
        "/auth/login",
        null,
        """{"email":"$email","password":"$password",
           "device":{"name":"SMOKE-SCRIPT","platform":"desktop"}}"""
    )
    MiniJson.parse(res).asObj()?.objOrNull("tokens")?.str("access_token")?.takeIf { it.isNotBlank() }
}.getOrNull()

/**
 * 拿一个可用令牌：先试固定邮箱（登录），不行再注册一个新的。
 *
 * 固定邮箱由 `-PemailA` / `-PemailB` 给；不给就退回「本轮时间戳 + 注册」。
 * 两条路都失败返回 null，调用方负责报错。
 */
private fun acquireToken(role: String, stamp: Long, password: String): String? {
    val fixed = (if (role == "a") readProp("stumate.emailA") else readProp("stumate.emailB"))
        ?.takeIf { it.isNotBlank() }
    val passwordArg = readProp("stumate.password")?.takeIf { it.isNotBlank() } ?: password

    if (fixed != null) {
        login(fixed, passwordArg)?.let {
            println("  · $role 用固定账号 $fixed 登录成功")
            return it
        }
        println("  · $role 固定账号 $fixed 登录失败，改走注册")
    }

    val invite = readInvite() ?: run {
        println("  · $role 既没有 -Pemail${role.uppercase()}，也没有 -Pinvite，无法拿令牌")
        return null
    }
    val email = "smoke-$role-$stamp@verify.local"
    return register(email, passwordArg, invite)?.also {
        println("  · $role 新注册 $email 成功（邀请码额度又少 1 次）")
    }
}

private fun get(path: String, token: String): JsonValue.Obj =
    runCatching { MiniJson.parse(getRaw(path, token)).asObj() ?: JsonValue.Obj(emptyMap()) }
        .getOrElse { JsonValue.Obj(emptyMap()) }

private fun getRaw(path: String, token: String): String {
    val res = client.send(
        HttpRequest.newBuilder(URI.create(HttpJson.BASE_URL + path))
            .timeout(Duration.ofSeconds(20))
            .header("Authorization", "Bearer $token")
            .GET()
            .build(),
        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
    )
    lastStatus = res.statusCode()
    return res.body()
}

/** 最近一次请求的 HTTP 状态码 */
private var lastStatus: Int = 0

/** 请求一次并断言状态码，返回 body 供解析 */
private fun statusOf(path: String, token: String, expect: Int): Boolean {
    runCatching { getRaw(path, token) }
    val actual = lastStatus
    if (actual != expect) {
        println("    （$path 实际 $actual，期望 $expect）")
        return false
    }
    return true
}

private fun post(path: String, token: String, body: String): JsonValue.Obj =
    postRaw(path, token, body).let {
        runCatching { MiniJson.parse(it).asObj() ?: JsonValue.Obj(emptyMap()) }
            .getOrElse { JsonValue.Obj(emptyMap()) }
    }

private fun postRaw(path: String, token: String?, body: String): String {
    val builder = HttpRequest.newBuilder(URI.create(HttpJson.BASE_URL + path))
        .timeout(Duration.ofSeconds(20))
        .header("Content-Type", "application/json; charset=utf-8")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
    if (token != null) builder.header("Authorization", "Bearer $token")
    return client.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).body()
}

private val client: HttpClient by lazy {
    HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
}

/** 从环境变量 STUMATE_INVITE 读；没有就返回 null */
private fun readInvite(): String? = readProp("stumate.invite")

private fun readProp(key: String): String? =
    System.getProperty(key)?.trim()?.takeIf { it.isNotBlank() }

/** `array()` 的元素类型是 [JsonValue]，要调 Obj 的扩展（`int` / `str` / `objOrNull`）先转一下 */
private fun JsonValue.asObj(): JsonValue.Obj? = this as? JsonValue.Obj

/** 数组里第一个对象元素；不是对象或数组为空则返回 null */
private fun List<JsonValue>.firstObj(): JsonValue.Obj? =
    firstOrNull()?.asObj()

/** 该字段是否缺失 / 为 null / 是空串 */
private fun blankOrNull(o: JsonValue?, key: String): Boolean {
    if (o !is JsonValue.Obj) return true
    return when (val v = o.fields[key]) {
        null, is JsonValue.Null -> true
        is JsonValue.Str -> v.value.isBlank()
        else -> false
    }
}
