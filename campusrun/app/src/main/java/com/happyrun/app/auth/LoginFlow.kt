package com.happyrun.app.auth

import android.content.Context
import android.util.Log
import com.happyrun.app.data.AccountStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** 一次自动登录尝试的结局，调用方据此决定是显示已登录、打开原生表单，还是回落到网页登录 */
sealed class LoginAttempt {
    object LoggedIn : LoginAttempt()

    /** 没有账号或没存密码：交给原生登录表单 */
    object NeedCredentials : LoginAttempt()

    /** 保存的密码被服务端否定：打开原生表单并清空密码 */
    object InvalidPassword : LoginAttempt()

    /** 需要二次验证、页面结构无法识别或静默失败：回落到网页登录 */
    object NeedWebLogin : LoginAttempt()

    /** 已经有一发在跑：这次什么都不做 */
    object AlreadyRunning : LoginAttempt()

    data class Error(val detail: String) : LoginAttempt()
}

/**
 * 自动登录编排，主界面每次冷启动都会走一遍。
 *
 * 顺序刻意是"先换票、再静默账密、最后回落网页"：
 * 1. 用现成的 CASTGC 走一遍平台入口（`CasSession.renew`）——TGT 还活着时无需账密、
 *    不碰短信，拿到新的平台 cookie；
 * 2. TGT 没了才用保存的账密走 `CasAuthClient.loginSilently`；撞到二次验证立刻停下，
 *    交给网页登录页完成（短信/图形码都由校方页面自己处理）；
 * 3. 账号密码被否定 → 原生表单；网络异常 → 只报错，不自动拉网页（拉起来也打不开）。
 *
 * 这样"登录成功"就意味着平台 cookie 已经在共享 cookie 罐里，跑步页用现成会话即可，
 * 不需要再经过 CAS。
 */
object LoginFlow {

    private const val TAG = "LoginFlow"

    suspend fun autoLogin(
        context: Context,
        store: AccountStore,
        entryUrl: String,
    ): LoginAttempt = withContext(Dispatchers.IO) {
        val account = store.active()
        if (account == null) {
            Log.i(TAG, "没有当前账号，需要人工登录")
            return@withContext LoginAttempt.NeedCredentials
        }

        when (val renew = CasSession.renew(entryUrl)) {
            CasSession.Renew.LoggedIn -> {
                Log.i(TAG, "现有 TGT 换票成功，平台会话已刷新")
                return@withContext LoginAttempt.LoggedIn
            }

            is CasSession.Renew.Error -> {
                Log.w(TAG, "TGT 换票网络异常：${renew.detail}")
                return@withContext LoginAttempt.Error(renew.detail)
            }

            CasSession.Renew.NeedLogin -> Unit
        }

        val password = account.password
        if (password.isNullOrBlank()) {
            Log.i(TAG, "TGT 已失效且未保存密码，需要人工登录")
            return@withContext LoginAttempt.NeedCredentials
        }

        when (SilentLogin.decide(context, store)) {
            SilentLogin.Decision.ALLOW -> {
                SilentLogin.claim()
                // IOException 只可能是网络问题：这种时候网页登录同样打不开，
                // 要交给登录页提示"先连校园 VPN"，而不是直接跳网页
                var networkFailure: String? = null
                val outcome = try {
                    CasSession.loginSilently(account.username, password)
                } catch (e: IOException) {
                    val detail = e.message ?: "网络异常"
                    Log.w(TAG, "静默登录网络异常", e)
                    networkFailure = detail
                    SilentLoginOutcome.Error(detail)
                }
                SilentLogin.record(context, outcome)
                Log.i(TAG, "静默登录结果：${SilentLogin.nameOf(outcome)}")
                if (networkFailure != null) return@withContext LoginAttempt.Error(networkFailure)
                return@withContext when (outcome) {
                    SilentLoginOutcome.Success -> LoginAttempt.LoggedIn
                    // 二验页的短信/图形码交给校方网页，原生只负责到"账密通过"为止
                    is SilentLoginOutcome.NeedSecondFactor -> LoginAttempt.NeedWebLogin
                    SilentLoginOutcome.Rejected -> LoginAttempt.InvalidPassword
                    is SilentLoginOutcome.Error -> LoginAttempt.NeedWebLogin
                }
            }

            SilentLogin.Decision.DISABLED -> return@withContext LoginAttempt.InvalidPassword
            // 上面已经确认密码非空，这个分支理论上不会出现
            SilentLogin.Decision.NO_CREDENTIALS -> return@withContext LoginAttempt.NeedCredentials
            SilentLogin.Decision.IN_FLIGHT -> return@withContext LoginAttempt.AlreadyRunning
            // 冷却多半是刚撞过二验：直接走网页登录，别让用户干等
            SilentLogin.Decision.COOLDOWN -> return@withContext LoginAttempt.NeedWebLogin
        }
    }
}
