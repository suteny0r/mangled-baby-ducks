package com.suteny0r.mangledbabyducks

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import android.util.Log
import com.suteny0r.mangledbabyducks.db.BackupResult
import com.suteny0r.mangledbabyducks.db.ChannelEntity
import com.suteny0r.mangledbabyducks.db.MapNode
import com.suteny0r.mangledbabyducks.db.MeshDatabase
import com.suteny0r.mangledbabyducks.db.NodeBackupManager
import com.suteny0r.mangledbabyducks.db.NodeWithUser
import com.suteny0r.mangledbabyducks.db.UserEntity
import com.suteny0r.mangledbabyducks.radio.BleScanner
import com.suteny0r.mangledbabyducks.radio.HardwareCatalog
import com.suteny0r.mangledbabyducks.radio.LanScanner
import com.suteny0r.mangledbabyducks.radio.LocationSharer
import com.suteny0r.mangledbabyducks.radio.MeshProtocol
import com.suteny0r.mangledbabyducks.radio.MessageNotifier
import com.suteny0r.mangledbabyducks.radio.PacketIngest
import com.suteny0r.mangledbabyducks.diagnostics.LogStore
import com.suteny0r.mangledbabyducks.net.MeshPicCache
import com.suteny0r.mangledbabyducks.net.MeshPicLinks
import com.suteny0r.mangledbabyducks.radio.PresenceProbe
import com.suteny0r.mangledbabyducks.radio.RadioConnection
import com.suteny0r.mangledbabyducks.radio.RadioManager
import com.suteny0r.mangledbabyducks.radio.RadioService
import com.suteny0r.mangledbabyducks.radio.TcpConnection
import com.suteny0r.mangledbabyducks.ui.Router
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private val Context.settingsDataStore by preferencesDataStore("settings")

/** DataStore keys for the remembered radio, used for reconnect on app start. */
object PrefKeys {
    // The auto-connect target: the radio the app reaches for on launch.
    val RADIO_TYPE = stringPreferencesKey("radio_type") // "ble" or "tcp"
    val RADIO_ADDRESS = stringPreferencesKey("radio_address") // MAC or host:port
    val RADIO_NAME = stringPreferencesKey("radio_name")

    /** Every radio this app has connected to, as a JSON array; see [knownRadios]. */
    val KNOWN_RADIOS = stringPreferencesKey("known_radios")

    val SHARE_LOCATION = booleanPreferencesKey("share_location")

    /**
     * Whether favorites are lifted to the top of the node list. Off leaves the list in
     * plain most-recently-heard order (the connected radio still leads). Shared by the
     * phone list and the Android Auto one, which has no toggle of its own.
     */
    val PIN_FAVORITES = booleanPreferencesKey("pin_favorites")
}

/** How many radios to keep before the least recently used one is dropped. */
private const val MAX_KNOWN_RADIOS = 12
private const val TAG = "AppContainer"

/** A radio the app has connected to, as stored in DataStore. */
data class RememberedRadio(
    val type: String,
    val address: String,
    val name: String?,
    val lastConnectedMs: Long = 0L,
    /**
     * The short name from the radio's owner record: the icon or initials the user chose.
     * Kept apart from [name], which is whatever the radio was advertising or the host it
     * answers on, because the two disagree and only one of them is the user's choice.
     */
    val ownerShort: String? = null,
) {
    /** What the UI calls this radio; the address is the fallback when nothing else is known. */
    val label: String get() =
        advertisedForm() ?: name?.takeIf { it.isNotBlank() } ?: address

    /**
     * The name the radio ought to be advertising, rebuilt from what it told us over the
     * link: the owner's short name and the last two bytes of its MAC, lowercase, which is
     * what stock Meshtastic puts in its advertisement.
     *
     * Rebuilt rather than read off the air because some hardware does not follow its own
     * owner: the GAT562s advertise `GAT562_xxxx` however they are renamed. Keeping the
     * MAC tail matters as much as the short name does, because a short name is often a
     * single emoji and these rows show no address underneath to tell two of them apart.
     */
    private fun advertisedForm(): String? {
        val short = ownerShort?.takeIf { it.isNotBlank() } ?: return null
        // No tail, no form: a TCP radio is addressed by host, and "Connecting to 👀" with
        // the host thrown away would be worse than what it says today.
        return macTail(address)?.let { "${short}_$it" }
    }
}

