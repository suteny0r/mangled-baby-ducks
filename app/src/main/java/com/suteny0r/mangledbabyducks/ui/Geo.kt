package com.suteny0r.mangledbabyducks.ui

import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Geodesy for list rows (CLLocation.swift's getBearingBetweenTwoPoints and
 * DistanceText.swift): distance and initial bearing between two coordinates, and the
 * distance as MKDistanceFormatter prints it in the locale's customary units.
 */

fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * r * atan2(sqrt(a), sqrt(1 - a))
}

/** Initial bearing from point 1 to point 2, 0..360 degrees clockwise from true north. */
fun bearingDegrees(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val phi1 = Math.toRadians(lat1)
    val phi2 = Math.toRadians(lat2)
    val dLon = Math.toRadians(lon2 - lon1)
    val y = sin(dLon) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLon)
    return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
}

val imperialUnits: Boolean
    get() = Locale.getDefault().country in setOf("US", "LR", "MM")

/** MKDistanceFormatter default style: feet under a tenth of a mile, else miles; metres under 1 km, else km. */
fun formatDistance(meters: Double): String {
    if (imperialUnits) {
        val miles = meters / 1609.344
        if (miles < 0.1) return "${(meters * 3.28084).toInt()} ft"
        return if (miles < 10) "%.1f mi".format(miles) else "${miles.toInt()} mi"
    }
    if (meters < 1000) return "${meters.toInt()} m"
    return if (meters < 10_000) "%.1f km".format(meters / 1000) else "${(meters / 1000).toInt()} km"
}

/** A Google Maps link for a coordinate; opens in Maps, the web app, or any map app registering the host. */
fun mapsLink(latitude: Double, longitude: Double): String =
    String.format(Locale.US, "https://maps.google.com/?q=%.5f,%.5f", latitude, longitude)

/**
 * The coordinate a map link points at, or null if it is not one we recognise.
 *
 * Covers what actually turns up in messages: this app's own `maps.google.com/?q=lat,lon`,
 * Google's documented `/maps/search/?api=1&query=lat,lon`, the `@lat,lon,zoom` form that
 * Maps puts in a shared URL, and the plain `geo:` scheme. Anything with a place name
 * rather than a coordinate is deliberately not matched: there is nothing to put a pin on
 * without geocoding it, which would mean a network round trip per message.
 */
fun mapLinkCoordinate(url: String): Pair<Double, Double>? {
    val match = MAP_LINK_PATTERNS.firstNotNullOfOrNull { it.find(url) } ?: return null
    val latitude = match.groupValues[1].toDoubleOrNull() ?: return null
    val longitude = match.groupValues[2].toDoubleOrNull() ?: return null
    // A swapped or junk pair would otherwise put a pin in the sea.
    if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
    return latitude to longitude
}

private const val COORD = """(-?\d{1,3}(?:\.\d+)?)[,%]?[, ]\s*(-?\d{1,3}(?:\.\d+)?)"""

private val MAP_LINK_PATTERNS = listOf(
    Regex("""https?://(?:www\.)?maps\.google\.[a-z.]+/\?q=$COORD""", RegexOption.IGNORE_CASE),
    Regex("""https?://(?:www\.)?google\.[a-z.]+/maps/search/\?api=1&query=$COORD""", RegexOption.IGNORE_CASE),
    Regex("""https?://(?:www\.)?google\.[a-z.]+/maps/@$COORD""", RegexOption.IGNORE_CASE),
    Regex("""https?://(?:www\.)?google\.[a-z.]+/maps/place/$COORD""", RegexOption.IGNORE_CASE),
    Regex("""geo:$COORD""", RegexOption.IGNORE_CASE),
)

/** Every map link in [text], in the order written, paired with its coordinate. */
fun mapLinksIn(text: String): List<MapLink> =
    URL_IN_TEXT.findAll(text)
        .mapNotNull { m -> mapLinkCoordinate(m.value)?.let { MapLink(m.value, it.first, it.second) } }
        .distinctBy { "${it.latitude},${it.longitude}" }
        .toList()

data class MapLink(val url: String, val latitude: Double, val longitude: Double)

/** Trailing punctuation is excluded so "see <link>." does not swallow the full stop. */
private val URL_IN_TEXT = Regex("""(?:https?://|geo:)[^\s<>"']+[^\s<>"'.,!?;:)\]]""")
