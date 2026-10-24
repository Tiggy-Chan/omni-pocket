package com.tiggy.qwenimage.engine

import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class WorkstationClient(serverUrl: String, private val maxResponseBytes: Long = 4L * 1024 * 1024) : AutoCloseable {
    private val baseUri = normalizeBase(serverUrl)
    private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()

    val serverUrl: String = baseUri.toString().trimEnd('/')

    fun isHealthy(): Boolean {
        val connection = open("/health", "GET", 3_000, 3_000)
        return try {
            connection.responseCode == HttpURLConnection.HTTP_OK
        } catch (_: IOException) {
            false
        } finally {
            release(connection)
        }
    }

    fun postJson(path: String, payload: JSONObject, readTimeoutMs: Int): JSONObject {
        val connection = open(path, "POST", 10_000, readTimeoutMs).apply {
            doOutput = true
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
        }
        return try {
            connection.outputStream.use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val message = connection.errorStream?.let { readLimited(it, 64 * 1024) } ?: "HTTP $code"
                throw IOException("工作站请求失败 ($code)：$message")
            }
            val body = connection.inputStream.use { readLimited(it, maxResponseBytes) }
            JSONObject(body)
        } finally {
            release(connection)
        }
    }

    fun getJson(path: String, readTimeoutMs: Int = 10_000): JSONObject {
        val connection = open(path, "GET", 10_000, readTimeoutMs)
        return try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val message = connection.errorStream?.let { readLimited(it, 64 * 1024) } ?: "HTTP $code"
                throw IOException("工作站请求失败 ($code)：$message")
            }
            JSONObject(connection.inputStream.use { readLimited(it, maxResponseBytes) })
        } finally {
            release(connection)
        }
    }

    fun download(reference: String, target: File, maxBytes: Long, expectedContentType: String): File {
        val connection = open(reference, "GET", 10_000, 60_000)
        val partial = File(target.parentFile, "${target.name}.part")
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) throw IOException("作品下载失败 ($code)")
            val contentType = connection.contentType?.substringBefore(';')?.trim()?.lowercase().orEmpty()
            if (contentType.isNotEmpty() && contentType != "application/octet-stream" &&
                !contentType.startsWith("$expectedContentType/")) {
                throw IOException("作品格式异常：${contentType.ifEmpty { "未知" }}")
            }
            val declaredLength = connection.contentLengthLong
            if (declaredLength > maxBytes) throw IOException("作品超过大小限制")
            target.parentFile?.mkdirs()
            partial.delete()
            connection.inputStream.use { input ->
                FileOutputStream(partial).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > maxBytes) throw IOException("作品超过大小限制")
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            if (partial.length() == 0L) throw IOException("下载的作品为空")
            if (declaredLength >= 0 && partial.length() != declaredLength) throw IOException("作品下载不完整")
            if (target.exists() && !target.delete()) throw IOException("无法替换旧作品")
            if (!partial.renameTo(target)) throw IOException("无法保存作品")
            return target
        } catch (error: Throwable) {
            partial.delete()
            throw error
        } finally {
            release(connection)
        }
    }

    fun resolve(reference: String): URL {
        val uri = if (reference.startsWith("http://") || reference.startsWith("https://")) {
            URI(reference)
        } else if (reference.startsWith("/")) {
            baseUri.resolve(reference)
        } else {
            URI("$serverUrl/${reference.trimStart('/')}")
        }
        requireSameOrigin(uri)
        return uri.toURL()
    }

    override fun close() {
        connections.forEach { it.disconnect() }
        connections.clear()
    }

    private fun open(reference: String, method: String, connectTimeoutMs: Int, readTimeoutMs: Int): HttpURLConnection {
        val connection = resolve(reference).openConnection() as HttpURLConnection
        connection.connectTimeout = connectTimeoutMs
        connection.readTimeout = readTimeoutMs
        connection.requestMethod = method
        connection.instanceFollowRedirects = false
        connections += connection
        return connection
    }

    private fun release(connection: HttpURLConnection) {
        connections -= connection
        connection.disconnect()
    }

    private fun requireSameOrigin(uri: URI) {
        require(uri.scheme == "http" || uri.scheme == "https") { "仅支持 HTTP(S) 工作站" }
        require(uri.scheme == baseUri.scheme && uri.host == baseUri.host && effectivePort(uri) == effectivePort(baseUri)) {
            "作品地址必须与工作站同源"
        }
    }

    private fun readLimited(input: InputStream, limit: Long): String {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var total = 0L
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            if (total > limit) throw IOException("工作站响应过大")
            output.write(buffer, 0, count)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private companion object {
        fun normalizeBase(value: String): URI {
            val uri = URI(value.trim().trimEnd('/'))
            require((uri.scheme == "http" || uri.scheme == "https") && !uri.host.isNullOrBlank()) {
                "请输入有效的工作站地址"
            }
            require(uri.userInfo == null && uri.query == null && uri.fragment == null) { "工作站地址不能包含凭据、查询或片段" }
            return uri
        }

        fun effectivePort(uri: URI): Int = when {
            uri.port >= 0 -> uri.port
            uri.scheme == "https" -> 443
            else -> 80
        }
    }
}
