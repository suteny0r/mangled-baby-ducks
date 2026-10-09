package com.suteny0r.mangledbabyducks.ui

import org.meshtastic.proto.ConfigProtos
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.ModemPreset
import org.meshtastic.proto.ConfigProtos.Config.LoRaConfig.RegionCode
import org.meshtastic.proto.MeshProtos

// Port of LoraConfigEnums.swift: the labels, gating and coding-rate / bandwidth rules the
// LoRa config form is built from. The proto enums themselves are the source of values.

/** RegionCodes.description. */
fun regionLabel(region: RegionCode): String = when (region) {
    RegionCode.UNSET -> "Please set a region"
    RegionCode.US -> "United States"
    RegionCode.EU_433 -> "European Union 433MHz"
    RegionCode.EU_868 -> "European Union 868MHz"
    RegionCode.CN -> "China"
    RegionCode.JP -> "Japan"
    RegionCode.ANZ -> "Australia / New Zealand"
    RegionCode.ANZ_433 -> "Australia / New Zealand 433MHz"
    RegionCode.KR -> "Korea"
    RegionCode.TW -> "Taiwan"
    RegionCode.RU -> "Russia"
    RegionCode.IN -> "India"
    RegionCode.NZ_865 -> "New Zealand 865MHz"
    RegionCode.TH -> "Thailand"
    RegionCode.UA_433 -> "Ukraine 433MHz"
    RegionCode.UA_868 -> "Ukraine 868MHz"
    RegionCode.MY_433 -> "Malaysia 433MHz"
    RegionCode.MY_919 -> "Malaysia 919MHz"
    RegionCode.SG_923 -> "Singapore 923MHz"
    RegionCode.PH_433 -> "Philippines 433MHz"
    RegionCode.PH_868 -> "Philippines 868MHz"
    RegionCode.PH_915 -> "Philippines 915MHz"
    RegionCode.KZ_433 -> "Kazakhstan 433MHz"
    RegionCode.KZ_863 -> "Kazakhstan 863MHz"
    RegionCode.NP_865 -> "Nepal 865MHz"
    RegionCode.BR_902 -> "Brazil 902MHz"
    RegionCode.ITU1_2M -> "ITU Region 1 / Amateur 2m"
    RegionCode.ITU2_2M -> "ITU Region 2 / Amateur 2m"
    RegionCode.EU_866 -> "European Union 866MHz"
    RegionCode.EU_874 -> "European Union 874MHz"
    RegionCode.EU_917 -> "European Union 917MHz"
    RegionCode.EU_N_868 -> "European Union 868MHz (Narrow)"
    RegionCode.ITU3_2M -> "ITU Region 3 / Amateur 2m"
    RegionCode.ITU1_70CM -> "ITU Region 1 / Amateur 70cm"
    RegionCode.ITU2_70CM -> "ITU Region 2 / Amateur 70cm"
    RegionCode.ITU3_70CM -> "ITU Region 3 / Amateur 70cm"
    RegionCode.ITU2_125CM -> "ITU Region 2 / Amateur 1.25m"
    RegionCode.LORA_24 -> "2.4 Ghz"
    RegionCode.UNRECOGNIZED -> "Unknown"
}

/** RegionCodes.requiresFirmware2_8: ham and EU SRD / narrow bands the 2.8 rework added. */
private val REGIONS_2_8 = setOf(
    RegionCode.EU_866, RegionCode.EU_N_868, RegionCode.ITU1_2M, RegionCode.ITU2_2M, RegionCode.ITU3_2M,
    RegionCode.ITU1_70CM, RegionCode.ITU2_70CM, RegionCode.ITU3_70CM, RegionCode.ITU2_125CM,
)

/** RegionCodes.isHiddenFromPicker: enumerated by the firmware, not offered by the app. */
private val REGIONS_HIDDEN = setOf(RegionCode.EU_874, RegionCode.EU_917)

