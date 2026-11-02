package com.happyrun.app.vpn

import android.app.Activity
import android.net.VpnService
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.happyrun.app.R
import com.happyrun.app.auth.AppHttp

/**
 * 在任意页面把校园 VPN 拉起来并等到连接结果。
 *
 * 登录页要在网络不通时先用 VPN 再重试，网页登录页和 VPN 页也要同一套"连上以后怎么办"，
 * 所以把 `prepare` 授权、监听 [VpnState]、收验证码、超时这几件事收在这里。
 *
 * 用法（必须在 Activity 的 `onCreate` 里构造——内部要注册 ActivityResultLauncher）：
 * ```
 * vpnGate = VpnGate(this)
 * vpnGate.ensureConnected(username, password) { result -> ... }
 * ```
 * 回调总在主线程；页面销毁时调 [cancel]，只解绑不拆隧道。
 */
class VpnGate(private val activity: AppCompatActivity) {

    sealed class Result {
        object Connected : Result()
        data class Failed(val reason: String) : Result()
    }

    private companion object {
        const val TAG = "VpnGate"

        /** 认证 + 建 tun 正常在 10 秒内完成；超过这个时间基本是网络或服务端卡住 */
        const val CONNECT_TIMEOUT_MS = 45_000L
    }

    private val main = Handler(Looper.getMainLooper())
    private var awaiting: ((Result) -> Unit)? = null
    private var username = ""
    private var password = ""

    private val otpPrompt = VpnOtpPrompt(
        activity,
        onSubmitted = { code -> OpenVpnService.submitOtp(activity, code) },
        onCancelled = { OpenVpnService.disconnect(activity) },
    )

    private val consentLauncher = activity.registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startService()
        } else {
            settle(Result.Failed(activity.getString(R.string.vpn_consent_denied)))
        }
    }

    private val timeout = Runnable {
        settle(Result.Failed(activity.getString(R.string.vpn_gate_timeout)))
    }

    private val listener: (VpnPhase, String) -> Unit = { phase, message ->
        main.post {
            if (awaiting == null) return@post
            when (phase) {
                VpnPhase.CONNECTED -> settle(Result.Connected)

                VpnPhase.ERROR -> settle(
                    Result.Failed(message.ifBlank { activity.getString(R.string.vpn_state_error) })
                )

                VpnPhase.NEED_OTP -> {
                    // 验证码等多久由隧道自己决定（静态挑战 3 分钟 / 动态挑战 10 分钟），
                    // 这层连接超时不能再掐它
                    main.removeCallbacks(timeout)
                    otpPrompt.sync()
                }

                // 没有任何人主动断开时走到 IDLE，说明隧道已经停了（比如验证码框被取消）
                VpnPhase.IDLE -> settle(Result.Failed(activity.getString(R.string.vpn_gate_idle)))

                else -> otpPrompt.dismiss()
            }
        }
    }

    fun ensureConnected(username: String, password: String, onResult: (Result) -> Unit) {
        if (VpnState.phase == VpnPhase.CONNECTED) {
            onResult(Result.Connected)
            return
        }
        if (awaiting != null) {
            onResult(Result.Failed(activity.getString(R.string.vpn_gate_busy)))
            return
        }
        if (username.isBlank() || password.isEmpty()) {
            onResult(Result.Failed(activity.getString(R.string.vpn_gate_need_credentials)))
            return
        }

        this.username = username
        this.password = password
        awaiting = onResult
        VpnState.addListener(listener)

        // 上一次连接还在半路（例如刚从 VPN 页切过来）：跟着它，不重复起服务
        if (VpnState.isActive()) {
            Log.i(TAG, "已有连接在进行（${VpnState.phase}），跟随它的状态")
            main.postDelayed(timeout, CONNECT_TIMEOUT_MS)
            return
        }

        val consent = VpnService.prepare(activity)
        if (consent != null) {
            Log.i(TAG, "需要系统 VPN 授权")
            consentLauncher.launch(consent)
        } else {
            startService()
        }
    }

    /** 页面销毁：只解绑，不拆隧道（用户可能正靠它跑校园网） */
    fun cancel() {
        awaiting = null
        main.removeCallbacks(timeout)
        VpnState.removeListener(listener)
        otpPrompt.dismiss()
    }

    private fun startService() {
        Log.i(TAG, "启动隧道：账号=$username")
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, CONNECT_TIMEOUT_MS)
        OpenVpnService.connect(activity, appOnly = true, username = username, password = password)
    }

    private fun settle(result: Result) {
        val callback = awaiting ?: return
        awaiting = null
        main.removeCallbacks(timeout)
        VpnState.removeListener(listener)
        otpPrompt.dismiss()

        if (result is Result.Connected) {
            // 隧道一装好，之前建的 socket 还挂在原来的路由上；等连接池清完再回调，
            // 否则第一发重试可能复用旧连接，看起来像"连了 VPN 还是失败"
            AppHttp.evictConnections { callback(result) }
            return
        }
        callback(result)
    }
}
