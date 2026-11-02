package com.happyrun.app.auth

import com.happyrun.app.BuildConfig
import java.net.URLEncoder

/**
 * 统一身份认证与跑步平台的地址。
 *
 * 站点地址不写进源码：构建时从 `target.properties` 或环境变量注入 BuildConfig，
 * 仓库里始终保持空值。换一所学校只需要提供自己的配置，代码不必改动。
 *
 * CAS 只给注册过的 service 发 `ST-` 票，因此 [platformEntry] 必须与平台跳转时携带的
 * 那一串完全一致，不能替换为登录首页或其他入口。
 */
class PlatformUrls(
    val casHost: String = BuildConfig.TARGET_CAS_HOST,
    val platformHost: String = BuildConfig.TARGET_PLATFORM_HOST,
    val platformEntry: String = BuildConfig.TARGET_PLATFORM_ENTRY,
) {

    /** 三项都注入后才允许发起登录；否则界面提示未配置站点。 */
    val configured: Boolean
        get() = casHost.isNotBlank() && platformHost.isNotBlank() && platformEntry.isNotBlank()

    val casOrigin: String
        get() = "https://$casHost"

    /** CAS 登录页（带 service），与浏览器从平台跳过去时完全一致。 */
    val casLoginEntry: String
        get() = "https://$casHost/tpass/login?service=" +
            URLEncoder.encode(platformEntry, "UTF-8")

    /** 这个 URL 是否已经落在平台主机上（而不是 CAS）。 */
    fun isPlatformUrl(url: String?): Boolean {
        if (platformHost.isBlank()) return false
        val host = hostOf(url) ?: return false
        return host == platformHost || host.endsWith(".$platformHost")
    }

    /** 这个 URL 是否是 CAS 登录页（说明当前没有有效 TGT）。 */
    fun isCasUrl(url: String?): Boolean {
        if (casHost.isBlank()) return false
        val host = hostOf(url) ?: return false
        return host == casHost || host.endsWith(".$casHost")
    }

    private fun hostOf(url: String?): String? =
        url?.let { runCatching { java.net.URI(it).host }.getOrNull() }
}

/** 应用内使用的默认实例，取值来自构建时注入的 BuildConfig。 */
val platformUrls = PlatformUrls()
