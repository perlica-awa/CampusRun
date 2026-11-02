package com.happyrun.app.auth

import android.util.Base64
import android.util.Log
import com.happyrun.app.BuildConfig
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher

/** 登录页里这次要用的东西。lt 与 execution 都是一次性的，每次加载现取，绝不缓存 */
data class CasLoginForm(
    val action: String,
    val lt: String,
    val execution: String,
    val publicKeyB64: String,
)

/**
 * 二验页的表单。[hidden] 是 `#second_auth_form` 里的全部隐藏字段，**原样重放**——
 * 我们只覆盖 imgCode/scendAuthCode/method/_eventId 四个，于是永远不需要预先知道字段表，
 * 校方加字段也不会把我们打挂。
 *
 * 可序列化是为了能随 Intent 从"静默续期"那一侧交给登录页继续用：账密刚被服务端接受，
 * 表单里的一次性 token 还在，没必要让登录页把同样的账密再提交一遍。
 */
data class CasSecondFactorForm(
    val action: String,
    val hidden: Map<String, String>,
    /** mobile / email / wechatQrCode，由服务端渲染了哪个 #secondAuthBy* 按钮决定 */
    val mode: String,
) : java.io.Serializable

/** 加载登录页的三种去向 */
sealed class CasFormLoad {
    data class Ok(val form: CasLoginForm) : CasFormLoad()

    /** 一上来就 302：本地有活着的 TGT，不必输账密，跟着这条 Location 走即可 */
    data class Redirected(val location: String) : CasFormLoad()

    data class Failed(val detail: String) : CasFormLoad()
}

/** 一次提交（账密或二验）的判定 */
sealed class CasStep {
    /** 302 且 Location 带 ticket=ST-：通过，票在 Location 里等着被服务端消费 */
    data class Ticket(val location: String) : CasStep()

    /** 200 且是二验页：账密通过了，但必须过验证码 */
    data class SecondFactor(val form: CasSecondFactorForm) : CasStep()

    /** 200 且回到登录页并带错误文案；null = 被弹回表单但服务端没给文案 */
    data class BadCredentials(val serverMessage: String?) : CasStep()

    /** 既不是票也不是能识别的页面：结构变了或响应异常 */
    data class Unclear(val detail: String) : CasStep()
}

/** 发短信那一步的结局 */
sealed class CasSmsResult {
    object Sent : CasSmsResult()
    data class Rejected(val message: String) : CasSmsResult()
    data class Failed(val detail: String) : CasSmsResult()
}

/** 兑票落地结果：落到平台主机才算拿到；落回 CAS 说明没有有效 TGT */
sealed class CasConsume {
    /** 票被服务端消费，[finalUrl] 是最终落地地址（通常带 token/sign/uid） */
    data class Ok(val finalUrl: String) : CasConsume()

    /** 落回 CAS 登录页：TGT 已经没了 */
    object NoTgt : CasConsume()

    /** 落到了别的地址：service 配置或服务端行为变了 */
    data class Elsewhere(val host: String, val finalUrl: String) : CasConsume()

    data class Error(val detail: String) : CasConsume()
}

/** 静默重登（只用账密换 TGT，不替用户做二次验证）的结局 */
sealed class SilentLoginOutcome {
    object Success : SilentLoginOutcome()

    /**
     * 账号密码通过了，但校方要求短信/图形二次验证——已停在"发短信之前"。
     * [form] 已被服务端接受，交给登录页继续用，用户只需补验证码。
     */
    data class NeedSecondFactor(val form: CasSecondFactorForm) : SilentLoginOutcome()

    /** 账号密码被拒 */
    object Rejected : SilentLoginOutcome()

    /** 网络异常或页面结构无法识别 */
    data class Error(val detail: String) : SilentLoginOutcome()
}

/**
 * 原生 CAS 客户端：不再用 WebView 驱动登录页，自己把表单走完。
 *
 * service 必须是平台入口（[platformUrls.platformEntry]），除此之外的跳转地址都拿不到票。
 *
 * 实测可用的关键点（勿随手改动）：
 * - 提交字段与页面自己的 `login()` 一致：`rsa`（RSA(账号+密码)）、`ul`/`pl`（明文长度）、
 *   `lt`、`execution`、`_eventId=submit`，外加三个恒为空的 `t_un`/`t_pd`/`t_c`；
 * - RSA 公钥优先从页面脚本现场解析，解析不到才用兜底常量；
 * - 二验页的隐藏字段原样重放，只覆盖四个已知字段。
 *
 * IOException 一律向上抛，由调用方归类成网络问题；判定失败用返回值表达，不用异常。
 */
