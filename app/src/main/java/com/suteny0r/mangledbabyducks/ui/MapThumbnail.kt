package com.suteny0r.mangledbabyducks.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import android.util.LruCache
import com.suteny0r.mangledbabyducks.db.MapNode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.snapshotter.MapSnapshot
import org.maplibre.android.snapshotter.MapSnapshotter
import kotlin.coroutines.resume

/**
 * A still map for a coordinate shared in a message, rendered off-screen.
 *
 * `MapSnapshotter` rather than a live `MapView`: a message list can hold many of these and
 * a map view apiece would be ruinous. It also keeps the thumbnail on the same tiles and the
 * same style as the Map tab, so a shared pin looks like the rest of the app rather than
 * like somebody else's map.
 *
 * Framed to **span the whole mesh**, not just the pin. A pin alone tells the reader nothing;
 * seen against every node the mesh knows about, it says where this is relative to everyone,
 * which is the question a mesh user is actually asking. Same bounds-of-all-nodes rule the
 * Map tab uses when it fits more than one node.
 */
object MapThumbnail {

    private const val TAG = "MapThumbnail"
    /** Keeps a screenful of thumbnails; each is a few hundred KB at this size. */
    private val cache = object : LruCache<String, Rendered>(12) {}

    /** Margin around the fitted bounds so pins never sit on the very edge. */
    private const val INSET = 0.12
    /** Used when the mesh has nothing to frame against: roughly a 10 km box, as the Map tab does. */
    private const val LONE_PIN_SPAN_METRES = 10_000.0

    /**
     * [bitmap] plus where things landed on it, in bitmap pixels. The projection has to come
     * from the snapshot itself; recomputing it from the bounds would drift, because the
     * renderer fits the region to the aspect ratio it was given.
     */
    data class Rendered(
        val bitmap: Bitmap,
        val pin: PointF,
        val nodes: List<PointF>,
    )

    suspend fun render(
        context: Context,
        latitude: Double,
        longitude: Double,
        meshNodes: List<MapNode>,
        widthPx: Int,
        heightPx: Int,
    ): Rendered? {
        if (widthPx <= 0 || heightPx <= 0) return null
        val key = key(latitude, longitude, meshNodes, widthPx, heightPx)
        cache.get(key)?.let { return it }

        val target = LatLng(latitude, longitude)
        val bounds = frame(target, meshNodes) ?: return null
        return runCatching {
            // The snapshotter talks to the native renderer and must be driven from the main
            // thread; only the waiting happens off it.
            withContext(Dispatchers.Main) {
                MapLibre.getInstance(context)
                snapshot(context, bounds, widthPx, heightPx)
            }?.let { snapshot ->
                val rendered = Rendered(
                    bitmap = snapshot.bitmap,
                    pin = snapshot.pixelForLatLng(target),
                    nodes = meshNodes.map { snapshot.pixelForLatLng(LatLng(it.latitude, it.longitude)) },
                )
                cache.put(key, rendered)
                rendered
            }
        }.getOrElse {
            Log.w(TAG, "Snapshot failed: ${it.message}")
            null
        }
    }

    private suspend fun snapshot(
        context: Context,
        bounds: LatLngBounds,
        widthPx: Int,
        heightPx: Int,
    ): MapSnapshot? = suspendCancellableCoroutine { continuation ->
        val options = MapSnapshotter.Options(widthPx, heightPx)
            .withRegion(bounds)
            .withStyle(STREETS_STYLE_URL)
            .withLogo(false)
        val snapshotter = MapSnapshotter(context, options)
        continuation.invokeOnCancellation { runCatching { snapshotter.cancel() } }
        snapshotter.start(
            { snapshot -> if (continuation.isActive) continuation.resume(snapshot) },
            { error ->
                Log.w(TAG, "Snapshotter error: $error")
                if (continuation.isActive) continuation.resume(null)
            },
        )
    }

    /**
     * Everything the mesh knows about plus the shared pin, inset a little. With nothing to
     * frame against, falls back to a box around the pin rather than rendering the planet.
     */
    private fun frame(target: LatLng, meshNodes: List<MapNode>): LatLngBounds? = runCatching {
        val points = meshNodes.map { LatLng(it.latitude, it.longitude) } + target
        if (points.size < 2) return@runCatching loneBox(target)
        var north = points.maxOf { it.latitude }
        var south = points.minOf { it.latitude }
        var east = points.maxOf { it.longitude }
        var west = points.minOf { it.longitude }
        val padLat = ((north - south) * INSET).coerceAtLeast(0.002)
        val padLng = ((east - west) * INSET).coerceAtLeast(0.002)
        north = (north + padLat).coerceAtMost(85.0)
        south = (south - padLat).coerceAtLeast(-85.0)
        east = (east + padLng).coerceAtMost(180.0)
        west = (west - padLng).coerceAtLeast(-180.0)
        // Degenerate when every node sits on one spot; loneBox gives it something to show.
        if (north - south < 1e-6 || east - west < 1e-6) loneBox(target)
        else LatLngBounds.from(north, east, south, west)
    }.getOrNull()

    private fun loneBox(target: LatLng): LatLngBounds {
        val dLat = LONE_PIN_SPAN_METRES / 2 / 111_320.0
        val dLng = LONE_PIN_SPAN_METRES / 2 /
            (111_320.0 * Math.cos(Math.toRadians(target.latitude)).coerceAtLeast(0.01))
        return LatLngBounds.from(
            target.latitude + dLat, target.longitude + dLng,
            target.latitude - dLat, target.longitude - dLng,
        )
    }

    /**
     * The mesh moves, so the frame does. Keyed on the node count and a rounded extent
     * rather than every position, or a single node drifting a few metres would throw away
     * every thumbnail on screen.
     */
    private fun key(
        latitude: Double,
        longitude: Double,
        meshNodes: List<MapNode>,
        widthPx: Int,
        heightPx: Int,
    ): String {
        val north = meshNodes.maxOfOrNull { it.latitude } ?: latitude
        val south = meshNodes.minOfOrNull { it.latitude } ?: latitude
        val east = meshNodes.maxOfOrNull { it.longitude } ?: longitude
        val west = meshNodes.minOfOrNull { it.longitude } ?: longitude
        return "%.5f,%.5f|%d|%.2f,%.2f,%.2f,%.2f|%dx%d".format(
            latitude, longitude, meshNodes.size, north, south, east, west, widthPx, heightPx,
        )
    }
}
