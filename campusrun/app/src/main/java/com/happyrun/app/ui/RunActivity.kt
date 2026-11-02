package com.happyrun.app.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ConsoleMessage
import android.webkit.GeolocationPermissions
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.happyrun.app.BuildConfig
import com.happyrun.app.R
import com.happyrun.app.auth.CasSession
import com.happyrun.app.auth.platformUrls
import com.happyrun.app.auth.SilentLogin
import com.happyrun.app.auth.SilentLoginOutcome
import com.happyrun.app.auth.UserAgent
import com.happyrun.app.data.AccountStore
import com.happyrun.app.data.AppSettings
import com.happyrun.app.data.TemplateRepository
import com.happyrun.app.geo.GeoBridge
import com.happyrun.app.geo.GeoShim
import com.happyrun.app.run.GeoFix
import com.happyrun.app.run.RunSession
import com.happyrun.app.service.RunKeepAliveService
import com.happyrun.app.web.CasPrefill
import com.happyrun.app.web.ClockWatcher
import com.happyrun.app.web.SessionCookies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 主界面：顶部固定状态条，下方是原平台 H5。
 * 定位由 document-start 脚本接管；打卡点全部通过后可自动/手动切入模板回放。
 */
class RunActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_STADIUM_ID = "stadium_id"
        const val EXTRA_LANE = "lane"
        const val EXTRA_PACE = "pace_sec_per_km"
        const val EXTRA_JITTER = "speed_jitter_pct"
        const val EXTRA_DRIFT_CM = "drift_cm"
        const val EXTRA_LANE_CHANGE = "allow_lane_change"
        const val EXTRA_WALK = "allow_walk"
        const val EXTRA_TARGET_KM = "target_km"
        const val EXTRA_AUTO_SWITCH = "auto_switch"
        const val EXTRA_ENTRY_URL = "entry_url"
        private const val TAG = "CampusRun"
    }

    private lateinit var session: RunSession
    private lateinit var webView: WebView
    private lateinit var watcher: ClockWatcher
    private lateinit var tvMode: TextView
    private lateinit var tvClocks: TextView
    private lateinit var tvDistance: TextView
    private lateinit var tvLap: TextView
    private lateinit var btnSwitch: MaterialButton
    private lateinit var btnBack: MaterialButton
    private lateinit var tvAccount: TextView

    private lateinit var locationManager: LocationManager
    private val handler = Handler(Looper.getMainLooper())

    private var targetKm = 2.0
    private var autoSwitch = true
    private var lastStatus: ClockWatcher.Status? = null
    private var sawIncomplete = false
    private var targetAnnounced = false
    private var legacyInjection = false

    private var entryUrl = ""
    private var recoveryAttempts = 0
    private var lastRecoveryAt = 0L
    private var accountStore: AccountStore? = null
    private var activeAccountId: String? = null
    private var loginMarked = false
    private lateinit var settings: AppSettings

    /** 原生登录页已经拉起：避免页面每次 history 更新都再拉一次 */
    private var loginLaunched = false

    private val loginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        loginLaunched = false
        if (result.resultCode == RESULT_OK) {
            CasSession.markSignedIn(this, accountStore?.active()?.username)
            Toast.makeText(this, R.string.login_success, Toast.LENGTH_SHORT).show()
            webView.loadUrl(entryUrl)
        }
    }

    private val hudTick = object : Runnable {
        override fun run() {
            renderHud()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_run)

        settings = AppSettings(this)
        UserAgent.adopt(this)
        accountStore = AccountStore(this)
        val activeAccount = accountStore?.active()
        activeAccountId = activeAccount?.id

        // 入口闸门：会话不属于当前账号时，跑步页不能靠最近任务或残留 Activity 直接开跑。
        // 主页面在 onResume 也会拦一次，这里兜住 RunActivity 自己从后台被恢复的情况。
        val store = accountStore
        if (store == null || !CasSession.isSignedIn(this, store)) {
            Log.i(TAG, "未登录或会话不属于当前账号，退出跑步页并回到登录页")
            startActivity(LoginActivity.intentForAutoLogin(this))
            finish()
            return
        }

        val stadiumId = intent.getStringExtra(EXTRA_STADIUM_ID) ?: settings.stadiumId
        val lane = intent.getIntExtra(EXTRA_LANE, settings.laneIndex)
        val pace = intent.getIntExtra(EXTRA_PACE, settings.paceSecPerKm).toDouble()
        val jitterPct = intent.getIntExtra(EXTRA_JITTER, settings.speedJitterPct)
        val driftCm = intent.getIntExtra(EXTRA_DRIFT_CM, settings.driftCm)
        val allowLaneChange = intent.getBooleanExtra(EXTRA_LANE_CHANGE, settings.allowLaneChange)
        val allowWalk = intent.getBooleanExtra(EXTRA_WALK, settings.allowWalk)
        targetKm = intent.getFloatExtra(EXTRA_TARGET_KM, settings.targetKm).toDouble()
        autoSwitch = intent.getBooleanExtra(EXTRA_AUTO_SWITCH, settings.autoSwitch)
        entryUrl = intent.getStringExtra(EXTRA_ENTRY_URL) ?: settings.entryUrl

        val track = runCatching { TemplateRepository(this).loadTrack(stadiumId) }.getOrElse {
            Toast.makeText(
                this,
                getString(R.string.template_missing, it.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        session = RunSession(
            track = track,
            paceSecPerKm = pace,
            selectedLane = lane,
            speedJitterPct = jitterPct / 100.0,
            driftM = driftCm / 100.0,
            allowLaneChange = allowLaneChange,
            targetDistanceM = targetKm * 1000.0,
            allowWalk = allowWalk
        )

        webView = findViewById(R.id.webView)
        tvMode = findViewById(R.id.tvMode)
        tvClocks = findViewById(R.id.tvClocks)
        tvDistance = findViewById(R.id.tvDistance)
        tvLap = findViewById(R.id.tvLap)
        tvAccount = findViewById(R.id.tvAccount)
        btnSwitch = findViewById(R.id.btnSwitch)
        btnBack = findViewById(R.id.btnBack)
        btnBack.setOnClickListener { webBack() }
        findViewById<MaterialButton>(R.id.btnExit).setOnClickListener { confirmExit() }

        setupWebView()

        watcher = ClockWatcher(webView)
        watcher.start { status -> onClockStatus(status) }

        btnSwitch.setOnClickListener { switchToSimulation(manual = true) }
        renderHud()
        updateBackButton()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            // 优先在页面历史里后退，退到底了才询问是否退出跑步
            override fun handleOnBackPressed() {
                if (!webBack()) confirmExit()
            }
        })

        startLocationUpdates()
        startKeepAlive()
        webView.loadUrl(entryUrl)
        handler.postDelayed(hudTick, 1000)
    }

    /** 前台服务把进程钉在前台优先级；没有定位权限时 Android 14 会拒绝 location 类型的前台服务 */
    private fun startKeepAlive() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (fine) {
            RunKeepAliveService.start(this)
        } else {
            Log.w(TAG, "无精确定位权限，跳过前台保活服务")
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.setBackgroundColor(Color.WHITE)
        // 后台时不要主动降级渲染进程：跑步期间页面被系统冻结，计时与上传都会停
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            webView.setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false)
        }
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            @Suppress("DEPRECATION")
            allowFileAccess = false
            @Suppress("DEPRECATION")
            allowContentAccess = false
        }

        // 必须在 loadUrl 之前注册：document-start 脚本一执行就会回调 RunGeo
        webView.addJavascriptInterface(GeoBridge(session), "RunGeo")

        val shim = GeoShim.script(this)
        val injectedBeforeLoad = runCatching {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return@runCatching false
            WebViewCompat.addDocumentStartJavaScript(webView, shim, setOf("*"))
            true
        }.getOrElse {
            Log.w(TAG, "document-start 注入注册失败：${it.message}")
            false
        }
        if (!injectedBeforeLoad) {
            legacyInjection = true
            Toast.makeText(this, R.string.inject_unsupported, Toast.LENGTH_LONG).show()
        }

        // 只对 CAS 登录页注入账密预填：只填不提交，登录按钮仍由用户按
        val prefill = accountStore?.active()?.takeIf { !it.password.isNullOrEmpty() }
        if (prefill?.password != null) {
            runCatching {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return@runCatching
                WebViewCompat.addDocumentStartJavaScript(
                    webView,
                    CasPrefill.script(prefill.username, prefill.password),
                    setOf(CasPrefill.CAS_ORIGIN)
                )
                Log.i(TAG, "已注册 CAS 账密预填（账号 ${prefill.username}，密码不入日志）")
            }.onFailure { Log.w(TAG, "CAS 预填注入失败：${it.message}") }
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                // 页面只在真实阶段真正使用浏览器定位；模拟阶段由注入脚本接管
                callback?.invoke(origin, true, false)
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                if (consoleMessage != null) {
                    Log.d(TAG, "[web] ${consoleMessage.message()} @${consoleMessage.lineNumber()}")
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (legacyInjection) view?.evaluateJavascript(shim, null)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                updateBackButton()
                view ?: return
                if (url == null || !url.startsWith("http")) return
                view.evaluateJavascript("(window.__runGeo && window.__runGeo.installed) ? '1' : '0'") { value ->
                    if (value != "\"1\"") {
                        view.evaluateJavascript(shim, null)
                        Log.w(TAG, "document-start 注入未生效，已在 onPageFinished 补注入")
                    }
                }
                // 首次进入时重定向链可能不走 doUpdateVisitedHistory（视 WebView 版本而定）
                maybeRecoverSession(url)
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    Log.w(TAG, "主文档加载失败：${error?.description}")
                }
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateBackButton()
                maybeRecoverSession(url)
            }
        }

        if (BuildConfig.DEBUG) WebView.setWebContentsDebuggingEnabled(true)
    }

    private fun startLocationUpdates() {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) return

        locationManager = getSystemService(LocationManager::class.java)
        listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).forEach { provider ->
            runCatching {
                locationManager.getLastKnownLocation(provider)?.let { session.onRealFix(it.toGeoFix()) }
                locationManager.requestLocationUpdates(
                    provider, 1000L, 0f, locationListener, Looper.getMainLooper()
                )
            }.onFailure { Log.w(TAG, "定位源 $provider 不可用：${it.message}") }
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            session.onRealFix(location.toGeoFix())
        }

        @Deprecated("Deprecated in Java")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private fun Location.toGeoFix(): GeoFix = GeoFix(
        lat = latitude,
        lon = longitude,
        accuracy = if (hasAccuracy()) accuracy else 20f,
        altitude = if (hasAltitude()) altitude else 40.0,
        heading = if (hasBearing()) bearing.toDouble() else null,
        speed = if (hasSpeed()) speed.toDouble() else 0.0,
        timeMs = System.currentTimeMillis()
    )

    private fun onClockStatus(status: ClockWatcher.Status) {
        lastStatus = status
        if (status.total > 0 && status.passed < status.total) sawIncomplete = true
        // 页面能报出打卡点 = 账号确实登录成功了，把这个信号记到账号上
        if (!loginMarked && status.total > 0) {
            loginMarked = true
            activeAccountId?.let { accountStore?.markLogin(it) }
        }

        val allPassed = status.total > 0 && status.passed >= status.total
        // 只认“本次进来之后看到过未完成”的转换，避免页面里上一次跑步的残留状态直接触发切换
        if (autoSwitch && sawIncomplete && allPassed && !session.isSimulating) {
            switchToSimulation(manual = false)
        }
        renderHud()
    }

    private fun switchToSimulation(manual: Boolean) {
        if (session.isSimulating) return
        val hadFix = session.lastRealFix != null
        val aligned = session.beginSimulation()
        webView.evaluateJavascript(
            "window.__runGeo && window.__runGeo.push && window.__runGeo.push();", null
        )
        val message = when {
            !hadFix -> R.string.switched_no_fix
            !aligned -> R.string.switched_far
            manual -> R.string.switched_manual
            else -> R.string.switched_aligned
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        Log.i(
            TAG,
            "切换到模拟：aligned=$aligned offTrack=${(session.offTrackM * 10).toInt() / 10.0}m " +
                "lane=${session.lane} pace=${session.paceSecPerKm}"
        )
        renderHud()
    }

    private fun renderHud() {
        val simulating = session.isSimulating
        tvMode.text = getString(if (simulating) R.string.hud_sim else R.string.hud_real)
        tvMode.setTextColor(ContextCompat.getColor(this, if (simulating) R.color.state_sim else R.color.state_real))

        val status = lastStatus
        tvClocks.text = if (status != null && status.total > 0) {
            getString(R.string.hud_clocks, status.passed, status.total)
        } else {
            getString(R.string.hud_clocks_unknown)
        }

        val account = accountStore?.active()
        tvAccount.text = account?.let { getString(R.string.hud_account, it.display) } ?: ""

        val simKm = session.simulatedDistance() / 1000.0
        val km = status?.distanceKm ?: simKm
        tvDistance.text = getString(R.string.hud_distance, km, targetKm)

        val lap = Format.pace(session.predictedLapSeconds().toInt())
        tvLap.text = if (simulating) {
            getString(R.string.hud_lap_sim, session.lane, lap, simKm)
        } else {
            getString(R.string.hud_lap, session.lane, lap)
        }

        btnSwitch.isEnabled = !simulating
        btnSwitch.text = getString(if (simulating) R.string.switching else R.string.switch_to_sim)

        if (!targetAnnounced && status?.distanceKm != null && status.distanceKm >= targetKm) {
            targetAnnounced = true
            Toast.makeText(this, R.string.target_reached, Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 页面自己跳回登录页 = 平台 token 已失效（状态码 101/102/103 时它会清掉 user 并 reLaunch）。
     *
     * 恢复分两级：
     * 1. 先用现成的 CASTGC 原生换票（[CasSession.renew]）——TGT 还活着就静默恢复；
     * 2. TGT 没了就拉起原生登录页（[LoginActivity]）。存了密码的话先试一次静默账密登录，
     *    撞上短信二验立刻停手，把那张已被服务端接受的二验表单交给登录页，用户只需补验证码。
     *
     * 每 60 s 最多两次，且同一时间只允许一个登录页/静默尝试。
     */
    private fun maybeRecoverSession(url: String?) {
        if (url.isNullOrEmpty()) return
        if (platformUrls.isCasUrl(url)) {
            // 页面自己跳到了 CAS 登录页：说明既没有平台 token，也没有活着的 TGT。
            // 这是首次未登录/过期后的落点，直接交给原生登录页，别让用户在 WebView 里重复输密码。
            if (loginLaunched) return
            val now = SystemClock.elapsedRealtime()
            if (recoveryAttempts >= 2 || now - lastRecoveryAt < 60_000L) return
            recoveryAttempts++
            lastRecoveryAt = now
            // 同 pages/login 分支：旧平台 token 已不可用，先清页面数据再交给原生登录
            SessionCookies.clearPageData()
            CasSession.markExpired(this)
            Log.i(TAG, "页面落到 CAS 登录页，拉起原生登录：${SessionCookies.describe()}")
            attemptNativeLogin()
            return
        }
        if (!url.contains("pages/login", ignoreCase = true)) return
        if (loginLaunched) return
        val now = SystemClock.elapsedRealtime()
        if (recoveryAttempts >= 2 || now - lastRecoveryAt < 60_000L) return
        recoveryAttempts++
        lastRecoveryAt = now
        // 页面已经落在登录页/CAS，旧 localStorage.user（uid/token/sign）必然不可用；
        // 在重载或重新登录之前清掉，避免新会话被上一次账号的数据干扰。
        SessionCookies.clearPageData()
        CasSession.markExpired(this)
        Log.i(TAG, "检测到登录页，尝试自动恢复：${SessionCookies.describe()}")
        Toast.makeText(this, R.string.session_renewing, Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            when (val state = CasSession.renew(entryUrl)) {
                CasSession.Renew.LoggedIn -> {
                    CasSession.markSignedIn(this@RunActivity, accountStore?.active()?.username)
                    Toast.makeText(this@RunActivity, R.string.session_renewed, Toast.LENGTH_SHORT).show()
                    webView.loadUrl(entryUrl)
                }

                CasSession.Renew.NeedLogin -> attemptNativeLogin()

                is CasSession.Renew.Error ->
                    Toast.makeText(
                        this@RunActivity,
                        getString(R.string.session_renew_failed, state.detail),
                        Toast.LENGTH_LONG
                    ).show()
            }
        }
    }

    /**
     * TGT 已经不在：能静默就静默，否则拉起原生登录页。
     *
     * 静默只走"已保存的账密"这一条，且受 [SilentLogin] 的冷却/熔断约束；撞到二验会停在
     * 发短信之前，把表单交给登录页继续——绝不会替用户发短信。
     */
    private fun attemptNativeLogin() {
        if (loginLaunched || isFinishing) return
        val store = accountStore
        val account = store?.active()
        val password = account?.password
        if (store == null || account == null || password.isNullOrBlank()) {
            launchLogin(Intent(this, LoginActivity::class.java))
            return
        }
        when (SilentLogin.decide(this, store)) {
            SilentLogin.Decision.ALLOW -> runSilentLogin(account.username, password)
            // 熔断说明这份密码已被服务端否定：别原样再提交，账号预填、密码清空
            SilentLogin.Decision.DISABLED -> launchLogin(LoginActivity.intentForInvalidPassword(this))
            // 已经有一发静默登录在跑：让它先出结果，别叠着再拉一个登录页
            SilentLogin.Decision.IN_FLIGHT -> Unit
            else -> launchLogin(Intent(this, LoginActivity::class.java))
        }
    }

    private fun runSilentLogin(username: String, password: String) {
        SilentLogin.claim()
        val generation = CasSession.generation
        Log.i(TAG, "用已保存的账密静默换票（撞到二验会停下）")
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                try {
                    CasSession.loginSilently(username, password)
                } catch (e: IOException) {
                    Log.w(TAG, "静默换票网络异常", e)
                    SilentLoginOutcome.Error(e.message ?: "网络异常")
                }
            }
            SilentLogin.record(this@RunActivity, outcome)
            if (generation != CasSession.generation) {
                // 期间账号被切走/删掉：这次换到的 cookie 属于旧账号，必须作废
                Log.w(TAG, "静默换票期间账号已变化，丢弃结果并清会话")
                CasSession.signOut(this@RunActivity)
                return@launch
            }
            when (outcome) {
                SilentLoginOutcome.Success -> {
                    CasSession.markSignedIn(this@RunActivity, accountStore?.active()?.username)
                    Toast.makeText(this@RunActivity, R.string.session_renewed, Toast.LENGTH_SHORT).show()
                    webView.loadUrl(entryUrl)
                }

                is SilentLoginOutcome.NeedSecondFactor -> launchLogin(
                    LoginActivity.intentForSecondFactor(this@RunActivity, outcome.form)
                )

                SilentLoginOutcome.Rejected ->
                    launchLogin(LoginActivity.intentForInvalidPassword(this@RunActivity))

                is SilentLoginOutcome.Error -> {
                    Log.w(TAG, "静默换票失败：${outcome.detail}")
                    launchLogin(Intent(this@RunActivity, LoginActivity::class.java))
                }
            }
        }
    }

    private fun launchLogin(intent: Intent) {
        if (loginLaunched || isFinishing) return
        loginLaunched = true
        loginLauncher.launch(intent)
    }

    /** 在页面历史里后退一步；没有可回退的历史时返回 false，交给退出确认处理。 */
    private fun webBack(): Boolean {        if (!::webView.isInitialized) return false
        val canGoBack = runCatching { webView.canGoBack() }.getOrDefault(false)
        if (!canGoBack) return false
        runCatching { webView.goBack() }
        return true
    }

    private fun updateBackButton() {
        if (!::btnBack.isInitialized || !::webView.isInitialized) return
        val canGoBack = runCatching { webView.canGoBack() }.getOrDefault(false)
        btnBack.isEnabled = canGoBack
        btnBack.alpha = if (canGoBack) 1f else 0.38f
    }

    private fun confirmExit() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.exit_confirm_title)
            .setMessage(R.string.exit_confirm_msg)
            .setPositiveButton(R.string.exit_confirm_ok) { _, _ -> finish() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    override fun onPause() {
        super.onPause()
        // cookie 先落盘：进程随时可能在后台被杀，会话能不能续上取决于这一步
        CasSession.flush()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (::watcher.isInitialized) watcher.stop()
        if (::locationManager.isInitialized) {
            runCatching { locationManager.removeUpdates(locationListener) }
        }
        CasSession.flush()
        RunKeepAliveService.stop(this)
        if (::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }
}
