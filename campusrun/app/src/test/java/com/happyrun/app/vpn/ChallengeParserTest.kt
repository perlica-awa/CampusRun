package com.happyrun.app.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Base64

class ChallengeParserTest {

    /** 真机抓到的那一条：服务端就在 INFOMSG 里推这个 */
    @Test
    fun parsesRealCrTextLine() {
        assertEquals("请输入短信验证码：", ChallengeParser.parseCrText("CR_TEXT:R,E:请输入短信验证码："))
    }

    @Test
    fun parsesCrTextWithoutFlags() {
        assertEquals("请输入验证码", ChallengeParser.parseCrText("CR_TEXT:请输入验证码"))
    }

    @Test
    fun keepsColonsInsideChallengeText() {
        assertEquals("验证码已发至 138****1234，有效期 5 分钟", ChallengeParser.parseCrText("CR_TEXT:R,E:验证码已发至 138****1234，有效期 5 分钟"))
        assertEquals("http://example.com/auth", ChallengeParser.parseCrText("CR_TEXT:R:http://example.com/auth"))
    }

    @Test
    fun ignoresNonCrText() {
        assertNull(ChallengeParser.parseCrText("OPEN_URL:https://x"))
        assertNull(ChallengeParser.parseCrText("CR_TEXT:"))
    }

    @Test
    fun parsesDynamicPrompt() {
        val prompt = "请输入短信验证码"
        val encoded = Base64.getEncoder().encodeToString(prompt.toByteArray())
        val raw = "Verification Failed: 'Auth' ['AUTH_FAILED,CRV1:R,E:$encoded:ZWNobw==:c3RhdGU=']"
        val decoded = ChallengeParser.parseDynamicPrompt(raw) { String(Base64.getDecoder().decode(it)) }
        assertEquals(prompt, decoded)
    }

    @Test
    fun returnsNullWhenNoCrv1() {
        assertNull(ChallengeParser.parseDynamicPrompt("Verification Failed: 'Auth'") { it })
    }
}
