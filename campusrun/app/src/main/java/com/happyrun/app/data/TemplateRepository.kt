package com.happyrun.app.data

import android.content.Context
import com.happyrun.app.run.GeoPoint
import com.happyrun.app.run.StadiumTrack
import org.json.JSONArray
import org.json.JSONObject

data class LaneInfo(val index: Int, val lengthM: Double) {
    val label: String get() = "$index 道"
}

data class Stadium(
    val id: String,
    val name: String,
    val dir: String,
    val file: String,
    val laneCount: Int,
    val lanes: List<LaneInfo>
)

/**
 * 读取构建时从 campusrun/templates/ 同步进 assets/templates 的场地几何。
 * geometry.json 只存内、外两条边界（等角度采样）和道数，任意道次由 App 端插值。
 */
class TemplateRepository(private val context: Context) {

    @Volatile
    private var cache: List<Stadium>? = null

    fun stadiums(): List<Stadium> {
        cache?.let { return it }
        val text = context.assets.open("templates/stadiums.json").bufferedReader().use { it.readText() }
        val array = JSONObject(text).getJSONArray("stadiums")
        val result = ArrayList<Stadium>(array.length())
        for (i in 0 until array.length()) {
            val o = array.getJSONObject(i)
            val lanesArray = o.optJSONArray("lanes") ?: JSONArray()
            val lanes = ArrayList<LaneInfo>(lanesArray.length())
            for (j in 0 until lanesArray.length()) {
                val lane = lanesArray.getJSONObject(j)
                lanes += LaneInfo(lane.getInt("index"), lane.optDouble("length_m", 0.0))
            }
            val id = o.getString("id")
            result += Stadium(
                id = id,
                name = o.optString("name", id),
                dir = o.optString("dir", id),
                file = o.optString("file", "geometry.json"),
                laneCount = o.optInt("lane_count", lanes.size.coerceAtLeast(2)),
                lanes = lanes.sortedBy { it.index }
            )
        }
        cache = result
        return result
    }

    fun loadTrack(stadiumId: String): StadiumTrack {
        val stadium = stadiums().firstOrNull { it.id == stadiumId }
            ?: error("未找到场地 $stadiumId")
        val text = context.assets
            .open("templates/${stadium.dir}/${stadium.file}")
            .bufferedReader().use { it.readText() }
        val o = JSONObject(text)
        val centerObj = o.getJSONObject("center")
        val lanesArray = o.optJSONArray("lanes") ?: JSONArray()
        val lengths = HashMap<Int, Double>(lanesArray.length())
        for (i in 0 until lanesArray.length()) {
            val lane = lanesArray.getJSONObject(i)
            lengths[lane.getInt("index")] = lane.optDouble("length_m", 0.0)
        }
        return StadiumTrack(
            venueId = o.optString("venue_id", stadium.id),
            venueName = o.optString("venue_name", stadium.name),
            laneCount = o.optInt("lane_count", stadium.laneCount),
            stepDeg = o.optDouble("step_deg", 0.5),
            center = GeoPoint(centerObj.getDouble("lat"), centerObj.getDouble("lon")),
            inner = parseRing(o.getJSONArray("inner")),
            outer = parseRing(o.getJSONArray("outer")),
            laneLengthsM = lengths
        )
    }

    private fun parseRing(array: JSONArray): List<GeoPoint> {
        val points = ArrayList<GeoPoint>(array.length())
        for (i in 0 until array.length()) {
            val p = array.getJSONArray(i)
            points += GeoPoint(p.getDouble(0), p.getDouble(1))
        }
        return points
    }
}
