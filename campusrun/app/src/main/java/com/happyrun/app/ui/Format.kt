package com.happyrun.app.ui

import java.util.Locale

object Format {
    /** 5'30" 形式。 */
    fun pace(secPerKm: Int): String =
        String.format(Locale.US, "%d'%02d\"", secPerKm / 60, secPerKm % 60)

    /** 安装包大小：B / KB / MB，保留一位小数。 */
    fun size(bytes: Long): String = when {
        bytes < 1024 -> String.format(Locale.US, "%d B", bytes)
        bytes < 1024 * 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }
}
