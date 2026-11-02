package com.happyrun.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** 安装下载好的 APK：未知来源授权、包名校验、FileProvider 一次性授权。 */
object ApkInstaller {

    private fun authority(context: Context) = "${context.packageName}.fileprovider"

    /** Android 8+ 需要用户允许这个来源安装应用。 */
    fun canInstall(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
            context.packageManager.canRequestPackageInstalls()

    /**
     * 跳转"允许安装未知应用"页面。个别 ROM 没有这个设置页，启动失败时调用方应提示用户
     * 去系统设置里手动开启。
     */
    fun unknownSourcesSettings(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return null
        return Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}"),
        )
    }

    /**
     * 校验下载的 APK 确实属于本应用。防止 GitHub Release 被替换成别的包后，
     * 用户点了安装却装上一个不相干的 App。
     */
    @Suppress("DEPRECATION")
    fun isSameApp(context: Context, apk: File): Boolean {
        val info = context.packageManager.getPackageArchiveInfo(apk.absolutePath, 0) ?: return false
        return info.packageName == context.packageName
    }

    /** 拉起系统安装器。调用前先确认 [canInstall]。 */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, authority(context), apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
