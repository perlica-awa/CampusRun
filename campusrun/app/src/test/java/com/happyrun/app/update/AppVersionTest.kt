package com.happyrun.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppVersionTest {

    @Test
    fun `tag 前缀与段数差异都按数字比较`() {
        assertEquals(0, AppVersion.compare("v0.1.0", "0.1.0"))
        assertEquals(0, AppVersion.compare("1.0", "1.0.0"))
        assertTrue(AppVersion.compare("0.2.0", "0.1.9") > 0)
        assertTrue(AppVersion.compare("0.1.10", "0.1.9") > 0)
        assertTrue(AppVersion.compare("0.1.0", "0.1.1") < 0)
        assertTrue(AppVersion.compare("v2.0.0", "1.99.99") > 0)
    }

    @Test
    fun `预发布后缀不参与比较`() {
        assertEquals(0, AppVersion.compare("0.2.0-beta1", "0.2.0"))
        assertTrue(AppVersion.compare("0.2.0-beta1", "0.1.0") > 0)
    }

    @Test
    fun `normalize 只去掉版本前缀`() {
        assertEquals("0.3.1", AppVersion.normalize("v0.3.1"))
        assertEquals("0.3.1", AppVersion.normalize(" V0.3.1 "))
    }
}