class CasAuthClient(private val client: OkHttpClient) {

    companion object {
        private const val TAG = "CasAuthClient"

        private val CAS_ORIGIN = platformUrls.casOrigin
        private const val LOGIN_PATH = "/tpass/login"
        private const val CAPTCHA_PATH = "/tpass/code"
        private const val SMS_PATH = "/tpass/secondAuthCode"

        /** 公钥脚本路径，构建时注入；为空则直接用兜底公钥 */
        private val LOGIN_JS_PATH = BuildConfig.TARGET_LOGIN_JS_PATH

        /** 兜底公钥：页面脚本取不到时使用 */
        private const val FALLBACK_PUBKEY =
            "MIIBIjANBgkqhkiG9w0BAQEFAAOCAQ8AMIIBCgKCAQEAnjA28DLKXZzxb" +
                "Kmo9/1WkVLf1mr+wtLXLXt6sC4WiBCtsbzF5ewm7ARZeAdS3iZtqlYPn6" +
                "IcUoOw42H8nAK/tfFcIb6dZ1K0atn0U39oWCGPzYuKtLJeMuNZiDXVuA" +
                "XtojrckOjLW9B3gUnaNGLuIx0fYe66l0o9WjU2cGLNZQfiIxs2h00z1EA" +
                "9IdSnVxiVQWSD+lsP3JZXh2TT287la4Y4603SQNKTK/QvXfcmccwTEd1I" +
                "W6HwGxD6QrkInBiHisKWxmveN7UDSaQRZ/J97G0YC32pD38WT53izXeK0" +
                "p/kU/X37VP555um1wVWFvPIuc9I7gMP1+hq5a+X6c++tQIDAQAB"

        private const val SECOND_FACTOR_FORM_ID = "second_auth_form"

        /** 这四个字段由我们覆盖，其余隐藏字段原样重放 */
        private val OVERRIDDEN_FIELDS = setOf("imgCode", "scendAuthCode", "method", "_eventId")

        /** RSA-2048 + PKCS#1 v1.5 的明文上限：256 - 11 = 245 字节 */
        private const val MAX_PLAINTEXT_BYTES = 245
    }

    /** 请求类型：导航（document）还是脚本发起的 XHR。两者的 Sec-Fetch-* 不一样 */
    private enum class Kind { DOCUMENT, XHR }

