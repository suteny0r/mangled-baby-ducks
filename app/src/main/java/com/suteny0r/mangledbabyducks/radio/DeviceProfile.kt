package com.suteny0r.mangledbabyducks.radio

import android.util.Base64
import android.util.Log
import com.google.protobuf.ByteString
import com.google.protobuf.CodedInputStream
import com.google.protobuf.MessageLite
import com.google.protobuf.WireFormat
import com.suteny0r.mangledbabyducks.db.MeshDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import org.meshtastic.proto.AppOnlyProtos
import org.meshtastic.proto.ChannelProtos
import org.meshtastic.proto.ClientOnlyProtos
import org.meshtastic.proto.ConfigProtos
import org.meshtastic.proto.LocalOnlyProtos
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.ModuleConfigProtos
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "DeviceProfile"

// ====================================================================== Export

/**
 * DeviceProfileExport.swift + DeviceProfileDocument.swift: the connected radio's whole
 * configuration as a `DeviceProfile` protobuf, the `.cfg` format shared with the official
 * Android app, the iOS app and the `meshtastic` CLI.
 *
 * iOS rebuilds each section from its own entities, field by field. This app stores the
 * sections as the raw protobuf the radio sent, so a section is exported exactly as received.
 */
object DeviceProfileExport {

    /**
     * NodeInfoEntity.exportDeviceProfile. Only sections the radio has sent are included.
     * [ringtone] and [cannedMessages] are the two texts the radio keeps outside its config;
     * null or empty leaves them out.
     */
    suspend fun build(
        db: MeshDatabase,
        myNum: Long,
        ringtone: String?,
        cannedMessages: String?,
    ): ClientOnlyProtos.DeviceProfile {
        val profile = ClientOnlyProtos.DeviceProfile.newBuilder()

        db.userDao().get(myNum)?.let { user ->
            profile.longName = user.longName ?: ""
            profile.shortName = user.shortName ?: ""
            // is_licensed is deliberately excluded: ham mode is its own flow, and a backup
            // must not be able to switch it on.
            profile.isUnmessagable = user.unmessagable
        }

        val stored = db.configDao().all().associate { it.type to it.bytes }

        val local = LocalOnlyProtos.LocalConfig.newBuilder()
        var lora: ConfigProtos.Config.LoRaConfig? = null
        var position: ConfigProtos.Config.PositionConfig? = null
        stored.forEach { (type, bytes) ->
            if (!type.startsWith("config.")) return@forEach
            val config = runCatching { ConfigProtos.Config.parseFrom(bytes) }.getOrNull() ?: return@forEach
            when (config.payloadVariantCase) {
                ConfigProtos.Config.PayloadVariantCase.DEVICE -> local.device = config.device
                ConfigProtos.Config.PayloadVariantCase.POSITION -> {
                    local.position = config.position
                    position = config.position
                }
                ConfigProtos.Config.PayloadVariantCase.POWER -> local.power = config.power
                ConfigProtos.Config.PayloadVariantCase.NETWORK -> local.network = config.network
                ConfigProtos.Config.PayloadVariantCase.DISPLAY -> local.display = config.display
                ConfigProtos.Config.PayloadVariantCase.LORA -> {
                    local.lora = config.lora
                    lora = config.lora
                }
                ConfigProtos.Config.PayloadVariantCase.BLUETOOTH -> local.bluetooth = config.bluetooth
                ConfigProtos.Config.PayloadVariantCase.SECURITY -> local.security = config.security
                else -> Unit
            }
        }
        profile.config = local.build()

        // The same fourteen modules iOS exports. Remote hardware, traffic management and
        // mesh beacon are left out there too.
        val modules = LocalOnlyProtos.LocalModuleConfig.newBuilder()
        stored.forEach { (type, bytes) ->
            if (!type.startsWith("module.")) return@forEach
            val module = runCatching { ModuleConfigProtos.ModuleConfig.parseFrom(bytes) }.getOrNull() ?: return@forEach
            when (module.payloadVariantCase) {
                ModuleConfigProtos.ModuleConfig.PayloadVariantCase.MQTT -> modules.mqtt = module.mqtt
                ModuleConfigProtos.ModuleConfig.PayloadVariantCase.SERIAL -> modules.serial = module.serial
                ModuleConfigProtos.ModuleConfig.PayloadVariantCase.EXTERNAL_NOTIFICATION ->
                    modules.externalNotification = module.externalNotification
                ModuleConfigProtos.ModuleConfig.PayloadVariantCase.STORE_FORWARD -> modules.storeForward = module.storeForward
                ModuleConfigProtos.ModuleConfig.PayloadVariantCase.RANGE_TEST -> modules.rangeTest = module.rangeTest
                ModuleConfigProtos.ModuleConfig.PayloadVariantCase.TELEMETRY -> modules.telemetry = module.telemetry
                ModuleConfigProtos.ModuleConfig.PayloadVariantCase.CANNED_MESSAGE -> modules.cannedMessage = module.cannedMessage
                ModuleConfigProtos.ModuleConfig.PayloadVariantCase.AUDIO -> modules.audio = module.audio
                ModuleConfigProtos.ModuleConfig.PayloadVariantCase.NEIGHBOR_INFO -> modules.neighborInfo = module.neighborInfo
                ModuleConfigProtos.ModuleConfig.PayloadVariantCase.AMBIENT_LIGHTING -> modules.ambientLighting = module.ambientLighting
                ModuleConfigProtos.ModuleConfig.PayloadVariantCase.DETECTION_SENSOR -> modules.detectionSensor = module.detectionSensor
                ModuleConfigProtos.ModuleConfig.PayloadVariantCase.PAXCOUNTER -> modules.paxcounter = module.paxcounter
                ModuleConfigProtos.ModuleConfig.PayloadVariantCase.TAK -> modules.tak = module.tak
                ModuleConfigProtos.ModuleConfig.PayloadVariantCase.STATUSMESSAGE -> modules.statusmessage = module.statusmessage
                else -> Unit
            }
        }
        profile.moduleConfig = modules.build()

        if (!ringtone.isNullOrEmpty()) profile.ringtone = ringtone
        if (!cannedMessages.isNullOrEmpty()) profile.cannedMessages = cannedMessages

        channelUrl(db, lora)?.let { profile.channelUrl = it }

        // The fixed coordinates only when the radio is actually using one.
        if (position?.fixedPosition == true) {
            db.positionDao().latest(myNum)?.let { fix ->
                if (fix.latitudeI != 0 || fix.longitudeI != 0) {
                    profile.fixedPosition = MeshProtos.Position.newBuilder()
                        .setLatitudeI(fix.latitudeI)
                        .setLongitudeI(fix.longitudeI)
                        .setAltitude(fix.altitude)
                        .build()
                }
            }
        }
        return profile.build()
    }

