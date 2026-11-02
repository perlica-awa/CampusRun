package com.happyrun.app.run

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.io.File

/**
 * 临时脚本：用真实几何跑一段模拟并落盘 CSV，便于离线检查速度曲线与路径。
 * 平时跳过；需要时：
 *   cd campusrun && SIM_DUMP=1 ./gradlew :app:testDebugUnitTest --tests "*SimDumpTest*"
 *
 * 可选环境变量：SIM_STADIUM（默认 venue-b）、SIM_LANE（默认 6）、
 * SIM_JITTER（默认 0.15）、SIM_DRIFT（默认 1.0）、SIM_TARGET_KM（默认 5）、
 * SIM_LANE_CHANGE=0 / SIM_WALK=0 可关掉换道和走段。
 */
class SimDumpTest {

    @Test
    fun dump() {
        if (System.getenv("SIM_DUMP") != "1") return
        val stadium = System.getenv("SIM_STADIUM") ?: "venue-b"
        val file = File("../templates/$stadium/geometry.json")
        if (!file.exists()) return
        val o = JSONObject(file.readText())
        val center = o.getJSONObject("center")
        fun ring(array: JSONArray) = (0 until array.length()).map {
            val p = array.getJSONArray(it)
            GeoPoint(p.getDouble(0), p.getDouble(1))
        }
        val lengths = mutableMapOf<Int, Double>()
        val lanes = o.getJSONArray("lanes")
        for (i in 0 until lanes.length()) {
            val lane = lanes.getJSONObject(i)
            lengths[lane.getInt("index")] = lane.getDouble("length_m")
        }
        val track = StadiumTrack(
            venueId = o.getString("venue_id"),
            venueName = o.getString("venue_name"),
            laneCount = o.getInt("lane_count"),
            stepDeg = o.getDouble("step_deg"),
            center = GeoPoint(center.getDouble("lat"), center.getDouble("lon")),
            inner = ring(o.getJSONArray("inner")),
            outer = ring(o.getJSONArray("outer")),
            laneLengthsM = lengths
        )

        val pace = System.getenv("SIM_PACE")?.toDoubleOrNull() ?: 240.0
        val seconds = System.getenv("SIM_SECONDS")?.toIntOrNull() ?: 1200
        val outName = System.getenv("SIM_OUT") ?: "run.csv"
        val lane = System.getenv("SIM_LANE")?.toIntOrNull() ?: 6
        val jitter = System.getenv("SIM_JITTER")?.toDoubleOrNull() ?: 0.15
        val drift = System.getenv("SIM_DRIFT")?.toDoubleOrNull() ?: 1.0
        val targetKm = System.getenv("SIM_TARGET_KM")?.toDoubleOrNull() ?: 5.0
        val allowLaneChange = System.getenv("SIM_LANE_CHANGE") != "0"
        val allowWalk = System.getenv("SIM_WALK") != "0"
        val session = RunSession(
            track = track,
            paceSecPerKm = pace,
            selectedLane = lane,
            speedJitterPct = jitter,
            driftM = drift,
            allowLaneChange = allowLaneChange,
            targetDistanceM = targetKm * 1000.0,
            allowWalk = allowWalk,
            seed = 42L
        )
        // 真实点放在选定道外侧 6 m，演示平滑切入
        val onTrack = track.pointAt(0.0, lane.toDouble())
        val anchor = GeoPoint(onTrack.lat + 6.0 / 110_540.0, onTrack.lon)
        session.onRealFix(GeoFix(anchor.lat, anchor.lon, accuracy = 5f))
        session.beginSimulation(0L)

        val out = File("build/sim/$outName")
        out.parentFile?.mkdirs()
        out.bufferedWriter().use { w ->
            w.write("t,speed,lane,lat,lon,distance,walk\n")
            for (i in 0..seconds) {
                val fix = session.currentFix(i * 1000L)
                w.write(
                    "$i,${session.currentSpeed},${session.lanePosition}," +
                        "${fix.lat},${fix.lon},${session.simulatedDistance()},${session.walkBlend}\n"
                )
            }
        }
    }
}
