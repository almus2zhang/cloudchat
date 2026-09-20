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
    isOutgoing: Boolean = false,
    onUrlClick: ((String) -> Unit)? = null
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
                    ClickableMarkdownText(
                        annotatedText = buildAnnotatedMarkdown(trimmed.removePrefix("### "), isOutgoing),
                        fontWeight = FontWeight.Bold,
                        fontSize = fontSize + 2,
                        lineHeight = (fontSize + 2) * 1.35f,
                        color = if (isOutgoing) Color.White else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        onUrlClick = onUrlClick
                    )
                }
                trimmed.startsWith("## ") -> {
                    ClickableMarkdownText(
                        annotatedText = buildAnnotatedMarkdown(trimmed.removePrefix("## "), isOutgoing),
                        fontWeight = FontWeight.Bold,
                        fontSize = fontSize + 3.5f,
                        lineHeight = (fontSize + 3.5f) * 1.35f,
                        color = if (isOutgoing) Color.White else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 6.dp, bottom = 2.dp),
                        onUrlClick = onUrlClick
                    )
                }
                trimmed.startsWith("# ") -> {
                    ClickableMarkdownText(
                        annotatedText = buildAnnotatedMarkdown(trimmed.removePrefix("# "), isOutgoing),
                        fontWeight = FontWeight.ExtraBold,
                        fontSize = fontSize + 5,
                        lineHeight = (fontSize + 5) * 1.35f,
                        color = if (isOutgoing) Color.White else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
                        onUrlClick = onUrlClick
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
                        ClickableMarkdownText(
                            annotatedText = buildAnnotatedMarkdown(content, isOutgoing),
                            fontSize = fontSize,
                            color = defaultColor,
                            lineHeight = fontSize * 1.35f,
                            onUrlClick = onUrlClick
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
                        ClickableMarkdownText(
                            annotatedText = buildAnnotatedMarkdown(content, isOutgoing),
                            fontSize = fontSize,
                            color = defaultColor,
                            lineHeight = fontSize * 1.35f,
                            onUrlClick = onUrlClick
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
                        ClickableMarkdownText(
                            annotatedText = buildAnnotatedMarkdown(trimmed.removePrefix("> "), isOutgoing),
                            fontStyle = FontStyle.Italic,
                            fontSize = fontSize,
                            color = if (isOutgoing) Color.White.copy(alpha = 0.9f) else MaterialTheme.colorScheme.onSurfaceVariant,
                            lineHeight = fontSize * 1.35f,
                            onUrlClick = onUrlClick
                        )
                    }
                }
                // Regular paragraph / formatted text
                else -> {
                    ClickableMarkdownText(
                        annotatedText = buildAnnotatedMarkdown(trimmed, isOutgoing),
                        fontSize = fontSize,
                        color = defaultColor,
                        lineHeight = fontSize * 1.35f,
                        onUrlClick = onUrlClick
                    )
                }
            }
        }
    }
}

@Composable
fun ClickableMarkdownText(
    annotatedText: AnnotatedString,
    color: Color,
    fontSize: Float,
    lineHeight: Float,
    fontWeight: FontWeight? = null,
    fontStyle: FontStyle? = null,
    modifier: Modifier = Modifier,
    onUrlClick: ((String) -> Unit)? = null
) {
    val hasUrl = annotatedText.getStringAnnotations("URL", 0, annotatedText.length).isNotEmpty()
    if (hasUrl && onUrlClick != null) {
        androidx.compose.foundation.text.ClickableText(
            text = annotatedText,
            style = TextStyle(
                color = color,
                fontSize = fontSize.sp,
                lineHeight = lineHeight.sp,
                fontWeight = fontWeight,
                fontStyle = fontStyle
            ),
            modifier = modifier,
            onClick = { offset ->
                annotatedText.getStringAnnotations("URL", offset, offset).firstOrNull()?.let {
                    onUrlClick(it.item)
                }
            }
        )
    } else {
        Text(
            text = annotatedText,
            color = color,
            fontSize = fontSize.sp,
            lineHeight = lineHeight.sp,
            fontWeight = fontWeight,
            fontStyle = fontStyle,
            modifier = modifier
        )
    }
}

/**
 * Builds an AnnotatedString for inline Markdown formatting (**bold**, *italic*, `code`, ~~strikethrough~~, [link](url), raw URLs)
 */
