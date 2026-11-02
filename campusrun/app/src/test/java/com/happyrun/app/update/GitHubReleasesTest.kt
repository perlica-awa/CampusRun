package com.happyrun.app.update

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleasesTest {

    @Test
    fun `解析 release 并优先选择 campusrun 的 apk`() {
        val json = JSONObject(
            """
            {
              "tag_name": "v0.2.0",
              "body": "修复若干问题",
              "html_url": "https://github.com/perlica-awa/CampusRun/releases/tag/v0.2.0",
              "assets": [
                {
                  "name": "source.zip",
                  "browser_download_url": "https://example.com/source.zip",
                  "size": 10
                },
                {
                  "name": "campusrun-v0.2.0.apk",
                  "browser_download_url": "https://example.com/campusrun-v0.2.0.apk",
                  "size": 123456,
                  "digest": "sha256:ABCdef"
                }
              ]
            }
            """.trimIndent()
        )

        val release = GitHubReleases.parseRelease(json)

        assertEquals("0.2.0", release.version)
        assertEquals("v0.2.0", release.tag)
        assertEquals("修复若干问题", release.notes)
        assertEquals("campusrun-v0.2.0.apk", release.apkName)
        assertEquals("https://example.com/campusrun-v0.2.0.apk", release.apkUrl)
        assertEquals(123456L, release.apkSize)
        assertEquals("ABCdef", release.apkSha256)
    }

    @Test
    fun `没有 digest 时为 null`() {
        val json = JSONObject(
            """
            {
              "tag_name": "v0.1.0",
              "assets": [
                {
                  "name": "app-release.apk",
                  "browser_download_url": "https://example.com/app-release.apk",
                  "size": 1
                }
              ]
            }
            """.trimIndent()
        )

        val release = GitHubReleases.parseRelease(json)
        assertNull(release.apkSha256)
        assertEquals("0.1.0", release.version)
        assertTrue(release.notes.isEmpty())
    }

    @Test(expected = UpdateException::class)
    fun `没有 apk 时抛错`() {
        val json = JSONObject(
            """
            {
              "tag_name": "v0.1.0",
              "assets": [
                {
                  "name": "source.zip",
                  "browser_download_url": "https://example.com/source.zip",
                  "size": 10
                }
              ]
            }
            """.trimIndent()
        )
        GitHubReleases.parseRelease(json)
    }
}
