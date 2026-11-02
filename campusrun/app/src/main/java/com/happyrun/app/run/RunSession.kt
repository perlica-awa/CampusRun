package com.happyrun.app.run

import java.util.Random
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * 一次跑步的模拟状态。
 *
 * - 真实阶段只记录最后一个 GPS 点；
 * - 切到模拟后用该点在选定道次上的投影做“平滑切入”（沿一条轻微弯曲的线跑到跑道上），
 *   切入距离过长（>80 m）时直接对齐；
 * - 之后按跑道几何前进。速度不是一个确定函数，而是：
 *     1. Ornstein-Uhlenbeck 均值回归随机过程（对数速度），不会像正弦叠加那样周期性重复；
 *     2. 疲劳项：从 +fatigue/2 线性衰减到 -fatigue/2，越跑越慢；
 *     3. 配速闭环：累计距离落后/超前计划时，小幅修正目标速度，保证平均配速不漂；
 *     4. 随机走跑片段：跑了一段后偶尔降速走 12–26 秒（1.70–1.85 m/s，仍在 10'00"/km 内）。
 *   横向同样是 OU 随机游走 + 偶尔换到内侧相邻道。
 *
 * 所有位置保持 WGS84，页面自己转 GCJ-02。
 */
class RunSession(
    val track: StadiumTrack,
    val paceSecPerKm: Double,
    selectedLane: Int,
    /** 速度波动幅度，0.15 表示对数速度的标准差约为 7.5%，大多数时间落在 ±15% 内。 */
    private val speedJitterPct: Double,
    /** 横向漂移幅度（米），OU 过程的约 2σ 范围。 */
    private val driftM: Double,
    private val allowLaneChange: Boolean,
    /** 目标里程（米），用于疲劳进度与配速闭环。 */
    private val targetDistanceM: Double = 5_000.0,
    /** 是否允许随机走一段。 */
    private val allowWalk: Boolean = true,
    /** 疲劳幅度：从 +fatigue/2 衰减到 -fatigue/2。 */
    private val fatigue: Double = 0.06,
    seed: Long = System.currentTimeMillis()
) {
    val baseSpeed: Double = 1000.0 / paceSecPerKm
    val laneCount: Int = track.laneCount
    val lane: Int = selectedLane.coerceIn(1, laneCount)

    @Volatile
    var isSimulating: Boolean = false
        private set

    @Volatile
    var lastRealFix: GeoFix? = null
        private set

    /** 切入时真实位置离跑道的距离（米）。 */
    @Volatile
    var offTrackM: Double = 0.0
        private set

    @Volatile
    var lanePosition: Double = lane.toDouble()
        private set

    @Volatile
    var currentSpeed: Double = baseSpeed
        private set

    private val rnd = Random(seed)

    // OU 过程的离散更新：x += -θ·x·dt + σ·√dt·N(0,1)，稳态标准差 = σ/√(2θ)
    private val speedTheta = 1.0 / 75.0
    private val speedSigma = (speedJitterPct / 2.0) * sqrt(2 * speedTheta)
    private val driftTheta = 1.0 / 90.0
    private val driftSigma = (driftM / 2.0) * sqrt(2 * driftTheta)

    private var speedDev: Double = 0.0
    private var driftPos: Double = 0.0
    private var accuracy: Double = 5.0

    /** 走段在整段时间里的占比：4'00" 约 3%，6'00" 约 19%，8'00" 约 35%。 */
    private val walkFraction: Double = if (!allowWalk) 0.0 else {
        (0.03 + (paceSecPerKm - 240.0) / 240.0 * 0.32).coerceIn(0.0, 0.35)
    }

    /** 走、跑按占比折算出的整体期望速度，仅用于抑制随机漂移。 */
    private val effectiveSpeed: Double = if (walkFraction <= 0.0) {
        baseSpeed
    } else {
        1.0 / (walkFraction / NOMINAL_WALK_SPEED + (1.0 - walkFraction) / baseSpeed)
    }

    private var walkPhase: Double = 0.0

    /** 0 = 跑，1 = 走；中间的过渡段在 0..1 之间。 */
    @Volatile
    var walkBlend: Double = 0.0
        private set
    private var walkCycleMs: Long = 300_000L
    private var walkRampS: Double = 6.0
    private var walkSpeed: Double = 1.8
    private var walkUntilMs: Long = 0L
    private var nextWalkAtMs: Long = Long.MAX_VALUE

    private var phase: Double = 0.0
    private var distanceM: Double = 0.0
    private var startAtMs: Long = 0L
    private var lastTickMs: Long = 0L
    private var prelude: Prelude? = null

    private var laneFrom: Double = lane.toDouble()
    private var laneTo: Double = lane.toDouble()
    private var laneMoveStartMs: Long = -1L
    private var laneMoveDurationMs: Long = 3_000L
    private var nextLaneChangeMs: Long = 0L

    fun onRealFix(fix: GeoFix) {
        if (!isSimulating) lastRealFix = fix
    }

    /**
     * 进入模拟。返回 true 表示已经平滑对齐到真实位置；false 表示没有真实定位或离得太远，
     * 直接从跑道最近点开始（会跳一次）。
     */
    @Synchronized
    fun beginSimulation(nowMs: Long = System.currentTimeMillis()): Boolean {
        if (isSimulating) return true
        startAtMs = nowMs
        lastTickMs = nowMs
        laneFrom = lane.toDouble()
        laneTo = laneFrom
        lanePosition = laneFrom
        nextLaneChangeMs = nowMs + randomLaneInterval()
        nextWalkAtMs = nowMs + 60_000L + rnd.nextInt(90_000)

        val anchor = lastRealFix
        if (anchor == null) {
            phase = 0.0
            isSimulating = true
            return false
        }

        val entryIndex = track.nearestIndex(anchor.lat, anchor.lon, lanePosition)
        val entry = track.pointAt(entryIndex, lanePosition)
        val a = track.toPlane(anchor.lat, anchor.lon)
        val b = track.toPlane(entry.lat, entry.lon)
        val length = hypot(b.x - a.x, b.y - a.y)
        offTrackM = length

        isSimulating = true
        return when {
            length > MAX_PRELUDE_M -> {
                phase = entryIndex
                false
            }
            length < 0.8 -> {
                phase = entryIndex
                true
            }
            else -> {
                val bow = minOf(8.0, length * 0.25) * if (rnd.nextBoolean()) 1.0 else -1.0
                prelude = Prelude(a.x, a.y, b.x, b.y, entryIndex, length, bow)
                true
            }
        }
    }

    @Synchronized
    fun currentFix(nowMs: Long = System.currentTimeMillis()): GeoFix {
        if (!isSimulating) {
            val p = track.pointAt(phase, lanePosition)
            return GeoFix(p.lat, p.lon, speed = 0.0, timeMs = nowMs)
        }

        val dt = ((nowMs - lastTickMs) / 1000.0).coerceIn(0.0, 3.0)
        lastTickMs = nowMs
        val elapsed = (nowMs - startAtMs) / 1000.0

        updateStochastic(dt)
        updateWalk(nowMs, dt)
        val speed = speedNow(elapsed)
        currentSpeed = speed
        distanceM += speed * dt

        val lanePos = lanePositionAt(nowMs)
        lanePosition = lanePos

        val p = prelude
        if (p != null) {
            p.traveled += speed * dt
            val s = p.traveled / p.length
            if (s < 1.0) {
                val pt = p.point(s)
                val geo = track.toGeo(pt.x, pt.y)
                return GeoFix(
                    lat = geo.lat,
                    lon = geo.lon,
                    accuracy = accuracyAt(),
                    heading = p.heading(s),
                    speed = speed,
                    timeMs = nowMs
                )
            }
            prelude = null
            phase = p.entryIndex
        }

        val metersPerIndex = track.metersPerIndex(phase, lanePos).coerceAtLeast(0.01)
        phase = wrap(phase + speed * dt / metersPerIndex)

        val geo = track.pointAt(phase, lanePos)
        return GeoFix(
            lat = geo.lat,
            lon = geo.lon,
            accuracy = accuracyAt(),
            heading = track.bearingAt(phase, lanePos),
            speed = speed,
            timeMs = nowMs
        )
    }

    /** 模拟开始后实际走过的里程（米）。 */
    fun simulatedDistance(): Double = distanceM

    fun predictedLapSeconds(): Double = track.laneLengthM(lane) / 1000.0 * paceSecPerKm

    // ---- 速度 ----

    private fun speedNow(elapsed: Double): Double {
        val progress = (distanceM / targetDistanceM).coerceIn(0.0, 1.0)
        val fatigueFactor = 1.0 + fatigue / 2.0 - fatigue * progress

        // 目标配速是“跑段”的配速；走段是额外时间，所以整体平均会比目标慢一点。
        // 这里只做小幅闭环，抵消随机过程的长期漂移，不把走掉的时间强行追回来。
        val scheduleError = effectiveSpeed * elapsed - distanceM
        val correction = (scheduleError / 250.0).coerceIn(-0.08, 0.08)
        val nominal = baseSpeed * (1.0 + correction) * fatigueFactor
        val mixed = nominal * (1.0 - walkBlend) + walkSpeed * walkBlend
        // 走的时候随机幅度收窄，避免被 1.70 的下限削成一条平线
        val jitterScale = 1.0 - 0.75 * walkBlend
        // 平台只校验整段平均配速 3'00"–10'00"，过程速度不离谱即可：
        // 这里把单点速度限制在 1.30 m/s（12'49"/km）–5.45 m/s（3'03"/km）
        return (mixed * exp(speedDev * jitterScale)).coerceIn(1.30, 5.45)
    }

    private fun updateStochastic(dt: Double) {
        if (dt <= 0.0) return
        val sqrtDt = sqrt(dt)
        if (speedSigma > 0.0) {
            speedDev += -speedTheta * speedDev * dt + speedSigma * sqrtDt * rnd.nextGaussian()
            speedDev = speedDev.coerceIn(-0.30, 0.30)
        }
        if (driftSigma > 0.0) {
            driftPos += -driftTheta * driftPos * dt + driftSigma * sqrtDt * rnd.nextGaussian()
            driftPos = driftPos.coerceIn(-driftM, driftM)
        }
        accuracy = (accuracy + (rnd.nextDouble() - 0.5) * 0.6).coerceIn(3.0, 9.0)
    }

    /** 跑过 25% 里程之后，随机进入一次 12–26 秒的“走一段”。 */
    private fun updateWalk(nowMs: Long, dt: Double) {
        if (!allowWalk) {
            walkBlend = 0.0
            return
        }
        if (walkUntilMs > 0 && nowMs >= walkUntilMs) {
            walkUntilMs = 0L
            val runMs = (walkCycleMs * (1.0 - walkFraction)).toLong()
            nextWalkAtMs = nowMs + runMs.coerceAtLeast(30_000L)
        }
        val progress = distanceM / targetDistanceM
        if (walkUntilMs == 0L && progress > 0.15 && nowMs >= nextWalkAtMs) {
            walkCycleMs = 240_000L + rnd.nextInt(180_000)  // 4–7 分钟一个完整周期
            val targetDuration = (walkCycleMs * walkFraction * (0.8 + rnd.nextDouble() * 0.4)).toLong()
            // 至少 15 秒，保证有真正的“走路平台”，而不是刚降速就被拉回跑
            val duration = targetDuration.coerceIn(15_000L, 180_000L)
            if (duration > targetDuration) {
                walkCycleMs = (duration / walkFraction).toLong()  // 拉长间隔，维持原有走段占比
            }
            walkUntilMs = nowMs + duration
            val durationSec = duration / 1000.0
            walkRampS = (durationSec * 0.25).coerceIn(2.5, 8.0)
            walkSpeed = 1.45 + rnd.nextDouble() * 0.20
        }
        val target = if (walkUntilMs > 0) 1.0 else 0.0
        val step = dt / walkRampS
        walkPhase = if (walkPhase < target) {
            minOf(target, walkPhase + step)
        } else {
            maxOf(target, walkPhase - step)
        }
        walkBlend = walkPhase * walkPhase * (3 - 2 * walkPhase)  // S 曲线，两端都不突兀
    }

    // ---- 横向 ----

    private fun lanePositionAt(nowMs: Long): Double {
        if (allowLaneChange && laneMoveStartMs < 0 && nowMs >= nextLaneChangeMs) {
            startLaneChange(nowMs)
        }
        var base = laneFrom
        if (laneMoveStartMs >= 0) {
            val s = ((nowMs - laneMoveStartMs).toDouble() / laneMoveDurationMs).coerceIn(0.0, 1.0)
            val eased = s * s * (3 - 2 * s)
            base = laneFrom + (laneTo - laneFrom) * eased
            if (s >= 1.0) {
                laneFrom = laneTo
                laneMoveStartMs = -1L
                nextLaneChangeMs = nowMs + randomLaneInterval()
            }
        }
        // 漂移按道次叠加；上限 0.85 道（约 1 m），再大就会和相邻道重叠
        val driftLanes = (driftPos / track.laneWidthM).coerceIn(-0.85, 0.85)
        return (base + driftLanes).coerceIn(1.0, laneCount.toDouble())
    }

    /** 只在选定道次与内侧相邻道之间来回换，绝不向围栏方向外扩。 */
    private fun startLaneChange(nowMs: Long) {
        val inward = (lane - 1).coerceAtLeast(1).toDouble()
        val target = when {
            laneFrom > inward && rnd.nextDouble() < 0.65 -> laneFrom - 1
            else -> lane.toDouble()
        }
        if (target == laneFrom) {
            nextLaneChangeMs = nowMs + randomLaneInterval()
            return
        }
        laneTo = target
        laneMoveStartMs = nowMs
        laneMoveDurationMs = 2_500L + rnd.nextInt(1_500)
    }

    private fun randomLaneInterval(): Long = 120_000L + rnd.nextInt(180_000)

    private fun accuracyAt(): Float = accuracy.toFloat()

    private fun wrap(index: Double): Double {
        val n = track.sampleCount
        var v = index % n
        if (v < 0) v += n
        return v
    }

    /** 切入段：从真实位置到跑道最近点的一条轻微弯曲的二次贝塞尔曲线。 */
    private class Prelude(
        private val ax: Double,
        private val ay: Double,
        private val bx: Double,
        private val by: Double,
        val entryIndex: Double,
        val length: Double,
        bow: Double
    ) {
        var traveled: Double = 0.0

        private val cx: Double
        private val cy: Double

        init {
            val dx = bx - ax
            val dy = by - ay
            val n = hypot(dx, dy).coerceAtLeast(1e-6)
            cx = (ax + bx) / 2 - dy / n * bow
            cy = (ay + by) / 2 + dx / n * bow
        }

        fun point(s: Double): StadiumTrack.PlanePoint {
            val t = s.coerceIn(0.0, 1.0)
            val u = 1 - t
            return StadiumTrack.PlanePoint(
                u * u * ax + 2 * u * t * cx + t * t * bx,
                u * u * ay + 2 * u * t * cy + t * t * by
            )
        }

        fun heading(s: Double): Double {
            val t = s.coerceIn(0.0, 0.999)
            val p0 = point(t)
            val p1 = point(t + 0.001)
            val deg = Math.toDegrees(kotlin.math.atan2(p1.x - p0.x, p1.y - p0.y))
            return (deg + 360.0) % 360.0
        }
    }

    private companion object {
        const val MAX_PRELUDE_M = 80.0
        const val NOMINAL_WALK_SPEED = 1.55
    }
}
