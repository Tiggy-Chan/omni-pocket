package com.tiggy.qwenimage.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentTextExtractorTest {

    @Test
    fun extractsPlainTextAndUtf8Bom() {
        val body = "\uFEFFOmni Pocket 本地阅读摘要测试\r\n第二行内容。"
        val doc = DocumentTextExtractor.extractFromBytes("notes.txt", "text/plain", body.toByteArray(Charsets.UTF_8))
        assertEquals(DocumentFormat.PlainText, doc.format)
        assertTrue(doc.text.startsWith("Omni Pocket 本地阅读摘要测试"))
        assertTrue(doc.text.contains("第二行内容"))
        assertFalse(doc.truncated)
    }

    @Test
    fun stripsHtmlScriptsAndEntities() {
        val html = """
            <html><head><style>body{color:red}</style><script>alert(1)</script></head>
            <body><h1>Title &amp; Overview</h1><p>Line 1 &lt;ok&gt;</p><ul><li>Item A</li><li>Item B</li></ul></body></html>
        """.trimIndent()
        val doc = DocumentTextExtractor.extractFromBytes("page.html", "text/html", html.toByteArray())
        assertEquals(DocumentFormat.Html, doc.format)
        assertTrue(doc.text.contains("Title & Overview"))
        assertTrue(doc.text.contains("Line 1 <ok>"))
        assertTrue(doc.text.contains("Item A"))
        assertFalse(doc.text.contains("alert"))
    }

    @Test
    fun extractsDocxParagraphsAndTables() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>第一章：端侧推理</w:t></w:r></w:p>
                <w:p><w:r><w:t>完全离线运行，</w:t></w:r><w:r><w:t>零云端上传。</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zos ->
            zos.putNextEntry(ZipEntry("word/document.xml"))
            zos.write(xml.toByteArray(Charsets.UTF_8))
            zos.closeEntry()
        }
        val doc = DocumentTextExtractor.extractFromBytes(
            "report.docx",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            baos.toByteArray()
        )
        assertEquals(DocumentFormat.Docx, doc.format)
        assertTrue(doc.text.contains("第一章：端侧推理"))
        assertTrue(doc.text.contains("完全离线运行，零云端上传。"))
    }

    @Test
    fun extractsFlateDecodedPdfWithToUnicodeCMap() {
        val cmap = """
            begincodespacerange
            <0001> <0004>
            endcodespacerange
            beginbfchar
            <0001> <7AEF>
            <0002> <4FA7>
            <0003> <6458>
            <0004> <8981>
            endbfchar
        """.trimIndent()
        val content = "BT /F1 12 Tf (Hello Omni Pocket ) Tj <0001000200030004> Tj ET"
        val deflated = deflate(content.toByteArray(Charsets.ISO_8859_1))

        val pdf = ByteArrayOutputStream()
        pdf.write("%PDF-1.4\n".toByteArray(Charsets.ISO_8859_1))
        pdf.write("1 0 obj << /Type /Page >> endobj\n".toByteArray(Charsets.ISO_8859_1))
        pdf.write("2 0 obj << /Length ${cmap.length} >>\nstream\n$cmap\nendstream\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        pdf.write("3 0 obj << /Filter /FlateDecode /Length ${deflated.size} >>\nstream\n".toByteArray(Charsets.ISO_8859_1))
        pdf.write(deflated)
        pdf.write("\nendstream\nendobj\n%%EOF".toByteArray(Charsets.ISO_8859_1))

        val doc = DocumentTextExtractor.extractFromBytes("sample.pdf", "application/pdf", pdf.toByteArray())
        assertEquals(DocumentFormat.Pdf, doc.format)
        assertTrue("Expected English text, got: ${doc.text}", doc.text.contains("Hello Omni Pocket"))
        assertTrue("Expected Chinese CMap text, got: ${doc.text}", doc.text.contains("端侧摘要"))
        assertFalse(doc.isLikelyScannedPdf)
    }

    @Test
    fun flagsImageOnlyPdfAsLikelyScanned() {
        val pdf = "%PDF-1.4\n1 0 obj << /Type /Page >> endobj\n2 0 obj << /Subtype /Image /Length 4 >>\nstream\n1234\nendstream\nendobj\n%%EOF"
        val doc = DocumentTextExtractor.extractFromBytes("scan.pdf", "application/pdf", pdf.toByteArray(Charsets.ISO_8859_1))
        assertEquals(DocumentFormat.Pdf, doc.format)
        assertTrue(doc.isLikelyScannedPdf)
    }

    private fun deflate(input: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(input)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!deflater.finished()) {
            val n = deflater.deflate(buf)
            if (n > 0) out.write(buf, 0, n)
        }
        deflater.end()
        return out.toByteArray()
    }
}
