package com.suteny0r.mangledbabyducks.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.RemoveCircle
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material.icons.filled.SensorsOff
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.suteny0r.mangledbabyducks.db.MapNode
import com.suteny0r.mangledbabyducks.db.NodeWithUser
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * NodeFilterParameters.swift, NodeDistanceFilterBounds: a latitude and longitude box around
 * a centre, 10% wider than the distance asked for. A box rather than a circle because that
 * is what iOS filters with; the corners reach further than the stated distance.
 */
class NodeDistanceFilterBounds private constructor(
    private val minLatitudeI: Int,
    private val maxLatitudeI: Int,
    private val minLongitudeI: Int,
    private val maxLongitudeI: Int,
    private val crossesAntimeridian: Boolean,
) {
    fun contains(latitudeI: Int, longitudeI: Int): Boolean {
        if (latitudeI < minLatitudeI || latitudeI > maxLatitudeI) return false
        return if (crossesAntimeridian) longitudeI >= minLongitudeI || longitudeI <= maxLongitudeI
        else longitudeI in minLongitudeI..maxLongitudeI
    }

    companion object {
        private const val EARTH_RADIUS_METERS = 6_371_009.0

        fun around(center: Pair<Double, Double>?, maxDistance: Double): NodeDistanceFilterBounds? {
            if (center == null || maxDistance <= 0) return null
            val (lat, lon) = center
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
            val distance = maxDistance * 1.1
            val latitudeCosine = cos(Math.toRadians(lat))
            if (abs(latitudeCosine) <= 0.000001) return null
            val deltaLatitude = Math.toDegrees(distance / EARTH_RADIUS_METERS)
            val deltaLongitude = Math.toDegrees(distance / (EARTH_RADIUS_METERS * latitudeCosine))
            val rawMinLongitude = lon - deltaLongitude
            val rawMaxLongitude = lon + deltaLongitude
            return NodeDistanceFilterBounds(
                minLatitudeI = scaled((lat - deltaLatitude).coerceAtLeast(-90.0)),
                maxLatitudeI = scaled((lat + deltaLatitude).coerceAtMost(90.0)),
                minLongitudeI = scaled(normalizedLongitude(rawMinLongitude)),
                maxLongitudeI = scaled(normalizedLongitude(rawMaxLongitude)),
                crossesAntimeridian = rawMinLongitude < -180 || rawMaxLongitude > 180,
            )
        }

        private fun scaled(value: Double): Int = (value * 1e7).toInt()

        private fun normalizedLongitude(value: Double): Double {
            var longitude = value
            while (longitude < -180) longitude += 360
            while (longitude > 180) longitude -= 360
            return longitude
        }
    }
}

/**
 * NodeFilterParameters.swift: the node list's filter set. Saved as one JSON preference, so
 * the next launch starts from the last set; the search text is deliberately not part of it.
 */
