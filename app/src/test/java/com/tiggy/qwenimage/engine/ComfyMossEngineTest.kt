package com.tiggy.qwenimage.engine

import com.tiggy.qwenimage.data.MOSS_TEXT_ENCODER
import com.tiggy.qwenimage.data.MOSS_VAE
import com.tiggy.qwenimage.data.MossSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

class ComfyMossEngineTest {
    private lateinit var server: FakeComfyServer
    private lateinit var outputDirectory: File

    @Before
    fun setUp() {
        outputDirectory = Files.createTempDirectory("moss-engine-test").toFile()
        server = FakeComfyServer()
    }

    @After
    fun tearDown() {
        server.close()
        outputDirectory.deleteRecursively()
    }

    private fun settings() = MossSettings(
        serverUrl = server.url,
        prompt = "a red apple",
        promptUpsampler = false
    )

    private fun engine() = ComfyMossEngine(
        outputDirectory = outputDirectory,
        imageDimensionsReader = { readPngDimensions(it) },
        pollIntervalMs = 1,
        maxWaitMs = 2_000
    )

    @Test
    fun promptHistoryQueueAndViewProduceARealImageResult() {
        val updates = mutableListOf<EngineUpdate>()

        val result = runBlocking {
            engine().executeGeneration("job-1", settings()) { updates += it }
        }

        assertEquals("prompt-1", result.promptId)
        assertEquals(3, result.width)
        assertEquals(2, result.height)
        assertEquals(File(outputDirectory, "job-1.png"), result.file)
        assertTrue(result.file.isFile)
        assertEquals(3 to 2, readPngDimensions(result.file))
        assertEquals(1, server.objectInfoCalls.get())
        assertEquals(1, server.promptCalls.get())
        assertEquals(1, server.viewCalls.get())
        assertEquals(
            "filename=moss+result.png&subfolder=nested%2Foutput&type=output",
            server.lastViewRawQuery.get()
        )
        val request = JSONObject(server.lastPromptBody.get())
        assertEquals("job-1", request.getString("client_id"))
        assertEquals("UNETLoader", request.getJSONObject("prompt").getJSONObject("1").getString("class_type"))
        assertTrue(updates.any { it.stage.contains("排队中") })
        assertTrue(updates.any { it.stage == "工作站正在生成" })
        assertTrue(updates.dropLast(1).all { it.progress == null })
        assertEquals(1f, updates.last().progress)
    }

    @Test
    fun historyExecutionFailureDoesNotReturnOrKeepAResult() {
        server.historyBehavior = HistoryBehavior.ExecutionError
        val updates = mutableListOf<EngineUpdate>()

        val error = assertThrows(IOException::class.java) {
            runBlocking { engine().executeGeneration("failed-job", settings()) { updates += it } }
        }

        assertTrue(error.message.orEmpty().contains("boom"))
        assertEquals(0, server.viewCalls.get())
        assertFalse(File(outputDirectory, "failed-job.png").exists())
        assertTrue(updates.none { it.progress == 1f })
    }

    @Test
    fun preflightRejectsAnIncompatibleServerBeforeSubmitting() {
        server.includeMossClipType = false

        val error = assertThrows(IOException::class.java) {
            runBlocking { engine().executeGeneration("bad-server", settings()) }
        }

        assertTrue(error.message.orEmpty().contains("moss_image"))
        assertEquals(0, server.promptCalls.get())
    }

    @Test
    fun invalidDownloadedImageIsDeletedAndNeverReportedAsSuccess() {
        server.serveValidImage = false
        val updates = mutableListOf<EngineUpdate>()

        assertThrows(IOException::class.java) {
            runBlocking { engine().executeGeneration("bad-image", settings()) { updates += it } }
        }

        assertFalse(File(outputDirectory, "bad-image.png").exists())
        assertTrue(updates.none { it.progress == 1f })
    }

    @Test
    fun queuedCancellationOnlyUsesQueueDeleteSemantics() {
        server.queueBehavior = QueueBehavior.Pending

        cancelComfyPrompt(server.url, "prompt-1")

        assertEquals(1, server.queueDeleteCalls.get())
        assertEquals(0, server.interruptCalls.get())
        val body = JSONObject(server.lastQueueDeleteBody.get())
        assertEquals("prompt-1", body.getJSONArray("delete").getString(0))
    }

