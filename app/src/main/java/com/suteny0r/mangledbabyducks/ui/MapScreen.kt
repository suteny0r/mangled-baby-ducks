package com.suteny0r.mangledbabyducks.ui

import android.app.Application
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suteny0r.mangledbabyducks.container
import com.suteny0r.mangledbabyducks.db.MapNode
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
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

/** Free vector style, no API key required. */
private const val STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"
private const val SOURCE_ID = "mesh-nodes"
private const val CIRCLE_LAYER_ID = "mesh-nodes-circles"
private const val LABEL_LAYER_ID = "mesh-nodes-labels"

class MapViewModel(app: Application) : AndroidViewModel(app) {
    val nodes: StateFlow<List<MapNode>> = app.container.database.positionDao().mapNodes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
}

@Composable
fun MapScreen(vm: MapViewModel = viewModel()) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val nodes by vm.nodes.collectAsState()
    val currentNodes = androidx.compose.runtime.rememberUpdatedState(nodes)

    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context).apply {
            getMapAsync { map ->
                map.setStyle(STYLE_URL) { style ->
                    style.addSource(GeoJsonSource(SOURCE_ID))
                    style.addLayer(
                        CircleLayer(CIRCLE_LAYER_ID, SOURCE_ID).withProperties(
                            circleRadius(7f),
                            circleColor(androidx.compose.ui.graphics.Color(0xFF2E8B57).toArgb()),
                            circleStrokeColor(android.graphics.Color.WHITE),
                            circleStrokeWidth(2f),
                        )
                    )
                    style.addLayer(
                        SymbolLayer(LABEL_LAYER_ID, SOURCE_ID).withProperties(
                            textField(get("label")),
                            // The openfreemap glyph server only carries Noto Sans;
                            // the SDK default stack 404s and stalls label rendering.
                            textFont(arrayOf("Noto Sans Regular")),
                            textSize(11f),
                            textAnchor("top"),
                            textOffset(arrayOf(0f, 0.8f)),
                            textColor(android.graphics.Color.BLACK),
                            textHaloColor(android.graphics.Color.WHITE),
                            textHaloWidth(1.5f),
                            textAllowOverlap(true),
                            textOptional(true),
                        )
                    )
                    // Data may have emitted before the style finished loading;
                    // render whatever is current now that layers exist.
                    renderNodes(map, currentNodes.value)
                }
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
                Lifecycle.Event.ON_DESTROY -> mapView.onDestroy()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDestroy()
        }
    }

    AndroidView(
        factory = { mapView },
        modifier = Modifier.fillMaxSize(),
        update = { view ->
            view.getMapAsync { map -> renderNodes(map, nodes) }
        },
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

private var didInitialFit = false

private fun renderNodes(map: MapLibreMap, nodes: List<MapNode>) {
    val style = map.style ?: return
    val source = style.getSourceAs<GeoJsonSource>(SOURCE_ID) ?: return
    val features = nodes.map { node ->
        Feature.fromGeometry(Point.fromLngLat(node.longitude, node.latitude)).also {
            it.addStringProperty("label", mapLabel(node))
        }
    }
    source.setGeoJson(FeatureCollection.fromFeatures(features))

    if (!didInitialFit && nodes.size >= 2) {
        didInitialFit = true
        val bounds = LatLngBounds.Builder()
        nodes.forEach { bounds.include(LatLng(it.latitude, it.longitude)) }
        runCatching {
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(bounds.build(), 80))
        }
    }
}