    /**
     * exportChannelURL: LoRa config plus every enabled channel, as a meshtastic.org/e/ link.
     * Carries the uplink and downlink flags as well, which iOS leaves out and so loses on
     * a restore.
     */
    private suspend fun channelUrl(db: MeshDatabase, lora: ConfigProtos.Config.LoRaConfig?): String? {
        if (lora == null) return null
        val channels = db.channelDao().activeChannels().first().sortedBy { it.index }
        if (channels.isEmpty()) return null
        val set = AppOnlyProtos.ChannelSet.newBuilder().setLoraConfig(lora)
        channels.forEach { channel ->
            set.addSettings(
                ChannelProtos.ChannelSettings.newBuilder()
                    .setName(channel.name ?: "")
                    .setPsk(ByteString.copyFrom(channel.psk ?: ByteArray(0)))
                    .setUplinkEnabled(channel.uplinkEnabled)
                    .setDownlinkEnabled(channel.downlinkEnabled)
                    .setModuleSettings(
                        ChannelProtos.ModuleSettings.newBuilder()
                            .setPositionPrecision(channel.positionPrecision)
                            .setIsMuted(channel.mute)
                    )
            )
        }
        val encoded = Base64.encodeToString(
            set.build().toByteArray(),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP,
        )
        return ChannelCodec.URL_PREFIX + encoded
    }

    /**
     * DeviceProfileDocument.exportFilename: `Meshtastic_<shortName>_<yyyyMMdd>_nodeConfig.cfg`,
     * the official Android app's name for the same file.
     */
    fun filename(shortName: String?, longName: String?, date: Date = Date()): String {
        val raw = listOf(shortName, longName).mapNotNull { it?.trim() }.firstOrNull { it.isNotEmpty() } ?: "Node"
        val safe = raw.split('/', '\\', ':').joinToString("-").trim('-', ' ', '\t', '\n', '\r')
        val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(date)
        return "Meshtastic_${safe.ifEmpty { "Node" }}_${stamp}_nodeConfig.cfg"
    }
}

// ====================================================================== Import plan

/** DeviceProfileImportError. */
enum class DeviceProfileImportError { EMPTY_FILE, TOO_LARGE, MALFORMED, NOTHING_TO_IMPORT }

class DeviceProfileImportException(val error: DeviceProfileImportError) : Exception(error.name)

/**
 * ImportSection: the coarse groups the review screen lets the user switch on and off.
 * Everything starts on except Security & Identity, because importing a foreign private
 * key rewrites this node's identity and its admin-key trust.
 */
enum class ImportSection(val title: String) {
    OWNER("Owner Name"),
    RADIO_AND_DEVICE("Radio & Device"),
    NETWORK("Network"),
    MODULES("Modules"),
    PERSONALIZATION("Ringtone & Canned Messages"),
    FIXED_POSITION("Fixed Position"),
    SECURITY("Security & Identity"),
    CHANNELS_AND_LORA("Channels & LoRa");

    val defaultsOn: Boolean get() = this != SECURITY
}

/**
 * FirmwareSupport: whether the connected firmware can apply an item. The radio acks a
 * module config it has no handler for and applies nothing, so the only way to tell the
 * user is to check the version up front.
 */
sealed interface FirmwareSupport {
    data object Always : FirmwareSupport
    data class FromVersion(val version: String) : FirmwareSupport
    /** No firmware implements it: the radio accepts the message and discards it. */
    data object Unimplemented : FirmwareSupport
}

/** ImportItemKind. */
enum class ImportItemKind(val displayName: String) {
    OWNER("Owner Name"),
    DEVICE_CONFIG("Device"),
    DISPLAY_CONFIG("Display"),
    POSITION_CONFIG("Position"),
    POWER_CONFIG("Power"),
    NETWORK_CONFIG("Network"),
    BLUETOOTH_CONFIG("Bluetooth"),
    SECURITY_CONFIG("Security & Identity"),
    MQTT("MQTT"),
    SERIAL("Serial"),
    EXTERNAL_NOTIFICATION("External Notification"),
    STORE_FORWARD("Store & Forward"),
    RANGE_TEST("Range Test"),
    TELEMETRY("Telemetry"),
    CANNED_MESSAGE("Canned Message"),
    AUDIO("Audio"),
    NEIGHBOR_INFO("Neighbor Info"),
    AMBIENT_LIGHTING("Ambient Lighting"),
    DETECTION_SENSOR("Detection Sensor"),
    PAXCOUNTER("PAX Counter"),
    TAK("TAK"),
    STATUS_MESSAGE("Status Message"),
    RINGTONE("Ringtone"),
    CANNED_MESSAGES_TEXT("Canned Messages"),
    FIXED_POSITION("Fixed Position"),
    CHANNEL_URL("Channels & LoRa"),
    LORA_CONFIG("LoRa Region");

    val firmwareSupport: FirmwareSupport
        get() = when (this) {
            // First released in 2.7.20.
            STATUS_MESSAGE -> FirmwareSupport.FromVersion("2.7.20")
            // handleSetModuleConfig has never had a TAK case, on any tag or on develop.
            TAK -> FirmwareSupport.Unimplemented
            else -> FirmwareSupport.Always
        }

