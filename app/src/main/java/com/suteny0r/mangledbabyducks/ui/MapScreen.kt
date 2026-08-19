package com.suteny0r.mangledbabyducks.ui

import android.app.Application
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suteny0r.mangledbabyducks.container
import com.suteny0r.mangledbabyducks.db.ChannelEntity
import com.suteny0r.mangledbabyducks.db.MapNode
import com.suteny0r.mangledbabyducks.db.WaypointEntity
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textAnchor
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textOptional
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Streets: keyless openfreemap vector style. */
private const val STREETS_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/**
 * Satellite: Esri World Imagery raster tiles (keyless, attribution required).
 * A raster style carries no glyphs, so the openfreemap glyph endpoint is added
 * for the node labels.
 */
private val SATELLITE_STYLE_JSON = """
{
  "version": 8,
  "name": "Satellite",
  "glyphs": "https://tiles.openfreemap.org/fonts/{fontstack}/{range}.pbf",
  "sources": {
    "sat": {
      "type": "raster",
      "tiles": ["https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/{z}/{y}/{x}"],
      "tileSize": 256,
      "maxzoom": 19,
      "attribution": "Esri, Maxar, Earthstar Geographics"
    }
  },
  "layers": [{"id": "sat", "type": "raster", "source": "sat"}]
}
""".trimIndent()

private const val SOURCE_ID = "mesh-nodes"
private const val CIRCLE_LAYER_ID = "mesh-nodes-circles"
private const val LABEL_LAYER_ID = "mesh-nodes-labels"
private const val WP_SOURCE_ID = "waypoints"
private const val WP_CIRCLE_LAYER_ID = "waypoints-circles"
private const val WP_LABEL_LAYER_ID = "waypoints-labels"

class MapViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container

    val nodes: StateFlow<List<MapNode>> = container.database.positionDao().mapNodes()
        .onEach { android.util.Log.d("MapScreen", "mapNodes emitted ${it.size}") }
        .catch { android.util.Log.e("MapScreen", "mapNodes flow failed", it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val waypoints: StateFlow<List<WaypointEntity>> =
        container.database.waypointDao().active(System.currentTimeMillis() / 1000)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val channels: StateFlow<List<ChannelEntity>> =
        container.database.channelDao().activeChannels()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun sendWaypoint(name: String, description: String, lat: Double, lon: Double, channel: Int) {
        viewModelScope.launch {
            container.radioManager.sendWaypoint(
                name, description,
                (lat * 1e7).toInt(), (lon * 1e7).toInt(),
                channel,
            )
        }
    }
}

@Composable
fun MapScreen(vm: MapViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val nodes by vm.nodes.collectAsState()
    val waypoints by vm.waypoints.collectAsState()
    val channels by vm.channels.collectAsState()
    val currentNodes = rememberUpdatedState(nodes)
    val currentWaypoints = rememberUpdatedState(waypoints)
    var satellite by rememberSaveable { mutableStateOf(true) }
    var newWaypointAt by remember { mutableStateOf<Pair<Double, Double>?>(null) }

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    // Per-MapView fit flag: the view is destroyed and recreated on tab switches,
    // so a file-level flag would suppress the re-fit forever.
    val fitState = remember { FitState() }

    // Re-apply the style (and our layers on top of it) whenever the toggle flips.
    LaunchedEffect(satellite) {
        mapView.getMapAsync { map ->
            val builder = if (satellite) {
                Style.Builder().fromJson(SATELLITE_STYLE_JSON)
            } else {
                Style.Builder().fromUri(STREETS_STYLE_URL)
            }
            map.setStyle(builder) { style ->
                addNodeLayers(style, satellite)
                renderNodes(map, currentNodes.value, fitState)
                renderWaypoints(map, currentWaypoints.value)
            }
            map.addOnMapLongClickListener { latLng ->
                newWaypointAt = latLng.latitude to latLng.longitude
                true
            }
        }
    }

    // MapView needs the host lifecycle forwarded manually under Compose.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(
            factory = { mapView },
            modifier = Modifier.fillMaxSize(),
            update = { view ->
                // Read the state HERE, synchronously: getMapAsync defers its callback
                // while the map initializes, and reads inside a deferred callback are
                // invisible to Compose's snapshot observer — update would never re-run.
                val currentNodeList = nodes
                val currentWaypointList = waypoints
                view.getMapAsync { map ->
                    renderNodes(map, currentNodeList, fitState)
                    renderWaypoints(map, currentWaypointList)
                }
            },
        )
        SmallFloatingActionButton(
            onClick = { satellite = !satellite },
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(12.dp),
        ) {
            Icon(Icons.Default.Layers, contentDescription = "Toggle satellite/streets")
        }
        if (nodes.isEmpty()) {
            Text(
                "No node positions yet",
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(16.dp),
            )
        }
    }

    newWaypointAt?.let { (lat, lon) ->
        WaypointDialog(
            lat = lat,
            lon = lon,
            channels = channels,
            onDismiss = { newWaypointAt = null },
            onSave = { name, description, channel ->
                vm.sendWaypoint(name, description, lat, lon, channel)
                newWaypointAt = null
            },
        )
    }
}

