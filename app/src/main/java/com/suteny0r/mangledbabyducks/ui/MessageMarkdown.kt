package com.suteny0r.mangledbabyducks.ui

import android.util.Patterns
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.LinkInteractionListener
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle

// Port of MarkdownFormatting.swift (the composer's formatting toolbar), MentionParser.swift
// (`@!<hex>` tokens) and the inline-markdown rendering MessageText.swift gets from
// AttributedString(markdown:) with inlineOnlyPreservingWhitespace.

/** MarkdownStyle: the five toolbar styles and their delimiters. */
enum class MarkdownStyle(val opening: String, val closing: String, val label: String) {
    BOLD("**", "**", "Bold"),
    ITALIC("*", "*", "Italic"),
    STRIKETHROUGH("~~", "~~", "Strikethrough"),
    CODE("`", "`", "Code"),
    LINK("[", "]", "Link"),
}

/** FormattingResult: the new draft and where the selection should land in it. */
data class FormattingResult(val text: String, val selection: TextRange)

private val DELIMITER_CHARS = setOf('*', '~', '`')
private val MARKDOWN_LINK = Regex("^\\[([^\\]]+)\\]\\(([^)]+)\\)$")

/** wrapSelection: wrap the selection in the style's delimiters, or strip them if already wrapped. */
fun wrapSelection(text: String, range: TextRange, style: MarkdownStyle): FormattingResult {
    val start = range.min
    val end = range.max
    val opening = style.opening
    val closing = style.closing
    val hasOpeningBefore = start - opening.length >= 0 && text.substring(start - opening.length, start) == opening
    val hasClosingAfter = end + closing.length <= text.length && text.substring(end, end + closing.length) == closing
    if (hasOpeningBefore && hasClosingAfter) {
        val delimStart = start - opening.length
        val delimEnd = end + closing.length
        val newText = text.substring(0, delimStart) + text.substring(start, end) + text.substring(delimEnd)
        return FormattingResult(newText, TextRange(delimStart, delimStart + (end - start)))
    }
    val expanded = expandToDelimiterBoundaries(text, start, end)
    val selected = text.substring(expanded.first, expanded.second)
    val cleaned = selected.filter { it !in DELIMITER_CHARS }
    val trimmed = cleaned.trim()
    if (trimmed.isEmpty()) return insertDelimiters(text, expanded.first, style)
    val leadingWs = cleaned.takeWhile { it.isWhitespace() }
    val trailingWs = cleaned.takeLastWhile { it.isWhitespace() }
    val wrapped = leadingWs + opening + trimmed + closing + trailingWs
    var newText = text.substring(0, expanded.first) + wrapped + text.substring(expanded.second)
    newText = cleanOrphanedDelimiters(newText)
    val full = opening + trimmed + closing
    val at = newText.indexOf(full)
    return if (at >= 0) {
        FormattingResult(newText, TextRange(at, at + full.length))
    } else {
        val contentStart = minOf(leadingWs.length, newText.length)
        FormattingResult(newText, TextRange(contentStart, minOf(contentStart + full.length, newText.length)))
    }
}

/** insertDelimiters: an empty pair at the caret, caret between them. */
fun insertDelimiters(text: String, at: Int, style: MarkdownStyle): FormattingResult {
    val index = at.coerceIn(0, text.length)
    val newText = text.substring(0, index) + style.opening + style.closing + text.substring(index)
    val caret = index + style.opening.length
    return FormattingResult(newText, TextRange(caret, caret))
}

fun isMarkdownLink(text: String): Boolean = MARKDOWN_LINK.matches(text)

/** wrapSelectionWithLink: `[selected](url)`, or a placeholder when nothing is selected. */
fun wrapSelectionWithLink(text: String, range: TextRange, url: String): FormattingResult {
    val start = range.min
    val end = range.max
    val selected = text.substring(start, end)
    val link = if (selected.isEmpty()) "[link text]($url)" else "[$selected]($url)"
    val newText = text.substring(0, start) + link + text.substring(end)
    return FormattingResult(newText, TextRange(start, start + link.length))
}

/** unwrapLink: a selected `[text](url)` back to its text; null when the selection is not a link. */
fun unwrapLink(text: String, range: TextRange): FormattingResult? {
    val start = range.min
    val end = range.max
    val match = MARKDOWN_LINK.find(text.substring(start, end)) ?: return null
    val display = match.groupValues[1]
    val newText = text.substring(0, start) + display + text.substring(end)
    return FormattingResult(newText, TextRange(start, start + display.length))
}

