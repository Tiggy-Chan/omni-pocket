package com.tiggy.qwenimage.data

import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

class ResumableHttpDownloaderTest {
    @Test
    fun resumesAcrossManyTruncatedSegmentsWhenMakingForwardProgress() {
        val payload = ByteArray(100_000) { (it * 31 and 0xFF).toByte() }
        val chunkLimit = 20_000 // Forces 5 resumed segments (> maxAttempts=2!)
        val requests = AtomicInteger(0)

        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    var rangeHeader: String? = null
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        if (line.startsWith("Range:", ignoreCase = true)) {
                            rangeHeader = line.substringAfter(":").trim()
                        }
                    }
                    requests.incrementAndGet()
                    val start = rangeHeader?.removePrefix("bytes=")?.substringBefore("-")?.toIntOrNull() ?: 0
                    val endExclusive = (start + chunkLimit).coerceAtMost(payload.size)
                    val slice = payload.copyOfRange(start, endExclusive)
                    val out = s.getOutputStream()
                    val headers = if (start > 0) {
                        "HTTP/1.1 206 Partial Content\r\n" +
                            "Content-Range: bytes $start-${payload.size - 1}/${payload.size}\r\n" +
                            "Content-Length: ${payload.size - start}\r\n" +
                            "Connection: close\r\n\r\n"
                    } else {
                        "HTTP/1.1 200 OK\r\n" +
                            "Content-Length: ${payload.size}\r\n" +
                            "Connection: close\r\n\r\n"
                    }
                    out.write(headers.toByteArray(Charsets.US_ASCII))
                    out.write(slice)
                    out.flush()
                }
            }
        }
        val tempFile = File.createTempFile("omni-dl-test-", ".part")
        tempFile.delete()
        try {
            val url = "http://127.0.0.1:${server.localPort}/model.gguf"
            val downloaded = ResumableHttpDownloader.download(
                url = url,
                part = tempFile,
                expectedSize = payload.size.toLong(),
                operation = Dispatchers.IO,
                maxAttempts = 2
            ) { _, _ -> }
            assertEquals(payload.size.toLong(), downloaded)
            assertArrayEquals(payload, tempFile.readBytes())
            assertEquals(5, requests.get())
        } finally {
            server.close()
            worker.join(1000)
            tempFile.delete()
        }
    }

    @Test
    fun retriesTransientErrorsInFetchText() {
        val hits = AtomicInteger(0)
        val server = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val socket = runCatching { server.accept() }.getOrNull() ?: break
                socket.use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    val out = s.getOutputStream()
                    if (hits.incrementAndGet() == 1) {
                        out.write("HTTP/1.1 503 Service Unavailable\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                    } else {
                        val body = """{"ok":true}""".toByteArray(Charsets.UTF_8)
                        out.write(
                            ("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
                        )
                        out.write(body)
                    }
                    out.flush()
                }
            }
        }
        try {
            val url = "http://127.0.0.1:${server.localPort}/api"
            val text = ResumableHttpDownloader.fetchText(url, HubSettings(mode = HubMirrorMode.Official))
            assertEquals("""{"ok":true}""", text)
            assertEquals(2, hits.get())
        } finally {
            server.close()
            worker.join(1000)
        }
    }
}
