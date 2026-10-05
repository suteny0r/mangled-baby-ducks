package com.suteny0r.mangledbabyducks.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * About and licenses. No Swift counterpart.
 *
 * This screen is the app's GPLv3 section 6 compliance surface: it names the license,
 * shows the full text, and gives the URL of the complete corresponding source. It also
 * carries the non-affiliation disclaimer the Meshtastic trademark policy asks for.
 */

const val SOURCE_URL = "https://github.com/suteny0r/mangled-baby-ducks"
private const val ISSUES_URL = "$SOURCE_URL/issues"

/** A bundled text file the screen can open full-page. */
enum class AboutDoc(val title: String, val asset: String) {
    LICENSE("GNU General Public License v3.0", "LICENSE.txt"),
    NOTICES("Third-party notices", "NOTICES.txt"),
}

private data class Credit(val name: String, val license: String, val url: String)

private val DERIVED_FROM = listOf(
    Credit("Meshtastic-Apple", "GPL-3.0. The Swift app this client was ported from.",
        "https://github.com/meshtastic/Meshtastic-Apple"),
    Credit("Meshtastic protobufs", "GPL-3.0. Wire format, vendored unmodified.",
        "https://github.com/meshtastic/protobufs"),
)

private val THIRD_PARTY = listOf(
    Credit("MapLibre Native", "BSD 2-Clause", "https://github.com/maplibre/maplibre-native"),
    Credit("Protocol Buffers", "BSD 3-Clause", "https://github.com/protocolbuffers/protobuf"),
    Credit("AndroidX and Jetpack Compose", "Apache License 2.0",
        "https://developer.android.com/jetpack/androidx"),
    Credit("Kotlin and kotlinx.coroutines", "Apache License 2.0",
        "https://github.com/Kotlin/kotlinx.coroutines"),
    Credit("ZXing", "Apache License 2.0", "https://github.com/zxing/zxing"),
)

private val MAP_DATA = listOf(
    Credit("OpenFreeMap", "Street tiles and glyphs", "https://openfreemap.org"),
    Credit("OpenMapTiles", "Vector tile schema", "https://openmaptiles.org"),
    Credit("OpenStreetMap contributors", "Map data, ODbL",
        "https://www.openstreetmap.org/copyright"),
    Credit("Esri World Imagery", "Esri, Maxar, Earthstar Geographics, and the GIS User Community",
        "https://www.esri.com"),
)

/** Full-screen About page with its own back header; [onBack] returns to Settings. */
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "?"
    }
    var doc by rememberSaveable { mutableStateOf<AboutDoc?>(null) }

    // One header at a time: a bundled document replaces the About page rather than
    // stacking a second back arrow under the first.
    doc?.let { open ->
        BackHandler { doc = null }
        AssetTextScreen(open, onBack = { doc = null })
        return
    }
    BackHandler(onBack = onBack)

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text("About and licenses", style = MaterialTheme.typography.headlineSmall)
        }
        Text("Mangled Baby Ducks", style = MaterialTheme.typography.titleLarge)
        Text("Version $version", style = MaterialTheme.typography.bodyMedium)
        Text(
            "A free, open-source Android client for mesh radios running Meshtastic® firmware.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text(
            "Mangled Baby Ducks is an independent project. It is not affiliated with or " +
                "endorsed by Meshtastic LLC or the Meshtastic project. Meshtastic® is a " +
                "registered trademark of Meshtastic LLC.",
            style = MaterialTheme.typography.bodySmall,
        )

        Text("License", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column {
                ListItem(
                    headlineContent = { Text(AboutDoc.LICENSE.title) },
                    supportingContent = {
                        Text("Free software. You may use, study, share and modify it. Tap to read the license.")
                    },
                    modifier = Modifier.clickable { doc = AboutDoc.LICENSE },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Source code") },
                    supportingContent = { Text(SOURCE_URL) },
                    modifier = Modifier.clickable { openUrl(context, SOURCE_URL) },
                )
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text("Report a problem") },
                    supportingContent = { Text("Issues on GitHub") },
                    modifier = Modifier.clickable { openUrl(context, ISSUES_URL) },
                )
            }
        }

        Text("Derived from", style = MaterialTheme.typography.titleMedium)
        CreditCard(DERIVED_FROM, context)

        Text("Third-party software", style = MaterialTheme.typography.titleMedium)
        Card(Modifier.fillMaxWidth()) {
            Column {
                THIRD_PARTY.forEachIndexed { index, credit ->
                    if (index > 0) HorizontalDivider()
                    CreditRow(credit, context)
                }
                HorizontalDivider()
                ListItem(
                    headlineContent = { Text(AboutDoc.NOTICES.title) },
                    supportingContent = { Text("Full license texts for the components above") },
                    modifier = Modifier.clickable { doc = AboutDoc.NOTICES },
                )
            }
        }

        Text("Map data", style = MaterialTheme.typography.titleMedium)
        CreditCard(MAP_DATA, context)
    }
}

@Composable
private fun CreditCard(credits: List<Credit>, context: Context) {
    Card(Modifier.fillMaxWidth()) {
        Column {
            credits.forEachIndexed { index, credit ->
                if (index > 0) HorizontalDivider()
                CreditRow(credit, context)
            }
        }
    }
}

@Composable
private fun CreditRow(credit: Credit, context: Context) {
    ListItem(
        headlineContent = { Text(credit.name) },
        supportingContent = { Text(credit.license) },
        modifier = Modifier.clickable { openUrl(context, credit.url) },
    )
}

/** Full-page view of a bundled text asset, loaded off the main thread. */
@Composable
private fun AssetTextScreen(doc: AboutDoc, onBack: () -> Unit) {
    val context = LocalContext.current
    var text by remember(doc) { mutableStateOf<String?>(null) }
    LaunchedEffect(doc) {
        text = withContext(Dispatchers.IO) {
            runCatching {
                context.assets.open(doc.asset).bufferedReader().use { it.readText() }
            }.getOrElse { "Could not load ${doc.asset}: ${it.message}" }
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp),
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
            }
            Text(doc.title, style = MaterialTheme.typography.titleLarge)
        }
        val body = text
        if (body == null) {
            CircularProgressIndicator(Modifier.padding(24.dp))
        } else {
            // License texts are pre-wrapped at ~72 columns; a monospace font and a
            // horizontal scroll keep the original layout instead of re-flowing it.
            Text(
                body,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .horizontalScroll(rememberScrollState())
                    .padding(16.dp),
            )
        }
    }
}

fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
    }.onFailure {
        Toast.makeText(context, "No browser available", Toast.LENGTH_SHORT).show()
    }
}