    @Test
    fun runningCancellationChecksQueueThenInterruptsTheMatchingId() {
        server.queueBehavior = QueueBehavior.Running

        cancelComfyPrompt(server.url, "prompt-1")

        assertEquals(1, server.queueCalls.get())
        assertEquals(1, server.interruptCalls.get())
        assertEquals(0, server.queueDeleteCalls.get())
    }

    @Test
    fun cancellationNeverInterruptsSomebodyElsesRunningPrompt() {
        server.queueBehavior = QueueBehavior.RunningOther

        cancelComfyPrompt(server.url, "prompt-1")

        assertEquals(1, server.queueCalls.get())
        assertEquals(0, server.interruptCalls.get())
        assertEquals(0, server.queueDeleteCalls.get())
    }

    @Test
    fun cancelGenerationStopsLocalExecutionWithoutASuccessUpdate() {
        server.historyBehavior = HistoryBehavior.NeverComplete
        server.queueBehavior = QueueBehavior.Running
        val engine = engine()
        val updates = mutableListOf<EngineUpdate>()
        val failure = AtomicReference<Throwable?>()
        val worker = thread {
            try {
                runBlocking { engine.executeGeneration("cancel-job", settings()) { updates += it } }
            } catch (error: Throwable) {
                failure.set(error)
            }
        }

        assertTrue(server.firstQueuePoll.await(2, TimeUnit.SECONDS))
        engine.cancelGeneration()
        assertTrue(server.interruptLatch.await(2, TimeUnit.SECONDS))
        worker.join(3_000)
        engine.close()

        assertFalse(worker.isAlive)
        assertTrue(failure.get() is CancellationException)
        assertFalse(File(outputDirectory, "cancel-job.png").exists())
        assertTrue(updates.none { it.progress == 1f })
    }

    @Test
    fun workstationClientRejectsCrossOriginReferencesBeforeNetworkAccess() {
        WorkstationClient(server.url).use { client ->
            assertThrows(IllegalArgumentException::class.java) {
                client.resolve("https://example.com/view?filename=secret.png")
            }
            assertThrows(IllegalArgumentException::class.java) {
                client.resolve("//example.com/view?filename=secret.png")
            }
        }
    }
}

private fun readPngDimensions(file: File): Pair<Int, Int> {
    val bytes = file.readBytes()
    val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
    if (bytes.size < 24 || !bytes.copyOfRange(0, 8).contentEquals(signature) ||
        String(bytes, 12, 4, Charsets.US_ASCII) != "IHDR"
    ) {
        throw IOException("invalid image")
    }
    val width = ByteBuffer.wrap(bytes, 16, 4).int
    val height = ByteBuffer.wrap(bytes, 20, 4).int
    if (width <= 0 || height <= 0) throw IOException("invalid image")
    return width to height
}

private enum class HistoryBehavior { SuccessAfterPolling, ExecutionError, NeverComplete }
private enum class QueueBehavior { QueueThenRun, Pending, Running, RunningOther }

private data class FakeRequest(val method: String, val path: String, val rawQuery: String, val body: ByteArray)
private data class FakeResponse(val status: Int, val body: ByteArray, val contentType: String)

