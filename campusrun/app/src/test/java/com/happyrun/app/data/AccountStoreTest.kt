package com.happyrun.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 只测账号表的序列化/解析：这两段是纯函数，不碰 AndroidKeyStore。
 * 存储本身（EncryptedSharedPreferences）在真机上验证。
 */
class AccountStoreTest {

    @Test
    fun roundTripsAllFields() {
        val source = listOf(
            Account("id-1", "20240002", "大号", "p@ss\"word\\", 1234L),
            Account("id-2", "20240001", "", null, 0L),
        )
        val parsed = AccountStore.parseAccounts(AccountStore.serializeAccounts(source))
        assertEquals(source, parsed)
    }

    @Test
    fun dropsEntriesWithoutUsername() {
        val raw = AccountStore.serializeAccounts(
            listOf(Account("a", "  ", "空学号", null), Account("b", "20240002", "", null))
        )
        val parsed = AccountStore.parseAccounts(raw)
        assertEquals(1, parsed.size)
        assertEquals("20240002", parsed[0].username)
    }

    @Test
    fun nullPasswordStaysNullNotEmptyString() {
        val parsed = AccountStore.parseAccounts(
            AccountStore.serializeAccounts(listOf(Account("a", "20240002", "", null)))
        )
        assertNull(parsed[0].password)
    }

    @Test
    fun emptyPasswordFieldIsTreatedAsAbsent() {
        val raw = """[{"id":"a","username":"20240002","label":"","password":"","lastLoginAt":0}]"""
        assertNull(AccountStore.parseAccounts(raw)[0].password)
    }

    @Test
    fun legacyEntryWithoutAutoLoginDefaultsToTrue() {
        val raw = """[{"id":"a","username":"20240002","label":"","password":"p","lastLoginAt":0}]"""
        assertTrue(AccountStore.parseAccounts(raw)[0].autoLogin)
    }

    @Test
    fun autoLoginFalseSurvivesRoundTrip() {
        val source = listOf(Account("a", "20240002", "", "p", 0L, autoLogin = false))
        val parsed = AccountStore.parseAccounts(AccountStore.serializeAccounts(source))
        assertEquals(false, parsed[0].autoLogin)
    }

    @Test
    fun malformedJsonThrowsForCallerToHandle() {
        // 调用方（AccountStore.accounts）负责兜住异常，这里只确认解析器本身不静默返回脏数据
        assertTrue(runCatching { AccountStore.parseAccounts("{oops") }.isFailure)
    }

    @Test
    fun displayFallsBackToUsername() {
        assertEquals("20240002", Account("a", "20240002", "", null).display)
        assertEquals("大号", Account("a", "20240002", "大号", null).display)
    }
}
