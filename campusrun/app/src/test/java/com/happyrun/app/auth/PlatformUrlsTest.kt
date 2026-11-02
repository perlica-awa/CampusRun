package com.happyrun.app.auth

import com.happyrun.app.data.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URLDecoder

class PlatformUrlsTest {

    /** 用示例域名测试，避免把任何真实站点写进仓库 */
    private val urls = PlatformUrls(
        casHost = "cas.example.edu",
        platformHost = "run.example.edu",
        platformEntry = "https://run.example.edu/app/public/index.php/index/login/entry",
    )

    @Test
    fun `cas entry carries the platform entry as service`() {
        val entry = urls.casLoginEntry
        assertTrue(entry.startsWith("https://cas.example.edu/tpass/login?service="))
        // service 里的 :// 必须编码，否则 CAS 会把 service 截断
        assertEquals(urls.platformEntry, URLDecoder.decode(entry.substringAfter("service="), "UTF-8"))
        assertTrue(entry.contains("%3A%2F%2Frun.example.edu%2F"))
    }

    @Test
    fun `platform url detection`() {
        assertTrue(urls.isPlatformUrl(urls.platformEntry))
        assertTrue(urls.isPlatformUrl("https://run.example.edu/view/index"))
        assertTrue(urls.isPlatformUrl("https://run.example.edu/"))
        assertFalse(urls.isPlatformUrl("https://cas.example.edu/tpass/login?service=x"))
        assertFalse(urls.isPlatformUrl("https://evil.example.com/run.example.edu"))
        assertFalse(urls.isPlatformUrl(null))
    }

    @Test
    fun `cas url detection`() {
        assertTrue(urls.isCasUrl(urls.casLoginEntry))
        assertTrue(urls.isCasUrl("https://cas.example.edu/tpass/login?service=abc"))
        assertFalse(urls.isCasUrl(urls.platformEntry))
        assertFalse(urls.isCasUrl("https://cas.example.edu.evil.com/tpass/login"))
        assertFalse(urls.isCasUrl(""))
    }

    @Test
    fun `empty configuration stays inert`() {
        val empty = PlatformUrls("", "", "")
        assertFalse(empty.configured)
        assertFalse(empty.isPlatformUrl("https://run.example.edu/"))
        assertFalse(empty.isCasUrl("https://cas.example.edu/tpass/login"))
    }

    @Test
    fun `app default entry follows the configured platform entry`() {
        assertEquals(platformUrls.platformEntry, AppSettings.DEFAULT_ENTRY)
    }
}
