package com.tiggy.qwenimage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelDownloadServiceContractTest {

    @Test
    fun `fast-failed task before onStartCommand still promotes foreground first then self-stops`() {
        val decision = resolveDownloadFgsCommand(action = null, hasActiveTask = false)
        assertTrue(decision.mustPromoteForegroundFirst)
        assertFalse(decision.cancelActiveDownload)
        assertTrue(decision.stopImmediatelyAfterPromote)
    }

    @Test
    fun `active download promotes foreground and stays alive`() {
        val decision = resolveDownloadFgsCommand(action = null, hasActiveTask = true)
        assertTrue(decision.mustPromoteForegroundFirst)
        assertFalse(decision.cancelActiveDownload)
        assertFalse(decision.stopImmediatelyAfterPromote)
    }

    @Test
    fun `cancel action still satisfies startForeground contract before stopping`() {
        val decision = resolveDownloadFgsCommand(action = ModelDownloadService.ACTION_CANCEL, hasActiveTask = false)
        assertTrue(decision.mustPromoteForegroundFirst)
        assertTrue(decision.cancelActiveDownload)
        assertTrue(decision.stopImmediatelyAfterPromote)
    }

    @Test
    fun `document extractor rejects image bytes and image extensions`() {
        val pngHeader = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        assertTrue(DocumentTextExtractor.isImageInput("photo.png", null, ByteArray(0)))
        assertTrue(DocumentTextExtractor.isImageInput("file", "image/jpeg", ByteArray(0)))
        assertTrue(DocumentTextExtractor.isImageInput("unknown.bin", null, pngHeader))
        assertFalse(DocumentTextExtractor.isImageInput("notes.md", "text/markdown", "# hello".toByteArray()))
        val err = runCatching {
            DocumentTextExtractor.extractFromBytes("photo.jpg", "image/jpeg", pngHeader)
        }.exceptionOrNull()
        assertEquals(IllegalArgumentException::class.java, err?.javaClass)
    }
}