    /** The key this kind's section is stored under in the configs table, if it has one. */
    internal val configKey: String?
        get() = when (this) {
            DEVICE_CONFIG -> "config.device"
            DISPLAY_CONFIG -> "config.display"
            POSITION_CONFIG -> "config.position"
            POWER_CONFIG -> "config.power"
            NETWORK_CONFIG -> "config.network"
            BLUETOOTH_CONFIG -> "config.bluetooth"
            SECURITY_CONFIG -> "config.security"
            LORA_CONFIG -> "config.lora"
            MQTT -> "module.mqtt"
            SERIAL -> "module.serial"
            EXTERNAL_NOTIFICATION -> "module.external_notification"
            STORE_FORWARD -> "module.store_forward"
            RANGE_TEST -> "module.range_test"
            TELEMETRY -> "module.telemetry"
            CANNED_MESSAGE -> "module.canned_message"
            AUDIO -> "module.audio"
            NEIGHBOR_INFO -> "module.neighbor_info"
            AMBIENT_LIGHTING -> "module.ambient_lighting"
            DETECTION_SENSOR -> "module.detection_sensor"
            PAXCOUNTER -> "module.paxcounter"
            TAK -> "module.tak"
            STATUS_MESSAGE -> "module.statusmessage"
            else -> null
        }
}

/**
 * ImportPayload: the already-built protobuf for one send. iOS has a case per section; the
 * config and module sections here ride in the same `Config` and `ModuleConfig` wrappers
 * the admin message takes, with exactly one section set.
 */
sealed interface ImportPayload {
    data class Owner(val user: MeshProtos.User) : ImportPayload
    data class Config(val config: ConfigProtos.Config) : ImportPayload
    data class Module(val config: ModuleConfigProtos.ModuleConfig) : ImportPayload
    data class Ringtone(val text: String) : ImportPayload
    data class CannedMessagesText(val text: String) : ImportPayload
    data class FixedPosition(val position: MeshProtos.Position) : ImportPayload
    data class ChannelUrl(val set: AppOnlyProtos.ChannelSet) : ImportPayload

    /**
     * ImportPayload.message: the protobuf to compare on readback, or null for the payloads
     * the radio never echoes back in a comparable form.
     */
    val message: MessageLite?
        get() = when (this) {
            is Owner -> user
            is Config -> config.section()
            is Module -> config.section()
            is FixedPosition -> position
            is Ringtone, is CannedMessagesText, is ChannelUrl -> null
        }
}

private fun ConfigProtos.Config.section(): MessageLite? = when (payloadVariantCase) {
    ConfigProtos.Config.PayloadVariantCase.DEVICE -> device
    ConfigProtos.Config.PayloadVariantCase.POSITION -> position
    ConfigProtos.Config.PayloadVariantCase.POWER -> power
    ConfigProtos.Config.PayloadVariantCase.NETWORK -> network
    ConfigProtos.Config.PayloadVariantCase.DISPLAY -> display
    ConfigProtos.Config.PayloadVariantCase.LORA -> lora
    ConfigProtos.Config.PayloadVariantCase.BLUETOOTH -> bluetooth
    ConfigProtos.Config.PayloadVariantCase.SECURITY -> security
    else -> null
}

private fun ModuleConfigProtos.ModuleConfig.section(): MessageLite? = when (payloadVariantCase) {
    ModuleConfigProtos.ModuleConfig.PayloadVariantCase.MQTT -> mqtt
    ModuleConfigProtos.ModuleConfig.PayloadVariantCase.SERIAL -> serial
    ModuleConfigProtos.ModuleConfig.PayloadVariantCase.EXTERNAL_NOTIFICATION -> externalNotification
    ModuleConfigProtos.ModuleConfig.PayloadVariantCase.STORE_FORWARD -> storeForward
    ModuleConfigProtos.ModuleConfig.PayloadVariantCase.RANGE_TEST -> rangeTest
    ModuleConfigProtos.ModuleConfig.PayloadVariantCase.TELEMETRY -> telemetry
    ModuleConfigProtos.ModuleConfig.PayloadVariantCase.CANNED_MESSAGE -> cannedMessage
    ModuleConfigProtos.ModuleConfig.PayloadVariantCase.AUDIO -> audio
    ModuleConfigProtos.ModuleConfig.PayloadVariantCase.NEIGHBOR_INFO -> neighborInfo
    ModuleConfigProtos.ModuleConfig.PayloadVariantCase.AMBIENT_LIGHTING -> ambientLighting
    ModuleConfigProtos.ModuleConfig.PayloadVariantCase.DETECTION_SENSOR -> detectionSensor
    ModuleConfigProtos.ModuleConfig.PayloadVariantCase.PAXCOUNTER -> paxcounter
    ModuleConfigProtos.ModuleConfig.PayloadVariantCase.TAK -> tak
    ModuleConfigProtos.ModuleConfig.PayloadVariantCase.STATUSMESSAGE -> statusmessage
    else -> null
}

/**
 * ImportItem: one applyable unit. [mayReboot] describes the item on its own; since the run
 * commits inside a firmware edit transaction, every successful run reboots regardless.
 */
data class ImportItem(
    val kind: ImportItemKind,
    val section: ImportSection,
    val summary: String,
    val isSensitive: Boolean,
    val mayReboot: Boolean,
    val payload: ImportPayload,
)

/**
 * DeviceProfileImportPlan: the ordered admin sends that restore a radio from a
 * `DeviceProfile`. Items are in a fixed apply order; the screen filters by section but
 * never re-sorts, so Channels & LoRa always stays last.
 */
