package com.happyrun.app.web

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * cookie 头解析是纯函数，单独覆盖：会话判定全建立在这上面，
 * 误判一次就会拿错账号的 TGT 去换票。
 */
class SessionCookiesTest {

    @Test
    fun findsExactCookieName() {
        assertTrue(SessionCookies.cookieHeaderHas("CASTGC=abc; PHPSESSID=xyz", "CASTGC"))
        assertTrue(SessionCookies.cookieHeaderHas("CASTGC=abc; PHPSESSID=xyz", "PHPSESSID"))
    }

    @Test
    fun ignoresCaseAndSurroundingSpaces() {
        assertTrue(SessionCookies.cookieHeaderHas("  castgc = abc ;PHPSESSID=xyz", "CASTGC"))
    }

    @Test
    fun doesNotMatchPrefix() {
        // 后缀/前缀相同的 cookie 名不能算命中
        assertFalse(SessionCookies.cookieHeaderHas("XCASTGC=abc", "CASTGC"))
        assertFalse(SessionCookies.cookieHeaderHas("CASTGCX=abc", "CASTGC"))
    }

    @Test
    fun tokenWithoutValueIsNotACookie() {
        assertFalse(SessionCookies.cookieHeaderHas("CASTGC", "CASTGC"))
        assertFalse(SessionCookies.cookieHeaderHas("", "CASTGC"))
    }

    @Test
    fun emptyValueStillCountsAsPresent() {
        // 服务端用 Max-Age=0 删除 cookie 时仍会返回 name= 形式的空值
        assertTrue(SessionCookies.cookieHeaderHas("CASTGC=", "CASTGC"))
    }
}