/** The last two bytes of a BLE MAC, lowercase; null for anything not shaped like one. */
private fun macTail(address: String): String? {
    val parts = address.split(':')
    if (parts.size != 6 || parts.any { it.length != 2 }) return null
    return (parts[4] + parts[5]).lowercase()
}

/** The radio the app reaches for on launch, or null once auto-connect has been cleared. */
fun Preferences.rememberedRadio(): RememberedRadio? {
    val type = this[PrefKeys.RADIO_TYPE] ?: return null
    val address = this[PrefKeys.RADIO_ADDRESS] ?: return null
    return RememberedRadio(type, address, this[PrefKeys.RADIO_NAME])
}

/**
 * Every radio the app has connected to, most recent first, so the user can pick one
 * without scanning. Installs that predate the list contribute their single auto-connect
 * target, which is the migration path.
 */
fun Preferences.knownRadios(): List<RememberedRadio> {
    val stored = this[PrefKeys.KNOWN_RADIOS]
    val parsed = if (stored.isNullOrBlank()) {
        emptyList()
    } else {
        runCatching { decodeRadios(stored) }.getOrDefault(emptyList())
    }
    val target = rememberedRadio()
    val merged = if (target != null && parsed.none { it.address == target.address }) {
        parsed + target
    } else {
        parsed
    }
    return merged.sortedByDescending { it.lastConnectedMs }
}

private fun encodeRadios(radios: List<RememberedRadio>): String {
    val array = JSONArray()
    radios.forEach { radio ->
        array.put(
            JSONObject().apply {
                put("type", radio.type)
                put("address", radio.address)
                radio.name?.let { put("name", it) }
                radio.ownerShort?.let { put("ownerShort", it) }
                put("lastConnected", radio.lastConnectedMs)
            }
        )
    }
    return array.toString()
}

private fun decodeRadios(json: String): List<RememberedRadio> {
    val array = JSONArray(json)
    return (0 until array.length()).mapNotNull { index ->
        val entry = array.optJSONObject(index) ?: return@mapNotNull null
        val type = entry.optString("type").ifEmpty { return@mapNotNull null }
        val address = entry.optString("address").ifEmpty { return@mapNotNull null }
        RememberedRadio(
            type = type,
            address = address,
            name = entry.optString("name").ifEmpty { null },
            lastConnectedMs = entry.optLong("lastConnected"),
            ownerShort = entry.optString("ownerShort").ifEmpty { null },
        )
    }
}

/** Manual singleton graph; the app is small enough not to need a DI framework yet. */
class AppContainer(context: Context) {
    val database: MeshDatabase = MeshDatabase.build(context)
    /** One database snapshot per radio; see NodeBackupManager and [switchRadio]. */
    val backups = NodeBackupManager(context, database)
    val ingest = PacketIngest(database, backups)
    val radioManager = RadioManager(database, ingest)
    val bleScanner = BleScanner(context)
    val lanScanner = LanScanner(context)
    val messageNotifier = MessageNotifier(context, database, radioManager)
    val router = Router()
    val prefs = context.settingsDataStore
    val locationSharer = LocationSharer(context, radioManager, prefs)
    /** Device catalog for the node detail hardware card; refreshes at most every 48 h. */
    val hardwareCatalog = HardwareCatalog(context).also { it.refresh() }

    /**
     * Started as early as the container is built, because the whole point is to have the
     * history already on disk by the time someone notices a fault and exports it.
     */
    val logStore = LogStore(context).also { it.start() }

