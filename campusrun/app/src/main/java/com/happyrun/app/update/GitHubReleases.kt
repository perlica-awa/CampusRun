package com.happyrun.app.update

import com.happyrun.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** GitHub 上的一个发布版本，只保留更新流程需要的字段。 */
data class ReleaseInfo(
    /** 规范化后的版本号（去掉 tag 的 v 前缀），例如 `0.2.0`。 */
    val version: String,
    val tag: String,
    val notes: String,
    val pageUrl: String,
    val apkName: String,
    val apkUrl: String,
    val apkSize: Long,
    /** GitHub 新接口返回的 `sha256:...`；老数据可能为空。 */
    val apkSha256: String?,
)

class UpdateException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * 通过 GitHub Releases API 检查并下载安装包。
 *
 * 这里刻意不共用 [com.happyrun.app.auth.AppHttp]：那个 client 的 cookie 罐连着
 * WebView 的校园会话，绝不能把校内 cookie 发给 github.com。GitHub 也不要求 cookie，
 * 独立 client 更安全，顺便避开学校域名的代理/证书配置。
 */
object GitHubReleases {

    const val REPO = "perlica-awa/CampusRun"
    const val REPO_URL = "https://github.com/$REPO"
    const val REPO_DISPLAY = "github.com/$REPO"

    private const val API_LATEST = "https://api.github.com/repos/$REPO/releases/latest"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    private fun request(url: String) = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("User-Agent", "CampusRun/${BuildConfig.VERSION_NAME}")
        .build()

    /** 最近一次正式发布（GitHub 会自动排除 draft 与 prerelease）。 */
    suspend fun fetchLatest(): ReleaseInfo = withContext(Dispatchers.IO) {
        client.newCall(request(API_LATEST)).execute().use { response ->
            when {
                response.code == 404 -> throw UpdateException("仓库还没有发布任何版本")
                !response.isSuccessful -> throw UpdateException("GitHub 返回 HTTP ${response.code}")
            }
            val text = response.body?.string()
                ?: throw UpdateException("GitHub 返回了空响应")
            parseRelease(JSONObject(text))
        }
    }

    internal fun parseRelease(json: JSONObject): ReleaseInfo {
        val tag = json.optString("tag_name").trim()
        if (tag.isEmpty()) throw UpdateException("发布信息里没有版本号")

        val asset = pickApk(json.optJSONArray("assets"))
            ?: throw UpdateException("这次发布没有附带 APK")

        val digest = asset.optString("digest").trim()
            .takeIf { it.startsWith("sha256:", ignoreCase = true) }
            ?.substringAfter(':')
            ?.takeIf { it.isNotBlank() }

        return ReleaseInfo(
            version = AppVersion.normalize(tag),
            tag = tag,
            notes = json.optString("body").orEmpty(),
            pageUrl = json.optString("html_url").orEmpty(),
            apkName = asset.optString("name").ifBlank { "campusrun-$tag.apk" },
            apkUrl = asset.optString("browser_download_url"),
            apkSize = asset.optLong("size"),
            apkSha256 = digest,
        )
    }

    /** 优先选名字里带 campusrun 的 APK，避免仓库以后挂多个安装包时选错。 */
    private fun pickApk(assets: JSONArray?): JSONObject? {
        if (assets == null) return null
        var fallback: JSONObject? = null
        for (i in 0 until assets.length()) {
            val asset = assets.optJSONObject(i) ?: continue
            val name = asset.optString("name")
            if (!name.endsWith(".apk", ignoreCase = true)) continue
            if (name.contains("campusrun", ignoreCase = true)) return asset
            if (fallback == null) fallback = asset
        }
        return fallback
    }

    /**
     * 下载到 [target]（先写 `.part`，校验通过再改名），返回最终文件。
     * [onProgress] 在 IO 线程回调 0–100，调用方自行切回主线程。
     */
    suspend fun download(
        release: ReleaseInfo,
        target: File,
        onProgress: (Int) -> Unit,
    ): File = withContext(Dispatchers.IO) {
        if (release.apkUrl.isBlank()) throw UpdateException("APK 下载地址为空")
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, "${target.name}.part")
        part.delete()

        client.newCall(request(release.apkUrl)).execute().use { response ->
            if (!response.isSuccessful) throw UpdateException("下载失败：HTTP ${response.code}")
            val body = response.body ?: throw UpdateException("下载响应为空")
            val total = body.contentLength().takeIf { it > 0 } ?: release.apkSize
            var read = 0L
            var lastPercent = -1

            body.byteStream().use { input ->
                part.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        read += count
                        val percent = if (total > 0) {
                            ((read * 100) / total).toInt().coerceIn(0, 100)
                        } else {
                            0
                        }
                        if (percent != lastPercent) {
                            lastPercent = percent
                            onProgress(percent)
                        }
                    }
                }
            }

            if (release.apkSize > 0 && read != release.apkSize) {
                part.delete()
                throw UpdateException("下载不完整（$read/${release.apkSize} 字节）")
            }
        }

        release.apkSha256?.let { expected ->
            val actual = sha256(part)
            if (!actual.equals(expected, ignoreCase = true)) {
                part.delete()
                throw UpdateException("安装包校验失败（SHA-256 不一致）")
            }
        }

        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        target
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
