package com.happyrun.app.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话归属判定。`session_owner` 与当前账号对不上就说明 cookie 属于上一个账号，
 * 必须按未登录处理——这正是"删了账号还能跑"的防线。
 */
class CasSessionTest {

    @Test
    fun unknownOwnerIsCompatible() {
        // 升级前的旧数据没有 session_owner，不能因此把用户挡在登录页
        assertTrue(CasSession.ownerMatches(null, "20240002"))
    }

    @Test
    fun unknownAccountIsCompatible() {
        // 网页登录未保存账号时 active 为空，只能相信 owner
        assertTrue(CasSession.ownerMatches("20240002", null))
    }

    @Test
    fun sameOwnerIsCompatible() {
        assertTrue(CasSession.ownerMatches("20240002", "20240002"))
    }

    @Test
    fun differentOwnerIsRejected() {
        assertFalse(CasSession.ownerMatches("20240001", "20240002"))
    }
}