    /** Process-lifetime scope for the shared hot flows below and the launch-time prune. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Fetch a shared picture the moment its message lands, not when someone finally
        // opens the thread. meshpic.org deletes an image after about a day, so a message
        // that arrives overnight would otherwise lose its picture before it was ever seen,
        // and the link left behind would be dead. Once the copy is local it is permanent.
        appScope.launch {
            radioManager.incomingMessages.collect { message ->
                MeshPicLinks.find(message.payload.orEmpty()).forEach { picture ->
                    runCatching { MeshPicCache.get(context, picture.shortId) }
                }
            }
        }
        // Keep the saved radio's name in step with the owner record. Keyed on the owner
        // row rather than on connect alone, so renaming the radio in Settings updates the
        // list straight away instead of waiting for the next session.
        @OptIn(ExperimentalCoroutinesApi::class)
        appScope.launch {
            radioManager.myNodeNum
                .flatMapLatest { num ->
                    if (num == 0L) flowOf(null) else database.userDao().observe(num)
                }
                .distinctUntilChangedBy { it?.shortName }
                .collect { owner ->
                    val num = owner?.num ?: return@collect
                    runCatching { refreshOwnerName(num) }
                        .onFailure { Log.w(TAG, "Could not record the radio's owner name", it) }
                }
        }
    }

    /**
     * The two lists every surface reads, kept hot for the whole process so a screen can
     * paint from `.value` on its very first frame.
     *
     * This exists for Android Auto. A car Screen is constructed fresh on each push and the
     * host asks for its template immediately, before any cold Room flow it subscribes to
     * has emitted, so the node list rendered "No nodes yet" for as long as the first
     * emission and the host's follow-up template request took. The phone never showed it
     * because its ViewModel StateFlows were already hot and cached. Eagerly, not
     * WhileSubscribed: a car session that starts with no phone UI up must still find a
     * populated value, and these two queries are small.
     */
    val nodesWithUsers: StateFlow<List<NodeWithUser>> =
        database.nodeDao().nodesWithUsers()
            .stateIn(appScope, SharingStarted.Eagerly, emptyList())

    val mapNodes: StateFlow<List<MapNode>> =
        database.positionDao().mapNodes()
            .stateIn(appScope, SharingStarted.Eagerly, emptyList())

    /**
     * The car Messages screen's two lists, hot for exactly the reason above. It observed
     * the cold DAO flows directly and so rendered "No channels yet; connect a radio" with
     * a radio connected and messages in the store, which is the same fault the node list
     * had. Any flow a car screen needs populated in its *first* template has to be hot and
     * cached here; the phone never shows these because its ViewModels already are.
     */
    val activeChannels: StateFlow<List<ChannelEntity>> =
        database.channelDao().activeChannels()
            .stateIn(appScope, SharingStarted.Eagerly, emptyList())

    val dmContacts: StateFlow<List<UserEntity>> =
        database.userDao().dmContacts()
            .stateIn(appScope, SharingStarted.Eagerly, emptyList())

    /**
     * [PrefKeys.PIN_FAVORITES], hot for the same reason as the lists above: the car node
     * list reads it from `.value` while building its first template.
     */
    val pinFavorites: StateFlow<Boolean> =
        prefs.data.map { it[PrefKeys.PIN_FAVORITES] ?: true }
            .stateIn(appScope, SharingStarted.Eagerly, true)

    suspend fun setPinFavorites(pin: Boolean) {
        prefs.edit { it[PrefKeys.PIN_FAVORITES] = pin }
    }

    init {
        // Positions and telemetry whose node row is gone (radio switch, removed node)
        // are dropped at every launch as well as at connect, so an install that already
        // carries them is clean before the first session.
        appScope.launch {
            runCatching {
                database.positionDao().pruneOrphans()
                database.telemetryDao().pruneOrphans()
            }
        }
    }

    suspend fun rememberedRadio(): RememberedRadio? = prefs.data.first().rememberedRadio()

    suspend fun knownRadios(): List<RememberedRadio> = prefs.data.first().knownRadios()

    /**
     * Record a successful connection: the radio becomes the auto-connect target and
     * joins the saved list, so it can be picked again without a scan.
     */
    suspend fun rememberRadio(type: String, address: String, name: String?) {
        prefs.edit { prefs ->
            prefs[PrefKeys.RADIO_TYPE] = type
            prefs[PrefKeys.RADIO_ADDRESS] = address
            if (name.isNullOrBlank()) prefs.remove(PrefKeys.RADIO_NAME) else prefs[PrefKeys.RADIO_NAME] = name
            val known = prefs.knownRadios()
            // Carried over rather than rebuilt: this runs on every connect, and the owner
            // name is learned later in the session than the row is written.
            val entry = RememberedRadio(
                type = type,
                address = address,
                name = name,
                lastConnectedMs = System.currentTimeMillis(),
                ownerShort = known.firstOrNull { it.address == address }?.ownerShort,
            )
            val others = known.filterNot { it.address == address }
            prefs[PrefKeys.KNOWN_RADIOS] = encodeRadios((listOf(entry) + others).take(MAX_KNOWN_RADIOS))
        }
    }