class DeviceProfileImportPlan(
    profile: ClientOnlyProtos.DeviceProfile,
    currentUser: MeshProtos.User?,
    currentSecurity: ConfigProtos.Config.SecurityConfig? = null,
    firmwareVersion: String? = null,
) {
    /** The items that will be sent: everything this firmware can apply. */
    val items: List<ImportItem>

    /** Items in the profile this firmware cannot apply. Never sent. */
    val unsupported: List<Pair<ImportItem, FirmwareSupport>>

    init {
        val all = mutableListOf<ImportItem>()
        val config = profile.config
        val module = profile.moduleConfig

        fun configPayload(build: ConfigProtos.Config.Builder.() -> Unit) =
            ImportPayload.Config(ConfigProtos.Config.newBuilder().apply(build).build())

        fun modulePayload(build: ModuleConfigProtos.ModuleConfig.Builder.() -> Unit) =
            ImportPayload.Module(ModuleConfigProtos.ModuleConfig.newBuilder().apply(build).build())

        // 1. Owner name. Only when the profile carries one and there is a user to merge onto.
        if ((profile.hasLongName() || profile.hasShortName() || profile.hasIsUnmessagable()) && currentUser != null) {
            val merged = ownerUser(profile, currentUser)
            val name = listOfNotNull(
                if (profile.hasLongName()) profile.longName else merged.longName,
                if (profile.hasShortName()) "(${profile.shortName})" else null,
            ).joinToString(" ")
            all += ImportItem(
                ImportItemKind.OWNER, ImportSection.OWNER, name.ifEmpty { "Owner name" },
                isSensitive = false, mayReboot = true, payload = ImportPayload.Owner(merged),
            )
        }

        // 2-5. Radio and device configs. LoRa is kept for the terminal step so it stays
        // paired with the channel URL that carries the same LoRaConfig.
        if (profile.hasConfig()) {
            if (config.hasDevice()) {
                all += ImportItem(
                    ImportItemKind.DEVICE_CONFIG, ImportSection.RADIO_AND_DEVICE, "Device role & behavior",
                    false, true, configPayload { device = config.device },
                )
            }
            if (config.hasDisplay()) {
                all += ImportItem(
                    ImportItemKind.DISPLAY_CONFIG, ImportSection.RADIO_AND_DEVICE, "Display settings",
                    false, true, configPayload { display = config.display },
                )
            }
            if (config.hasPosition()) {
                all += ImportItem(
                    ImportItemKind.POSITION_CONFIG, ImportSection.RADIO_AND_DEVICE, "Position settings",
                    false, true, configPayload { position = config.position },
                )
            }
            if (config.hasPower()) {
                all += ImportItem(
                    ImportItemKind.POWER_CONFIG, ImportSection.RADIO_AND_DEVICE, "Power settings",
                    false, true, configPayload { power = config.power },
                )
            }
        }

        // 6. Module configs, in the order export writes them. MQTT is sensitive (password).
        if (profile.hasModuleConfig()) {
            fun add(kind: ImportItemKind, summary: String, sensitive: Boolean, reboot: Boolean, payload: ImportPayload) {
                all += ImportItem(kind, ImportSection.MODULES, summary, sensitive, reboot, payload)
            }
            if (module.hasMqtt()) add(ImportItemKind.MQTT, "MQTT", true, true, modulePayload { mqtt = module.mqtt })
            if (module.hasSerial()) add(ImportItemKind.SERIAL, "Serial", false, true, modulePayload { serial = module.serial })
            if (module.hasExternalNotification()) {
                add(
                    ImportItemKind.EXTERNAL_NOTIFICATION, "External Notification", false, true,
                    modulePayload { externalNotification = module.externalNotification },
                )
            }
            if (module.hasStoreForward()) {
                add(ImportItemKind.STORE_FORWARD, "Store & Forward", false, true, modulePayload { storeForward = module.storeForward })
            }
            if (module.hasRangeTest()) {
                add(ImportItemKind.RANGE_TEST, "Range Test", false, true, modulePayload { rangeTest = module.rangeTest })
            }
            if (module.hasTelemetry()) {
                add(ImportItemKind.TELEMETRY, "Telemetry", false, true, modulePayload { telemetry = module.telemetry })
            }
            if (module.hasCannedMessage()) {
                add(ImportItemKind.CANNED_MESSAGE, "Canned Message", false, true, modulePayload { cannedMessage = module.cannedMessage })
            }
            if (module.hasAudio()) add(ImportItemKind.AUDIO, "Audio", false, true, modulePayload { audio = module.audio })
            if (module.hasNeighborInfo()) {
                add(ImportItemKind.NEIGHBOR_INFO, "Neighbor Info", false, true, modulePayload { neighborInfo = module.neighborInfo })
            }
            if (module.hasAmbientLighting()) {
                add(
                    ImportItemKind.AMBIENT_LIGHTING, "Ambient Lighting", false, true,
                    modulePayload { ambientLighting = module.ambientLighting },
                )
            }
            if (module.hasDetectionSensor()) {
                add(
                    ImportItemKind.DETECTION_SENSOR, "Detection Sensor", false, true,
                    modulePayload { detectionSensor = module.detectionSensor },
                )
            }
            if (module.hasPaxcounter()) {
                add(ImportItemKind.PAXCOUNTER, "PAX Counter", false, true, modulePayload { paxcounter = module.paxcounter })
            }
            if (module.hasTak()) add(ImportItemKind.TAK, "TAK", false, true, modulePayload { tak = module.tak })
            if (module.hasStatusmessage()) {
                add(ImportItemKind.STATUS_MESSAGE, "Status Message", false, false, modulePayload { statusmessage = module.statusmessage })
            }
        }

        // 7. Ringtone and canned-message text, after the Canned Message module config so
        // the text is not clobbered by the config that follows it.
        if (profile.hasRingtone() && profile.ringtone.isNotEmpty()) {
            all += ImportItem(
                ImportItemKind.RINGTONE, ImportSection.PERSONALIZATION, "Ringtone",
                false, false, ImportPayload.Ringtone(profile.ringtone),
            )
        }
        if (profile.hasCannedMessages() && profile.cannedMessages.isNotEmpty()) {
            all += ImportItem(
                ImportItemKind.CANNED_MESSAGES_TEXT, ImportSection.PERSONALIZATION, "Canned messages",
                false, false, ImportPayload.CannedMessagesText(profile.cannedMessages),
            )
        }

        // 8. Fixed position, only for real coordinates, after the position config whose
        // fixed-position flag it depends on.
        if (profile.hasFixedPosition() &&
            (profile.fixedPosition.latitudeI != 0 || profile.fixedPosition.longitudeI != 0)
        ) {
            all += ImportItem(
                ImportItemKind.FIXED_POSITION, ImportSection.FIXED_POSITION, "Fixed position",
                false, false, ImportPayload.FixedPosition(profile.fixedPosition),
            )
        }

        // 9. Network, late because enabling Wi-Fi or Ethernet can drop a TCP link mid-import.
        if (profile.hasConfig() && config.hasNetwork()) {
            val ssid = config.network.wifiSsid
            all += ImportItem(
                ImportItemKind.NETWORK_CONFIG, ImportSection.NETWORK,
                if (ssid.isEmpty()) "Wi-Fi / Ethernet" else "Wi-Fi SSID: $ssid",
                true, true, configPayload { network = config.network },
            )
        }

        // 10. Bluetooth, just before the end: a mode or pin change can drop the BLE link.
        if (profile.hasConfig() && config.hasBluetooth()) {
            all += ImportItem(
                ImportItemKind.BLUETOOTH_CONFIG, ImportSection.RADIO_AND_DEVICE, "Bluetooth settings",
                false, true, configPayload { bluetooth = config.bluetooth },
            )
        }

        // 11. Security: opt-in, and late because admin-key or managed changes can restrict
        // further local admin.
        if (profile.hasConfig() && config.hasSecurity()) {
            val merged = securityConfig(config.security, currentSecurity)
            val keepsIdentity = currentSecurity != null &&
                config.security.privateKey.isEmpty && config.security.publicKey.isEmpty
            all += ImportItem(
                ImportItemKind.SECURITY_CONFIG, ImportSection.SECURITY,
                if (keepsIdentity) "Admin access & flags (keeps this node's keys)" else "Node identity, keys & admin access",
                true, true, configPayload { security = merged },
            )
        }

        // 12. Terminal: Channels & LoRa. Export puts the same LoRaConfig in config.lora and
        // in the channel URL, so exactly one owner is emitted: the URL when it parses as a
        // replace with a LoRa config, else the standalone LoRa config.
        var addedChannelOrLora = false
        if (profile.hasChannelUrl() && profile.channelUrl.isNotEmpty()) {
            val set = ChannelCodec.fromUrl(profile.channelUrl)
            if (set != null && set.hasLoraConfig()) {
                val n = set.settingsCount
                all += ImportItem(
                    ImportItemKind.CHANNEL_URL, ImportSection.CHANNELS_AND_LORA,
                    "Channels & LoRa region ($n channel${if (n == 1) "" else "s"})",
                    true, false, ImportPayload.ChannelUrl(set),
                )
                addedChannelOrLora = true
            }
        }
        if (!addedChannelOrLora && profile.hasConfig() && config.hasLora()) {
            all += ImportItem(
                ImportItemKind.LORA_CONFIG, ImportSection.CHANNELS_AND_LORA,
                "LoRa region: ${config.lora.region}",
                false, true, configPayload { lora = config.lora },
            )
        }

        val (applyable, rejected) = all.partition { isSupported(it.kind.firmwareSupport, firmwareVersion) }
        // A profile whose only contents are unsupported still has nothing to apply.
        if (applyable.isEmpty()) throw DeviceProfileImportException(DeviceProfileImportError.NOTHING_TO_IMPORT)
        items = applyable
        unsupported = rejected.map { it to it.kind.firmwareSupport }
    }

    /** The sections present in this profile, in canonical order. */
    val presentSections: List<ImportSection>
        get() = ImportSection.entries.filter { section -> items.any { it.section == section } }

    /** The items of the selected sections, in apply order. */
    fun items(selection: Set<ImportSection>): List<ImportItem> = items.filter { it.section in selection }

    fun containsSensitive(selection: Set<ImportSection>): Boolean = items(selection).any { it.isSensitive }

    companion object {
        /** A real profile is a few KB; anything past this is not handed to the decoder. */
        const val MAX_PROFILE_BYTES = 512 * 1024

        /** parseDeviceProfile: the trust boundary for an imported file. */
        fun parseDeviceProfile(data: ByteArray): ClientOnlyProtos.DeviceProfile {
            if (data.isEmpty()) throw DeviceProfileImportException(DeviceProfileImportError.EMPTY_FILE)
            if (data.size > MAX_PROFILE_BYTES) throw DeviceProfileImportException(DeviceProfileImportError.TOO_LARGE)
            return runCatching { ClientOnlyProtos.DeviceProfile.parseFrom(data) }
                .getOrElse { throw DeviceProfileImportException(DeviceProfileImportError.MALFORMED) }
        }

        /**
         * ownerUser: the profile's names over the node's existing user. is_licensed is
         * deliberately not copied; ham mode is a flow of its own with side effects a plain
         * set_owner would skip.
         */
        fun ownerUser(profile: ClientOnlyProtos.DeviceProfile, base: MeshProtos.User): MeshProtos.User {
            val user = base.toBuilder()
            if (profile.hasLongName()) user.longName = profile.longName
            if (profile.hasShortName()) user.shortName = profile.shortName
            if (profile.hasIsUnmessagable()) user.isUnmessagable = profile.isUnmessagable
            return user.build()
        }

        /**
         * securityConfig: the profile's security block over the node's current one, keeping
         * the node's own keys wherever the profile carries none. A security block has no
         * per-field presence, so a partial or event config that leaves the keypair empty
         * would otherwise wipe this node's identity. An empty key is never a valid identity.
         */
        fun securityConfig(
            profile: ConfigProtos.Config.SecurityConfig,
            base: ConfigProtos.Config.SecurityConfig?,
        ): ConfigProtos.Config.SecurityConfig {
            if (base == null) return profile
            val merged = profile.toBuilder()
            if (merged.privateKey.isEmpty) merged.privateKey = base.privateKey
            if (merged.publicKey.isEmpty) merged.publicKey = base.publicKey
            if (profile.adminKeyList.all { it.isEmpty }) {
                merged.clearAdminKey()
                merged.addAllAdminKey(base.adminKeyList)
            }
            return merged.build()
        }

        /** isSupported: permissive when the firmware version is unknown. */
        fun isSupported(support: FirmwareSupport, firmwareVersion: String?): Boolean = when (support) {
            FirmwareSupport.Always -> true
            FirmwareSupport.Unimplemented -> false
            is FirmwareSupport.FromVersion -> {
                if (firmwareVersion.isNullOrEmpty() || firmwareVersion == "0.0.0") true
                else compareVersions(support.version, firmwareVersion) <= 0
            }
        }

        /** Numeric comparison of the leading dotted numbers ("2.7.20" against "2.7.20.6658ec2"). */
        private fun compareVersions(a: String, b: String): Int {
            val x = a.split('.').take(3).map { it.toIntOrNull() ?: 0 }
            val y = b.split('.').take(3).map { it.toIntOrNull() ?: 0 }
            for (i in 0 until 3) {
                val d = x.getOrElse(i) { 0 }.compareTo(y.getOrElse(i) { 0 })
                if (d != 0) return d
            }
            return 0
        }
    }
}

