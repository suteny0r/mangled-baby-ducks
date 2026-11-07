package com.suteny0r.mangledbabyducks.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PointF
import android.util.Log
import android.util.LruCache
import com.suteny0r.mangledbabyducks.db.MapNode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 *
 * ## One snapshotter for the life of the process, and it is never let go
 *
 * MapLibre 11.8.5 cannot release a `MapSnapshotter` safely while its style is loading, and
 * nothing in its API stops a style load. Read together, the sources give the sequence
 * behind every one of the native crashes this app has had:
 *
 * - `MapSnapshotter::Impl::cancel()` is `renderStillCallback.reset()` and nothing else
 *   (`platform/default/src/mbgl/map/map_snapshotter.cpp`). The Java `cancel()` drops the
 *   pending render callback; the style request, the Map and its observer chain carry on.
 * - The Java object has no destroy; its native peer dies in `finalize()`. The JNI
 *   destructor (`platform/android/.../snapshotter/map_snapshotter.cpp`) runs on the
 *   finalizer thread, frees the JNI wrapper there, and only *schedules* the deletion of the
 *   native snapshotter onto the main run loop. The wrapper is the `MapSnapshotterObserver`
 *   the native `Impl` holds by reference.
 * - Between those two moments the style finishing, or failing, lands on the main run loop
 *   as `Map::Impl::onStyleLoaded` -> `MapSnapshotter::Impl::onDidFinishLoadingStyle` ->
 *   `observer.onDidFinishLoadingStyle()` through a freed object. SIGSEGV on the main
 *   thread, no Java frames, nothing to catch. Symbolicated twice (2026-10-26 and
 *   2026-11-07, `HANDOFF.md`); the other user's five crashes carry the same frames.
 *
 * Cancelling in a `finally`, the previous fix, made it worse: it is the moment the Java
 * object becomes unreachable, so a thread of map links scrolled away mid-load handed the GC
 * a batch of snapshotters whose styles were still in flight. Upstream 13.7.1 has the same
 * destructor, so an upgrade would not help.
 *
 * So: a single `MapSnapshotter` is created on first use and kept for the life of the
 * process. Renders are serialized through it in a scope the caller cannot cancel, and the
 * result goes into the cache whether or not whoever asked is still composed, so a thumbnail
 * that was scrolled away is ready when it scrolls back. One instance also means the style
 * and its tiles are loaded once per process rather than once per bubble. A render that
 * never calls back is written off after [RENDER_TIMEOUT_MS] and its snapshotter parked in
 * [abandoned], held strongly on purpose: a parked one leaks a render thread, a released one
 * crashes the app.
 */
object MapThumbnail {

    private const val TAG = "MapThumbnail"
    /** Keeps a screenful of thumbnails; each is a few hundred KB at this size. */
    private val cache = object : LruCache<String, Rendered>(12) {}

    /** Margin around the fitted bounds so pins never sit on the very edge. */
    private const val INSET = 0.12
    /** Used when the mesh has nothing to frame against: roughly a 10 km box, as the Map tab does. */
    private const val LONE_PIN_SPAN_METRES = 10_000.0
    /** A style plus a screen of tiles over a slow link; past this the snapshotter is written off. */
    private const val RENDER_TIMEOUT_MS = 45_000L

    // Everything from here down is touched on the main thread only. `Dispatchers.Main`,
    // not `.immediate`: MapLibre resumes our continuation from inside its own callback and
    // only resets its "already started" guard after that callback returns, so the next
    // `start` must be a later main-loop turn, which a posting dispatcher guarantees.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val renders = Mutex()
    private val inFlight = HashMap<String, Deferred<Rendered?>>()
    private var snapshotter: MapSnapshotter? = null
    private var snapshotterSize: Pair<Int, Int>? = null
    /** Set when a render failed; the style is loaded again before the next one. */
    private var styleNeedsReload = false
    /** Snapshotters that timed out mid-render. Never released; see the class comment. */
    private val abandoned = ArrayList<MapSnapshotter>()

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

