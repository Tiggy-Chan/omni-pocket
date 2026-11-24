package com.tiggy.qwenimage.engine

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/**
 * IPC message contract between the UI process and the isolated `:engine` inference service.
 */
object InferenceWorkerProtocol {
    const val ENGINE_SD = "sd"
    const val ENGINE_YUE2 = "yue2"

    const val MSG_RUN = 1
    const val MSG_CANCEL = 2

    const val MSG_OUTPUT = 10
    const val MSG_COMPLETED = 11
    const val MSG_FAILED = 12

    const val KEY_JOB_ID = "job_id"
    const val KEY_ENGINE = "engine"
    const val KEY_ARGS = "args"
    const val KEY_WORK_DIR = "work_dir"
    const val KEY_OPENCL_CACHE_DIR = "opencl_cache_dir"
    const val KEY_CHUNK = "chunk"
    const val KEY_EXIT_CODE = "exit_code"
    const val KEY_ERROR = "error"
}

/**
 * Stateful UTF-8 byte-stream decoder that preserves incomplete trailing multi-byte sequences
 * across arbitrary pipe/IPC chunk boundaries.
 */
internal class Utf8StreamDecoder {
    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)
    private val pending = ByteArrayOutputStream()

    fun decode(chunk: ByteArray): String {
        if (chunk.isEmpty()) return ""
        pending.write(chunk)
        val raw = pending.toByteArray()
        pending.reset()
        val input = ByteBuffer.wrap(raw)
        val output = CharBuffer.allocate((raw.size * 2).coerceAtLeast(16))
        decoder.decode(input, output, false)
        if (input.hasRemaining()) {
            val remaining = ByteArray(input.remaining())
            input.get(remaining)
            pending.write(remaining)
        }
        output.flip()
        return output.toString()
    }

    fun finish(): String {
        val raw = pending.toByteArray()
        pending.reset()
        val input = ByteBuffer.wrap(raw)
        val output = CharBuffer.allocate((raw.size * 2).coerceAtLeast(16))
        decoder.decode(input, output, true)
        decoder.flush(output)
        decoder.reset()
        output.flip()
        return output.toString()
    }
}