// ====================================================================== Import engine

/**
 * ProfileApplyGateway: turns one item into an admin send. The engine holds the ordering,
 * abort and commit logic; this seam is what touches the radio.
 */
interface ProfileApplyGateway {
    val isConnected: Boolean

    /** Applies one item, throwing on a real failure. */
    suspend fun apply(item: ImportItem)

    /** begin_edit_settings: the firmware defers every save and reboot until the commit. */
    suspend fun beginEditSettings()

    /** commit_edit_settings: one save, one reboot. */
    suspend fun commitEditSettings()
}

/** DeviceProfileImportResult. */
data class DeviceProfileImportResult(
    val applied: List<ImportItemKind> = emptyList(),
    val skipped: List<ImportItemKind> = emptyList(),
    /** The first item that failed, with the reason. The run stops there. */
    val failed: Pair<ImportItemKind, String>? = null,
    /** The radio reboots as a result of this run. */
    val rebooting: Boolean = false,
    val usedTransaction: Boolean = false,
    /** The commit was sent, and either acked or dropped by the reboot it causes. */
    val transactionCommitted: Boolean = false,
    /** The commit could not be confirmed: the radio may still hold an open transaction. */
    val commitUnconfirmed: Boolean = false,
    /** Items the commit's disconnect left unsent. Not failures; they need a second pass. */
    val requiresReconnect: List<ImportItemKind> = emptyList(),
    val wasCancelled: Boolean = false,
) {
    val isCompleteSuccess: Boolean get() = failed == null && !wasCancelled
}

