package com.happyrun.app.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.happyrun.app.R
import com.happyrun.app.ui.VpnActivity
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * 校园 VPN（OpenVPN 2.6，ics-openvpn 的 native 版）。
 *
 * 进程模型与 ics-openvpn 一致，只是外壳是我们自己的，方便把范围限制到"只有本应用走 VPN"：
 *
 * 1. 我们自己建 tun（`VpnService.Builder`，`addAllowedApplication` 决定谁能用这条隧道），
 *    把 fd 通过管理口的 `needok 'OPENTUN' ok` 交给 native 进程；
 * 2. native openvpn 用 `--config stdin` 从标准输入读配置，配置里带
 *    `management <cacheDir>/mgmtsocket unix` + `management-client`，它反过来连我们的本地 socket；
 * 3. 地址/路由/DNS 全部由 openvpn 通过 `>NEED-OK` 提出、我们翻译成 Builder 调用；
 * 4. 认证走 `>PASSWORD`，短信二次验证走 `AUTH_FAILED,CRV1` 动态挑战。
 *
 * native 二进制来自 ics-openvpn（GPLv2），`jniLibs/arm64-v8a/libovpnexec.so` 是启动器，
 * `libopenvpn.so` 是主程序；两者必须同时在 `nativeLibraryDir` 里，启动器靠 `LD_LIBRARY_PATH` 找到它。
 */
class OpenVpnService : VpnService() {

