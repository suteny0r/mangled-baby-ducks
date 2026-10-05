package com.suteny0r.mangledbabyducks.radio

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Port of the device-hardware half of `MeshtasticAPI.swift` plus `HardwareCatalogResolver`
 * (DeviceHardwareEntity.swift): the catalog behind the node detail's hardware card
 * (display name, support level, product image). Fetched from the Meshtastic API, cached
 * as a file for 48 h (`staleDeviceImageLinkInterval`), and read entirely offline after
 * that. Images are served by the web flasher as SVG.
 */
class HardwareCatalog(context: Context) {
    data class Info(
        val hwModel: Int,
        val slug: String?,
        val target: String?,
        val displayName: String?,
        val activelySupported: Boolean,
        /** 1 flagship, 2 niche, 3 legacy, anything else discontinued (SupportLevel.swift). */
        val supportLevel: Int,
        val images: List<String>,
    ) {
        val imageUrl: String? get() = images.firstOrNull()?.let { IMAGE_PREFIX + it }
        val sectionTitle: String
            get() = when (supportLevel) {
                1 -> "Supported Hardware"
                2 -> "Niche Hardware"
                3 -> "Legacy Hardware"
                else -> "Discontinued Hardware"
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheFile = File(context.cacheDir, "device_hardware.json")

    private val _byModel = MutableStateFlow<Map<Int, Info>>(emptyMap())
    /** Best catalog entry per protobuf hardware model number. */
    val byModel: StateFlow<Map<Int, Info>> = _byModel.asStateFlow()

    fun refresh() {
        scope.launch {
            val cached = cacheFile.takeIf { it.exists() }?.readText()
            if (cached != null) runCatching { publish(cached) }
            val fresh = System.currentTimeMillis() - cacheFile.lastModified() < STALE_MS
            if (cached != null && fresh) return@launch
            runCatching {
                val conn = URL(ENDPOINT).openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 20_000
                conn.setRequestProperty("Accept", "application/json")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()
                publish(body)
                cacheFile.writeText(body)
            }.onFailure { Log.w(TAG, "hardware catalog refresh failed: ${it.message}") }
        }
    }

    private fun publish(json: String) {
        val array = JSONArray(json)
        val records = (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            Info(
                hwModel = o.optInt("hwModel", -1),
                slug = o.optString("hwModelSlug", null),
                target = o.optString("platformioTarget", null),
                displayName = o.optString("displayName", null),
                activelySupported = o.optBoolean("activelySupported", false),
                supportLevel = o.optInt("supportLevel", 0),
                images = o.optJSONArray("images")?.let { arr -> (0 until arr.length()).map { arr.getString(it) } }
                    ?: emptyList(),
            )
        }
        _byModel.value = records.groupBy { it.hwModel }.mapValues { (_, matches) -> resolve(matches) }
    }

    /**
     * HardwareCatalogResolver.record(for:in:): firmware targets are not one-to-one with the
     * hardware model a radio reports, so pick the canonical target (the one named after the
     * slug) and otherwise the most desirable entry, deterministically.
     */
    private fun resolve(matches: List<Info>): Info {
        if (matches.size == 1) return matches[0]
        val canonical = matches.filter { it.target != null && it.slug != null && it.target == normalizedTarget(it.slug) }
        if (canonical.size == 1) return canonical[0]
        return matches.sortedWith(
            compareByDescending<Info> { it.activelySupported }
                .thenBy { it.supportLevel.takeIf { l -> l > 0 } ?: 99 }
                .thenByDescending { it.images.isNotEmpty() }
                .thenBy { it.displayName?.length ?: Int.MAX_VALUE },
        ).first()
    }

    private fun normalizedTarget(slug: String) = slug.lowercase().replace('_', '-')

    companion object {
        private const val TAG = "HardwareCatalog"
        const val ENDPOINT = "https://api.meshtastic.org/resource/deviceHardware"
        const val IMAGE_PREFIX = "https://flasher.meshtastic.org/img/devices/"
        private const val STALE_MS = 48L * 60 * 60 * 1000
    }
}
