package com.suteny0r.mangledbabyducks.ui

import android.util.Base64
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import org.meshtastic.proto.ConfigProtos

/**
 * The radio config sections this app can read and write, one screen each, mirroring the
 * "Radio Configuration" / "Device Configuration" lists in the iOS app's `Settings.swift`.
 * Module configs (`module.*`) are not here yet.
 */
enum class ConfigSection(val title: String, val summary: String) {
    LORA("LoRa", "Region, modem preset, hop limit, transmit"),
    DEVICE("Device", "Role, rebroadcast, node info interval"),
    POSITION("Position", "GPS mode, broadcast interval, position flags"),
    BLUETOOTH("Bluetooth", "Pairing mode and PIN"),
    DISPLAY("Display", "Screen timeout, units, orientation"),
    NETWORK("Network", "WiFi, Ethernet, NTP, syslog"),
    POWER("Power", "Sleep intervals, shutdown, battery"),
    SECURITY("Security", "Keys, managed mode, serial console"),
}

/** One config section's form. The caller supplies the header and back affordance. */
@Composable
fun ConfigSectionDetail(section: ConfigSection, vm: SettingsViewModel, connected: Boolean) {
    // A result left over from the previous section would read as this one's outcome.
    LaunchedEffect(section) { vm.clearWriteResult() }
    when (section) {
        ConfigSection.LORA -> LoRaSection(vm, connected)
        ConfigSection.DEVICE -> DeviceSection(vm, connected)
        ConfigSection.POSITION -> PositionSection(vm, connected)
        ConfigSection.BLUETOOTH -> BluetoothSection(vm, connected)
        ConfigSection.DISPLAY -> DisplaySection(vm, connected)
        ConfigSection.NETWORK -> NetworkSection(vm, connected)
        ConfigSection.POWER -> PowerSection(vm, connected)
        ConfigSection.SECURITY -> SecuritySection(vm, connected)
    }
}

// ---------------------------------------------------------------- sections

@Composable
private fun LoRaSection(vm: SettingsViewModel, connected: Boolean) {
    val current by vm.loraConfig.collectAsState()
    ConfigForm(current, connected, vm, vm::writeLoraConfig) { draft, update ->
        ConfigEnumRow(
            "Region",
            draft.region,
            protoEntries(ConfigProtos.Config.LoRaConfig.RegionCode.entries),
        ) { update(draft.toBuilder().setRegion(it).build()) }
        ConfigEnumRow(
            "Modem preset",
            draft.modemPreset,
            protoEntries(ConfigProtos.Config.LoRaConfig.ModemPreset.entries),
            // A preset choice only takes effect with usePreset set; without it the
            // radio keeps using the custom bandwidth/spread factor/coding rate.
            subtitle = if (draft.usePreset) null else
                "custom: bw ${draft.bandwidth}, sf ${draft.spreadFactor}, cr ${draft.codingRate}",
        ) { update(draft.toBuilder().setModemPreset(it).setUsePreset(true).build()) }
        ConfigEnumRow("Hop limit", draft.hopLimit, (1..7).toList()) {
            update(draft.toBuilder().setHopLimit(it).build())
        }
        ConfigSwitchRow("Transmit enabled", null, draft.txEnabled) {
            update(draft.toBuilder().setTxEnabled(it).build())
        }
        ConfigNumberRow(
            "Transmit power",
            draft.txPower,
            suffix = "dBm",
            allowZero = true,
            unsigned = false,
        ) {
            update(draft.toBuilder().setTxPower(it).build())
        }
        ConfigNumberRow(
            "Frequency slot",
            draft.channelNum,
            hint = "0 uses the default slot for the region and preset",
            allowZero = true,
        ) { update(draft.toBuilder().setChannelNum(it).build()) }
        ConfigSwitchRow(
            "Boosted RX gain",
            "SX126x receivers only",
            draft.sx126XRxBoostedGain,
        ) { update(draft.toBuilder().setSx126XRxBoostedGain(it).build()) }
        ConfigSwitchRow(
            "Override duty cycle",
            "Ignore the region's legal duty cycle limit",
            draft.overrideDutyCycle,
        ) { update(draft.toBuilder().setOverrideDutyCycle(it).build()) }
        ConfigSwitchRow("Ignore MQTT", null, draft.ignoreMqtt) {
            update(draft.toBuilder().setIgnoreMqtt(it).build())
        }
        ConfigSwitchRow("OK to MQTT", "Let gateways forward this node's packets", draft.configOkToMqtt) {
            update(draft.toBuilder().setConfigOkToMqtt(it).build())
        }
    }
}