data class NodeFilter(
    val viaLora: Boolean = true,
    val viaMqtt: Boolean = true,
    val isOnline: Boolean = false,
    val hidesUnheardOnCurrentLora: Boolean = false,
    val isSigned: Boolean = false,
    val isPkiEncrypted: Boolean = false,
    val isFavorite: Boolean = false,
    /** iOS meaning: on shows only ignored nodes, off hides them. */
    val isIgnored: Boolean = false,
    val isEnvironment: Boolean = false,
    val distanceFilter: Boolean = false,
    /** Metres. iOS defaults to 800 000, which is not one of its own picker's values; this is the nearest that is. */
    val maxDistance: Double = 804_672.0,
    /** -1 is all, 0 is direct only, n is one to n hops. */
    val hopsAway: Int = -1,
    val roleFilter: Boolean = false,
    val deviceRoles: Set<Int> = emptySet(),
) {
    /** Whether any filter is narrowing the list (the search text aside). */
    val isFiltering: Boolean
        get() = isOnline || hidesUnheardOnCurrentLora || isSigned || isPkiEncrypted || isFavorite ||
            isIgnored || isEnvironment || distanceFilter || hopsAway >= 0 ||
            (roleFilter && deviceRoles.isNotEmpty()) || viaLora != viaMqtt

    /** The two "via" switches can never both be off: turning the last one off turns the other on. */
    fun withViaLora(on: Boolean) = copy(viaLora = on, viaMqtt = viaMqtt || !on)
    fun withViaMqtt(on: Boolean) = copy(viaMqtt = on, viaLora = viaLora || !on)

    /**
     * NodeFilterParameters.matches, minus the search text. [environmentNums] is the set of
     * nodes with environment telemetry; [bounds] is null when the distance filter is off
     * or has no centre to measure from, and a node with no position is never excluded by it.
     */
    fun matches(
        entry: NodeWithUser,
        nowMs: Long,
        environmentNums: Set<Long>,
        positions: Map<Long, MapNode>,
        bounds: NodeDistanceFilterBounds?,
    ): Boolean {
        val node = entry.node
        if (isFavorite && !node.favorite) return false
        if (viaLora && !viaMqtt && node.viaMqtt) return false
        if (!viaLora && viaMqtt && !node.viaMqtt) return false
        if (roleFilter && deviceRoles.isNotEmpty()) {
            val role = entry.user?.role ?: return false
            if (role !in deviceRoles) return false
        }
        if (hopsAway == 0) {
            if (node.hopsAway != 0) return false
        } else if (hopsAway > 0) {
            if (node.hopsAway <= 0 || node.hopsAway > hopsAway) return false
        }
        if (isOnline) {
            val lastHeard = node.lastHeard ?: return false
            if (lastHeard < nowMs - ONLINE_WINDOW_MS) return false
        }
        // NodeInfoEntity.isUnheardOnCurrentLora.
        if (hidesUnheardOnCurrentLora && node.heardOnCurrentLora == false && !node.viaMqtt) return false
        if (isSigned && !node.hasXeddsaSigned) return false
        if (isPkiEncrypted && entry.user?.pkiEncrypted != true) return false
        if (isIgnored != node.ignored) return false
        if (isEnvironment && node.num !in environmentNums) return false
        if (distanceFilter && bounds != null) {
            val position = positions[node.num]
            if (position != null && !bounds.contains(position.latitudeI, position.longitudeI)) return false
        }
        return true
    }

    fun toJson(): String = JSONObject()
        .put("viaLora", viaLora)
        .put("viaMqtt", viaMqtt)
        .put("isOnline", isOnline)
        .put("hidesUnheardOnCurrentLora", hidesUnheardOnCurrentLora)
        .put("isSigned", isSigned)
        .put("isPkiEncrypted", isPkiEncrypted)
        .put("isFavorite", isFavorite)
        .put("isIgnored", isIgnored)
        .put("isEnvironment", isEnvironment)
        .put("distanceFilter", distanceFilter)
        .put("maxDistance", maxDistance)
        .put("hopsAway", hopsAway)
        .put("roleFilter", roleFilter)
        .put("deviceRoles", JSONArray(deviceRoles.sorted()))
        .toString()

    companion object {
        /** NodeInfoEntity.isOnline: heard within two hours. */
        const val ONLINE_WINDOW_MS = 7_200_000L

        /** MeshMapDistances, in metres. */
        val DISTANCES = listOf(
            3218.69, 8046.72, 16093.4, 40233.6, 80467.2, 160934.0, 321869.0,
            804672.0, 1609000.0, 2414016.0, 4023360.0,
        )

        /** DeviceRoles.allCases, in the order iOS lists them, with its names. */
        val ROLES = listOf(
            0 to "Client",
            1 to "Client Mute",
            8 to "Client Hidden",
            5 to "Tracker",
            9 to "Lost and Found",
            6 to "Sensor",
            7 to "TAK",
            10 to "TAK Tracker",
            2 to "Router",
            11 to "Router Late",
            12 to "Client Base",
            4 to "Repeater (Deprecated)",
        )

        fun fromJson(json: String): NodeFilter = runCatching {
            val o = JSONObject(json)
            val d = NodeFilter()
            val roles = o.optJSONArray("deviceRoles")
            val viaLora = o.optBoolean("viaLora", d.viaLora)
            val viaMqtt = o.optBoolean("viaMqtt", d.viaMqtt)
            NodeFilter(
                viaLora = viaLora || !viaMqtt,
                viaMqtt = viaMqtt,
                isOnline = o.optBoolean("isOnline", d.isOnline),
                hidesUnheardOnCurrentLora = o.optBoolean("hidesUnheardOnCurrentLora", d.hidesUnheardOnCurrentLora),
                isSigned = o.optBoolean("isSigned", d.isSigned),
                isPkiEncrypted = o.optBoolean("isPkiEncrypted", d.isPkiEncrypted),
                isFavorite = o.optBoolean("isFavorite", d.isFavorite),
                isIgnored = o.optBoolean("isIgnored", d.isIgnored),
                isEnvironment = o.optBoolean("isEnvironment", d.isEnvironment),
                distanceFilter = o.optBoolean("distanceFilter", d.distanceFilter),
                maxDistance = o.optDouble("maxDistance", d.maxDistance),
                hopsAway = o.optInt("hopsAway", d.hopsAway).coerceIn(-1, 7),
                roleFilter = o.optBoolean("roleFilter", d.roleFilter),
                deviceRoles = if (roles == null) emptySet() else (0 until roles.length()).map { roles.getInt(it) }.toSet(),
            )
        }.getOrDefault(NodeFilter())
    }
}

