package com.suteny0r.mangledbabyducks.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddBox
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Autorenew
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suteny0r.mangledbabyducks.db.ChannelEntity
import com.suteny0r.mangledbabyducks.ui.theme.IosGreen
import java.util.Base64
import kotlin.math.roundToInt

/**
 * Port of Channels.swift: the radio's channel list under a frequency summary, each row
 * opening the ChannelForm editor, plus Add Channel while fewer than 8 are defined.
 */
@Composable
fun ChannelsScreen(vm: SettingsViewModel, connected: Boolean, onBack: () -> Unit) {
    val channels by vm.channels.collectAsState()
    val lora by vm.loraConfig.collectAsState()
    val primaryChannelName by vm.primaryChannelName.collectAsState()
    var editing by remember { mutableStateOf<ChannelDraft?>(null) }

    val calculator = LoRaChannelCalculator(lora)
    val slot = calculator.effectiveSlot(LoRaChannelCalculator.hashName(primaryChannelName, lora))
    val frequency = calculator.frequencyMHz(slot)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        RoundBackButton(onBack, Modifier.padding(start = 12.dp, top = 8.dp))
        Text(
            "Channels",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 12.dp),
        )
        GroupCard(Modifier.padding(horizontal = 16.dp)) {
            // ChannelConfigSummaryRow: region on the left, frequency and slot trailing.
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.SettingsInputAntenna,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    calculator.regionName,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                Text(
                    if (frequency > 0) "%.3f MHz  •  Slot %d".format(frequency, slot) else "Frequency unknown",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            HorizontalDivider()
            val shown = channels.filter { it.role != 0 }
            shown.forEach { channel ->
                ChannelRow(channel) { editing = ChannelDraft.of(channel) }
                HorizontalDivider()
            }
            if (shown.size < 8) {
                Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                    Button(
                        onClick = { editing = ChannelDraft.new(firstFreeIndex(channels), vm) },
                        shape = CircleShape,
                        enabled = connected,
                    ) {
                        Icon(Icons.Default.AddBox, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("Add Channel")
                    }
                }
            }
        }
        Spacer(Modifier.padding(bottom = 24.dp))
    }

    editing?.let { draft ->
        ChannelEditor(
            draft = draft,
            vm = vm,
            connected = connected,
            onDismiss = { editing = null },
        )
    }
}

/** The first index 0..7 not already taken, which is iOS's firstMissingChannelIndex. */
private fun firstFreeIndex(channels: List<ChannelEntity>): Int {
    val used = channels.filter { it.role != 0 }.map { it.index }.toSet()
    return (0..7).firstOrNull { it !in used } ?: 1
}

/** ChannelRow: index disc, lock glyph, name, role caption. */
@Composable
private fun ChannelRow(channel: ChannelEntity, onClick: () -> Unit) {
    val title = when {
        !channel.name.isNullOrEmpty() -> channel.name!!
        channel.role == 1 -> "Primary Channel"
        else -> "Channel ${channel.index}"
    }
    val subtitle = if (channel.role == 1) "Primary channel" else "Channel ${channel.index}"
    ListItem(
        leadingContent = { NodeAvatar(channel.index.toString(), CHANNEL_DISC, 46.dp) },
        headlineContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val real = (channel.psk?.size ?: 0) > 1
                Icon(
                    if (real) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = null,
                    tint = if (real) IosGreen else Color(0xFFB8860B),
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(title, fontWeight = FontWeight.SemiBold)
            }
        },
        supportingContent = { Text(subtitle) },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (channel.positionPrecision > 0) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = "Position sharing",
                        tint = IosGreen,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                }
                Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.outline)
            }
        },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** CircleText(..., color: .accentColor).brightness(0.1) for the channel disc. */
private const val CHANNEL_DISC = 0x5E8BE6L

/** The editor's working copy, so Save is the only thing that touches the radio. */
data class ChannelDraft(
    val index: Int,
    val name: String,
    val keyBase64: String,
    val keySize: Int,
    val role: Int,
    val uplink: Boolean,
    val downlink: Boolean,
    val positionPrecision: Int,
    val isNew: Boolean,
) {
    companion object {
        fun of(channel: ChannelEntity): ChannelDraft {
            val psk = channel.psk ?: ByteArray(0)
            return ChannelDraft(
                index = channel.index,
                name = channel.name.orEmpty(),
                keyBase64 = Base64.getEncoder().encodeToString(psk),
                keySize = if (psk.size == 1) -1 else psk.size,
                role = channel.role,
                uplink = channel.uplinkEnabled,
                downlink = channel.downlinkEnabled,
                positionPrecision = channel.positionPrecision,
                isNew = false,
            )
        }

        /** Add Channel: a fresh 128 bit key, secondary role, positions off. */
        fun new(index: Int, vm: SettingsViewModel): ChannelDraft = ChannelDraft(
            index = index,
            name = "",
            keyBase64 = Base64.getEncoder().encodeToString(vm.generateChannelKey(16)),
            keySize = 16,
            role = 2,
            uplink = false,
            downlink = false,
            positionPrecision = 0,
            isNew = true,
        )
    }
}

