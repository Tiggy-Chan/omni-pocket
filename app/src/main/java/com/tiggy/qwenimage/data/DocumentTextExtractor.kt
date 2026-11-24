package com.tiggy.qwenimage.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.Locale
import java.util.zip.Inflater
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

enum class DocumentFormat {
    PlainText,
    Markdown,
    Html,
    Docx,
    Pdf
}

data class ExtractedDocument(
    val fileName: String,
    val format: DocumentFormat,
    val text: String,
    val originalBytes: Long,
    val truncated: Boolean = false,
    val pageCount: Int = 0,
    val isLikelyScannedPdf: Boolean = false
)

/**
 * Pure on-device document text extractor with zero third-party native or JAR dependencies.
 * Supports UTF-8/GB18030/UTF-16 plain text, Markdown, CSV/JSON/code, HTML, DOCX (OOXML),
 * and PDF text streams (including FlateDecode and ToUnicode CMap).
 */
object DocumentTextExtractor {
    const val MAX_FILE_BYTES: Long = 24L * 1024L * 1024L
    const val MAX_EXTRACTED_CHARS: Int = 360_000

    fun extractFromUri(context: Context, uri: Uri): ExtractedDocument {
        val resolver = context.contentResolver
        var fileName = "document.txt"
        var declaredSize = -1L
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (nameIdx >= 0) c.getString(nameIdx)?.takeIf { it.isNotBlank() }?.let { fileName = it }
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) declaredSize = c.getLong(sizeIdx)
            }
        }
        if (declaredSize > MAX_FILE_BYTES) {
            throw IllegalArgumentException("File exceeds ${MAX_FILE_BYTES / (1024 * 1024)} MB limit")
        }
        val mime = resolver.getType(uri)
        val bytes = resolver.openInputStream(uri)?.use { input ->
            readBounded(input, MAX_FILE_BYTES)
        } ?: throw IllegalStateException("Cannot open selected document")
        return extractFromBytes(fileName, mime, bytes)
    }

    fun extractFromBytes(fileName: String, mimeType: String?, bytes: ByteArray): ExtractedDocument {
        val format = detectFormat(fileName, mimeType, bytes)
        return when (format) {
            DocumentFormat.Pdf -> extractPdf(fileName, bytes)
            DocumentFormat.Docx -> extractDocx(fileName, bytes)
            DocumentFormat.Html -> {
                val raw = decodeBestCharset(bytes)
                val cleaned = stripHtml(raw)
                finalize(fileName, format, cleaned, bytes.size.toLong())
            }
            DocumentFormat.Markdown, DocumentFormat.PlainText -> {
                val raw = decodeBestCharset(bytes)
                finalize(fileName, format, normalizeLineEndings(raw), bytes.size.toLong())
            }
        }
    }

    fun detectFormat(fileName: String, mimeType: String?, bytes: ByteArray): DocumentFormat {
        val lowerName = fileName.lowercase(Locale.US)
        val lowerMime = mimeType.orEmpty().lowercase(Locale.US)
        if (lowerName.endsWith(".pdf") || lowerMime == "application/pdf" ||
            (bytes.size >= 5 && bytes[0] == '%'.code.toByte() && bytes[1] == 'P'.code.toByte() &&
                bytes[2] == 'D'.code.toByte() && bytes[3] == 'F'.code.toByte() && bytes[4] == '-'.code.toByte())
        ) {
            return DocumentFormat.Pdf
        }
        if (lowerName.endsWith(".docx") ||
            lowerMime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        ) {
            return DocumentFormat.Docx
        }
        if (lowerName.endsWith(".html") || lowerName.endsWith(".htm") || lowerMime == "text/html") {
            return DocumentFormat.Html
        }
        if (lowerName.endsWith(".md") || lowerName.endsWith(".markdown") || lowerMime == "text/markdown") {
            return DocumentFormat.Markdown
        }
        // Reject obvious binary archives/media that are neither PDF nor DOCX.
        if (bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
        ) {
            // Might be a .docx with missing extension — probe for word/document.xml
            if (zipContainsEntry(bytes, "word/document.xml")) {
                return DocumentFormat.Docx
            }
        }
        return DocumentFormat.PlainText
    }

    private fun readBounded(input: java.io.InputStream, maxBytes: Long): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buf)
            if (n <= 0) break
            total += n
            if (total > maxBytes) {
                throw IllegalArgumentException("File exceeds ${maxBytes / (1024 * 1024)} MB limit")
            }
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    private fun zipContainsEntry(bytes: ByteArray, entryName: String): Boolean = runCatching {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.name == entryName) return true
            }
        }
        false
    }.getOrDefault(false)

    internal fun decodeBestCharset(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        // BOM checks
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        }
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) {
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        }
        // Strict UTF-8 first
        runCatching {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }
        // Fallback to GB18030 for legacy Chinese .txt files if supported
        runCatching {
            val gb = Charset.forName("GB18030")
            val decoder = gb.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }
        return String(bytes, Charsets.UTF_8)
    }

    private fun finalize(
        fileName: String,
        format: DocumentFormat,
        rawText: String,
        originalBytes: Long,
        pageCount: Int = 0,
        likelyScanned: Boolean = false
    ): ExtractedDocument {
        val cleaned = rawText.trim()
        val truncated = cleaned.length > MAX_EXTRACTED_CHARS
        val bounded = if (truncated) cleaned.substring(0, MAX_EXTRACTED_CHARS) else cleaned
        return ExtractedDocument(
            fileName = fileName,
            format = format,
            text = bounded,
            originalBytes = originalBytes,
            truncated = truncated,
            pageCount = pageCount,
            isLikelyScannedPdf = likelyScanned
        )
    }

    private fun normalizeLineEndings(text: String): String =
        text.replace("\r\n", "\n").replace('\r', '\n').replace(Regex("\n{3,}"), "\n\n")

    internal fun stripHtml(html: String): String {
        var s = html
            .replace(Regex("(?is)<script[^>]*>.*?</script>"), " ")
            .replace(Regex("(?is)<style[^>]*>.*?</style>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
            .replace(Regex("(?i)<(br|/p|/div|/li|/tr|/h[1-6]|/section|/article)\\b[^>]*>"), "\n")
            .replace(Regex("(?i)<li\\b[^>]*>"), "\n• ")
            .replace(Regex("<[^>]+>"), " ")
        s = decodeHtmlEntities(s)
        val lines = s.split('\n').map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }
        return lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun decodeHtmlEntities(input: String): String {
        var s = input
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
        s = Regex("&#x([0-9a-fA-F]+);").replace(s) { m ->
            val cp = m.groupValues[1].toIntOrNull(16)
            if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else m.value
        }
        s = Regex("&#(\\d+);").replace(s) { m ->
            val cp = m.groupValues[1].toIntOrNull()
            if (cp != null && Character.isValidCodePoint(cp)) String(Character.toChars(cp)) else m.value
        }
        return s
    }

    private fun extractDocx(fileName: String, bytes: ByteArray): ExtractedDocument {
        var documentXml: ByteArray? = null
        ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
            while (true) {
                val entry = zis.nextEntry ?: break
                if (entry.name == "word/document.xml") {
                    documentXml = zis.readBytes()
                    break
                }
            }
        }
        val xmlBytes = documentXml ?: throw IllegalArgumentException("DOCX archive missing word/document.xml")
        val text = parseDocxXml(xmlBytes)
        return finalize(fileName, DocumentFormat.Docx, text, bytes.size.toLong())
    }

    internal fun parseDocxXml(xmlBytes: ByteArray): String {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(xmlBytes))
        val out = StringBuilder()

        fun walk(node: org.w3c.dom.Node) {
            val local = node.localName ?: node.nodeName.substringAfter(':')
            when (local) {
                "t" -> out.append(node.textContent.orEmpty())
                "tab" -> out.append('\t')
                "br", "cr" -> out.append('\n')
                "p" -> {
                    val children = node.childNodes
                    val beforeLen = out.length
                    for (i in 0 until children.length) walk(children.item(i))
                    if (out.length > beforeLen) out.append('\n')
                }
                "tr" -> {
                    val children = node.childNodes
                    for (i in 0 until children.length) walk(children.item(i))
                    if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                }
                "tc" -> {
                    val children = node.childNodes
                    val beforeLen = out.length
                    for (i in 0 until children.length) walk(children.item(i))
                    if (out.length > beforeLen) {
                        if (out.last() == '\n') out.setCharAt(out.lastIndex, '\t')
                        else out.append('\t')
                    }
                }
                else -> {
                    val children = node.childNodes
                    for (i in 0 until children.length) walk(children.item(i))
                }
            }
        }

        walk(doc.documentElement)
        return normalizeLineEndings(out.toString())
    }

    // ---------------------------------------------------------------------
    // Pure-Kotlin PDF text extractor (supports FlateDecode + ToUnicode CMap)
    // ---------------------------------------------------------------------

    private data class PdfCMap(
        val single: Map<Int, String> = emptyMap(),
        val ranges: List<Triple<Int, Int, Int>> = emptyList(),
        val codeByteLength: Int = 2
    ) {
        fun decodeHex(hex: String): String {
            val clean = hex.filter { !it.isWhitespace() }
            if (clean.isEmpty()) return ""
            // UTF-16BE BOM inside hex string
            if (clean.length >= 4 && clean.startsWith("FEFF", ignoreCase = true)) {
                val rawBytes = hexToBytes(clean.substring(4))
                return String(rawBytes, Charsets.UTF_16BE)
            }
            val step = (codeByteLength * 2).coerceAtLeast(2)
            val sb = StringBuilder()
            if (single.isNotEmpty() || ranges.isNotEmpty()) {
                var i = 0
                while (i + step <= clean.length) {
                    val code = clean.substring(i, i + step).toIntOrNull(16)
                    if (code != null) {
                        val mapped = lookup(code)
                        if (mapped != null) sb.append(mapped)
                        else if (code in 0x20..0x7E) sb.append(code.toChar())
                    }
                    i += step
                }
                if (sb.isNotEmpty()) return sb.toString()
            }
            // Fallback: try 2-byte UTF-16BE if looks like BMP text, otherwise Latin-1
            val rawBytes = hexToBytes(clean)
            if (rawBytes.size >= 2 && rawBytes.size % 2 == 0) {
                var zeroHigh = 0
                var cjkLike = 0
                for (i in rawBytes.indices step 2) {
                    val hi = rawBytes[i].toInt() and 0xFF
                    if (hi == 0) zeroHigh++
                    if (hi in 0x4E..0x9F || hi in 0x30..0x33 || hi == 0xFF) cjkLike++
                }
                if (zeroHigh * 2 >= rawBytes.size / 2 || cjkLike > 0) {
                    return String(rawBytes, Charsets.UTF_16BE)
                }
            }
            return String(rawBytes, Charsets.ISO_8859_1)
        }

        private fun lookup(code: Int): String? {
            single[code]?.let { return it }
            for ((start, end, dstStart) in ranges) {
                if (code in start..end) {
                    val cp = dstStart + (code - start)
                    if (Character.isValidCodePoint(cp)) return String(Character.toChars(cp))
                }
            }
            return null
        }
    }

    private fun extractPdf(fileName: String, bytes: ByteArray): ExtractedDocument {
        val rawLatin = String(bytes, Charsets.ISO_8859_1)
        val pageCount = Regex("/Type\\s*/Page\\b").findAll(rawLatin).count().coerceAtLeast(1)
        val streams = extractPdfStreams(bytes, rawLatin)

        // First pass: collect ToUnicode CMaps across streams
        val mergedSingle = LinkedHashMap<Int, String>()
        val mergedRanges = ArrayList<Triple<Int, Int, Int>>()
        var detectedCodeBytes = 2
        val contentStreams = ArrayList<String>()

        for (streamText in streams) {
            if (streamText.contains("beginbfchar") || streamText.contains("beginbfrange")) {
                val cmap = parseToUnicodeCMap(streamText)
                mergedSingle.putAll(cmap.single)
                mergedRanges.addAll(cmap.ranges)
                detectedCodeBytes = cmap.codeByteLength
            } else if (streamText.contains("BT") && (streamText.contains("Tj") || streamText.contains("TJ") || streamText.contains("'"))) {
                contentStreams.add(streamText)
            }
        }
        // Also scan uncompressed content outside streams (some simple PDFs put BT/ET directly in streams without FlateDecode, already caught above).
        val globalCmap = PdfCMap(mergedSingle, mergedRanges, detectedCodeBytes)

        val extracted = StringBuilder()
        for (content in contentStreams) {
            val pageText = parsePdfContentStream(content, globalCmap)
            if (pageText.isNotBlank()) {
                if (extracted.isNotEmpty()) extracted.append("\n\n")
                extracted.append(pageText)
            }
            if (extracted.length >= MAX_EXTRACTED_CHARS + 4096) break
        }

        val cleaned = normalizeLineEndings(extracted.toString())
        val nonWs = cleaned.count { !it.isWhitespace() }
        val likelyScanned = nonWs < 16
        return finalize(
            fileName = fileName,
            format = DocumentFormat.Pdf,
            rawText = if (likelyScanned && nonWs == 0) "" else cleaned,
            originalBytes = bytes.size.toLong(),
            pageCount = pageCount,
            likelyScanned = likelyScanned
        )
    }

    private fun extractPdfStreams(bytes: ByteArray, rawLatin: String): List<String> {
        val results = ArrayList<String>()
        var searchPos = 0
        while (searchPos < bytes.size) {
            val streamIdx = rawLatin.indexOf("stream", searchPos)
            if (streamIdx < 0) break
            // Verify word boundary before "stream" (not "endstream")
            if (streamIdx >= 3 && rawLatin.substring(streamIdx - 3, streamIdx) == "end") {
                searchPos = streamIdx + 6
                continue
            }
            // Locate dictionary header immediately before "stream"
            val dictStart = rawLatin.lastIndexOf("<<", streamIdx).coerceAtLeast(searchPos)
            val dict = rawLatin.substring(dictStart, streamIdx)
            val isImage = dict.contains("/Subtype /Image") || dict.contains("/Subtype/Image")

            var dataStart = streamIdx + 6
            if (dataStart < bytes.size && bytes[dataStart] == '\r'.code.toByte()) dataStart++
            if (dataStart < bytes.size && bytes[dataStart] == '\n'.code.toByte()) dataStart++

            val endIdx = rawLatin.indexOf("endstream", dataStart)
            if (endIdx < 0) break
            var dataEnd = endIdx
            while (dataEnd > dataStart && (bytes[dataEnd - 1] == '\n'.code.toByte() || bytes[dataEnd - 1] == '\r'.code.toByte())) {
                dataEnd--
            }
            searchPos = endIdx + 9

            if (isImage || dataEnd <= dataStart) continue
            val isFlate = dict.contains("/FlateDecode")
            val isUnsupportedFilter = dict.contains("/DCTDecode") || dict.contains("/JPXDecode") ||
                dict.contains("/CCITTFaxDecode") || dict.contains("/JBIG2Decode")
            if (isUnsupportedFilter) continue

            val slice = bytes.copyOfRange(dataStart, dataEnd)
            val decoded = if (isFlate) inflateSafely(slice) else slice
            if (decoded != null && decoded.isNotEmpty()) {
                results.add(String(decoded, Charsets.ISO_8859_1))
            }
        }
        return results
    }

    private fun inflateSafely(data: ByteArray): ByteArray? {
        fun runInflater(nowrap: Boolean): ByteArray? = runCatching {
            val inflater = Inflater(nowrap)
            try {
                inflater.setInput(data)
                val out = ByteArrayOutputStream()
                val buf = ByteArray(16 * 1024)
                var total = 0
                val maxInflated = 4 * 1024 * 1024
                while (!inflater.finished() && total < maxInflated) {
                    val count = inflater.inflate(buf)
                    if (count <= 0) {
                        if (inflater.needsInput() || inflater.needsDictionary()) break
                        break
                    }
                    out.write(buf, 0, count)
                    total += count
                }
                if (out.size() > 0) out.toByteArray() else null
            } finally {
                inflater.end()
            }
        }.getOrNull()

        return runInflater(false) ?: runInflater(true)
    }

    private fun parseToUnicodeCMap(cmapText: String): PdfCMap {
        val single = LinkedHashMap<Int, String>()
        val ranges = ArrayList<Triple<Int, Int, Int>>()
        var codeBytes = 2

        val csRange = Regex("begincodespacerange(.*?)endcodespacerange", RegexOption.DOT_MATCHES_ALL)
            .find(cmapText)?.groupValues?.get(1)
        if (csRange != null) {
            val firstHex = Regex("<([0-9A-Fa-f]+)>").find(csRange)?.groupValues?.get(1)
            if (firstHex != null && firstHex.length >= 2) {
                codeBytes = (firstHex.length / 2).coerceIn(1, 2)
            }
        }

        Regex("beginbfchar(.*?)endbfchar", RegexOption.DOT_MATCHES_ALL).findAll(cmapText).forEach { block ->
            Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>").findAll(block.groupValues[1]).forEach { m ->
                val src = m.groupValues[1].toIntOrNull(16) ?: return@forEach
                val dst = decodeUtf16Hex(m.groupValues[2])
                if (dst.isNotEmpty()) single[src] = dst
            }
        }

        Regex("beginbfrange(.*?)endbfrange", RegexOption.DOT_MATCHES_ALL).findAll(cmapText).forEach { block ->
            val body = block.groupValues[1]
            // <src1> <src2> <dst>
            Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>").findAll(body).forEach { m ->
                val s1 = m.groupValues[1].toIntOrNull(16) ?: return@forEach
                val s2 = m.groupValues[2].toIntOrNull(16) ?: return@forEach
                val dst = m.groupValues[3].toIntOrNull(16) ?: return@forEach
                if (s2 >= s1 && s2 - s1 <= 4096) {
                    ranges.add(Triple(s1, s2, dst))
                }
            }
            // <src1> <src2> [ <dst1> <dst2> ... ]
            Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*\\[(.*?)]", RegexOption.DOT_MATCHES_ALL).findAll(body).forEach { m ->
                val s1 = m.groupValues[1].toIntOrNull(16) ?: return@forEach
                var code = s1
                Regex("<([0-9A-Fa-f]+)>").findAll(m.groupValues[3]).forEach { item ->
                    val dst = decodeUtf16Hex(item.groupValues[1])
                    if (dst.isNotEmpty()) single[code] = dst
                    code++
                }
            }
        }
        return PdfCMap(single, ranges, codeBytes)
    }

    private fun decodeUtf16Hex(hex: String): String {
        val bytes = hexToBytes(hex)
        if (bytes.isEmpty()) return ""
        return if (bytes.size == 1) {
            (bytes[0].toInt() and 0xFF).toChar().toString()
        } else {
            String(bytes, Charsets.UTF_16BE)
        }
    }

    private fun hexToBytes(hex: String): ByteArray {
        val padded = if (hex.length % 2 == 1) "${hex}0" else hex
        val out = ByteArray(padded.length / 2)
        for (i in out.indices) {
            val b = padded.substring(i * 2, i * 2 + 2).toIntOrNull(16) ?: 0
            out[i] = b.toByte()
        }
        return out
    }

    private fun parsePdfContentStream(content: String, cmap: PdfCMap): String {
        val out = StringBuilder()
        var pos = 0
        val len = content.length
        while (pos < len) {
            val bt = findPdfToken(content, "BT", pos)
            if (bt < 0) break
            val et = findPdfToken(content, "ET", bt + 2)
            val blockEnd = if (et >= 0) et else len
            val block = content.substring(bt + 2, blockEnd)
            val blockText = extractTextFromBtBlock(block, cmap)
            if (blockText.isNotBlank()) {
                if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                out.append(blockText)
            }
            pos = if (et >= 0) et + 2 else len
        }
        return out.toString()
    }

    private fun findPdfToken(text: String, token: String, fromIndex: Int): Int {
        var idx = text.indexOf(token, fromIndex)
        while (idx >= 0) {
            val beforeOk = idx == 0 || text[idx - 1].isWhitespace() || text[idx - 1] in "()<>[]{}/%"
            val afterIdx = idx + token.length
            val afterOk = afterIdx >= text.length || text[afterIdx].isWhitespace() || text[afterIdx] in "()<>[]{}/%"
            if (beforeOk && afterOk) return idx
            idx = text.indexOf(token, idx + 1)
        }
        return -1
    }

    private fun extractTextFromBtBlock(block: String, cmap: PdfCMap): String {
        val sb = StringBuilder()
        var i = 0
        val n = block.length
        while (i < n) {
            val c = block[i]
            when {
                c == '(' -> {
                    val (decoded, next) = readPdfLiteralString(block, i)
                    sb.append(decoded)
                    i = next
                }
                c == '<' && i + 1 < n && block[i + 1] != '<' -> {
                    val close = block.indexOf('>', i + 1)
                    if (close > i) {
                        val hex = block.substring(i + 1, close)
                        sb.append(cmap.decodeHex(hex))
                        i = close + 1
                    } else {
                        i++
                    }
                }
                c == '[' -> {
                    val (arrayText, next) = readPdfTjArray(block, i, cmap)
                    sb.append(arrayText)
                    i = next
                }
                c == 'T' && i + 1 < n && (block[i + 1] == 'd' || block[i + 1] == 'D' || block[i + 1] == '*') -> {
                    if (sb.isNotEmpty() && sb.last() != '\n') sb.append('\n')
                    i += 2
                }
                else -> i++
            }
        }
        return sb.toString()
            .replace(Regex("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]"), "")
            .trim()
    }

    private fun readPdfTjArray(block: String, start: Int, cmap: PdfCMap): Pair<String, Int> {
        val sb = StringBuilder()
        var i = start + 1
        val n = block.length
        while (i < n && block[i] != ']') {
            val c = block[i]
            when {
                c == '(' -> {
                    val (decoded, next) = readPdfLiteralString(block, i)
                    sb.append(decoded)
                    i = next
                }
                c == '<' && i + 1 < n && block[i + 1] != '<' -> {
                    val close = block.indexOf('>', i + 1)
                    if (close > i) {
                        sb.append(cmap.decodeHex(block.substring(i + 1, close)))
                        i = close + 1
                    } else {
                        i++
                    }
                }
                c == '-' || c.isDigit() -> {
                    val numStart = i
                    i++
                    while (i < n && (block[i].isDigit() || block[i] == '.')) i++
                    val kern = block.substring(numStart, i).toDoubleOrNull() ?: 0.0
                    if (kern < -180.0 && sb.isNotEmpty() && !sb.last().isWhitespace()) {
                        sb.append(' ')
                    }
                }
                else -> i++
            }
        }
        return sb.toString() to if (i < n) i + 1 else n
    }

    private fun readPdfLiteralString(text: String, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var i = start + 1
        var depth = 1
        val n = text.length
        while (i < n && depth > 0) {
            val c = text[i]
            if (c == '\\' && i + 1 < n) {
                val next = text[i + 1]
                when (next) {
                    'n' -> { sb.append('\n'); i += 2 }
                    'r' -> { sb.append('\r'); i += 2 }
                    't' -> { sb.append('\t'); i += 2 }
                    'b' -> { sb.append('\b'); i += 2 }
                    'f' -> { sb.append('\u000C'); i += 2 }
                    '(', ')', '\\' -> { sb.append(next); i += 2 }
                    '\r' -> { i += if (i + 2 < n && text[i + 2] == '\n') 3 else 2 }
                    '\n' -> { i += 2 }
                    in '0'..'7' -> {
                        var oct = next - '0'
                        var consumed = 2
                        if (i + 2 < n && text[i + 2] in '0'..'7') {
                            oct = oct * 8 + (text[i + 2] - '0')
                            consumed++
                            if (i + 3 < n && text[i + 3] in '0'..'7') {
                                oct = oct * 8 + (text[i + 3] - '0')
                                consumed++
                            }
                        }
                        sb.append((oct and 0xFF).toChar())
                        i += consumed
                    }
                    else -> { sb.append(next); i += 2 }
                }
            } else if (c == '(') {
                depth++
                sb.append(c)
                i++
            } else if (c == ')') {
                depth--
                if (depth > 0) sb.append(c)
                i++
            } else {
                sb.append(c)
                i++
            }
        }
        return sb.toString() to i
    }
}
