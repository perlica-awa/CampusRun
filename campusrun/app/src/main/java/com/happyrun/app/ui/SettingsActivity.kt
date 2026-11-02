package com.happyrun.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.happyrun.app.BuildConfig
import com.happyrun.app.R
import com.happyrun.app.auth.CasSession
import com.happyrun.app.data.Account
import com.happyrun.app.data.AccountStore
import com.happyrun.app.data.AppSettings
import com.happyrun.app.data.VpnAccountStore
import com.happyrun.app.update.ApkInstaller
import com.happyrun.app.update.AppVersion
import com.happyrun.app.update.GitHubReleases
import com.happyrun.app.update.ReleaseInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.File

/**
 * 设置页：账号管理入口 + VPN 账号 + 版本更新。
 *
 * VPN 账号默认跟随当前登录账号（同一套统一身份认证），这里只是把这个默认关系摆出来，
 * 并给"VPN 要用另一个账号"留一个覆盖开关：从已保存的账号里选，不再单独填账密。
 *
 * 更新走 GitHub Releases API：只读取最新正式发布，下载 APK 后校验大小/SHA-256/包名，
 * 再交给系统安装器覆盖安装。
 */
class SettingsActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var vpnAccounts: VpnAccountStore

    private lateinit var tvSettingsAccount: TextView
    private lateinit var tvVpnAccountDetail: TextView
    private lateinit var switchVpnFollow: MaterialSwitch
    private lateinit var btnSetVpnAccount: MaterialButton
    private lateinit var btnClearVpnAccount: MaterialButton

    private lateinit var tvAppVersion: TextView
    private lateinit var tvGithub: TextView
    private lateinit var tvUpdateStatus: TextView
    private lateinit var progressUpdate: ProgressBar
    private lateinit var btnCheckUpdate: MaterialButton
    private lateinit var btnInstallUpdate: MaterialButton

    private var updateJob: Job? = null
    private var pendingUpdateApk: File? = null

    /** 去系统设置授权"安装未知应用"回来后，如果已授权就继续安装。 */
    private val unknownSourcesLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        val apk = pendingUpdateApk ?: return@registerForActivityResult
        if (ApkInstaller.canInstall(this)) {
            launchInstaller(apk)
        } else {
            tvUpdateStatus.text = getString(R.string.update_install_blocked)
            tvUpdateStatus.visibility = View.VISIBLE
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        settings = AppSettings(this)
        vpnAccounts = VpnAccountStore(this)

        findViewById<MaterialToolbar>(R.id.toolbarSettings).setNavigationOnClickListener { finish() }
        tvSettingsAccount = findViewById(R.id.tvSettingsAccount)
        tvVpnAccountDetail = findViewById(R.id.tvVpnAccountDetail)
        switchVpnFollow = findViewById(R.id.switchVpnFollow)
        btnSetVpnAccount = findViewById(R.id.btnSetVpnAccount)
        btnClearVpnAccount = findViewById(R.id.btnClearVpnAccount)
        tvAppVersion = findViewById(R.id.tvAppVersion)
        tvGithub = findViewById(R.id.tvGithub)
        tvUpdateStatus = findViewById(R.id.tvUpdateStatus)
        progressUpdate = findViewById(R.id.progressUpdate)
        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        btnInstallUpdate = findViewById(R.id.btnInstallUpdate)

        switchVpnFollow.isChecked = settings.vpnFollowAccount
        switchVpnFollow.setOnCheckedChangeListener { _, checked ->
            settings.vpnFollowAccount = checked
            renderVpn()
        }

        findViewById<MaterialButton>(R.id.btnManageAccounts).setOnClickListener {
            startActivity(Intent(this, AccountsActivity::class.java))
        }
        btnSetVpnAccount.setOnClickListener { showVpnAccountPicker() }
        btnClearVpnAccount.setOnClickListener {
            vpnAccounts.clearOverride()
            Toast.makeText(this, R.string.settings_vpn_cleared, Toast.LENGTH_SHORT).show()
            renderVpn()
        }
        tvAppVersion.text = getString(R.string.settings_version, BuildConfig.VERSION_NAME)
        tvGithub.text = getString(R.string.settings_github, GitHubReleases.REPO_DISPLAY)
        tvGithub.setOnClickListener { openRepository() }
        btnCheckUpdate.setOnClickListener { checkForUpdate() }
        btnInstallUpdate.setOnClickListener { pendingUpdateApk?.let { installUpdate(it) } }

        renderAccount()
        renderVpn()
    }

    override fun onResume() {
        super.onResume()
        // 账号可能在 AccountsActivity 里被切换/删掉了
        renderAccount()
        renderVpn()
    }

    private fun renderAccount() {
        val store = AccountStore(this)
        val account = store.active()
        tvSettingsAccount.text = when {
            account == null -> getString(R.string.account_none)
            CasSession.isSignedIn(this, store) -> getString(R.string.account_logged_in, account.display)
            else -> getString(R.string.account_not_logged_in, account.display)
        }
    }

    private fun renderVpn() {
        val following = settings.vpnFollowAccount
        val overrideUsername = vpnAccounts.overrideUsername()
        val overrideAccount = overrideUsername?.let { username ->
            AccountStore(this).accounts().firstOrNull { it.username == username }
        }

        btnSetVpnAccount.visibility = if (following) View.GONE else View.VISIBLE
        btnClearVpnAccount.visibility =
            if (!following && overrideUsername != null) View.VISIBLE else View.GONE

        tvVpnAccountDetail.text = when {
            following -> {
                val resolved = VpnAccountStore.resolve(this)
                if (resolved != null) {
                    getString(R.string.settings_vpn_effective, resolved.username)
                } else {
                    getString(R.string.settings_vpn_effective_none)
                }
            }

            overrideUsername == null -> getString(R.string.settings_vpn_override_none)
            overrideAccount == null -> getString(R.string.settings_vpn_override_missing, overrideUsername)
            overrideAccount.password == null ->
                getString(R.string.settings_vpn_override_no_password, accountLabel(overrideAccount))

            else -> getString(R.string.settings_vpn_override_set, accountLabel(overrideAccount))
        }
    }

    /** 备注名没填时 display 就是学号，不要显示成 "20240392（20240392）"。 */
    private fun accountLabel(account: Account): String =
        if (account.display == account.username) {
            account.username
        } else {
            "${account.display}（${account.username}）"
        }

    /** 独立 VPN 账号从已保存的账号里选，不再单独填一套账密。 */
    private fun showVpnAccountPicker() {
        val accounts = AccountStore(this).accounts()
        if (accounts.isEmpty()) {
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.settings_vpn_pick_title)
                .setMessage(R.string.settings_vpn_pick_empty)
                .setPositiveButton(R.string.settings_vpn_pick_manage) { _, _ ->
                    startActivity(Intent(this, AccountsActivity::class.java))
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            return
        }

        val current = vpnAccounts.overrideUsername()
        val labels = accounts.map { account ->
            val base = accountLabel(account)
            if (account.username == current) {
                "$base · ${getString(R.string.settings_vpn_pick_current)}"
            } else {
                base
            }
        }.toTypedArray()

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.settings_vpn_pick_title)
            .setSingleChoiceItems(labels, accounts.indexOfFirst { it.username == current }) { dialog, which ->
                val account = accounts[which]
                vpnAccounts.setOverride(account.username)
                val text = if (account.password == null) {
                    getString(R.string.settings_vpn_pick_no_password, account.display)
                } else {
                    getString(R.string.settings_vpn_pick_saved, account.display)
                }
                Toast.makeText(
                    this,
                    text,
                    if (account.password == null) Toast.LENGTH_LONG else Toast.LENGTH_SHORT,
                ).show()
                renderVpn()
                dialog.dismiss()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ── 版本更新 ─────────────────────────────────────────────────────────────

    private fun openRepository() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(GitHubReleases.REPO_URL)))
        } catch (e: Exception) {
            Toast.makeText(this, R.string.settings_github_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkForUpdate() {
        updateJob?.cancel()
        progressUpdate.visibility = View.GONE
        btnInstallUpdate.visibility = View.GONE
        setUpdateBusy(true, getString(R.string.update_checking))

        updateJob = lifecycleScope.launch {
            try {
                val release = GitHubReleases.fetchLatest()
                if (AppVersion.compare(release.version, BuildConfig.VERSION_NAME) > 0) {
                    setUpdateBusy(false, getString(R.string.update_available_title, release.version))
                    showUpdateDialog(release)
                } else {
                    setUpdateBusy(false, getString(R.string.update_latest, BuildConfig.VERSION_NAME))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                setUpdateBusy(false, getString(R.string.update_check_failed, describe(e)))
            }
        }
    }

    private fun showUpdateDialog(release: ReleaseInfo) {
        val notes = release.notes.trim().take(2000).ifEmpty { getString(R.string.update_notes_empty) }
        val size = if (release.apkSize > 0) {
            "\n" + getString(R.string.update_size, Format.size(release.apkSize))
        } else {
            ""
        }
        val message = getString(
            R.string.update_available_msg,
            BuildConfig.VERSION_NAME,
            release.version,
            size + "\n\n" + notes,
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.update_available_title, release.version))
            .setMessage(message)
            .setPositiveButton(R.string.update_download) { _, _ -> downloadUpdate(release) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun downloadUpdate(release: ReleaseInfo) {
        updateJob?.cancel()
        progressUpdate.progress = 0
        progressUpdate.visibility = View.VISIBLE
        btnInstallUpdate.visibility = View.GONE
        setUpdateBusy(true, getString(R.string.update_downloading, 0))

        updateJob = lifecycleScope.launch {
            val dir = File(cacheDir, "updates").apply { mkdirs() }
            val safeName = release.apkName.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val target = File(dir, safeName)

            try {
                GitHubReleases.download(release, target) { percent ->
                    runOnUiThread {
                        progressUpdate.progress = percent
                        tvUpdateStatus.text = getString(R.string.update_downloading, percent)
                        tvUpdateStatus.visibility = View.VISIBLE
                    }
                }
                if (!ApkInstaller.isSameApp(this@SettingsActivity, target)) {
                    target.delete()
                    throw IllegalStateException(getString(R.string.update_install_invalid))
                }

                pendingUpdateApk = target
                progressUpdate.visibility = View.GONE
                setUpdateBusy(false, getString(R.string.update_download_done))
                btnInstallUpdate.visibility = View.VISIBLE
                installUpdate(target)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                progressUpdate.visibility = View.GONE
                setUpdateBusy(false, getString(R.string.update_download_failed, describe(e)))
            }
        }
    }

    private fun installUpdate(apk: File) {
        if (!ApkInstaller.canInstall(this)) {
            tvUpdateStatus.text = getString(R.string.update_install_blocked)
            tvUpdateStatus.visibility = View.VISIBLE
            val intent = ApkInstaller.unknownSourcesSettings(this)
            if (intent != null) {
                try {
                    unknownSourcesLauncher.launch(intent)
                } catch (e: Exception) {
                    Toast.makeText(this, R.string.update_install_blocked, Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, R.string.update_install_blocked, Toast.LENGTH_LONG).show()
            }
            return
        }
        launchInstaller(apk)
    }

    private fun launchInstaller(apk: File) {
        try {
            ApkInstaller.install(this, apk)
        } catch (e: Exception) {
            tvUpdateStatus.text = getString(R.string.update_install_failed, describe(e))
            tvUpdateStatus.visibility = View.VISIBLE
        }
    }

    private fun setUpdateBusy(busy: Boolean, status: String) {
        btnCheckUpdate.isEnabled = !busy
        tvUpdateStatus.text = status
        tvUpdateStatus.visibility = if (status.isBlank()) View.GONE else View.VISIBLE
    }

    private fun describe(e: Exception): String = e.message ?: e.javaClass.simpleName
}