    /** 浏览器导航必带的头。少了它们，一个自称 Chrome 的客户端在风控眼里就很可疑 */
    private fun navHeaders(builder: Request.Builder, kind: Kind, referer: String? = null) {
        builder.header("User-Agent", UserAgent.current)
        builder.header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
        builder.header("Upgrade-Insecure-Requests", "1")
        if (kind == Kind.DOCUMENT) {
            builder.header(
                "Accept",
                "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            )
            builder.header("Sec-Fetch-Dest", "document")
            builder.header("Sec-Fetch-Mode", "navigate")
            builder.header("Sec-Fetch-Site", if (referer == null) "none" else "same-origin")
            builder.header("Sec-Fetch-User", "?1")
        } else {
            builder.header("Accept", "application/json, text/plain, */*")
            builder.header("Sec-Fetch-Dest", "empty")
            builder.header("Sec-Fetch-Mode", "cors")
            builder.header("Sec-Fetch-Site", "same-origin")
            builder.header("X-Requested-With", "XMLHttpRequest")
        }
        referer?.let { builder.header("Referer", it) }
    }

    private val noRedirect = client.newBuilder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val follow = client.newBuilder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    // ── 静默重登：一条不用界面的完整路径 ─────────────────────────────────────

    /**
     * 只用账密换一次 TGT 并兑票，全程无界面。
     *
     * **撞到二次验证立刻停**——短信只有 [requestSmsCode] 才会真的发出去，本方法永远不调用它。
     * 这是静默路径的安全边界，也是它能在后台被调用的前提。
     */
    fun loginSilently(username: String, password: String): SilentLoginOutcome {
        return when (val load = fetchLoginForm()) {
            is CasFormLoad.Failed -> SilentLoginOutcome.Error(load.detail)

            // 本地 TGT 还活着：CAS 直接给票，不必输账密
            is CasFormLoad.Redirected -> {
                Log.i(TAG, "本地 TGT 仍可用，直接兑票")
                consume(load.location)
            }

            is CasFormLoad.Ok -> when (val step = submitCredentials(load.form, username, password)) {
                is CasStep.Ticket -> {
                    Log.i(TAG, "账密通过，已拿到 ticket，跟随消费")
                    consume(step.location)
                }

                is CasStep.SecondFactor -> {
                    Log.i(TAG, "账密通过，但服务端要求二次验证；静默到此为止（不发短信）")
                    SilentLoginOutcome.NeedSecondFactor(step.form)
                }

                is CasStep.BadCredentials -> {
                    Log.w(TAG, "账密被拒（服务端文案：${step.serverMessage ?: "未给出"}）")
                    SilentLoginOutcome.Rejected
                }

                is CasStep.Unclear -> SilentLoginOutcome.Error(step.detail)
            }
        }
    }

    private fun consume(location: String): SilentLoginOutcome =
        when (val result = consumeTicket(location)) {
            is CasConsume.Ok -> SilentLoginOutcome.Success
            CasConsume.NoTgt -> SilentLoginOutcome.Error("CAS 没有签发新票")
            is CasConsume.Elsewhere -> SilentLoginOutcome.Error("兑票落到了 ${result.host}")
            is CasConsume.Error -> SilentLoginOutcome.Error(result.detail)
        }

    // ── 换票：交互登录与静默登录共用的最后一步 ───────────────────────────────

    /**
     * 把票交给平台消费并跟随重定向。落地平台主机才算成功；
     * 落回 CAS 的 200 登录页说明 TGT 已经没了。
     */
    fun consumeTicket(
        location: String,
        expectedHost: String = platformUrls.platformHost,
    ): CasConsume {
        return try {
            follow.newCall(
                Request.Builder().url(location).header("User-Agent", UserAgent.current).get().build()
            ).execute().use { resp ->
                val landed = resp.request.url
                val finalUrl = landed.toString()
                Log.d(TAG, "兑票落地 $landed (HTTP ${resp.code}，期望主机 $expectedHost)")
                when (landed.host) {
                    expectedHost -> CasConsume.Ok(finalUrl)
                    platformUrls.casHost -> CasConsume.NoTgt
                    else -> CasConsume.Elsewhere(landed.host, finalUrl)
                }
            }
        } catch (e: IOException) {
            Log.w(TAG, "兑票链跟随失败", e)
            CasConsume.Error(e.message ?: "网络异常")
        }
    }

    // ── 账密登录：分三步，便于 UI 在中间插验证码 ──────────────────────────────

    /**
     * 加载登录页并取出 lt/execution/表单地址/公钥。
     *
     * 二验的判定不需要渲染：登录页里 `second_auth_form`/`scendAuthCode`/`imgCode` 出现次数
     * 为 0，它们只在二验页出现；反过来二验页也会有 `#loginForm`，所以 [classify] 先判二验。
     */
    fun fetchLoginForm(): CasFormLoad {
        val entry = platformUrls.casLoginEntry
        val (code, location, html) = get(entry, noRedirect)
        if (code in 300..399) {
            return if (location == null) CasFormLoad.Failed("$code 但没有 Location")
            else CasFormLoad.Redirected(location)
        }
        if (code != 200) return CasFormLoad.Failed("登录页返回 HTTP $code")

        val doc = Jsoup.parse(html)
        val form = doc.selectFirst("form#loginForm")
            ?: return CasFormLoad.Failed("登录页里没找到 form#loginForm（页面结构可能变了）")
        val lt = form.selectFirst("input#lt")?.attr("value").orEmpty()
        val execution = form.selectFirst("input[name=execution]")?.attr("value").orEmpty()
        if (lt.isEmpty() || execution.isEmpty()) {
            return CasFormLoad.Failed("lt/execution 解析不全（lt=${lt.take(12)}…, execution='$execution'）")
        }
        return CasFormLoad.Ok(
            CasLoginForm(
                action = resolveAction(form.attr("action")),
                lt = lt,
                execution = execution,
                publicKeyB64 = fetchPublicKey(entry),
            )
        )
    }

    /**
     * 提交账密。不跟随重定向：302 的 Location 带不带 ticket 是最干净的判据。
     *
     * 密码只出现在 [rsaEncrypt] 的入参里，任何时候都不进日志；`pl` 传的是**长度**不是密码。
     */
    fun submitCredentials(form: CasLoginForm, username: String, password: String): CasStep {
        val plain = username + password
        if (plain.toByteArray(StandardCharsets.UTF_8).size > MAX_PLAINTEXT_BYTES) {
            return CasStep.Unclear("账号+密码超过 $MAX_PLAINTEXT_BYTES 字节，RSA-2048 装不下")
        }
        val body = FormBody.Builder()
            .add("rsa", rsaEncrypt(form.publicKeyB64, plain))
            .add("ul", username.length.toString())
            .add("pl", password.length.toString())
            .add("lt", form.lt)
            .add("execution", form.execution)
            .add("_eventId", "submit")
            .add("t_un", "")
            .add("t_pd", "")
            .add("t_c", "")
            .build()
        val (code, location, html) = post(form.action, body)
        return classify(code, location, html)
    }

    /** 图形验证码图片（服务端给 image/jpeg，原样返回交给 UI 解码）。失败返回 null */
    fun fetchCaptcha(): ByteArray? {
        val builder = Request.Builder().url("$CAS_ORIGIN$CAPTCHA_PATH?${System.currentTimeMillis()}")
        navHeaders(builder, Kind.XHR, platformUrls.casLoginEntry)
        return follow.newCall(builder.get().build()).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.bytes() else null
        }
    }

