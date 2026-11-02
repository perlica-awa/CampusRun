package com.happyrun.app.vpn

import android.os.Looper
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.happyrun.app.R

/**
 * 收 VPN 网关验证码的对话框。
 *
 * VPN 的验证码挑战（CRV1 / CR_TEXT）和登录页的短信二验是两回事：这条码是 VPN 服务端发的，
 * 客户端只能原样弹出来让用户填。以前它只长在 [com.happyrun.app.ui.VpnActivity] 里，
 * 而登录页、网页登录页一旦自己拉起 VPN，挑战就没人应答，隧道会一直僵到超时——
 * 所以抽出来给三个页面共用。
 *
 * [sync] 可以随便在哪个线程调，内部会切到主线程。
 */
class VpnOtpPrompt(
    private val activity: AppCompatActivity,
    private val onSubmitted: (String) -> Unit,
    private val onCancelled: () -> Unit,
) {

    private var dialog: AlertDialog? = null

    /** 按当前 [VpnState.phase] 决定弹窗还是收起 */
    fun sync() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread { sync() }
            return
        }
        if (VpnState.phase != VpnPhase.NEED_OTP) {
            dismiss()
            return
        }
        if (dialog?.isShowing == true) return

        val input = EditText(activity).apply {
            hint = activity.getString(R.string.vpn_otp_hint)
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
            setPadding(dp(20), dp(12), dp(20), dp(12))
        }
        val target = MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.vpn_otp_title)
            .setMessage(VpnState.challenge.ifBlank { activity.getString(R.string.vpn_otp_default_prompt) })
            .setView(input)
            .setPositiveButton(R.string.vpn_otp_ok, null)
            .setNegativeButton(R.string.cancel) { _, _ -> onCancelled() }
            .create()

        target.setOnShowListener {
            target.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = input.text.toString().trim()
                if (code.isEmpty()) {
                    Toast.makeText(activity, R.string.vpn_otp_required, Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                onSubmitted(code)
                target.dismiss()
                dialog = null
            }
        }
        target.setOnDismissListener { if (dialog === target) dialog = null }
        dialog = target
        target.show()
    }

    fun dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            activity.runOnUiThread { dismiss() }
            return
        }
        dialog?.dismiss()
        dialog = null
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()
}