fun buildAnnotatedMarkdown(text: String, isOutgoing: Boolean): AnnotatedString {
    return buildAnnotatedString {
        val linkColor = Color(0xFF007AFF)
        val pattern = Regex("(\\[([^\\]]+)\\]\\((https?://\\S+|www\\.\\S+)\\)|\\*\\*.*?\\*\\*|`.*?`|\\*.*?\\*|~~.*?~~|https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+|www\\.[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)")
        var lastIndex = 0

        for (match in pattern.findAll(text)) {
            if (match.range.first > lastIndex) {
                append(text.substring(lastIndex, match.range.first))
            }
            val matchText = match.value

            when {
                // Markdown link: [text](url)
                matchText.startsWith("[") && match.groupValues.size >= 4 && match.groupValues[2].isNotEmpty() && match.groupValues[3].isNotEmpty() -> {
                    val label = match.groupValues[2]
                    var url = match.groupValues[3]
                    if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                        url = "https://$url"
                    }
                    pushStringAnnotation(tag = "URL", annotation = url)
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium)) {
                        append(label)
                    }
                    pop()
                }
                // Bold: **text**
                matchText.startsWith("**") && matchText.endsWith("**") && matchText.length >= 4 -> {
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(matchText.substring(2, matchText.length - 2))
                    }
                }
                // Code: `code`
                matchText.startsWith("`") && matchText.endsWith("`") && matchText.length >= 2 -> {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            background = if (isOutgoing) Color.Black.copy(alpha = 0.2f) else Color(0xFFE8E8E8),
                            color = if (isOutgoing) Color(0xFFFFD54F) else Color(0xFFD32F2F)
                        )
                    ) {
                        append(" ${matchText.substring(1, matchText.length - 1)} ")
                    }
                }
                // Strikethrough: ~~text~~
                matchText.startsWith("~~") && matchText.endsWith("~~") && matchText.length >= 4 -> {
                    withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) {
                        append(matchText.substring(2, matchText.length - 2))
                    }
                }
                // Italic: *text*
                matchText.startsWith("*") && matchText.endsWith("*") && matchText.length >= 2 -> {
                    withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                        append(matchText.substring(1, matchText.length - 1))
                    }
                }
                // Raw URL: http://... or https://... or www....
                matchText.startsWith("http://", ignoreCase = true) || 
                matchText.startsWith("https://", ignoreCase = true) || 
                matchText.startsWith("www.", ignoreCase = true) -> {
                    val cleanUrl = matchText.trimEnd('.', ',', ';', '!', '?', ')')
                    val trailing = matchText.substring(cleanUrl.length)
                    var targetUrl = cleanUrl
                    if (!targetUrl.startsWith("http://", ignoreCase = true) && !targetUrl.startsWith("https://", ignoreCase = true)) {
                        targetUrl = "https://$targetUrl"
                    }
                    pushStringAnnotation(tag = "URL", annotation = targetUrl)
                    withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium)) {
                        append(cleanUrl)
                    }
                    pop()
                    if (trailing.isNotEmpty()) {
                        append(trailing)
                    }
                }
                else -> {
                    append(matchText)
                }
            }
            lastIndex = match.range.last + 1
        }

        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }
}

/**
 * Builds an AnnotatedString for plain text with highlighted & clickable URLs
 */
fun buildAnnotatedTextWithUrls(text: String, isOutgoing: Boolean): AnnotatedString {
    return buildAnnotatedString {
        val linkColor = Color(0xFF007AFF)
        val pattern = Regex("(https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+|www\\.[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+)")
        var lastIndex = 0

        for (match in pattern.findAll(text)) {
            if (match.range.first > lastIndex) {
                append(text.substring(lastIndex, match.range.first))
            }
            val matchText = match.value
            val cleanUrl = matchText.trimEnd('.', ',', ';', '!', '?', ')')
            val trailing = matchText.substring(cleanUrl.length)
            var targetUrl = cleanUrl
            if (!targetUrl.startsWith("http://", ignoreCase = true) && !targetUrl.startsWith("https://", ignoreCase = true)) {
                targetUrl = "https://$targetUrl"
            }
            pushStringAnnotation(tag = "URL", annotation = targetUrl)
            withStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline, fontWeight = FontWeight.Medium)) {
                append(cleanUrl)
            }
            pop()
            if (trailing.isNotEmpty()) {
                append(trailing)
            }
            lastIndex = match.range.last + 1
        }

        if (lastIndex < text.length) {
            append(text.substring(lastIndex))
        }
    }
}