    /**
     * 触发短信。**这是唯一会让短信真的发出去的接口**，所以只在交互路径上、由用户点击调用；
     * 静默路径永远不碰它。
     */
    fun requestSmsCode(form: CasSecondFactorForm, captcha: String): CasSmsResult {
        val body = FormBody.Builder()
            .add("code", captcha)
            .add("method", form.mode)
            .build()
        val builder = Request.Builder().url("$CAS_ORIGIN$SMS_PATH")
        navHeaders(builder, Kind.XHR, platformUrls.casLoginEntry)
        builder.header("Origin", CAS_ORIGIN)
        builder.post(body)
        return noRedirect.newCall(builder.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                CasSmsResult.Failed("HTTP ${resp.code}: ${text.take(120)}")
            } else {
                try {
                    val json = JSONObject(text)
                    if (json.optBoolean("success")) CasSmsResult.Sent
                    else CasSmsResult.Rejected(json.optString("message"))
                } catch (e: Exception) {
                    CasSmsResult.Failed("响应不是 JSON：${text.take(120)}")
                }
            }
        }
    }

    /**
     * 提交二验（图形码 + 短信码）。隐藏字段原样重放，我们只覆盖那四个，
     * 所以 `RelayState`/`execution` 之类的一次性 token 不需要我们理解。
     */
    fun submitSecondFactor(form: CasSecondFactorForm, captcha: String, smsCode: String): CasStep {
        val builder = FormBody.Builder()
        form.hidden.filterKeys { it !in OVERRIDDEN_FIELDS }
            .forEach { (k, v) -> builder.add(k, v) }
        builder.add("imgCode", captcha)
        builder.add("scendAuthCode", smsCode)
        builder.add("method", form.mode)
        builder.add("_eventId", "submit")
        val (code, location, html) = post(form.action, builder.build())
        return classify(code, location, html)
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 提交后的判定顺序：先票，再二验页，最后登录页（带不带错误文案） */
    private fun classify(code: Int, location: String?, html: String): CasStep {
        if (code in 300..399) {
            if (location == null) return CasStep.Unclear("$code 但没有 Location")
            return when {
                location.contains("ticket=ST-", ignoreCase = true) -> CasStep.Ticket(location)
                location.contains(LOGIN_PATH) -> CasStep.BadCredentials(null)
                else -> CasStep.Unclear("$code 重定向到非登录页且不带 ticket：${location.take(160)}")
            }
        }
        if (code != 200) return CasStep.Unclear("未预期的响应码 $code")

        if (html.contains(SECOND_FACTOR_FORM_ID) || html.contains("scendAuthCode")) {
            return CasStep.SecondFactor(parseSecondFactor(html))
        }
        if (html.contains("id=\"loginForm\"")) {
            return CasStep.BadCredentials(visibleErrorText(html))
        }
        return CasStep.Unclear("200 但既不认识二验页也不认识登录页（${html.length} 字节）")
    }

    private fun parseSecondFactor(html: String): CasSecondFactorForm {
        val doc: Document = Jsoup.parse(html)
        val form = doc.selectFirst("#$SECOND_FACTOR_FORM_ID")
        if (form == null) {
            Log.w(TAG, "响应含 $SECOND_FACTOR_FORM_ID 标记但没有该表单元素")
        }
        val hidden = LinkedHashMap<String, String>()
        form?.select("input[type=hidden]")?.forEach { hidden[it.attr("name")] = it.attr("value") }
        val mode = when {
            doc.selectFirst("#secondAuthByMobile") != null -> "mobile"
            doc.selectFirst("#secondAuthByEmail") != null -> "email"
            doc.selectFirst("#secondAuthByQyQrCode") != null -> "wechatQrCode"
            // 兜底绝不能是 mobile：服务端换了按钮 id 时，我们会给用户一个永远发不出码的面板
            else -> "unknown"
        }
        return CasSecondFactorForm(resolveAction(form?.attr("action").orEmpty()), hidden, mode)
    }

    /**
     * 取出服务端给出的错误文案。
     *
     * `#errormsg` 在干净页面里就带着一段 `display:none` 的占位文案「账号不存在！」，
     * 必须看内联 style；真实文案是服务端渲染进 `#errormsghide` 的。
     */
    private fun visibleErrorText(html: String): String? {
        val doc = Jsoup.parse(html)
        doc.selectFirst("#errormsghide")?.text()?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
        val el = doc.selectFirst("#errormsg") ?: return null
        if (el.attr("style").contains("none")) return null
        return el.text().trim().takeIf { it.isNotEmpty() }
    }

    /** 公钥优先现场解析（服务端换密钥时不会静默失效），失败才回退内置常量 */
    private fun fetchPublicKey(entry: String): String {
        if (LOGIN_JS_PATH.isBlank()) return FALLBACK_PUBKEY
        return try {
            val (code, _, js) = get("$CAS_ORIGIN$LOGIN_JS_PATH", noRedirect, referer = entry)
            if (code == 200) {
                Regex("""publicKeyStr\s*=\s*"([A-Za-z0-9+/=]+)"""").find(js)?.groupValues?.get(1)
                    ?.also {
                        Log.d(TAG, "公钥来自现场脚本（与兜底${if (it == FALLBACK_PUBKEY) "一致" else "不同，请留意"}）")
                    }
            } else {
                null
            }
        } catch (e: IOException) {
            Log.w(TAG, "取公钥脚本失败，用兜底公钥", e)
            null
        } ?: FALLBACK_PUBKEY
    }

    private fun rsaEncrypt(pubKeyB64: String, plain: String): String {
        val key: PublicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(Base64.decode(pubKeyB64, Base64.DEFAULT)))
        // 与页面 jsencrypt 的默认填充一致（已用 .har/_fixture/RsaInteropCheck.java 双向验证）
        val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        return Base64.encodeToString(cipher.doFinal(plain.toByteArray(StandardCharsets.UTF_8)), Base64.NO_WRAP)
    }

    /** 表单 action 可能是绝对地址、站点绝对路径或相对 /tpass/ 的路径；空则回落到登录入口 */
    private fun resolveAction(action: String): String = when {
        action.startsWith("http") -> action
        action.startsWith("/") -> CAS_ORIGIN + action
        action.isEmpty() -> platformUrls.casLoginEntry
        else -> "$CAS_ORIGIN/tpass/$action"
    }

    private fun get(
        url: String,
        http: OkHttpClient,
        referer: String? = null,
    ): Triple<Int, String?, String> {
        val builder = Request.Builder().url(url)
        navHeaders(builder, Kind.DOCUMENT, referer)
        return http.newCall(builder.get().build()).execute().use { resp ->
            Triple(resp.code, resp.header("Location"), resp.body?.string().orEmpty())
        }
    }

    private fun post(url: String, body: FormBody): Triple<Int, String?, String> {
        val builder = Request.Builder().url(url)
        // 表单提交也是导航：Chrome 发它时带的是 document 那套 Sec-Fetch-*
        navHeaders(builder, Kind.DOCUMENT, platformUrls.casLoginEntry)
        builder.header("Origin", CAS_ORIGIN)
        builder.post(body)
        return noRedirect.newCall(builder.build()).execute().use { resp ->
            Triple(resp.code, resp.header("Location"), resp.body?.string().orEmpty())
        }
    }
}
