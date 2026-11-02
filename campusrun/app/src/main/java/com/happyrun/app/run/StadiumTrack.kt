package com.happyrun.app.run

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow

/**
 * 体育场几何：等角度采样的内、外两条边界（田径场第 1 道与第 N 道）。
 *
 * 中间各道由两条边界按比例线性插值得到，所以任意角度都能取到任意道次的位置；
 * 换道时只需要改变 lanePos，沿赛道方向不会跳动。坐标全程 WGS84。
 */
class StadiumTrack(
    val venueId: String,
    val venueName: String,
    val laneCount: Int,
    val stepDeg: Double,
    val center: GeoPoint,
    val inner: List<GeoPoint>,
    val outer: List<GeoPoint>,
    private val laneLengthsM: Map<Int, Double>
) {
    init {
        require(laneCount >= 2) { "道数太少：$laneCount" }
        require(inner.size == outer.size && inner.size >= 36) { "边界采样点异常" }
    }

    data class PlanePoint(val x: Double, val y: Double)

    val sampleCount: Int = inner.size
    val startIndex: Double = 0.0

    private val mPerDegLat = 110_540.0
    private val mPerDegLon = 111_320.0 * cos(Math.toRadians(center.lat))

    private val ix = DoubleArray(sampleCount) { (inner[it].lon - center.lon) * mPerDegLon }
    private val iy = DoubleArray(sampleCount) { (inner[it].lat - center.lat) * mPerDegLat }
    private val ox = DoubleArray(sampleCount) { (outer[it].lon - center.lon) * mPerDegLon }
    private val oy = DoubleArray(sampleCount) { (outer[it].lat - center.lat) * mPerDegLat }

    /** 相邻两道之间的平均间距（米），用于把横向漂移从米换算成道次。 */
    val laneWidthM: Double = run {
        var sum = 0.0
        for (i in 0 until sampleCount) sum += hypot(ox[i] - ix[i], oy[i] - iy[i])
        (sum / sampleCount) / (laneCount - 1)
    }

    fun laneLengthM(lane: Int): Double =
        laneLengthsM[lane] ?: laneLengthsM.values.firstOrNull() ?: 0.0

    fun toPlane(lat: Double, lon: Double): PlanePoint =
        PlanePoint((lon - center.lon) * mPerDegLon, (lat - center.lat) * mPerDegLat)

    fun toGeo(x: Double, y: Double): GeoPoint =
        GeoPoint(center.lat + y / mPerDegLat, center.lon + x / mPerDegLon)

    private fun laneFraction(lanePos: Double): Double =
        ((lanePos - 1.0) / (laneCount - 1).toDouble()).coerceIn(0.0, 1.0)

    private fun wrap(index: Double): Double {
        var v = index % sampleCount
        if (v < 0) v += sampleCount
        return v
    }

    private fun planeAt(index: Double, lanePos: Double): PlanePoint {
        val i = wrap(index)
        val i0 = floor(i).toInt()
        val i1 = (i0 + 1) % sampleCount
        val t = i - i0
        val f = laneFraction(lanePos)
        val xin = ix[i0] + (ix[i1] - ix[i0]) * t
        val yin = iy[i0] + (iy[i1] - iy[i0]) * t
        val xout = ox[i0] + (ox[i1] - ox[i0]) * t
        val yout = oy[i0] + (oy[i1] - oy[i0]) * t
        return PlanePoint(xin + (xout - xin) * f, yin + (yout - yin) * f)
    }

    fun pointAt(index: Double, lanePos: Double): GeoPoint {
        val p = planeAt(index, lanePos)
        return toGeo(p.x, p.y)
    }

    /** 沿赛道走 1 个采样索引对应的米数（等角度采样下，直道和弯道不同）。 */
    fun metersPerIndex(index: Double, lanePos: Double): Double {
        val a = planeAt(index - 0.25, lanePos)
        val b = planeAt(index + 0.25, lanePos)
        return hypot(b.x - a.x, b.y - a.y) / 0.5
    }

    fun bearingAt(index: Double, lanePos: Double): Double {
        val a = planeAt(index - 0.25, lanePos)
        val b = planeAt(index + 0.25, lanePos)
        val deg = Math.toDegrees(atan2(b.x - a.x, b.y - a.y))
        return (deg + 360.0) % 360.0
    }

    /** 把任意坐标投影到指定道次，返回采样索引（0..sampleCount）。 */
    fun nearestIndex(lat: Double, lon: Double, lanePos: Double): Double {
        val p = toPlane(lat, lon)
        var best = Double.MAX_VALUE
        var bestIndex = 0.0
        for (i in 0 until sampleCount) {
            val q = planeAt(i.toDouble(), lanePos)
            val d = (q.x - p.x).pow(2) + (q.y - p.y).pow(2)
            if (d < best) {
                best = d
                bestIndex = i.toDouble()
            }
        }
        var refinedIndex = bestIndex
        for (step in doubleArrayOf(-0.75, -0.5, -0.25, 0.25, 0.5, 0.75)) {
            val idx = wrap(bestIndex + step)
            val q = planeAt(idx, lanePos)
            val d = (q.x - p.x).pow(2) + (q.y - p.y).pow(2)
            if (d < best) {
                best = d
                refinedIndex = idx
            }
        }
        return refinedIndex
    }
}
