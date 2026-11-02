package com.happyrun.app.web

import android.os.Handler
import android.os.Looper
import android.webkit.WebView
import org.json.JSONObject

/**
 * 打卡状态的唯一观测方式：页面把 vuex 的 mapInfo 序列化进 localStorage（16_index chunk 里
 * 的 setStorageSync("mapInfo", JSON.stringify(...))），我们只读这个镜像，不碰跑步模块内部。
 * 顺带把页面自己算出的里程读出来，HUD 显示的就是平台上看到的数字。
 */
class ClockWatcher(
    private val webView: WebView,
    private val intervalMs: Long = 1000L
) {
    data class Status(val total: Int, val passed: Int, val distanceKm: Double?)

    private val handler = Handler(Looper.getMainLooper())
    private var running = false
    private var listener: ((Status) -> Unit)? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            poll()
            handler.postDelayed(this, intervalMs)
        }
    }

    fun start(onStatus: (Status) -> Unit) {
        listener = onStatus
        if (running) return
        running = true
        handler.post(tick)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        listener = null
    }

    private fun poll() {
        val callback = listener ?: return
        webView.evaluateJavascript(SCRIPT) { value ->
            parse(value)?.let(callback)
        }
    }

    private fun parse(raw: String?): Status? {
        if (raw.isNullOrEmpty() || raw == "null") return null
        return try {
            val o = JSONObject(raw)
            if (!o.has("total")) return null
            // mapInfo.distance 是米（页面自己的 distance getter 才除以 1000）
            val distance = o.opt("distance_m")
            val km = when {
                distance == null || distance == JSONObject.NULL -> null
                distance is Number -> distance.toDouble() / 1000.0
                else -> distance.toString().toDoubleOrNull()?.let { it / 1000.0 }
            }
            Status(o.getInt("total"), o.getInt("pass"), km)
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val SCRIPT = """
(function () {
    function findClocks(o, depth) {
        if (!o || typeof o !== 'object' || depth > 4) return null;
        if (Object.prototype.toString.call(o.clocks) === '[object Array]') return o;
        for (var p in o) {
            if (p === 'clocks') continue;
            var r = findClocks(o[p], depth + 1);
            if (r) return r;
        }
        return null;
    }
    try {
        var raw = localStorage.getItem('mapInfo');
        var found = raw ? findClocks(JSON.parse(raw), 0) : null;
        if (!found) {
            for (var i = 0; i < localStorage.length; i++) {
                var k = localStorage.key(i);
                if (k === 'mapInfo') continue;
                var v = localStorage.getItem(k);
                if (!v || v.indexOf('"clocks"') < 0) continue;
                found = findClocks(JSON.parse(v), 0);
                if (found) break;
            }
        }
        if (!found) return null;
        // 定向打卡（logMode=2）时 clocks 只含当前已分配的 1 个点，allClocks 才是本次要求的全部点
        var all = found.allClocks;
        var list = (Object.prototype.toString.call(all) === '[object Array]' && all.length) ? all : found.clocks;
        var pass = 0;
        for (var j = 0; j < list.length; j++) if (list[j] && list[j].pass) pass++;
        var d = found.distance;
        if (d === undefined) d = null;
        return { total: list.length, pass: pass, distance_m: d };
    } catch (e) {
        return null;
    }
})()
"""
    }
}
