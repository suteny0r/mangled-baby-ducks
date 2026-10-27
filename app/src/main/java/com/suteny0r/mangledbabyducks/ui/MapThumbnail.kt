package com.suteny0r.mangledbabyducks.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import android.util.LruCache
import com.suteny0r.mangledbabyducks.db.MapNode
import kotlinx.coroutines.CancellationException
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
            // Every native call here happens on the main thread, including reading the
            // projection back out of the finished snapshot; only the waiting happens off it.
            withContext(Dispatchers.Main) {
                MapLibre.getInstance(context)
                val snapshot = snapshot(context, bounds, widthPx, heightPx)
                    ?: return@withContext null
                Rendered(
                    bitmap = snapshot.bitmap,
                    pin = snapshot.pixelForLatLng(target),
                    nodes = meshNodes.map { snapshot.pixelForLatLng(LatLng(it.latitude, it.longitude)) },
                )
            }?.also { cache.put(key, it) }
        }.getOrElse { failure ->
            // runCatching catches CancellationException too, and swallowing that turns a
            // cancelled thumbnail into a coroutine that will not die.
            if (failure is CancellationException) throw failure
            Log.w(TAG, "Snapshot failed: ${failure.message}")
            null
        }
    }

    /**
     * A snapshot, with the snapshotter released deterministically afterwards.
     *
     * [MapSnapshotter.cancel] is the only orderly way to shut one down. Without it the
     * object is eventually collected and its native peer destroyed by `finalize()` on the
     * GC's thread, which is how a crash arrives:
     *
     * ```
     * mbgl::MapSnapshotter::Impl::onDidFinishLoadingStyle
     *   mbgl::Map::Impl::onStyleLoaded
     *     mbgl::style::Style::Impl::parse
     *       mbgl::FileSourceRequest::setResponse
     *         mbgl::Mailbox::receive ... mbgl::util::RunLoop::process
     * ```
     *
     * The style response comes back after the Impl is gone and calls into freed memory:
     * SIGSEGV on the main thread, no Java frames, nothing to catch. Scrolling a thread
     * away from a map link while its style is still loading is all it takes.
     *
     * The cancel is in a `finally` so it covers the success, the error and the cancelled
     * path alike, and it runs on the main thread because the caller confines this whole
     * function there: [MapSnapshotter.cancel] checks the thread and throws otherwise.
     * [MapSnapshot] owns a separate native handle with its own finalizer, so the returned
     * snapshot stays usable after its snapshotter is gone.
     */
    private suspend fun snapshot(
        context: Context,
        bounds: LatLngBounds,
        widthPx: Int,
        heightPx: Int,
    ): MapSnapshot? {
        val options = MapSnapshotter.Options(widthPx, heightPx)
            .withRegion(bounds)
            .withStyle(STREETS_STYLE_URL)
            .withLogo(false)
        val snapshotter = MapSnapshotter(context, options)
        try {
            return suspendCancellableCoroutine { continuation ->
                snapshotter.start(
                    { snapshot -> if (continuation.isActive) continuation.resume(snapshot) },
                    { error ->
                        Log.w(TAG, "Snapshotter error: $error")
                        if (continuation.isActive) continuation.resume(null)
                    },
                )
            }
        } finally {
            // Never from inside the callback above: that would destroy the object native
            // code is still returning through. The continuation resumes through the main
            // dispatcher, so by here the native call has unwound.
            runCatching { snapshotter.cancel() }
                .onFailure { Log.w(TAG, "Snapshotter cancel failed: ${it.message}") }
        }
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
