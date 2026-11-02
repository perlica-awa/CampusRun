package com.happyrun.app.auth

import android.content.Context
import android.util.Log
import com.happyrun.app.data.AccountStore
import com.happyrun.app.data.AppSettings
import com.happyrun.app.web.SessionCookies
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/**
 * CAS 会话：票与登录态的唯一入口。
 *
 * 其他组件不再自己拼 CAS 地址、判断落点、清 cookie：
 * - 想"用现成的 TGT 续一次会话" → [renew]；
 * - 手上有一张 `ST-…` 票（登录页 302 的 Location）→ [consume]；
 * - 想用保存的账密换票 → [loginSilently]；
 * - 要换人/注销 → [signOut]；只是会话过期、cookie 可能还能用 → [markExpired]。
 *
 * 登录态与 cookie 同生共死。只有 `logged_in` 一个布尔值不够：必须再记下这套 cookie
 * 属于哪个学号（`session_owner`）。删除当前账号却留下 cookie 时，owner 与当前账号
 * 对不上，[isSignedIn] 会直接判成未登录，不会再拿旧 TGT 把人静默登回去。
 *
 * 线程约定：[renew]/[consume]/[loginSilently] 是阻塞 IO，调用方放 `Dispatchers.IO`；
 * [signOut]/[resumePendingClear] 会操作 WebView cookie 与缓存，必须在主线程。
 */
object CasSession {

    private const val TAG = "CasSession"

    /** 一次"用现有 TGT 换票"的结论 */
    sealed class Renew {
        /** 落到平台主机：会话可用 */
        object LoggedIn : Renew()

        /** 落回 CAS 登录页：TGT 没了，需要人工登录 */
        object NeedLogin : Renew()

        /** 网络或服务端异常，结论不确定 */
        data class Error(val detail: String) : Renew()
    }

    enum class Phase { SIGNED_OUT, CLEARING, SIGNED_IN }

    /** 全应用共用的连接池（cookie 罐落在 [SessionCookies] 上） */
    val http: OkHttpClient get() = AppHttp.client

    private val cas: CasAuthClient by lazy { CasAuthClient(AppHttp.client) }

    /**
     * 每次 [signOut] 自增。登录动作开始前记下它，结束时对不上说明账号已经被切走/删掉，
     * 这次结果必须丢弃，否则在飞的请求会把旧账号的 cookie 又种回来。
     */
    @Volatile
    var generation: Long = 0L
        private set

    private var clearInFlight = false
    private val queued = mutableListOf<() -> Unit>()

    // ── 取票 ────────────────────────────────────────────────────────────────

    /**
     * 用共享 cookie 罐里现成的 CASTGC 走一遍平台入口，把新票交给服务端消费。
     *
     * 落地平台主机 = 换票成功（PHPSESSID 已被服务端重写）；落回 CAS 登录页 = TGT 已过期。
     * 失败用返回值表达，IOException 只往上归成 [Renew.Error]。
     */
    fun renew(entryUrl: String): Renew = try {
        AppHttp.client.newCall(
            Request.Builder()
                .url(entryUrl)
                .header("User-Agent", UserAgent.current)
                .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .header("Accept-Language", "zh-CN,zh;q=0.9")
                .get()
                .build()
        ).execute().use { response ->
            val landed = response.request.url
            Log.i(TAG, "换票落地 ${landed.host}${landed.encodedPath} (HTTP ${response.code})")
            when (landed.host) {
                platformUrls.platformHost -> Renew.LoggedIn
                platformUrls.casHost -> Renew.NeedLogin
                else -> Renew.Error("落到了 ${landed.host}")
            }
        }
    } catch (e: IOException) {
        Log.w(TAG, "换票失败：${e.message}")
        Renew.Error(e.message ?: "网络异常")
    }

    /** 消费登录页 302 带来的 `ST-…` 票；落点判定与 [CasAuthClient.consumeTicket] 一致 */
    fun consume(location: String): CasConsume = cas.consumeTicket(location)

    /** 只用账密换票，撞到二次验证立刻停（绝不发短信） */
    fun loginSilently(username: String, password: String): SilentLoginOutcome =
        cas.loginSilently(username, password)

