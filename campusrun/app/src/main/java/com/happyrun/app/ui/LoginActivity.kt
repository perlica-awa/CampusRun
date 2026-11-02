package com.happyrun.app.ui

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import com.happyrun.app.R
import com.happyrun.app.auth.CasAuthClient
import com.happyrun.app.auth.CasConsume
import com.happyrun.app.auth.CasFormLoad
import com.happyrun.app.auth.CasLoginForm
import com.happyrun.app.auth.CasSecondFactorForm
import com.happyrun.app.auth.CasSession
import com.happyrun.app.auth.CasSmsResult
import com.happyrun.app.auth.CasStep
import com.happyrun.app.auth.LoginAttempt
import com.happyrun.app.auth.LoginFlow
import com.happyrun.app.auth.platformUrls
import com.happyrun.app.auth.SilentLogin
import com.happyrun.app.auth.UserAgent
import com.happyrun.app.data.AccountStore
import com.happyrun.app.data.AppSettings
import com.happyrun.app.vpn.VpnGate
import com.happyrun.app.vpn.VpnPhase
import com.happyrun.app.vpn.VpnState
import com.happyrun.app.web.SessionCookies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * 登录页（应用的启动页，也是默认登录路径）。
 *
 * 冷启动流程：有保存了密码且勾选了自动登录的账号 → 先跑一遍 [LoginFlow.autoLogin]；
 * 否则直接展示表单。自动登录失败按 [LoginAttempt] 分流，网络类错误不再自动跳网页
 * ——网络不通时网页同样打不开，此时该做的是「先连校园 VPN 再重试」。
 *
 * 账密表单 / 二次验证 / 加载失败三个面板在同一页里切换，登录协议全部走
 * [CasAuthClient]，不用 WebView 驱动登录页。
 *
 * 三个面板：
 * 1. 账密 —— `fetchLoginForm()` 取一次性 `lt`/`execution`，`submitCredentials()` 提交；
 * 2. 二次验证 —— 图形码 + 短信码；**只有点"获取短信验证码"才会真的发短信**；
 * 3. 加载失败 —— 说明原因，给"重试"、"连校园 VPN 后重试"和"网页登录"三条路。
 *
 * VPN 就在这里拉起：它要的账号密码跟 CAS 是同一套，表单里填的就是它的凭据。
 * 连接逻辑在 [VpnGate] 里，验证码挑战也和 VPN 页共用同一个对话框。
 *
 * 成功判据不是"本地有 cookie"，而是兑票真正落到平台主机（[CasConsume.Ok]）。
 * 成功后清掉页面 localStorage 里的旧 `user`，再由调用方重载入口——这样换账号不会串数据。
 */
class LoginActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LoginActivity"

        private const val MODE_MOBILE = "mobile"
        private const val MODE_EMAIL = "email"

        /** 静默续期交过来的二验表单：账密已被服务端接受，用户只差一个验证码 */
        private const val EXTRA_SECOND_FACTOR = "second_factor_form"

        /** 保存的那份密码已被服务端否定：账号预填、密码清空 */
        private const val EXTRA_INVALID_PASSWORD = "invalid_password"

        /** 不是启动入口（比如主页面发现会话失效后拉起的），但仍要走自动登录 */
        private const val EXTRA_AUTO_LOGIN = "auto_login"

        /** 登录成功后直接进主页面（启动入口或主页面把自己的位置交给登录页时） */
        private const val EXTRA_OPEN_MAIN = "open_main"

        /**
         * 账号管理页的"新增账号"：表单保持空白、不自动登录，成功后只回给调用方，
         * 由账号列表刷新。调用方负责先清会话，否则 CAS 会直接换回旧账号。
         */
        private const val EXTRA_ADD_ACCOUNT = "add_account"

        fun intentForSecondFactor(context: Context, form: CasSecondFactorForm): Intent =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_SECOND_FACTOR, form)

        fun intentForInvalidPassword(context: Context): Intent =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_INVALID_PASSWORD, true)

        /** 新增账号：空白表单、不自动登录；成功后由 [AccountsActivity] 刷新列表 */
        fun intentForAddAccount(context: Context): Intent =
            Intent(context, LoginActivity::class.java).putExtra(EXTRA_ADD_ACCOUNT, true)

        /**
         * 会话刚失效：允许再自动登录一次（账号可能还留着有效密码），
         * 成功后直接进主页面——调用方（主页面）通常已经 finish 了自己。
         */
        fun intentForAutoLogin(context: Context): Intent =
            Intent(context, LoginActivity::class.java)
                .putExtra(EXTRA_AUTO_LOGIN, true)
                .putExtra(EXTRA_OPEN_MAIN, true)
    }

    private val cas = CasAuthClient(CasSession.http)
    private val store by lazy { AccountStore(this) }
    private val settings by lazy { AppSettings(this) }
    private lateinit var vpnGate: VpnGate

    private var form: CasLoginForm? = null
    private var secondFactor: CasSecondFactorForm? = null
    private var busy = false

    /** 已经自动回落到网页登录：防止解析失败时反复拉起同一个页面 */
    private var webFallbackLaunched = false

    /** 本次登录用的账密（提交后暂存）。保存/清除只在登录成功之后做。 */
    private var pendingUsername: String? = null
    private var pendingPassword: String? = null
    private var pendingInitialError: String? = null

    /** 当前显示的是哪一张卡片：VPN 失败时要把错误说在用户正在看的那个面板上 */
    private var currentPanel: View? = null

    /** 登录成功后是否由自己拉起主页面（作为启动入口，或主页面把位置交给了登录页） */
    private var openMainAfterLogin = false

    private lateinit var groupCredentials: View
    private lateinit var groupSecondFactor: View
    private lateinit var groupLoadError: View
    private lateinit var progressLogin: LinearProgressIndicator
    private lateinit var tvLoginProgress: TextView
    private lateinit var etUsername: TextInputEditText
    private lateinit var etPassword: TextInputEditText
    private lateinit var cbRememberPassword: MaterialCheckBox
    private lateinit var cbConnectVpn: MaterialCheckBox
    private lateinit var tvError: TextView
    private lateinit var btnLogin: MaterialButton
    private lateinit var ivCaptcha: ImageView
    private lateinit var btnRefreshCaptcha: MaterialButton
    private lateinit var etCaptcha: TextInputEditText
    private lateinit var tilSmsCode: TextInputLayout
    private lateinit var etSmsCode: TextInputEditText
    private lateinit var tv2faError: TextView
    private lateinit var btnSendSms: MaterialButton
    private lateinit var btnSubmit2fa: MaterialButton
    private lateinit var tvLoadError: TextView
    private lateinit var btnRestartLogin: MaterialButton
    private lateinit var btnRetry: MaterialButton
    private lateinit var btnVpnRetry: MaterialButton

    private val webLoginLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            finishLogin()
        } else {
            // 用户从网页登录页退回来了：允许再点一次"改用网页登录"
            webFallbackLaunched = false
            setBusy(false)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UserAgent.adopt(this)
        // 登录页一打开就清掉页面数据（localStorage.user 等），并且**在登录之前**清：
        // 放在兑票成功之后清会与调用方随后的页面加载抢时序，可能把刚写入的新 user 抹掉。
        SessionCookies.clearPageData()
        setContentView(R.layout.activity_login)
        // 必须在 onCreate 里建：内部要注册 VPN 授权的 ActivityResultLauncher
        vpnGate = VpnGate(this)

        progressLogin = findViewById(R.id.progressLogin)
        tvLoginProgress = findViewById(R.id.tvLoginProgress)
        groupCredentials = findViewById(R.id.groupCredentials)
        groupSecondFactor = findViewById(R.id.groupSecondFactor)
        groupLoadError = findViewById(R.id.groupLoadError)
        etUsername = findViewById(R.id.etUsername)
        etPassword = findViewById(R.id.etPassword)
        cbRememberPassword = findViewById(R.id.cbRememberPassword)
        cbConnectVpn = findViewById(R.id.cbConnectVpn)
        tvError = findViewById(R.id.tvError)
        btnLogin = findViewById(R.id.btnLogin)
        ivCaptcha = findViewById(R.id.ivCaptcha)
        btnRefreshCaptcha = findViewById(R.id.btnRefreshCaptcha)
        etCaptcha = findViewById(R.id.etCaptcha)
        tilSmsCode = findViewById(R.id.tilSmsCode)
        etSmsCode = findViewById(R.id.etSmsCode)
        tv2faError = findViewById(R.id.tv2faError)
        btnSendSms = findViewById(R.id.btnSendSms)
        btnSubmit2fa = findViewById(R.id.btnSubmit2fa)
        tvLoadError = findViewById(R.id.tvLoadError)
        btnRestartLogin = findViewById(R.id.btnRestartLogin)
        btnRetry = findViewById(R.id.btnRetry)
        btnVpnRetry = findViewById(R.id.btnVpnRetry)

        findViewById<MaterialToolbar>(R.id.toolbarLogin).setNavigationOnClickListener { finish() }

        btnLogin.setOnClickListener { submitCredentials() }
        btnRefreshCaptcha.setOnClickListener { refreshCaptcha() }
        ivCaptcha.setOnClickListener { refreshCaptcha() }
        btnSendSms.setOnClickListener { sendSmsCode() }
        btnSubmit2fa.setOnClickListener { submitSecondFactor() }
        btnRestartLogin.setOnClickListener {
            // 换账号必须真的清掉旧 TGT：否则 CAS 会把"换个账号"又静默换回原来那个人
            Log.i(TAG, "用户选择换个账号，清空会话后重取表单")
            secondFactor = null
            setBusy(false)
            CasSession.signOut(this) { loadLoginForm() }
        }
        btnRetry.setOnClickListener { loadLoginForm() }
        btnVpnRetry.setOnClickListener { retryViaVpn() }
        findViewById<MaterialButton>(R.id.btnWebLogin).setOnClickListener { openWebLogin() }
        cbConnectVpn.setOnCheckedChangeListener { _, checked ->
            settings.connectVpnBeforeLogin = checked
        }

        etPassword.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                submitCredentials()
                true
            } else {
                false
            }
        }

        // 上次的会话清理可能死在 removeAllCookies 回调之前；先补做完再决定能不能自动登录，
        // 否则残留的 CASTGC 会把刚删掉的账号静默换回来。
        CasSession.resumePendingClear(this) {
            if (isFinishing || isDestroyed) return@resumePendingClear
            applyInitialState(savedInstanceState == null)
        }
    }

    private fun applyInitialState(firstLaunch: Boolean) {
        val adding = intent.getBooleanExtra(EXTRA_ADD_ACCOUNT, false)
        // 启动入口进来，或主页面把位置交给了登录页：成功后由登录页拉起主页面。
        // 新增账号是调用方（账号管理页）的中间步骤，成功后只回结果，不自己进主页面。
        openMainAfterLogin = !adding && (isTaskRoot || intent.getBooleanExtra(EXTRA_OPEN_MAIN, false))

        val handoff = secondFactorFromIntent()
        if (adding) {
            // 新增账号：不预填旧账号；默认记住密码，登录成功后才落账号表
            Log.i(TAG, "新增账号模式：空白表单，不自动登录")
            cbRememberPassword.isChecked = true
        } else {
            store.active()?.let {
                etUsername.setText(it.username)
                it.password?.let { password -> etPassword.setText(password) }
                // 有保存的密码就等于"记住密码"：保存密码与下次自动登录是同一个选择
                cbRememberPassword.isChecked = it.password != null
            }
        }
        cbConnectVpn.isChecked = settings.connectVpnBeforeLogin

        if (handoff != null) {
            Log.i(TAG, "从静默续期接手的二次验证表单，直接进二验面板")
            val active = store.active()
            pendingUsername = active?.username
            pendingPassword = active?.password
            showSecondFactor(handoff)
            return
        }

        if (intent.getBooleanExtra(EXTRA_INVALID_PASSWORD, false)) {
            Log.i(TAG, "保存的密码已失效，清空密码框")
            etPassword.setText("")
            pendingInitialError = getString(R.string.login_saved_password_invalid)
        }

        // 站点地址是构建时注入的；没注入时任何登录请求都会打到空地址
        if (!platformUrls.configured) {
            Log.e(TAG, "站点地址未注入，登录不可用")
            showLoadError(getString(R.string.target_not_configured))
            return
        }

        // 只有"应用启动入口"或调用方明确要求时才自动登录；从跑步页拉起时直接给表单，
        // 否则会出现"静默登录失败 → 拉起登录页 → 又静默登录"的循环
        val wantsAutoLogin = !adding && firstLaunch &&
            (isTaskRoot || intent.getBooleanExtra(EXTRA_AUTO_LOGIN, false))
        if (wantsAutoLogin) {
            autoLogin()
        } else {
            loadLoginForm()
        }
    }

    private fun secondFactorFromIntent(): CasSecondFactorForm? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra(EXTRA_SECOND_FACTOR, CasSecondFactorForm::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra(EXTRA_SECOND_FACTOR) as? CasSecondFactorForm
        }

    // ── 冷启动自动登录 ─────────────────────────────────────────────────────

    /**
     * 有账号、有密码、且勾选了"下次启动自动登录"才跑。结论交给 [LoginAttempt] 分流：
     * - 成功 → 直接进主页面；
     * - 需要账密/密码被拒 → 表单（密码被拒时清空密码框）；
     * - 需要二验/页面不识别 → 网页登录；
     * - 网络异常 → 停在错误面板，主按钮是「连接校园 VPN 后重试」。
     */
    private fun autoLogin() {
        val account = store.active()
        if (account == null) {
            Log.i(TAG, "没有当前账号，直接显示登录表单")
            loadLoginForm()
            return
        }
        if (!account.autoLogin || account.password.isNullOrBlank()) {
            Log.i(TAG, "账号未开启自动登录或没存密码，直接显示登录表单")
            loadLoginForm()
            return
        }

        Log.i(TAG, "开始自动登录：${account.username}")
        setBusy(true, getString(R.string.login_auto_checking))
        val generation = CasSession.generation
        lifecycleScope.launch {
            val attempt = withContext(Dispatchers.IO) {
                LoginFlow.autoLogin(
                    context = this@LoginActivity,
                    store = store,
                    entryUrl = settings.entryUrl,
                )
            }
            if (isFinishing || isDestroyed) return@launch
            setBusy(false)
            if (generation != CasSession.generation) {
                // 自动登录期间账号被切走/删掉：这次拿到的 cookie 属于旧账号，必须作废
                Log.w(TAG, "自动登录期间账号已变化，丢弃结果并清会话")
                CasSession.signOut(this@LoginActivity) { loadLoginForm() }
                return@launch
            }
            when (attempt) {
                LoginAttempt.LoggedIn -> {
                    Log.i(TAG, "自动登录成功")
                    finishLogin()
                }

                LoginAttempt.NeedCredentials -> {
                    pendingInitialError = getString(R.string.login_auto_need_credentials)
                    loadLoginForm()
                }

                LoginAttempt.InvalidPassword -> {
                    etPassword.setText("")
                    pendingInitialError = getString(R.string.login_saved_password_invalid)
                    loadLoginForm()
                }

                LoginAttempt.NeedWebLogin -> {
                    Log.i(TAG, "自动登录需要二验或页面无法识别，回落到网页登录")
                    fallbackToWeb()
                }

                LoginAttempt.AlreadyRunning -> {
                    pendingInitialError = getString(R.string.login_auto_in_flight)
                    loadLoginForm()
                }

                is LoginAttempt.Error -> {
                    Log.w(TAG, "自动登录网络异常：${attempt.detail}")
                    showLoadError(getString(R.string.login_error_network_panel))
                }
            }
        }
    }

    // ── 第 1 步：取表单 ─────────────────────────────────────────────────────

    private fun loadLoginForm(afterError: String? = null) {
        if (busy) {
            Log.w(TAG, "有请求在飞，忽略这次重取表单")
            return
        }
        form = null
        secondFactor = null
        showOnly(null)
        setBusy(true, getString(R.string.login_loading_form))
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) { runCatching { cas.fetchLoginForm() } }
            setBusy(false)
            val load = outcome.getOrElse { error ->
                if (error is IOException) {
                    Log.w(TAG, "取登录页网络异常", error)
                    onNetworkFailure()
                    return@launch
                }
                CasFormLoad.Failed(error.message ?: error.javaClass.simpleName)
            }
            when (load) {
                is CasFormLoad.Ok -> {
                    form = load.form
                    showOnly(groupCredentials)
                    val error = afterError ?: pendingInitialError
                    pendingInitialError = null
                    showCredentialsError(error)
                }

                // 本地 TGT 还活着：CAS 直接给票，不必输账密
                is CasFormLoad.Redirected -> {
                    Log.i(TAG, "本地 TGT 仍可用，直接兑票（不展示账密表单）")
                    consumeAndFinish(load.location)
                }

                is CasFormLoad.Failed -> {
                    showLoadError(getString(R.string.login_error_load_form, load.detail))
                    // 原生解析整个失效时，别把用户堵在错误面板上
                    fallbackToWeb()
                }
            }
        }
    }

    // ── 第 2 步：提交账密 ───────────────────────────────────────────────────

    private fun submitCredentials() {
        if (busy) return
        if (form == null) {
            loadLoginForm()
            return
        }
        val username = etUsername.text?.toString()?.trim().orEmpty()
        val password = etPassword.text?.toString().orEmpty()
        if (username.isEmpty()) {
            showCredentialsError(getString(R.string.login_error_empty_username))
            return
        }
        if (password.isEmpty()) {
            showCredentialsError(getString(R.string.login_error_empty_password))
            return
        }
        if (cbConnectVpn.isChecked && VpnState.phase != VpnPhase.CONNECTED) {
            connectVpnThen(username, password) { doSubmitCredentials(username, password) }
            return
        }
        doSubmitCredentials(username, password)
    }

    private fun doSubmitCredentials(username: String, password: String) {
        val current = form ?: return
        showCredentialsError(null)
        setBusy(true, getString(R.string.login_signing_in))
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { cas.submitCredentials(current, username, password) }
            }
            val step = outcome.getOrElse { error ->
                setBusy(false)
                if (error is IOException) {
                    Log.w(TAG, "提交账密网络异常", error)
                    onNetworkFailure()
                    return@launch
                }
                CasStep.Unclear(error.message ?: error.javaClass.simpleName)
            }
            when (step) {
                is CasStep.Ticket -> {
                    pendingUsername = username
                    pendingPassword = password
                    consumeAndFinish(step.location)
                }

                is CasStep.SecondFactor -> {
                    pendingUsername = username
                    pendingPassword = password
                    showSecondFactor(step.form)
                }

                is CasStep.BadCredentials -> {
                    setBusy(false)
                    Log.w(TAG, "账密被拒（服务端文案：${step.serverMessage ?: "未给出"}）")
                    loadLoginForm(
                        afterError = step.serverMessage
                            ?: getString(R.string.login_error_bad_credentials)
                    )
                }

                is CasStep.Unclear -> {
                    setBusy(false)
                    Log.w(TAG, "登录响应无法识别：${step.detail}")
                    showLoadError(getString(R.string.login_error_unclear, step.detail))
                    fallbackToWeb()
                }
            }
        }
    }

    // ── 第 3 步：二次验证 ───────────────────────────────────────────────────

    private fun showSecondFactor(target: CasSecondFactorForm) {
        secondFactor = target
        setBusy(false)
        if (target.mode != MODE_MOBILE && target.mode != MODE_EMAIL) {
            Log.w(TAG, "二验方式 ${target.mode} 原生不支持，引导去网页登录")
            showLoadError(getString(R.string.login_2fa_unsupported))
            fallbackToWeb()
            return
        }
        showOnly(groupSecondFactor)
        tv2faError.visibility = View.GONE
        etCaptcha.setText("")
        etSmsCode.setText("")
        tilSmsCode.hint = getString(
            if (target.mode == MODE_EMAIL) R.string.login_2fa_email_hint else R.string.login_2fa_sms_hint
        )
        btnSendSms.text = getString(
            if (target.mode == MODE_EMAIL) R.string.login_2fa_send_email else R.string.login_2fa_send_sms
        )
        refreshCaptcha()
    }

    private fun refreshCaptcha() {
        lifecycleScope.launch {
            ivCaptcha.setImageDrawable(null)
            val bytes = withContext(Dispatchers.IO) {
                try {
                    cas.fetchCaptcha()
                } catch (e: IOException) {
                    Log.w(TAG, "取图形验证码网络异常", e)
                    null
                }
            }
            val bitmap = bytes?.let {
                withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(it, 0, it.size) }
            }
            if (bitmap == null) {
                show2faError(getString(R.string.login_2fa_captcha_failed))
            } else {
                ivCaptcha.setImageBitmap(bitmap)
            }
        }
    }

    /** 全应用唯一会让短信真的发出去的调用，所以只挂在这个按钮上 */
    private fun sendSmsCode() {
        val target = secondFactor ?: return
        if (busy) return
        val captcha = etCaptcha.text?.toString()?.trim().orEmpty()
        if (captcha.isEmpty()) {
            show2faError(getString(R.string.login_2fa_error_empty_captcha))
            return
        }
        show2faError(null)
        setBusy(true, getString(R.string.login_2fa_sending))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    cas.requestSmsCode(target, captcha)
                } catch (e: IOException) {
                    Log.w(TAG, "请求验证码网络异常", e)
                    CasSmsResult.Failed(getString(R.string.login_error_network))
                }
            }
            setBusy(false)
            when (result) {
                CasSmsResult.Sent -> {
                    Toast.makeText(this@LoginActivity, R.string.login_2fa_send_sent, Toast.LENGTH_LONG).show()
                    etSmsCode.requestFocus()
                }

                is CasSmsResult.Rejected -> {
                    Log.w(TAG, "验证码请求被拒：${result.message}")
                    show2faError(
                        result.message.ifBlank { getString(R.string.login_2fa_send_rejected) }
                    )
                    etCaptcha.setText("")
                    refreshCaptcha()
                }

                is CasSmsResult.Failed -> {
                    Log.w(TAG, "验证码请求失败：${result.detail}")
                    show2faError(result.detail)
                }
            }
        }
    }

    private fun submitSecondFactor() {
        val target = secondFactor ?: return
        if (busy) return
        val captcha = etCaptcha.text?.toString()?.trim().orEmpty()
        val code = etSmsCode.text?.toString()?.trim().orEmpty()
        if (captcha.isEmpty()) {
            show2faError(getString(R.string.login_2fa_error_empty_captcha))
            return
        }
        if (code.isEmpty()) {
            show2faError(getString(R.string.login_2fa_error_empty_code))
            return
        }
        show2faError(null)
        setBusy(true, getString(R.string.login_signing_in))
        lifecycleScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching { cas.submitSecondFactor(target, captcha, code) }
            }
            val step = outcome.getOrElse { error ->
                setBusy(false)
                if (error is IOException) {
                    Log.w(TAG, "提交二验网络异常", error)
                    onNetworkFailure()
                    return@launch
                }
                CasStep.Unclear(error.message ?: error.javaClass.simpleName)
            }
            when (step) {
                is CasStep.Ticket -> consumeAndFinish(step.location)

                is CasStep.SecondFactor -> {
                    setBusy(false)
                    secondFactor = step.form
                    etCaptcha.setText("")
                    etSmsCode.setText("")
                    show2faError(getString(R.string.login_2fa_error_code))
                    refreshCaptcha()
                }

                is CasStep.BadCredentials -> {
                    setBusy(false)
                    Log.w(TAG, "二验被退回登录页，流程作废，重新取表单")
                    loadLoginForm(afterError = getString(R.string.login_2fa_expired))
                }

                is CasStep.Unclear -> {
                    setBusy(false)
                    Log.w(TAG, "二验响应无法识别：${step.detail}")
                    show2faError(getString(R.string.login_error_unclear, step.detail))
                    fallbackToWeb()
                }
            }
        }
    }

    // ── 校园 VPN ───────────────────────────────────────────────────────────

    /**
     * 连接成功后执行 [action]。凭据就用登录表单里的这份——VPN 和统一身份认证是同一套账号。
     * 失败时说在用户正看着的那张卡片上，不要把已经填好的表单换掉。
     */
    private fun connectVpnThen(username: String, password: String, action: () -> Unit) {
        Log.i(TAG, "为登录连接校园 VPN：账号=$username")
        setBusy(true, getString(R.string.login_vpn_connecting))
        vpnGate.ensureConnected(username, password) { result ->
            if (isFinishing || isDestroyed) return@ensureConnected
            setBusy(false)
            when (result) {
                is VpnGate.Result.Connected -> {
                    Log.i(TAG, "校园 VPN 已连接，继续登录")
                    Toast.makeText(this, R.string.login_vpn_ready, Toast.LENGTH_SHORT).show()
                    action()
                }

                is VpnGate.Result.Failed -> {
                    Log.w(TAG, "校园 VPN 连接失败：${result.reason}")
                    val message = getString(R.string.login_vpn_failed, result.reason)
                    if (currentPanel === groupCredentials) showCredentialsError(message)
                    else showLoadError(message)
                }
            }
        }
    }

    /** 错误面板上的"连接校园 VPN 后重试" */
    private fun retryViaVpn() {
        if (busy) return
        val username = etUsername.text?.toString()?.trim().orEmpty()
            .ifEmpty { store.active()?.username.orEmpty() }
        val password = etPassword.text?.toString().orEmpty()
            .ifEmpty { store.active()?.password.orEmpty() }
        if (username.isBlank() || password.isEmpty()) {
            VpnAccountDialog.show(this, prefillUsername = username.ifBlank { null }) { user, pass ->
                connectVpnThen(user, pass) {
                    Log.i(TAG, "VPN 已连接，重新加载登录页")
                    loadLoginForm(afterError = getString(R.string.login_vpn_ready))
                }
            }
            return
        }
        connectVpnThen(username, password) {
            Log.i(TAG, "VPN 已连接，重新加载登录页")
            loadLoginForm(afterError = getString(R.string.login_vpn_ready))
        }
    }

    // ── 收尾 ───────────────────────────────────────────────────────────────

    /** 兑票 + 落点判定；只有真的落到平台主机才算登录成功 */
    private fun consumeAndFinish(location: String) {
        setBusy(true, getString(R.string.login_verifying))
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                try {
                    CasSession.consume(location)
                } catch (e: IOException) {
                    Log.w(TAG, "兑票网络异常", e)
                    CasConsume.Error(e.message ?: getString(R.string.login_error_network))
                }
            }
            if (result is CasConsume.Ok) {
                Log.i(TAG, "兑票成功：${result.finalUrl}")
                finishLogin()
                return@launch
            }
            setBusy(false)
            // 这里不能退回 loadLoginForm：TGT 活着时它会再次拿到 Redirected，
            // 变成"兑票失败 → 重取 → 又 Redirected → 再兑票"的死循环
            Log.w(TAG, "兑票未落到平台主机：$result")
            if (result is CasConsume.Error) {
                // consumeTicket 只在 IOException 时给 Error：网络问题，优先提示 VPN
                onNetworkFailure()
                return@launch
            }
            showLoadError(getString(R.string.login_error_consume))
            fallbackToWeb()
        }
    }

    private fun finishLogin() {
        setBusy(false)
        // applyCredentialChoice 会清空 pendingUsername，先记下这套 cookie 属于谁
        val sessionUser = pendingUsername ?: store.active()?.username
        applyCredentialChoice()
        CasSession.markSignedIn(this, sessionUser)
        CasSession.flush()
        Log.i(TAG, "登录成功")
        setResult(RESULT_OK)
        // 启动入口或被主页面"交接"进来时，下一站是主页面；由跑步页拉起时由调用方自己继续
        if (openMainAfterLogin) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        finish()
    }

    /**
     * 按勾选框落定账号与密码，**只在登录成功之后执行**：
     * 失败时不动已存的凭据，更不会把一份打错的密码存进去。
     */
    private fun applyCredentialChoice() {
        val username = pendingUsername
        if (username == null) {
            // 靠还活着的 TGT 直接兑票：没有输入账密，只把当前账号记一次登录
            store.active()?.let { store.markLogin(it.id) }
            return
        }
        val password = pendingPassword
        pendingUsername = null
        pendingPassword = null
        val keepPassword = cbRememberPassword.isChecked
        store.recordLogin(
            username = username,
            password = password,
            keepPassword = keepPassword,
            // "记住密码"同时就是"下次启动自动登录"
            autoLogin = keepPassword,
        )
        // 换过凭据，给静默重登一次干净的机会
        SilentLogin.reset(this)
    }

    // ── 界面状态 ────────────────────────────────────────────────────────────

    private fun showOnly(group: View?) {
        currentPanel = group
        groupCredentials.visibility = if (group === groupCredentials) View.VISIBLE else View.GONE
        groupSecondFactor.visibility = if (group === groupSecondFactor) View.VISIBLE else View.GONE
        groupLoadError.visibility = if (group === groupLoadError) View.VISIBLE else View.GONE
    }

    private fun showLoadError(message: String) {
        showOnly(groupLoadError)
        tvLoadError.text = message
    }

    private fun showCredentialsError(message: String?) {
        tvError.text = message.orEmpty()
        tvError.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun show2faError(message: String?) {
        tv2faError.text = message.orEmpty()
        tv2faError.visibility = if (message.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    private fun setBusy(value: Boolean, status: String? = null) {
        busy = value
        progressLogin.visibility = if (value) View.VISIBLE else View.GONE
        tvLoginProgress.text = status.orEmpty()
        tvLoginProgress.visibility = if (value && !status.isNullOrEmpty()) View.VISIBLE else View.GONE
        btnLogin.isEnabled = !value
        etUsername.isEnabled = !value
        etPassword.isEnabled = !value
        cbRememberPassword.isEnabled = !value
        cbConnectVpn.isEnabled = !value
        btnRefreshCaptcha.isEnabled = !value
        etCaptcha.isEnabled = !value
        btnSendSms.isEnabled = !value
        etSmsCode.isEnabled = !value
        btnSubmit2fa.isEnabled = !value
        btnRestartLogin.isEnabled = !value
        btnRetry.isEnabled = !value
        btnVpnRetry.isEnabled = !value
    }

    /**
     * 网络不可达时的统一出口：不自动跳网页登录（网络不通时网页一样打不开），
     * 停在错误面板让用户选择「重试」「连接校园 VPN 后重试」或「改用网页登录」。
     */
    private fun onNetworkFailure() {
        showLoadError(getString(R.string.login_error_network_panel))
    }

    /** 逃生口：原生解析失灵或学校要求扫码时，用真 WebView 走完登录 */
    private fun openWebLogin() {
        Log.i(TAG, "用户选择网页登录（兼容模式）")
        // 把刚填好的账密带过去：网页登录页的 VPN 入口和 CAS 预填都靠它，
        // 免得用户在一个已经打不开的原生表单里输完密码、切过去又输一遍
        val username = etUsername.text?.toString()?.trim().orEmpty()
        val password = etPassword.text?.toString().orEmpty()
        val intent = Intent(this, WebLoginActivity::class.java).apply {
            if (username.isNotEmpty()) putExtra(WebLoginActivity.EXTRA_USERNAME, username)
            if (password.isNotEmpty()) putExtra(WebLoginActivity.EXTRA_PASSWORD, password)
            val remember = cbRememberPassword.isChecked
            putExtra(WebLoginActivity.EXTRA_KEEP_PASSWORD, remember)
            // 记住密码 = 下次启动自动登录，网页登录页也按同一个选择落盘
            putExtra(WebLoginActivity.EXTRA_AUTO_LOGIN, remember)
        }
        webLoginLauncher.launch(intent)
    }

    /**
     * 原生流程无法继续时自动回落到网页登录。
     *
     * 这个应用整体依赖校方页面，登录又是唯一不能失灵的功能：解析失败、二验页不认识、
     * 兑票没落到平台，都不该让用户停在一个点不动的面板上。用 [webFallbackLaunched]
     * 保证一次失败只回落一次；用户从网页页退回来之后仍可手动再点"改用网页登录"。
     */
    private fun fallbackToWeb() {
        if (webFallbackLaunched || isFinishing) return
        webFallbackLaunched = true
        Log.i(TAG, "原生流程无法继续，自动回落到网页登录")
        Toast.makeText(this, R.string.login_fallback_web, Toast.LENGTH_LONG).show()
        openWebLogin()
    }

    override fun onDestroy() {
        // 只解绑监听与验证码框：隧道是用户主动开的，页面关掉不该替他拆
        if (::vpnGate.isInitialized) vpnGate.cancel()
        super.onDestroy()
    }
}
