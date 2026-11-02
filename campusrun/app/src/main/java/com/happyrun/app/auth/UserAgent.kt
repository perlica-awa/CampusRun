package com.happyrun.app.auth

import android.content.Context
import android.webkit.WebSettings

/**
 * 让原生 OkHttp 与 WebView 报同一个 UA。
 *
 * 登录链上既有 WebView 跳转也有原生请求，两边 UA 不一致时校方日志里会出现两种客户端，
 * 也让风控多了一个可区分的信号。WebView 的默认 UA 就是这里取到的值，所以原生侧直接照抄。
 */
object UserAgent {

    private const val FALLBACK =
        "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    @Volatile
    private var value: String? = null

    /** 主线程调用；重复调用无副作用 */
    fun adopt(context: Context) {
        if (value != null) return
        runCatching { value = WebSettings.getDefaultUserAgent(context) }
    }

    val current: String get() = value ?: FALLBACK
}
