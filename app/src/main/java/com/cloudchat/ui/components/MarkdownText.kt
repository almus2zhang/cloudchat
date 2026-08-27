package com.cloudchat.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun MarkdownText(
    markdown: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: Float = 14f,
    isOutgoing: Boolean = false
) {
    val defaultColor = if (color != Color.Unspecified) color else if (isOutgoing) Color.White else MaterialTheme.colorScheme.onSurface

    val lines = remember(markdown) { markdown.lines() }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        var inCodeBlock = false
        var codeBlockContent = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()

            // Code block toggle
            if (trimmed.startsWith("```")) {
                if (inCodeBlock) {
                    // Close code block
                    val code = codeBlockContent.toString().trimEnd()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isOutgoing) Color.Black.copy(alpha = 0.25f) else Color(0xFF282C34))
                            .padding(8.dp)
                    ) {
                        Text(
                            text = code,
                            color = if (isOutgoing) Color.White else Color(0xFFABB2BF),
                            fontFamily = FontFamily.Monospace,
                            fontSize = (fontSize - 1).coerceAtLeast(11f).sp,
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        )
                    }
                    codeBlockContent = StringBuilder()
                    inCodeBlock = false
                } else {
                    inCodeBlock = true
                }
                continue
            }

            if (inCodeBlock) {
                codeBlockContent.append(line).append("\n")
                continue
            }

            if (trimmed.isEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                continue
            }

            // Headers
            when {
                trimmed.startsWith("### ") -> {
                    Text(
                        text = buildAnnotatedMarkdown(trimmed.removePrefix("### "), isOutgoing),
                        fontWeight = FontWeight.Bold,
                        fontSize = (fontSize + 2).sp,
                        color = if (isOutgoing) Color.White else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
                    )
                }
                trimmed.startsWith("## ") -> {
                    Text(
                        text = buildAnnotatedMarkdown(trimmed.removePrefix("## "), isOutgoing),
                        fontWeight = FontWeight.Bold,
                        fontSize = (fontSize + 3.5f).sp,
                        color = if (isOutgoing) Color.White else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
                    )
                }
                trimmed.startsWith("# ") -> {
                    Text(
                        text = buildAnnotatedMarkdown(trimmed.removePrefix("# "), isOutgoing),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = (fontSize + 5).sp,
                        color = if (isOutgoing) Color.White else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                    )
                }
                // Bullet list item
                trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("• ") -> {
                    val content = trimmed.substring(2)
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = "•",
                            fontWeight = FontWeight.Bold,
                            color = if (isOutgoing) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary,
                            fontSize = (fontSize + 1).sp,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(
                            text = buildAnnotatedMarkdown(content, isOutgoing),
                            fontSize = fontSize.sp,
                            color = defaultColor,
                            lineHeight = (fontSize * 1.35f).sp
                        )
                    }
                }
                // Numbered list item: e.g. 1. or 2.
                trimmed.matches(Regex("^\\d+\\.\\s.*")) -> {
                    val dotIdx = trimmed.indexOf('.')
                    val num = trimmed.substring(0, dotIdx + 1)
                    val content = trimmed.substring(dotIdx + 1).trim()
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 4.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Text(
                            text = num,
                            fontWeight = FontWeight.Bold,
                            color = if (isOutgoing) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.primary,
                            fontSize = fontSize.sp,
                            modifier = Modifier.padding(end = 6.dp)
                        )
                        Text(
                            text = buildAnnotatedMarkdown(content, isOutgoing),
                            fontSize = fontSize.sp,
                            color = defaultColor,
                            lineHeight = (fontSize * 1.35f).sp
                        )
                    }
                }
                // Quote block
                trimmed.startsWith("> ") -> {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(4.dp))
                            .background(if (isOutgoing) Color.White.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height(16.dp)
                                .background(if (isOutgoing) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.primary)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = buildAnnotatedMarkdown(trimmed.removePrefix("> "), isOutgoing),
                            fontStyle = FontStyle.Italic,
                            fontSize = fontSize.sp,
                            color = if (isOutgoing) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                // Regular paragraph / formatted text
                else -> {
                    Text(
                        text = buildAnnotatedMarkdown(trimmed, isOutgoing),
                        fontSize = fontSize.sp,
                        color = defaultColor,
                        lineHeight = (fontSize * 1.35f).sp
                    )
                }
            }
        }
    }
}

/**
 * Builds an AnnotatedString for inline Markdown formatting (**bold**, *italic*, `code`, ~~strikethrough~~)
 */
fun buildAnnotatedMarkdown(text: String, isOutgoing: Boolean): AnnotatedString {
    return buildAnnotatedString {
        var i = 0
        val len = text.length

        while (i < len) {
            // Bold: **text**
            if (i + 1 < len && text[i] == '*' && text[i + 1] == '*') {
                val end = text.indexOf("**", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                    continue
                }
            }
            // Inline code: `code`
            if (text[i] == '`') {
                val end = text.indexOf('`', i + 1)
                if (end != -1) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = if (isOutgoing) Color.Black.copy(alpha = 0.2f) else Color(0xFFE8E8E8),
                            color = if (isOutgoing) Color(0xFFFFD54F) else Color(0xFFD32F2F)
                        )
                    ) {
                        append(" ${text.substring(i + 1, end)} ")
                    }
                    i = end + 1
                    continue
                }
            }
            // Italic: *text* (single star, not double)
            if (text[i] == '*' && (i + 1 >= len || text[i + 1] != '*')) {
                val end = text.indexOf('*', i + 1)
                if (end != -1 && (end + 1 >= len || text[end + 1] != '*')) {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(text.substring(i + 1, end))
                    }
                    i = end + 1
                    continue
                }
            }
            // Strikethrough: ~~text~~
            if (i + 1 < len && text[i] == '~' && text[i + 1] == '~') {
                val end = text.indexOf("~~", i + 2)
                if (end != -1) {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(text.substring(i + 2, end))
                    }
                    i = end + 2
                    continue
                }
            }

            append(text[i])
            i++
        }
    }
}