private val KEY_SIZES = listOf(0 to "Empty", -1 to "Default", 1 to "1 byte", 16 to "128 bit", 32 to "256 bit")

/** ChannelForm.swift: details, position, MQTT, and a Save that writes the radio. */
@Composable
private fun ChannelEditor(
    draft: ChannelDraft,
    vm: SettingsViewModel,
    connected: Boolean,
    onDismiss: () -> Unit,
) {
    var name by remember(draft) { mutableStateOf(draft.name) }
    var keySize by remember(draft) { mutableStateOf(draft.keySize) }
    var key by remember(draft) { mutableStateOf(draft.keyBase64) }
    var role by remember(draft) { mutableStateOf(draft.role) }
    var uplink by remember(draft) { mutableStateOf(draft.uplink) }
    var downlink by remember(draft) { mutableStateOf(draft.downlink) }
    var precision by remember(draft) { mutableStateOf(draft.positionPrecision) }
    var sizeMenu by remember { mutableStateOf(false) }

    val decoded = remember(key) { runCatching { Base64.getDecoder().decode(key) }.getOrNull() }
    val validKey = decoded != null && (keySize == -1 || decoded.size == keySize)
    val positionsEnabled = precision > 0
    val preciseLocation = precision >= 32

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.isNew) "New Channel" else "Channel ${draft.index}") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                SectionCaption("Channel Details")
                OutlinedTextField(
                    value = name,
                    // The firmware caps a channel name at 11 bytes and spaces are dropped.
                    onValueChange = { text ->
                        var cleaned = text.replace(" ", "")
                        while (cleaned.toByteArray().size > 11) cleaned = cleaned.dropLast(1)
                        name = cleaned
                    },
                    label = { Text("Name") },
                    placeholder = { Text("Channel Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Key Size", Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { sizeMenu = true }) {
                            Text(KEY_SIZES.firstOrNull { it.first == keySize }?.second ?: "$keySize bytes")
                        }
                        DropdownMenu(expanded = sizeMenu, onDismissRequest = { sizeMenu = false }) {
                            KEY_SIZES.forEach { (size, label) ->
                                DropdownMenuItem(
                                    text = { Text(label) },
                                    onClick = {
                                        keySize = size
                                        key = if (size == -1) "AQ=="
                                        else Base64.getEncoder().encodeToString(vm.generateChannelKey(size))
                                        sizeMenu = false
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = {
                        key = if (keySize == -1) "AQ=="
                        else Base64.getEncoder().encodeToString(vm.generateChannelKey(keySize))
                    }) {
                        Icon(Icons.Default.Autorenew, contentDescription = "Generate channel key")
                    }
                }
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it },
                    label = { Text("Key") },
                    singleLine = false,
                    enabled = keySize > 0,
                    isError = !validKey,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Channel Role", Modifier.weight(1f))
                    if (draft.index == 0 || role == 1) {
                        Text("Primary", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        TextButton(onClick = { role = if (role == 2) 0 else 2 }) {
                            Text(if (role == 2) "Secondary" else "Disabled")
                        }
                    }
                }

                SectionCaption("Position")
                SwitchLine(
                    if (role == 1) "Positions Enabled" else "Allow Position Requests",
                    positionsEnabled,
                ) { on -> precision = if (on) 14 else 0 }
                if (positionsEnabled) {
                    if (keySize > 1 && key != "AQ==" && role > 0) {
                        SwitchLine("Precise Location", preciseLocation) { on ->
                            precision = if (on) 32 else 14
                        }
                    }
                    if (!preciseLocation) {
                        Text(
                            precisionLabel(precision),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Slider(
                            value = precision.coerceIn(12, 15).toFloat(),
                            onValueChange = { precision = it.roundToInt() },
                            valueRange = 12f..15f,
                            steps = 2,
                        )
                    }
                }

                SectionCaption("MQTT")
                SwitchLine("Uplink Enabled", uplink, icon = Icons.Default.ArrowUpward) { uplink = it }
                SwitchLine("Downlink Enabled", downlink, icon = Icons.Default.ArrowDownward) { downlink = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = connected && validKey,
                onClick = {
                    vm.saveChannel(
                        index = draft.index,
                        name = name,
                        psk = decoded ?: ByteArray(0),
                        role = if (draft.index == 0) 1 else role,
                        uplink = uplink,
                        downlink = downlink,
                        positionPrecision = precision,
                    )
                    onDismiss()
                },
            ) {
                Icon(Icons.Outlined.Save, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun SectionCaption(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp),
    )
}

@Composable
private fun SwitchLine(
    title: String,
    checked: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(title, Modifier.weight(1f), fontSize = 15.sp)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** PositionPrecision.description, in the metric form the slider range covers. */
private fun precisionLabel(precision: Int): String = when (precision) {
    12 -> "Within 5.8 km"
    13 -> "Within 2.9 km"
    14 -> "Within 1.5 km"
    15 -> "Within 730 m"
    else -> "Precise location"
}
