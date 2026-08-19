package com.suteny0r.mangledbabyducks.ui

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.suteny0r.mangledbabyducks.container
import com.suteny0r.mangledbabyducks.db.TelemetryEntity
import com.suteny0r.mangledbabyducks.db.TracerouteEntity
import kotlinx.coroutines.launch

class NodeDetailViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    private val db = container.database

    fun node(num: Long) = db.nodeDao().nodeWithUserFlow(num)
    fun deviceMetrics(num: Long) =
        db.telemetryDao().history(num, 0, System.currentTimeMillis() - 48 * 3600_000L)
    fun latestPosition(num: Long) = db.positionDao().latestFlow(num)
    fun traceroutes(num: Long) = db.tracerouteDao().forNode(num)

    fun runTraceroute(num: Long) {
        viewModelScope.launch { container.radioManager.sendTraceroute(num) }
    }

    suspend fun nameFor(num: Long): String =
        db.userDao().get(num)?.let { it.longName ?: "!%08x".format(num) } ?: "!%08x".format(num)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeDetailScreen(
    nodeNum: Long,
    onBack: () -> Unit,
    onMessage: () -> Unit,
    vm: NodeDetailViewModel = viewModel(),
) {
    val entry by vm.node(nodeNum).collectAsState(initial = null)
    val metrics by vm.deviceMetrics(nodeNum).collectAsState(initial = emptyList())
    val position by vm.latestPosition(nodeNum).collectAsState(initial = null)
    val traceroutes by vm.traceroutes(nodeNum).collectAsState(initial = emptyList())

    Column(Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(entry?.user?.longName ?: "Node $nodeNum") },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                IconButton(onClick = onMessage) {
                    Icon(Icons.AutoMirrored.Outlined.Message, contentDescription = "Message")
                }
            },
        )
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Card(Modifier.fillMaxWidth()) {
                Column {
                    val user = entry?.user
                    val node = entry?.node
                    ListItem(
                        headlineContent = { Text("Identity") },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    user?.shortName?.let { "$it" },
                                    user?.userId,
                                    user?.hwModel,
                                    if (user?.isLicensed == true) "licensed" else null,
                                ).joinToString("  •  ").ifEmpty { "unknown" },
                            )
                        },
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Link") },
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    node?.lastHeard?.let { "heard ${relativeTime(it)}" },
                                    node?.snr?.takeIf { it != 0f }?.let { "SNR %.1f".format(it) },
                                    node?.rssi?.takeIf { it != 0 }?.let { "RSSI $it" },
                                    node?.hopsAway?.takeIf { it >= 0 }
                                        ?.let { if (it == 0) "direct" else "$it hops" },
                                    if (node?.viaMqtt == true) "via MQTT" else null,
                                ).joinToString("  •  ").ifEmpty { "—" },
                            )
                        },
                    )
                    HorizontalDivider()
                    ListItem(
                        headlineContent = { Text("Position") },
                        supportingContent = {
                            Text(
                                position?.let {
                                    "%.5f, %.5f  •  ${relativeTime(it.time)}".format(
                                        it.latitude, it.longitude,
                                    )
                                } ?: "no position",
                            )
                        },
                    )
                    if (entry?.user?.keyMatch == false) {
                        HorizontalDivider()
                        ListItem(
                            headlineContent = {
                                Text("Key mismatch", color = MaterialTheme.colorScheme.error)
                            },
                            supportingContent = {
                                Text("This node's public key changed; DMs may fail until re-verified.")
                            },
                        )
                    }
                }
            }

            if (metrics.isNotEmpty()) {
                Text("Battery (48h)", style = MaterialTheme.typography.titleMedium)
                Card(Modifier.fillMaxWidth()) {
                    MetricChart(
                        points = metrics.mapNotNull { m ->
                            m.batteryLevel?.let { m.time to it.coerceAtMost(100).toFloat() }
                        },
                        unit = "%",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .padding(12.dp),
                    )
                }
                Text("Channel utilization (48h)", style = MaterialTheme.typography.titleMedium)
                Card(Modifier.fillMaxWidth()) {
                    MetricChart(
                        points = metrics.mapNotNull { m ->
                            m.channelUtilization?.let { m.time to it }
                        },
                        unit = "%",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(140.dp)
                            .padding(12.dp),
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Traceroute", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { vm.runTraceroute(nodeNum) }) { Text("Run") }
            }
            if (traceroutes.isEmpty()) {
                Text("No traceroutes yet", style = MaterialTheme.typography.bodyMedium)
            }
            traceroutes.forEach { route ->
                TracerouteCard(route, vm)
            }
        }
    }
}

@Composable
private fun TracerouteCard(route: TracerouteEntity, vm: NodeDetailViewModel) {
    val text by androidx.compose.runtime.produceState(initialValue = "…", route) {
        value = if (!route.response) {
            "pending"
        } else {
            buildString {
                append("→ ")
                append(routeText(route.routeTowards, route.snrTowards, vm))
                if (route.routeBack.isNotEmpty() || route.snrBack.isNotEmpty()) {
                    append("\n← ")
                    append(routeText(route.routeBack, route.snrBack, vm))
                }
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(relativeTime(route.time), style = MaterialTheme.typography.labelSmall)
            Text(text, style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Resolve "num,num" + "snr,snr" (scaled by 4) into "Name (x.x dB) → Name". */
private suspend fun routeText(routeCsv: String, snrCsv: String, vm: NodeDetailViewModel): String {
    val hops = routeCsv.split(",").filter { it.isNotBlank() }.map { it.toLong() }
    val snrs = snrCsv.split(",").filter { it.isNotBlank() }.map { it.toInt() / 4f }
    if (hops.isEmpty()) {
        return if (snrs.isNotEmpty()) "direct (%.1f dB)".format(snrs.last()) else "direct"
    }
    val names = hops.map { vm.nameFor(it) }
    return buildString {
        names.forEachIndexed { i, name ->
            append(name)
            snrs.getOrNull(i)?.let { append(" (%.1f dB)".format(it)) }
            if (i < names.lastIndex) append(" → ")
        }
        if (snrs.size > names.size) {
            append(" → dest (%.1f dB)".format(snrs.last()))
        }
    }
}

/** Minimal time-series line chart; no chart library needed. */
@Composable
private fun MetricChart(
    points: List<Pair<Long, Float>>,
    unit: String,
    modifier: Modifier = Modifier,
) {
    if (points.size < 2) {
        Text(
            "not enough data",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(12.dp),
        )
        return
    }
    val line = MaterialTheme.colorScheme.primary
    val label = MaterialTheme.colorScheme.onSurfaceVariant
    val min = points.minOf { it.second }
    val max = points.maxOf { it.second }
    val range = (max - min).takeIf { it > 0f } ?: 1f
    val t0 = points.first().first
    val t1 = points.last().first
    val tRange = (t1 - t0).takeIf { it > 0 } ?: 1L

    Column(modifier) {
        Text(
            "%.0f$unit – %.0f$unit".format(min, max),
            style = MaterialTheme.typography.labelSmall,
            color = label,
        )
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val path = Path()
            points.forEachIndexed { i, (t, v) ->
                val x = (t - t0).toFloat() / tRange * size.width
                val y = size.height - (v - min) / range * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color = line, style = Stroke(width = 4f))
        }
    }
}
