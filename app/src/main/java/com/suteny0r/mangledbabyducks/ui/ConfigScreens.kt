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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt
import com.suteny0r.mangledbabyducks.ui.theme.IosGreen
import com.suteny0r.mangledbabyducks.ui.theme.IosOrange

/**
 * The radio config sections this app can read and write, one screen each, mirroring the
 * "Radio Configuration" / "Device Configuration" lists in the iOS app's `Settings.swift`.
 * Module configs (`module.*`) are not here yet.
 */
enum class ConfigSection(val title: String, val summary: String, val pageTitle: String = title) {
    LORA("LoRa", "Region, modem preset, hop limit, transmit", pageTitle = "LoRa Config"),
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

/**
 * LoRaConfig.swift: header, an Options section (region, Use Preset, presets with the US
 * compliance warning, the licensed-band notice) and an Advanced section (MQTT flags,
 * transmit, custom bandwidth / spread factor, coding rate with the follow-preset toggle
 * and sliders, hop limit, frequency slot, RX boosted gain, frequency override, transmit
 * power stepper). Wording follows the current iOS build the user compared against.
 */
@Composable
private fun LoRaSection(vm: SettingsViewModel, connected: Boolean) {
    val current by vm.loraConfig.collectAsState()
    val myInfo by vm.myInfo.collectAsState()
    val myUser by vm.myUser.collectAsState()
    val regionPresets by vm.regionPresets.collectAsState()
    val supports2_8 = firmwareAtLeast(myInfo?.firmwareVersion, "2.8.0")
    ConfigForm(
        current, connected, vm, vm::writeLoraConfig,
        header = if (connected && myInfo != null) "Configuration for: ${myUser?.longName ?: "Unknown"}" else null,
        grouped = false,
    ) { draft, update ->
        val region = draft.region
        val preset = draft.modemPreset
        val usePreset = draft.usePreset
        val defaultCr = presetDefaultCodingRate(preset)
        val normalizedCr = CodingRates.normalized(draft.codingRate, usePreset, preset)
        val canOverrideCr = defaultCr < CodingRates.validRange.last
        // Only consulted on 2.8 firmware: a map left behind by another radio means nothing here.
        val regionInfo = if (supports2_8) regionPresets[region] else null
        val availablePresets = run {
            val base = selectablePresets(supports2_8)
            var list = base
            if (regionInfo != null && regionInfo.presets.isNotEmpty()) {
                val constrained = base.filter { it in regionInfo.presets }
                if (constrained.isNotEmpty()) list = constrained
            }
            if (presetIsDeprecated(preset) && preset !in list) list = list + preset
            list
        }
        val bandwidthIssue = !usePreset && Bandwidths.unsupported(draft.bandwidth, region, null)

        SectionHeader("Options", Modifier.padding(start = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column {
                ConfigPickerRow(
                    "Region", regionLabel(region), selectableRegions(supports2_8),
                    description = "The region where you will be using your radios.",
                    label = ::regionLabel,
                ) { newRegion ->
                    var next = draft.toBuilder().setRegion(newRegion)
                    // applyRegionPresetDefault: a factory-fresh node picking US moves off Long
                    // Fast; an illegal preset falls back to the region's default.
                    val factoryFresh = (current?.region ?: ConfigProtos.Config.LoRaConfig.RegionCode.UNSET) == ConfigProtos.Config.LoRaConfig.RegionCode.UNSET
                    presetToSelect(newRegion, factoryFresh, supports2_8, usePreset, regionPresets[newRegion].takeIf { supports2_8 }, preset)
                        ?.let { next = next.setModemPreset(it) }
                    update(next.build())
                }
                if (regionInfo?.licensedOnly == true) {
                    val licensed = myUser?.isLicensed == true
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
                        Icon(
                            if (licensed) Icons.Filled.Verified else Icons.Filled.Warning,
                            contentDescription = null,
                            tint = if (licensed) IosGreen else IosOrange,
                            modifier = Modifier.size(22.dp),
                        )
                        Column(Modifier.padding(start = 8.dp)) {
                            Text("Licensed band", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(
                                if (licensed) "This region is restricted to licensed amateur radio operators. Your operator profile is marked as licensed."
                                else "This region is restricted to licensed amateur radio operators. Enable \u201cLicensed Operator\u201d and set your call sign in User Config before transmitting.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    HorizontalDivider()
                }
                ConfigSwitchRow(
                    "Use Preset",
                    "Use the modem preset settings instead of a manual bandwidth, spread factor and coding rate",
                    usePreset,
                    icon = Icons.AutoMirrored.Outlined.ListAlt,
                ) { on ->
                    val cr = CodingRates.normalized(draft.codingRate, on, preset)
                    update(draft.toBuilder().setUsePreset(on).setCodingRate(cr).build())
                }
                if (usePreset) {
                    // Long Fast stays selectable in the US, but its bandwidth is not US-compliant
                    // on 2.8; with a firmware map, any preset outside the US list gets the note.
                    val usWarning = when {
                        !supports2_8 || region != ConfigProtos.Config.LoRaConfig.RegionCode.US -> null
                        presetBandwidthKHz(preset) < 500 ->
                            "${presetLabel(preset)}'s bandwidth is not compliant in the US. The Turbo presets are recommended."
                        else -> null
                    }
                    ConfigPickerRow(
                        "Presets", presetLabel(preset), availablePresets,
                        description = "Available modem presets, default is Long Fast.",
                        warning = usWarning,
                        label = ::presetLabel,
                    ) { newPreset ->
                        val cr = CodingRates.normalized(draft.codingRate, usePreset, newPreset)
                        update(draft.toBuilder().setModemPreset(newPreset).setCodingRate(cr).build())
                    }
                }
            }
        }

        SectionHeader("Advanced", Modifier.padding(start = 4.dp, top = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column {
                ConfigSwitchRow(
                    "Ignore MQTT",
                    "Ignore packets received over LoRa that travelled via MQTT anywhere on their path.",
                    draft.ignoreMqtt,
                    icon = Icons.Outlined.Dns,
                ) { update(draft.toBuilder().setIgnoreMqtt(it).build()) }
                ConfigSwitchRow("Ok to MQTT", null, draft.configOkToMqtt, icon = Icons.Outlined.Public) {
                    update(draft.toBuilder().setConfigOkToMqtt(it).build())
                }
                ConfigSwitchRow(
                    "Transmit Enabled",
                    "Allow the LoRa radio to transmit. Turn off while hot-swapping antennas or bench testing.",
                    draft.txEnabled,
                    icon = Icons.Outlined.GraphicEq,
                ) { update(draft.toBuilder().setTxEnabled(it).build()) }
                if (!usePreset) {
                    // CustomBandwidthPicker: the stored value, an "Unsupported" entry when the
                    // radio cannot do it here, the 2.4 GHz default, then the legal set.
                    val options = buildList {
                        val stored = Bandwidths.pickerValueForStored(draft.bandwidth, region)
                        if (bandwidthIssue) add(stored)
                        if (region == ConfigProtos.Config.LoRaConfig.RegionCode.LORA_24) add(0)
                        Bandwidths.selectable(region, null).forEach { add(Bandwidths.pickerValue(it)) }
                    }.distinct()
                    ConfigPickerRow(
                        "Bandwidth",
                        if (bandwidthIssue) "Unsupported (${Bandwidths.labelForPicker(Bandwidths.pickerValueForStored(draft.bandwidth, region), region)})"
                        else Bandwidths.labelForPicker(Bandwidths.pickerValueForStored(draft.bandwidth, region), region),
                        options,
                        warning = if (bandwidthIssue) "This bandwidth is not supported by the connected radio in the selected region. Choose a supported value before saving." else null,
                        label = { Bandwidths.labelForPicker(it, region) },
                    ) { update(draft.toBuilder().setBandwidth(it).build()) }
                    // Spread factor 7..12; the firmware stores 12 as 0 (its default).
                    ConfigPickerRow(
                        "Spread Factor",
                        (if (draft.spreadFactor == 0) 12 else draft.spreadFactor).toString(),
                        (7..12).toList(),
                        label = { it.toString() },
                    ) { update(draft.toBuilder().setSpreadFactor(if (it == 12) 0 else it).build()) }
                }
                // Coding rate.
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("Coding Rate", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(CodingRates.description(normalizedCr, preset), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (usePreset) {
                        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Follow Preset Coding Rate", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Switch(
                                checked = normalizedCr == 0,
                                enabled = canOverrideCr,
                                onCheckedChange = { follow ->
                                    update(draft.toBuilder().setCodingRate(if (follow || !canOverrideCr) 0 else defaultCr + 1).build())
                                },
                            )
                        }
                        when {
                            !canOverrideCr -> ConfigDescription("This preset already uses 4/$defaultCr, the highest redundancy available.")
                            normalizedCr == 0 -> ConfigDescription("Uses ${presetLabel(preset)}'s 4/$defaultCr coding rate. Turn this off to raise it.")
                            else -> {
                                CodingRateSlider(
                                    value = maxOf(normalizedCr, defaultCr + 1),
                                    range = (defaultCr + 1)..CodingRates.validRange.last,
                                ) { update(draft.toBuilder().setCodingRate(it).build()) }
                                ConfigDescription("Uses 4/$normalizedCr while keeping the ${presetLabel(preset)} bandwidth and spread factor. Higher values add error correction, but each packet uses more airtime and has less throughput.")
                            }
                        }
                    } else {
                        CodingRateSlider(value = normalizedCr, range = CodingRates.validRange) {
                            update(draft.toBuilder().setCodingRate(CodingRates.normalized(it, false, preset)).build())
                        }
                        ConfigDescription("Coding rate controls error-correction redundancy. Higher values can help noisy links, but reduce throughput and increase airtime. Keep 4/5 unless your channel plan calls for a different value.")
                    }
                }
                HorizontalDivider()
                ConfigPickerRow(
                    "Hop Limit", draft.hopLimit.toString(), (0..7).toList(),
                    description = "How many times a message may be repeated before it stops being forwarded.",
                    label = { it.toString() },
                ) { update(draft.toBuilder().setHopLimit(it).build()) }
                ConfigNumberRow(
                    "Frequency Slot",
                    draft.channelNum,
                    hint = "0 derives the slot from the primary channel name",
                    allowZero = true,
                    enabled = draft.overrideFrequency <= 0f,
                    description = "Your node\u2019s operating frequency is calculated based on the region, modem preset, and this field. When 0, the slot is automatically calculated based on the primary channel name.",
                ) { update(draft.toBuilder().setChannelNum(it).build()) }
                ConfigSwitchRow(
                    "RX Boosted Gain",
                    "Enable RX boosted gain mode on SX126X based radios",
                    draft.sx126XRxBoostedGain,
                    icon = Icons.Outlined.Equalizer,
                ) { update(draft.toBuilder().setSx126XRxBoostedGain(it).build()) }
                ConfigFloatRow(
                    "Frequency Override",
                    draft.overrideFrequency,
                    hint = "MHz; 0 uses the slot above",
                    icon = Icons.Outlined.MonitorHeart,
                ) { update(draft.toBuilder().setOverrideFrequency(it).build()) }
                // Transmit power stepper, 0..30 dBm, 0 meaning the region's legal maximum.
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Transmit Power", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(
                            if (draft.txPower == 0) "Max" else "${draft.txPower} dBm",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        Stepper(
                            onMinus = { if (draft.txPower > 0) update(draft.toBuilder().setTxPower(draft.txPower - 1).build()) },
                            onPlus = { if (draft.txPower < 30) update(draft.toBuilder().setTxPower(draft.txPower + 1).build()) },
                            minusEnabled = draft.txPower > 0,
                            plusEnabled = draft.txPower < 30,
                        )
                    }
                    ConfigDescription("Radio transmit power. Leave at zero to use the highest level legal for the region, which is what most radios should use.")
                }
            }
        }
    }
}

/** The grey explanatory line under a row, iOS's `.callout` gray text. */
@Composable
private fun ConfigDescription(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun CodingRateSlider(value: Int, range: IntRange, onSet: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("4/${range.first}", style = MaterialTheme.typography.labelMedium)
        Slider(
            value = value.toFloat(),
            onValueChange = { onSet(it.roundToInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first - 1).coerceAtLeast(0),
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text("4/${range.last}", style = MaterialTheme.typography.labelMedium)
    }
}

/** The iOS Stepper's minus / plus pill. */
@Composable
private fun Stepper(onMinus: () -> Unit, onPlus: () -> Unit, minusEnabled: Boolean, plusEnabled: Boolean) {
    Row(
        Modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onMinus, enabled = minusEnabled, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Remove, contentDescription = "Decrease")
        }
        Box(Modifier.width(1.dp).height(22.dp).background(MaterialTheme.colorScheme.outlineVariant))
        IconButton(onClick = onPlus, enabled = plusEnabled, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Filled.Add, contentDescription = "Increase")
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
    /** ConfigHeader's "Configuration for: <name>" line, when the form has a radio. */
    header: String? = null,
    /** False when [rows] lays out its own section cards (several sections, as on iOS). */
    grouped: Boolean = true,
    rows: @Composable ColumnScope.(T, (T) -> Unit) -> Unit,
) {
    // Keyed on `current`: a fresh config from the radio (which is what a successful save
    // produces) replaces the draft rather than leaving a stale one on screen.
    var draft by remember(current) { mutableStateOf(current) }
    val result by vm.writeResult.collectAsState()

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        header?.let {
            GroupCard(Modifier.fillMaxWidth()) {
                Text(it, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(16.dp))
            }
        }
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
            if (grouped) {
                Card(Modifier.fillMaxWidth()) { Column { rows(value, { draft = it }) } }
            } else {
                rows(value, { draft = it })
            }
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

/** A row the radio owns: shown, never edited. */
@Composable
private fun ConfigReadOnlyRow(title: String, value: String, subtitle: String?) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle == null) null else ({ Text(subtitle) }),
        trailingContent = { Text(value) },
    )
    HorizontalDivider()
}

@Composable
private fun ConfigSwitchRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean = true,
    icon: ImageVector? = null,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (subtitle == null) null else ({ Text(subtitle) }),
        leadingContent = icon?.let { { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary) } },
        trailingContent = {
            Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
        },
    )
    HorizontalDivider()
}