    /**
     * Record the connected radio's short name, so the saved list can name it from the owner
     * record instead of from what it advertises. [RememberedRadio.label] rebuilds the
     * advertised form around it.
     *
     * The two are not the same string and on some hardware they never converge: the GAT562s
     * advertise `GAT562_xxxx` whatever the owner is renamed to, which the iOS client shows
     * too, so it is the firmware's doing and not something a client can argue with.
     *
     * Live, not just per connect: renaming the radio in Settings writes the users row, and
     * this follows it without needing a reconnect.
     */
    private suspend fun refreshOwnerName(nodeNum: Long) {
        val address = radioManager.currentAddress ?: return
        val short = database.userDao().get(nodeNum)?.shortName?.takeIf { it.isNotBlank() } ?: return
        prefs.edit { prefs ->
            val known = prefs.knownRadios()
            val entry = known.firstOrNull { it.address == address } ?: return@edit
            if (entry.ownerShort == short) return@edit
            val updated = entry.copy(ownerShort = short)
            Log.i(TAG, "Saved radio $address is \"${updated.label}\" (advertised \"${entry.name}\")")
            prefs[PrefKeys.KNOWN_RADIOS] =
                encodeRadios(listOf(updated) + known.filterNot { it.address == address })
        }
    }

    /**
     * Reconnect to the last radio, mirroring iOS's preferredPeripheral. Shared by
     * MainActivity (onCreate, permission result, onResume) and the Android Auto session,
     * which can start while the phone app is not on screen. RadioManager spends exactly
     * one automatic attempt per process, so a radio that is off or out of range is not
     * chased in the background; the Connect screen (phone or car) offers Reconnect.
     */
    suspend fun autoConnectIfRemembered(context: Context) {
        val target = rememberedRadio() ?: return
        val factory = connectionFactory(target) ?: return
        if (target.type == "ble" &&
            (!hasBlePermission(context) || bleScanner.adapter?.isEnabled != true)
        ) {
            return
        }
        val radio = radioManager
        if (radio.isConnected || radio.isAttempting) return
        // A car session is bound by the Android Auto host rather than started from an
        // Activity; if the OS refuses the foreground service the link still gets tried.
        runCatching { RadioService.start(context, target.label) }
        radio.autoConnect(target.label, presenceProbe(target), target.address, factory)
        if (radio.isConnected) {
            // Keeps "last used" ordering in the saved list honest.
            rememberRadio(target.type, target.address, target.name)
        }
        // The service only earns its notification while a link is up or being made.
        if (!radio.isConnected && !radio.isAttempting) RadioService.stop(context)
    }

    // MARK: - Radio switching (Connect.swift switchToDevice / backupCurrentAndRestoreDatabase)

    /** The radio whose data the store currently holds, by node number, or null when empty. */
    /**
     * The node the store's data belongs to: the my_info row, nothing else. RadioManager's
     * number outlives a clear and a failed connect, and a backup keyed on it filed an
     * empty store under the previous radio.
     */
    suspend fun currentNodeNum(): Long? = database.myInfoDao().myInfoOnce()?.myNodeNum

    /** The device id for a backup of [num]: the live radio's, and only when it is the store's owner. */
    fun currentDeviceId(num: Long): String? =
        if (radioManager.myNodeNum.value == num) radioManager.deviceId.value else null

    private suspend fun currentNodeName(num: Long): String? =
        database.userDao().get(num)?.longName
            ?: database.myInfoDao().myInfoOnce()?.bleName
            ?: radioManager.deviceName.value

