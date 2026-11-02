package com.happyrun.app.run

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

class StadiumTrackTest {

    private fun circleTrack(
        innerRadiusM: Double = 60.0,
        widthM: Double = 8.6,
        laneCount: Int = 8,
        n: Int = 720
    ): StadiumTrack {
        val center = GeoPoint(41.765, 123.4167)
        val mPerDegLat = 110_540.0
        val mPerDegLon = 111_320.0 * cos(Math.toRadians(center.lat))
        fun ring(radius: Double) = (0 until n).map { i ->
            val th = 2 * PI * i / n
            GeoPoint(
                lat = center.lat + radius * sin(th) / mPerDegLat,
                lon = center.lon + radius * cos(th) / mPerDegLon
            )
        }
        val lengths = (1..laneCount).associateWith { lane ->
            val r = innerRadiusM + widthM * (lane - 1) / (laneCount - 1)
            2 * PI * r
        }
        return StadiumTrack(
            venueId = "test",
            venueName = "测试圆",
            laneCount = laneCount,
            stepDeg = 360.0 / n,
            center = center,
            inner = ring(innerRadiusM),
            outer = ring(innerRadiusM + widthM),
            laneLengthsM = lengths
        )
    }

    @Test
    fun laneInterpolationHitsBothBoundaries() {
        val track = circleTrack()
        val atInner = track.pointAt(0.0, 1.0)
        val atOuter = track.pointAt(0.0, 8.0)
        assertClose(track.inner[0], atInner, 0.05)
        assertClose(track.outer[0], atOuter, 0.05)
    }

    @Test
    fun laneWidthIsConsistentWithBoundaries() {
        val track = circleTrack()
        // 内外半径相差 8.6 m，7 个道间距 → 每道约 1.23 m
        assertEquals(8.6 / 7.0, track.laneWidthM, 0.02)
    }

    @Test
    fun nearestIndexRecoversOriginalSample() {
        val track = circleTrack()
        val index = 123.0
        val p = track.pointAt(index, 6.0)
        val found = track.nearestIndex(p.lat, p.lon, 6.0)
        assertEquals(index, found, 1.0)
    }

    @Test
    fun metersPerIndexMatchesCircumference() {
        val track = circleTrack()
        val perIndex = track.metersPerIndex(0.0, 1.0)
        assertEquals(2 * PI * 60.0 / 720.0, perIndex, 0.02)
    }

    @Test
    fun runAdvancesAtTargetPace() {
        val track = circleTrack()
        val session = RunSession(
            track = track,
            paceSecPerKm = 360.0,
            selectedLane = 6,
            speedJitterPct = 0.0,
            driftM = 0.0,
            allowLaneChange = false,
            allowWalk = false,
            fatigue = 0.0,
            seed = 1L
        )
        session.beginSimulation(0L)
        var t = 0L
        var prev = session.currentFix(t)
        var walked = 0.0
        repeat(60) {
            t += 1000L
            val fix = session.currentFix(t)
            walked += approxMeters(prev, fix)
            prev = fix
        }
        // 6'00"/km → 2.7778 m/s，60 秒约 166.7 m
        assertEquals(60 * 1000.0 / 360.0, walked, 4.0)
        assertEquals(60 * 1000.0 / 360.0, session.simulatedDistance(), 4.0)
    }

    @Test
    fun speedJitterKeepsAveragePace() {
        val track = circleTrack()
        val session = RunSession(
            track = track,
            paceSecPerKm = 360.0,
            selectedLane = 6,
            speedJitterPct = 0.10,
            driftM = 0.0,
            allowLaneChange = false,
            allowWalk = false,
            fatigue = 0.0,
            seed = 7L
        )
        session.beginSimulation(0L)
        var prev = session.currentFix(0L)
        var walked = 0.0
        for (i in 1..600) {
            val fix = session.currentFix(i * 1000L)
            walked += approxMeters(prev, fix)
            prev = fix
        }
        val expected = 600 * 1000.0 / 360.0
        assertEquals(expected, walked, expected * 0.03)
    }

    @Test
    fun laneNeverLeavesAllowedRange() {
        val track = circleTrack()
        val session = RunSession(
            track = track,
            paceSecPerKm = 360.0,
            selectedLane = 6,
            speedJitterPct = 0.05,
            driftM = 0.5,
            allowLaneChange = true,
            allowWalk = false,
            fatigue = 0.0,
            seed = 11L
        )
        session.beginSimulation(0L)
        var min = Double.MAX_VALUE
        var max = -Double.MAX_VALUE
        for (i in 1..600) {
            session.currentFix(i * 1000L)
            min = minOf(min, session.lanePosition)
            max = maxOf(max, session.lanePosition)
        }
        assertTrue("lanePosition 越界：$min", min >= 1.0)
        assertTrue("不应外扩到选定道之外太多：$max", max <= 6.5)
        assertTrue("应该发生过换道：$min", min < 5.9)
    }

