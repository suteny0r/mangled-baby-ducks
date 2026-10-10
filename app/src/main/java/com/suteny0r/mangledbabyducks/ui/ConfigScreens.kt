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
import org.meshtastic.proto.ModuleConfigProtos
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ListAlt
import androidx.compose.material.icons.automirrored.outlined.Message
import androidx.compose.material.icons.automirrored.outlined.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Equalizer
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.MonitorHeart
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Speaker
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import kotlin.math.roundToInt
import com.suteny0r.mangledbabyducks.ui.theme.IosGreen
import com.suteny0r.mangledbabyducks.ui.theme.IosOrange
import androidx.compose.material.icons.outlined.Air
import androidx.compose.material.icons.outlined.Autorenew
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.CallSplit
import androidx.compose.material.icons.outlined.Flip
import androidx.compose.material.icons.outlined.GridOn
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.LockReset
import androidx.compose.material.icons.outlined.ManageAccounts
import androidx.compose.material.icons.outlined.Password
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SettingsInputAntenna
import androidx.compose.material.icons.outlined.Tag
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.text.style.TextAlign

/**
 * The radio config sections this app can read and write, one screen each, mirroring the
 * "Radio Configuration" / "Device Configuration" lists in the iOS app's `Settings.swift`.
 * Module configs (`module.*`) live in [ModuleSection] below; only External Notification is
 * ported so far.
 */
