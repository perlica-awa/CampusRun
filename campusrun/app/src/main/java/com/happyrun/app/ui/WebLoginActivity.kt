package com.happyrun.app.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.happyrun.app.R
import com.happyrun.app.auth.CasSession
import com.happyrun.app.auth.platformUrls
import com.happyrun.app.auth.UserAgent
import com.happyrun.app.data.AccountStore
import com.happyrun.app.data.VpnAccountStore
import com.happyrun.app.vpn.VpnGate
import com.happyrun.app.web.CasPrefill
import com.happyrun.app.web.SessionCookies
import java.net.URI

/**
 * 网页版登录页（兼容模式 / 逃生口）。
 *
 * 默认路径是 [LoginActivity] 的原生表单；这里留着是因为整个应用依赖平台页面，
 * 校方一旦改版，原生解析可能整个失灵，而"登录"是唯一不能失灵的功能。两种进入方式：
 * - 原生面板报"打不开登录页 / 响应无法识别"时，面板上的"改用网页登录"；
 * - 学校要求微信扫码这类原生表单做不了的验证方式时。
 *
 * 成功判定：本次确实落到过平台主机，且页面自己的 `localStorage.user` 已经出现
 * （平台把 uid/token/sign 存在那里）。只看 cookie 会被上一次会话的残留骗过。
 */
class WebLoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "WebLoginActivity"
        private const val POLL_INTERVAL_MS = 800L

        /** 从原生登录页带过来的账密：网页登录页的 VPN 入口与 CAS 预填都用它 */
        const val EXTRA_USERNAME = "login_username"
        const val EXTRA_PASSWORD = "login_password"
        const val EXTRA_KEEP_PASSWORD = "login_keep_password"
        const val EXTRA_AUTO_LOGIN = "login_auto_login"

        /** 页面登录成功后会把 uid/token/sign 写进 localStorage.user */
        private const val JS_HAS_USER =
            "(function(){try{var u=localStorage.getItem('user');" +
                "return (u&&u.length>2)?'1':'0';}catch(e){return '0';}})()"
    }

    private lateinit var webView: WebView
    private lateinit var progressLogin: LinearProgressIndicator
    private lateinit var groupLoadFailed: View
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 登录页打不开时用它拉起 VPN：和原生登录页走同一套 [VpnGate] */
    private lateinit var vpnGate: VpnGate

    /** 从原生登录页带过来的账密（可能为空） */
    private var handoffUsername: String? = null
    private var handoffPassword: String? = null

    private var loggedIn = false
    private var checking = false

    /** 本次流程中 WebView 是否真的落到过平台（而不是一上来就被旧 cookie 骗过） */
    private var reachedPlatform = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (loggedIn) return
            checkLoginSuccess()
            mainHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UserAgent.adopt(this)
        Log.d(TAG, "onCreate(网页登录)")
        setContentView(R.layout.activity_web_login)
        // 必须在 onCreate 里建：内部要注册 VPN 授权的 ActivityResultLauncher
        vpnGate = VpnGate(this)
        handoffUsername = intent.getStringExtra(EXTRA_USERNAME)?.trim()?.takeIf { it.isNotEmpty() }
        handoffPassword = intent.getStringExtra(EXTRA_PASSWORD)?.takeIf { it.isNotEmpty() }

        webView = findViewById(R.id.webView)
        progressLogin = findViewById(R.id.progressLogin)
        groupLoadFailed = findViewById(R.id.groupLoadFailed)

        findViewById<MaterialToolbar>(R.id.toolbarWebLogin).setNavigationOnClickListener { navigateBack() }
        findViewById<MaterialButton>(R.id.btnWebLoginVpn).setOnClickListener { connectVpnAndReload() }

        SessionCookies.attach(webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            userAgentString = UserAgent.current
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                groupLoadFailed.visibility = View.GONE
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                super.onReceivedError(view, request, error)
                // 只有主文档失败才提示：子资源（统计脚本、字体）挂了不该挡住登录
                if (!request.isForMainFrame) return
                Log.w(TAG, "主文档加载失败：${request.url} ${error.description}")
                groupLoadFailed.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                Log.d(TAG, "onPageFinished: $url")
                if (platformUrls.isPlatformUrl(url)) reachedPlatform = true
                prefillIfCasPage(url)
                checkLoginSuccess()
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                Log.d(TAG, "history: $url")
                if (platformUrls.isPlatformUrl(url)) {
                    if (!reachedPlatform) Log.i(TAG, "WebView 已落到平台: $url")
                    reachedPlatform = true
                }
                checkLoginSuccess()
            }
        }
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progressLogin.visibility = if (newProgress in 1..99) android.view.View.VISIBLE else android.view.View.GONE
                progressLogin.progress = newProgress
            }
        }

        if (!platformUrls.configured) {
            groupLoadFailed.visibility = View.VISIBLE
            return
        }

        webView.loadUrl(platformUrls.casLoginEntry)
        mainHandler.postDelayed(pollRunnable, POLL_INTERVAL_MS)
    }

    /**
     * 只在 CAS 页面上预填账密（只填不提交）。绝不把密码注入平台页面：
     * 登录成功后就没必要再让任何页面碰到它。
     */
    private fun prefillIfCasPage(url: String?) {
        val host = url?.let { runCatching { URI(it).host }.getOrNull() }
        if (host != platformUrls.casHost) return
        val username = handoffUsername ?: AccountStore(this).active()?.username ?: return
        val password = handoffPassword ?: AccountStore(this).active()?.password ?: return
        webView.evaluateJavascript(CasPrefill.script(username, password), null)
    }

    private fun checkLoginSuccess() {
        if (loggedIn || checking) return
        // 便宜的预筛：本次必须真的落到过平台，且当前不是页面自己的登录页
        if (!reachedPlatform) return
        val url = webView.url ?: return
        if (!platformUrls.isPlatformUrl(url)) return
        if (url.contains("pages/login", ignoreCase = true)) return

        checking = true
        webView.evaluateJavascript(JS_HAS_USER) { raw ->
            checking = false
            if (raw?.trim()?.removeSurrounding("\"") == "1") onLoggedIn()
        }
    }

    private fun onLoggedIn() {
        loggedIn = true
        mainHandler.removeCallbacks(pollRunnable)
        CasSession.flush()
        val store = AccountStore(this)
        val username = handoffUsername
        val password = handoffPassword
        if (username != null && password != null) {
            // 网页登录也要把这次输的账密落定，否则下次启动仍然没有账号可自动登录
            store.recordLogin(
                username = username,
                password = password,
                keepPassword = intent.getBooleanExtra(EXTRA_KEEP_PASSWORD, false),
                autoLogin = intent.getBooleanExtra(EXTRA_AUTO_LOGIN, false),
            )
            com.happyrun.app.auth.SilentLogin.reset(this)
        } else {
            store.active()?.let { store.markLogin(it.id) }
        }
        CasSession.markSignedIn(this, username ?: store.active()?.username)
        Log.i(TAG, "登录成功（localStorage.user 已落地）")
        setResult(RESULT_OK)
        finish()
    }

    private fun navigateBack() {
        if (webView.canGoBack()) webView.goBack() else finish()
    }

    /**
     * 页面打不开时用 VPN 救一次。
     *
     * 凭据顺序：原生登录页带过来的账密 → 设置里的独立账号 / 当前登录账号 → 弹一次输入框。
     * 连上之后直接重载，不需要用户再点第二次。
     */
    private fun connectVpnAndReload() {
        val handed = handoffUsername?.let { user -> handoffPassword?.let { pass -> user to pass } }
        if (handed != null) {
            connectVpnThen(handed.first, handed.second)
            return
        }
        val credential = VpnAccountStore.resolve(this)
        if (credential == null) {
            VpnAccountDialog.show(this, prefillUsername = VpnAccountStore.preferredUsername(this)) { user, pass ->
                connectVpnThen(user, pass)
            }
            return
        }
        connectVpnThen(credential.username, credential.password)
    }

    private fun connectVpnThen(username: String, password: String) {
        val button = findViewById<MaterialButton>(R.id.btnWebLoginVpn)
        button.isEnabled = false
        button.text = getString(R.string.login_vpn_connecting)
        vpnGate.ensureConnected(username, password) { result ->
            if (isFinishing || isDestroyed) return@ensureConnected
            button.isEnabled = true
            button.text = getString(R.string.web_login_vpn_button)
            when (result) {
                is VpnGate.Result.Connected -> {
                    Log.i(TAG, "校园 VPN 已连接，重新加载登录页")
                    groupLoadFailed.visibility = View.GONE
                    Toast.makeText(this, R.string.login_vpn_ready, Toast.LENGTH_SHORT).show()
                    webView.reload()
                }

                is VpnGate.Result.Failed -> {
                    Log.w(TAG, "校园 VPN 连接失败：${result.reason}")
                    Toast.makeText(
                        this,
                        getString(R.string.login_vpn_failed, result.reason),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(pollRunnable)
        // 只解绑：隧道是用户主动开的，退出页面不该替他断开
        if (::vpnGate.isInitialized) vpnGate.cancel()
        super.onDestroy()
    }
}
