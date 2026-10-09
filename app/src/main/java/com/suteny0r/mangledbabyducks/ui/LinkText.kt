package com.suteny0r.mangledbabyducks.ui

import android.util.Patterns
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink

/**
 * MessageText.swift renders the payload as an AttributedString whose link runs are tinted
 * and tappable. Compose has no auto-detection, so this finds web URLs with the platform
 * matcher and wraps each in a [LinkAnnotation.Url]; `Text` opens them through the
 * UriHandler on tap. A match without a scheme ("www.example.org") gets "https://", or
 * nothing would handle it.
 */
fun linkifiedText(text: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    val matcher = Patterns.WEB_URL.matcher(text)
    var last = 0
    val style = TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    while (matcher.find()) {
        val start = matcher.start()
        val end = matcher.end()
        // The matcher is happy with "word.word" inside prose; require a scheme or www.
        val raw = text.substring(start, end)
        val lower = raw.lowercase()
        val isUrl = lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.")
        if (!isUrl) continue
        append(text, last, start)
        val target = if (lower.startsWith("www.")) "https://$raw" else raw
        withLink(LinkAnnotation.Url(target, style)) { append(raw) }
        last = end
    }
    append(text, last, text.length)
}