/**
 * DeviceProfileImporter.swift: walks a plan in order inside one firmware edit transaction.
 *
 * The run is `begin_edit_settings`, the items, `commit_edit_settings`. Without the bracket
 * the firmware treats each send as a standalone write: it saves to flash, schedules a
 * reboot and tears down Bluetooth for nearly every config, which drops the link a few
 * items in.
 *
 * Cancellation is a flag the engine reads between items, not coroutine cancellation: the
 * commit must always be sent, because the firmware has no abort and an uncommitted
 * transaction leaves the radio discarding config from every client until it reboots. The
 * caller runs this on a scope that outlives the screen for the same reason.
 */
object DeviceProfileImporter {

    /**
     * Pause between sends. The radio drops writes it cannot process in time and still acks
     * them; iOS measured a burst of 8 module writes losing up to 3, and none at this spacing.
     */
    const val DEFAULT_SEND_INTERVAL_MS = 150L

    private const val DISCONNECTED = "The radio disconnected before this step could be applied."

    private class Run(
        val gateway: ProfileApplyGateway,
        val sendIntervalMs: Long,
        val isCancelled: () -> Boolean,
        val progress: ((ImportItem, Int, Int) -> Unit)?,
        val total: Int,
    )

    suspend fun apply(
        plan: DeviceProfileImportPlan,
        selection: Set<ImportSection>,
        gateway: ProfileApplyGateway,
        sendIntervalMs: Long = DEFAULT_SEND_INTERVAL_MS,
        isCancelled: () -> Boolean = { false },
        progress: ((ImportItem, Int, Int) -> Unit)? = null,
    ): DeviceProfileImportResult {
        var result = DeviceProfileImportResult()
        val all = plan.items(selection)
        if (all.isEmpty()) return result

        // MQTT and Serial run after the commit. The firmware disables Bluetooth for exactly
        // those two whether or not a transaction is open, so inside the transaction they
        // would drop the link before the commit could arrive and lose the whole import.
        val deferredKinds = setOf(ImportItemKind.MQTT, ImportItemKind.SERIAL)
        val transactional = all.filter { it.kind !in deferredKinds }
        val deferred = all.filter { it.kind in deferredKinds }

        // 1. Open the transaction before anything is sent.
        if (!gateway.isConnected) {
            return result.copy(failed = all[0].kind to DISCONNECTED, skipped = all.drop(1).map { it.kind })
        }
        try {
            // Sent twice on purpose: begin is idempotent and never acked, so a dropped one
            // would silently turn the whole run into standalone writes.
            gateway.beginEditSettings()
            delay(sendIntervalMs)
            gateway.beginEditSettings()
            result = result.copy(usedTransaction = true)
        } catch (e: Exception) {
            return result.copy(
                failed = all[0].kind to "Could not open a settings transaction on the radio: ${e.message}",
                skipped = all.drop(1).map { it.kind },
            )
        }

        // 2. Everything except MQTT and Serial, inside the transaction.
        val context = Run(gateway, sendIntervalMs, isCancelled, progress, all.size)
        result = runItems(transactional, 0, deferred.map { it.kind }, context, result).first

        // 3. Commit unconditionally, even when step 2 stopped early or was cancelled.
        val connectedBeforeCommit = gateway.isConnected
        try {
            // Let the radio drain the last write before the commit lands on top of it.
            delay(sendIntervalMs)
            gateway.commitEditSettings()
            result = result.copy(transactionCommitted = true)
        } catch (e: Exception) {
            result = if (connectedBeforeCommit && !gateway.isConnected) {
                // The link dropped as the commit was processed: the commit disables Bluetooth
                // and reboots, so the send's own completion is routinely lost.
                Log.w(TAG, "Settings transaction commit did not complete; radio is rebooting: ${e.message}")
                result.copy(transactionCommitted = true)
            } else {
                Log.e(TAG, "Settings transaction commit unconfirmed (connected before: $connectedBeforeCommit): ${e.message}")
                result.copy(commitUnconfirmed = true)
            }
        }
        // The commit saves every segment and reboots, so any run that got that far reboots.
        if (result.applied.isNotEmpty()) result = result.copy(rebooting = true)

        // 4. MQTT and Serial, outside the transaction. Over BLE the commit has normally
        // taken the link down already; a miss here is not a failure, since everything else
        // is committed.
        if (result.failed != null || result.wasCancelled || deferred.isEmpty()) return result
        val (outcome, allApplied) = runItems(deferred, transactional.size, emptyList(), context, DeviceProfileImportResult())
        result = result.copy(applied = result.applied + outcome.applied)
        if (!allApplied) {
            result = result.copy(requiresReconnect = deferred.map { it.kind }.filter { it !in outcome.applied })
        }
        return result
    }