@Composable
private fun DeviceSection(vm: SettingsViewModel, connected: Boolean) {
    val current by vm.deviceConfig.collectAsState()
    ConfigForm(current, connected, vm, vm::writeDeviceConfig) { draft, update ->
        ConfigEnumRow(
            "Role",
            draft.role,
            protoEntries(ConfigProtos.Config.DeviceConfig.Role.entries),
        ) { update(draft.toBuilder().setRole(it).build()) }
        ConfigEnumRow(
            "Rebroadcast mode",
            draft.rebroadcastMode,
            protoEntries(ConfigProtos.Config.DeviceConfig.RebroadcastMode.entries),
        ) { update(draft.toBuilder().setRebroadcastMode(it).build()) }
        ConfigNumberRow(
            "Node info broadcast",
            draft.nodeInfoBroadcastSecs,
            suffix = "s",
            hint = "How often this node re-announces its name; 3600 is typical",
        ) { update(draft.toBuilder().setNodeInfoBroadcastSecs(it).build()) }
        ConfigTextRow(
            "Time zone",
            draft.tzdef,
            hint = "POSIX TZ string, for example EST5EDT,M3.2.0,M11.1.0",
            maxLen = 64,
        ) { update(draft.toBuilder().setTzdef(it).build()) }
        ConfigEnumRow(
            "Buzzer mode",
            draft.buzzerMode,
            protoEntries(ConfigProtos.Config.DeviceConfig.BuzzerMode.entries),
        ) { update(draft.toBuilder().setBuzzerMode(it).build()) }
        ConfigSwitchRow("LED heartbeat disabled", null, draft.ledHeartbeatDisabled) {
            update(draft.toBuilder().setLedHeartbeatDisabled(it).build())
        }
        ConfigSwitchRow("Double tap as button press", null, draft.doubleTapAsButtonPress) {
            update(draft.toBuilder().setDoubleTapAsButtonPress(it).build())
        }
        ConfigSwitchRow("Disable triple click", "Triple click normally toggles GPS", draft.disableTripleClick) {
            update(draft.toBuilder().setDisableTripleClick(it).build())
        }
    }
}

@Composable
private fun PositionSection(vm: SettingsViewModel, connected: Boolean) {
    val current by vm.positionConfig.collectAsState()
    ConfigForm(current, connected, vm, vm::writePositionConfig) { draft, update ->
        ConfigEnumRow(
            "GPS mode",
            draft.gpsMode,
            protoEntries(ConfigProtos.Config.PositionConfig.GpsMode.entries),
        ) { update(draft.toBuilder().setGpsMode(it).build()) }
        ConfigNumberRow("Position broadcast", draft.positionBroadcastSecs, suffix = "s") {
            update(draft.toBuilder().setPositionBroadcastSecs(it).build())
        }
        ConfigSwitchRow(
            "Smart position broadcast",
            "Broadcast on movement instead of on a fixed interval",
            draft.positionBroadcastSmartEnabled,
        ) { update(draft.toBuilder().setPositionBroadcastSmartEnabled(it).build()) }
        ConfigNumberRow(
            "Smart minimum distance",
            draft.broadcastSmartMinimumDistance,
            suffix = "m",
        ) { update(draft.toBuilder().setBroadcastSmartMinimumDistance(it).build()) }
        ConfigNumberRow(
            "Smart minimum interval",
            draft.broadcastSmartMinimumIntervalSecs,
            suffix = "s",
        ) { update(draft.toBuilder().setBroadcastSmartMinimumIntervalSecs(it).build()) }
        ConfigNumberRow("GPS update interval", draft.gpsUpdateInterval, suffix = "s") {
            update(draft.toBuilder().setGpsUpdateInterval(it).build())
        }
        ConfigSwitchRow(
            "Fixed position",
            "Keep broadcasting the last known position and stop using the GPS",
            draft.fixedPosition,
        ) { update(draft.toBuilder().setFixedPosition(it).build()) }
        PositionFlagsRow(draft.positionFlags) {
            update(draft.toBuilder().setPositionFlags(it).build())
        }
        ConfigNumberRow("GPS RX GPIO", draft.rxGpio, allowZero = true) {
            update(draft.toBuilder().setRxGpio(it).build())
        }
        ConfigNumberRow("GPS TX GPIO", draft.txGpio, allowZero = true) {
            update(draft.toBuilder().setTxGpio(it).build())
        }
        ConfigNumberRow("GPS enable GPIO", draft.gpsEnGpio, allowZero = true) {
            update(draft.toBuilder().setGpsEnGpio(it).build())
        }
    }
}

