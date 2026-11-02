package com.happyrun.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** 一次 VPN 连接要用的账密。默认就是当前登录账号，设置页可以单独指定。 */
data class VpnCredential(val username: String, val password: String)

/**
 * 独立 VPN 账号（可选）。
 *
 * 默认 [AppSettings.vpnFollowAccount] 为 true，直接用当前登录账号，不需要这个存储；
 * 只有用户明确要"VPN 用另一个账号"时才把**学号**放这里。
 *
 * 这里只存"选了哪个账号"，密码在连接时从 [AccountStore] 现取——账号密码只有一个来源，
 * 改了账号密码 VPN 自动跟着变。旧版本曾把账密一起存在这里，读取时仍兼容，下次重新选择
 * 账号就会清掉旧密码。
 */
class VpnAccountStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences? by lazy {
        if (cachedPrefs != null) return@lazy cachedPrefs
        cachedPrefs = createPrefs(appContext)
        cachedPrefs
    }

    fun overrideUsername(): String? =
        prefs?.getString(KEY_USERNAME, null)?.trim()?.takeIf { it.isNotEmpty() }

    fun override(): VpnCredential? {
        val username = overrideUsername() ?: return null
        val saved = AccountStore(appContext).accounts().firstOrNull { it.username == username }
        saved?.password?.let { return VpnCredential(username, it) }

        // 旧数据：账密一起存在这里。新流程不再写这个字段，用户重选账号时清掉。
        val legacyPassword = prefs?.getString(KEY_PASSWORD, null).orEmpty()
        if (legacyPassword.isNotEmpty()) return VpnCredential(username, legacyPassword)
        return null
    }

    /** 从账号列表里选中一个账号；密码不复制，连接时按学号回查。 */
    fun setOverride(username: String) {
        prefs?.edit()
            ?.putString(KEY_USERNAME, username.trim())
            ?.remove(KEY_PASSWORD)
            ?.apply()
    }

    fun clearOverride() {
        prefs?.edit()?.remove(KEY_USERNAME)?.remove(KEY_PASSWORD)?.apply()
    }

    private fun createPrefs(context: Context): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREF_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (e: Exception) {
        Log.e(TAG, "加密存储不可用，独立 VPN 账号无法保存", e)
        null
    }

    companion object {
        private const val TAG = "VpnAccountStore"
        private const val PREF_NAME = "campusrun_vpn_account"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"

        @Volatile
        private var cachedPrefs: SharedPreferences? = null

        /**
         * 解析"这次该用哪个账号连 VPN"。
         *
         * 顺序：设置里单独指定且填全了 → 用它；否则跟随当前账号 → 用它；都没有返回 null。
         * 登录页不走这里：它直接用用户刚输入/即将登录的那份账密。
         */
        fun resolve(context: Context): VpnCredential? {
            if (!AppSettings(context).vpnFollowAccount) {
                VpnAccountStore(context).override()?.let { return it }
            }
            val account = AccountStore(context).active() ?: return null
            val password = account.password ?: return null
            return VpnCredential(account.username, password)
        }

        /**
         * 连 VPN 需要用户手输账密时，默认预填哪个学号。
         * 指定了独立账号就预填它，否则用当前登录账号。
         */
        fun preferredUsername(context: Context): String? {
            if (!AppSettings(context).vpnFollowAccount) {
                VpnAccountStore(context).overrideUsername()?.let { return it }
            }
            return AccountStore(context).active()?.username
        }
    }
}