    @Test
    fun preludeStartsAtRealPositionThenJoinsTrack() {
        val track = circleTrack()
        val session = RunSession(
            track = track,
            paceSecPerKm = 360.0,
            selectedLane = 6,
            speedJitterPct = 0.0,
            driftM = 0.0,
            allowLaneChange = false,
            allowWalk = false,
            fatigue = 0.0,
            seed = 3L
        )
        val onTrack = track.pointAt(90.0, 6.0)
        // 把真实点放到跑道外 5 m
        val anchor = GeoPoint(onTrack.lat + 5.0 / 110_540.0, onTrack.lon)
        session.onRealFix(GeoFix(anchor.lat, anchor.lon, accuracy = 5f))
        assertTrue(session.beginSimulation(0L))

        val first = session.currentFix(0L)
        assertEquals(0.0, approxMeters(anchor, first), 1.0)

        val joined = session.currentFix(6_000L)
        val idx = track.nearestIndex(joined.lat, joined.lon, 6.0)
        val nearest = track.pointAt(idx, 6.0)
        assertTrue("切入后应回到跑道附近", approxMeters(joined, nearest) < 2.0)
    }

    @Test
    fun farAnchorSnapsWithoutPrelude() {
        val track = circleTrack()
        val session = RunSession(
            track = track,
            paceSecPerKm = 360.0,
            selectedLane = 6,
            speedJitterPct = 0.0,
            driftM = 0.0,
            allowLaneChange = false,
            allowWalk = false,
            fatigue = 0.0,
            seed = 5L
        )
        val onTrack = track.pointAt(10.0, 6.0)
        val far = GeoPoint(onTrack.lat + 200.0 / 110_540.0, onTrack.lon)
        session.onRealFix(GeoFix(far.lat, far.lon, accuracy = 5f))
        assertTrue(!session.beginSimulation(0L))
        assertTrue("offTrack=${session.offTrackM}", session.offTrackM > 100.0)

        val fix = session.currentFix(1_000L)
        val idx = track.nearestIndex(fix.lat, fix.lon, 6.0)
        assertTrue(approxMeters(fix, track.pointAt(idx, 6.0)) < 2.0)
    }

    private fun loadTemplate(venue: String): Pair<StadiumTrack, Map<Int, Double>>? {
        val file = File("../templates/$venue/geometry.json")
        if (!file.exists()) return null  // 在别的目录跑测试时不强依赖仓库布局
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
        return track to lengths
    }

    /** 几何实际走一圈，应该和 metadata 里的周长一致。 */
    private fun assertWalkMatchesMetadata(track: StadiumTrack, lengths: Map<Int, Double>, lane: Int) {
        var walked = 0.0
        for (i in 0 until track.sampleCount) {
            walked += approxMeters(
                track.pointAt(i.toDouble(), lane.toDouble()),
                track.pointAt(i + 1.0, lane.toDouble())
            )
        }
        assertEquals(lengths[lane]!!, walked, 2.0)
    }

    @Test
    fun realGeometryLoadsAndMatchesLaneLengths() {
        val (track, lengths) = loadTemplate("venue-b") ?: return
        assertEquals(403.98, track.laneLengthM(1), 0.5)
        assertEquals(458.01, track.laneLengthM(8), 0.5)
        assertWalkMatchesMetadata(track, lengths, 8)
    }

    @Test
    fun venueAGeometryLoadsAndMatchesLaneLengths() {
        val (track, lengths) = loadTemplate("venue-a") ?: return
        assertEquals(401.49, track.laneLengthM(1), 0.5)
        assertEquals(454.17, track.laneLengthM(8), 0.5)
        assertWalkMatchesMetadata(track, lengths, 8)
        assertWalkMatchesMetadata(track, lengths, 1)
    }

    @Test
    fun walkEpisodesAppearButAveragePaceHolds() {
        val track = circleTrack()
        val session = RunSession(
            track = track,
            paceSecPerKm = 240.0,
            selectedLane = 6,
            speedJitterPct = 0.15,
            driftM = 1.0,
            allowLaneChange = true,
            targetDistanceM = 5_000.0,
            allowWalk = true,
            fatigue = 0.06,
            seed = 9L
        )
        session.beginSimulation(0L)
        var minSpeed = Double.MAX_VALUE
        for (i in 1..1200) {
            session.currentFix(i * 1000L)
            minSpeed = minOf(minSpeed, session.currentSpeed)
        }
        // 走一段会拖慢瞬时速度，但配速闭环应把总距离拉回目标附近
        assertEquals(5_000.0, session.simulatedDistance(), 450.0)
        assertTrue("应该出现过走一段（最低速度 $minSpeed m/s）", minSpeed < 2.2)
    }

    private fun assertClose(expected: GeoPoint, actual: GeoPoint, toleranceM: Double) {
        assertTrue(
            "期望 $expected 实际 $actual",
            approxMeters(expected, actual) <= toleranceM
        )
    }

    private fun approxMeters(a: GeoPoint, b: GeoPoint): Double = approxMeters(a.lat, a.lon, b.lat, b.lon)

    private fun approxMeters(a: GeoFix, b: GeoFix): Double = approxMeters(a.lat, a.lon, b.lat, b.lon)

    private fun approxMeters(a: GeoPoint, b: GeoFix): Double = approxMeters(a.lat, a.lon, b.lat, b.lon)

    private fun approxMeters(a: GeoFix, b: GeoPoint): Double = approxMeters(a.lat, a.lon, b.lat, b.lon)

    private fun approxMeters(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Double {
        val dy = (bLat - aLat) * 110_540.0
        val dx = (bLon - aLon) * 111_320.0 * cos(Math.toRadians((aLat + bLat) / 2))
        return hypot(dx, dy)
    }
}
