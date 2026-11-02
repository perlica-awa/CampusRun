package com.happyrun.app.ui

import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.happyrun.app.R

/**
 * 弹一次 VPN 账号密码输入框（只在本次连接里用，不落盘）。
 *
 * 登录页有表单可以拿账密，网页登录页和设置页没有，所以统一放这里。
 */
object VpnAccountDialog {

    fun show(
        activity: AppCompatActivity,
        prefillUsername: String?,
        onCancelled: (() -> Unit)? = null,
        onReady: (username: String, password: String) -> Unit,
    ) {
        val form = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(activity, 20), dp(activity, 8), dp(activity, 20), 0)
        }
        val inputUser = EditText(activity).apply {
            hint = activity.getString(R.string.vpn_field_username)
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefillUsername ?: "")
            setSingleLine(true)
        }
        val inputPass = EditText(activity).apply {
            hint = activity.getString(R.string.vpn_field_password)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
        }
        form.addView(inputUser)
        form.addView(inputPass)

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.vpn_credentials_title)
            .setMessage(R.string.vpn_credentials_msg)
            .setView(form)
            .setPositiveButton(R.string.vpn_connect, null)
            .setNegativeButton(R.string.cancel) { _, _ -> onCancelled?.invoke() }
            .create()

        // 自己接管确认键：缺字段时不关对话框，避免用户填的内容全丢
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val username = inputUser.text.toString().trim()
                val password = inputPass.text.toString()
                if (username.isEmpty() || password.isEmpty()) {
                    Toast.makeText(activity, R.string.vpn_credentials_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.dismiss()
                onReady(username, password)
            }
        }
        dialog.show()
    }

    private fun dp(activity: AppCompatActivity, value: Int): Int =
        (value * activity.resources.displayMetrics.density).toInt()
}