@Composable
private fun WaypointDialog(
    lat: Double,
    lon: Double,
    channels: List<ChannelEntity>,
    onDismiss: () -> Unit,
    onSave: (name: String, description: String, channel: Int) -> Unit,
) {
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    // Default to the private secondary channel when one exists.
    var channel by remember {
        mutableStateOf(channels.lastOrNull { it.index > 0 }?.index ?: 0)
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New waypoint") },
        text = {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                Text("%.5f, %.5f".format(lat, lon), style = MaterialTheme.typography.bodySmall)
                androidx.compose.material3.OutlinedTextField(
                    value = name,
                    onValueChange = { if (it.length <= 30) name = it },
                    label = { Text("Name") },
                    singleLine = true,
                )
                androidx.compose.material3.OutlinedTextField(
                    value = description,
                    onValueChange = { if (it.length <= 100) description = it },
                    label = { Text("Description (optional)") },
                    singleLine = true,
                )
                channels.forEach { ch ->
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = channel == ch.index,
                            onClick = { channel = ch.index },
                        )
                        Text(ch.name?.ifEmpty { "Primary" } ?: "Primary")
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                enabled = name.isNotBlank(),
                onClick = { onSave(name.trim(), description.trim(), channel) },
            ) { Text("Share") }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun renderWaypoints(map: MapLibreMap, waypoints: List<WaypointEntity>) {
    val style = map.style ?: return
    val source = style.getSourceAs<GeoJsonSource>(WP_SOURCE_ID) ?: return
    val features = waypoints.map { wp ->
        Feature.fromGeometry(Point.fromLngLat(wp.longitude, wp.latitude)).also {
            it.addStringProperty("label", wp.name.filter { c -> !c.isSurrogate() }.trim())
        }
    }
    source.setGeoJson(FeatureCollection.fromFeatures(features))
}

private fun addNodeLayers(style: Style, satellite: Boolean) {
    style.addSource(GeoJsonSource(SOURCE_ID))
    style.addLayer(
        CircleLayer(CIRCLE_LAYER_ID, SOURCE_ID).withProperties(
            circleRadius(7f),
            circleColor(if (satellite) 0xFF67EA94.toInt() else 0xFF2E8B57.toInt()),
            circleStrokeColor(
                if (satellite) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            ),
            circleStrokeWidth(2f),
        )
    )
    style.addLayer(
        SymbolLayer(LABEL_LAYER_ID, SOURCE_ID).withProperties(
            textField(get("label")),
            // The openfreemap glyph server only carries Noto Sans; the SDK
            // default stack 404s and stalls label rendering.
            textFont(arrayOf("Noto Sans Regular")),
            textSize(11f),
            textAnchor("top"),
            textOffset(arrayOf(0f, 0.8f)),
            textColor(
                if (satellite) android.graphics.Color.WHITE else android.graphics.Color.BLACK
            ),
            textHaloColor(
                if (satellite) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            ),
            textHaloWidth(1.5f),
            textAllowOverlap(false),
            textOptional(true),
        )
    )
    style.addSource(GeoJsonSource(WP_SOURCE_ID))
    style.addLayer(
        CircleLayer(WP_CIRCLE_LAYER_ID, WP_SOURCE_ID).withProperties(
            circleRadius(7f),
            circleColor(0xFFFF9800.toInt()),
            circleStrokeColor(
                if (satellite) android.graphics.Color.BLACK else android.graphics.Color.WHITE
            ),
            circleStrokeWidth(2f),
        )
    )
    style.addLayer(
        SymbolLayer(WP_LABEL_LAYER_ID, WP_SOURCE_ID).withProperties(
            textField(get("label")),
            textFont(arrayOf("Noto Sans Regular")),
            textSize(11f),
            textAnchor("top"),
            textOffset(arrayOf(0f, 0.8f)),
            textColor(0xFFFF9800.toInt()),
            textHaloColor(android.graphics.Color.BLACK),
            textHaloWidth(1.5f),
            textAllowOverlap(true),
            textOptional(true),
        )
    )
}

/**
 * Glyph servers carry SDF fonts, not emoji, and unknown glyph ranges 404 — strip
 * non-BMP characters and fall back to the node id when nothing printable is left.
 */
private fun mapLabel(node: MapNode): String {
    val raw = node.shortName?.ifBlank { null } ?: node.longName ?: ""
    val printable = raw.filter { it.code in 0x20..0xFFFF && !it.isSurrogate() }.trim()
    return printable.ifEmpty { "%04x".format(node.nodeNum and 0xFFFF) }
}

class FitState {
    var done = false
}

private fun renderNodes(map: MapLibreMap, nodes: List<MapNode>, fit: FitState) {
    val style = map.style ?: run {
        android.util.Log.d("MapScreen", "renderNodes: style not ready (${nodes.size} nodes)")
        return
    }
    val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID) ?: run {
        android.util.Log.d("MapScreen", "renderNodes: source missing (${nodes.size} nodes)")
        return
    }
    android.util.Log.d("MapScreen", "renderNodes: ${nodes.size} nodes, fitDone=${fit.done}")
    val features = nodes.map { node ->
        Feature.fromGeometry(Point.fromLngLat(node.longitude, node.latitude)).also {
            it.addStringProperty("label", mapLabel(node))
        }
    }
    source.setGeoJson(FeatureCollection.fromFeatures(features))

    if (!fit.done && nodes.size >= 2) {
        fit.done = true
        val bounds = LatLngBounds.Builder()
        nodes.forEach { bounds.include(LatLng(it.latitude, it.longitude)) }
        runCatching {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 80))
        }
    }
}
