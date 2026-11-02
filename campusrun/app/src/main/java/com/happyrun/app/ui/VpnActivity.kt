package com.happyrun.app.ui

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.happyrun.app.R
import com.happyrun.app.data.AccountStore
import com.happyrun.app.data.AppSettings
import com.happyrun.app.data.VpnAccountStore
import com.happyrun.app.vpn.OpenVpnService
import com.happyrun.app.vpn.VpnLog
import com.happyrun.app.vpn.VpnOtpPrompt
import com.happyrun.app.vpn.VpnPhase
import com.happyrun.app.vpn.VpnState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 校园 VPN 的开关页。
 *
 * 账号默认沿用当前账号里保存的学号/密码（VPN 与统一身份认证是同一套）；
 * 没存密码时弹一次输入框，只在本次连接里用，不落盘。
 */
class VpnActivity : AppCompatActivity() {

    private lateinit var tvState: android.widget.TextView
    private lateinit var tvDetail: android.widget.TextView
    private lateinit var tvAccount: android.widget.TextView
    private lateinit var btnToggle: MaterialButton
    private lateinit var switchAppOnly: MaterialSwitch
    private lateinit var otpPrompt: VpnOtpPrompt

    private var pendingUsername = ""
    private var pendingPassword = ""

    private val consentLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            OpenVpnService.connect(this, switchAppOnly.isChecked, pendingUsername, pendingPassword)
        } else {
            Toast.makeText(this, R.string.vpn_consent_denied, Toast.LENGTH_LONG).show()
        }
    }

    private val stateListener: (VpnPhase, String) -> Unit = { phase, message ->
        VpnLog.add("UI 收到状态：$phase $message")
        runOnUiThread { render(); otpPrompt.sync() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_vpn)
        VpnLog.init(this)
        VpnLog.add("打开 VPN 页面，当前状态 ${VpnState.phase}")
        tvState = findViewById(R.id.tvVpnState)
        tvDetail = findViewById(R.id.tvVpnDetail)
        tvAccount = findViewById(R.id.tvVpnAccount)
        btnToggle = findViewById(R.id.btnVpnToggle)
        switchAppOnly = findViewById(R.id.switchAppOnly)
        otpPrompt = VpnOtpPrompt(
            this,
            onSubmitted = { code -> OpenVpnService.submitOtp(this, code) },
            onCancelled = { disconnect() },
        )

        switchAppOnly.isChecked = AppSettings(this).vpnAppOnly
        switchAppOnly.setOnCheckedChangeListener { _, checked ->
            if (VpnState.isActive()) {
                switchAppOnly.isChecked = !checked
                Toast.makeText(this, R.string.vpn_scope_locked, Toast.LENGTH_SHORT).show()
            } else {
                AppSettings(this).vpnAppOnly = checked
            }
        }

        btnToggle.setOnClickListener { if (VpnState.isActive()) disconnect() else startConnect() }
        findViewById<MaterialButton>(R.id.btnVpnSelfTest).setOnClickListener { runSelfTest() }
        findViewById<MaterialButton>(R.id.btnVpnLog).setOnClickListener { showLog() }
        findViewById<MaterialButton>(R.id.btnVpnLicenses).setOnClickListener { showLicenses() }

        VpnState.addListener(stateListener)
        render()
        otpPrompt.sync()
    }

    override fun onResume() {
        super.onResume()
        render()
        // 挑战可能在页面不在前台时到达：回来时补弹一次
        otpPrompt.sync()
    }

    override fun onDestroy() {
        VpnState.removeListener(stateListener)
        otpPrompt.dismiss()
        super.onDestroy()
    }

    private fun render() {
        val phase = VpnState.phase
        tvState.text = when (phase) {
            VpnPhase.IDLE -> getString(R.string.vpn_state_idle)
            VpnPhase.STARTING -> getString(R.string.vpn_state_starting)
            VpnPhase.NEED_AUTH -> getString(R.string.vpn_state_auth)
            VpnPhase.NEED_OTP -> getString(R.string.vpn_otp_needed)
            VpnPhase.CONNECTING -> getString(R.string.vpn_state_connecting, VpnState.message)
            VpnPhase.CONNECTED -> getString(R.string.vpn_state_connected)
            VpnPhase.RECONNECTING -> getString(R.string.vpn_state_reconnecting)
            VpnPhase.ERROR -> getString(R.string.vpn_state_error)
        }
        tvDetail.text = when (phase) {
            VpnPhase.NEED_OTP -> VpnState.challenge.ifBlank { VpnState.message }
            VpnPhase.ERROR -> VpnState.message
            else -> ""
        }

        val credential = VpnAccountStore.resolve(this)
        val account = AccountStore(this).active()
        tvAccount.text = when {
            credential != null -> getString(R.string.vpn_account_saved, credential.username)
            account == null -> getString(R.string.vpn_account_none)
            else -> getString(R.string.vpn_account_no_password, account.username)
        }

        btnToggle.text = getString(if (VpnState.isActive()) R.string.vpn_disconnect else R.string.vpn_connect)
        switchAppOnly.isEnabled = !VpnState.isActive()
    }

    private fun startConnect() {
        // 设置里指定了独立账号就用它，否则跟随当前账号；都没有就弹一次输入框
        val credential = VpnAccountStore.resolve(this)
        if (credential != null) {
            beginConnect(credential.username, credential.password)
            return
        }
        showCredentialDialog(VpnAccountStore.preferredUsername(this))
    }

    private fun showCredentialDialog(prefillUsername: String?) {
        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val inputUser = EditText(this).apply {
            hint = getString(R.string.vpn_field_username)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefillUsername ?: "")
            setSingleLine(true)
        }
        val inputPass = EditText(this).apply {
            hint = getString(R.string.vpn_field_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        form.addView(inputUser)
        form.addView(inputPass)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vpn_credentials_title)
            .setMessage(R.string.vpn_credentials_msg)
            .setView(form)
            .setPositiveButton(R.string.vpn_connect) { _, _ ->
                val user = inputUser.text.toString().trim()
                val pass = inputPass.text.toString()
                if (user.isEmpty() || pass.isEmpty()) {
                    Toast.makeText(this, R.string.vpn_credentials_required, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                beginConnect(user, pass)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun beginConnect(username: String, password: String) {
        pendingUsername = username
        pendingPassword = password
        val consent = VpnService.prepare(this)
        if (consent != null) {
            consentLauncher.launch(consent)
        } else {
            OpenVpnService.connect(this, switchAppOnly.isChecked, username, password)
        }
    }

    private fun disconnect() {
        OpenVpnService.disconnect(this)
    }

    /**
     * 分发合规：App 里打包了 GPLv2 的 openvpn 与 ics-openvpn 的启动器，
     * 许可全文随应用提供，源码按 GPLv2 一并公开。
     */
    private fun showLicenses() {
        val html = runCatching {
            assets.open("openvpn/licenses.html").bufferedReader().readText()
        }.getOrElse { getString(R.string.vpn_licenses_missing) }
        val web = android.webkit.WebView(this).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            loadDataWithBaseURL(null, html, "text/html", "utf-8", null)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vpn_licenses)
            .setView(web)
            .setPositiveButton(android.R.string.ok) { _, _ -> web.destroy() }
            .show()
    }

    private fun showLog() {
        val text = android.widget.TextView(this).apply {
            this.text = VpnLog.dump()
            textSize = 10f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(8), dp(16), dp(8))
        }
        val scroll = android.widget.ScrollView(this).apply { addView(text) }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.vpn_log_title)
            .setView(scroll)
            .setPositiveButton(R.string.vpn_log_copy) { _, _ ->
                val clipboard = getSystemService(android.content.ClipboardManager::class.java)
                clipboard?.setPrimaryClip(
                    android.content.ClipData.newPlainText("vpn-log", VpnLog.dump())
                )
                Toast.makeText(this, R.string.vpn_log_copied, Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton(R.string.vpn_log_clear) { _, _ -> VpnLog.clear() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun runSelfTest() {        findViewById<MaterialButton>(R.id.btnVpnSelfTest).isEnabled = false
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { OpenVpnService.selfTest(this@VpnActivity) }
            findViewById<MaterialButton>(R.id.btnVpnSelfTest).isEnabled = true
            MaterialAlertDialogBuilder(this@VpnActivity)
                .setTitle(R.string.vpn_self_test)
                .setMessage(result)
                .setPositiveButton(android.R.string.ok, null)
                .show()
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