/** RegionCodes.selectable: in the Swift enum's order, which is the picker order. */
fun selectableRegions(supports2_8: Boolean): List<RegionCode> = listOf(
    RegionCode.UNSET, RegionCode.US, RegionCode.EU_433, RegionCode.EU_868, RegionCode.CN, RegionCode.JP,
    RegionCode.ANZ, RegionCode.ANZ_433, RegionCode.KR, RegionCode.TW, RegionCode.RU, RegionCode.IN,
    RegionCode.NZ_865, RegionCode.TH, RegionCode.UA_433, RegionCode.MY_433, RegionCode.MY_919,
    RegionCode.SG_923, RegionCode.PH_433, RegionCode.PH_868, RegionCode.PH_915, RegionCode.KZ_433,
    RegionCode.KZ_863, RegionCode.NP_865, RegionCode.BR_902, RegionCode.ITU1_2M, RegionCode.ITU2_2M,
    RegionCode.EU_866, RegionCode.EU_874, RegionCode.EU_917, RegionCode.EU_N_868, RegionCode.LORA_24,
    RegionCode.ITU3_2M, RegionCode.ITU1_70CM, RegionCode.ITU2_70CM, RegionCode.ITU3_70CM, RegionCode.ITU2_125CM,
).filter { it !in REGIONS_HIDDEN && (supports2_8 || it !in REGIONS_2_8) }

/** RegionCodes.prohibitsTurboPresets: EU band plans cap bandwidth below what Turbo uses. */
fun regionProhibitsTurbo(region: RegionCode): Boolean = region in setOf(
    RegionCode.EU_433, RegionCode.EU_868, RegionCode.EU_866, RegionCode.EU_874, RegionCode.EU_917, RegionCode.EU_N_868,
)

/** RegionCodes.allowsBandLimitedPresets: where Lite, Narrow and Tiny are legal (used without a firmware map). */
fun regionAllowsBandLimited(region: RegionCode): Boolean = region in setOf(
    RegionCode.EU_868, RegionCode.EU_866, RegionCode.EU_N_868, RegionCode.ITU1_2M, RegionCode.ITU2_2M, RegionCode.ITU3_2M,
    RegionCode.ITU1_70CM, RegionCode.ITU2_70CM, RegionCode.ITU3_70CM, RegionCode.ITU2_125CM,
)

/** RegionCodes.dutyCycle: the hourly transmit limit, percent; 0 when the region is unset. */
fun regionDutyCycle(region: RegionCode): Int = when (region) {
    RegionCode.UNSET, RegionCode.UNRECOGNIZED -> 0
    RegionCode.EU_433, RegionCode.EU_868, RegionCode.UA_433, RegionCode.EU_866, RegionCode.EU_874,
    RegionCode.EU_917, RegionCode.EU_N_868 -> 10
    else -> 100
}

/** ModemPresets.isTurbo: the 500 kHz presets the US band plan expects. */
fun presetIsTurbo(preset: ModemPreset): Boolean =
    preset == ModemPreset.LONG_TURBO || preset == ModemPreset.SHORT_TURBO || preset == ModemPreset.MEDIUM_TURBO

/** ModemPresets.isBandLimited: Lite (125 kHz), Narrow (62.5 kHz) and Tiny (20 kHz). */
fun presetIsBandLimited(preset: ModemPreset): Boolean = preset in setOf(
    ModemPreset.LITE_FAST, ModemPreset.LITE_SLOW, ModemPreset.NARROW_FAST, ModemPreset.NARROW_SLOW,
    ModemPreset.TINY_FAST, ModemPreset.TINY_SLOW,
)

/**
 * LoRaConfig.paFanHardware: the four boards whose firmware drives a PA fan from a GPIO
 * (RF95_FAN_EN). HardwareModel values BETAFPV_2400_TX, RADIOMASTER_900_BANDIT_NANO,
 * RADIOMASTER_900_BANDIT, TBEAM_1_WATT.
 */
val PA_FAN_HARDWARE = setOf(45, 64, 74, 122)

