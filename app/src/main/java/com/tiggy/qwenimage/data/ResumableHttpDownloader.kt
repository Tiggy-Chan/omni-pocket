package com.tiggy.qwenimage.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import kotlin.coroutines.CoroutineContext

/**
 * Resumable HTTP downloader supporting Hugging Face / hf-mirror redirects, strict Content-Range
 * validation, optional bearer auth (stripped across third-party CDN redirects), and retries.
 */
object ResumableHttpDownloader {
    private const val MAX_REDIRECTS = 10
    private const val BUFFER_SIZE = 1 shl 20
    private const val USER_AGENT = "OmniPocket/0.1"

    fun download(
        url: String,
        part: File,
        expectedSize: Long?,
        operation: CoroutineContext,
        hubSettings: HubSettings = HubSettings(mode = HubMirrorMode.Official),
        maxAttempts: Int = 3,
        progress: (downloaded: Long, total: Long) -> Unit
    ): Long {
        val rewrittenUrl = HubUrlRewriter.rewriteDownloadUrl(url, hubSettings)
        var lastError: Throwable? = null
        for (attempt in 1..maxAttempts) {
            operation.ensureActive()
            try {
                return downloadOnce(rewrittenUrl, part, expectedSize, operation, hubSettings, progress)
            } catch (error: NonRetryableHttpException) {
                throw error
            } catch (error: IllegalArgumentException) {
                // Protocol / Range contract error should fail fast without retrying.
                throw error
            } catch (error: IllegalStateException) {
                if (attempt == maxAttempts) throw error
                lastError = error
            } catch (error: IOException) {
                if (attempt == maxAttempts) throw error
                lastError = error
            }
            // Brief blocking backoff on IO dispatcher between retries.
            val sleepMs = (attempt * 1500L).coerceAtMost(5_000L)
            val deadline = System.currentTimeMillis() + sleepMs
            while (System.currentTimeMillis() < deadline) {
                operation.ensureActive()
                Thread.sleep(100)
            }
        }
        throw lastError ?: IOException("下载失败")
    }