    /**
     * `backupCurrentDatabase`: snapshot the current radio ahead of a store clear. Null
     * when there is nothing to protect (no store owner, or the target IS the current
     * radio); [BackupResult.Skipped] means the store holds data that could NOT be
     * snapshotted, and the caller decides whether clearing is still acceptable.
     */
    private suspend fun backupCurrentDatabase(targetNodeNum: Long?): BackupResult? {
        val current = currentNodeNum()
        return when {
            current == null -> { Log.w(TAG, "No current node num, skipping backup"); null }
            current == targetNodeNum -> { Log.i(TAG, "Skipping backup because the target is the active node"); null }
            else -> {
                val result = backups.createBackup(current, currentDeviceId(current), currentNodeName(current))
                when (result) {
                    is BackupResult.Success -> Log.i(TAG, "Backup created: ${result.entry.fileSize} bytes for node $current")
                    is BackupResult.Skipped -> Log.w(TAG, "Backup skipped: ${result.reason}")
                    BackupResult.NoBackupFound -> Unit
                }
                result
            }
        }
    }

    /** The clear-and-import tail of a switch or restore, run only once the backup gate passed. */
    private suspend fun clearAndRestore(
        context: Context,
        targetNodeNum: Long?,
        disconnectCurrentDevice: Boolean,
    ): BackupResult {
        if (disconnectCurrentDevice) {
            // A restore from Settings is a deliberate disconnect: the auto-connect target
            // goes too, or the next resume would reconnect the old radio and the foreign
            // store guard would immediately back up and clear what was just restored.
            Log.i(TAG, "Disconnecting current device before restore")
            disconnectRadio(context)
        }
        router.resetNavigation()
        withContext(Dispatchers.IO) { database.clearAllTables() }
        return if (targetNodeNum != null) backups.restoreFromBackup(targetNodeNum) else BackupResult.NoBackupFound
    }

    /**
     * `backupCurrentAndRestoreDatabase`: back the current radio up, optionally drop the
     * link, clear the whole store (always, so one radio's nodes never land on another's),
     * and import the target's snapshot when there is one. The caller connects afterwards.
     * A failed backup of the current store aborts before anything is touched unless
     * [force]; the Skipped it returns then describes the backup, not the restore.
     */
    suspend fun backupCurrentAndRestore(
        context: Context,
        targetNodeNum: Long?,
        disconnectCurrentDevice: Boolean = false,
        force: Boolean = false,
    ): BackupResult {
        val backup = backupCurrentDatabase(targetNodeNum)
        if (!force && backup is BackupResult.Skipped) {
            return BackupResult.Skipped("The current radio could not be backed up, so its data was left in place. ${backup.reason}")
        }
        return clearAndRestore(context, targetNodeNum, disconnectCurrentDevice)
    }

    /**
     * `switchToDevice`: the user picked a radio other than the one the store belongs to.
     * The backup gate runs before anything changes: a store that cannot be snapshotted is
     * not cleared, the link and the auto-connect target stay as they were (remembering the
     * target first would let the next resume finish the switch without the snapshot), and
     * the reason is returned for the caller to surface, with [force] as the explicit
     * override. On a null return the switch completed: the choice is recorded as the
     * auto-connect target (a failed connect must retry the radio the user asked for, not
     * bounce back), the link dropped, the store cleared and the target's snapshot restored.
     */
    suspend fun switchRadio(context: Context, type: String, address: String, name: String?, force: Boolean = false): String? {
        val targetNodeNum = backups.resolveNodeNum(address)
        Log.i(TAG, "Node switch: current ${currentNodeNum()} (${currentRadioAddress()}), target ${targetNodeNum ?: "unknown"} ($address)")
        val backup = backupCurrentDatabase(targetNodeNum)
        if (!force && backup is BackupResult.Skipped) {
            Log.w(TAG, "Switch to $address refused: ${backup.reason}")
            return backup.reason
        }
        rememberRadio(type, address, name)
        if (radioManager.isConnected) radioManager.disconnect()
        when (val result = clearAndRestore(context, targetNodeNum, disconnectCurrentDevice = false)) {
            is BackupResult.Success -> Log.i(TAG, "Backup restored for target node $targetNodeNum")
            is BackupResult.Skipped -> Log.w(TAG, "Restore skipped: ${result.reason}")
            BackupResult.NoBackupFound -> Log.i(TAG, "No backup for target node ${targetNodeNum ?: "unknown"}; radio will populate fresh data")
        }
        return null
    }