/**
 * The iOS Picker row: title, the chosen value trailing in the accent colour with the
 * up/down glyph, an optional description and warning under it. Tapping opens the list.
 */
@Composable
private fun <T> ConfigPickerRow(
    title: String,
    valueLabel: String,
    options: List<T>,
    description: String? = null,
    warning: String? = null,
    enabled: Boolean = true,
    label: (T) -> String,
    onPick: (T) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled) { open = true }
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(valueLabel, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyLarge)
            Icon(Icons.Filled.UnfoldMore, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
        }
        description?.let { ConfigDescription(it) }
        warning?.let {
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = IosOrange, modifier = Modifier.size(20.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
    }
    HorizontalDivider()
    if (open) {
        EnumPickerDialog(
            title = title,
            options = options,
            selected = options.firstOrNull { label(it) == valueLabel },
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
    /** Shown after the stored value, for a field the radio resolves itself when left at 0. */
    derived: String? = null,
    // Most of these fields are proto uint32, which the generated Java exposes as a signed
    // Int: the firmware's "disabled" sentinel 0xFFFFFFFF would otherwise read as -1.
    unsigned: Boolean = true,
    description: String? = null,
    onSet: (Int) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val shown = if (unsigned) value.toUInt().toString() else value.toString()
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(
                    if (value == 0 && !allowZero) "unset"
                    else listOfNotNull(shown, suffix, derived).joinToString(" ")
                )
                description?.let { ConfigDescription(it) }
            }
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
    icon: ImageVector? = null,
    onSet: (Float) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(value.toString()) },
        leadingContent = icon?.let { { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary) } },
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
    selected: T?,
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
