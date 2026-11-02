package com.happyrun.app.web

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.webkit.WebView
import com.happyrun.app.auth.platformUrls
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 全应用唯一的 cookie 所有者。
 *
 * 原生 HTTP 与 WebView 共用同一份 [CookieManager]：页面登录种下的 `CASTGC`，原生换票看得到；
 * 原生换票写回的 `PHPSESSID`，页面也看得到。因此读、写、清都必须只在这里发生——
 * 任何绕开本模块直接调 `CookieManager` 的代码都会让"会话到底属于谁"重新变成猜测。
 *
 * cookie 的寿命由服务端决定（`CASTGC` Max-Age 7200、`PHPSESSID` 是会话 cookie），
 * 这里不维护自己的过期时间。"还在不在"只按 host + path 查；"还算不算登录"由
 * [com.happyrun.app.auth.CasSession] 结合账号归属判断。
 */
object SessionCookies : CookieJar {

    private const val TAG = "SessionCookies"

    /** `CASTGC` 只对 /tpass/ 路径可见，必须按路径查，不能只查 host */
    private val CAS_TGC_URL = "https://${platformUrls.casHost}/tpass/"
    private val PLATFORM_URL = "https://${platformUrls.platformHost}/"

    private const val CASTGC = "CASTGC"
    private const val PHPSESSID = "PHPSESSID"

    /** 清除超时：回调万一不触发，也不能让 [isClearing] 永远为真 */
    private const val CLEAR_TIMEOUT_MS = 5_000L

    /** 清除期间丢弃所有 Set-Cookie，避免在飞请求把旧账号的会话种回来 */
    @Volatile
    private var clearing = false

    fun isClearing(): Boolean = clearing

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val raw = runCatching { CookieManager.getInstance().getCookie(url.toString()) }.getOrNull()
            ?: return emptyList()
        return raw.split(";").mapNotNull { Cookie.parse(url, it.trim()) }
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (clearing) {
            Log.d(TAG, "正在清会话，丢弃 ${cookies.size} 个 Set-Cookie")
            return
        }
        val manager = CookieManager.getInstance()
        cookies.forEach { manager.setCookie(url.toString(), it.toString()) }
        manager.flush()
    }

    /** WebView cookie 写在内存里，进程被杀前必须 flush 才会真正落盘 */
    fun flush() {
        runCatching { CookieManager.getInstance().flush() }
    }

    fun hasTgt(): Boolean = hasCookie(CAS_TGC_URL, CASTGC)

    fun hasPlatformSession(): Boolean = hasCookie(PLATFORM_URL, PHPSESSID)

    /** 一行不含凭据的状态，只用于日志与排查 */
    fun describe(): String = "CASTGC=${hasTgt()} PHPSESSID=${hasPlatformSession()}"

    /** 网页登录页与平台页面都需要接受第三方 cookie */
    fun attach(webView: WebView) {
        CookieManager.getInstance().apply {
            setAcceptCookie(true)
            setAcceptThirdPartyCookies(webView, true)
        }
    }

    /**
     * 只清页面数据（localStorage/sessionStorage），**不动 cookie**。
     *
     * 页面跳回登录页、准备用现成 TGT 换票时用这个：上一次的 `localStorage.user`
     * （uid/token/sign）已经失效，但 cookie 里的 TGT 可能还活着，不能一起删。
     */
    fun clearPageData() {
        runCatching { WebStorage.getInstance().deleteAllData() }
    }

    /**
     * 清空整套会话：cookie + localStorage/sessionStorage + 页面缓存。
     *
     * 切换账号、注销、删除当前账号都必须走这里，否则 CAS 会拿旧 TGT 静默换回上一个账号。
     * 必须在主线程调用（内部会临时建一个 WebView 清缓存）。回调保证只触发一次，
     * 即使 [CookieManager.removeAllCookies] 的回调没来，也会在 5 秒后兜底放行。
     */
    fun clearAll(context: Context, onDone: (() -> Unit)? = null) {
        clearing = true
        val appContext = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val done = AtomicBoolean(false)
        val finish = Runnable {
            if (done.compareAndSet(false, true)) {
                clearing = false
                onDone?.invoke()
            }
        }

        val timeout = Runnable { finish.run() }
        handler.postDelayed(timeout, CLEAR_TIMEOUT_MS)

        CookieManager.getInstance().removeAllCookies {
            handler.removeCallbacks(timeout)
            // CookieManager 的回调线程不保证是主线程。清缓存要建 WebView、调用方的收尾
            // 往往是 Toast/刷新界面，都必须回主线程，否则异常会被 runCatching 静默吞掉。
            handler.post {
                runCatching {
                    CookieManager.getInstance().flush()
                    clearPageData()
                    clearCache(appContext)
                }.onFailure { Log.w(TAG, "清会话时出错：${it.message}") }
                finish.run()
            }
        }
    }

    private fun clearCache(context: Context) {
        val probe = WebView(context)
        probe.clearCache(true)
        probe.clearFormData()
        probe.clearHistory()
        probe.destroy()
    }

    private fun hasCookie(url: String, key: String): Boolean {
        val raw = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull() ?: return false
        return cookieHeaderHas(raw, key)
    }

    /**
     * cookie 头里是否有名为 [key] 的项。只认 `name=value` 形式，避免 `XCASTGC` 这类前缀误匹配。
     * 纯函数，单测直接覆盖。
     */
    internal fun cookieHeaderHas(header: String, key: String): Boolean =
        header.split(";").any { token ->
            val trimmed = token.trim()
            val equals = trimmed.indexOf('=')
            equals > 0 && trimmed.substring(0, equals).trim().equals(key, ignoreCase = true)
        }
}