    /**
     * One ordered run. On an early stop the current item is the failure (or the run is
     * cancelled) and everything after it, [pendingAfter] included, is skipped.
     */
    private suspend fun runItems(
        items: List<ImportItem>,
        progressOffset: Int,
        pendingAfter: List<ImportItemKind>,
        run: Run,
        into: DeviceProfileImportResult,
    ): Pair<DeviceProfileImportResult, Boolean> {
        var result = into
        items.forEachIndexed { index, item ->
            // Pace every send after the first.
            if (index > 0) delay(run.sendIntervalMs)
            if (run.isCancelled()) {
                return result.copy(wasCancelled = true, skipped = items.drop(index).map { it.kind } + pendingAfter) to false
            }
            if (!run.gateway.isConnected) {
                return result.copy(
                    failed = item.kind to DISCONNECTED,
                    skipped = items.drop(index + 1).map { it.kind } + pendingAfter,
                ) to false
            }
            run.progress?.invoke(item, progressOffset + index, run.total)
            try {
                run.gateway.apply(item)
                result = result.copy(
                    applied = result.applied + item.kind,
                    rebooting = result.rebooting || item.mayReboot,
                )
            } catch (e: Exception) {
                return result.copy(
                    failed = item.kind to (e.message ?: "The send failed."),
                    skipped = items.drop(index + 1).map { it.kind } + pendingAfter,
                ) to false
            }
        }
        return result to true
    }
}

/** AccessoryProfileApplyGateway: every send is self-admin to the connected radio. */
class RadioProfileApplyGateway(private val radio: RadioManager) : ProfileApplyGateway {

    override val isConnected: Boolean get() = radio.state.value is RadioState.Subscribed

    private fun sentOrThrow(sent: Boolean) {
        if (!sent) throw java.io.IOException("The message could not be sent to the radio.")
    }

    override suspend fun beginEditSettings() = sentOrThrow(radio.beginEditSettings())

    override suspend fun commitEditSettings() = sentOrThrow(radio.commitEditSettings())

    override suspend fun apply(item: ImportItem) {
        when (val payload = item.payload) {
            is ImportPayload.Owner ->
                sentOrThrow(radio.setOwner(payload.user.longName, payload.user.shortName, payload.user.isUnmessagable))
            is ImportPayload.Config -> {
                val sent = radio.setConfigUntransacted(payload.config)
                // A standalone LoRa write can reboot the radio under the send; only a send
                // that failed while still connected is a real failure.
                if (item.kind == ImportItemKind.LORA_CONFIG && !sent && !isConnected) {
                    Log.w(TAG, "LoRa config send did not complete; radio is likely rebooting")
                } else sentOrThrow(sent)
            }
            is ImportPayload.Module -> sentOrThrow(radio.setModuleConfig(payload.config))
            is ImportPayload.Ringtone -> sentOrThrow(radio.setRingtone(payload.text))
            is ImportPayload.CannedMessagesText -> sentOrThrow(radio.setCannedMessages(payload.text))
            is ImportPayload.FixedPosition -> sentOrThrow(radio.setFixedPosition(payload.position))
            is ImportPayload.ChannelUrl -> radio.replaceChannelSet(payload.set)
        }
    }
}

// ====================================================================== Verification

/** VerificationOutcome: what the readback says about one item. */
sealed interface VerificationOutcome {
    /** Every field the profile set to a non-default value is on the radio. */
    data object Applied : VerificationOutcome

    /** Nothing landed and the radio still holds its pre-import values: a dropped message. */
    data object LikelyDropped : VerificationOutcome

    /** Some fields differ. Firmware normalizes some inputs, so this is not proof of failure. */
    data class Inconclusive(val detail: String) : VerificationOutcome

    data class NotComparable(val reason: String) : VerificationOutcome
}

/** DeviceProfileVerificationReadiness: whether a post-import readback can be checked, once. */
data class DeviceProfileVerificationReadiness(
    val expectsReconnect: Boolean,
    val refreshNotBefore: Long?,
    val lastConfigRefresh: Long?,
    val hasVerification: Boolean,
) {
    val shouldVerify: Boolean
        get() = expectsReconnect && !hasVerification && refreshNotBefore != null &&
            lastConfigRefresh != null && lastConfigRefresh >= refreshNotBefore
}

data class DeviceProfileVerification(
    val outcomes: List<Pair<ImportItemKind, VerificationOutcome>> = emptyList(),
    /** Set when verification could not run at all, with the reason. */
    val unavailable: String? = null,
)

/**
 * DeviceProfileVerifier.swift: checks after the fact whether an import landed. The firmware
 * acks writes it silently discards, so only reading the config back proves anything.
 *
 * The comparison is per field, over the fields the profile set to a non-default value.
 * iOS gets that projection from protobuf JSON, which omits defaults; the lite runtime here
 * has no JSON, but the wire encoding omits proto3 defaults the same way, so the fields are
 * read straight off the serialized message and named by their field number.
 */
object DeviceProfileVerifier {

    /** The stored value for each kind: what the radio last sent, as the export reads it. */
    suspend fun snapshot(
        db: MeshDatabase,
        myNum: Long,
        kinds: Collection<ImportItemKind>,
    ): Map<ImportItemKind, ImportPayload> {
        val stored = db.configDao().all().associate { it.type to it.bytes }
        val result = mutableMapOf<ImportItemKind, ImportPayload>()
        for (kind in kinds) {
            if (kind == ImportItemKind.OWNER) {
                db.userDao().get(myNum)?.let { result[kind] = ImportPayload.Owner(ownerProto(it.longName, it.shortName, it.unmessagable)) }
                continue
            }
            val key = kind.configKey ?: continue
            val bytes = stored[key] ?: continue
            runCatching {
                result[kind] = if (key.startsWith("config.")) ImportPayload.Config(ConfigProtos.Config.parseFrom(bytes))
                else ImportPayload.Module(ModuleConfigProtos.ModuleConfig.parseFrom(bytes))
            }
        }
        return result
    }