/** ModemPresets.description. */
fun presetLabel(preset: ModemPreset): String = when (preset) {
    ModemPreset.LONG_FAST -> "Long Range - Fast"
    ModemPreset.LONG_SLOW -> "Long Range - Slow"
    ModemPreset.LONG_MODERATE -> "Long Range - Moderate"
    ModemPreset.LONG_TURBO -> "Long Range - Turbo"
    ModemPreset.MEDIUM_SLOW -> "Medium Range - Slow"
    ModemPreset.MEDIUM_FAST -> "Medium Range - Fast"
    ModemPreset.SHORT_SLOW -> "Short Range - Slow"
    ModemPreset.SHORT_FAST -> "Short Range - Fast"
    ModemPreset.SHORT_TURBO -> "Short Range - Turbo"
    ModemPreset.LITE_FAST -> "Lite - Fast"
    ModemPreset.LITE_SLOW -> "Lite - Slow"
    ModemPreset.NARROW_FAST -> "Narrow - Fast"
    ModemPreset.NARROW_SLOW -> "Narrow - Slow"
    ModemPreset.TINY_FAST -> "Tiny - Fast"
    ModemPreset.TINY_SLOW -> "Tiny - Slow"
    ModemPreset.MEDIUM_TURBO -> "Medium Range - Turbo"
    ModemPreset.VERY_LONG_SLOW -> "Very Long Range - Slow"
    ModemPreset.UNRECOGNIZED -> "Unknown"
}

/** ModemPresets.requiresFirmware2_8. */
private val PRESETS_2_8 = setOf(
    ModemPreset.LITE_FAST, ModemPreset.LITE_SLOW, ModemPreset.NARROW_FAST, ModemPreset.NARROW_SLOW,
    ModemPreset.TINY_FAST, ModemPreset.TINY_SLOW, ModemPreset.MEDIUM_TURBO,
)

/** ModemPresets.isDeprecated: kept as values so an existing radio still shows its label. */
fun presetIsDeprecated(preset: ModemPreset): Boolean =
    preset == ModemPreset.LONG_SLOW || preset == ModemPreset.VERY_LONG_SLOW

/** ModemPresets.selectable, in the Swift enum's order. */
fun selectablePresets(supports2_8: Boolean): List<ModemPreset> = listOf(
    ModemPreset.LONG_FAST, ModemPreset.LONG_SLOW, ModemPreset.LONG_MODERATE, ModemPreset.LONG_TURBO,
    ModemPreset.MEDIUM_SLOW, ModemPreset.MEDIUM_FAST, ModemPreset.SHORT_SLOW, ModemPreset.SHORT_FAST,
    ModemPreset.SHORT_TURBO, ModemPreset.LITE_FAST, ModemPreset.LITE_SLOW, ModemPreset.NARROW_FAST,
    ModemPreset.NARROW_SLOW, ModemPreset.TINY_FAST, ModemPreset.TINY_SLOW, ModemPreset.MEDIUM_TURBO,
).filter { !presetIsDeprecated(it) && (supports2_8 || it !in PRESETS_2_8) }

/** ModemPresets.defaultCodingRate. */
fun presetDefaultCodingRate(preset: ModemPreset): Int = when (preset) {
    ModemPreset.LONG_TURBO, ModemPreset.LONG_MODERATE, ModemPreset.LONG_SLOW -> 8
    ModemPreset.NARROW_FAST, ModemPreset.NARROW_SLOW -> 6
    else -> 5
}

/** CodingRates. */
object CodingRates {
    val validRange = 5..8

    /**
     * The first firmware that honours `coding_rate` while a preset is on; before it,
     * applyModemConfig took the rate from the preset (meshtastic/firmware#9155).
     */
    const val OVERRIDE_FIRMWARE = "2.7.18"

    /** The rate the radio is actually using: the preset's own on firmware that cannot override. */
    fun effective(codingRate: Int, usePreset: Boolean, preset: ModemPreset, supportsOverride: Boolean): Int {
        if (!supportsOverride && usePreset) return 0
        return normalized(codingRate, usePreset, preset)
    }

    fun options(usePreset: Boolean, preset: ModemPreset): List<Int> {
        if (!usePreset) return validRange.toList()
        val default = presetDefaultCodingRate(preset)
        return listOf(0) + validRange.filter { it > default }
    }

    fun normalized(codingRate: Int, usePreset: Boolean, preset: ModemPreset): Int {
        val options = options(usePreset, preset)
        if (codingRate in options) return codingRate
        return if (usePreset) 0 else validRange.first
    }

    fun description(codingRate: Int, preset: ModemPreset): String =
        if (codingRate == 0) "Preset Default (4/${presetDefaultCodingRate(preset)})" else "4/$codingRate"
}