private class FakeComfyServer : AutoCloseable {
    private val serverSocket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val executor = Executors.newCachedThreadPool()
    private val sockets = ConcurrentHashMap.newKeySet<Socket>()
    private val png = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAMAAAACCAIAAAASFvFNAAAAHElEQVR4nGPQCKjQSKnQqKhgcAuocEupcKuoAAA8UAZzasmRyAAAAABJRU5ErkJggg=="
    )

    @Volatile
    private var running = true
    private var acceptThread: Thread? = null

    val url: String
        get() = "http://127.0.0.1:${serverSocket.localPort}"

    val objectInfoCalls = AtomicInteger()
    val promptCalls = AtomicInteger()
    val queueCalls = AtomicInteger()
    val viewCalls = AtomicInteger()
    val queueDeleteCalls = AtomicInteger()
    val interruptCalls = AtomicInteger()
    val lastPromptBody = AtomicReference<String>()
    val lastQueueDeleteBody = AtomicReference<String>()
    val lastViewRawQuery = AtomicReference<String>()
    val firstQueuePoll = CountDownLatch(1)
    val interruptLatch = CountDownLatch(1)

    @Volatile
    var includeMossClipType = true

    @Volatile
    var serveValidImage = true

    @Volatile
    var historyBehavior = HistoryBehavior.SuccessAfterPolling

    @Volatile
    var queueBehavior = QueueBehavior.QueueThenRun

    init {
        acceptThread = thread(isDaemon = true, name = "fake-comfy-accept") { acceptLoop() }
    }

    private fun acceptLoop() {
        while (running) {
            try {
                val socket = serverSocket.accept()
                sockets += socket
                executor.execute { handle(socket) }
            } catch (_: SocketException) {
                if (running) continue
            } catch (_: IOException) {
                if (!running) break
            }
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 5_000
            val request = readRequest(socket)
            val response = try {
                route(request)
            } catch (error: Throwable) {
                textResponse(500, error.message.orEmpty())
            }
            val header = buildString {
                append("HTTP/1.1 ${response.status} STATUS\r\n")
                append("Content-Type: ${response.contentType}\r\n")
                append("Content-Length: ${response.body.size}\r\n")
                append("Connection: close\r\n\r\n")
            }.toByteArray(Charsets.US_ASCII)
            socket.getOutputStream().use { output ->
                output.write(header)
                output.write(response.body)
                output.flush()
            }
        } catch (_: IOException) {
        } finally {
            sockets -= socket
            socket.close()
        }
    }

    private fun readRequest(socket: Socket): FakeRequest {
        val input = BufferedInputStream(socket.getInputStream())
        val requestLine = readHttpLine(input).split(' ')
        if (requestLine.size < 3) throw IOException("invalid request")
        val target = requestLine[1]
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = readHttpLine(input)
            if (line.isEmpty()) break
            val separator = line.indexOf(':')
            if (separator > 0) headers[line.substring(0, separator).lowercase()] = line.substring(separator + 1).trim()
        }
        val body = if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            readChunked(input)
        } else {
            val size = headers["content-length"]?.toIntOrNull() ?: 0
            if (size !in 0..20 * 1024 * 1024) throw IOException("invalid content length")
            input.readExactly(size)
        }
        return FakeRequest(
            method = requestLine[0],
            path = target.substringBefore('?'),
            rawQuery = target.substringAfter('?', ""),
            body = body
        )
    }

    private fun readHttpLine(input: BufferedInputStream): String {
        val output = java.io.ByteArrayOutputStream()
        while (true) {
            val value = input.read()
            if (value < 0) throw IOException("unexpected end of request")
            if (value == '\n'.code) break
            if (value != '\r'.code) output.write(value)
            if (output.size() > 64 * 1024) throw IOException("header too large")
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun readChunked(input: BufferedInputStream): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        while (true) {
            val size = readHttpLine(input).substringBefore(';').toInt(16)
            if (size == 0) {
                readHttpLine(input)
                return output.toByteArray()
            }
            if (size > 20 * 1024 * 1024 || output.size() + size > 20 * 1024 * 1024) {
                throw IOException("request too large")
            }
            output.write(input.readExactly(size))
            readHttpLine(input)
        }
    }

    private fun BufferedInputStream.readExactly(size: Int): ByteArray {
        val bytes = ByteArray(size)
        var offset = 0
        while (offset < size) {
            val count = read(bytes, offset, size - offset)
            if (count < 0) throw IOException("unexpected end of body")
            offset += count
        }
        return bytes
    }

    private fun route(request: FakeRequest): FakeResponse = when {
        request.method == "GET" && request.path == "/object_info" -> {
            objectInfoCalls.incrementAndGet()
            jsonResponse(objectInfo())
        }
        request.method == "POST" && request.path == "/prompt" -> {
            promptCalls.incrementAndGet()
            val body = request.body.toString(Charsets.UTF_8)
            JSONObject(body)
            lastPromptBody.set(body)
            jsonResponse(JSONObject().put("prompt_id", "prompt-1"))
        }
        request.method == "GET" && request.path == "/history/prompt-1" -> jsonResponse(history())
        request.method == "GET" && request.path == "/queue" -> {
            firstQueuePoll.countDown()
            jsonResponse(queue())
        }
        request.method == "POST" && request.path == "/queue" -> {
            queueDeleteCalls.incrementAndGet()
            lastQueueDeleteBody.set(request.body.toString(Charsets.UTF_8))
            FakeResponse(200, ByteArray(0), "application/json")
        }
        request.method == "POST" && request.path == "/interrupt" -> {
            interruptCalls.incrementAndGet()
            interruptLatch.countDown()
            FakeResponse(200, ByteArray(0), "application/json")
        }
        request.method == "GET" && request.path == "/view" -> {
            viewCalls.incrementAndGet()
            lastViewRawQuery.set(request.rawQuery)
            FakeResponse(200, if (serveValidImage) png else "not an image".toByteArray(), "image/png")
        }
        else -> textResponse(404, "not found")
    }

    private fun objectInfo(): JSONObject {
        val info = JSONObject()
        MossWorkflow.requiredNodeTypes(MossSettings(prompt = "x")).forEach { info.put(it, JSONObject()) }
        info.put("UNETLoader", loader(JSONObject().put("unet_name", options(
            "moss_image_sft_int8_convrot.safetensors",
            "moss_image_base_int8_convrot.safetensors"
        ))))
        info.put("CLIPLoader", loader(JSONObject().apply {
            put("clip_name", options(MOSS_TEXT_ENCODER))
            put("type", if (includeMossClipType) options("moss_image", "flux2") else options("flux2"))
        }))
        info.put("VAELoader", loader(JSONObject().put("vae_name", options(MOSS_VAE))))
        return info
    }

    private fun history(): JSONObject = when (historyBehavior) {
        HistoryBehavior.SuccessAfterPolling -> if (queueCalls.get() >= 2) {
            JSONObject().put("prompt-1", JSONObject().apply {
                put("status", JSONObject().apply {
                    put("status_str", "success")
                    put("completed", true)
                    put("messages", JSONArray())
                })
                put("outputs", JSONObject().put("12", JSONObject().put("images", JSONArray().put(JSONObject().apply {
                    put("filename", "moss result.png")
                    put("subfolder", "nested/output")
                    put("type", "output")
                }))))
            })
        } else {
            JSONObject()
        }
        HistoryBehavior.ExecutionError -> JSONObject().put("prompt-1", JSONObject().apply {
            put("status", JSONObject().apply {
                put("status_str", "error")
                put("completed", false)
                put("messages", JSONArray().put(JSONArray().apply {
                    put("execution_error")
                    put(JSONObject().put("exception_message", "boom"))
                }))
            })
            put("outputs", JSONObject())
        })
        HistoryBehavior.NeverComplete -> JSONObject()
    }

    private fun queue(): JSONObject {
        val call = queueCalls.incrementAndGet()
        val runningId = when (queueBehavior) {
            QueueBehavior.QueueThenRun -> if (call >= 2) "prompt-1" else null
            QueueBehavior.Running -> "prompt-1"
            QueueBehavior.RunningOther -> "somebody-else"
            QueueBehavior.Pending -> null
        }
        val pendingId = when (queueBehavior) {
            QueueBehavior.QueueThenRun -> if (call == 1) "prompt-1" else null
            QueueBehavior.Pending -> "prompt-1"
            else -> null
        }
        return JSONObject().apply {
            put("queue_running", queueEntry(runningId))
            put("queue_pending", queueEntry(pendingId))
        }
    }

    private fun queueEntry(promptId: String?): JSONArray {
        val entries = JSONArray()
        if (promptId != null) entries.put(JSONArray().put(0).put(promptId).put(JSONObject()))
        return entries
    }

    private fun loader(required: JSONObject) = JSONObject().put("input", JSONObject().put("required", required))

    private fun options(vararg values: String): JSONArray {
        val choices = JSONArray()
        values.forEach { choices.put(it) }
        return JSONArray().put(choices).put(JSONObject())
    }

    private fun jsonResponse(body: JSONObject) = FakeResponse(200, body.toString().toByteArray(Charsets.UTF_8), "application/json")

    private fun textResponse(status: Int, body: String) =
        FakeResponse(status, body.toByteArray(Charsets.UTF_8), "text/plain")

    override fun close() {
        running = false
        serverSocket.close()
        sockets.forEach { it.close() }
        executor.shutdownNow()
        acceptThread?.join(1_000)
    }
}