    /** The three owner fields set_owner carries, which is all the comparison can see. */
    fun ownerProto(longName: String?, shortName: String?, unmessagable: Boolean): MeshProtos.User =
        MeshProtos.User.newBuilder()
            .setLongName(longName ?: "")
            .setShortName(shortName ?: "")
            .setIsUnmessagable(unmessagable)
            .build()

    fun verify(
        applied: List<ImportItemKind>,
        plan: DeviceProfileImportPlan,
        before: Map<ImportItemKind, ImportPayload>,
        after: Map<ImportItemKind, ImportPayload>,
        lastConfigRefresh: Long?,
        readbackNotBefore: Long,
    ): DeviceProfileVerification {
        // A readback older than the import still holds pre-import values, which would read
        // as a total wipe. Refuse rather than report a false failure.
        if (lastConfigRefresh == null) {
            return DeviceProfileVerification(unavailable = "The app has not received a configuration from the radio yet.")
        }
        if (lastConfigRefresh < readbackNotBefore) {
            return DeviceProfileVerification(unavailable = "The radio has not sent its configuration since the import began.")
        }
        val sentByKind = plan.items.associate { it.kind to it.payload }
        return DeviceProfileVerification(
            outcomes = applied.mapNotNull { kind ->
                sentByKind[kind]?.let { kind to outcome(it, before[kind], after[kind]) }
            }
        )
    }

    private fun outcome(sent: ImportPayload, before: ImportPayload?, actual: ImportPayload?): VerificationOutcome {
        val sentMessage = sent.sentMessage()
            ?: return VerificationOutcome.NotComparable("This setting cannot be read back from the radio.")
        val actualMessage = actual?.message
            ?: return VerificationOutcome.NotComparable("The app has no stored value for this setting.")

        val sentFields = nonDefaultFields(sentMessage)
        if (sentFields.isEmpty()) {
            return VerificationOutcome.NotComparable("The profile set only default values, which cannot be verified.")
        }
        val actualFields = nonDefaultFields(actualMessage)
        val beforeFields = before?.message?.let(::nonDefaultFields) ?: emptyMap()

        val landed = mutableListOf<Int>()
        val missing = mutableListOf<Int>()
        var unchangedFromBefore = 0
        for ((field, sentValue) in sentFields) {
            val actualValue = actualFields[field]
            if (actualValue == sentValue) landed += field
            else {
                missing += field
                if (actualValue == beforeFields[field]) unchangedFromBefore++
            }
        }
        if (missing.isEmpty()) return VerificationOutcome.Applied
        if (landed.isEmpty() && unchangedFromBefore == missing.size) return VerificationOutcome.LikelyDropped
        val sample = missing.sorted().take(3).joinToString(", ") { "#$it" }
        return VerificationOutcome.Inconclusive(
            "${landed.size} of ${sentFields.size} settings match; differing fields: $sample${if (missing.size > 3) "…" else ""}"
        )
    }

    /** The owner goes out as three fields only, so only those are compared. */
    private fun ImportPayload.sentMessage(): MessageLite? = when (this) {
        is ImportPayload.Owner -> ownerProto(user.longName, user.shortName, user.isUnmessagable)
        else -> message
    }

    /**
     * Top-level fields with a non-default value, as field number to rendered value. A
     * repeated field renders every element; a sub-message renders as its bytes.
     */
    internal fun nonDefaultFields(message: MessageLite): Map<Int, String> {
        val fields = sortedMapOf<Int, MutableList<String>>()
        runCatching {
            val input = CodedInputStream.newInstance(message.toByteArray())
            while (true) {
                val tag = input.readTag()
                if (tag == 0) break
                val number = WireFormat.getTagFieldNumber(tag)
                val value = when (WireFormat.getTagWireType(tag)) {
                    WireFormat.WIRETYPE_VARINT -> input.readRawVarint64().toString()
                    WireFormat.WIRETYPE_FIXED64 -> input.readRawLittleEndian64().toString()
                    WireFormat.WIRETYPE_FIXED32 -> input.readRawLittleEndian32().toString()
                    WireFormat.WIRETYPE_LENGTH_DELIMITED ->
                        Base64.encodeToString(input.readByteArray(), Base64.NO_WRAP)
                    else -> {
                        input.skipField(tag)
                        "?"
                    }
                }
                fields.getOrPut(number) { mutableListOf() } += value
            }
        }
        return fields.mapValues { it.value.joinToString("|") }
    }
}

// ====================================================================== Progress

/**
 * DeviceProfileImportProgress.swift: progress of a run, fed by the importer's callback. The
 * importer reorders (MQTT and Serial go after the commit), so the index is a position in
 * the send order, not in the plan.
 */
data class ImportApplyProgress(
    private val sectionItemCounts: Map<ImportSection, Int>,
    private val sectionAnnouncedCounts: Map<ImportSection, Int> = emptyMap(),
    val currentSection: ImportSection? = null,
    val currentItemTitle: String = "",
    val currentIndex: Int = 0,
) {
    enum class SectionStatus { PENDING, SENDING, SENT }

    val total: Int get() = sectionItemCounts.values.sum()

    val fractionCompleted: Float get() = if (total > 0) currentIndex.toFloat() / total else 0f

    fun announce(item: ImportItem, index: Int): ImportApplyProgress = copy(
        sectionAnnouncedCounts = sectionAnnouncedCounts + (item.section to ((sectionAnnouncedCounts[item.section] ?: 0) + 1)),
        currentSection = item.section,
        currentItemTitle = item.kind.displayName,
        currentIndex = index,
    )

    fun sectionStatus(section: ImportSection): SectionStatus {
        val expected = sectionItemCounts[section] ?: 0
        if (expected <= 0) return SectionStatus.PENDING
        if (currentSection == section) return SectionStatus.SENDING
        return if ((sectionAnnouncedCounts[section] ?: 0) >= expected) SectionStatus.SENT else SectionStatus.PENDING
    }
}