    private fun downloadOnce(
        url: String,
        part: File,
        expectedSize: Long?,
        operation: CoroutineContext,
        hubSettings: HubSettings,
        progress: (Long, Long) -> Unit
    ): Long {
        var have = if (part.isFile) part.length() else 0L
        if (expectedSize != null && have > expectedSize) {
            if (!part.delete()) throw IOException("无法清理超长的续传文件")
            have = 0L
        }
        val connection = openWithRedirects(
            url = url,
            hubSettings = hubSettings,
            rangeStart = have.takeIf { it > 0L },
            connectTimeoutMs = 30_000,
            readTimeoutMs = 60_000
        )
        try {
            val response = connection.responseCode
            if (response == 401 || response == 403) {
                throw NonRetryableHttpException("需要访问权限（HTTP $response），如为受限模型请在设置中填写 Hugging Face Token")
            }
            if (response == 404) {
                throw NonRetryableHttpException("模型文件不存在（HTTP 404），请切换镜像源或检查仓库版本")
            }
            if (response == 416 && expectedSize != null && have == expectedSize) {
                progress(have, expectedSize)
                return have
            }
            require(response == HttpURLConnection.HTTP_OK || (have > 0 && response == HttpURLConnection.HTTP_PARTIAL)) {
                "下载失败：HTTP $response"
            }
            var totalSize = expectedSize ?: -1L
            if (response == HttpURLConnection.HTTP_PARTIAL) {
                val range = Regex("bytes (\\d+)-(\\d+)/(\\d+|\\*)")
                    .matchEntire(connection.getHeaderField("Content-Range").orEmpty())
                require(range != null) { "服务器缺少有效的 Content-Range" }
                val start = range.groupValues[1].toLong()
                val end = range.groupValues[2].toLong()
                val totalHeader = range.groupValues[3]
                val parsedTotal = totalHeader.toLongOrNull()
                if (expectedSize != null) {
                    require(
                        start == have && end >= have && end < expectedSize &&
                            (totalHeader == "*" || parsedTotal == expectedSize)
                    ) { "服务器返回了错误的续传范围" }
                    totalSize = expectedSize
                } else {
                    require(start == have && end >= have) { "服务器返回了错误的续传范围" }
                    totalSize = parsedTotal ?: (end + 1)
                }
            } else {
                val contentLength = connection.contentLengthLong
                if (expectedSize == null && contentLength > 0) totalSize = contentLength
            }

            val append = response == HttpURLConnection.HTTP_PARTIAL && have > 0
            if (!append) have = 0L
            progress(have, totalSize.coerceAtLeast(have))
            connection.inputStream.use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(BUFFER_SIZE)
                    var received = have
                    var lastReport = System.currentTimeMillis()
                    while (true) {
                        operation.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        if (expectedSize != null) {
                            check(received + count <= expectedSize) { "下载内容超过清单大小" }
                        }
                        output.write(buffer, 0, count)
                        received += count
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 150L || (totalSize > 0 && received >= totalSize)) {
                            progress(received, totalSize.coerceAtLeast(received))
                            lastReport = now
                        }
                    }
                    progress(received, totalSize.coerceAtLeast(received))
                }
            }
            val finalSize = part.length()
            if (expectedSize != null) {
                check(finalSize == expectedSize) { "下载不完整，可继续重试" }
            } else if (totalSize > 0) {
                check(finalSize == totalSize) { "下载不完整（$finalSize / $totalSize），可继续重试" }
            }
            return finalSize
        } finally {
            connection.disconnect()
        }
    }

    fun fetchText(
        url: String,
        hubSettings: HubSettings,
        maxBytes: Int = 2 * 1024 * 1024,
        connectTimeoutMs: Int = 15_000,
        readTimeoutMs: Int = 30_000
    ): String {
        val rewritten = HubUrlRewriter.rewriteDownloadUrl(url, hubSettings)
        val connection = openWithRedirects(
            url = rewritten,
            hubSettings = hubSettings,
            rangeStart = null,
            connectTimeoutMs = connectTimeoutMs,
            readTimeoutMs = readTimeoutMs,
            accept = "application/json"
        )
        try {
            val code = connection.responseCode
            if (code == 401 || code == 403) {
                throw IOException("无权访问该仓库（HTTP $code），可能需要填写 Hugging Face Token")
            }
            if (code !in 200..299) {
                throw IOException("请求失败：HTTP $code")
            }
            return connection.inputStream.use { readLimited(it, maxBytes) }
        } finally {
            connection.disconnect()
        }
    }

    internal fun openWithRedirects(
        url: String,
        hubSettings: HubSettings,
        rangeStart: Long?,
        connectTimeoutMs: Int,
        readTimeoutMs: Int,
        accept: String? = null
    ): HttpURLConnection {
        var current = url
        val token = hubSettings.token.trim()
        for (hop in 0..MAX_REDIRECTS) {
            val conn = (URL(current).openConnection() as HttpURLConnection).apply {
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Encoding", "identity")
                if (accept != null) setRequestProperty("Accept", accept)
                if (rangeStart != null && rangeStart > 0) {
                    setRequestProperty("Range", "bytes=$rangeStart-")
                }
                if (token.isNotEmpty() && HubUrlRewriter.apiHostMatches(current, hubSettings)) {
                    setRequestProperty("Authorization", "Bearer $token")
                }
            }
            val code = conn.responseCode
            if (code == 301 || code == 302 || code == 303 || code == 307 || code == 308) {
                val location = conn.getHeaderField("Location")
                conn.disconnect()
                if (location.isNullOrBlank()) throw IOException("重定向缺少目标地址")
                current = runCatching { URI(current).resolve(location).toString() }
                    .getOrElse { URL(URL(current), location).toString() }
                continue
            }
            return conn
        }
        throw IOException("重定向次数过多")
    }

    private class NonRetryableHttpException(message: String) : IOException(message)

    fun readLimited(input: InputStream, maximum: Int): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var length = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            length += count
            require(length <= maximum) { "响应内容过大" }
            if (count > 0) output.write(buffer, 0, count)
        }
        return String(output.toByteArray(), Charsets.UTF_8)
    }

    fun sha256(file: File, operation: CoroutineContext? = null): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                operation?.ensureActive()
                val count = input.read(buffer)
                if (count < 0) break
                if (count > 0) digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
