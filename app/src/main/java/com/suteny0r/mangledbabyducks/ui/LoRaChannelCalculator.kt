package com.suteny0r.mangledbabyducks.ui

import org.meshtastic.proto.ConfigProtos
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Port of LoRaChannelCalculator.swift: the firmware's own frequency-slot derivation.
 * Region plus preset bandwidth give the number of slots in the band; when `channel_num`
 * is 0 the firmware hashes the primary channel's name (djb2) into a 1-based slot, which
 * is why a radio set to US / MediumFast lands on slot 45 at 913.125 MHz while the stored
 * config still reads 0.
 */
class LoRaChannelCalculator(private val config: ConfigProtos.Config.LoRaConfig?) {

    private val region: RegionInfo? = RegionInfo.forCode(config?.regionValue ?: 0)

    val regionName: String
        get() = config?.region?.name?.replace('_', ' ') ?: "Unknown region"

    /** The slot the radio actually operates on: the override when set, else the name hash. */
    fun effectiveSlot(primaryName: String): Int {
        val pinned = config?.channelNum ?: 0
        if (pinned != 0) return pinned
        region?.defaultSlot?.takeIf { it > 0 }?.let { return it }
        val slots = slotCount()
        if (slots <= 0) return 0
        return (djb2(primaryName) % slots.toUInt()).toInt() + 1
    }

    /** Centre frequency of a slot in MHz, or 0 when the region or bandwidth is unknown. */
    fun frequencyMHz(slot: Int): Double {
        val offset = config?.frequencyOffset?.toDouble() ?: 0.0
        val override = config?.overrideFrequency?.toDouble() ?: 0.0
        if (override != 0.0) return override + offset
        val region = region ?: return 0.0
        val bandwidth = bandwidthMHz(region)
        if (bandwidth <= 0.0 || slot <= 0) return 0.0
        val slotWidth = region.spacing + 2 * region.padding + bandwidth
        return region.freqStart + bandwidth / 2 + region.padding + (slot - 1) * slotWidth + offset
    }

    private fun slotCount(): Int {
        val region = region ?: return 0
        val bandwidth = bandwidthMHz(region)
        if (bandwidth <= 0.0) return 1
        val slotWidth = region.spacing + 2 * region.padding + bandwidth
        return max(((region.freqEnd - region.freqStart + region.spacing) / slotWidth).roundToInt(), 1)
    }

    private fun bandwidthMHz(region: RegionInfo): Double {
        val config = config ?: return 0.0
        if (config.usePreset) {
            val preset = presetBandwidthMHz(config.modemPresetValue)
            return preset * (if (region.wideLoRa) 3.25 else 1.0)
        }
        return when (val bandwidth = config.bandwidth) {
            31 -> 0.03125
            62 -> 0.0625
            200 -> 0.203125
            400 -> 0.40625
            800 -> 0.8125
            1600 -> 1.625
            else -> bandwidth / 1000.0
        }
    }

    private fun djb2(name: String): UInt {
        var hash = 5381u
        name.forEach { hash = hash + (hash shl 5) + it.code.toUInt() }
        return hash
    }

    companion object {
        /** ModemPresets.bandwidthMHz, keyed by the proto's enum number. */
        private fun presetBandwidthMHz(preset: Int): Double = when (preset) {
            8, 9, 16 -> 0.5                 // short turbo, long turbo, medium turbo
            0, 3, 4, 5, 6 -> 0.25           // long fast, medium slow/fast, short slow/fast
            1, 7, 10, 11 -> 0.125           // long slow, long moderate, lite fast/slow
            12, 13 -> 0.0625                // narrow fast/slow
            14, 15 -> 0.0156                // tiny fast/slow
            else -> 0.0
        }

        /**
         * The name the firmware hashes: the primary channel's own name, or, when that is
         * blank, the preset's default channel name (Channels.swift primaryChannelName).
         */
        fun hashName(primaryChannelName: String?, config: ConfigProtos.Config.LoRaConfig?): String {
            if (!primaryChannelName.isNullOrEmpty()) return primaryChannelName
            if (config != null && !config.usePreset) return "Custom"
            return presetChannelName(config?.modemPresetValue ?: 0)
        }

        /** ModemPresets.firmwareChannelName. */
        private fun presetChannelName(preset: Int): String = when (preset) {
            0 -> "LongFast"
            1 -> "LongSlow"
            2 -> "VLongSlow"
            3 -> "MediumSlow"
            4 -> "MediumFast"
            5 -> "ShortSlow"
            6 -> "ShortFast"
            7 -> "LongMod"
            8 -> "ShortTurbo"
            9 -> "LongTurbo"
            10 -> "LiteFast"
            11 -> "LiteSlow"
            12 -> "NarrowFast"
            13 -> "NarrowSlow"
            14 -> "TinyFast"
            15 -> "TinySlow"
            16 -> "MediumTurbo"
            else -> "LongFast"
        }
    }
}

