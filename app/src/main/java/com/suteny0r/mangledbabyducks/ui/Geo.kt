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
