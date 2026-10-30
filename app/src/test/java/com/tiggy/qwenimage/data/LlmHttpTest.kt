package com.tiggy.qwenimage.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.file.Files

class LlmHttpTest {
    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread
    private lateinit var directory: File
    private val payload = "0123456789abcdef".toByteArray()
    private var wrongRange = false
    private var truncate = false
    @Volatile private var serving = true

    @Before
    fun startServer() {
        directory = Files.createTempDirectory("llm-http-test").toFile()
        serving = true
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        serverThread = Thread({
            while (serving) {
                try {
                    server.accept().use { socket -> respond(socket) }
                } catch (_: SocketException) {
                    if (serving) throw AssertionError("local HTTP server stopped unexpectedly")
                }
            }
        }, "llm-test-http").apply { isDaemon = true; start() }
    }

    private fun respond(socket: Socket) {
        val reader = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        reader.readLine()
        val headers = generateSequence { reader.readLine() }.takeWhile { it.isNotEmpty() }.toList()
        val range = headers.firstOrNull { it.startsWith("Range:", ignoreCase = true) }?.substringAfter(':')?.trim()
        val start = range?.removePrefix("bytes=")?.removeSuffix("-")?.toLongOrNull()
        val body = when {
            truncate -> payload.copyOf(payload.size - 1)
            start == null -> payload
            else -> payload.copyOfRange(start.toInt(), payload.size)
        }
        val status = if (start != null && !truncate) 206 else 200
        val contentRange = if (wrongRange) "bytes 0-${payload.size - 1}/${payload.size}"
        else "bytes $start-${payload.size - 1}/${payload.size}"
        val header = buildString {
            append("HTTP/1.1 $status Test\r\nContent-Length: ${body.size}\r\nConnection: close\r\n")
            if (status == 206) append("Content-Range: $contentRange\r\n")
            append("\r\n")
        }
        socket.getOutputStream().apply {
            write(header.toByteArray(Charsets.US_ASCII))
            write(body)
            flush()
        }
    }

    @After
    fun stopServer() {
        serving = false
        server.close()
        serverThread.join(2_000)
        directory.deleteRecursively()
    }

    @Test
    fun resumesAtValidatedRange() = runBlocking {
        val part = File(directory, "model.part").apply { writeBytes(payload.copyOf(4)) }
        LlmHttp.download(url(), part, payload.size.toLong(), coroutineContext) {}
        assertEquals(payload.toList(), part.readBytes().toList())
    }

    @Test
    fun ignoresRangeOnlyWhenServerReturnsFullResponse() = runBlocking {
        val part = File(directory, "model.part").apply { writeBytes(payload.copyOf(4)) }
        truncate = true
        assertThrows(IllegalStateException::class.java) {
            runBlocking { LlmHttp.download(url(), part, payload.size.toLong(), coroutineContext) {} }
        }
        truncate = false
        LlmHttp.download(url(), part, payload.size.toLong(), coroutineContext) {}
        assertEquals(payload.toList(), part.readBytes().toList())
    }

    @Test
    fun rejectsWrongRangeBeforeAppendingBytes() = runBlocking {
        val part = File(directory, "model.part").apply { writeBytes(payload.copyOf(4)) }
        wrongRange = true
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { LlmHttp.download(url(), part, payload.size.toLong(), coroutineContext) {} }
        }
        assertEquals(payload.copyOf(4).toList(), part.readBytes().toList())
    }

    private fun url() = "http://127.0.0.1:${server.localPort}/model"
}
