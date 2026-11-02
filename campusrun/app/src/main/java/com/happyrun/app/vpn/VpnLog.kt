package com.happyrun.app.vpn

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * VPN 的诊断日志。
 *
 * 三条出口：
 * - logcat（tag `CampusRunVpn`）；
 * - 内存环形缓冲，给应用内的"查看连接日志"用（这台机器 logcat 缓冲转得很快，靠它回溯不稳）；
 * - cacheDir/vpn.log，debuggable 包可以直接 `adb shell run-as com.happyrun.app cat cache/vpn.log` 取，
 *   进程被杀也不丢。
 *
 * **写入前必须自己脱敏**：密码与验证码一律不进这里（[OpenVpnService] 发送命令时已处理）。
 */
object VpnLog {

    private const val TAG = "CampusRunVpn"
    private const val MAX_LINES = 600
    private const val MAX_FILE_BYTES = 256 * 1024L
    private const val FILE_NAME = "vpn.log"

    private val lines = ArrayDeque<String>()
    private val lock = Any()
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        if (logFile != null) return
        val file = File(context.applicationContext.cacheDir, FILE_NAME)
        if (file.length() > MAX_FILE_BYTES) file.delete()
        logFile = file
    }

    fun add(message: String) = write("I", message)

    fun debug(message: String) = write("D", message)

    private fun write(level: String, message: String) {
        val line = "${timeFormat.format(Date())} $message"
        if (level == "D") Log.d(TAG, message) else Log.i(TAG, message)
        synchronized(lock) {
            while (lines.size >= MAX_LINES) lines.removeFirst()
            lines.addLast(line)
            logFile?.let { file ->
                runCatching {
                    if (file.length() > MAX_FILE_BYTES) file.delete()
                    file.appendText(line + "\n")
                }
            }
        }
    }

    /** 最新的在最上面：手机上打开就能看到刚才发生了什么 */
    fun dump(): String = synchronized(lock) {
        if (lines.isEmpty()) "(暂无日志)" else lines.reversed().joinToString("\n")
    }

    fun clear() {
        synchronized(lock) {
            lines.clear()
            logFile?.delete()
        }
    }
}
