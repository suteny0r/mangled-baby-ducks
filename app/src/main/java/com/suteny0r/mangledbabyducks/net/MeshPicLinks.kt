package com.suteny0r.mangledbabyducks.net

/**
 * Recognising the meshpic.org links this app sends, so a received picture shows as a
 * picture rather than as an opaque short URL.
 *
 * A link is all that crosses the mesh; the picture itself lives on meshpic.org. The view
 * page's own `og:image` points at `/image/<short_id>`, which serves the image bytes
 * directly with no cookie, token or special header, so the bubble can load it straight
 * from the short link without fetching the page first. Verified against the live service.
 */
object MeshPicLinks {

    /**
     * Paths on the host that are not short ids. Without this, a link to the image itself
     * (`/image/gor`) would be read as a short id of "image" and send the bubble looking
     * for `/image/image`.
     */
    private val RESERVED = setOf("image", "images", "static", "report", "_ui", "api")

    /**
     * The trailing guard matters: without it, `meshpic.org/gor/agA` would match just "gor"
     * and silently drop the rest of a link that is not a plain short link at all.
     */
    private val PATTERN =
        Regex("""https?://(?:www\.)?meshpic\.org/([A-Za-z0-9_-]{1,32})(?![A-Za-z0-9_/-])""")

    data class Picture(val shortId: String, val link: String) {
        /** Serves the image bytes; no auth, no headers. */
        val imageUrl: String get() = "https://meshpic.org/image/$shortId"
    }

    /** Every meshpic link in [text], in the order written, without duplicates. */
    fun find(text: String): List<Picture> =
        PATTERN.findAll(text)
            .filter { it.groupValues[1].lowercase() !in RESERVED }
            .map { Picture(it.groupValues[1], it.value) }
            .distinctBy { it.shortId }
            .toList()

    /**
     * [text] with the recognised links taken out, for a bubble that is showing the picture
     * instead. Whitespace left behind by the removal is collapsed so a message that was
     * only a link renders as empty rather than as a stray space, and one that had a caption
     * does not keep a gap where the URL used to be.
     */
    fun strip(text: String, pictures: List<Picture>): String {
        if (pictures.isEmpty()) return text
        var out = text
        pictures.forEach { out = out.replace(it.link, "") }
        return out
            // Taking a URL out of a sentence leaves its punctuation stranded: "see <link>."
            // became "see ." and "(<link>)" became "( )". Tidy the wreckage rather than
            // show it.
            .replace(Regex("""\(\s*\)"""), "")
            .replace(Regex("""\[\s*]"""), "")
            .replace(Regex("""[ \t]+([.,!?;:])"""), "$1")
            .replace(Regex("""[ \t]{2,}"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }
}
