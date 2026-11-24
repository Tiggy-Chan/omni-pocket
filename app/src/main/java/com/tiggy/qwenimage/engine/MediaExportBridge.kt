package com.tiggy.qwenimage.engine

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer

/**
 * AI-native invisible export bridge using only Android platform APIs (MediaExtractor, MediaCodec,
 * MediaMuxer, PdfDocument) — zero third-party media libraries and zero APK size bloat.
 *
 * Bridges AI outputs into universally shareable formats:
 * - Video `.webm` (VP8 + optional PCM/Opus) -> `.mp4` (H.264 AVC + AAC-LC)
 * - Music `.wav` (PCM 16-bit LE) -> `.m4a` (AAC-LC)
 * - Chat / Decision / Summary -> `.md` and paginated A4 `.pdf`
 */
object MediaExportBridge {
    private const val TIMEOUT_US = 10_000L

    private data class EncodedSample(
        val data: ByteArray,
        val presentationTimeUs: Long,
        val flags: Int
    )

    fun exportDir(context: Context): File =
        File(context.cacheDir, "exports").apply { mkdirs() }

    /**
     * Transcodes a local `.webm` video (Wan2.2 video-only or MiniMax-H3 VP8 + 32kHz PCM audio)
     * into a standard H.264 + AAC `.mp4` suitable for WeChat, Xiaohongshu, and system galleries.
     */
    suspend fun exportWebmToMp4(
        inputWebm: File,
        outputMp4: File,
        onProgress: (Int) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        require(inputWebm.isFile) { "Source video file does not exist" }
        outputMp4.parentFile?.mkdirs()
        val tmpOut = File(outputMp4.parentFile, "${outputMp4.name}.tmp")
        if (tmpOut.exists()) tmpOut.delete()

        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(inputWebm.absolutePath)
            var videoTrackIndex = -1
            var audioTrackIndex = -1
            var videoInputFormat: MediaFormat? = null
            var audioInputFormat: MediaFormat? = null

            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME).orEmpty()
                if (mime.startsWith("video/") && videoTrackIndex < 0) {
                    videoTrackIndex = i
                    videoInputFormat = fmt
                } else if (mime.startsWith("audio/") && audioTrackIndex < 0) {
                    audioTrackIndex = i
                    audioInputFormat = fmt
                }
            }
            require(videoTrackIndex >= 0 && videoInputFormat != null) { "No video track found in ${inputWebm.name}" }

            // Pass 1: Encode audio track (if present) into memory (~16 KB/s AAC) so MediaMuxer can start immediately once video format is ready.
            var encodedAudioFormat: MediaFormat? = null
            var encodedAudioSamples: List<EncodedSample> = emptyList()
            if (audioTrackIndex >= 0 && audioInputFormat != null) {
                onProgress(5)
                val audioResult = encodeAudioTrackToAac(inputWebm, audioTrackIndex, audioInputFormat)
                if (audioResult != null) {
                    encodedAudioFormat = audioResult.first
                    encodedAudioSamples = audioResult.second
                }
            }
            onProgress(15)

            // Pass 2: Decode VP8 -> YUV420 Image -> H.264 AVC encoder -> MediaMuxer (plus buffered AAC track).
            transcodeVideoAndMux(
                inputWebm = inputWebm,
                videoTrackIndex = videoTrackIndex,
                videoInputFormat = videoInputFormat,
                audioFormat = encodedAudioFormat,
                audioSamples = encodedAudioSamples,
                outputFile = tmpOut,
                onProgress = { pct -> onProgress(15 + (pct * 85) / 100) }
            )

            if (outputMp4.exists()) outputMp4.delete()
            check(tmpOut.renameTo(outputMp4)) { "Failed to finalize exported MP4" }
            onProgress(100)
            outputMp4
        } finally {
            extractor.release()
            if (tmpOut.exists()) tmpOut.delete()
        }
    }

    /**
     * Converts a 16-bit PCM `.wav` track into a compact `.m4a` (AAC-LC in MPEG-4 container).
     */
    suspend fun exportWavToM4a(
        inputWav: File,
        outputM4a: File,
        bitrate: Int = 192_000,
        onProgress: (Int) -> Unit = {}
    ): File = withContext(Dispatchers.IO) {
        require(inputWav.isFile) { "Source audio file does not exist" }
        val header = FileInputStream(inputWav).use { ExportFormatters.parseWavHeader(it) }
        outputM4a.parentFile?.mkdirs()
        val tmpOut = File(outputM4a.parentFile, "${outputM4a.name}.tmp")
        if (tmpOut.exists()) tmpOut.delete()

        val aacFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC,
            header.sampleRate,
            header.channels
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
        }

        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var muxer: MediaMuxer? = null
        var muxerStarted = false
        var audioTrackIdx = -1
        val info = MediaCodec.BufferInfo()

        try {
            encoder.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            muxer = MediaMuxer(tmpOut.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val bytesPerSampleFrame = header.channels * 2
            val totalPcmBytes = header.dataSize.toLong().coerceAtLeast(1L)
            var bytesRead = 0L
            var totalSamplesFed = 0L
            var inputDone = false
            var outputDone = false

            FileInputStream(inputWav).use { fis ->
                var skipped = 0L
                while (skipped < header.dataOffset) {
                    val s = fis.skip(header.dataOffset - skipped)
                    if (s <= 0) break
                    skipped += s
                }
                val chunkBuf = ByteArray(16384)

                while (!outputDone) {
                    if (!inputDone) {
                        val inIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inIdx >= 0) {
                            val inBuf = encoder.getInputBuffer(inIdx)!!
                            inBuf.clear()
                            val remaining = (totalPcmBytes - bytesRead).coerceAtMost(inBuf.capacity().toLong()).coerceAtMost(chunkBuf.size.toLong()).toInt()
                            val n = if (remaining > 0) fis.read(chunkBuf, 0, remaining) else -1
                            if (n <= 0) {
                                val eosPts = (totalSamplesFed * 1_000_000L) / header.sampleRate
                                encoder.queueInputBuffer(inIdx, 0, 0, eosPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                inBuf.put(chunkBuf, 0, n)
                                val ptsUs = (totalSamplesFed * 1_000_000L) / header.sampleRate
                                encoder.queueInputBuffer(inIdx, 0, n, ptsUs, 0)
                                bytesRead += n
                                totalSamplesFed += n / bytesPerSampleFrame
                                onProgress(((bytesRead * 95L) / totalPcmBytes).toInt().coerceIn(0, 95))
                            }
                        }
                    }

                    val outIdx = encoder.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            check(!muxerStarted)
                            audioTrackIdx = muxer.addTrack(encoder.outputFormat)
                            muxer.start()
                            muxerStarted = true
                        }
                        outIdx >= 0 -> {
                            val outBuf = encoder.getOutputBuffer(outIdx)!!
                            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                                info.size = 0
                            }
                            if (info.size > 0 && muxerStarted) {
                                outBuf.position(info.offset)
                                outBuf.limit(info.offset + info.size)
                                muxer.writeSampleData(audioTrackIdx, outBuf, info)
                            }
                            encoder.releaseOutputBuffer(outIdx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                outputDone = true
                            }
                        }
                    }
                }
            }

            if (muxerStarted) muxer.stop()
            muxer.release()
            muxer = null
            if (outputM4a.exists()) outputM4a.delete()
            check(tmpOut.renameTo(outputM4a)) { "Failed to finalize exported M4A" }
            onProgress(100)
            outputM4a
        } finally {
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { muxer?.release() }
            if (tmpOut.exists()) tmpOut.delete()
        }
    }

    suspend fun exportTextToMarkdown(
        context: Context,
        title: String,
        markdownBody: String
    ): File = withContext(Dispatchers.IO) {
        val safe = ExportFormatters.sanitizeExportFileName(title, "omni_export")
        val out = File(exportDir(context), "${safe}_${System.currentTimeMillis() / 1000}.md")
        out.writeText(markdownBody, Charsets.UTF_8)
        out
    }

    /**
     * Renders clean paginated A4 PDF using Android's native [PdfDocument] and [StaticLayout]
     * so CJK and Latin text wrap naturally with zero third-party PDF libraries.
     */
    suspend fun exportTextToPdf(
        context: Context,
        title: String,
        body: String
    ): File = withContext(Dispatchers.IO) {
        val safe = ExportFormatters.sanitizeExportFileName(title, "omni_export")
        val out = File(exportDir(context), "${safe}_${System.currentTimeMillis() / 1000}.pdf")

        val pageWidth = 595 // A4 at 72 dpi
        val pageHeight = 842
        val marginX = 48f
        val marginTop = 52f
        val marginBottom = 52f
        val contentWidth = (pageWidth - marginX * 2).toInt()
        val usableHeight = (pageHeight - marginTop - marginBottom).toInt()

        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(20, 20, 24)
            textSize = 16f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(36, 36, 42)
            textSize = 10.5f
            typeface = Typeface.DEFAULT
        }
        val footerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(130, 130, 140)
            textSize = 8.5f
        }

        val fullText = body.trim().ifBlank { title }
        val layout = StaticLayout.Builder.obtain(fullText, 0, fullText.length, bodyPaint, contentWidth)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(3f, 1.15f)
            .setIncludePad(false)
            .build()

        val document = PdfDocument()
        try {
            var lineIdx = 0
            var pageNumber = 1
            val totalLines = layout.lineCount

            while (lineIdx < totalLines || pageNumber == 1) {
                val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, pageNumber).create()
                val page = document.startPage(pageInfo)
                val canvas = page.canvas
                canvas.drawColor(Color.WHITE)

                var yOffset = marginTop
                if (pageNumber == 1 && title.isNotBlank()) {
                    val titleLayout = StaticLayout.Builder.obtain(title, 0, title.length, titlePaint, contentWidth)
                        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                        .setLineSpacing(2f, 1.1f)
                        .build()
                    canvas.save()
                    canvas.translate(marginX, yOffset)
                    titleLayout.draw(canvas)
                    canvas.restore()
                    yOffset += titleLayout.height + 16f
                }

                val maxY = pageHeight - marginBottom
                var drawnOnPage = 0
                while (lineIdx < totalLines) {
                    val lineHeight = (layout.getLineBottom(lineIdx) - layout.getLineTop(lineIdx)).toFloat()
                    if (yOffset + lineHeight > maxY && drawnOnPage > 0) break
                    val start = layout.getLineStart(lineIdx)
                    val end = layout.getLineEnd(lineIdx)
                    val baseline = yOffset - layout.getLineAscent(lineIdx)
                    canvas.drawText(fullText, start, end, marginX, baseline, bodyPaint)
                    yOffset += lineHeight
                    lineIdx++
                    drawnOnPage++
                }

                canvas.drawText("Omni Pocket · $pageNumber", marginX, pageHeight - 24f, footerPaint)
                document.finishPage(page)
                pageNumber++
                if (lineIdx >= totalLines) break
            }

            FileOutputStream(out).use { fos ->
                document.writeTo(fos)
            }
            out
        } finally {
            document.close()
        }
    }

    private fun encodeAudioTrackToAac(
        inputWebm: File,
        audioTrackIndex: Int,
        inputFormat: MediaFormat
    ): Pair<MediaFormat, List<EncodedSample>>? {
        val inputMime = inputFormat.getString(MediaFormat.KEY_MIME).orEmpty()
        val sampleRate = if (inputFormat.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
            inputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
        } else 32000
        val channels = if (inputFormat.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
            inputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
        } else 2

        val extractor = MediaExtractor()
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        var decoder: MediaCodec? = null
        val samples = ArrayList<EncodedSample>()
        var outputFormat: MediaFormat? = null

        try {
            extractor.setDataSource(inputWebm.absolutePath)
            extractor.selectTrack(audioTrackIndex)

            val encFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            }
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            val isRawPcm = inputMime == MediaFormat.MIMETYPE_AUDIO_RAW
            if (!isRawPcm) {
                decoder = MediaCodec.createDecoderByType(inputMime)
                decoder.configure(inputFormat, null, null, 0)
                decoder.start()
            }

            val encInfo = MediaCodec.BufferInfo()
            val decInfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var encoderInputDone = false
            var encoderOutputDone = false
            var rawPcmBytesFed = 0L
            val bytesPerFrame = channels * 2

            while (!encoderOutputDone) {
                if (isRawPcm) {
                    if (!encoderInputDone) {
                        val inIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
                        if (inIdx >= 0) {
                            val inBuf = encoder.getInputBuffer(inIdx)!!
                            inBuf.clear()
                            val size = extractor.readSampleData(inBuf, 0)
                            if (size < 0) {
                                val eosPts = (rawPcmBytesFed / bytesPerFrame) * 1_000_000L / sampleRate
                                encoder.queueInputBuffer(inIdx, 0, 0, eosPts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                encoderInputDone = true
                            } else {
                                val samplePts = extractor.sampleTime.takeIf { it >= 0L }
                                    ?: ((rawPcmBytesFed / bytesPerFrame) * 1_000_000L / sampleRate)
                                encoder.queueInputBuffer(inIdx, 0, size, samplePts, 0)
                                rawPcmBytesFed += size
                                extractor.advance()
                            }
                        }
                    }
                } else {
                    val dec = decoder!!
                    if (!extractorDone) {
                        val dIn = dec.dequeueInputBuffer(TIMEOUT_US)
                        if (dIn >= 0) {
                            val buf = dec.getInputBuffer(dIn)!!
                            val size = extractor.readSampleData(buf, 0)
                            if (size < 0) {
                                dec.queueInputBuffer(dIn, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                extractorDone = true
                            } else {
                                dec.queueInputBuffer(dIn, 0, size, extractor.sampleTime.coerceAtLeast(0L), 0)
                                extractor.advance()
                            }
                        }
                    }
                    if (!encoderInputDone) {
                        val dOut = dec.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                        if (dOut >= 0) {
                            val pcmBuf = dec.getOutputBuffer(dOut)!!
                            val isEos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                            if (decInfo.size > 0) {
                                var offset = decInfo.offset
                                val end = decInfo.offset + decInfo.size
                                while (offset < end) {
                                    val eIn = encoder.dequeueInputBuffer(TIMEOUT_US)
                                    if (eIn >= 0) {
                                        val dst = encoder.getInputBuffer(eIn)!!
                                        dst.clear()
                                        val take = (end - offset).coerceAtMost(dst.capacity())
                                        pcmBuf.position(offset)
                                        pcmBuf.limit(offset + take)
                                        dst.put(pcmBuf)
                                        encoder.queueInputBuffer(eIn, 0, take, decInfo.presentationTimeUs, 0)
                                        offset += take
                                    }
                                }
                            }
                            dec.releaseOutputBuffer(dOut, false)
                            if (isEos) {
                                var queued = false
                                while (!queued) {
                                    val eIn = encoder.dequeueInputBuffer(TIMEOUT_US)
                                    if (eIn >= 0) {
                                        encoder.queueInputBuffer(eIn, 0, 0, decInfo.presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                        queued = true
                                    }
                                }
                                encoderInputDone = true
                            }
                        }
                    }
                }

                val eOut = encoder.dequeueOutputBuffer(encInfo, TIMEOUT_US)
                when {
                    eOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        outputFormat = encoder.outputFormat
                    }
                    eOut >= 0 -> {
                        val outBuf = encoder.getOutputBuffer(eOut)!!
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && encInfo.size > 0) {
                            val copy = ByteArray(encInfo.size)
                            outBuf.position(encInfo.offset)
                            outBuf.limit(encInfo.offset + encInfo.size)
                            outBuf.get(copy)
                            samples.add(EncodedSample(copy, encInfo.presentationTimeUs, encInfo.flags))
                        }
                        encoder.releaseOutputBuffer(eOut, false)
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderOutputDone = true
                        }
                    }
                }
            }

            val fmt = outputFormat ?: return null
            if (samples.isEmpty()) return null
            return fmt to samples
        } catch (_: Throwable) {
            // If an unusual audio codec fails, fall back gracefully to exporting the video stream rather than failing entirely.
            return null
        } finally {
            runCatching { decoder?.stop() }
            runCatching { decoder?.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            extractor.release()
        }
    }

    private fun transcodeVideoAndMux(
        inputWebm: File,
        videoTrackIndex: Int,
        videoInputFormat: MediaFormat,
        audioFormat: MediaFormat?,
        audioSamples: List<EncodedSample>,
        outputFile: File,
        onProgress: (Int) -> Unit
    ) {
        val width = videoInputFormat.getInteger(MediaFormat.KEY_WIDTH)
        val height = videoInputFormat.getInteger(MediaFormat.KEY_HEIGHT)
        val fps = if (videoInputFormat.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            runCatching { videoInputFormat.getInteger(MediaFormat.KEY_FRAME_RATE) }
                .getOrElse { runCatching { videoInputFormat.getFloat(MediaFormat.KEY_FRAME_RATE).toInt() }.getOrDefault(24) }
                .coerceIn(8, 60)
        } else 24
        val durationUs = if (videoInputFormat.containsKey(MediaFormat.KEY_DURATION)) {
            videoInputFormat.getLong(MediaFormat.KEY_DURATION).coerceAtLeast(1L)
        } else 1_000_000L
        val videoMime = videoInputFormat.getString(MediaFormat.KEY_MIME).orEmpty()

        val extractor = MediaExtractor()
        val decoder = MediaCodec.createDecoderByType(videoMime)
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

        var muxerStarted = false
        var muxVideoTrack = -1
        var muxAudioTrack = -1

        try {
            extractor.setDataSource(inputWebm.absolutePath)
            extractor.selectTrack(videoTrackIndex)

            val encColorFormat = chooseEncoderColorFormat(encoder)
            val bitrate = (width.toLong() * height.toLong() * fps.toLong() / 3L).coerceIn(1_500_000L, 6_000_000L).toInt()
            val encFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, encColorFormat)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            }
            encoder.configure(encFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            videoInputFormat.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            )
            decoder.configure(videoInputFormat, null, null, 0)
            decoder.start()

            val decInfo = MediaCodec.BufferInfo()
            val encInfo = MediaCodec.BufferInfo()
            var extractorDone = false
            var decoderDone = false
            var encoderDone = false
            var framesEncoded = 0
            val defaultFrameStepUs = 1_000_000L / fps
            var lastVideoPtsUs = -1L

            while (!encoderDone) {
                if (!extractorDone) {
                    val inIdx = decoder.dequeueInputBuffer(TIMEOUT_US)
                    if (inIdx >= 0) {
                        val inBuf = decoder.getInputBuffer(inIdx)!!
                        val sampleSize = extractor.readSampleData(inBuf, 0)
                        if (sampleSize < 0) {
                            decoder.queueInputBuffer(inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            extractorDone = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            decoder.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                if (!decoderDone) {
                    val outIdx = decoder.dequeueOutputBuffer(decInfo, TIMEOUT_US)
                    if (outIdx >= 0) {
                        val isEos = decInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                        if (decInfo.size > 0) {
                            val srcImage = decoder.getOutputImage(outIdx)
                            val ptsUs = if (decInfo.presentationTimeUs > lastVideoPtsUs) {
                                decInfo.presentationTimeUs
                            } else {
                                lastVideoPtsUs + defaultFrameStepUs
                            }
                            lastVideoPtsUs = ptsUs
                            if (srcImage != null) {
                                try {
                                    feedFrameToEncoder(encoder, srcImage, encColorFormat, width, height, ptsUs)
                                    framesEncoded++
                                    onProgress(((ptsUs * 95L) / durationUs).toInt().coerceIn(0, 95))
                                } finally {
                                    srcImage.close()
                                }
                            }
                        }
                        decoder.releaseOutputBuffer(outIdx, false)
                        if (isEos) {
                            var queuedEos = false
                            while (!queuedEos) {
                                val encIn = encoder.dequeueInputBuffer(TIMEOUT_US)
                                if (encIn >= 0) {
                                    encoder.queueInputBuffer(
                                        encIn,
                                        0,
                                        0,
                                        (lastVideoPtsUs + defaultFrameStepUs).coerceAtLeast(0L),
                                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                    )
                                    queuedEos = true
                                }
                            }
                            decoderDone = true
                        }
                    }
                }

                while (true) {
                    val encOut = encoder.dequeueOutputBuffer(encInfo, if (decoderDone) TIMEOUT_US else 0L)
                    if (encOut == MediaCodec.INFO_TRY_AGAIN_LATER) break
                    if (encOut == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        check(!muxerStarted)
                        muxVideoTrack = muxer.addTrack(encoder.outputFormat)
                        if (audioFormat != null && audioSamples.isNotEmpty()) {
                            muxAudioTrack = muxer.addTrack(audioFormat)
                        }
                        muxer.start()
                        muxerStarted = true
                    } else if (encOut >= 0) {
                        val outBuf = encoder.getOutputBuffer(encOut)!!
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                            encInfo.size = 0
                        }
                        if (encInfo.size > 0 && muxerStarted) {
                            outBuf.position(encInfo.offset)
                            outBuf.limit(encInfo.offset + encInfo.size)
                            muxer.writeSampleData(muxVideoTrack, outBuf, encInfo)
                        }
                        encoder.releaseOutputBuffer(encOut, false)
                        if (encInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            encoderDone = true
                            break
                        }
                    }
                }
            }

            check(muxerStarted && framesEncoded > 0) { "Video encoder produced no output frames" }

            // Write buffered AAC audio samples cleanly after video track finishes.
            if (muxAudioTrack >= 0) {
                val audioInfo = MediaCodec.BufferInfo()
                for (sample in audioSamples) {
                    val buf = ByteBuffer.wrap(sample.data)
                    audioInfo.offset = 0
                    audioInfo.size = sample.data.size
                    audioInfo.presentationTimeUs = sample.presentationTimeUs
                    audioInfo.flags = sample.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv()
                    muxer.writeSampleData(muxAudioTrack, buf, audioInfo)
                }
            }

            muxer.stop()
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { encoder.stop() }
            runCatching { encoder.release() }
            runCatching { muxer.release() }
            extractor.release()
        }
    }

    private fun chooseEncoderColorFormat(encoder: MediaCodec): Int {
        val caps = runCatching { encoder.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC) }.getOrNull()
        val formats = caps?.colorFormats ?: intArrayOf()
        return when {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible in formats ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar in formats ->
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
            else -> MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
        }
    }

    private fun feedFrameToEncoder(
        encoder: MediaCodec,
        srcImage: Image,
        encColorFormat: Int,
        width: Int,
        height: Int,
        ptsUs: Long
    ) {
        var encInIdx = -1
        while (encInIdx < 0) {
            encInIdx = encoder.dequeueInputBuffer(TIMEOUT_US)
        }
        val dstImage = runCatching { encoder.getInputImage(encInIdx) }.getOrNull()
        val expectedBytes = width * height * 3 / 2
        if (dstImage != null) {
            try {
                copyYuv420Image(srcImage, dstImage, width, height)
            } finally {
                dstImage.close()
            }
            encoder.queueInputBuffer(encInIdx, 0, expectedBytes, ptsUs, 0)
        } else {
            val dstBuf = encoder.getInputBuffer(encInIdx)!!
            dstBuf.clear()
            val semiPlanar = encColorFormat != MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
            packYuv420ToBuffer(srcImage, dstBuf, width, height, semiPlanar)
            encoder.queueInputBuffer(encInIdx, 0, expectedBytes, ptsUs, 0)
        }
    }

    private fun copyYuv420Image(src: Image, dst: Image, width: Int, height: Int) {
        val crop = src.cropRect
        val w = width.coerceAtMost(crop.width())
        val h = height.coerceAtMost(crop.height())
        for (p in 0 until 3) {
            val planeW = if (p == 0) w else w / 2
            val planeH = if (p == 0) h else h / 2
            val srcPlane = src.planes[p]
            val dstPlane = dst.planes[p]
            val srcBuf = srcPlane.buffer
            val dstBuf = dstPlane.buffer
            val srcRowStride = srcPlane.rowStride
            val srcPixelStride = srcPlane.pixelStride
            val dstRowStride = dstPlane.rowStride
            val dstPixelStride = dstPlane.pixelStride
            val cropLeft = if (p == 0) crop.left else crop.left / 2
            val cropTop = if (p == 0) crop.top else crop.top / 2

            if (srcPixelStride == 1 && dstPixelStride == 1) {
                val rowBytes = ByteArray(planeW)
                for (row in 0 until planeH) {
                    srcBuf.position((cropTop + row) * srcRowStride + cropLeft)
                    srcBuf.get(rowBytes, 0, planeW)
                    dstBuf.position(row * dstRowStride)
                    dstBuf.put(rowBytes, 0, planeW)
                }
            } else {
                for (row in 0 until planeH) {
                    val srcRowBase = (cropTop + row) * srcRowStride + cropLeft * srcPixelStride
                    val dstRowBase = row * dstRowStride
                    for (col in 0 until planeW) {
                        dstBuf.put(dstRowBase + col * dstPixelStride, srcBuf.get(srcRowBase + col * srcPixelStride))
                    }
                }
            }
        }
    }

    private fun packYuv420ToBuffer(src: Image, dst: ByteBuffer, width: Int, height: Int, semiPlanarNv12: Boolean) {
        val crop = src.cropRect
        val w = width.coerceAtMost(crop.width())
        val h = height.coerceAtMost(crop.height())

        val yPlane = src.planes[0]
        val yBuf = yPlane.buffer
        val rowBytes = ByteArray(w)
        for (row in 0 until h) {
            yBuf.position((crop.top + row) * yPlane.rowStride + crop.left)
            yBuf.get(rowBytes, 0, w)
            dst.put(rowBytes, 0, w)
        }

        val uPlane = src.planes[1]
        val vPlane = src.planes[2]
        val uBuf = uPlane.buffer
        val vBuf = vPlane.buffer
        val cw = w / 2
        val ch = h / 2
        val cLeft = crop.left / 2
        val cTop = crop.top / 2

        if (semiPlanarNv12) {
            for (row in 0 until ch) {
                val uBase = (cTop + row) * uPlane.rowStride + cLeft * uPlane.pixelStride
                val vBase = (cTop + row) * vPlane.rowStride + cLeft * vPlane.pixelStride
                for (col in 0 until cw) {
                    dst.put(uBuf.get(uBase + col * uPlane.pixelStride))
                    dst.put(vBuf.get(vBase + col * vPlane.pixelStride))
                }
            }
        } else {
            for (row in 0 until ch) {
                val uBase = (cTop + row) * uPlane.rowStride + cLeft * uPlane.pixelStride
                for (col in 0 until cw) dst.put(uBuf.get(uBase + col * uPlane.pixelStride))
            }
            for (row in 0 until ch) {
                val vBase = (cTop + row) * vPlane.rowStride + cLeft * vPlane.pixelStride
                for (col in 0 until cw) dst.put(vBuf.get(vBase + col * vPlane.pixelStride))
            }
        }
    }
}