/** MeshMapDistances.description: whole miles or kilometres in the locale's units. */
private fun filterDistanceLabel(meters: Double): String {
    val number = NumberFormat.getIntegerInstance()
    if (imperialUnits) return "${number.format((meters / 1609.344).roundToInt())} mi"
    val km = meters / 1000
    return if (km < 10) "%.1f km".format(km) else "${number.format(km.roundToInt())} km"
}

/**
 * NodeListFilter.swift: the "Node Filters" sheet. Every change applies and saves at once,
 * as the iOS toggles do; there is no Apply button.
 *
 * @param hasLocation whether there is a centre for the distance filter to measure from.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeFilterSheet(
    filter: NodeFilter,
    hasLocation: Boolean,
    onChange: (NodeFilter) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Node Filters", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Done") }
            }
            FilterToggle(Icons.Filled.Sensors, "Via Lora", filter.viaLora) { onChange(filter.withViaLora(it)) }
            FilterToggle(Icons.Filled.CloudUpload, "Via Mqtt", filter.viaMqtt) { onChange(filter.withViaMqtt(it)) }
            FilterToggle(Icons.Filled.CheckCircle, "Online", filter.isOnline) { onChange(filter.copy(isOnline = it)) }
            FilterToggle(Icons.Filled.SensorsOff, "Hide Not Heard on Current LoRa", filter.hidesUnheardOnCurrentLora) {
                onChange(filter.copy(hidesUnheardOnCurrentLora = it))
            }
            FilterToggle(NodeSecurityIndicator.SIGNED.icon, "Signed", filter.isSigned) { onChange(filter.copy(isSigned = it)) }
            FilterToggle(Icons.Filled.Lock, "Encrypted", filter.isPkiEncrypted) { onChange(filter.copy(isPkiEncrypted = it)) }
            FilterToggle(Icons.Filled.Star, "Favorites", filter.isFavorite) { onChange(filter.copy(isFavorite = it)) }
            FilterToggle(Icons.Filled.RemoveCircle, "Ignored", filter.isIgnored) { onChange(filter.copy(isIgnored = it)) }
            FilterToggle(Icons.Filled.Thermostat, "Environment", filter.isEnvironment) { onChange(filter.copy(isEnvironment = it)) }

            // Without a centre the filter cannot be turned on, but one left on can be turned off.
            FilterToggle(
                Icons.Filled.Map, "Distance", filter.distanceFilter,
                enabled = hasLocation || filter.distanceFilter,
                divider = !filter.distanceFilter,
            ) { onChange(filter.copy(distanceFilter = it)) }
            if (!hasLocation) {
                Text(
                    "Requires a position from your radio or phone",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
                )
            }
            if (filter.distanceFilter && hasLocation) {
                var picking by remember { mutableStateOf(false) }
                Row(
                    Modifier.fillMaxWidth().clickable { picking = true }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Straighten, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("Show nodes", modifier = Modifier.weight(1f))
                    Box {
                        Text(filterDistanceLabel(filter.maxDistance), color = MaterialTheme.colorScheme.primary)
                        DropdownMenu(expanded = picking, onDismissRequest = { picking = false }) {
                            NodeFilter.DISTANCES.forEach { meters ->
                                DropdownMenuItem(
                                    text = { Text(filterDistanceLabel(meters)) },
                                    onClick = {
                                        picking = false
                                        onChange(filter.copy(maxDistance = meters))
                                    },
                                )
                            }
                        }
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 56.dp))
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Route, contentDescription = null, modifier = Modifier.size(24.dp))
                    Spacer(Modifier.width(16.dp))
                    Text("Hops Away")
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("All")
                    Slider(
                        value = filter.hopsAway.toFloat(),
                        onValueChange = { onChange(filter.copy(hopsAway = it.roundToInt())) },
                        valueRange = -1f..7f,
                        steps = 7,
                        modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                    )
                    Text("7")
                }
                if (filter.hopsAway >= 0) {
                    Text(
                        when (filter.hopsAway) {
                            0 -> "Direct"
                            1 -> "1 hop away"
                            else -> "${filter.hopsAway} or less hops away"
                        }
                    )
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 56.dp))

            FilterToggle(Icons.Filled.Smartphone, "Roles", filter.roleFilter, divider = false) {
                onChange(filter.copy(roleFilter = it))
            }
            if (filter.roleFilter) {
                NodeFilter.ROLES.forEach { (role, name) ->
                    val selected = role in filter.deviceRoles
                    val toggle = {
                        onChange(filter.copy(deviceRoles = if (selected) filter.deviceRoles - role else filter.deviceRoles + role))
                    }
                    Row(
                        Modifier.fillMaxWidth().clickable(onClick = toggle).padding(start = 44.dp, end = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = selected, onCheckedChange = { toggle() })
                        Text(name)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun FilterToggle(
    icon: ImageVector,
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    divider: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(16.dp))
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
    if (divider) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(start = 56.dp))
    }
}