@Composable
private fun BluetoothSection(vm: SettingsViewModel, connected: Boolean) {
    val current by vm.bluetoothConfig.collectAsState()
    ConfigForm(
        current,
        connected,
        vm,
        vm::writeBluetoothConfig,
        note = "Turning Bluetooth off, or changing the pairing mode, ends this app's " +
            "connection to the radio and may need re-pairing in Android settings.",
    ) { draft, update ->
        ConfigSwitchRow("Bluetooth enabled", null, draft.enabled) {
            update(draft.toBuilder().setEnabled(it).build())
        }
        ConfigEnumRow(
            "Pairing mode",
            draft.mode,
            protoEntries(ConfigProtos.Config.BluetoothConfig.PairingMode.entries),
        ) { update(draft.toBuilder().setMode(it).build()) }
        ConfigNumberRow(
            "Fixed PIN",
            draft.fixedPin,
            hint = "Six digits, used when the pairing mode is FIXED_PIN",
            enabled = draft.mode == ConfigProtos.Config.BluetoothConfig.PairingMode.FIXED_PIN,
        ) { update(draft.toBuilder().setFixedPin(it).build()) }
    }
}

@Composable
private fun DisplaySection(vm: SettingsViewModel, connected: Boolean) {
    val current by vm.displayConfig.collectAsState()
    ConfigForm(current, connected, vm, vm::writeDisplayConfig) { draft, update ->
        ConfigNumberRow(
            "Screen on time",
            draft.screenOnSecs,
            suffix = "s",
            hint = "0 keeps the screen on forever",
            allowZero = true,
        ) { update(draft.toBuilder().setScreenOnSecs(it).build()) }
        ConfigNumberRow(
            "Screen carousel",
            draft.autoScreenCarouselSecs,
            suffix = "s",
            hint = "0 disables automatic page cycling",
            allowZero = true,
        ) { update(draft.toBuilder().setAutoScreenCarouselSecs(it).build()) }
        ConfigEnumRow(
            "Units",
            draft.units,
            protoEntries(ConfigProtos.Config.DisplayConfig.DisplayUnits.entries),
        ) { update(draft.toBuilder().setUnits(it).build()) }
        ConfigEnumRow(
            "Display mode",
            draft.displaymode,
            protoEntries(ConfigProtos.Config.DisplayConfig.DisplayMode.entries),
        ) { update(draft.toBuilder().setDisplaymode(it).build()) }
        ConfigEnumRow(
            "OLED type",
            draft.oled,
            protoEntries(ConfigProtos.Config.DisplayConfig.OledType.entries),
        ) { update(draft.toBuilder().setOled(it).build()) }
        ConfigEnumRow(
            "Compass orientation",
            draft.compassOrientation,
            protoEntries(ConfigProtos.Config.DisplayConfig.CompassOrientation.entries),
        ) { update(draft.toBuilder().setCompassOrientation(it).build()) }
        ConfigSwitchRow("Flip screen", null, draft.flipScreen) {
            update(draft.toBuilder().setFlipScreen(it).build())
        }
        ConfigSwitchRow("Bold heading", null, draft.headingBold) {
            update(draft.toBuilder().setHeadingBold(it).build())
        }
        ConfigSwitchRow("Wake on tap or motion", null, draft.wakeOnTapOrMotion) {
            update(draft.toBuilder().setWakeOnTapOrMotion(it).build())
        }
        ConfigSwitchRow("12 hour clock", null, draft.use12HClock) {
            update(draft.toBuilder().setUse12HClock(it).build())
        }
        ConfigSwitchRow("Long node names", null, draft.useLongNodeName) {
            update(draft.toBuilder().setUseLongNodeName(it).build())
        }
        ConfigSwitchRow("Message bubbles", null, draft.enableMessageBubbles) {
            update(draft.toBuilder().setEnableMessageBubbles(it).build())
        }
    }
}

