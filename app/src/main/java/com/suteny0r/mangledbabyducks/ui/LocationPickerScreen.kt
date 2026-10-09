package com.suteny0r.mangledbabyducks.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import java.util.Locale

/**
 * Not in the iOS app (its map-pin button sends the radio's own position after the
 * message). Here the composer's pin opens this: the satellite map with a fixed pin at the
 * centre; pan until the pin sits on the spot, then Insert puts a Google Maps link into the
 * draft. Starts on [initial] (where we are) when known.
 */
@Composable
fun LocationPickerScreen(
    initial: Pair<Double, Double>?,
    onPick: (latitude: Double, longitude: Double) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val mapView = remember {
        MapLibre.getInstance(context)
        MapView(context)
    }
    var map by remember { mutableStateOf<MapLibreMap?>(null) }
    var centre by remember { mutableStateOf(initial) }

    LaunchedEffect(Unit) {
        mapView.getMapAsync { m ->
            m.uiSettings.isLogoEnabled = false
            m.uiSettings.isAttributionEnabled = false
            m.setStyle(Style.Builder().fromJson(SATELLITE_STYLE_JSON))
            initial?.let { (lat, lon) ->
                m.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 15.0))
            }
            m.addOnCameraIdleListener {
                val t = m.cameraPosition.target
                if (t != null) centre = t.latitude to t.longitude
            }
            map = m
        }
    }

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

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RoundBackButton(onBack)
            Spacer(Modifier.weight(1f))
            Text("Drop a pin", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Spacer(Modifier.size(44.dp))
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            // The pin's tip, not its centre, marks the spot: lift the glyph by half its height.
            Icon(
                Icons.Filled.Place,
                contentDescription = "Pin",
                tint = Color(0xFFE53935),
                modifier = Modifier.align(Alignment.Center).size(44.dp).offset(y = (-22).dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                centre?.let { (lat, lon) -> String.format(Locale.US, "%.5f, %.5f", lat, lon) } ?: "Pan the map to a spot",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Button(
                enabled = centre != null,
                onClick = { centre?.let { (lat, lon) -> onPick(lat, lon) } },
            ) { Text("Insert link") }
        }
    }
}

/** The link the picker inserts; opens in Google Maps, the Maps web app, or any map app that registers the host. */
fun mapsLink(latitude: Double, longitude: Double): String =
    String.format(Locale.US, "https://maps.google.com/?q=%.5f,%.5f", latitude, longitude)