    /**
     * Whether connecting to [address] is a switch (a different radio than the store holds)
     * or a plain reconnect. Same saved address, or a backup that says this address is the
     * store's own node, means reconnect.
     */
    suspend fun isSwitch(address: String): Boolean {
        val current = currentNodeNum() ?: return false
        if (currentRadioAddress() == address) return false
        if (rememberedRadio()?.address == address) return false
        return backups.resolveNodeNum(address) != current
    }

    /** The address recorded in the store for the radio it holds data for. */
    private suspend fun currentRadioAddress(): String? =
        database.myInfoDao().myInfoOnce()?.radioAddress

    /**
     * Explicit reconnect to a saved radio (the car's Reconnect button and the phone's
     * failed-state retry): bypasses the one-attempt auto-connect budget. Returns the
     * backup gate's refusal reason when a required switch was aborted, null otherwise;
     * connecting anyway would just trip the foreign-store guard into the clear the gate
     * refused, so the connect is skipped too.
     */
    suspend fun connectKnown(context: Context, target: RememberedRadio, force: Boolean = false): String? {
        val factory = connectionFactory(target) ?: return null
        if (isSwitch(target.address)) {
            switchRadio(context, target.type, target.address, target.name, force)?.let { return it }
        }
        runCatching { RadioService.start(context, target.label) }
        runCatching { radioManager.connect(target.label, presenceProbe(target), target.address, factory) }
        if (radioManager.isConnected) rememberRadio(target.type, target.address, target.name)
        return null
    }

    /**
     * A deliberate Disconnect, from the phone's Connect tab or the car's Radio screen:
     * tear the link down, drop the foreground service, and stop auto-connecting on the
     * next launch. The radio stays in the saved list.
     */
    suspend fun disconnectRadio(context: Context) {
        radioManager.disconnect()
        RadioService.stop(context)
        clearAutoConnectTarget()
    }

    fun hasBlePermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /**
     * Presence check for a saved radio: BLE waits for the advertisement before touching
     * the GATT stack, TCP has nothing to scan for.
     */
    fun presenceProbe(radio: RememberedRadio): PresenceProbe? = when (radio.type) {
        "ble" -> object : PresenceProbe {
            override suspend fun isVisible(timeoutMs: Long) =
                bleScanner.isAdvertising(radio.address, timeoutMs)

            override suspend fun awaitReturn() = bleScanner.awaitReturn(radio.address)
        }
        else -> null
    }

    /** Connection builder for a remembered radio, or null for an unknown transport. */
    fun connectionFactory(radio: RememberedRadio): (() -> RadioConnection)? = when (radio.type) {
        "ble" -> ({ bleScanner.connection(radio.address) })
        "tcp" -> {
            val host = radio.address.substringBefore(':')
            val port = radio.address.substringAfter(':', "")
                .toIntOrNull() ?: MeshProtocol.DEFAULT_TCP_PORT
            ({ TcpConnection(host, port) })
        }
        else -> null
    }

    /**
     * Stop auto-connecting on launch without losing the radio from the saved list —
     * what a deliberate Disconnect means.
     */
    suspend fun clearAutoConnectTarget() {
        prefs.edit { prefs ->
            prefs.remove(PrefKeys.RADIO_TYPE)
            prefs.remove(PrefKeys.RADIO_ADDRESS)
            prefs.remove(PrefKeys.RADIO_NAME)
        }
    }

    /** Drop a radio from the saved list, and from auto-connect if it was the target. */
    suspend fun forgetRadio(address: String) {
        prefs.edit { prefs ->
            prefs[PrefKeys.KNOWN_RADIOS] =
                encodeRadios(prefs.knownRadios().filterNot { it.address == address })
            if (prefs[PrefKeys.RADIO_ADDRESS] == address) {
                prefs.remove(PrefKeys.RADIO_TYPE)
                prefs.remove(PrefKeys.RADIO_ADDRESS)
                prefs.remove(PrefKeys.RADIO_NAME)
            }
        }
    }
}

val Context.container: AppContainer
    get() = (applicationContext as MeshtasticApplication).container