enum class ConfigSection(val title: String, val summary: String, val pageTitle: String = title) {
    LORA("LoRa", "Region, modem preset, hop limit, transmit", pageTitle = "LoRa Config"),
    DEVICE("Device", "Role, rebroadcast, node info interval", pageTitle = "Device Config"),
    POSITION("Position", "GPS mode, broadcast interval, position flags", pageTitle = "Position Config"),
    BLUETOOTH("Bluetooth", "Pairing mode and PIN", pageTitle = "Bluetooth Config"),
    DISPLAY("Display", "Screen timeout, units, orientation", pageTitle = "Display Config"),
    NETWORK("Network", "WiFi, Ethernet, NTP, syslog", pageTitle = "Network Config"),
    POWER("Power", "Sleep intervals, shutdown, battery", pageTitle = "Power Config"),
    SECURITY("Security", "Keys, managed mode, serial console", pageTitle = "Security Config"),
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

/**
 * Module config sections, mirroring Settings.swift's "Module Configuration" group. Only
 * External Notification is ported; the rest of that group (MQTT, Canned Messages, Serial,
 * Store & Forward, Telemetry, ...) stays a known gap.
 */
enum class ModuleSection(val title: String, val summary: String, val pageTitle: String = title) {
    EXTERNAL_NOTIFICATION("External Notification", "Buzzer, LED and vibration alerts", pageTitle = "External Notification Config"),
}

/** One module config section's form. The caller supplies the header and back affordance. */
@Composable
fun ModuleSectionDetail(section: ModuleSection, vm: SettingsViewModel, connected: Boolean) {
    LaunchedEffect(section) { vm.clearWriteResult() }
    when (section) {
        ModuleSection.EXTERNAL_NOTIFICATION -> ExternalNotificationSection(vm, connected)
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
    val primaryChannelName by vm.primaryChannelName.collectAsState()
    val supports2_8 = firmwareAtLeast(myInfo?.firmwareVersion, "2.8.0")
    val supportsCrOverride = firmwareAtLeast(myInfo?.firmwareVersion, CodingRates.OVERRIDE_FIRMWARE)
    ConfigForm(
        current, connected, vm, vm::writeLoraConfig,
        header = if (connected && myInfo != null) "Configuration for: ${myUser?.longName ?: "Unknown"}" else null,
        grouped = false,
        saveNote = if (vm.loraSavesWithoutReboot) "Your device may reboot after saving." else "After config values save the node will reboot.",
        canSave = { d ->
            d.region != ConfigProtos.Config.LoRaConfig.RegionCode.UNRECOGNIZED &&
                (d.usePreset || !Bandwidths.unsupported(d.bandwidth, d.region, null))
        },
    ) { draft, update ->
        val region = draft.region
        val preset = draft.modemPreset
        val usePreset = draft.usePreset
        val defaultCr = presetDefaultCodingRate(preset)
        val normalizedCr = CodingRates.effective(draft.codingRate, usePreset, preset, supportsCrOverride)
        val canOverrideCr = defaultCr < CodingRates.validRange.last
        // Only consulted on 2.8 firmware: a map left behind by another radio means nothing here.
        val regionInfo = if (supports2_8) regionPresets[region] else null
        // ModemPresetRow.available: the firmware-gated set, minus Turbo where the EU band
        // plans forbid it, constrained to the radio's map for the region, or without a map
        // minus the band-limited presets outside their regions; the current preset always
        // stays visible so the picker never renders blank.
        val availablePresets = run {
            var base = selectablePresets(supports2_8)
            if (regionProhibitsTurbo(region)) base = base.filter { !presetIsTurbo(it) }
            var list = base
            if (regionInfo != null && regionInfo.presets.isNotEmpty()) {
                val constrained = base.filter { it in regionInfo.presets }
                if (constrained.isNotEmpty()) list = constrained
            } else if (!regionAllowsBandLimited(region)) {
                list = list.filter { !presetIsBandLimited(it) }
            }
            if (preset !in list) list = list + preset
            list
        }
        val bandwidthIssue = !usePreset && Bandwidths.unsupported(draft.bandwidth, region, null)
        val dutyCycle = regionDutyCycle(region)
        val hasPaFan = (myUser?.hwModelId ?: 0) in PA_FAN_HARDWARE
        // ChannelFrequencySummary: the radio tunes itself from region + preset + the primary
        // channel name, so a stored slot of 0 is "derive it", not "no frequency". Computed
        // against the draft, so a new region or preset shows its frequency before the write.
        val calculator = LoRaChannelCalculator(draft)
        val slot = calculator.effectiveSlot(LoRaChannelCalculator.hashName(primaryChannelName, draft))
        val frequency = calculator.frequencyMHz(slot)

        SectionHeader("Options", Modifier.padding(start = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column {
                ConfigPickerRow(
                    "Region", regionLabel(region), selectableRegions(supports2_8),
                    description = "The region where you will be using your radios.",
                    warning = if (region == ConfigProtos.Config.LoRaConfig.RegionCode.UNRECOGNIZED)
                        "This radio uses a newer region that this app does not support. Choose a supported region before saving." else null,
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
                        !presetIsTurbo(preset) ->
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
                ConfigReadOnlyRow(
                    "Frequency",
                    if (frequency > 0) "%.3f MHz".format(frequency) else "Unknown",
                    buildString {
                        append(calculator.regionName)
                        append("  •  slot ")
                        append(if (slot > 0) slot.toString() else "unknown")
                        if (draft.channelNum == 0) append(" (derived from the primary channel name)")
                    },
                )
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
                        description = "Number of chirps per symbol, as 2 raised to this value.",
                        label = { it.toString() },
                    ) { update(draft.toBuilder().setSpreadFactor(if (it == 12) 0 else it).build()) }
                }
                // Coding rate.
                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth()) {
                        Text("Coding Rate", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                        Text(CodingRates.description(normalizedCr, preset), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    if (usePreset && !supportsCrOverride) {
                        ConfigDescription("Raising the coding rate above the preset's needs firmware ${CodingRates.OVERRIDE_FIRMWARE} or later. This radio uses ${presetLabel(preset)}'s 4/$defaultCr.")
                    } else if (usePreset) {
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
                                ConfigDescription("Uses 4/$normalizedCr while keeping the ${presetLabel(preset)} bandwidth and spread factor. Every packet takes longer on air, which uses more of the duty cycle and channel utilization budget.")
                            }
                        }
                    } else {
                        CodingRateSlider(value = normalizedCr, range = CodingRates.validRange) {
                            update(draft.toBuilder().setCodingRate(CodingRates.normalized(it, false, preset)).build())
                        }
                        ConfigDescription("Coding rate controls error-correction redundancy. Higher values can help noisy links, but reduce throughput.")
                    }
                }
                HorizontalDivider()
                ConfigPickerRow(
                    "Hop Limit", draft.hopLimit.toString(), (1..7).toList(),
                    description = "How many times a message may be repeated before it stops being forwarded.",
                    label = { it.toString() },
                ) { update(draft.toBuilder().setHopLimit(it).build()) }
                ConfigNumberRow(
                    "Frequency Slot",
                    draft.channelNum,
                    hint = "0 derives the slot from the primary channel name",
                    allowZero = true,
                    derived = if (draft.channelNum == 0 && slot > 0) "(now $slot)" else null,
                    enabled = draft.overrideFrequency <= 0f,
                    description = "Your node\u2019s operating frequency is calculated based on the region, modem preset, and this field. When 0, the slot is automatically calculated based on the primary channel name.",
                ) { update(draft.toBuilder().setChannelNum(it).build()) }
                ConfigSwitchRow(
                    "RX Boosted Gain",
                    "Enable RX boosted gain mode on SX126X based radios",
                    draft.sx126XRxBoostedGain,
                    icon = Icons.Outlined.Equalizer,
                ) { update(draft.toBuilder().setSx126XRxBoostedGain(it).build()) }
                // Only in bands with an hourly limit: elsewhere there is nothing to override.
                if (dutyCycle in 1..99) {
                    ConfigSwitchRow("Override Duty Cycle", null, draft.overrideDutyCycle, icon = Icons.Outlined.Autorenew) {
                        update(draft.toBuilder().setOverrideDutyCycle(it).build())
                    }
                }
                // Only on the boards whose firmware drives a fan; nothing reports one.
                if (hasPaFan) {
                    ConfigSwitchRow("PA Fan Disabled", null, draft.paFanDisabled, icon = Icons.Outlined.Air) {
                        update(draft.toBuilder().setPaFanDisabled(it).build())
                    }
                }
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
                            "${draft.txPower} dBm",
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

/** "Configuration for: <name>" while a radio is connected, as ConfigHeader shows it. */
@Composable
private fun configHeader(vm: SettingsViewModel, connected: Boolean): String? {
    val myInfo by vm.myInfo.collectAsState()
    val myUser by vm.myUser.collectAsState()
    return if (connected && myInfo != null) "Configuration for: ${myUser?.longName ?: "Unknown"}" else null
}

/** The admin-result toast line the forms with admin actions share. */
@Composable
private fun AdminResultLine(vm: SettingsViewModel) {
    val result by vm.adminResult.collectAsState()
    result?.let {
        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * DeviceConfig.swift: Options (role with the router warning, rebroadcast mode, node info
 * interval), Hardware (double tap, triple click, LED heartbeat), Debug (time zone), GPIO
 * (button, buzzer), then Reset NodeDB and Factory Reset for the connected radio.
 */
@Composable
private fun DeviceSection(vm: SettingsViewModel, connected: Boolean) {
    val current by vm.deviceConfig.collectAsState()
    var pendingRole by remember { mutableStateOf<ConfigProtos.Config.DeviceConfig.Role?>(null) }
    var confirmNodeDb by remember { mutableStateOf(false) }
    var confirmFactory by remember { mutableStateOf(false) }
    ConfigForm(
        current, connected, vm, { draft ->
            // DeviceConfig.normalize: Router Client was retired; the node-info floor is the firmware's.
            var c = draft
            if (c.role == ConfigProtos.Config.DeviceConfig.Role.ROUTER_CLIENT) c = c.toBuilder().setRole(ConfigProtos.Config.DeviceConfig.Role.CLIENT_MUTE).build()
            if (c.nodeInfoBroadcastSecs.toUInt() < 10800u) c = c.toBuilder().setNodeInfoBroadcastSecs(10800).build()
            vm.writeDeviceConfig(c)
        },
        header = configHeader(vm, connected),
        grouped = false,
    ) { draft, update ->
        val role = draft.role
        SectionHeader("Options", Modifier.padding(start = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column {
                val roleOptions = DEVICE_ROLE_ORDER.filter { it == role || !roleIsDeprecated(it) }.let { if (role in it) it else it + role }
                ConfigPickerRow(
                    "Device Role", roleLabel(role), roleOptions,
                    description = roleDescription(role),
                    warning = if (roleIsDeprecated(role)) "This role is deprecated. Select a Router-based role to keep this node on a supported configuration." else null,
                    label = ::roleLabel,
                ) { newRole ->
                    // DeviceRolePicker: the router-class roles confirm before they are chosen.
                    if (roleWarning(newRole) != null) pendingRole = newRole
                    else update(draft.toBuilder().setRole(newRole).build())
                }
                ConfigPickerRow(
                    "Rebroadcast Mode", rebroadcastLabel(draft.rebroadcastMode),
                    protoEntries(ConfigProtos.Config.DeviceConfig.RebroadcastMode.entries),
                    description = rebroadcastDescription(draft.rebroadcastMode),
                    label = ::rebroadcastLabel,
                ) { update(draft.toBuilder().setRebroadcastMode(it).build()) }
                IntervalPickerRow(
                    "Node Info Broadcast Interval", draft.nodeInfoBroadcastSecs, Intervals.broadcastLong,
                    description = "How often node information is sent. Defaults to 900 seconds.",
                ) { update(draft.toBuilder().setNodeInfoBroadcastSecs(it).build()) }
            }
        }
        SectionHeader("Hardware", Modifier.padding(start = 4.dp, top = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column {
                ConfigSwitchRow(
                    "Double Tap as Button", "Treat double tap on supported accelerometers as a user button press.",
                    draft.doubleTapAsButtonPress, icon = Icons.Outlined.TouchApp,
                ) { update(draft.toBuilder().setDoubleTapAsButtonPress(it).build()) }
                ConfigSwitchRow(
                    "Disable Triple Click", "Disables the user button triple-press shortcut.",
                    draft.disableTripleClick, icon = Icons.Outlined.Place,
                ) { update(draft.toBuilder().setDisableTripleClick(it).build()) }
                // Labelled "LED Heartbeat" upstream, so the toggle shows the positive sense.
                ConfigSwitchRow(
                    "LED Heartbeat",
                    "Controls the blinking LED on the device.  For most devices this will control one of the up to 4 LEDS, the charger and GPS LEDs are not controllable.",
                    !draft.ledHeartbeatDisabled, icon = Icons.Outlined.MonitorHeart,
                ) { update(draft.toBuilder().setLedHeartbeatDisabled(!it).build()) }
            }
        }
        SectionHeader("Debug", Modifier.padding(start = 4.dp, top = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column {
                ConfigTextRow(
                    "Time Zone", draft.tzdef,
                    hint = "POSIX TZ string, for example EST5EDT,M3.2.0,M11.1.0",
                    maxLen = 63, icon = Icons.Outlined.Schedule,
                    description = "POSIX timezone definition string",
                ) { update(draft.toBuilder().setTzdef(it).build()) }
            }
        }
        SectionHeader("GPIO", Modifier.padding(start = 4.dp, top = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column {
                GpioPickerRow("Button GPIO", draft.buttonGpio, "GPIO pin for the user button, can be remapped on boards with multiple buttons") {
                    update(draft.toBuilder().setButtonGpio(it).build())
                }
                GpioPickerRow("Buzzer GPIO", draft.buzzerGpio, "GPIO pin for the PWM buzzer") {
                    update(draft.toBuilder().setBuzzerGpio(it).build())
                }
            }
        }
        pendingRole?.let { newRole ->
            AlertDialog(
                onDismissRequest = { pendingRole = null },
                title = { Text("Are you sure?") },
                text = { Text(roleWarning(newRole) ?: "") },
                confirmButton = {
                    TextButton(onClick = {
                        update(draft.toBuilder().setRole(newRole).build())
                        pendingRole = null
                    }) { Text("Confirm") }
                },
                dismissButton = { TextButton(onClick = { pendingRole = null }) { Text("Cancel") } },
            )
        }
        // DeviceResetSection: admin commands, not configuration, so below the form.
        if (connected) {
            SectionHeader("Reset", Modifier.padding(start = 4.dp, top = 4.dp))
            GroupCard(Modifier.fillMaxWidth()) {
                Column {
                    DestructiveRow("Reset NodeDB") { confirmNodeDb = true }
                    DestructiveRow("Factory Reset") { confirmFactory = true }
                }
            }
            AdminResultLine(vm)
        }
    }
    if (confirmNodeDb) {
        AlertDialog(
            onDismissRequest = { confirmNodeDb = false },
            title = { Text("Are you sure?") },
            text = { Text("Reset the radio's node database? The radio forgets every node it has heard; this app's copy refills from the mesh.") },
            confirmButton = {
                TextButton(onClick = { vm.resetNodeDb(); confirmNodeDb = false }) {
                    Text("Reset node database", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmNodeDb = false }) { Text("Cancel") } },
        )
    }
    if (confirmFactory) {
        AlertDialog(
            onDismissRequest = { confirmFactory = false },
            title = { Text("Factory reset will delete device and app data.") },
            text = {
                Column {
                    TextButton(onClick = { vm.factoryReset(resetDevice = false); confirmFactory = false }) {
                        Text("Delete all config?", color = MaterialTheme.colorScheme.error)
                    }
                    TextButton(onClick = { vm.factoryReset(resetDevice = true); confirmFactory = false }) {
                        Text("Delete all config, keys and BLE bonds?", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { confirmFactory = false }) { Text("Cancel") } },
        )
    }
}

/**
 * PositionConfig.swift: Position Packet (interval, smart broadcast and its two limits),
 * Device GPS (mode, update interval, fixed position with its confirmation), Position
 * Flags (one toggle per bit, the dependent bits under their parents), Advanced Device
 * GPS (the three GPIOs, GPS on only).
 */
@Composable
private fun PositionSection(vm: SettingsViewModel, connected: Boolean) {
    val current by vm.positionConfig.collectAsState()
    var confirmFixed by remember { mutableStateOf<Boolean?>(null) }
    ConfigForm(current, connected, vm, vm::writePositionConfig, header = configHeader(vm, connected), grouped = false) { draft, update ->
        val smart = draft.positionBroadcastSmartEnabled
        val gpsOn = draft.gpsMode == ConfigProtos.Config.PositionConfig.GpsMode.ENABLED
        SectionHeader("Position Packet", Modifier.padding(start = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column {
                IntervalPickerRow(
                    "Broadcast Interval", draft.positionBroadcastSecs, Intervals.broadcastMedium,
                    description = "The longest a node will go without broadcasting a position.",
                ) { update(draft.toBuilder().setPositionBroadcastSecs(it).build()) }
                ConfigSwitchRow("Smart Position", null, smart, icon = Icons.Outlined.Psychology) {
                    update(draft.toBuilder().setPositionBroadcastSmartEnabled(it).build())
                }
                if (smart) {
                    IntervalPickerRow(
                        "Minimum Interval", draft.broadcastSmartMinimumIntervalSecs, Intervals.smartBroadcastMinimum,
                        description = "The shortest interval between position updates once the minimum distance has been met.",
                    ) { update(draft.toBuilder().setBroadcastSmartMinimumIntervalSecs(it).build()) }
                    ConfigPickerRow(
                        "Minimum Distance", "${draft.broadcastSmartMinimumDistance} m", (10..150 step 5).toList(),
                        description = "The minimum change in distance before a smart position broadcast is considered.",
                        label = { "$it" },
                    ) { update(draft.toBuilder().setBroadcastSmartMinimumDistance(it).build()) }
                }
            }
        }
        SectionHeader("Device GPS", Modifier.padding(start = 4.dp, top = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column {
                ConfigPickerRow(
                    "GPS Mode", gpsModeLabel(draft.gpsMode),
                    listOf(ConfigProtos.Config.PositionConfig.GpsMode.ENABLED, ConfigProtos.Config.PositionConfig.GpsMode.DISABLED, ConfigProtos.Config.PositionConfig.GpsMode.NOT_PRESENT),
                    label = ::gpsModeLabel,
                ) { update(draft.toBuilder().setGpsMode(it).build()) }
                if (gpsOn) {
                    ConfigPickerRow(
                        "Update Interval", GPS_UPDATE_INTERVALS.firstOrNull { it.first == draft.gpsUpdateInterval }?.second ?: intervalLabel(draft.gpsUpdateInterval),
                        GPS_UPDATE_INTERVALS.map { it.first },
                        description = "How often to try to get a GPS position.",
                        label = { v -> GPS_UPDATE_INTERVALS.firstOrNull { it.first == v }?.second ?: intervalLabel(v) },
                    ) { update(draft.toBuilder().setGpsUpdateInterval(it).build()) }
                }
                if (!gpsOn || draft.fixedPosition) {
                    // FixedPositionRow: both directions confirm; the switch moves at once and
                    // comes back if the confirmation is declined or the send fails.
                    ConfigSwitchRow(
                        "Fixed Position",
                        "The last known latitude, longitude and altitude are broadcast over the mesh on the position interval, rather than a live GPS fix.",
                        draft.fixedPosition, icon = Icons.Outlined.LocationOn,
                    ) { on ->
                        update(draft.toBuilder().setFixedPosition(on).build())
                        confirmFixed = on
                    }
                }
            }
        }
        SectionHeader("Position Flags", Modifier.padding(start = 4.dp, top = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column {
                Text(
                    "Optional fields to include when assembling position messages",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                )
                POSITION_FLAGS.forEach { spec ->
                    if (spec.requires == 0 || draft.positionFlags and spec.requires != 0) {
                        ConfigSwitchRow(spec.label, spec.description, draft.positionFlags and spec.bit != 0) { on ->
                            val next = if (on) draft.positionFlags or spec.bit else draft.positionFlags and spec.bit.inv()
                            update(draft.toBuilder().setPositionFlags(next).build())
                        }
                    }
                }
            }
        }
        if (gpsOn) {
            SectionHeader("Advanced Device GPS", Modifier.padding(start = 4.dp, top = 4.dp))
            GroupCard(Modifier.fillMaxWidth()) {
                Column {
                    GpioPickerRow("GPS Receive GPIO", draft.rxGpio, "GPIO pin for GPS RX") { update(draft.toBuilder().setRxGpio(it).build()) }
                    GpioPickerRow("GPS Transmit GPIO", draft.txGpio, "GPIO pin for GPS TX") { update(draft.toBuilder().setTxGpio(it).build()) }
                    GpioPickerRow("GPS EN GPIO", draft.gpsEnGpio, "GPIO pin for GPS enable") { update(draft.toBuilder().setGpsEnGpio(it).build()) }
                }
            }
        }
        AdminResultLine(vm)
        confirmFixed?.let { turningOn ->
            AlertDialog(
                onDismissRequest = {
                    update(draft.toBuilder().setFixedPosition(!turningOn).build())
                    confirmFixed = null
                },
                title = { Text(if (turningOn) "Set Fixed Position" else "Remove Fixed Position") },
                text = {
                    Text(
                        if (turningOn) "This will send a current position from your phone and enable fixed position."
                        else "This will disable fixed position and remove the currently set position."
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        vm.setFixedPosition(turningOn) { update(draft.toBuilder().setFixedPosition(!turningOn).build()) }
                        confirmFixed = null
                    }) { Text(if (turningOn) "Set" else "Remove", color = if (turningOn) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
                },
                dismissButton = {
                    TextButton(onClick = {
                        update(draft.toBuilder().setFixedPosition(!turningOn).build())
                        confirmFixed = null
                    }) { Text("Cancel") }
                },
            )
        }
    }
}

/** BluetoothConfig.swift: enabled, pairing mode, and the six-digit PIN while fixed-pin pairing is on. */
@Composable
private fun BluetoothSection(vm: SettingsViewModel, connected: Boolean) {
    val current by vm.bluetoothConfig.collectAsState()
    ConfigForm(
        current, connected, vm, vm::writeBluetoothConfig,
        note = "Turning Bluetooth off, or changing the pairing mode, ends this app's " +
            "connection to the radio and may need re-pairing in Android settings.",
        header = configHeader(vm, connected),
        grouped = false,
        canSave = { d -> d.mode != ConfigProtos.Config.BluetoothConfig.PairingMode.FIXED_PIN || d.fixedPin in 100000..999999 },
    ) { draft, update ->
        SectionHeader("Options", Modifier.padding(start = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column {
                ConfigSwitchRow("Bluetooth Enabled", "Enable Bluetooth on the device", draft.enabled, icon = Icons.Outlined.SettingsInputAntenna) {
                    update(draft.toBuilder().setEnabled(it).build())
                }
                ConfigPickerRow(
                    "Pairing Mode", pairingModeLabel(draft.mode),
                    protoEntries(ConfigProtos.Config.BluetoothConfig.PairingMode.entries),
                    description = "Bluetooth pairing strategy",
                    label = ::pairingModeLabel,
                ) { update(draft.toBuilder().setMode(it).build()) }
                if (draft.mode == ConfigProtos.Config.BluetoothConfig.PairingMode.FIXED_PIN) {
                    val complete = draft.fixedPin in 100000..999999
                    ConfigNumberRow(
                        "Fixed Pin", draft.fixedPin,
                        hint = "Six digits, no leading zero",
                        allowZero = true,
                        description = "Fixed PIN for Bluetooth pairing. Used when pairing mode is set to fixed PIN",
                    ) { update(draft.toBuilder().setFixedPin(it.coerceIn(0, 999999)).build()) }
                    if (!complete) {
                        Text(
                            "BLE Pin must be 6 digits long.",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

/** DisplayConfig.swift: Device Screen (orientation, clock, bold heading, units) and Timing and Overrides. */
@Composable
private fun DisplaySection(vm: SettingsViewModel, connected: Boolean) {
    val current by vm.displayConfig.collectAsState()
    ConfigForm(current, connected, vm, vm::writeDisplayConfig, header = configHeader(vm, connected), grouped = false) { draft, update ->
        SectionHeader("Device Screen", Modifier.padding(start = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column {
                ConfigPickerRow(
                    "Compass Orientation", compassOrientationLabel(draft.compassOrientation),
                    protoEntries(ConfigProtos.Config.DisplayConfig.CompassOrientation.entries),
                    description = "Indicates how to rotate or invert the compass output for accurate display.",
                    label = ::compassOrientationLabel,
                ) { update(draft.toBuilder().setCompassOrientation(it).build()) }
                ConfigSwitchRow("12 Hour Clock", "Sets the screen clock format to 12-hour.", draft.use12HClock, icon = Icons.Outlined.Schedule) {
                    update(draft.toBuilder().setUse12HClock(it).build())
                }
                ConfigSwitchRow("Bold Heading", "Bold the heading text on the screen.", draft.headingBold, icon = Icons.Filled.FormatBold) {
                    update(draft.toBuilder().setHeadingBold(it).build())
                }
                ConfigPickerRow(
                    "Display Units", displayUnitsLabel(draft.units),
                    protoEntries(ConfigProtos.Config.DisplayConfig.DisplayUnits.entries),
                    description = "Units shown on the device screen.",
                    label = ::displayUnitsLabel,
                ) { update(draft.toBuilder().setUnits(it).build()) }
            }
        }
        SectionHeader("Timing and Overrides", Modifier.padding(start = 4.dp, top = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column {
                ConfigPickerRow(
                    "Screen on for", SCREEN_ON_INTERVALS.firstOrNull { it.first == draft.screenOnSecs }?.second ?: intervalLabel(draft.screenOnSecs),
                    SCREEN_ON_INTERVALS.map { it.first },
                    description = "How long the screen remains on after the user button is pressed or messages are received.",
                    label = { v -> SCREEN_ON_INTERVALS.firstOrNull { it.first == v }?.second ?: intervalLabel(v) },
                ) { update(draft.toBuilder().setScreenOnSecs(it).build()) }
                ConfigPickerRow(
                    "Carousel Interval", SCREEN_CAROUSEL_INTERVALS.firstOrNull { it.first == draft.autoScreenCarouselSecs }?.second ?: intervalLabel(draft.autoScreenCarouselSecs),
                    SCREEN_CAROUSEL_INTERVALS.map { it.first },
                    description = "Automatically moves to the next screen page, like a carousel, on this interval.",
                    label = { v -> SCREEN_CAROUSEL_INTERVALS.firstOrNull { it.first == v }?.second ?: intervalLabel(v) },
                ) { update(draft.toBuilder().setAutoScreenCarouselSecs(it).build()) }
                ConfigSwitchRow("Wake Screen on tap or motion", "Requires that there be an accelerometer on your device.", draft.wakeOnTapOrMotion, icon = Icons.Outlined.Vibration) {
                    update(draft.toBuilder().setWakeOnTapOrMotion(it).build())
                }
                ConfigSwitchRow("Flip Screen", "Flip screen vertically", draft.flipScreen, icon = Icons.Outlined.Flip) {
                    update(draft.toBuilder().setFlipScreen(it).build())
                }
                ConfigPickerRow(
                    "Display Mode", displayModeLabel(draft.displaymode),
                    protoEntries(ConfigProtos.Config.DisplayConfig.DisplayMode.entries),
                    description = "Override default screen layout.",
                    label = ::displayModeLabel,
                ) { update(draft.toBuilder().setDisplaymode(it).build()) }
                ConfigPickerRow(
                    "OLED Type", oledLabel(draft.oled),
                    OLED_TYPES.let { if (draft.oled in it) it else it + draft.oled },
                    description = "Override automatic OLED screen detection.",
                    label = ::oledLabel,
                ) { update(draft.toBuilder().setOled(it).build()) }
            }
        }
    }
}

/**
 * NetworkConfig.swift: WiFi Options and Ethernet Options by what the radio reports having,
 * Network Servers, Address Mode, the static IPv4 fields with validation, UDP Broadcast.
 * The DHCP save clears the static fields, as the original does.
 */
@Composable
private fun NetworkSection(vm: SettingsViewModel, connected: Boolean) {
    val current by vm.networkConfig.collectAsState()
    val metadata by vm.deviceMetadata.collectAsState()
    val hasWifi = metadata?.hasWifi == true
    val hasEthernet = metadata?.hasEthernet == true
    val networked = hasWifi || hasEthernet
    ConfigForm(
        current, connected, vm, { d ->
            val cleaned = if (d.addressMode != ConfigProtos.Config.NetworkConfig.AddressMode.STATIC)
                d.toBuilder().setIpv4Config(ConfigProtos.Config.NetworkConfig.IpV4Config.getDefaultInstance()).build() else d
            vm.writeNetworkConfig(cleaned)
        },
        note = if (metadata == null) "Connect the radio to learn whether it has WiFi or Ethernet." else if (!networked) "This radio reports neither WiFi nor Ethernet." else null,
        header = configHeader(vm, connected),
        grouped = false,
        canSave = { d ->
            d.addressMode != ConfigProtos.Config.NetworkConfig.AddressMode.STATIC ||
                (IPv4.isRequiredValid(ipToString(d.ipv4Config.ip)) && IPv4.isRequiredValid(ipToString(d.ipv4Config.gateway)) &&
                    IPv4.isRequiredValid(ipToString(d.ipv4Config.subnet)))
        },
    ) { draft, update ->
        val static = draft.addressMode == ConfigProtos.Config.NetworkConfig.AddressMode.STATIC
        if (hasWifi) {
            SectionHeader("WiFi Options", Modifier.padding(start = 4.dp))
            GroupCard(Modifier.fillMaxWidth()) {
                Column {
                    ConfigSwitchRow("WiFi Enabled", "Enabling WiFi will disable the bluetooth connection to the app.", draft.wifiEnabled, icon = Icons.Outlined.Wifi) {
                        update(draft.toBuilder().setWifiEnabled(it).build())
                    }
                    ConfigTextRow("SSID", draft.wifiSsid, maxLen = 32, icon = Icons.Outlined.Public, description = "WiFi network name to connect to") {
                        update(draft.toBuilder().setWifiSsid(it).build())
                    }
                    ConfigTextRow("Password", draft.wifiPsk, maxLen = 63, masked = true, icon = Icons.Outlined.Password, description = "WiFi password for authentication") {
                        update(draft.toBuilder().setWifiPsk(it).build())
                    }
                }
            }
        }
        if (hasEthernet) {
            SectionHeader("Ethernet Options", Modifier.padding(start = 4.dp, top = 4.dp))
            GroupCard(Modifier.fillMaxWidth()) {
                Column {
                    ConfigSwitchRow("Ethernet Enabled", "Enabling Ethernet will disable the bluetooth connection to the app.", draft.ethEnabled, icon = Icons.Outlined.Public) {
                        update(draft.toBuilder().setEthEnabled(it).build())
                    }
                }
            }
        }
        if (networked) {
            SectionHeader("Network Servers", Modifier.padding(start = 4.dp, top = 4.dp))
            GroupCard(Modifier.fillMaxWidth()) {
                Column {
                    ConfigTextRow("NTP Server", draft.ntpServer, maxLen = 32, icon = Icons.Outlined.Schedule, description = "NTP server address. Defaults to meshtastic.pool.ntp.org") {
                        update(draft.toBuilder().setNtpServer(it).build())
                    }
                    ConfigTextRow("Rsyslog Server", draft.rsyslogServer, maxLen = 32, icon = Icons.Outlined.Dns) {
                        update(draft.toBuilder().setRsyslogServer(it).build())
                    }
                }
            }
            SectionHeader("Address Mode", Modifier.padding(start = 4.dp, top = 4.dp))
            GroupCard(Modifier.fillMaxWidth()) {
                Column {
                    ConfigPickerRow(
                        "Address Mode", addressModeLabel(draft.addressMode),
                        protoEntries(ConfigProtos.Config.NetworkConfig.AddressMode.entries),
                        label = ::addressModeLabel,
                    ) { update(draft.toBuilder().setAddressMode(it).build()) }
                }
            }
            if (static) {
                SectionHeader("Static IPv4 Configuration", Modifier.padding(start = 4.dp, top = 4.dp))
                GroupCard(Modifier.fillMaxWidth()) {
                    Column {
                        val ipv4 = draft.ipv4Config
                        fun set(b: ConfigProtos.Config.NetworkConfig.IpV4Config.Builder) = update(draft.toBuilder().setIpv4Config(b).build())
                        Ipv4Row("IP", ipv4.ip, required = true, icon = Icons.Outlined.Tag) { set(ipv4.toBuilder().setIp(it)) }
                        Ipv4Row("Gateway", ipv4.gateway, required = true, icon = Icons.Outlined.CallSplit) { set(ipv4.toBuilder().setGateway(it)) }
                        Ipv4Row("Subnet", ipv4.subnet, required = true, icon = Icons.Outlined.GridOn) { set(ipv4.toBuilder().setSubnet(it)) }
                        Ipv4Row("DNS", ipv4.dns, required = false, icon = Icons.Outlined.Search) { set(ipv4.toBuilder().setDns(it)) }
                        Text(
                            "Address, gateway and subnet are required and must be valid IPv4 addresses. DNS may be left blank.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        )
                    }
                }
            }
            SectionHeader("UDP Broadcast", Modifier.padding(start = 4.dp, top = 4.dp))
            GroupCard(Modifier.fillMaxWidth()) {
                Column {
                    Text(
                        "Enable broadcasting packets via UDP over the local network.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp),
                    )
                    ConfigSwitchRow("UDP Broadcast", null, draft.enabledProtocols and 1 != 0, icon = Icons.Outlined.Hub) { on ->
                        update(draft.toBuilder().setEnabledProtocols(if (on) draft.enabledProtocols or 1 else draft.enabledProtocols and 1.inv()).build())
                    }
                }
            }
        }
    }
}

/**
 * PowerConfig.swift: Power (power saving, shutdown on power loss as a zero-means-off
 * toggle, wait for Bluetooth) and Battery (ADC override). The original hides the ESP32
 * and nRF52 rows by a hardware catalog this port does not carry, so every row shows.
 */
@Composable
private fun PowerSection(vm: SettingsViewModel, connected: Boolean) {
    val current by vm.powerConfig.collectAsState()
    ConfigForm(current, connected, vm, vm::writePowerConfig, header = configHeader(vm, connected), grouped = false) { draft, update ->
        SectionHeader("Power", Modifier.padding(start = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column {
                ConfigSwitchRow(
                    "Power Saving",
                    "Will sleep everything as much as possible, for the tracker and sensor role this will also include the lora radio. Don't use this setting if you want to use your device with the phone apps or are using a device without a user button.",
                    draft.isPowerSaving, icon = Icons.Outlined.Bolt,
                ) { update(draft.toBuilder().setIsPowerSaving(it).build()) }
                // nonZeroToggle(onValue: 1800): half an hour when switched on.
                ConfigSwitchRow(
                    "Shutdown on Power Loss",
                    "How long after external power is removed before the device powers off. Zero to disable.",
                    draft.onBatteryShutdownAfterSecs != 0, icon = Icons.Outlined.PowerSettingsNew,
                ) { on -> update(draft.toBuilder().setOnBatteryShutdownAfterSecs(if (on) 1800 else 0).build()) }
                if (draft.onBatteryShutdownAfterSecs != 0) {
                    ConfigNumberRow("Shutdown after", draft.onBatteryShutdownAfterSecs, suffix = "s") {
                        update(draft.toBuilder().setOnBatteryShutdownAfterSecs(it).build())
                    }
                }
                IntervalPickerRow(
                    "Wait for Bluetooth Duration", draft.waitBluetoothSecs, Intervals.waitBluetooth,
                    icon = Icons.Outlined.Bluetooth,
                ) { update(draft.toBuilder().setWaitBluetoothSecs(it).build()) }
            }
        }
        SectionHeader("Battery", Modifier.padding(start = 4.dp, top = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column {
                // ADCOverrideField: a toggle, and the multiplier while on; zero means no override.
                ConfigSwitchRow("ADC Override", null, draft.adcMultiplierOverride != 0f) { on ->
                    update(draft.toBuilder().setAdcMultiplierOverride(if (on) 2f else 0f).build())
                }
                if (draft.adcMultiplierOverride != 0f) {
                    ConfigFloatRow("Multiplier", draft.adcMultiplierOverride, hint = "Between 2 and 6 on most boards") { v ->
                        if (v > 0f) update(draft.toBuilder().setAdcMultiplierOverride(v).build())
                    }
                }
            }
        }
    }
}

/**
 * SecurityConfig.swift: Packet Authenticity, Direct Message Key (public key with Copy,
 * private key masked with reveal and regenerate), Admin Keys (three slots), Logs,
 * Administration (managed, only once an admin key exists). The iCloud key backup and the
 * app-local lockdown section have no Android counterpart here.
 */
@Composable
private fun SecuritySection(vm: SettingsViewModel, connected: Boolean) {
    val current by vm.securityConfig.collectAsState()
    val metadata by vm.deviceMetadata.collectAsState()
    val clipboard = LocalClipboardManager.current
    ConfigForm(
        current, connected, vm, vm::writeSecurityConfig,
        header = configHeader(vm, connected),
        grouped = false,
    ) { draft, update ->
        val policyAllowed = connected && metadata?.hasXeddsa == true
        SectionHeader("Packet Authenticity", Modifier.padding(start = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column {
                ConfigPickerRow(
                    "Protection Level", signaturePolicyLabel(draft.packetSignaturePolicy),
                    protoEntries(ConfigProtos.Config.SecurityConfig.PacketSignaturePolicy.entries),
                    description = if (metadata != null && metadata?.hasXeddsa == false) "This connected device does not support packet signature verification."
                    else signaturePolicyDescription(draft.packetSignaturePolicy),
                    warning = if (metadata == null) "This device has not reported whether it supports packet signature verification. Update its firmware to configure this setting." else null,
                    enabled = policyAllowed,
                    label = ::signaturePolicyLabel,
                ) { update(draft.toBuilder().setPacketSignaturePolicy(it).build()) }
            }
        }
        SectionHeader("Direct Message Key", Modifier.padding(start = 4.dp, top = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                val publicText = SecurityKey.text(draft.publicKey.toByteArray())
                val derived = SecurityKey.publicKeyFor(draft.privateKey.toByteArray())
                val matches = draft.privateKey.isEmpty || derived == null || derived.contentEquals(draft.publicKey.toByteArray())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Public Key", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp).weight(1f))
                    OutlinedButton(enabled = publicText.isNotEmpty(), onClick = { clipboard.setText(AnnotatedString(publicText)) }) { Text("Copy") }
                }
                SelectionContainer {
                    Text(
                        publicText.ifEmpty { "not set" },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = if (matches) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
                ConfigDescription("Generated from your private key and sent out to other nodes on the mesh to allow them to compute a shared secret key")
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                // PrivateKeyRows: typed text reaches the message only once it is a 32-byte key;
                // the public key follows it.
                KeyField(
                    "Private Key", draft.privateKey.toByteArray(), icon = Icons.Filled.Key,
                    description = "Used to create a shared key with a remote device",
                ) { bytes ->
                    val b = draft.toBuilder().setPrivateKey(com.google.protobuf.ByteString.copyFrom(bytes))
                    SecurityKey.publicKeyFor(bytes)?.let { b.setPublicKey(com.google.protobuf.ByteString.copyFrom(it)) }
                    update(b.build())
                }
                HorizontalDivider(Modifier.padding(vertical = 10.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Autorenew, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                    Text("Regenerate Private Key", style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp).weight(1f))
                    OutlinedButton(onClick = {
                        val fresh = SecurityKey.generatePrivateKey()
                        val pub = SecurityKey.publicKeyFor(fresh)!!
                        update(
                            draft.toBuilder()
                                .setPrivateKey(com.google.protobuf.ByteString.copyFrom(fresh))
                                .setPublicKey(com.google.protobuf.ByteString.copyFrom(pub))
                                .build()
                        )
                    }) { Icon(Icons.Outlined.LockReset, contentDescription = "Regenerate private key") }
                }
                ConfigDescription("Generate a new private key to replace the one currently in use. The public key will automatically be regenerated from your private key.")
            }
        }
        SectionHeader("Admin Keys", Modifier.padding(start = 4.dp, top = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                val keys = (0 until SecurityKey.SLOTS).map { draft.adminKeyList.getOrNull(it)?.toByteArray() ?: ByteArray(0) }
                listOf("Primary Admin Key", "Secondary Admin Key", "Tertiary Admin Key").forEachIndexed { slot, title ->
                    if (slot > 0) HorizontalDivider(Modifier.padding(vertical = 10.dp))
                    KeyField(title, keys[slot], icon = Icons.Outlined.Key, description = null) { bytes ->
                        // Positional: an empty slot still occupies its place. Managed mode goes
                        // with the last key.
                        val next = keys.toMutableList().also { it[slot] = bytes }
                        val b = draft.toBuilder().clearAdminKey()
                        next.forEach { b.addAdminKey(com.google.protobuf.ByteString.copyFrom(it)) }
                        if (next.all { it.isEmpty() }) b.setIsManaged(false)
                        update(b.build())
                    }
                }
                ConfigDescription("The public key authorized to send admin messages to this node")
            }
        }
        SectionHeader("Logs", Modifier.padding(start = 4.dp, top = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column {
                ConfigSwitchRow("Serial Console", "Serial Console over the Stream API.", draft.serialEnabled, icon = Icons.Outlined.Terminal) {
                    update(draft.toBuilder().setSerialEnabled(it).build())
                }
                ConfigSwitchRow("Debug Logs", "Output live debug logging over serial, view and export position-redacted device logs over Bluetooth.", draft.debugLogApiEnabled, icon = Icons.Outlined.BugReport) {
                    update(draft.toBuilder().setDebugLogApiEnabled(it).build())
                }
            }
        }
        SectionHeader("Administration", Modifier.padding(start = 4.dp, top = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column {
                val hasAdminKey = draft.adminKeyList.any { !it.isEmpty }
                ConfigSwitchRow(
                    "Managed Device",
                    "Device is managed by a mesh administrator, the user is unable to access any of the device settings.",
                    draft.isManaged, enabled = hasAdminKey, icon = Icons.Outlined.ManageAccounts,
                ) { update(draft.toBuilder().setIsManaged(it).build()) }
                if (!hasAdminKey) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.Top) {
                        Icon(Icons.Filled.Warning, contentDescription = null, tint = IosOrange, modifier = Modifier.size(18.dp))
                        Text("An admin key must be set before enabling managed mode.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }
}

/**
 * ExternalNotificationConfig.swift: Options (enabled, alert on bell/message, PWM/I2S buzzer
 * mode), Primary GPIO (active high/low, output pin, output duration, nag timeout), Optional
 * GPIO (the same two alerts split out to the vibra and buzzer pins, plus their pins).
 */
@Composable
private fun ExternalNotificationSection(vm: SettingsViewModel, connected: Boolean) {
    val current by vm.externalNotificationConfig.collectAsState()
    ConfigForm(current, connected, vm, vm::writeExternalNotificationConfig, header = configHeader(vm, connected), grouped = false) { draft, update ->
        SectionHeader("Options", Modifier.padding(start = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column {
                ConfigSwitchRow("External Notification Enabled", "Enable external notifications", draft.enabled, icon = Icons.Outlined.Campaign) {
                    update(draft.toBuilder().setEnabled(it).build())
                }
                ConfigSwitchRow("Alert when receiving a bell", "Alert on bell character", draft.alertBell, icon = Icons.Outlined.NotificationsActive) {
                    update(draft.toBuilder().setAlertBell(it).build())
                }
                ConfigSwitchRow("Alert when receiving a message", "Alert on incoming message", draft.alertMessage, icon = Icons.AutoMirrored.Outlined.Message) {
                    update(draft.toBuilder().setAlertMessage(it).build())
                }
                ConfigSwitchRow(
                    "Use PWM Buzzer",
                    "Use a PWM output (like the RAK Buzzer) for tunes instead of an on/off output. This will ignore the output, output duration and active settings and use the device config buzzer GPIO option instead.",
                    draft.usePwm, icon = Icons.AutoMirrored.Outlined.VolumeUp,
                ) { update(draft.toBuilder().setUsePwm(it).build()) }
                ConfigSwitchRow(
                    "Use I2S As Buzzer",
                    "Enables devices with native I2S audio output to use the RTTTL over speaker like a buzzer. T-Watch S3 and T-Deck for example have this capability.",
                    draft.useI2SAsBuzzer, icon = Icons.Outlined.Speaker,
                ) { update(draft.toBuilder().setUseI2SAsBuzzer(it).build()) }
            }
        }
        SectionHeader("Primary GPIO", Modifier.padding(start = 4.dp, top = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column {
                ConfigSwitchRow(
                    "Active",
                    "If enabled, the 'output' Pin will be pulled active high, disabled means active low.",
                    draft.active, icon = Icons.Outlined.PowerSettingsNew,
                ) { update(draft.toBuilder().setActive(it).build()) }
                GpioPickerRow("Output pin GPIO", draft.output, "GPIO pin driven on notification. Defaults to the board's EXT_NOTIFY_OUT pin.") {
                    update(draft.toBuilder().setOutput(it).build())
                }
                ConfigPickerRow(
                    "GPIO Output Duration", outputMsLabel(draft.outputMs), OUTPUT_MS_INTERVALS,
                    description = "In GPIO mode, how long to keep the output on.",
                    label = ::outputMsLabel, icon = Icons.Outlined.Timer,
                ) { update(draft.toBuilder().setOutputMs(it).build()) }
                IntervalPickerRow(
                    "Nag Timeout", draft.nagTimeout, Intervals.nagTimeout,
                    description = "How long the notification lasts.", icon = Icons.Outlined.Repeat,
                ) { update(draft.toBuilder().setNagTimeout(it).build()) }
            }
        }
        SectionHeader("Optional GPIO", Modifier.padding(start = 4.dp, top = 4.dp))
        GroupCard(Modifier.fillMaxWidth()) {
            Column {
                ConfigSwitchRow("Alert GPIO buzzer when receiving a bell", "Buzz on bell character", draft.alertBellBuzzer, icon = Icons.Outlined.NotificationsActive) {
                    update(draft.toBuilder().setAlertBellBuzzer(it).build())
                }
                ConfigSwitchRow("Alert GPIO vibra motor when receiving a bell", "Vibrate on bell character", draft.alertBellVibra, icon = Icons.Outlined.Vibration) {
                    update(draft.toBuilder().setAlertBellVibra(it).build())
                }
                ConfigSwitchRow("Alert GPIO buzzer when receiving a message", "Buzz on incoming message", draft.alertMessageBuzzer, icon = Icons.AutoMirrored.Outlined.Message) {
                    update(draft.toBuilder().setAlertMessageBuzzer(it).build())
                }
                ConfigSwitchRow("Vibra Motor Alert", "Alert GPIO vibra motor when receiving a message", draft.alertMessageVibra, icon = Icons.Outlined.Vibration) {
                    update(draft.toBuilder().setAlertMessageVibra(it).build())
                }
                GpioPickerRow("Output pin buzzer GPIO", draft.outputBuzzer, "Buzzer output pin") {
                    update(draft.toBuilder().setOutputBuzzer(it).build())
                }
                GpioPickerRow("Output pin vibra GPIO", draft.outputVibra, "Vibration motor output pin") {
                    update(draft.toBuilder().setOutputVibra(it).build())
                }
            }
        }
    }
}

// ---------------------------------------------------------------- section-specific rows

/** A 32-byte key as base64: masked with a reveal, red while the text is not a key, blank clears. */
@Composable
private fun KeyField(
    title: String,
    value: ByteArray,
    icon: ImageVector,
    description: String?,
    onSet: (ByteArray) -> Unit,
) {
    var text by remember(value.contentHashCode()) { mutableStateOf(SecurityKey.text(value)) }
    var reveal by remember { mutableStateOf(false) }
    val valid = SecurityKey.isValid(text)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.padding(start = 12.dp).weight(1f))
        IconButton(onClick = { reveal = !reveal }) {
            Icon(if (reveal) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility, contentDescription = if (reveal) "Hide" else "Reveal")
        }
    }
    OutlinedTextField(
        value = text,
        onValueChange = { candidate ->
            text = candidate.trim()
            if (SecurityKey.isValid(text)) onSet(SecurityKey.data(text))
        },
        singleLine = true,
        isError = !valid,
        visualTransformation = if (reveal) VisualTransformation.None else PasswordVisualTransformation(),
        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        modifier = Modifier.fillMaxWidth(),
    )
    description?.let { ConfigDescription(it) }
}

/** The 0..48 GPIO picker with "Unset" for zero. */
@Composable
private fun GpioPickerRow(title: String, value: Int, description: String?, onSet: (Int) -> Unit) {
    ConfigPickerRow(
        title, if (value == 0) "Unset" else value.toString(), (0..48).toList(),
        description = description,
        label = { if (it == 0) "Unset" else it.toString() },
        onPick = onSet,
    )
}

/**
 * UpdateIntervalPicker: the curated options, plus the stored value when it is not one of
 * them, flagged as not optimized.
 */
@Composable
private fun IntervalPickerRow(
    title: String,
    value: Int,
    options: List<Int>,
    description: String? = null,
    icon: ImageVector? = null,
    onSet: (Int) -> Unit,
) {
    val listed = if (value in options) options else options + value
    val outOfRange = value !in options
    ConfigPickerRow(
        title, intervalLabel(value), listed,
        description = description,
        warning = if (outOfRange) "The configured value (${intervalLabel(value)}) is not one of the optimized options." else null,
        label = ::intervalLabel,
        icon = icon,
        onPick = onSet,
    )
}

/** IPv4Row: a dotted quad typed as text; a required field tints blank or malformed as invalid. */
@Composable
private fun Ipv4Row(title: String, value: Int, required: Boolean, icon: ImageVector, onSet: (Int) -> Unit) {
    val text = if (value == 0) "" else ipToString(value)
    val valid = if (required) IPv4.isRequiredValid(text) else IPv4.isValid(text)
    ConfigTextRow(
        title, text, hint = "e.g. 192.168.1.10", maxLen = 15, icon = icon,
        invalid = !valid,
    ) { typed -> onSet(if (IPv4.isValid(typed) && typed.isNotBlank()) ipToInt(typed.trim()) else 0) }
}

/** A destructive action row, red, for the reset commands. */
@Composable
private fun DestructiveRow(title: String, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title, color = MaterialTheme.colorScheme.error) },
        modifier = Modifier.clickable(onClick = onClick),
    )
    HorizontalDivider()
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
    /** LoRaConfig.canSave: a draft the radio could not take keeps Save disabled. */
    canSave: (T) -> Boolean = { true },
    /** SaveConfigButton's confirmation line; LoRa on 2.8 replaces it with "may reboot". */
    saveNote: String = "After config values save the node will reboot.",
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
        Text(saveNote, style = MaterialTheme.typography.bodySmall)
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
                Button(enabled = connected && dirty && canSave(value), onClick = { onSave(value) }) {
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
    icon: ImageVector? = null,
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
            icon?.let {
                Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(end = 12.dp))
            }
            // The title keeps its words; a long value yields first, as the iOS picker row does.
            Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f, fill = false), color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(12.dp))
            Text(
                valueLabel,
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1.4f, fill = false),
            )
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
    icon: ImageVector? = null,
    description: String? = null,
    invalid: Boolean = false,
    onSet: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Column {
                Text(
                    when {
                        value.isEmpty() -> "unset"
                        masked -> "\u2022".repeat(value.length.coerceAtMost(12))
                        else -> value
                    },
                    color = if (invalid) MaterialTheme.colorScheme.error else Color.Unspecified,
                )
                description?.let { ConfigDescription(it) }
            }
        },
        leadingContent = icon?.let { { Icon(it, contentDescription = null, tint = MaterialTheme.colorScheme.primary) } },
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