@Composable
private fun NetworkSection(vm: SettingsViewModel, connected: Boolean) {
    val current by vm.networkConfig.collectAsState()
    ConfigForm(
        current,
        connected,
        vm,
        vm::writeNetworkConfig,
        note = "WiFi and Ethernet are only present on some hardware; on a radio without " +
            "them these settings do nothing.",
    ) { draft, update ->
        ConfigSwitchRow("WiFi enabled", null, draft.wifiEnabled) {
            update(draft.toBuilder().setWifiEnabled(it).build())
        }
        ConfigTextRow("WiFi SSID", draft.wifiSsid, maxLen = 32, enabled = draft.wifiEnabled) {
            update(draft.toBuilder().setWifiSsid(it).build())
        }
        ConfigTextRow(
            "WiFi password",
            draft.wifiPsk,
            maxLen = 64,
            masked = true,
            enabled = draft.wifiEnabled,
        ) { update(draft.toBuilder().setWifiPsk(it).build()) }
        ConfigSwitchRow("Ethernet enabled", null, draft.ethEnabled) {
            update(draft.toBuilder().setEthEnabled(it).build())
        }
        ConfigSwitchRow("IPv6 enabled", null, draft.ipv6Enabled) {
            update(draft.toBuilder().setIpv6Enabled(it).build())
        }
        ConfigEnumRow(
            "Address mode",
            draft.addressMode,
            protoEntries(ConfigProtos.Config.NetworkConfig.AddressMode.entries),
        ) { update(draft.toBuilder().setAddressMode(it).build()) }
        val static = draft.addressMode == ConfigProtos.Config.NetworkConfig.AddressMode.STATIC
        ConfigTextRow("IP address", ipToString(draft.ipv4Config.ip), enabled = static, maxLen = 15) {
            update(
                draft.toBuilder()
                    .setIpv4Config(draft.ipv4Config.toBuilder().setIp(ipToInt(it)))
                    .build()
            )
        }
        ConfigTextRow("Gateway", ipToString(draft.ipv4Config.gateway), enabled = static, maxLen = 15) {
            update(
                draft.toBuilder()
                    .setIpv4Config(draft.ipv4Config.toBuilder().setGateway(ipToInt(it)))
                    .build()
            )
        }
        ConfigTextRow("Subnet mask", ipToString(draft.ipv4Config.subnet), enabled = static, maxLen = 15) {
            update(
                draft.toBuilder()
                    .setIpv4Config(draft.ipv4Config.toBuilder().setSubnet(ipToInt(it)))
                    .build()
            )
        }
        ConfigTextRow("DNS server", ipToString(draft.ipv4Config.dns), enabled = static, maxLen = 15) {
            update(
                draft.toBuilder()
                    .setIpv4Config(draft.ipv4Config.toBuilder().setDns(ipToInt(it)))
                    .build()
            )
        }
        ConfigTextRow("NTP server", draft.ntpServer, maxLen = 64) {
            update(draft.toBuilder().setNtpServer(it).build())
        }
        ConfigTextRow("Syslog server", draft.rsyslogServer, maxLen = 64) {
            update(draft.toBuilder().setRsyslogServer(it).build())
        }
    }
}