/** RegionInfo.swift: the band a region allows, plus the few regions with a fixed slot. */
private data class RegionInfo(
    val freqStart: Double,
    val freqEnd: Double,
    val wideLoRa: Boolean = false,
    val spacing: Double = 0.0,
    val padding: Double = 0.0,
    val defaultSlot: Int = 0,
) {
    companion object {
        fun forCode(code: Int): RegionInfo? = when (code) {
            0, 1 -> RegionInfo(902.0, 928.0)              // unset, US
            2 -> RegionInfo(433.0, 434.0)                 // EU_433
            3 -> RegionInfo(869.4, 869.65)                // EU_868
            4 -> RegionInfo(470.0, 510.0)                 // CN
            5 -> RegionInfo(920.5, 923.5)                 // JP
            6 -> RegionInfo(915.0, 928.0)                 // ANZ
            7 -> RegionInfo(920.0, 923.0)                 // KR
            8 -> RegionInfo(920.0, 925.0)                 // TW
            9 -> RegionInfo(868.7, 869.2)                 // RU
            10 -> RegionInfo(865.0, 867.0)                // IN
            11 -> RegionInfo(864.0, 868.0)                // NZ_865
            12 -> RegionInfo(920.0, 925.0)                // TH
            13 -> RegionInfo(2400.0, 2483.5, wideLoRa = true)  // LORA_24
            14 -> RegionInfo(433.0, 434.7)                // UA_433
            16 -> RegionInfo(433.0, 435.0)                // MY_433
            17 -> RegionInfo(919.0, 924.0)                // MY_919
            18 -> RegionInfo(917.0, 925.0)                // SG_923
            19 -> RegionInfo(433.0, 434.7)                // PH_433
            20 -> RegionInfo(868.0, 869.4)                // PH_868
            21 -> RegionInfo(915.0, 918.0)                // PH_915
            22 -> RegionInfo(433.05, 434.79)              // ANZ_433
            23 -> RegionInfo(433.075, 434.775)            // KZ_433
            24 -> RegionInfo(863.0, 868.0, wideLoRa = true)    // KZ_863
            25 -> RegionInfo(865.0, 868.0)                // NP_865
            26 -> RegionInfo(902.0, 907.5)                // BR_902
            27 -> RegionInfo(144.0, 146.0, padding = 0.0022, defaultSlot = 26)   // ITU1_2M
            28 -> RegionInfo(144.0, 148.0, padding = 0.0022, defaultSlot = 51)   // ITU2_2M
            29 -> RegionInfo(865.6, 867.6, spacing = 0.4, padding = 0.0375)      // EU_866
            30 -> RegionInfo(873.0, 876.0)                // EU_874
            31 -> RegionInfo(917.0, 921.0)                // EU_917
            32 -> RegionInfo(869.4, 869.65, padding = 0.0104, defaultSlot = 1)   // EU_N_868
            33 -> RegionInfo(144.0, 148.0, padding = 0.0022, defaultSlot = 33)   // ITU3_2M
            34 -> RegionInfo(430.0, 440.0, padding = 0.01875, defaultSlot = 37)  // ITU1_70CM
            35 -> RegionInfo(420.0, 450.0, padding = 0.01875, defaultSlot = 137) // ITU2_70CM
            36 -> RegionInfo(430.0, 450.0, padding = 0.01875, defaultSlot = 37)  // ITU3_70CM
            37 -> RegionInfo(220.0, 225.0, padding = 0.01875, defaultSlot = 37)  // ITU2_125CM
            else -> null
        }
    }
}
