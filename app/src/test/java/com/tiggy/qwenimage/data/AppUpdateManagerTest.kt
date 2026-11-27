package com.tiggy.qwenimage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdateManagerTest {

    @Test
    fun `semver comparison handles patch minor major and v prefix`() {
        assertTrue(AppUpdateManager.isNewerVersion("v0.2.2", "0.2.1"))
        assertTrue(AppUpdateManager.isNewerVersion("0.10.0", "0.2.9"))
        assertTrue(AppUpdateManager.isNewerVersion("v1.0.0", "0.9.9"))
        assertFalse(AppUpdateManager.isNewerVersion("v0.2.2", "0.2.2"))
        assertFalse(AppUpdateManager.isNewerVersion("0.2.1", "0.2.2"))
    }

    @Test
    fun `parses GitHub release JSON with sha256 digest and bilingual highlights`() {
        val hash = "a".repeat(64)
        val json = """
            {
              "tag_name": "v0.2.2",
              "draft": false,
              "prerelease": false,
              "body": "## Omni Pocket v0.2.2\n- **Hexagon NPU** — Experimental Snapdragon NPU\n<details><summary>简体中文</summary>\n- **高通 NPU** — 实验性支持骁龙 NPU\n</details>",
              "assets": [
                {
                  "name": "omni-pocket-v0.2.2-arm64-v8a.apk",
                  "size": 43000000,
                  "digest": "sha256:$hash",
                  "browser_download_url": "https://github.com/Tiggy-Chan/omni-pocket/releases/download/v0.2.2/omni-pocket-v0.2.2-arm64-v8a.apk"
                },
                {
                  "name": "SHA256SUMS.txt",
                  "size": 197,
                  "browser_download_url": "https://github.com/Tiggy-Chan/omni-pocket/releases/download/v0.2.2/SHA256SUMS.txt"
                }
              ]
            }
        """.trimIndent()
        val info = AppUpdateManager.parseReleaseJson(json)
        assertNotNull(info)
        assertEquals("0.2.2", info!!.versionName)
        assertEquals(hash, info.expectedSha256)
        assertTrue(info.releaseNotesEn.contains("Hexagon NPU"))
        assertTrue(info.releaseNotesZh.contains("高通 NPU"))
        val urls = AppUpdateManager.buildCandidateUrls(info.apkUrl)
        assertEquals(info.apkUrl, urls.first())
        assertTrue(urls.size >= 3)
    }

    @Test
    fun `parses sha256 from SHA256SUMS text`() {
        val h = "b".repeat(64)
        val text = "$h  omni-pocket-v0.2.2-arm64-v8a.apk\n"
        assertEquals(h, AppUpdateManager.parseSha256FromSums(text, "omni-pocket-v0.2.2-arm64-v8a.apk"))
    }
}