@Composable
private fun PowerSection(vm: SettingsViewModel, connected: Boolean) {
    val current by vm.powerConfig.collectAsState()
    ConfigForm(current, connected, vm, vm::writePowerConfig) { draft, update ->
        ConfigSwitchRow(
            "Power saving",
            "For nodes that sleep between transmissions",
            draft.isPowerSaving,
        ) { update(draft.toBuilder().setIsPowerSaving(it).build()) }
        ConfigNumberRow(
            "Shutdown on battery after",
            draft.onBatteryShutdownAfterSecs,
            suffix = "s",
            hint = "0 never shuts down",
            allowZero = true,
        ) { update(draft.toBuilder().setOnBatteryShutdownAfterSecs(it).build()) }
        ConfigNumberRow(
            "Wait for Bluetooth",
            draft.waitBluetoothSecs,
            suffix = "s",
            hint = "How long the radio stays awake waiting for a phone",
        ) { update(draft.toBuilder().setWaitBluetoothSecs(it).build()) }
        ConfigNumberRow("Light sleep", draft.lsSecs, suffix = "s", allowZero = true) {
            update(draft.toBuilder().setLsSecs(it).build())
        }
        ConfigNumberRow("Super deep sleep", draft.sdsSecs, suffix = "s", allowZero = true) {
            update(draft.toBuilder().setSdsSecs(it).build())
        }
        ConfigNumberRow("Minimum wake", draft.minWakeSecs, suffix = "s", allowZero = true) {
            update(draft.toBuilder().setMinWakeSecs(it).build())
        }
        ConfigFloatRow(
            "ADC multiplier override",
            draft.adcMultiplierOverride,
            hint = "0 uses the firmware default for this board",
        ) { update(draft.toBuilder().setAdcMultiplierOverride(it).build()) }
        ConfigNumberRow(
            "Battery INA address",
            draft.deviceBatteryInaAddress,
            hint = "I2C address of an INA current sensor, 0 for none",
            allowZero = true,
        ) { update(draft.toBuilder().setDeviceBatteryInaAddress(it).build()) }
    }
}

@Composable
private fun SecuritySection(vm: SettingsViewModel, connected: Boolean) {
    val current by vm.securityConfig.collectAsState()
    ConfigForm(
        current,
        connected,
        vm,
        vm::writeSecurityConfig,
        note = "Managed mode locks this radio out of client configuration: it can then " +
            "only be changed by a node holding an admin key. The private key is never " +
            "shown or written by this app.",
    ) { draft, update ->
        ConfigInfoRow(
            "Public key",
            draft.publicKey.toByteArray()
                .takeIf { it.isNotEmpty() }
                ?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
                ?: "not set",
            selectable = true,
        )
        ConfigInfoRow("Private key", if (draft.privateKey.isEmpty) "not set" else "set")
        ConfigInfoRow(
            "Admin keys",
            draft.adminKeyCount.let { if (it == 0) "none" else "$it configured" },
        )
        ConfigEnumRow(
            "Packet signature policy",
            draft.packetSignaturePolicy,
            protoEntries(ConfigProtos.Config.SecurityConfig.PacketSignaturePolicy.entries),
        ) { update(draft.toBuilder().setPacketSignaturePolicy(it).build()) }
        ConfigSwitchRow(
            "Managed mode",
            "Only an admin key may change this radio's config",
            draft.isManaged,
        ) { update(draft.toBuilder().setIsManaged(it).build()) }
        ConfigSwitchRow("Serial console", null, draft.serialEnabled) {
            update(draft.toBuilder().setSerialEnabled(it).build())
        }
        ConfigSwitchRow("Debug log over API", null, draft.debugLogApiEnabled) {
            update(draft.toBuilder().setDebugLogApiEnabled(it).build())
        }
        ConfigSwitchRow(
            "Admin channel",
            "Accept legacy unauthenticated admin messages on the admin channel",
            draft.adminChannelEnabled,
        ) { update(draft.toBuilder().setAdminChannelEnabled(it).build()) }
    }
}

// ---------------------------------------------------------------- form shell

/**
 * A whole section edited as a draft, then written in one admin exchange. One write per
 * edited field would make the radio save and reboot after every tap, so the Save button
 * is the only thing that touches the radio.
 */
