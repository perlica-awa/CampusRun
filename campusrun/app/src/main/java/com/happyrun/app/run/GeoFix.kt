package com.happyrun.app.run

/**
 * 页面拿到的是 WGS84（它自己会转 GCJ-02），所以注入的坐标一律保持 WGS84。
 */
data class GeoPoint(val lat: Double, val lon: Double)

data class GeoFix(
    val lat: Double,
    val lon: Double,
    val accuracy: Float = 5f,
    val altitude: Double = 40.0,
    val heading: Double? = null,
    val speed: Double = 0.0,
    val timeMs: Long = System.currentTimeMillis()
) {
    /** 与 geo_shim.js 约定的字段名，保持精简。 */
    fun toJson(): String {
        val h = heading?.toString() ?: "null"
        return """{"lat":$lat,"lon":$lon,"acc":$accuracy,"alt":$altitude,"heading":$h,"speed":$speed,"t":$timeMs}"""
    }
}