    /**
     * The thumbnail for one shared coordinate, from the cache when it is there.
     *
     * Cancelling the caller stops the wait, not the render: the native work finishes in
     * [scope] and the result is cached for the next caller with the same key. Identical
     * concurrent requests share one render.
     */
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
        val appContext = context.applicationContext
        return withContext(Dispatchers.Main) {
            cache.get(key)?.let { return@withContext it }
            val job = inFlight.getOrPut(key) {
                scope.async {
                    try {
                        renders.withLock {
                            renderOne(appContext, key, target, meshNodes, bounds, widthPx, heightPx)
                        }
                    } finally {
                        inFlight.remove(key)
                    }
                }
            }
            job.await()
        }
    }

    /** One render through the shared snapshotter. Main thread, under [renders]. */
    private suspend fun renderOne(
        context: Context,
        key: String,
        target: LatLng,
        meshNodes: List<MapNode>,
        bounds: LatLngBounds,
        widthPx: Int,
        heightPx: Int,
    ): Rendered? {
        cache.get(key)?.let { return it }
        return try {
            val snapshot = snapshot(context, bounds, widthPx, heightPx) ?: return null
            Rendered(
                bitmap = snapshot.bitmap,
                pin = snapshot.pixelForLatLng(target),
                nodes = meshNodes.map { snapshot.pixelForLatLng(LatLng(it.latitude, it.longitude)) },
            ).also { cache.put(key, it) }
        } catch (failure: CancellationException) {
            throw failure
        } catch (failure: Exception) {
            Log.w(TAG, "Snapshot failed: ${failure.message}")
            null
        }
    }

    /**
     * A snapshot from the shared snapshotter, or null when the render failed or timed out.
     *
     * Nothing here calls `cancel()` and nothing lets the snapshotter go; the class comment
     * says why. [MapSnapshot] owns a separate native handle with its own finalizer, so the
     * returned snapshot stays usable whatever happens to the snapshotter afterwards.
     */
    private suspend fun snapshot(
        context: Context,
        bounds: LatLngBounds,
        widthPx: Int,
        heightPx: Int,
    ): MapSnapshot? {
        MapLibre.getInstance(context)
        val current = snapshotterFor(context, widthPx, heightPx)
        if (styleNeedsReload) {
            current.setStyleUrl(STREETS_STYLE_URL)
            styleNeedsReload = false
        }
        current.setRegion(bounds)
        var calledBack = false
        val snapshot = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
            suspendCancellableCoroutine<MapSnapshot?> { continuation ->
                current.start(
                    { snapshot ->
                        calledBack = true
                        if (continuation.isActive) continuation.resume(snapshot)
                    },
                    { error ->
                        calledBack = true
                        Log.w(TAG, "Snapshotter error: $error")
                        // A failed style stays failed; load it again before the next render.
                        styleNeedsReload = true
                        if (continuation.isActive) continuation.resume(null)
                    },
                )
            }
        }
        if (!calledBack) {
            // Still rendering, or never going to. Either way it may still call back into
            // native code, so it is parked rather than released.
            Log.w(TAG, "Snapshotter timed out after ${RENDER_TIMEOUT_MS / 1000} s; parking it")
            abandoned += current
            snapshotter = null
            snapshotterSize = null
        }
        return snapshot
    }

    private fun snapshotterFor(context: Context, widthPx: Int, heightPx: Int): MapSnapshotter {
        snapshotter?.let { existing ->
            if (snapshotterSize != widthPx to heightPx) {
                existing.setSize(widthPx, heightPx)
                snapshotterSize = widthPx to heightPx
            }
            return existing
        }
        val options = MapSnapshotter.Options(widthPx, heightPx)
            .withStyle(STREETS_STYLE_URL)
            .withLogo(false)
        return MapSnapshotter(context, options).also {
            snapshotter = it
            snapshotterSize = widthPx to heightPx
            styleNeedsReload = false
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