/** Bandwidths: the picker value 0 is the firmware's regional default (250 kHz sub-GHz). */
object Bandwidths {
    private val subGHz = listOf(31, 62, 125, 250, 500)
    private val highBand = listOf(200, 400, 800)
    private val sx128xTargets = setOf(
        "betafpv_2400_tx_micro", "makerpython_nrf52840_sx1280_eink", "makerpython_nrf52840_sx1280_oled",
        "my-esp32s3-diy-eink", "my-esp32s3-diy-oled", "tlora-v2-1-1_8",
    )

    fun label(value: Int): String = when (value) {
        31 -> "31 kHz"
        62 -> "62 kHz"
        125 -> "125 kHz"
        200 -> "203.125 kHz"
        250 -> "250 kHz"
        400 -> "406.25 kHz"
        500 -> "500 kHz"
        800 -> "812.5 kHz"
        1600 -> "1625 kHz"
        else -> "$value kHz"
    }

    fun pickerValue(value: Int): Int = if (value == 250) 0 else value

    fun selectable(region: RegionCode, pioEnv: String?): List<Int> {
        if (region != RegionCode.LORA_24) return subGHz
        return if (pioEnv != null && pioEnv.lowercase() in sx128xTargets) highBand + 1600 else highBand
    }

    /** Null when the stored value is fine; "unsupported" otherwise. */
    fun unsupported(value: Int, region: RegionCode, pioEnv: String?): Boolean {
        if (value == 0) return false
        val normalized = pickerValueForStored(value, region)
        return selectable(region, pioEnv).none { pickerValue(it) == normalized }
    }

    fun pickerValueForStored(value: Int, region: RegionCode): Int =
        if (region != RegionCode.LORA_24 && value == 250) 0 else value

    fun labelForPicker(value: Int, region: RegionCode): String =
        if (value == 0) (if (region == RegionCode.LORA_24) "Default (812.5 kHz)" else "250 kHz") else label(value)
}

/** RegionPresetInfo: one region's legal presets from the firmware's 2.8 map. */
data class RegionPresetInfo(
    val presets: Set<ModemPreset>,
    val defaultPreset: ModemPreset,
    val licensedOnly: Boolean,
)

/** LoRaRegionPresetMap.decoded(): flatten the grouped wire form per region. */
fun MeshProtos.LoRaRegionPresetMap.decoded(): Map<RegionCode, RegionPresetInfo> {
    val result = HashMap<RegionCode, RegionPresetInfo>()
    for (regionGroup in regionGroupsList) {
        val group = groupsList.getOrNull(regionGroup.groupIndex) ?: continue
        result[regionGroup.region] = RegionPresetInfo(
            presets = group.presetsList.toSet(),
            defaultPreset = group.defaultPreset,
            licensedOnly = group.licensedOnly,
        )
    }
    return result
}

/** ModemPresets.presetToSelect: what to pre-select when the region changes; null keeps the current one. */
fun presetToSelect(
    region: RegionCode,
    factoryFresh: Boolean,
    supports2_8: Boolean,
    usePreset: Boolean,
    regionInfo: RegionPresetInfo?,
    currentPreset: ModemPreset,
): ModemPreset? {
    if (!supports2_8 || !usePreset) return null
    if (factoryFresh && region == RegionCode.US && currentPreset == ModemPreset.LONG_FAST &&
        (regionInfo == null || ModemPreset.LONG_TURBO in regionInfo.presets)
    ) return ModemPreset.LONG_TURBO
    if (regionInfo != null && currentPreset in regionInfo.presets) return null
    if (regionInfo == null || regionInfo.presets.isEmpty()) return null
    return regionInfo.defaultPreset
}

/** AccessoryManager.checkIsVersionSupported: "2.8.0" <= firmware, numeric per component. */
fun firmwareAtLeast(version: String?, required: String): Boolean {
    if (version.isNullOrBlank()) return false
    val have = version.substringBefore('-').substringBefore('+').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
    val need = required.split('.').map { it.toIntOrNull() ?: 0 }
    for (i in 0 until maxOf(have.size, need.size)) {
        val h = have.getOrElse(i) { 0 }
        val n = need.getOrElse(i) { 0 }
        if (h != n) return h > n
    }
    return true
}
