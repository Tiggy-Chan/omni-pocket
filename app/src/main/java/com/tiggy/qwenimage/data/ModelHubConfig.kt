package com.tiggy.qwenimage.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

enum class HubMirrorMode(val label: String, val defaultBaseUrl: String) {
    Official("Hugging Face", "https://huggingface.co"),
    HfMirror("HF-Mirror 国内镜像", "https://hf-mirror.com"),
    Custom("自定义镜像", "")
}

data class HubSettings(
    val mode: HubMirrorMode = defaultMirrorMode(),
    val customBaseUrl: String = "",
    val token: String = ""
) {
    val effectiveBaseUrl: String
        get() = HubUrlRewriter.effectiveBaseUrl(this)

    val displayName: String
        get() = when (mode) {
            HubMirrorMode.Official -> "Hugging Face"
            HubMirrorMode.HfMirror -> "HF-Mirror"
            HubMirrorMode.Custom -> customBaseUrl.removePrefix("https://").removePrefix("http://").trimEnd('/')
                .ifBlank { "自定义镜像" }
        }

    companion object {
        fun defaultMirrorMode(locale: Locale = Locale.getDefault()): HubMirrorMode =
            if (locale.country.equals("CN", ignoreCase = true)) HubMirrorMode.HfMirror else HubMirrorMode.Official
    }
}

object HubUrlRewriter {
    const val OFFICIAL_BASE = "https://huggingface.co"
    const val HF_MIRROR_BASE = "https://hf-mirror.com"

    fun normalizeBaseUrl(raw: String): String {
        val trimmed = raw.trim().trimEnd('/')
        if (trimmed.isEmpty()) return ""
        val uri = runCatching { URI(trimmed) }.getOrNull()
            ?: throw IllegalArgumentException("镜像地址格式无效")
        val scheme = uri.scheme?.lowercase(Locale.ROOT)
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val isLocalTest = host == "127.0.0.1" || host == "localhost"
        require(scheme == "https" || (scheme == "http" && isLocalTest)) {
            "镜像地址必须使用 https://"
        }
        require(host.isNotBlank()) { "镜像地址缺少主机名" }
        return trimmed
    }

    fun effectiveBaseUrl(settings: HubSettings): String = when (settings.mode) {
        HubMirrorMode.Official -> OFFICIAL_BASE
        HubMirrorMode.HfMirror -> HF_MIRROR_BASE
        HubMirrorMode.Custom -> runCatching { normalizeBaseUrl(settings.customBaseUrl) }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: OFFICIAL_BASE
    }

    /** Rewrites canonical https://huggingface.co/... URLs to the configured Hub mirror. */
    fun rewriteDownloadUrl(url: String, settings: HubSettings): String {
        val base = effectiveBaseUrl(settings)
        if (url.startsWith("$OFFICIAL_BASE/")) {
            return base + url.removePrefix(OFFICIAL_BASE)
        }
        if (url.startsWith("$HF_MIRROR_BASE/")) {
            return base + url.removePrefix(HF_MIRROR_BASE)
        }
        return url
    }

    fun resolveFileUrl(repo: String, revision: String, path: String, settings: HubSettings): String {
        require(repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) { "无效仓库名：$repo" }
        require(revision.isNotBlank() && !revision.contains("..")) { "无效版本：$revision" }
        val encodedRev = encodeSegment(revision)
        val encodedPath = path.split('/').joinToString("/") { encodeSegment(it) }
        return "${effectiveBaseUrl(settings)}/$repo/resolve/$encodedRev/$encodedPath"
    }

    fun canonicalFileUrl(repo: String, revision: String, path: String): String {
        val encodedRev = encodeSegment(revision)
        val encodedPath = path.split('/').joinToString("/") { encodeSegment(it) }
        return "$OFFICIAL_BASE/$repo/resolve/$encodedRev/$encodedPath"
    }

    fun apiHostMatches(url: String, settings: HubSettings): Boolean {
        val targetHost = runCatching { URI(url).host?.lowercase(Locale.ROOT) }.getOrNull() ?: return false
        val configuredHost = runCatching { URI(effectiveBaseUrl(settings)).host?.lowercase(Locale.ROOT) }.getOrNull()
        return configuredHost != null && targetHost == configuredHost
    }

    fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
}

data class HubProbeResult(
    val reachable: Boolean,
    val latencyMs: Long,
    val message: String
)

object HubConnectivityChecker {
    suspend fun probe(settings: HubSettings): HubProbeResult = withContext(Dispatchers.IO) {
        val base = settings.effectiveBaseUrl
        val start = System.currentTimeMillis()
        var connection: HttpURLConnection? = null
        try {
            connection = (URL("$base/api/models?limit=1").openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "OmniPocket/0.1")
                if (settings.token.isNotBlank()) {
                    setRequestProperty("Authorization", "Bearer ${settings.token.trim()}")
                }
            }
            val code = connection.responseCode
            val latency = (System.currentTimeMillis() - start).coerceAtLeast(1L)
            if (code in 200..299) {
                HubProbeResult(true, latency, "已连接 · ${latency} ms")
            } else {
                HubProbeResult(false, latency, "响应 HTTP $code")
            }
        } catch (error: Throwable) {
            val latency = System.currentTimeMillis() - start
            HubProbeResult(false, latency, "连接失败：${error.message ?: error.javaClass.simpleName}")
        } finally {
            connection?.disconnect()
        }
    }
}

class HubPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("model-hub", Context.MODE_PRIVATE)
    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<HubSettings> = _settings.asStateFlow()

    fun current(): HubSettings = _settings.value

    fun update(transform: (HubSettings) -> HubSettings): HubSettings {
        val next = transform(_settings.value)
        if (next.mode == HubMirrorMode.Custom && next.customBaseUrl.isNotBlank()) {
            HubUrlRewriter.normalizeBaseUrl(next.customBaseUrl)
        }
        prefs.edit()
            .putString("mode", next.mode.name)
            .putString("customBaseUrl", next.customBaseUrl.trim())
            .putString("token", next.token.trim())
            .apply()
        _settings.value = next
        return next
    }

    private fun read(): HubSettings {
        val mode = prefs.getString("mode", null)?.let { name ->
            runCatching { HubMirrorMode.valueOf(name) }.getOrNull()
        } ?: HubSettings.defaultMirrorMode()
        return HubSettings(
            mode = mode,
            customBaseUrl = prefs.getString("customBaseUrl", "").orEmpty(),
            token = prefs.getString("token", "").orEmpty()
        )
    }

    companion object {
        @Volatile private var instance: HubPreferences? = null

        fun get(context: Context): HubPreferences =
            instance ?: synchronized(this) {
                instance ?: HubPreferences(context.applicationContext).also { instance = it }
            }
    }
}