private val PAIRED_PATTERNS = listOf(
    Regex("\\*\\*[^*]+\\*\\*"),
    Regex("(?<!\\*)\\*[^*]+\\*(?!\\*)"),
    Regex("~~[^~]+~~"),
    Regex("`[^`]+`"),
    Regex("\\[[^\\]]+\\]\\([^)]+\\)"),
)

/** containsMarkdownSyntax: any properly paired delimiter, which is when the preview shows. */
fun containsMarkdownSyntax(text: String): Boolean =
    text.isNotEmpty() && PAIRED_PATTERNS.any { it.containsMatchIn(text) }

private fun expandToDelimiterBoundaries(text: String, start: Int, end: Int): Pair<Int, Int> {
    val inside = text.substring(start, end).any { it in DELIMITER_CHARS }
    val before = start > 0 && text[start - 1] in DELIMITER_CHARS
    val after = end < text.length && text[end] in DELIMITER_CHARS
    if (!inside && !before && !after) return start to end
    var lower = start
    while (lower > 0 && text[lower - 1] in DELIMITER_CHARS) lower--
    var upper = end
    while (upper < text.length && text[upper] in DELIMITER_CHARS) upper++
    return lower to upper
}

private fun cleanOrphanedDelimiters(text: String): String {
    var result = text
    for (delimiter in listOf("**", "~~", "`", "*")) result = cleanOrphanedPairs(result, delimiter)
    return result
}

private fun cleanOrphanedPairs(text: String, delimiter: String): String {
    var count = 0
    var from = 0
    while (true) {
        val at = text.indexOf(delimiter, from)
        if (at < 0) break
        count++
        from = at + delimiter.length
    }
    if (count % 2 == 0) return text
    val last = text.lastIndexOf(delimiter)
    return text.substring(0, last) + text.substring(last + delimiter.length)
}

// ---------------------------------------------------------------------------------------
// Mentions

/** MentionParser: `@!<8 hex digits>` on the wire, shared with the other clients. */
object MentionParser {
    private val mentionRegex = Regex("@!([0-9a-f]{8})(?![0-9a-f])")

    fun mentionRanges(text: String): List<Pair<IntRange, Long>> =
        mentionRegex.findAll(text).mapNotNull { m ->
            m.groupValues[1].toLongOrNull(16)?.let { m.range to it }
        }.toList()

    /** The partial query after a trailing `@`, or null when there is no open trigger. */
    fun activeMentionQuery(text: String): String? {
        val at = text.lastIndexOf('@')
        if (at < 0) return null
        val after = text.substring(at + 1)
        if (after.startsWith("!")) return null
        if (after.any { it.isWhitespace() }) return null
        return after
    }

    /** Replace the open trigger with the resolved token `@!deadbeef`. */
    fun insertMentionToken(text: String, nodeNum: Long): String {
        val at = text.lastIndexOf('@')
        if (at < 0) return text
        val after = text.substring(at + 1)
        if (after.startsWith("!") || after.any { it.isWhitespace() }) return text
        return text.substring(0, at) + "@!%08x".format(nodeNum)
    }

    fun containsMention(nodeNum: Long, text: String): Boolean =
        mentionRanges(text).any { it.second == nodeNum }

    /** Every token becomes `[@Name](meshtastic:///nodes?nodenum=N)`, names escaped for markdown. */
    fun resolveMentions(text: String, nameFor: (Long) -> String?): String {
        val ranges = mentionRanges(text)
        if (ranges.isEmpty()) return text
        val sb = StringBuilder(text)
        for ((range, num) in ranges.reversed()) {
            val name = nameFor(num) ?: "!%08x".format(num)
            sb.replace(range.first, range.last + 1, "[@${escapeMarkdown(name)}](meshtastic:///nodes?nodenum=$num)")
        }
        return sb.toString()
    }

    private fun escapeMarkdown(name: String): String = buildString {
        name.forEach { c ->
            val code = c.code
            if (code in 33..47 || code in 58..64 || code in 91..96 || code in 123..126) append('\\')
            append(c)
        }
    }

    const val NODE_LINK_PREFIX = "meshtastic:///nodes?nodenum="
}

// ---------------------------------------------------------------------------------------
// Rendering

/** The link an incoming node mention points at, or null for an ordinary URL. */
fun mentionNodeNum(url: String): Long? =
    if (url.startsWith(MentionParser.NODE_LINK_PREFIX)) url.removePrefix(MentionParser.NODE_LINK_PREFIX).toLongOrNull() else null