    companion object {
        private const val TAG = "CampusRunVpn"
        private const val CHANNEL_ID = "vpn"
        private const val NOTIF_ID = 1201
        private const val MGMT_SOCKET = "mgmtsocket"
        private const val EXEC_NAME = "libovpnexec.so"
        private const val PROFILE_ASSET = "openvpn/student.ovpn"
        private const val MAX_OTP_ATTEMPTS = 3

        const val ACTION_CONNECT = "com.happyrun.app.vpn.CONNECT"
        const val ACTION_DISCONNECT = "com.happyrun.app.vpn.DISCONNECT"
        const val ACTION_OTP = "com.happyrun.app.vpn.OTP"
        const val EXTRA_OTP = "otp"
        const val EXTRA_APP_ONLY = "app_only"
        const val EXTRA_USERNAME = "username"
        const val EXTRA_PASSWORD = "password"

        fun connect(context: Context, appOnly: Boolean, username: String, password: String) {
            val intent = Intent(context, OpenVpnService::class.java).apply {
                action = ACTION_CONNECT
                putExtra(EXTRA_APP_ONLY, appOnly)
                putExtra(EXTRA_USERNAME, username)
                putExtra(EXTRA_PASSWORD, password)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun disconnect(context: Context) {
            context.startService(Intent(context, OpenVpnService::class.java).apply { action = ACTION_DISCONNECT })
        }

        fun submitOtp(context: Context, code: String) {
            context.startService(
                Intent(context, OpenVpnService::class.java).apply {
                    action = ACTION_OTP
                    putExtra(EXTRA_OTP, code)
                }
            )
        }

        /**
         * 不联网、不要密码的自检：只把 native 启动器跑起来看它能不能打印版本号。
         * 用来区分"隧道配错了"和"二进制根本没跑起来"。阻塞调用，放在 IO 线程。
         */
        fun selfTest(context: Context): String {
            val exec = File(context.applicationInfo.nativeLibraryDir, EXEC_NAME)
            if (!exec.exists()) {
                return "缺少 $EXEC_NAME（当前 ABI ${Build.SUPPORTED_ABIS.firstOrNull()}，只打包了 arm64-v8a）"
            }
            return try {
                val process = ProcessBuilder(exec.absolutePath, "--version")
                    .apply { environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir }
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readText()
                val finished = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    process.waitFor(20, TimeUnit.SECONDS)
                } else {
                    // API 24–25 没有带超时的 waitFor；自检本来就在 IO 线程，直接等待结束
                    process.waitFor()
                    true
                }
                if (!finished) {
                    process.destroy()
                    "自检超时"
                } else {
                    output.lineSequence().firstOrNull { it.startsWith("OpenVPN") }
                        ?: output.take(300)
                }
            } catch (e: Exception) {
                "启动失败：${e.message}"
            }
        }
    }

    @Volatile
    private var worker: Thread? = null

    @Volatile
    private var process: Process? = null

    @Volatile
    private var management: LocalSocket? = null

    @Volatile
    private var active = false

    private val otpQueue = LinkedBlockingQueue<String>()

    // openvpn 通过 NEED-OK 报上来的参数，直到 OPENTUN 才真正用来建 tun
    private var localIp: String? = null
    private var localPrefix: Int? = null
    private var mtu = 1500
    private val routes = mutableListOf<Pair<String, String>>()
    private val dnsServers = mutableListOf<String>()
    private var tunKeyEstablished: String? = null
    private var otpAttempts = 0

    @Volatile
    private var otpPromptActive = false

    @Volatile
    private var currentUsername = ""

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        VpnLog.init(this)
        when (intent?.action) {
            ACTION_CONNECT -> {
                if (active) {
                    Log.i(TAG, "连接请求被忽略：已经在运行")
                    return START_NOT_STICKY
                }
                val username = intent.getStringExtra(EXTRA_USERNAME).orEmpty()
                val password = intent.getStringExtra(EXTRA_PASSWORD).orEmpty()
                currentUsername = username
                val appOnly = intent.getBooleanExtra(EXTRA_APP_ONLY, true)
                startForegroundWith(VpnPhase.STARTING, getString(R.string.vpn_state_starting))
                active = true
                worker = Thread({ runTunnel(appOnly, username, password) }, "OpenVpnTunnel").apply { start() }
            }

            ACTION_DISCONNECT -> shutdown(getString(R.string.vpn_state_disconnected), keepState = false)

            ACTION_OTP -> {
                val code = intent.getStringExtra(EXTRA_OTP).orEmpty().trim()
                if (code.isNotEmpty()) {
                    otpQueue.offer(code)
                    VpnState.set(VpnPhase.CONNECTING, getString(R.string.vpn_state_verifying))
                    updateNotification(VpnPhase.CONNECTING, getString(R.string.vpn_state_verifying))
                }
            }

            else -> {
                // 系统或上次的 START_STICKY：没有连接意图就不要空转
                if (!active) stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onRevoke() {
        // 用户手动接管了 VPN，或者系统要求撤销
        Log.i(TAG, "onRevoke：隧道被系统收回")
        shutdown(getString(R.string.vpn_state_revoked), keepState = false)
        super.onRevoke()
    }

    override fun onDestroy() {
        shutdown(getString(R.string.vpn_state_disconnected), keepState = false)
        super.onDestroy()
    }

    // ── 隧道主流程 ──────────────────────────────────────────────────────────

    private fun runTunnel(appOnly: Boolean, username: String, password: String) {
        val mgmtPath = File(cacheDir, MGMT_SOCKET).absolutePath
        File(mgmtPath).delete()
        var server: LocalServerSocket? = null
        VpnLog.add("启动隧道：appOnly=$appOnly 账号=$username 管理口=$mgmtPath")
        try {
            val serverSocket = LocalSocket()
            serverSocket.bind(LocalSocketAddress(mgmtPath, LocalSocketAddress.Namespace.FILESYSTEM))
            server = LocalServerSocket(serverSocket.fileDescriptor)
            VpnLog.add("管理口服务端已创建")

            val config = buildConfig(mgmtPath)
            val nativeDir = applicationInfo.nativeLibraryDir
            val exec = File(nativeDir, EXEC_NAME)
            if (!exec.exists()) {
                fail(getString(R.string.vpn_missing_binary, nativeDir))
                return
            }
            VpnLog.add("原生组件：${exec.absolutePath} (${exec.length()} 字节)")

            val pb = ProcessBuilder(exec.absolutePath, "--config", "stdin")
            pb.environment()["LD_LIBRARY_PATH"] = nativeDir
            pb.environment()["TMPDIR"] = cacheDir.absolutePath
            pb.redirectErrorStream(true)
            val proc = pb.start()
            process = proc
            VpnLog.add("openvpn 进程已启动")

            // --config stdin：配置写完必须关掉标准输入，否则 openvpn 会一直等 EOF
            proc.outputStream.use { it.write(config.toByteArray(StandardCharsets.UTF_8)) }
            VpnLog.add("配置已写入 stdin（${config.length} 字符）")

            // 进程输出：verb 4 会打印几百行配置项，全记会把关键行挤掉，只留可能有用的
            Thread({
                try {
                    proc.inputStream.bufferedReader().forEachLine { line ->
                        if (isInteresting(line)) VpnLog.debug("[ovpn] $line")
                    }
                } catch (e: IOException) {
                    VpnLog.add("[ovpn] 输出结束：${e.message}")
                } finally {
                    VpnLog.add("[ovpn] 进程输出关闭，exit=${runCatching { proc.exitValue() }.getOrNull()}")
                    if (active) shutdown(getString(R.string.vpn_state_process_exit), keepState = true)
                }
            }, "OpenVpnLog").start()

            val socket = server.accept()
            management = socket
            VpnLog.add("管理口已连接")

            send("version 3\n")
            readLoop(socket, appOnly, username, password)
        } catch (e: Exception) {
            VpnLog.add("隧道异常：${e.javaClass.simpleName}: ${e.message}")
            if (active) fail(e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { server?.close() }
            runCatching { File(mgmtPath).delete() }
        }
    }

    /**
     * openvpn 的 stdout 每行形如 `1700000000.123456 1234 0x40 消息`，0x40 是配置回显。
     * 只保留可能与连接/认证有关的行，其余（几百行选项回显）直接丢掉。
     */
    private fun isInteresting(line: String): Boolean {
        val message = line.substringAfterLast(" ", line)
        val keywords = listOf(
            "ERROR", "WARNING", "MANAGEMENT", "AUTH", "TLS Error", "VERIFY",
            "Peer Connection", "SIGUSR", "Cannot", "Options error", "Exiting",
            "PUSH", "Initialization Sequence", "Control Channel", "Connection reset",
            "Inactivity timeout", "TLS:", "NET:", "link"
        )
        if (keywords.any { line.contains(it, ignoreCase = true) }) return true
        // 标志位里带 W/E 的是警告/错误
        val flags = Regex("^\\d+\\.\\d+\\s+\\S+\\s+(0x[0-9a-fA-F]+)\\s").find(line)
            ?.groupValues?.get(1)?.removePrefix("0x")?.toIntOrNull(16)
        return flags != null && (flags and 0x30) != 0 && message.isNotBlank()
    }

    private fun readLoop(socket: LocalSocket, appOnly: Boolean, username: String, password: String) {
        val input = socket.inputStream
        val buffer = ByteArray(4096)
        val pending = StringBuilder()
        val receivedFds = ArrayDeque<java.io.FileDescriptor>()

        while (active) {
            val read = input.read(buffer)
            if (read < 0) break
            // fd 是随管理口的某一次读一起送来的（SCM_RIGHTS），必须每次读后立刻取走
            val fds = socket.ancillaryFileDescriptors
            if (!fds.isNullOrEmpty()) {
                fds.forEach { receivedFds.addLast(it) }
                VpnLog.add("收到 ${fds.size} 个随管理口传来的 fd")
            }
            pending.append(String(buffer, 0, read, StandardCharsets.UTF_8))

            var newline = pending.indexOf("\n")
            while (newline >= 0) {
                val line = pending.substring(0, newline).trimEnd('\r')
                pending.delete(0, newline + 1)
                // 管理口每一行都记下来：挑战/密码/状态都在这里，缺一行就看不出顺序。
                // Auth-Token 是服务端发的会话令牌，等价于凭据，日志里只留占位。
                VpnLog.add(if (line.startsWith(">PASSWORD:Auth-Token")) "<< >PASSWORD:Auth-Token:(已脱敏)" else "<< $line")
                handleLine(line, appOnly, username, password, receivedFds)
                newline = pending.indexOf("\n")
            }
        }
        VpnLog.add("管理口读取结束")
    }

    private fun handleLine(
        line: String,
        appOnly: Boolean,
        username: String,
        password: String,
        receivedFds: ArrayDeque<java.io.FileDescriptor>,
    ) {
        when {
            line.startsWith("PROTECTFD:") -> {
                // openvpn 把自己去连服务器的 socket 交给我们 protect，否则隧道一装好它就绕回去了
                val fd = receivedFds.removeFirstOrNull()
                if (fd != null) {
                    runCatching { protect(fdInt(fd)) }
                        .onFailure { Log.w(TAG, "protect 失败：${it.message}") }
                }
            }

            line.startsWith(">PASSWORD:") -> onPassword(line.removePrefix(">PASSWORD:"), username, password)

            line.startsWith(">NEED-OK:") -> onNeedOk(line.removePrefix(">NEED-OK:"), appOnly, receivedFds)

            line.startsWith(">HOLD:") -> {
                // management-hold：openvpn 等我们说继续
                send("hold release\n")
                send("state on\n")
            }

            line.startsWith(">STATE:") -> onState(line.removePrefix(">STATE:"))

            line.startsWith(">FATAL:") -> fail(line.removePrefix(">FATAL:"))

            line.startsWith(">LOG:") -> {
                val text = line.removePrefix(">LOG:")
                if (text.contains(",W,") || text.contains(",E,")) {
                    VpnLog.add("[mgmt-log] $text")
                    // 少数服务端把 CRV1 挑战只写进日志行，管理口的 PASSWORD 事件里反而没有
                    if (text.contains("CRV1") && VpnState.phase != VpnPhase.NEED_OTP) {
                        val challenge = parseDynamicChallenge(text)
                        if (challenge != null) {
                            VpnLog.add("从日志行里发现 CRV1 挑战，按挑战处理")
                            onOtpChallenge(challenge, currentUsername)
                        }
                    }
                }
            }

            line.startsWith(">INFOMSG:") -> {
                // 服务端主动推的信息：静态挑战就是这条（CR_TEXT），不是 >INFO:
                val info = line.removePrefix(">INFOMSG:")
                VpnLog.add("INFOMSG $info")
                val prompt = parseCrText(info)
                if (prompt != null) {
                    VpnLog.add("收到 CR_TEXT 静态挑战：$prompt")
                    onStaticChallenge(prompt)
                }
            }

            line.startsWith(">INFO:") -> {
                val info = line.removePrefix(">INFO:")
                VpnLog.add("INFO $info")
                val prompt = parseCrText(info)
                if (prompt != null) {
                    VpnLog.add("收到 CR_TEXT 静态挑战（INFO 通道）：$prompt")
                    onStaticChallenge(prompt)
                }
            }
        }
    }

    /**
     * `CR_TEXT:<flags>:<挑战文本>`，flags 是 "R,E" 这类（R=必须回答，E=回显）。
     * 解析放在 [ChallengeParser] 里，单测覆盖。
     */
    private fun parseCrText(raw: String): String? = ChallengeParser.parseCrText(raw)

    private fun onPassword(argument: String, username: String, password: String) {
        when {
            // 服务端发来的会话令牌，openvpn 自己会保存并在重连时用；我们只需确认收到
            argument.startsWith("Auth-Token:") -> VpnLog.add("收到 Auth-Token（脱敏，openvpn 自用）")

            argument.startsWith("Need 'Auth' username/password") -> {
                VpnLog.add("服务端要求账号密码（Auth）")
                VpnState.set(VpnPhase.NEED_AUTH, getString(R.string.vpn_state_auth))
                send("username 'Auth' ${escape(username)}\n")
                send("password 'Auth' ${escape(password)}\n", redact = true)
            }

            argument.startsWith("Need 'Private Key'") ->
                VpnLog.add("服务端要私钥口令，这个配置不该出现")

            argument.startsWith("Verification Failed") -> {
                val detail = argument.substringAfter("Verification Failed", "")
                VpnLog.add("认证被拒：$detail")
                val challenge = parseDynamicChallenge(detail)
                if (challenge == null) {
                    VpnLog.add("这条失败信息里没有 CRV1，按普通认证失败处理")
                    fail(getString(R.string.vpn_auth_rejected))
                    return
                }
                VpnLog.add("解析出动态挑战提示：$challenge")
                otpAttempts++
                if (otpAttempts > MAX_OTP_ATTEMPTS) {
                    fail(getString(R.string.vpn_otp_rejected))
                    return
                }
                onOtpChallenge(challenge, username)
            }

            else -> VpnLog.add("未处理的 PASSWORD 事件：$argument")
        }
    }

    /**
     * `AUTH_FAILED,CRV1:<flags>:<b64 提示>:<b64 echo>:<b64 state>` 里的提示文本。
     * flags 是 "R,E" 或 "E"，所以按冒号切开后提示在索引 1。
     * 拿不到就返回 null（当作普通认证失败处理）。
     */
    private fun parseDynamicChallenge(raw: String): String? {
        val fallback = getString(R.string.vpn_otp_default_prompt)
        return ChallengeParser.parseDynamicPrompt(raw) { encoded ->
            runCatching {
                String(android.util.Base64.decode(encoded, android.util.Base64.NO_WRAP), StandardCharsets.UTF_8)
            }.getOrNull()
        } ?: if (raw.contains("CRV1:")) fallback else null
    }

    private fun onOtpChallenge(challenge: String, username: String) {
        if (otpPromptActive) return
        otpPromptActive = true
        VpnLog.add("弹出验证码输入框，提示：$challenge")
        VpnState.set(VpnPhase.NEED_OTP, getString(R.string.vpn_otp_needed), challenge)
        updateNotification(VpnPhase.NEED_OTP, challenge.ifBlank { getString(R.string.vpn_otp_needed) })
        val code = otpQueue.poll(10, TimeUnit.MINUTES)
        otpPromptActive = false
        if (code == null) {
            VpnLog.add("等验证码超时")
            fail(getString(R.string.vpn_otp_timeout))
            return
        }
        VpnLog.add("提交验证码（${code.length} 位），按动态挑战答复：用户名不变、密码位填码")
        // 动态挑战的答复就是"用户名不变 + 密码位填验证码"
        send("username 'Auth' ${escape(username)}\n")
        send("password 'Auth' ${escape(code)}\n", redact = true)
    }

    /** 静态挑战（INFOMSG:CR_TEXT）的答复走 cr-response，与动态挑战不是同一条路 */
    private fun onStaticChallenge(prompt: String) {
        if (otpPromptActive) return
        otpAttempts++
        if (otpAttempts > MAX_OTP_ATTEMPTS) {
            fail(getString(R.string.vpn_otp_rejected))
            return
        }
        otpPromptActive = true
        VpnLog.add("弹出静态挑战输入框，提示：$prompt")
        VpnState.set(VpnPhase.NEED_OTP, getString(R.string.vpn_otp_needed), prompt)
        updateNotification(VpnPhase.NEED_OTP, prompt)
        // 服务端只给握手延长了 54 秒左右，等太久没有意义
        val code = otpQueue.poll(3, TimeUnit.MINUTES)
        otpPromptActive = false
        if (code == null) {
            VpnLog.add("等验证码超时")
            fail(getString(R.string.vpn_otp_timeout))
            return
        }
        val encoded = android.util.Base64.encodeToString(code.toByteArray(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP)
        VpnLog.add("提交静态挑战应答（cr-response，${code.length} 位）")
        send("cr-response $encoded\n", redact = true)
    }

    private fun onNeedOk(
        argument: String,
        appOnly: Boolean,
        receivedFds: ArrayDeque<java.io.FileDescriptor>,
    ) {
        // 真机上的格式是：Need 'IFCONFIG' confirmation MSG:172.19.96.22 172.19.96.21 1500 net30
        val match = Regex("^Need '([^']+)'\\s*(.*)$").find(argument.trim()) ?: return
        val name = match.groupValues[1]
        var extra = match.groupValues[2].trim()
        if (extra.startsWith("confirmation MSG:")) extra = extra.removePrefix("confirmation MSG:").trim()

        when (name) {
            "IFCONFIG" -> {
                // 前两个是本地/对端地址，最后一个是 net30/p2p 模式，掩码要自己算
                val parts = extra.split(" ")
                localIp = parts.getOrNull(0)
                val mode = parts.getOrNull(3)?.trim()
                localPrefix = when (mode) {
                    "net30" -> 30
                    "p2p" -> 31
                    else -> prefixLength(parts.getOrNull(1))
                }
                parts.getOrNull(2)?.toIntOrNull()?.let { mtu = it }
                VpnLog.add("IFCONFIG $localIp/$localPrefix mtu=$mtu mode=$mode")
                send("needok 'IFCONFIG' ok\n")
            }

            "ROUTE" -> {
                // 10.8.0.0 255.255.240.0 172.19.96.21 dev android-gw
                val parts = extra.split(" ")
                val network = parts.getOrNull(0)
                val netmask = parts.getOrNull(1)
                if (network != null && netmask != null) {
                    routes += network to netmask
                    VpnLog.add("ROUTE $network/$netmask")
                }
                send("needok 'ROUTE' ok\n")
            }

            // 真机上是 DNSSERVER 不是 DNS
            "DNSSERVER", "DNS" -> {
                if (extra.isNotEmpty()) {
                    dnsServers += extra
                    VpnLog.add("DNS $extra")
                }
                send("needok '$name' ok\n")
            }

            "PROTECTFD" -> {
                // openvpn 把自己去连服务器的 socket 交过来，必须 protect，否则路由一装好它就绕进隧道
                val fd = receivedFds.removeFirstOrNull()
                if (fd != null) {
                    val ok = runCatching { protect(fdInt(fd)) }.getOrDefault(false)
                    VpnLog.add("PROTECTFD ${if (ok) "ok" else "failed"} ($extra)")
                    send("needok 'PROTECTFD' ${if (ok) "ok" else "cancel"}\n")
                } else {
                    VpnLog.add("PROTECTFD 没收到 fd，回复 cancel ($extra)")
                    send("needok 'PROTECTFD' cancel\n")
                }
            }

            "PERSIST_TUN_ACTION" -> {
                // 只接受 NOACTION / OPEN_BEFORE_CLOSE，回 "ok" 会直接 FATAL（真机踩过）
                val key = tunKey()
                val action = if (tunKeyEstablished == key) "NOACTION" else "OPEN_BEFORE_CLOSE"
                VpnLog.add("PERSIST_TUN_ACTION -> $action")
                send("needok 'PERSIST_TUN_ACTION' $action\n")
            }

            "OPENTUN" -> {
                val tun = establishTun(appOnly)
                if (tun == null) {
                    send("needok 'OPENTUN' cancel\n")
                    fail(getString(R.string.vpn_tun_failed))
                    return
                }
                tunKeyEstablished = tunKey()
                val socket = management ?: return
                try {
                    socket.setFileDescriptorsForSend(arrayOf(tun.fileDescriptor))
                    send("needok 'OPENTUN' ok\n")
                    socket.setFileDescriptorsForSend(null)
                } catch (e: IOException) {
                    Log.w(TAG, "发送 tun fd 失败：${e.message}")
                    fail(getString(R.string.vpn_tun_failed))
                } finally {
                    runCatching { tun.close() }
                }
            }

            else -> send("needok '$name' cancel\n")
        }
    }

    private fun establishTun(appOnly: Boolean): ParcelFileDescriptor? {
        val ip = localIp ?: run {
            VpnLog.add("没有 IFCONFIG 就要求开 tun")
            return null
        }
        val prefix = localPrefix ?: 30
        val builder = Builder()
            .setSession(getString(R.string.vpn_session))
            .setMtu(mtu)
            .addAddress(ip, prefix)
        VpnLog.add("建 tun：$ip/$prefix mtu=$mtu 路由 ${routes.size} 条 DNS $dnsServers 范围=${if (appOnly) "仅本应用" else "全局"}")
        dnsServers.forEach { server -> runCatching { builder.addDnsServer(server) } }
        routes.forEach { (network, netmask) ->
            runCatching { builder.addRoute(network, prefixLength(netmask)) }
                .onFailure { VpnLog.add("addRoute $network/$netmask 失败：${it.message}") }
        }
        if (appOnly) {
            builder.addAllowedApplication(packageName)
        }
        // 全局模式：不加任何 allow/disallow，即所有应用都走这条隧道
        return runCatching { builder.establish() }.onFailure {
            VpnLog.add("establish 失败：${it.message}")
        }.getOrNull()
    }

    /** PERSIST_TUN_ACTION 需要判断"这次的 tun 参数和上次是否一样" */
    private fun tunKey(): String = "$localIp/$localPrefix/$mtu/${routes.joinToString(";")}/${dnsServers.joinToString(",")}"

    private fun onState(argument: String) {
        // 时间戳,状态,本地IP,远端IP,协议,本地端口,远端端口,tun mtu,...
        val parts = argument.split(",")
        val state = parts.getOrNull(1)?.trim().orEmpty()
        VpnLog.add("STATE $state")
        when (state) {
            "CONNECTING", "WAIT", "AUTH", "AUTH_PENDING", "GET_CONFIG", "ASSIGN_IP", "ADD_ROUTES" -> {
                if (VpnState.phase != VpnPhase.NEED_OTP) {
                    VpnState.set(VpnPhase.CONNECTING, state)
                    updateNotification(VpnPhase.CONNECTING, getString(R.string.vpn_state_connecting, state))
                }
            }

            "CONNECTED" -> {
                VpnState.set(VpnPhase.CONNECTED, getString(R.string.vpn_state_connected))
                updateNotification(VpnPhase.CONNECTED, getString(R.string.vpn_state_connected))
            }

            "RECONNECTING" -> {
                VpnState.set(VpnPhase.RECONNECTING, getString(R.string.vpn_state_reconnecting))
                updateNotification(VpnPhase.RECONNECTING, getString(R.string.vpn_state_reconnecting))
            }

            "AUTH_FAILED" -> {
                VpnLog.add("STATE = AUTH_FAILED（服务端拒绝认证）")
                waitForChallengeThenFail()
            }

            "EXITING" -> shutdown(getString(R.string.vpn_state_disconnected), keepState = false)
        }
    }

    // ── 配置与工具 ──────────────────────────────────────────────────────────

    /**
     * AUTH_FAILED 这个状态和"要验证码"的 PASSWORD 事件是分开两条发出来的，谁先到不确定。
     * 立刻失败会把刚弹出的验证码框一起关掉，所以先等 1.5 秒看有没有挑战跟进来。
     */
    private fun waitForChallengeThenFail() {
        if (otpPromptActive) return
        Thread({
            Thread.sleep(1500)
            if (active && !otpPromptActive && VpnState.phase != VpnPhase.NEED_OTP) {
                VpnLog.add("1.5 秒内没有收到挑战，按认证失败处理")
                fail(getString(R.string.vpn_auth_rejected))
            }
        }, "AuthFailedWatch").start()
    }

    private fun buildConfig(mgmtPath: String): String {
        // 配置整份在构建时注入；没有注入时不要拿空配置去连
        val profile = runCatching {
            assets.open(PROFILE_ASSET)
                .bufferedReader(StandardCharsets.UTF_8)
                .readText()
                .removePrefix("\uFEFF")
                .replace("\r\n", "\n")
        }.getOrElse {
            throw IllegalStateException(getString(R.string.vpn_missing_profile))
        }

        val header = buildString {
            appendLine("management $mgmtPath unix")
            appendLine("management-client")
            appendLine("management-query-passwords")
            appendLine("management-hold")
            appendLine("setenv IV_GUI_VER \"CampusRun/0.1\"")
            appendLine("setenv IV_PLAT_VER \"android\"")
            appendLine("setenv IV_PLAT android")
            appendLine("setenv IV_SSO openurl,webauth,crtext")
            fakeHwAddr()?.let { appendLine("setenv IV_HWADDR $it") }
        }
        return header + profile
    }

    /**
     * 一个稳定的假 MAC：服务端若按设备做"记住设备"，换一次值就等于换一台设备。
     * 来源是 ANDROID_ID（每应用每设备稳定），做法与 ics-openvpn 一致。
     */
    private fun fakeHwAddr(): String? = runCatching {
        val androidId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: return null
        val digest = MessageDigest.getInstance("MD5").digest(androidId.toByteArray())
        val mac = StringBuilder("02:00")
        (0 until 5).forEach { index ->
            mac.append(String.format(":%02X", digest[index]))
        }
        mac.toString()
    }.getOrNull()

    private fun send(command: String, redact: Boolean = false) {
        val socket = management
        if (socket == null) {
            VpnLog.add("!! 管理口未建立，命令被丢弃")
            return
        }
        VpnLog.add(">> ${if (redact) "(已脱敏)" else command.trim()}")
        runCatching {
            socket.outputStream.write(command.toByteArray(StandardCharsets.UTF_8))
            socket.outputStream.flush()
        }.onFailure { VpnLog.add("!! 写管理口失败：${it.message}") }
    }

    /** Builder 只收前缀长度，openvpn 给的是点分掩码；255.255.255.0 → 24 */
    private fun prefixLength(netmask: String?): Int {
        if (netmask.isNullOrBlank()) return 24
        val parts = netmask.split(".").mapNotNull { it.toIntOrNull() }
        if (parts.size != 4) return 24
        return parts.sumOf { Integer.bitCount(it and 0xFF) }
    }

    /**
     * `VpnService.protect(FileDescriptor)` 是隐藏 API，公开的只有 `protect(int)`；
     * 用反射取 fd 整数值（与 ics-openvpn 取反向的做法同源）。
     */
    private fun fdInt(fd: java.io.FileDescriptor): Int =
        java.io.FileDescriptor::class.java
            .getDeclaredMethod("getInt$")
            .invoke(fd) as Int

    /**
     * openvpn 配置文件里的值要能带空格/引号，规则与 ics-openvpn 的 openVpnEscape 一致
     */
    private fun escape(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
        val needsQuote = escaped != value || escaped.any { it == ' ' || it == '\t' } ||
            escaped.startsWith("'") || escaped.startsWith("\"")
        return if (needsQuote) "\"$escaped\"" else escaped
    }

    private fun fail(reason: String) {
        VpnLog.add("隧道失败：$reason")
        VpnState.set(VpnPhase.ERROR, reason)
        shutdown(reason, keepState = true)
    }

    private fun shutdown(reason: String, keepState: Boolean) {
        if (!active) {
            if (keepState) VpnState.set(VpnPhase.ERROR, reason) else VpnState.set(VpnPhase.IDLE, reason)
            stopSelf()
            return
        }
        VpnLog.add("关闭隧道：$reason")
        active = false
        runCatching { send("signal SIGINT\n") }
        runCatching { management?.close() }
        management = null
        runCatching { process?.destroy() }
        process = null
        runCatching { worker?.interrupt() }
        worker = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        VpnState.set(if (keepState) VpnPhase.ERROR else VpnPhase.IDLE, reason)
        stopSelf()
    }

    // ── 通知 ────────────────────────────────────────────────────────────────

    private fun startForegroundWith(phase: VpnPhase, text: String) {
        VpnState.set(phase, text)
        ensureChannel()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(text), type)
    }

    private fun updateNotification(phase: VpnPhase, text: String) {
        if (!active) return
        runCatching {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification(text))
        }
    }

    private fun buildNotification(text: String): android.app.Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, VpnActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_run)
            .setContentTitle(getString(R.string.vpn_notification_title))
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.vpn_channel), NotificationManager.IMPORTANCE_LOW)
        )
    }
}
