package com.happyrun.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.materialswitch.MaterialSwitch
import com.happyrun.app.R
import com.happyrun.app.auth.CasSession
import com.happyrun.app.auth.UserAgent
import com.happyrun.app.data.AccountStore
import com.happyrun.app.data.AppSettings
import com.happyrun.app.data.LaneInfo
import com.happyrun.app.data.Stadium
import com.happyrun.app.data.TemplateRepository
import com.happyrun.app.data.VpnAccountStore
import com.happyrun.app.vpn.OpenVpnService
import com.happyrun.app.vpn.VpnGate
import com.happyrun.app.vpn.VpnPhase
import com.happyrun.app.vpn.VpnState
import java.util.Locale
import kotlin.math.abs

/**
 * 主页面：VPN 快捷入口、场地/跑道、模拟参数、开始跑步。
 *
 * 账号与登录都挪去了 [LoginActivity]（应用的启动页）和 [SettingsActivity]：
 * 这里只负责"已经登录之后要跑哪儿、怎么跑"，不再自己发起自动登录，
 * 否则会和启动页的自动登录叠在一起。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var settings: AppSettings
    private lateinit var repo: TemplateRepository

    private lateinit var spinnerStadium: Spinner
    private lateinit var spinnerLane: Spinner
    private lateinit var seekPace: SeekBar
    private lateinit var tvPace: TextView
    private lateinit var tvLap: TextView
    private lateinit var seekJitter: SeekBar
    private lateinit var tvJitter: TextView
    private lateinit var seekDrift: SeekBar
    private lateinit var tvDrift: TextView
    private lateinit var switchLaneChange: MaterialSwitch
    private lateinit var switchWalk: MaterialSwitch
    private lateinit var spinnerTarget: Spinner
    private lateinit var switchAuto: MaterialSwitch
    private lateinit var btnStart: MaterialButton
    private lateinit var tvVpnHint: TextView
    private lateinit var switchVpnQuick: MaterialSwitch

    /** 主页面的 VPN 快捷开关：系统授权、验证码和连接等待都交给 VpnGate */
    private lateinit var vpnGate: VpnGate

    /** 用代码同步开关状态时不要触发用户的连接/断开逻辑 */
    private var syncingVpnSwitch = false

    private val vpnStateListener: (VpnPhase, String) -> Unit = { _, _ ->
        runOnUiThread { renderVpn() }
    }

    private var stadiums: List<Stadium> = emptyList()
    private var currentStadium: Stadium? = null
    private var currentLane: LaneInfo? = null
    private val targets = listOf(2.0f, 3.0f, 4.0f, 5.0f)

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val granted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            result[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) launchRun() else {
            Toast.makeText(this, R.string.permission_needed, Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        UserAgent.adopt(this)
        setContentView(R.layout.activity_main)
        // 必须在 onCreate 里建：内部要注册 VPN 授权的 ActivityResultLauncher
        vpnGate = VpnGate(this)

        settings = AppSettings(this)
        repo = TemplateRepository(this)

        findViewById<MaterialToolbar>(R.id.toolbar).apply {
            inflateMenu(R.menu.menu_main)
            setOnMenuItemClickListener { item ->
                if (item.itemId == R.id.action_settings) {
                    startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
                    true
                } else {
                    false
                }
            }
        }

        spinnerStadium = findViewById(R.id.spinnerStadium)
        spinnerLane = findViewById(R.id.spinnerLane)
        seekPace = findViewById(R.id.seekPace)
        tvPace = findViewById(R.id.tvPace)
        tvLap = findViewById(R.id.tvLap)
        seekJitter = findViewById(R.id.seekJitter)
        tvJitter = findViewById(R.id.tvJitter)
        seekDrift = findViewById(R.id.seekDrift)
        tvDrift = findViewById(R.id.tvDrift)
        switchLaneChange = findViewById(R.id.switchLaneChange)
        switchWalk = findViewById(R.id.switchWalk)
        spinnerTarget = findViewById(R.id.spinnerTarget)
        switchAuto = findViewById(R.id.switchAuto)
        btnStart = findViewById(R.id.btnStart)
        tvVpnHint = findViewById(R.id.tvVpnHint)
        switchVpnQuick = findViewById(R.id.switchVpnQuick)
        findViewById<MaterialButton>(R.id.btnVpn).setOnClickListener {
            startActivity(Intent(this, VpnActivity::class.java))
        }
        switchVpnQuick.setOnCheckedChangeListener { _, checked ->
            if (syncingVpnSwitch) return@setOnCheckedChangeListener
            if (checked) connectVpnQuick() else OpenVpnService.disconnect(this)
        }

        loadStadiums()
        setupPace()
        setupJitter()
        setupDrift()
        setupTarget()

        switchLaneChange.isChecked = settings.allowLaneChange
        switchLaneChange.setOnCheckedChangeListener { _, checked -> settings.allowLaneChange = checked }

        switchWalk.isChecked = settings.allowWalk
        switchWalk.setOnCheckedChangeListener { _, checked -> settings.allowWalk = checked }

        switchAuto.isChecked = settings.autoSwitch
        switchAuto.setOnCheckedChangeListener { _, checked -> settings.autoSwitch = checked }

        btnStart.setOnClickListener { startRun() }
    }

    override fun onResume() {
        super.onResume()
        // 账号被切换/删除，或页面会话已经失效：回登录页重新认证。
        // 带 AUTO_LOGIN 标记，账号还留着可用密码时可以免输。
        val store = AccountStore(this)
        if (!CasSession.isSignedIn(this, store)) {
            // logged_in 仍为真但归属对不上 = cookie 属于上一个账号，先彻底清掉再登录，
            // 否则 CAS 会拿旧 TGT 把人静默换回刚删掉的那个账号。
            if (settings.loggedIn &&
                !CasSession.ownerMatches(CasSession.owner(this), store.active()?.username)
            ) {
                Log.i(TAG, "会话归属与当前账号不一致，清空会话")
                CasSession.signOut(this)
            }
            Log.i(TAG, "当前未登录，回到登录页")
            startActivity(LoginActivity.intentForAutoLogin(this))
            finish()
            return
        }
        VpnState.addListener(vpnStateListener)
        renderVpn()
    }

    override fun onPause() {
        VpnState.removeListener(vpnStateListener)
        super.onPause()
    }

    override fun onDestroy() {
        if (::vpnGate.isInitialized) vpnGate.cancel()
        super.onDestroy()
    }

    private fun renderVpn() {
        val phase = VpnState.phase
        syncingVpnSwitch = true
        switchVpnQuick.isChecked = phase == VpnPhase.CONNECTED
        // 连接过程中不让来回拨：断开用 VPN 页的按钮更明确
        switchVpnQuick.isEnabled = phase == VpnPhase.CONNECTED ||
            phase == VpnPhase.IDLE || phase == VpnPhase.ERROR
        syncingVpnSwitch = false

        tvVpnHint.text = when (phase) {
            VpnPhase.CONNECTED -> getString(R.string.vpn_entry_connected)
            VpnPhase.IDLE, VpnPhase.ERROR -> getString(R.string.vpn_entry_hint)
            else -> getString(R.string.vpn_entry_connecting)
        }
    }

    /** 主页面直接连 VPN：凭据取设置里的指定账号或当前账号，没有就弹一次输入框 */
    private fun connectVpnQuick() {
        val credential = VpnAccountStore.resolve(this)
        if (credential == null) {
            VpnAccountDialog.show(
                this,
                prefillUsername = VpnAccountStore.preferredUsername(this),
                onCancelled = { renderVpn() },
            ) { username, password ->
                connectVpnQuick(username, password)
            }
            return
        }
        connectVpnQuick(credential.username, credential.password)
    }

    private fun connectVpnQuick(username: String, password: String) {
        tvVpnHint.text = getString(R.string.vpn_entry_connecting)
        vpnGate.ensureConnected(username, password) { result ->
            if (isFinishing || isDestroyed) return@ensureConnected
            if (result is VpnGate.Result.Failed) {
                Toast.makeText(
                    this,
                    getString(R.string.login_vpn_failed, result.reason),
                    Toast.LENGTH_LONG
                ).show()
            }
            renderVpn()
        }
    }

    private fun loadStadiums() {
        stadiums = runCatching { repo.stadiums() }.getOrElse {
            Toast.makeText(this, getString(R.string.template_missing, it.message ?: ""), Toast.LENGTH_LONG).show()
            emptyList()
        }
        spinnerStadium.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            stadiums.map { it.name }
        )
        val savedIndex = stadiums.indexOfFirst { it.id == settings.stadiumId }.coerceAtLeast(0)
        // 模板 id 变更后旧偏好可能指向已不存在的场地，回落到第一个可用项
        if (stadiums.isNotEmpty() && stadiums[savedIndex].id != settings.stadiumId) {
            settings.stadiumId = stadiums[savedIndex].id
        }
        spinnerStadium.setSelection(savedIndex, false)
        spinnerStadium.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position in stadiums.indices) {
                    settings.stadiumId = stadiums[position].id
                    bindLanes(stadiums[position])
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        if (stadiums.isNotEmpty()) bindLanes(stadiums[savedIndex])
    }

    private fun bindLanes(stadium: Stadium) {
        currentStadium = stadium
        val lanes = stadium.lanes.ifEmpty {
            (1..stadium.laneCount).map { LaneInfo(it, 0.0) }
        }
        spinnerLane.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            lanes.map { String.format(Locale.US, "%d 道 · %.0f m", it.index, it.lengthM) }
        )
        val position = lanes.indices.minByOrNull { abs(lanes[it].index - settings.laneIndex) } ?: 0
        spinnerLane.setSelection(position, false)
        currentLane = lanes[position]
        settings.laneIndex = lanes[position].index
        spinnerLane.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, pos: Int, id: Long) {
                if (pos in lanes.indices) {
                    currentLane = lanes[pos]
                    settings.laneIndex = lanes[pos].index
                    updatePaceLabels()
                }
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        updatePaceLabels()
    }

    private fun setupPace() {
        val steps = (AppSettings.MAX_PACE - AppSettings.MIN_PACE) / PACE_STEP
        seekPace.max = steps
        seekPace.progress = ((settings.paceSecPerKm - AppSettings.MIN_PACE) / PACE_STEP).coerceIn(0, steps)
        seekPace.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                settings.paceSecPerKm = AppSettings.MIN_PACE + progress * PACE_STEP
                updatePaceLabels()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        updatePaceLabels()
    }

    private fun setupJitter() {
        seekJitter.max = 15
        seekJitter.progress = settings.speedJitterPct
        tvJitter.text = getString(R.string.jitter_value, settings.speedJitterPct)
        seekJitter.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                settings.speedJitterPct = progress
                tvJitter.text = getString(R.string.jitter_value, progress)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun setupDrift() {
        val steps = (AppSettings.MAX_DRIFT_CM - AppSettings.MIN_DRIFT_CM) / AppSettings.DRIFT_STEP_CM
        seekDrift.max = steps
        seekDrift.progress = (settings.driftCm - AppSettings.MIN_DRIFT_CM) / AppSettings.DRIFT_STEP_CM
        tvDrift.text = getString(R.string.drift_value, settings.driftCm / 100.0)
        seekDrift.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                settings.driftCm = AppSettings.MIN_DRIFT_CM + progress * AppSettings.DRIFT_STEP_CM
                tvDrift.text = getString(R.string.drift_value, settings.driftCm / 100.0)
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
    }

    private fun setupTarget() {
        val labels = targets.map { String.format(Locale.US, "%.0f km", it) }
        spinnerTarget.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, labels)
        val index = targets.indexOfFirst { abs(it - settings.targetKm) < 0.01f }.coerceAtLeast(0)
        spinnerTarget.setSelection(index, false)
        spinnerTarget.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                if (position in targets.indices) settings.targetKm = targets[position]
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    private fun updatePaceLabels() {
        val pace = settings.paceSecPerKm
        tvPace.text = Format.pace(pace)
        val lane = currentLane
        if (lane != null && lane.lengthM > 0) {
            val lapSec = (lane.lengthM / 1000.0 * pace).toInt()
            tvLap.text = getString(R.string.main_lap_hint, Format.pace(lapSec), lane.lengthM)
        } else {
            tvLap.text = ""
        }
    }

    private fun startRun() {
        if (stadiums.isEmpty()) {
            Toast.makeText(this, R.string.template_missing_no_stadium, Toast.LENGTH_LONG).show()
            return
        }
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val needsNotification = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        if ((fine || coarse) && !needsNotification) launchRun() else {
            val permissions = mutableListOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            // 前台保活服务的通知在 Android 13+ 需要单独授权；拒绝了也照样能跑，只是看不到通知
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions += Manifest.permission.POST_NOTIFICATIONS
            }
            permissionLauncher.launch(permissions.toTypedArray())
        }
    }

    private fun launchRun() {
        val stadium = currentStadium ?: stadiums.first()
        val lane = currentLane ?: stadium.lanes.first()
        startActivity(
            Intent(this, RunActivity::class.java)
                .putExtra(RunActivity.EXTRA_STADIUM_ID, stadium.id)
                .putExtra(RunActivity.EXTRA_LANE, lane.index)
                .putExtra(RunActivity.EXTRA_PACE, settings.paceSecPerKm)
                .putExtra(RunActivity.EXTRA_JITTER, settings.speedJitterPct)
                .putExtra(RunActivity.EXTRA_DRIFT_CM, settings.driftCm)
                .putExtra(RunActivity.EXTRA_LANE_CHANGE, settings.allowLaneChange)
                .putExtra(RunActivity.EXTRA_WALK, settings.allowWalk)
                .putExtra(RunActivity.EXTRA_TARGET_KM, settings.targetKm)
                .putExtra(RunActivity.EXTRA_AUTO_SWITCH, settings.autoSwitch)
                .putExtra(RunActivity.EXTRA_ENTRY_URL, settings.entryUrl)
        )
    }

    private companion object {
        const val TAG = "MainActivity"
        const val PACE_STEP = 15
    }
}
