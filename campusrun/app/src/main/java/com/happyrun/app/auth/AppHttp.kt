package com.happyrun.app.auth

import android.os.Looper
import com.happyrun.app.web.SessionCookies
import okhttp3.OkHttpClient
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 全应用共用的 OkHttp。cookie 罐直接落在 WebView 的 CookieManager 上（[SessionCookies]），
 * 所以原生登录种下的 CASTGC、兑票写回的 PHPSESSID，页面都能看到；反过来也一样。
 */
object AppHttp {

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .cookieJar(SessionCookies)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /**
     * 丢掉所有空闲连接。VPN 隧道建好/换路由之后必须调一次：
     * 旧 socket 还按原来的路径存在，复用它们等于绕过隧道。
     * `CasAuthClient` 里的两个 client 都是 `client.newBuilder()` 出来的，共用同一个连接池。
     *
     * 关闭空闲连接会真的读写 socket；如果在主线程同步执行，遇到 TLS 连接会抛
     * [android.os.NetworkOnMainThreadException] 把进程直接杀掉（曾经导致删除账号后
     * 状态没落盘、重启又回来）。所以主线程调用时一律丢到后台线程。
     *
     * [onEvicted] 在连接真正清完之后回主线程执行；VPN 切换路由后要等它再发请求，
     * 否则可能复用旧路由上的 socket。
     */
    fun evictConnections(onEvicted: (() -> Unit)? = null) {
        val evict = Runnable {
            runCatching { client.connectionPool.evictAll() }
            if (onEvicted != null) mainHandler.post(onEvicted)
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            evictExecutor.execute(evict)
        } else {
            evict.run()
        }
    }

    private val mainHandler by lazy { android.os.Handler(Looper.getMainLooper()) }

    private val evictExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "apphttp-evict").apply { isDaemon = true }
    }
}
