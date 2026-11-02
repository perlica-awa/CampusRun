package com.happyrun.app.ui

import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.happyrun.app.R
import com.happyrun.app.auth.CasSession
import com.happyrun.app.auth.SilentLogin
import com.happyrun.app.data.Account
import com.happyrun.app.data.AccountStore

/**
 * 账号管理：新增、删除、切换。
 *
 * 切换不是"改一个变量"——一个 WebView 会话只认一份 CAS TGT 和一份平台 token，
 * 所以切换必然等于**清空整个会话**（[CasSession.signOut]），下次进跑步页重新登录（账号密码已预填）。
 * 删除当前账号同理：不清 cookie 的话，CAS 会拿旧 TGT 把人静默换回刚删掉的账号。
 * 新增账号同样走真实登录：先清会话，再到 [LoginActivity] 里认证一次，成功后才落账号表——
 * 本地表单直接存一份没验证过的账密没有意义，也会让会话归属和账号表脱节。
 */
class AccountsActivity : AppCompatActivity() {

    private lateinit var store: AccountStore
    private lateinit var container: LinearLayout

    /** 新增账号 = 登录页完成一次真实登录；成功回来只需重绘列表 */
    private val addAccountLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_accounts)
        store = AccountStore(this)
        container = findViewById(R.id.accountList)
        findViewById<MaterialButton>(R.id.btnAddAccount).setOnClickListener { startAddAccount() }
        render()
    }

    private fun render() {
        container.removeAllViews()
        val accounts = store.accounts()
        val activeId = store.active()?.id
        if (accounts.isEmpty()) {
            container.addView(TextView(this).apply {
                text = getString(R.string.accounts_empty)
                textSize = 13f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
                setPadding(0, dp(24), 0, dp(24))
            })
            return
        }
        accounts.forEach { account ->
            container.addView(buildRow(account, account.id == activeId))
        }
    }

    private fun buildRow(account: Account, active: Boolean): MaterialCardView {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(8), dp(12))
        }

        val texts = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        texts.addView(TextView(this).apply {
            text = account.display
            textSize = 16f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
        texts.addView(TextView(this).apply {
            text = when {
                account.password == null -> getString(R.string.account_subtitle_no_password, account.username)
                account.autoLogin -> getString(R.string.account_subtitle_auto, account.username)
                else -> getString(R.string.account_subtitle_saved, account.username)
            }
            textSize = 12f
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        })
        row.addView(texts)

        if (active) {
            row.addView(TextView(this).apply {
                text = getString(R.string.account_current)
                textSize = 12f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorPrimary))
                setPadding(dp(8), 0, dp(4), 0)
            })
        }

        row.addView(MaterialButton(this).apply {
            text = getString(R.string.account_delete)
            textSize = 12f
            minWidth = 0
            minimumWidth = 0
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorError))
            setOnClickListener { confirmDelete(account) }
        })

        return MaterialCardView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(10) }
            radius = dp(14).toFloat()
            cardElevation = dp(1).toFloat()
            strokeWidth = dp(1)
            addView(row)
            setOnClickListener {
                if (active) {
                    Toast.makeText(this@AccountsActivity, R.string.account_already_active, Toast.LENGTH_SHORT).show()
                } else {
                    confirmSwitch(account)
                }
            }
        }
    }

    private fun confirmSwitch(account: Account) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.account_switch_title, account.display))
            .setMessage(R.string.account_switch_msg)
            .setPositiveButton(R.string.account_switch_ok) { _, _ -> switchTo(account) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun switchTo(account: Account) {
        store.setActive(account.id)
        // 换过账号，静默重登的熔断/冷却不该延续到新账号上
        SilentLogin.reset(this)
        // 先刷新列表：清 cookie 是异步的，界面不能等那个回调
        render()
        CasSession.signOut(this) {
            Toast.makeText(this, getString(R.string.account_switched, account.display), Toast.LENGTH_SHORT).show()
        }
    }

    private fun confirmDelete(account: Account) {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.account_delete_title)
            .setMessage(getString(R.string.account_delete_msg, account.display))
            .setPositiveButton(R.string.account_delete_ok) { _, _ -> deleteAccount(account) }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * 删的正好是当前账号时必须清会话：否则 cookie 里的 TGT 还在，下次自动登录会拿它
     * 把刚删掉的账号静默换回来。删非当前账号则只动账号表，不影响正在用的会话。
     */
    private fun deleteAccount(account: Account) {
        val removingActive = store.active()?.id == account.id
        store.remove(account.id)
        // 立刻刷新：账号已经从存储里删掉了，不能把界面更新押在异步清 cookie 的回调上
        render()
        if (!removingActive) return
        SilentLogin.reset(this)
        CasSession.signOut(this) {
            Toast.makeText(
                this,
                getString(R.string.account_deleted, account.display),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    /**
     * 新增账号：先清掉当前会话（CAS 只认一份 TGT，残留的话登录页会直接换回旧账号），
     * 再把登录页以"新增账号"模式拉起：不预填、不自动登录。
     */
    private fun startAddAccount() {
        val active = store.active()
        if (active == null) {
            CasSession.signOut(this) { launchAddLogin() }
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.account_add_title)
            .setMessage(getString(R.string.account_add_msg, active.display))
            .setPositiveButton(R.string.account_add_ok) { _, _ ->
                CasSession.signOut(this) { launchAddLogin() }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun launchAddLogin() {
        addAccountLauncher.launch(LoginActivity.intentForAddAccount(this))
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
