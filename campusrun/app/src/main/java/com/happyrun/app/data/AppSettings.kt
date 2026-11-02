package com.happyrun.app.data

import android.content.Context

class AppSettings(context: Context) {

    private val sp = context.getSharedPreferences("campusrun", Context.MODE_PRIVATE)

    var stadiumId: String
        get() = sp.getString(KEY_STADIUM, "venue-b") ?: "venue-b"
        set(v) = sp.edit().putString(KEY_STADIUM, v).apply()

    /** 道次，1 = 内道，8 = 外道。默认 4 道。 */
    var laneIndex: Int
        get() = sp.getInt(KEY_LANE, DEFAULT_LANE).coerceIn(1, 8)
        set(v) = sp.edit().putInt(KEY_LANE, v.coerceIn(1, 8)).apply()

    /** 模拟配速，秒/公里。 */
    var paceSecPerKm: Int
        get() = sp.getInt(KEY_PACE, DEFAULT_PACE)
        set(v) = sp.edit().putInt(KEY_PACE, v.coerceIn(MIN_PACE, MAX_PACE)).apply()

    /** 速度波动幅度，百分比（0–15），均值为 0，目标配速不变。 */
    var speedJitterPct: Int
        get() = sp.getInt(KEY_JITTER, DEFAULT_JITTER).coerceIn(0, 15)
        set(v) = sp.edit().putInt(KEY_JITTER, v.coerceIn(0, 15)).apply()

    /** 横向漂移幅度，厘米（10–150，即 0.1–1.5 m）。 */
    var driftCm: Int
        get() = sp.getInt(KEY_DRIFT, DEFAULT_DRIFT_CM).coerceIn(MIN_DRIFT_CM, MAX_DRIFT_CM)
        set(v) = sp.edit().putInt(KEY_DRIFT, v.coerceIn(MIN_DRIFT_CM, MAX_DRIFT_CM)).apply()

    /** 是否偶尔在选定道与内侧相邻道之间换道。 */
    var allowLaneChange: Boolean
        get() = sp.getBoolean(KEY_LANE_CHANGE, true)
        set(v) = sp.edit().putBoolean(KEY_LANE_CHANGE, v).apply()

    /** 是否随机插入“走一段”。 */
    var allowWalk: Boolean
        get() = sp.getBoolean(KEY_WALK, true)
        set(v) = sp.edit().putBoolean(KEY_WALK, v).apply()

    var targetKm: Float
        get() = sp.getFloat(KEY_TARGET, DEFAULT_TARGET_KM)
        set(v) = sp.edit().putFloat(KEY_TARGET, v).apply()

    /** 打卡点全部通过后自动切到模拟。 */
    var autoSwitch: Boolean
        get() = sp.getBoolean(KEY_AUTO_SWITCH, true)
        set(v) = sp.edit().putBoolean(KEY_AUTO_SWITCH, v).apply()

    var entryUrl: String
        get() = sp.getString(KEY_ENTRY, DEFAULT_ENTRY)?.takeIf { it.isNotBlank() } ?: DEFAULT_ENTRY
        set(v) = sp.edit().putString(KEY_ENTRY, v.trim()).apply()

    /** VPN 是否只放行本应用（默认是）；关掉即全局模式，所有应用都走校园网。 */
    var vpnAppOnly: Boolean
        get() = sp.getBoolean(KEY_VPN_APP_ONLY, true)
        set(v) = sp.edit().putBoolean(KEY_VPN_APP_ONLY, v).apply()

    /** VPN 账号是否跟随当前登录账号；关掉后用 [VpnAccountStore] 里单独指定的账号。 */
    var vpnFollowAccount: Boolean
        get() = sp.getBoolean(KEY_VPN_FOLLOW_ACCOUNT, true)
        set(v) = sp.edit().putBoolean(KEY_VPN_FOLLOW_ACCOUNT, v).apply()

    /** 登录页的"登录前先连校园 VPN"勾选，记住上次的选择 */
    var connectVpnBeforeLogin: Boolean
        get() = sp.getBoolean(KEY_CONNECT_VPN_BEFORE_LOGIN, false)
        set(v) = sp.edit().putBoolean(KEY_CONNECT_VPN_BEFORE_LOGIN, v).apply()

    /**
     * 上一次确认过的登录状态。真正的凭据在 cookie/localStorage 里，这里只是给界面看的
     * 状态标记；每次冷启动都会自动重新验证一次，所以它过期了也会被纠正。
     */
    val loggedIn: Boolean
        get() = sp.getBoolean(KEY_LOGGED_IN, false)

    val loggedInAt: Long
        get() = sp.getLong(KEY_LOGGED_IN_AT, 0L)

    fun markLoggedIn() {
        sp.edit().putBoolean(KEY_LOGGED_IN, true).putLong(KEY_LOGGED_IN_AT, System.currentTimeMillis()).apply()
    }

    fun markLoggedOut() {
        sp.edit().putBoolean(KEY_LOGGED_IN, false).apply()
    }

    /**
     * 当前 cookie 属于哪个学号。只由 [com.happyrun.app.auth.CasSession] 维护：
     * 登录成功时写入，注销/切换/删除当前账号时清空。null 表示未知（旧版本数据）。
     */
    var sessionOwner: String?
        get() = sp.getString(KEY_SESSION_OWNER, null)
        set(v) = sp.edit().putString(KEY_SESSION_OWNER, v).apply()

    /**
     * 有一次会话清理没做完（进程死在 `removeAllCookies` 回调之前）。
     * 下次进入登录页时必须先补清，否则旧 cookie 会让 CAS 静默换回上一个账号。
     */
    var clearPending: Boolean
        get() = sp.getBoolean(KEY_CLEAR_PENDING, false)
        set(v) = sp.edit().putBoolean(KEY_CLEAR_PENDING, v).apply()

    companion object {
        val DEFAULT_ENTRY = com.happyrun.app.auth.platformUrls.platformEntry
        const val DEFAULT_PACE = 300  // 5'00"/km
        const val DEFAULT_LANE = 4
        const val MIN_PACE = 240  // 4'00"/km
        const val MAX_PACE = 480  // 8'00"/km

        const val DEFAULT_JITTER = 10
        const val DEFAULT_DRIFT_CM = 100
        const val MIN_DRIFT_CM = 10
        const val MAX_DRIFT_CM = 150
        const val DRIFT_STEP_CM = 10
        const val DEFAULT_TARGET_KM = 5.0f

        private const val KEY_STADIUM = "stadium_id"
        private const val KEY_LANE = "lane_index"
        private const val KEY_PACE = "pace_sec_per_km"
        private const val KEY_JITTER = "speed_jitter_pct"
        private const val KEY_DRIFT = "drift_cm"
        private const val KEY_LANE_CHANGE = "allow_lane_change"
        private const val KEY_WALK = "allow_walk"
        private const val KEY_TARGET = "target_km"
        private const val KEY_AUTO_SWITCH = "auto_switch"
        private const val KEY_ENTRY = "entry_url"
        private const val KEY_VPN_APP_ONLY = "vpn_app_only"
        private const val KEY_VPN_FOLLOW_ACCOUNT = "vpn_follow_account"
        private const val KEY_CONNECT_VPN_BEFORE_LOGIN = "connect_vpn_before_login"
        private const val KEY_LOGGED_IN = "logged_in"
        private const val KEY_LOGGED_IN_AT = "logged_in_at"
        private const val KEY_SESSION_OWNER = "session_owner"
        private const val KEY_CLEAR_PENDING = "clear_pending"
    }
}
