package com.happyrun.app.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.happyrun.app.data.AccountStore

/**
 * 静默重登的护栏与账本。真正的登录动作在 [CasAuthClient.loginSilently] 里做。
 *
 * 三道护栏：
 * - 单飞：一次只能有一个静默登录在跑，否则多个触发点会对同一个账号并发提交两次账密；
 * - 冷却：两次尝试之间至少 [COOLDOWN_MS]；撞到短信验证码后拉长到
 *   [SECOND_FACTOR_COOLDOWN_MS]，避免在用户还没做二验时反复撩拨；
 * - 熔断：连续被拒 [MAX_CONSECUTIVE_REJECTIONS] 次就彻底停手。存错密码 + 后台自动重试
 *   是唯一可能把学校账号搞进风控/锁号的路径，必须自己先停下。
 *
 * 只有 [SilentLoginOutcome.Rejected] 计入熔断。短信验证码和网络异常都不算——
 * 它们不是凭据错了，熔断会让功能在校外或弱网下一次就被永久关掉。
 */
object SilentLogin {

    private const val TAG = "SilentLogin"

    private const val MAX_CONSECUTIVE_REJECTIONS = 2
    private const val COOLDOWN_MS = 60_000L
    private const val SECOND_FACTOR_COOLDOWN_MS = 10 * 60_000L

    /** 单飞占位的自愈时限：超过它就把占位当成陈旧的释放掉 */
    private const val IN_FLIGHT_TIMEOUT_MS = 90_000L

    private const val PREF_NAME = "campusrun_silent_login"
    private const val KEY_REJECTIONS = "rejections"
    private const val KEY_LAST_ATTEMPT = "lastAttemptAt"
    private const val KEY_COOLDOWN_UNTIL = "cooldownUntil"
    private const val KEY_LAST_OUTCOME = "lastOutcome"

    enum class Decision { ALLOW, NO_CREDENTIALS, IN_FLIGHT, COOLDOWN, DISABLED }

    /** 单飞占位的时间戳，0 = 没有静默重登在跑 */
    @Volatile
    private var inFlightAt = 0L

    /** 必须在**发请求之前**调用 */
    fun claim() {
        inFlightAt = System.currentTimeMillis()
    }

    fun release() {
        inFlightAt = 0L
    }

    private fun inFlight(): Boolean {
        val at = inFlightAt
        if (at == 0L) return false
        if (System.currentTimeMillis() - at < IN_FLIGHT_TIMEOUT_MS) return true
        Log.w(TAG, "静默重登占位已陈旧，自动释放")
        inFlightAt = 0L
        return false
    }

    /** 该不该试。冷却/熔断只约束后台自动重试；用户手动登录不走这里 */
    fun decide(context: Context, store: AccountStore): Decision {
        if (inFlight()) return Decision.IN_FLIGHT
        if (!hasPassword(store)) return Decision.NO_CREDENTIALS
        val p = prefs(context)
        if (p.getInt(KEY_REJECTIONS, 0) >= MAX_CONSECUTIVE_REJECTIONS) return Decision.DISABLED
        if (System.currentTimeMillis() < p.getLong(KEY_COOLDOWN_UNTIL, 0L)) return Decision.COOLDOWN
        return Decision.ALLOW
    }

    fun disabled(context: Context): Boolean =
        prefs(context).getInt(KEY_REJECTIONS, 0) >= MAX_CONSECUTIVE_REJECTIONS

    fun record(context: Context, outcome: SilentLoginOutcome) {
        release()
        val p = prefs(context)
        val now = System.currentTimeMillis()
        val edit = p.edit()
            .putLong(KEY_LAST_ATTEMPT, now)
            .putString(KEY_LAST_OUTCOME, nameOf(outcome))
        when (outcome) {
            // 成功也给一段冷却：万一是个假成功，没有冷却会"拉起登录→自报成功→回来又失效"无限重来
            SilentLoginOutcome.Success ->
                edit.putInt(KEY_REJECTIONS, 0).putLong(KEY_COOLDOWN_UNTIL, now + COOLDOWN_MS)

            SilentLoginOutcome.Rejected ->
                edit.putInt(KEY_REJECTIONS, p.getInt(KEY_REJECTIONS, 0) + 1)
                    .putLong(KEY_COOLDOWN_UNTIL, now + COOLDOWN_MS)

            is SilentLoginOutcome.NeedSecondFactor ->
                edit.putLong(KEY_COOLDOWN_UNTIL, now + SECOND_FACTOR_COOLDOWN_MS)

            is SilentLoginOutcome.Error ->
                edit.putLong(KEY_COOLDOWN_UNTIL, now + COOLDOWN_MS)
        }
        edit.apply()
        Log.i(TAG, "静默重登结果：${nameOf(outcome)}")
    }

    /** 保存/清除账密时调用：换过凭据就重新给满次数 */
    fun reset(context: Context) {
        prefs(context).edit()
            .putInt(KEY_REJECTIONS, 0)
            .putLong(KEY_COOLDOWN_UNTIL, 0L)
            .apply()
    }

    fun lastOutcome(context: Context): String? =
        prefs(context).getString(KEY_LAST_OUTCOME, null)

    /** 诊断用：一行状态，不含任何凭据 */
    fun describe(context: Context, store: AccountStore): String {
        val p = prefs(context)
        val until = p.getLong(KEY_COOLDOWN_UNTIL, 0L)
        val now = System.currentTimeMillis()
        val cooldown = if (until > now) "冷却中(${(until - now) / 1000}s)" else "无冷却"
        val last = p.getString(KEY_LAST_OUTCOME, null)
        val lastText = if (last == null) {
            "无"
        } else {
            val ago = now - p.getLong(KEY_LAST_ATTEMPT, 0L)
            "$last(${ago / 1000}s 前)"
        }
        return "已存账密=${hasPassword(store)} 账号=${store.active()?.username ?: "-"} " +
            "连续被拒=${p.getInt(KEY_REJECTIONS, 0)}/$MAX_CONSECUTIVE_REJECTIONS $cooldown " +
            "上次结果=$lastText"
    }

    fun nameOf(outcome: SilentLoginOutcome): String = when (outcome) {
        SilentLoginOutcome.Success -> "success"
        is SilentLoginOutcome.NeedSecondFactor -> "needSecondFactor"
        SilentLoginOutcome.Rejected -> "rejected"
        is SilentLoginOutcome.Error -> "error"
    }

    private fun hasPassword(store: AccountStore): Boolean =
        !store.active()?.password.isNullOrBlank()

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
}
