package com.happyrun.app.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 一个被记住的账号。密码可为空——不存密码的账号只能预填学号，切换后仍要手输密码。
 *
 * [autoLogin] 是"下次启动自动登录"：只有存了密码才有意义，所以勾选保存密码时才可以一起选。
 */
data class Account(
    val id: String,
    val username: String,
    val label: String,
    val password: String?,
    val lastLoginAt: Long = 0L,
    val autoLogin: Boolean = true,
) {
    /** 界面上优先显示备注名，没写就用学号 */
    val display: String get() = label.ifBlank { username }
}

/**
 * 多账号存储。
 *
 * 存的东西里有密码，所以整个文件放在 [EncryptedSharedPreferences] 里：主密钥由 AndroidKeyStore
 * 持有、不可导出，应用 `allowBackup=false`，凭据不会随备份离开设备。
 *
 * 一个 WebView 会话只认一套 cookie，所以这里的"当前账号"只决定**下次打开页面时预填谁**；
 * 真正的切换必须清空整个会话（见 [com.happyrun.app.auth.CasSession.signOut]）。
 *
 * 加密存储建不出来（少数机型 KeyStore 异常）时降级：账号表照常可用，但**密码一律不落盘**，
 * 宁可让用户多输一次，也不把明文密码写进普通 SharedPreferences。
 */
class AccountStore(context: Context) {

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences? by lazy {
        if (cachedPrefs != null) return@lazy cachedPrefs
        cachedPrefs = createPrefs(appContext)
        cachedPrefs
    }

    fun accounts(): List<Account> {
        val raw = prefs?.getString(KEY_ACCOUNTS, null) ?: return emptyList()
        return runCatching { parseAccounts(raw) }.getOrElse {
            Log.w(TAG, "账号表解析失败：${it.message}")
            emptyList()
        }
    }

    fun active(): Account? {
        val id = prefs?.getString(KEY_ACTIVE, null) ?: return null
        return accounts().firstOrNull { it.id == id }
    }

    fun setActive(id: String?) {
        prefs?.edit()?.putString(KEY_ACTIVE, id)?.apply()
    }

    fun upsert(account: Account) {
        val list = accounts().toMutableList()
        val index = list.indexOfFirst { it.id == account.id }
        val normalized = if (prefs == null) account.copy(password = null) else account
        if (index >= 0) list[index] = normalized else list.add(normalized)
        save(list)
        Log.i(TAG, "upsert id=${account.id} 账号=${account.username}，共 ${list.size} 个")
    }

    fun remove(id: String) {
        val before = accounts()
        val list = before.filterNot { it.id == id }
        save(list)
        // 删的正好是当前账号就留空，绝不自动把另一个账号顶上来：
        // "删掉 A 却自动登录成 B" 是比"未登录"更糟的意外身份切换。
        if (prefs?.getString(KEY_ACTIVE, null) == id) setActive(null)
        Log.i(TAG, "remove id=$id：${before.size} -> ${list.size}，active=${prefs?.getString(KEY_ACTIVE, null)}")
    }

    /** 登录成功的信号：让账号列表里的"最近登录"有意义 */
    fun markLogin(id: String) {
        val list = accounts().toMutableList()
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return
        list[index] = list[index].copy(lastLoginAt = System.currentTimeMillis())
        save(list)
    }

    /**
     * 原生登录成功后落定账号：按学号找到已有条目（没有就新建）、设为当前账号、记登录时间。
     *
     * [keepPassword] 来自登录页的"保存账号密码"勾选框：
     * - true 且这次确实输入了密码 → 存下新密码；没输入（走的是 TGT 直兑）则保留原密码；
     * - false → 清掉该账号已存的密码（取消勾选就等于关闭自动续期）。
     *
     * 无论存不存密码，账号本身都会留下并成为当前账号——否则"登录成功但账号列表没变化"
     * 会让下次预填落空。
     *
     * [autoLogin] 只在 [keepPassword] 为 true 时生效：没存密码就谈不上自动登录。
     */
    fun recordLogin(
        username: String,
        password: String?,
        keepPassword: Boolean,
        autoLogin: Boolean = true,
    ): Account {
        val list = accounts().toMutableList()
        val index = list.indexOfFirst { it.username == username }
        val existing = list.getOrNull(index)
        val newPassword = when {
            !keepPassword -> null
            password != null -> password
            else -> existing?.password
        }
        val account = Account(
            id = existing?.id ?: newId(),
            username = username,
            label = existing?.label ?: "",
            password = if (prefs == null) null else newPassword,
            lastLoginAt = System.currentTimeMillis(),
            autoLogin = newPassword != null && autoLogin,
        )
        if (index >= 0) list[index] = account else list.add(account)
        save(list)
        setActive(account.id)
        Log.i(TAG, "recordLogin 账号=$username keepPassword=$keepPassword autoLogin=$autoLogin，共 ${list.size} 个")
        return account
    }

    fun newId(): String = UUID.randomUUID().toString()

    private fun save(list: List<Account>) {
        prefs?.edit()?.putString(KEY_ACCOUNTS, serializeAccounts(list))?.apply()
        Log.i(TAG, "保存账号表：${list.size} 个")
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
        Log.e(TAG, "加密存储不可用，密码将不会被保存", e)
        null
    }

    companion object {
        private const val TAG = "AccountStore"
        private const val PREF_NAME = "campusrun_accounts"
        private const val KEY_ACCOUNTS = "accounts"
        private const val KEY_ACTIVE = "active_id"

        /** 与 [accounts] 一样只在主线程用，缓存只为省掉每次解密的主密钥开销 */
        @Volatile
        private var cachedPrefs: SharedPreferences? = null

        /**
         * 账号表的序列化与解析单独拎出来：这两段是纯函数，单元测试可以直接覆盖，
         * 不必在测试里搭一套 AndroidKeyStore。
         */
        internal fun serializeAccounts(list: List<Account>): String {
            val array = JSONArray()
            list.forEach { account ->
                array.put(
                    JSONObject()
                        .put("id", account.id)
                        .put("username", account.username)
                        .put("label", account.label)
                        .put("password", account.password ?: JSONObject.NULL)
                        .put("lastLoginAt", account.lastLoginAt)
                        .put("autoLogin", account.autoLogin)
                )
            }
            return array.toString()
        }

        fun parseAccounts(raw: String): List<Account> {            val array = JSONArray(raw)
            return (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                val username = obj.optString("username").trim()
                if (username.isEmpty()) return@mapNotNull null
                Account(
                    id = obj.optString("id").takeIf { it.isNotBlank() } ?: username,
                    username = username,
                    label = obj.optString("label"),
                    password = obj.optString("password").takeIf { it.isNotEmpty() && !obj.isNull("password") },
                    lastLoginAt = obj.optLong("lastLoginAt", 0L),
                    // 旧版本存的账号没有这个字段，按"自动登录"处理，保持原有行为
                    autoLogin = obj.optBoolean("autoLogin", true),
                )
            }
        }
    }
}