private val CODE_RX = Regex("`([^`]+)`")
private val LINK_RX = Regex("\\[([^\\]]+)\\]\\(([^)\\s]+)\\)")
private val BOLD_RX = Regex("\\*\\*(.+?)\\*\\*")
private val STRIKE_RX = Regex("~~(.+?)~~")
private val ITALIC_RX = Regex("(?<!\\*)\\*([^*\\s](?:[^*]*?[^*\\s])?)\\*(?!\\*)")

/**
 * MessageText's body: mentions resolved to links, then inline markdown (bold, italic,
 * strikethrough, code, `[text](url)`), then bare web URLs made tappable, the way the
 * iOS data detector does when the message is stored. Control characters (the alert
 * bell) are dropped from the display.
 */
fun renderMessageMarkdown(
    payload: String,
    nameFor: (Long) -> String?,
    linkColor: Color,
    codeBackground: Color,
    listener: LinkInteractionListener?,
    /** MessagePreviewText: the list preview keeps the formatting but drops link styling. */
    linksAsText: Boolean = false,
): AnnotatedString {
    val text = MentionParser.resolveMentions(payload.filter { it.code >= 0x20 || it == '\n' }, nameFor)
    val linkStyle = if (linksAsText) TextLinkStyles(SpanStyle()) else TextLinkStyles(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
    return buildAnnotatedString { appendInline(text, linkStyle, codeBackground, listener) }
}

private class Match(val start: Int, val end: Int, val kind: Char, val inner: String, val url: String = "")

private fun earliest(text: String, from: Int): Match? {
    var best: Match? = null
    fun consider(rx: Regex, kind: Char, urlGroup: Int = -1) {
        val m = rx.find(text, from) ?: return
        if (best == null || m.range.first < best!!.start) {
            best = Match(m.range.first, m.range.last + 1, kind, m.groupValues[1], if (urlGroup > 0) m.groupValues[urlGroup] else "")
        }
    }
    consider(CODE_RX, 'c')
    consider(LINK_RX, 'l', 2)
    consider(BOLD_RX, 'b')
    consider(STRIKE_RX, 's')
    consider(ITALIC_RX, 'i')
    return best
}

private fun AnnotatedString.Builder.appendInline(
    text: String,
    linkStyle: TextLinkStyles,
    codeBackground: Color,
    listener: LinkInteractionListener?,
) {
    var pos = 0
    while (pos < text.length) {
        val m = earliest(text, pos)
        if (m == null) {
            appendPlain(text.substring(pos), linkStyle, listener)
            break
        }
        appendPlain(text.substring(pos, m.start), linkStyle, listener)
        when (m.kind) {
            'c' -> withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = codeBackground)) { append(m.inner) }
            'l' -> withLink(LinkAnnotation.Url(m.url, linkStyle, listener)) { appendInline(unescape(m.inner), linkStyle, codeBackground, null) }
            'b' -> withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { appendInline(m.inner, linkStyle, codeBackground, listener) }
            's' -> withStyle(SpanStyle(textDecoration = TextDecoration.LineThrough)) { appendInline(m.inner, linkStyle, codeBackground, listener) }
            'i' -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { appendInline(m.inner, linkStyle, codeBackground, listener) }
        }
        pos = m.end
    }
}

/** Plain text with backslash escapes removed and bare URLs (scheme or www.) made links. */
private fun AnnotatedString.Builder.appendPlain(raw: String, linkStyle: TextLinkStyles, listener: LinkInteractionListener?) {
    val text = unescape(raw)
    val matcher = Patterns.WEB_URL.matcher(text)
    var last = 0
    while (matcher.find()) {
        val candidate = text.substring(matcher.start(), matcher.end())
        val lower = candidate.lowercase()
        val isUrl = lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("www.")
        if (!isUrl) continue
        append(text, last, matcher.start())
        val target = if (lower.startsWith("www.")) "https://$candidate" else candidate
        withLink(LinkAnnotation.Url(target, linkStyle, listener)) { append(candidate) }
        last = matcher.end()
    }
    append(text, last, text.length)
}

private fun unescape(s: String): String {
    if ('\\' !in s) return s
    val sb = StringBuilder(s.length)
    var i = 0
    while (i < s.length) {
        val c = s[i]
        if (c == '\\' && i + 1 < s.length && s[i + 1].code in 33..126 && !s[i + 1].isLetterOrDigit()) {
            sb.append(s[i + 1]); i += 2
        } else {
            sb.append(c); i++
        }
    }
    return sb.toString()
}
