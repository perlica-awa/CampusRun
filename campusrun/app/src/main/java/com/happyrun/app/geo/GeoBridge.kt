package com.happyrun.app.geo

import android.content.Context
import android.webkit.JavascriptInterface
import com.happyrun.app.R
import com.happyrun.app.run.RunSession

/** 页面在 document-start 就能拿到的桥接对象（JS 里叫 RunGeo）。 */
class GeoBridge(private val session: RunSession) {

    @JavascriptInterface
    fun mode(): String = if (session.isSimulating) "sim" else "real"

    @JavascriptInterface
    fun fix(): String = session.currentFix().toJson()
}

object GeoShim {
    private var cached: String? = null

    /** 注入脚本本身很短，读一次就够。 */
    fun script(context: Context): String {
        cached?.let { return it }
        val text = context.resources.openRawResource(R.raw.geo_shim)
            .bufferedReader()
            .use { it.readText() }
        cached = text
        return text
    }
}
