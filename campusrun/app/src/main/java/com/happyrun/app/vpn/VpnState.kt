package com.happyrun.app.vpn

import java.util.concurrent.CopyOnWriteArrayList

/** VPN 隧道对外暴露的状态。只在内存里，不持久化——进程死了隧道也就断了。 */
enum class VpnPhase {
    IDLE, STARTING, NEED_AUTH, NEED_OTP, CONNECTING, CONNECTED, RECONNECTING, ERROR
}

object VpnState {

    @Volatile
    var phase: VpnPhase = VpnPhase.IDLE
        private set

    @Volatile
    var message: String = ""
        private set

    /** 服务端下发的挑战提示（通常写着验证码发到哪个手机号），只在 NEED_OTP 时有意义 */
    @Volatile
    var challenge: String = ""
        private set

    private val listeners = CopyOnWriteArrayList<(VpnPhase, String) -> Unit>()

    fun isActive(): Boolean = phase != VpnPhase.IDLE && phase != VpnPhase.ERROR

    fun set(newPhase: VpnPhase, newMessage: String = "", newChallenge: String = "") {
        phase = newPhase
        message = newMessage
        challenge = newChallenge
        listeners.forEach { it(newPhase, newMessage) }
    }

    fun addListener(listener: (VpnPhase, String) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (VpnPhase, String) -> Unit) {
        listeners.remove(listener)
    }
}
