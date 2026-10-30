package com.tiggy.qwenimage.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private sealed interface LlmBlock {
    data class Paragraph(val text: String) : LlmBlock
    data class Heading(val text: String, val level: Int) : LlmBlock
    data class ListItem(val text: String, val marker: String) : LlmBlock
    data class Code(val text: String) : LlmBlock
}

@Composable
fun LlmMarkdown(text: String, modifier: Modifier = Modifier, color: Color = OmniColors.Ink) {
    val blocks = remember(text) { parseBlocks(text) }
    SelectionContainer(modifier) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { block ->
                when (block) {
                    is LlmBlock.Paragraph -> InlineMarkdown(block.text, color, MaterialTheme.typography.bodyLarge)
                    is LlmBlock.Heading -> InlineMarkdown(block.text, color,
                        if (block.level == 1) MaterialTheme.typography.titleLarge else MaterialTheme.typography.titleMedium)
                    is LlmBlock.ListItem -> Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(block.marker, color = OmniColors.Muted, style = MaterialTheme.typography.bodyLarge)
                        InlineMarkdown(block.text, color, MaterialTheme.typography.bodyLarge)
                    }
                    is LlmBlock.Code -> Text(
                        block.text,
                        color = OmniColors.Ink,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        lineHeight = 20.sp,
                        modifier = Modifier.fillMaxWidth().background(OmniColors.Field, RoundedCornerShape(12.dp))
                            .horizontalScroll(rememberScrollState()).padding(14.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun InlineMarkdown(text: String, color: Color, style: androidx.compose.ui.text.TextStyle) {
    val codeBackground = OmniColors.Selected
    val linkColor = OmniColors.Accent
    Text(text = remember(text, codeBackground, linkColor) { inlineMarkdown(text, codeBackground, linkColor) },
        style = style.copy(color = color))
}

private fun inlineMarkdown(text: String, codeBackground: Color, linkColor: Color): AnnotatedString {
    val builder = AnnotatedString.Builder()
    val pattern = Regex("\\*\\*([^*\\n]+)\\*\\*|`([^`\\n]+)`|\\[([^]]+)\\]\\((https?://[^)\\s]+)\\)")
    var cursor = 0
    pattern.findAll(text).forEach { match ->
        builder.append(text.substring(cursor, match.range.first))
        val bold = match.groups[1]
        val code = match.groups[2]
        val link = match.groups[3]
        val url = match.groups[4]
        when {
            bold != null -> {
                val start = builder.length
                builder.append(bold.value)
                builder.addStyle(SpanStyle(fontWeight = FontWeight.SemiBold), start, builder.length)
            }
            code != null -> {
                val start = builder.length
                builder.append(code.value)
                builder.addStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground), start, builder.length)
            }
            link != null && url != null -> {
                builder.withLink(LinkAnnotation.Url(url.value)) {
                    withStyle(SpanStyle(color = linkColor)) { append(link.value) }
                }
            }
        }
        cursor = match.range.last + 1
    }
    builder.append(text.substring(cursor))
    return builder.toAnnotatedString()
}

private fun parseBlocks(text: String): List<LlmBlock> {
    val result = mutableListOf<LlmBlock>()
    val paragraph = mutableListOf<String>()
    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            result += LlmBlock.Paragraph(paragraph.joinToString("\n"))
            paragraph.clear()
        }
    }
    val lines = text.replace("\r\n", "\n").split('\n')
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (line.trimStart().startsWith("```")) {
            flushParagraph()
            val code = mutableListOf<String>()
            index++
            while (index < lines.size && !lines[index].trimStart().startsWith("```")) {
                code += lines[index]
                index++
            }
            result += LlmBlock.Code(code.joinToString("\n"))
        } else {
            val trimmed = line.trim()
            val heading = Regex("^(#{1,3})\\s+(.+)$").find(trimmed)
            val bullet = Regex("^[-*]\\s+(.+)$").find(trimmed)
            val numbered = Regex("^(\\d+[.)])\\s+(.+)$").find(trimmed)
            when {
                trimmed.isEmpty() -> flushParagraph()
                heading != null -> {
                    flushParagraph()
                    result += LlmBlock.Heading(heading.groupValues[2], heading.groupValues[1].length)
                }
                bullet != null -> {
                    flushParagraph()
                    result += LlmBlock.ListItem(bullet.groupValues[1], "•")
                }
                numbered != null -> {
                    flushParagraph()
                    result += LlmBlock.ListItem(numbered.groupValues[2], numbered.groupValues[1])
                }
                else -> paragraph += line
            }
        }
        index++
    }
    flushParagraph()
    return result.ifEmpty { listOf(LlmBlock.Paragraph("")) }
}