@Composable
private fun <T : Any> ConfigForm(
    current: T?,
    connected: Boolean,
    vm: SettingsViewModel,
    onSave: (T) -> Unit,
    note: String? = null,
    rows: @Composable ColumnScope.(T, (T) -> Unit) -> Unit,
) {
    // Keyed on `current`: a fresh config from the radio (which is what a successful save
    // produces) replaces the draft rather than leaving a stale one on screen.
    var draft by remember(current) { mutableStateOf(current) }
    val result by vm.writeResult.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Saving makes the radio store this section and reboot.",
            style = MaterialTheme.typography.bodySmall,
        )
        note?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
        val value = draft
        if (value == null) {
            Text(
                "Waiting for this radio's configuration…",
                style = MaterialTheme.typography.bodyMedium,
            )
        } else {
            Card(Modifier.fillMaxWidth()) { Column { rows(value, { draft = it }) } }
            val dirty = value != current
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = connected && dirty, onClick = { onSave(value) }) {
                    Text("Save to radio")
                }
                if (dirty) {
                    OutlinedButton(onClick = { draft = current }) { Text("Revert") }
                }
            }
            if (!connected) {
                Text(
                    "Connect a radio to write config.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            result?.let {
                Text(
                    if (it) "Saved" else "Save failed",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (it) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

// ---------------------------------------------------------------- rows

@Composable
private fun ConfigSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle == null) null else ({ Text(subtitle) }),
        trailingContent = {
            Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
        },
    )
    HorizontalDivider()
}

@Composable
private fun <T> ConfigEnumRow(
    title: String,
    value: T,
    options: List<T>,
    subtitle: String? = null,
    enabled: Boolean = true,
    label: (T) -> String = { enumLabel(it) },
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(if (subtitle == null) label(value) else "${label(value)}  •  $subtitle")
        },
        modifier = Modifier.clickable(enabled = enabled) { open = true },
    )
    HorizontalDivider()
    if (open) {
        EnumPickerDialog(
            title = title,
            options = options,
            selected = value,
            label = label,
            onDismiss = { open = false },
            onPick = {
                onPick(it)
                open = false
            },
        )
    }
}

@Composable
private fun ConfigNumberRow(
    title: String,
    value: Int,
    suffix: String? = null,
    hint: String? = null,
    enabled: Boolean = true,
    allowZero: Boolean = false,
    // Most of these fields are proto uint32, which the generated Java exposes as a signed
    // Int: the firmware's "disabled" sentinel 0xFFFFFFFF would otherwise read as -1.
    unsigned: Boolean = true,
    onSet: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val shown = if (unsigned) value.toUInt().toString() else value.toString()
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                if (value == 0 && !allowZero) "unset"
                else listOfNotNull(shown, suffix).joinToString(" ")
            )
        },
        modifier = Modifier.clickable(enabled = enabled) { open = true },
    )
    HorizontalDivider()
    val parse: (String) -> Int? =
        if (unsigned) ({ it.toUIntOrNull()?.toInt() }) else ({ it.toIntOrNull() })
    if (open) {
        ValueEntryDialog(
            title = title,
            initial = shown,
            hint = hint,
            keyboard = KeyboardType.Number,
            validate = { parse(it) != null },
            onDismiss = { open = false },
            onConfirm = { text ->
                parse(text)?.let(onSet)
                open = false
            },
        )
    }
}

@Composable
private fun ConfigFloatRow(
    title: String,
    value: Float,
    hint: String? = null,
    enabled: Boolean = true,
    onSet: (Float) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value.toString()) },
        modifier = Modifier.clickable(enabled = enabled) { open = true },
    )
    HorizontalDivider()
    if (open) {
        ValueEntryDialog(
            title = title,
            initial = value.toString(),
            hint = hint,
            keyboard = KeyboardType.Decimal,
            validate = { it.toFloatOrNull() != null },
            onDismiss = { open = false },
            onConfirm = { text ->
                text.toFloatOrNull()?.let(onSet)
                open = false
            },
        )
    }
}

