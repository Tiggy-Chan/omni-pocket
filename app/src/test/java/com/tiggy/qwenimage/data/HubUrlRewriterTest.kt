package com.tiggy.qwenimage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class HubUrlRewriterTest {
    @Test
    fun rewritesCanonicalHuggingFaceUrlToHfMirror() {
        val settings = HubSettings(mode = HubMirrorMode.HfMirror)
        val input = "https://huggingface.co/taobao-mnn/Qwen3.5-0.8B-MNN/resolve/main/llm.mnn"
        assertEquals(
            "https://hf-mirror.com/taobao-mnn/Qwen3.5-0.8B-MNN/resolve/main/llm.mnn",
            HubUrlRewriter.rewriteDownloadUrl(input, settings)
        )
    }

    @Test
    fun rewritesToCustomHttpsMirrorAndStripsTrailingSlash() {
        val settings = HubSettings(mode = HubMirrorMode.Custom, customBaseUrl = "https://mirror.example.cn/hf/")
        val input = "https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/resolve/main/model.gguf"
        assertEquals(
            "https://mirror.example.cn/hf/Qwen/Qwen3-1.7B-GGUF/resolve/main/model.gguf",
            HubUrlRewriter.rewriteDownloadUrl(input, settings)
        )
    }

    @Test
    fun leavesNonHubUrlsUntouched() {
        val settings = HubSettings(mode = HubMirrorMode.HfMirror)
        val local = "http://127.0.0.1:8080/model"
        assertEquals(local, HubUrlRewriter.rewriteDownloadUrl(local, settings))
    }

    @Test
    fun rejectsInsecureRemoteCustomMirror() {
        assertThrows(IllegalArgumentException::class.java) {
            HubUrlRewriter.normalizeBaseUrl("http://hf-mirror.com")
        }
    }

    @Test
    fun encodesPathSegmentsSafely() {
        val settings = HubSettings(mode = HubMirrorMode.HfMirror)
        val url = HubUrlRewriter.resolveFileUrl("owner/repo", "main", "sub dir/model file.gguf", settings)
        assertEquals("https://hf-mirror.com/owner/repo/resolve/main/sub%20dir/model%20file.gguf", url)
    }

    @Test
    fun choosesHfMirrorByDefaultForChinaLocale() {
        assertEquals(HubMirrorMode.HfMirror, HubSettings.defaultMirrorMode(Locale.CHINA))
        assertEquals(HubMirrorMode.Official, HubSettings.defaultMirrorMode(Locale.US))
    }

    @Test
    fun apiHostMatchingProtectsTokenFromThirdPartyCdn() {
        val settings = HubSettings(mode = HubMirrorMode.HfMirror, token = "hf_secret")
        assertTrue(HubUrlRewriter.apiHostMatches("https://hf-mirror.com/api/models", settings))
        assertFalse(HubUrlRewriter.apiHostMatches("https://cdn-lfs.xethub.example.com/blob/123", settings))
    }
}