    fun flush() = SessionCookies.flush()

    fun evictConnections() = AppHttp.evictConnections()

    // ── 登录态状态机 ────────────────────────────────────────────────────────

    fun phase(context: Context): Phase {
        val settings = settings(context)
        return when {
            settings.clearPending -> Phase.CLEARING
            settings.loggedIn -> Phase.SIGNED_IN
            else -> Phase.SIGNED_OUT
        }
    }

    /** 当前 cookie 属于哪个学号；null 表示未知（升级前的旧数据/未保存账号的网页登录） */
    fun owner(context: Context): String? = settings(context).sessionOwner

    /**
     * 现在能不能进跑步页。除了"已登录"，还要求 cookie 的归属与当前账号一致。
     *
     * [ownerMatches] 的兼容规则：owner 或账号缺失时都按"一致"处理——宁可放行，
     * 也不要在升级后把老用户直接挡在登录页。
     */
    fun isSignedIn(context: Context, store: AccountStore): Boolean =
        phase(context) == Phase.SIGNED_IN &&
            ownerMatches(owner(context), store.active()?.username)

    /** 登录成功：标记已登录，并记下这套 cookie 属于谁 */
    fun markSignedIn(context: Context, username: String?) {
        val settings = settings(context)
        settings.markLoggedIn()
        settings.sessionOwner = username ?: settings.sessionOwner
        Log.i(TAG, "会话已登录：账号=${username ?: "-"} ${SessionCookies.describe()}")
    }

    /**
     * 会话过期但仍可能可以换票：只把标记降下来，**保留 cookie**。
     *
     * 跑步页发现页面跳回 CAS / `pages/login` 时用这个；随后 [renew] 需要那张 CASTGC。
     * 要真正作废会话请用 [signOut]。
     */
    fun markExpired(context: Context) {
        settings(context).markLoggedOut()
        Log.i(TAG, "标记会话失效（保留 cookie 以便换票）")
    }

    /**
     * 注销 / 切换账号 / 删除当前账号：清 cookie、清页面数据，并把登录标记落下。
     *
     * 状态先同步落盘（`logged_in=false`、`owner=null`、`clear_pending=true`），再异步清 cookie。
     * `clear_pending` 让下一次启动能补做没清完的那一步——进程恰好死在清除中途时，
     * 旧 cookie 不会带着旧账号继续自动登录。
     *
     * 单飞：清除进行中再次调用只排队回调，不会并行清两遍。
     */
    fun signOut(context: Context, onDone: (() -> Unit)? = null) {
        val app = context.applicationContext
        val settings = settings(app)
        settings.markLoggedOut()
        settings.sessionOwner = null
        settings.clearPending = true
        generation++
        AppHttp.evictConnections()
        Log.i(TAG, "注销会话：清除 cookie 与页面数据")
        runClear(app, settings, onDone)
    }

    /** 进程上次死在清 cookie 中途时，入口处补做一次；没有待办就立即回调 */
    fun resumePendingClear(context: Context, onDone: () -> Unit) {
        val app = context.applicationContext
        val settings = settings(app)
        if (!settings.clearPending && !clearInFlight) {
            onDone()
            return
        }
        Log.i(TAG, "补做上次未完成的会话清理")
        runClear(app, settings, onDone)
    }

    private fun runClear(app: Context, settings: AppSettings, onDone: (() -> Unit)?) {
        if (onDone != null) queued += onDone
        if (clearInFlight) return
        clearInFlight = true
        SessionCookies.clearAll(app) {
            settings.clearPending = false
            clearInFlight = false
            val callbacks = queued.toList()
            queued.clear()
            callbacks.forEach { runCatching(it) }
        }
    }

    /**
     * cookie 归属与当前账号是否一致。owner 或账号未知时按一致处理（兼容旧数据）。
     * 纯函数，单测直接覆盖。
     */
    internal fun ownerMatches(owner: String?, username: String?): Boolean =
        owner == null || username == null || owner == username

    private fun settings(context: Context): AppSettings = AppSettings(context.applicationContext)
}