@Composable
private fun ConfigTextRow(
    title: String,
    value: String,
    hint: String? = null,
    maxLen: Int = 128,
    masked: Boolean = false,
    enabled: Boolean = true,
    onSet: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                when {
                    value.isEmpty() -> "unset"
                    masked -> "•".repeat(value.length.coerceAtMost(12))
                    else -> value
                }
            )
        },
        modifier = Modifier.clickable(enabled = enabled) { open = true },
    )
    HorizontalDivider()
    if (open) {
        ValueEntryDialog(
            title = title,
            initial = value,
            hint = hint,
            keyboard = KeyboardType.Text,
            masked = masked,
            maxLen = maxLen,
            validate = { true },
            onDismiss = { open = false },
            onConfirm = {
                onSet(it)
                open = false
            },
        )
    }
}

@Composable
private fun ConfigInfoRow(title: String, value: String, selectable: Boolean = false) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            if (selectable) {
                SelectionContainer { Text(value, style = MaterialTheme.typography.bodySmall) }
            } else {
                Text(value)
            }
        },
    )
    HorizontalDivider()
}

/** Position flags are a bitmask, so the row edits the whole set at once. */
@Composable
private fun PositionFlagsRow(flags: Int, onSet: (Int) -> Unit) {
    val options = ConfigProtos.Config.PositionConfig.PositionFlags.entries.filter {
        it != ConfigProtos.Config.PositionConfig.PositionFlags.UNRECOGNIZED &&
            it != ConfigProtos.Config.PositionConfig.PositionFlags.UNSET
    }
    var open by remember { mutableStateOf(false) }
    val selected = options.filter { flags and it.number != 0 }
    ListItem(
        headlineContent = { Text("Position fields") },
        supportingContent = {
            Text(
                if (selected.isEmpty()) "none"
                else selected.joinToString(", ") { enumLabel(it) },
            )
        },
        modifier = Modifier.clickable { open = true },
    )
    HorizontalDivider()
    if (open) {
        var draft by remember { mutableIntStateOf(flags) }
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Position fields") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "Each extra field makes every position packet larger.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    options.forEach { flag ->
                        val on = draft and flag.number != 0
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    draft = if (on) draft and flag.number.inv()
                                    else draft or flag.number
                                },
                        ) {
                            Checkbox(
                                checked = on,
                                onCheckedChange = {
                                    draft = if (it) draft or flag.number
                                    else draft and flag.number.inv()
                                },
                            )
                            Text(enumLabel(flag))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    onSet(draft)
                    open = false
                }) { Text("Done") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Cancel") } },
        )
    }
}

// ---------------------------------------------------------------- dialogs

@Composable
internal fun <T> EnumPickerDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onDismiss: () -> Unit,
    onPick: (T) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEach { option ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(option) },
                    ) {
                        RadioButton(selected = option == selected, onClick = { onPick(option) })
                        Text(label(option))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun ValueEntryDialog(
    title: String,
    initial: String,
    hint: String?,
    keyboard: KeyboardType,
    validate: (String) -> Boolean,
    masked: Boolean = false,
    maxLen: Int = 32,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { if (it.length <= maxLen) text = it },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = keyboard),
                    visualTransformation = if (masked) PasswordVisualTransformation()
                    else androidx.compose.ui.text.input.VisualTransformation.None,
                )
                hint?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = {
            TextButton(enabled = validate(text), onClick = { onConfirm(text) }) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- helpers

/** Proto enums carry an UNRECOGNIZED case that must never be offered as a choice. */
private fun <T : Enum<T>> protoEntries(all: List<T>): List<T> =
    all.filter { it.name != "UNRECOGNIZED" }

private fun <T> enumLabel(value: T): String = when (value) {
    is Enum<*> -> value.name
    else -> value.toString()
}

/**
 * Meshtastic stores IPv4 addresses as a little-endian fixed32: the first octet is the low
 * byte. Ported from `NetworkConfig.swift`'s ipStringToUInt32 / uint32ToIpString.
 */
private fun ipToString(value: Int): String {
    if (value == 0) return ""
    return (0..3).joinToString(".") { (((value shr (it * 8)) and 0xFF)).toString() }
}

private fun ipToInt(text: String): Int {
    val parts = text.split(".").mapNotNull { it.trim().toIntOrNull() }
    if (parts.size != 4 || parts.any { it !in 0..255 }) return 0
    return parts[0] or (parts[1] shl 8) or (parts[2] shl 16) or (parts[3] shl 24)
}
