package com.suteny0r.mangledbabyducks.ui

import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.suteny0r.mangledbabyducks.PrefKeys
import com.suteny0r.mangledbabyducks.RememberedRadio
import com.suteny0r.mangledbabyducks.container
import com.suteny0r.mangledbabyducks.knownRadios
import com.suteny0r.mangledbabyducks.rememberedRadio
import com.suteny0r.mangledbabyducks.db.ChannelEntity
import com.suteny0r.mangledbabyducks.db.MessageEntity
import com.suteny0r.mangledbabyducks.db.MyInfoEntity
import com.suteny0r.mangledbabyducks.db.NodeEntity
import com.suteny0r.mangledbabyducks.db.NodeWithUser
import com.suteny0r.mangledbabyducks.db.UserEntity
import com.suteny0r.mangledbabyducks.radio.ChannelCodec
import com.suteny0r.mangledbabyducks.radio.DiscoveredDevice
import com.suteny0r.mangledbabyducks.radio.LanScanner
import com.suteny0r.mangledbabyducks.radio.MeshProtocol
import com.suteny0r.mangledbabyducks.radio.RadioService
import com.suteny0r.mangledbabyducks.radio.RadioState
import com.suteny0r.mangledbabyducks.radio.TcpConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.google.protobuf.ByteString
import org.meshtastic.proto.AppOnlyProtos
import org.meshtastic.proto.ChannelProtos
import org.meshtastic.proto.ConfigProtos
import com.suteny0r.mangledbabyducks.db.MapNode
import org.meshtastic.proto.MeshProtos
import org.meshtastic.proto.ModuleConfigProtos

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    private val radio = container.radioManager

    val state: StateFlow<RadioState> = radio.state
    val deviceName: StateFlow<String?> = radio.deviceName

    /** True once this session's own MyNodeInfo has landed; see RadioManager.identityReady. */
    val identityReady: StateFlow<Boolean> = radio.identityReady

    // The connected-device box (Connect.swift) shows our own node: name, short name
    // avatar, battery, firmware and the BLE name.
    val myUser: StateFlow<UserEntity?> = radio.myNodeNum
        .flatMapLatest { container.database.userDao().userFlow(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val myInfo: StateFlow<MyInfoEntity?> = container.database.myInfoDao().myInfo()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val myNodeNum: StateFlow<Long> = radio.myNodeNum
    val linkRssi: StateFlow<Int?> = radio.linkRssi
    val myBattery: StateFlow<Int?> = radio.myNodeNum
        .flatMapLatest { container.database.telemetryDao().latestDeviceMetrics(it) }
        .map { it?.batteryLevel }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Connect.swift presetName: the radio's LoRa preset, or "Custom" when use_preset is off. */
    val loraPreset: StateFlow<String?> = container.database.configDao().config("config.lora")
        .map { entity ->
            entity?.let { runCatching { ConfigProtos.Config.parseFrom(it.bytes).lora }.getOrNull() }
                ?.let { if (!it.usePreset) "Custom" else presetLabel(it.modemPreset) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Our own node row, for the device box's Share Contact menu item. */
    val myEntry: StateFlow<NodeWithUser?> = radio.myNodeNum
        .flatMapLatest { container.database.nodeDao().nodeWithUserFlow(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    private val _devices = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
    val devices: StateFlow<Map<String, DiscoveredDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    /** The radio the app would reconnect to, so the UI can offer it by name. */
    val remembered: StateFlow<RememberedRadio?> = container.prefs.data
        .map { it.rememberedRadio() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Radios already connected to at least once: pick one instead of scanning. */
    val knownRadios: StateFlow<List<RememberedRadio>> = container.prefs.data
        .map { it.knownRadios() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var scanJob: Job? = null

    // BluetoothPoweredOffRow (Connect.swift): the adapter state, tracked live so the row
    // appears when Bluetooth goes off and clears, and the scan restarts, when it comes back.
    private val _bluetoothOff = MutableStateFlow(container.bleScanner.adapter?.isEnabled != true)
    val bluetoothOff: StateFlow<Boolean> = _bluetoothOff.asStateFlow()
    private val adapterReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            _bluetoothOff.value = container.bleScanner.adapter?.isEnabled != true
        }
    }

    init {
        app.registerReceiver(adapterReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
    }

    override fun onCleared() {
        runCatching { getApplication<Application>().unregisterReceiver(adapterReceiver) }
        super.onCleared()
    }

    // LAN discovery runs while the Connect screen is visible (iOS browses Bonjour
    // continuously on that tab); results are keyed host:port to match saved radios.
    private val _lanDevices = MutableStateFlow<Map<String, LanScanner.LanDevice>>(emptyMap())
    val lanDevices: StateFlow<Map<String, LanScanner.LanDevice>> = _lanDevices.asStateFlow()
    private val _lanScanning = MutableStateFlow(false)
    val lanScanning: StateFlow<Boolean> = _lanScanning.asStateFlow()
    private var lanJob: Job? = null

    fun startLanScan() {
        if (lanJob?.isActive == true) return
        _lanScanning.value = true
        lanJob = viewModelScope.launch {
            // Never probe the radio we are talking to over TCP: the firmware takes one
            // client, and the sweep's handshake would steal that slot.
            val live = remembered.value?.takeIf { it.type == "tcp" && radio.isConnected }
                ?.address?.substringBefore(':')
            try {
                container.lanScanner.discover(exclude = setOfNotNull(live)).collect { _lanDevices.value = it }
            } catch (_: Exception) {
            } finally {
                _lanScanning.value = false
            }
        }
    }

    fun stopLanScan() {
        lanJob?.cancel()
        lanJob = null
        _lanScanning.value = false
    }

    fun connectLan(device: LanScanner.LanDevice) {
        connectTcp(device.host, device.port)
    }

    /** iOS scans for the whole time the Connect tab is up and no radio is linked. */
    fun startScan() {
        if (scanJob?.isActive == true) return
        _devices.value = emptyMap()
        _scanning.value = true
        scanJob = viewModelScope.launch {
            try {
                container.bleScanner.scan().collect { device ->
                    _devices.value = _devices.value + (device.id to device)
                }
            } catch (_: Exception) {
            } finally {
                _scanning.value = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
    }

    /** ManualConnectionMenu: "hostname[:port]" typed by the user. */
    fun connectManual(connectionString: String) {
        val parts = connectionString.trim().split(":")
        val host = parts[0].ifBlank { return }
        connectTcp(host, parts.getOrNull(1)?.toIntOrNull() ?: MeshProtocol.DEFAULT_TCP_PORT)
    }

    /** Connect box context menu "Power Off": shuts down the linked radio. */
    fun shutdownConnectedRadio() {
        val num = radio.myNodeNum.value
        if (num == 0L) return
        viewModelScope.launch { runCatching { radio.sendNodeShutdown(num) } }
    }

    fun connectBle(device: DiscoveredDevice) {
        scanJob?.cancel()
        _scanning.value = false
        viewModelScope.launch {
            // Connect.swift performRadioSwitch: a different radio than the store holds is
            // backed up, cleared and restored before the link is made.
            if (container.isSwitch(device.id)) {
                container.switchRadio(getApplication(), "ble", device.id, device.name)
            }
            RadioService.start(getApplication(), device.name)
            runCatching {
                radio.connect(device.name, address = device.id) { container.bleScanner.connection(device.id) }
            }
            if (radio.isConnected) container.rememberRadio("ble", device.id, device.name)
        }
    }

    fun connectTcp(host: String, port: Int) {
        viewModelScope.launch {
            val address = "$host:$port"
            if (container.isSwitch(address)) {
                container.switchRadio(getApplication(), "tcp", address, host)
            }
            RadioService.start(getApplication(), host)
            runCatching {
                radio.connect(host, address = address) { TcpConnection(host, port) }
            }
            if (radio.isConnected) container.rememberRadio("tcp", address, host)
        }
    }

    /** Connect to a saved radio: the no-scan path, also used by the failed-state retry. */
    /**
     * Connect to whatever the tapped row identifies, resolved against live state at the
     * moment of the tap: a saved radio's address, a scanned BLE address, or a LAN host.
     * The Connect list passes this key instead of a captured device, so a list that
     * reshuffles between the press and the release cannot redirect the connect.
     */
    fun connectByKey(key: String) {
        knownRadios.value.find { it.address == key }?.let {
            Log.i("ConnectViewModel", "connect requested: saved ${it.label} ($key)")
            return connectKnown(it)
        }
        devices.value[key]?.let {
            Log.i("ConnectViewModel", "connect requested: scanned ${it.name} ($key)")
            return connectBle(it)
        }
        lanDevices.value[key]?.let {
            Log.i("ConnectViewModel", "connect requested: lan ${it.name} ($key)")
            return connectLan(it)
        }
        Log.w("ConnectViewModel", "connect requested for unknown radio $key")
    }

    fun connectKnown(saved: RememberedRadio) {
        scanJob?.cancel()
        _scanning.value = false
        // A radio seen by the scan advertises its current name; the saved entry keeps the
        // one from the first connect otherwise, which is how a renamed radio went stale.
        val live = devices.value[saved.address]?.name?.takeIf { it.isNotBlank() }
        val target = if (live != null && live != saved.name) saved.copy(name = live) else saved
        viewModelScope.launch {
            val factory = container.connectionFactory(target) ?: return@launch
            if (container.isSwitch(target.address)) {
                container.switchRadio(getApplication(), target.type, target.address, target.name)
            }
            RadioService.start(getApplication(), target.label)
            runCatching {
                radio.connect(target.label, container.presenceProbe(target), target.address, factory)
            }
            if (radio.isConnected) {
                container.rememberRadio(target.type, target.address, target.name)
            }
        }
    }

    /** Drop a saved radio entirely (and disconnect first if it is the live one). */
    fun forget(target: RememberedRadio) {
        viewModelScope.launch {
            if (radio.isConnected && radio.deviceName.value == target.label) {
                radio.disconnect()
                RadioService.stop(getApplication())
            }
            container.forgetRadio(target.address)
        }
    }

    fun disconnect() {
        // A deliberate disconnect stops auto-connect on the next launch, but the
        // radio stays in the saved list so it can be picked without scanning.
        viewModelScope.launch { container.disconnectRadio(getApplication()) }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class NodesViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container

    /** Mirrors the iOS node list: ignored nodes are hidden unless shown, favorites can be isolated. */
    val showIgnored = MutableStateFlow(false)
    val favoritesOnly = MutableStateFlow(false)
    /** NodeFilterParameters.isSigned: only nodes whose NodeInfo signature the radio verified. */
    val signedOnly = MutableStateFlow(false)
    val searchText = MutableStateFlow("")

    // iOS NodeList display order: connected node first, then favorites,
    // then most-recently-heard.
    val nodes: StateFlow<List<NodeWithUser>> = combine(
        combine(showIgnored, favoritesOnly, signedOnly) { a, b, c -> Triple(a, b, c) },
        searchText, container.radioManager.myNodeNum,
        container.database.nodeDao().nodesWithUsers()
    ) { (showIgnored, favoritesOnly, signedOnly), search, myNum, list ->
        val needle = search.trim().lowercase()
        list.asSequence()
            .filter { (showIgnored || !it.node.ignored) && (!favoritesOnly || it.node.favorite) }
            .filter { !signedOnly || it.node.hasXeddsaSigned }
            .filter {
                needle.isEmpty() || nodeMatches(it, needle)
            }
            .sortedWith(
                compareByDescending<NodeWithUser> { it.node.num == myNum }
                    .thenByDescending { it.node.favorite }
                    .thenByDescending { it.node.lastHeard ?: 0L }
            )
            .toList()
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private fun nodeMatches(entry: NodeWithUser, needle: String): Boolean {
        val user = entry.user
        return listOfNotNull(
            user?.userId,
            user?.longName,
            user?.shortName,
            user?.hwModel,
            "!%08x".format(entry.node.num),
        ).any { it?.trim()?.lowercase()?.contains(needle) == true }
    }

    val myNodeNum: StateFlow<Long> = container.radioManager.myNodeNum

    /** Latest position per node (NodeListItem's latestNodeCoordinate). */
    val positionByNode: StateFlow<Map<Long, MapNode>> = container.database.positionDao().mapNodes()
        .map { list -> list.associateBy { it.nodeNum } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * Where we are, for distance and bearing: the connected radio's own position once it
     * has one (that is the node whose distances these are), and the phone's fix until
     * then. Radios without a GPS, or still waiting for a lock, would otherwise leave the
     * lists without a reference point; the switch happens on its own when the radio's
     * first position lands.
     */
    val myLocation: StateFlow<Pair<Double, Double>?> = combine(
        container.locationSharer.lastFix,
        positionByNode,
        myNodeNum,
    ) { fix, positions, me ->
        positions[me]?.let { it.latitude to it.longitude }
            ?: fix?.let { it.latitudeI / 1e7 to it.longitudeI / 1e7 }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Latest battery % per node for the list rows (empty for nodes without telemetry). */
    val batteryByNode: StateFlow<Map<Long, Int>> = nodes
        .flatMapLatest { items ->
            if (items.isEmpty()) flowOf<Map<Long, Int>>(emptyMap())
            else container.database.telemetryDao()
                .batteryByNums(items.map { it.node.num })
                .map { rows ->
                    // Rows are time-desc; the first per node is its latest reading.
                    rows.groupBy { it.nodeNum }.mapValues { (_, list) -> list.first().batteryLevel ?: 0 }
                }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    fun toggleFavorite(num: Long, favorite: Boolean) {
        viewModelScope.launch {
            container.radioManager.setFavorite(num, favorite)
            container.database.nodeDao().setFavorite(num, favorite)
        }
    }

    fun toggleIgnored(num: Long, ignored: Boolean) {
        viewModelScope.launch {
            container.radioManager.setIgnored(num, ignored)
            container.database.nodeDao().setIgnored(num, ignored)
        }
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MessagesViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    private val db = container.database

    val channels: StateFlow<List<ChannelEntity>> = db.channelDao().activeChannels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val unreadChannels: StateFlow<Int> = db.messageDao().unreadChannelCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val unreadDirect: StateFlow<Int> = db.messageDao().unreadDirectCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val dmContacts: StateFlow<List<UserEntity>> = db.userDao().dmContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** UserList.swift .searchable("Find a contact"); matches the fields its filter does. */
    val contactSearch = MutableStateFlow("")

    /**
     * Every contact a DM could be sent to: the whole node DB minus ignored nodes and our
     * own radio, which iOS drops with `users.filter { $0.num != activeDeviceNum }`.
     */
    val contacts: StateFlow<List<UserEntity>> = combine(
        db.userDao().allContacts(), container.radioManager.myNodeNum, contactSearch,
    ) { users, me, query ->
        val needle = query.trim().lowercase()
        users.asSequence()
            .filter { it.num != me }
            .filter { user ->
                needle.isEmpty() || sequenceOf(
                    user.longName, user.shortName, user.userId,
                    user.hwModel, user.hwDisplayName, user.num.toString(),
                ).any { it?.lowercase()?.contains(needle) == true }
            }
            .toList()
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val myNodeNum: StateFlow<Long> = container.radioManager.myNodeNum

    /** Latest position per node, for the distance under each sender's avatar. */
    val positionByNode: StateFlow<Map<Long, MapNode>> = container.database.positionDao().mapNodes()
        .map { list -> list.associateBy { it.nodeNum } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Same rule as NodesViewModel.myLocation: the radio's own fix when it has one, else the phone's. */
    val myLocation: StateFlow<Pair<Double, Double>?> = combine(
        container.locationSharer.lastFix,
        positionByNode,
        myNodeNum,
    ) { fix, positions, me ->
        positions[me]?.let { it.latitude to it.longitude }
            ?: fix?.let { it.latitudeI / 1e7 to it.longitudeI / 1e7 }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Row previews: newest message per channel and per DM peer, unread flags per thread. */
    val channelPreviews: StateFlow<Map<Int, MessageEntity>> = db.messageDao().channelPreviews()
        .map { list -> list.associateBy { it.channel } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val dmPreviews: StateFlow<Map<Long, MessageEntity>> = combine(
        db.messageDao().dmPreviews(), container.radioManager.myNodeNum,
    ) { list, me ->
        list.associateBy { if (it.fromNum == me) (it.toNum ?: it.fromNum) else it.fromNum }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())
    val unreadChannelSet: StateFlow<Set<Int>> = db.messageDao().unreadChannelIndexes()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())
    val unreadDmSet: StateFlow<Set<Long>> = db.messageDao().unreadDmPeers()
        .map { it.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    fun channelMessages(channel: Int) = db.messageDao().channelMessages(channel)

    /**
     * Keyed on our own node number as it changes, not on its value when the thread opened:
     * a thread opened while the radio was down sampled 0 and stayed empty for good, even
     * after the handshake supplied the real number.
     */
    fun directMessages(peer: Long) = container.radioManager.myNodeNum
        .flatMapLatest { db.messageDao().directMessages(it, peer) }

    fun channelTapbacks(channel: Int) = db.messageDao().channelTapbacks(channel)

    fun directTapbacks(peer: Long) = container.radioManager.myNodeNum
        .flatMapLatest { db.messageDao().directTapbacks(it, peer) }

    fun sendToChannel(text: String, channel: Int, replyId: Long = 0, isEmoji: Boolean = false, sharePosition: Boolean = false) {
        viewModelScope.launch {
            val ok = container.radioManager.sendTextMessage(
                text, channel = channel, replyId = replyId, isEmoji = isEmoji,
            )
            // TextMessageField.sendMessage: the position follows the text, to the whole
            // channel, without asking anyone to answer.
            if (ok && sharePosition) sendOurPosition(MeshProtocol.BROADCAST_NUM, channel, wantResponse = false)
        }
    }

    fun sendDirect(text: String, toNum: Long, replyId: Long = 0, isEmoji: Boolean = false, sharePosition: Boolean = false) {
        viewModelScope.launch {
            val ok = container.radioManager.sendTextMessage(
                text, toNum = toNum, replyId = replyId, isEmoji = isEmoji,
            )
            // A direct share also asks the peer for its position back.
            if (ok && sharePosition) sendOurPosition(toNum, 0, wantResponse = true)
        }
    }

    /** Our position as the lists define it: the radio's own fix when present, else the phone's. */
    private suspend fun sendOurPosition(toNum: Long, channel: Int, wantResponse: Boolean) {
        val here = myLocation.value ?: return
        val altitude = container.locationSharer.lastFix.value?.altitude ?: 0
        container.radioManager.sendDestPosition(
            toNum = toNum,
            latitudeI = (here.first * 1e7).toInt(),
            longitudeI = (here.second * 1e7).toInt(),
            altitude = altitude,
            channel = channel,
            wantResponse = wantResponse,
        )
    }

    /** Long name per node for resolving `@!hex` mentions and the position-share sentence. */
    val namesByNum: StateFlow<Map<Long, String>> = db.nodeDao().nodesWithUsers()
        .map { list ->
            list.mapNotNull { e ->
                val name = e.user?.longName?.takeIf { it.isNotBlank() } ?: e.user?.shortName?.takeIf { it.isNotBlank() }
                name?.let { e.node.num to it }
            }.toMap()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /** Every known user, for the @mention autocomplete (MentionAutocomplete queries all nodes). */
    val allUsers: StateFlow<List<UserEntity>> = db.userDao().allContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun markChannelRead(channel: Int) {
        viewModelScope.launch {
            db.messageDao().markChannelRead(channel)
            // The conversation notification (also what Android Auto reads out) is stale now.
            container.messageNotifier.dismiss(ThreadTarget.Channel(channel, ""))
        }
    }

    /** Local purge only; nothing is sent to the radio or the mesh (UpdateSwiftData.swift). */
    fun deleteChannelMessages(channel: Int) {
        viewModelScope.launch { db.messageDao().deleteChannelMessages(channel) }
    }

    fun deleteDirectMessages(peer: Long) {
        viewModelScope.launch { db.messageDao().deleteDirectMessages(peer) }
    }

    fun deleteMessage(messageId: Long) {
        viewModelScope.launch { db.messageDao().delete(messageId) }
    }

    /** RetryButton.swift: drop the stuck row and send the same text again as a new packet. */
    fun retry(message: MessageEntity) {
        viewModelScope.launch {
            val text = message.payload ?: return@launch
            db.messageDao().delete(message.messageId)
            val to = message.toNum
            if (to == null) {
                container.radioManager.sendTextMessage(
                    text, channel = message.channel, replyId = message.replyId, isEmoji = message.isEmoji,
                )
            } else {
                container.radioManager.sendTextMessage(
                    text, toNum = to, replyId = message.replyId, isEmoji = message.isEmoji,
                )
            }
        }
    }

    fun markDmRead(peer: Long) {
        viewModelScope.launch {
            // Same trap as directMessages: opening a thread before the handshake would
            // mark node 0's rows read and leave the badge up.
            db.messageDao().markDmRead(container.radioManager.myNodeNum.first { it != 0L }, peer)
            container.messageNotifier.dismiss(ThreadTarget.Direct(peer, ""))
        }
    }

    suspend fun userFor(num: Long): UserEntity? = db.userDao().get(num)

    suspend fun nodeFor(num: Long): NodeEntity? = db.nodeDao().get(num)

    /**
     * MessageEntityExtension.relayDisplay(): `relayNode` carries only the low byte of the
     * relaying node's number, so every known user whose number ends in that byte is a
     * candidate. One candidate names it outright; several are ranked by fewest hops away,
     * with nodes of unknown hops unrankable and only used as a fallback; none at all
     * leaves the hex byte.
     */
    suspend fun relayDisplay(message: MessageEntity): String? {
        if (message.relayNode == 0L) return null
        val suffix = message.relayNode and 0xFF
        val hexFallback = "Node 0x%02X".format(suffix)
        val matching = db.userDao().all().filter { (it.num and 0xFF) == suffix }
        if (matching.size == 1) {
            return matching[0].longName?.takeIf { it.isNotEmpty() } ?: hexFallback
        }
        val rankable = matching.mapNotNull { user ->
            db.nodeDao().get(user.num)?.hopsAway?.takeIf { it >= 0 }?.let { user to it }
        }
        val closest = rankable.minByOrNull { it.second }?.first ?: matching.firstOrNull()
        return closest?.longName?.takeIf { it.isNotEmpty() } ?: hexFallback
    }

    /**
     * The message rows push NodeDetail (ChannelMessageRow's NavigationLink on the avatar),
     * which needs the same favorite / ignore actions NodesScreen passes it. The current
     * value is read from the row rather than hoisted, since the thread has no node list.
     */
    fun toggleFavorite(num: Long) {
        viewModelScope.launch {
            val next = !(db.nodeDao().get(num)?.favorite ?: false)
            container.radioManager.setFavorite(num, next)
            db.nodeDao().setFavorite(num, next)
        }
    }

    fun toggleIgnored(num: Long) {
        viewModelScope.launch {
            val next = !(db.nodeDao().get(num)?.ignored ?: false)
            container.radioManager.setIgnored(num, next)
            db.nodeDao().setIgnored(num, next)
        }
    }

    fun openThread(target: ThreadTarget) {
        container.router.openThread(target)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container

    val myInfo: StateFlow<MyInfoEntity?> = container.database.myInfoDao().myInfo()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val state: StateFlow<RadioState> = container.radioManager.state
    val nodeCount: StateFlow<Int> = container.database.nodeDao().count()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    /**
     * One config section, parsed from the raw proto bytes the radio sent. Sections the
     * radio has not reported yet stay null, which is what the UI shows as "waiting".
     */
    private fun <T> configFlow(key: String, extract: (ConfigProtos.Config) -> T): StateFlow<T?> =
        container.database.configDao().config(key)
            .map { entity ->
                entity?.let { runCatching { extract(ConfigProtos.Config.parseFrom(it.bytes)) }.getOrNull() }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val loraConfig: StateFlow<ConfigProtos.Config.LoRaConfig?> =
        configFlow("config.lora") { it.lora }

    /** The connected radio's DeviceMetadata; null until a handshake has delivered one. */
    val deviceMetadata: StateFlow<MeshProtos.DeviceMetadata?> = container.radioManager.deviceMetadata

    /** Our location for Set Fixed Position: the radio's own fix, else the phone's. */
    private suspend fun ourLocation(): Triple<Int, Int, Int>? {
        val me = container.radioManager.myNodeNum.value
        container.database.positionDao().mapNodes().first().firstOrNull { it.nodeNum == me }?.let {
            return Triple(it.latitudeI, it.longitudeI, 0)
        }
        return container.locationSharer.lastFix.value?.let { Triple(it.latitudeI, it.longitudeI, it.altitude) }
    }

    /** One-shot outcome of an admin action on this screen, shown and cleared by the form. */
    val adminResult = MutableStateFlow<String?>(null)

    /** FixedPositionRow.send: the toggle has already moved; a failure puts it back. */
    fun setFixedPosition(enable: Boolean, onFailure: () -> Unit) {
        viewModelScope.launch {
            val ok = if (enable) {
                val here = ourLocation()
                if (here == null) {
                    adminResult.value = "No position to send: neither the radio nor the phone has a fix."
                    false
                } else container.radioManager.setFixedPosition(here.first, here.second, here.third)
            } else {
                container.radioManager.removeFixedPosition()
            }
            if (!ok) {
                if (adminResult.value == null) adminResult.value = "Fixed position change failed."
                onFailure()
            }
        }
    }

    /** DeviceResetSection.reset: the radio forgets its nodes; this app clears its copy too. */
    fun resetNodeDb() {
        viewModelScope.launch {
            val ok = container.radioManager.sendNodeDbReset()
            adminResult.value = if (ok) "Node database reset sent; the radio is clearing its nodes." else "Node database reset failed."
        }
    }

    /** DeviceResetSection.factoryReset. */
    fun factoryReset(resetDevice: Boolean) {
        viewModelScope.launch {
            val ok = container.radioManager.sendFactoryReset(resetDevice)
            adminResult.value = if (ok) "Factory reset sent; the radio will reboot." else "Factory reset failed."
        }
    }

    /** The firmware's region -> presets map (2.8+), decoded; empty when the radio sent none. */
    val regionPresets: StateFlow<Map<ConfigProtos.Config.LoRaConfig.RegionCode, RegionPresetInfo>> =
        container.radioManager.regionPresets
            .map { it?.decoded() ?: emptyMap() }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    /**
     * The primary channel's name, which is what the firmware hashes into a frequency slot
     * when channel_num is 0. Blank means the preset's own name is hashed instead; see
     * LoRaChannelCalculator.hashName.
     */
    val primaryChannelName: StateFlow<String?> = container.database.channelDao().activeChannels()
        .map { channels ->
            channels.firstOrNull { it.index == 0 || it.role == 1 }?.name?.takeIf { it.isNotEmpty() }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)
    val deviceConfig: StateFlow<ConfigProtos.Config.DeviceConfig?> =
        configFlow("config.device") { it.device }
    val bluetoothConfig: StateFlow<ConfigProtos.Config.BluetoothConfig?> =
        configFlow("config.bluetooth") { it.bluetooth }
    val displayConfig: StateFlow<ConfigProtos.Config.DisplayConfig?> =
        configFlow("config.display") { it.display }
    val networkConfig: StateFlow<ConfigProtos.Config.NetworkConfig?> =
        configFlow("config.network") { it.network }
    val positionConfig: StateFlow<ConfigProtos.Config.PositionConfig?> =
        configFlow("config.position") { it.position }
    val powerConfig: StateFlow<ConfigProtos.Config.PowerConfig?> =
        configFlow("config.power") { it.power }
    val securityConfig: StateFlow<ConfigProtos.Config.SecurityConfig?> =
        configFlow("config.security") { it.security }

    /** Same idea as [configFlow], keyed off the module.<case> rows PacketIngest.moduleConfig writes. */
    private fun <T> moduleConfigFlow(key: String, extract: (ModuleConfigProtos.ModuleConfig) -> T): StateFlow<T?> =
        container.database.configDao().config(key)
            .map { entity ->
                entity?.let { runCatching { extract(ModuleConfigProtos.ModuleConfig.parseFrom(it.bytes)) }.getOrNull() }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val externalNotificationConfig: StateFlow<ModuleConfigProtos.ModuleConfig.ExternalNotificationConfig?> =
        moduleConfigFlow("module.external_notification") { it.externalNotification }

    val myUser: StateFlow<UserEntity?> = container.radioManager.myNodeNum
        .flatMapLatest { num ->
            if (num == 0L) flowOf(null) else container.database.userDao().userFlow(num)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun setOwner(longName: String, shortName: String) {
        viewModelScope.launch { container.radioManager.setOwner(longName, shortName) }
    }

    val shareLocation: StateFlow<Boolean> = container.prefs.data
        .map { it[PrefKeys.SHARE_LOCATION] ?: false }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    fun setShareLocation(enabled: Boolean) {
        viewModelScope.launch {
            container.prefs.edit { it[PrefKeys.SHARE_LOCATION] = enabled }
        }
    }

    /** Build the meshtastic.org share URL for the radio's current channels + LoRa config. */
    suspend fun channelExportUrl(): String? {
        val channels = container.database.channelDao().activeChannels().first()
        if (channels.isEmpty()) return null
        return ChannelCodec.toUrl(channels, loraConfig.value)
    }

    fun parseChannelUrl(url: String): AppOnlyProtos.ChannelSet? = ChannelCodec.fromUrl(url)

    private val _applyResult = MutableStateFlow<Boolean?>(null)
    val applyResult: StateFlow<Boolean?> = _applyResult.asStateFlow()

    fun applyChannelSet(set: AppOnlyProtos.ChannelSet) {
        viewModelScope.launch {
            _applyResult.value = container.radioManager.applyChannelSet(set)
        }
    }

    private val _writeResult = MutableStateFlow<Boolean?>(null)

    /** Result of the last config write, or null while one is in flight / none has run. */
    val writeResult: StateFlow<Boolean?> = _writeResult.asStateFlow()

    fun clearWriteResult() {
        _writeResult.value = null
    }

    /**
     * Write one config section; the radio saves and usually reboots, so every screen
     * batches a whole section into a single write rather than one write per field.
     */
    private fun writeConfig(build: ConfigProtos.Config.Builder.() -> Unit) {
        viewModelScope.launch {
            _writeResult.value = null
            _writeResult.value = container.radioManager.setConfig(
                ConfigProtos.Config.newBuilder().apply(build).build()
            )
        }
    }

    /** Channels.swift's list, disabled slots included so the editor knows what is free. */
    val channels: StateFlow<List<ChannelEntity>> = container.database.channelDao().allChannels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /**
     * Channels.swift Save: one setChannel admin, then the local row follows. A channel
     * saved as DISABLED is deleted locally, as iOS deletes the entity and its messages.
     */
    fun saveChannel(
        index: Int,
        name: String,
        psk: ByteArray,
        role: Int,
        uplink: Boolean,
        downlink: Boolean,
        positionPrecision: Int,
    ) {
        viewModelScope.launch {
            _writeResult.value = null
            val settings = ChannelProtos.ChannelSettings.newBuilder()
                .setName(name)
                .setPsk(ByteString.copyFrom(psk))
                .setUplinkEnabled(uplink)
                .setDownlinkEnabled(downlink)
                .setModuleSettings(
                    ChannelProtos.ModuleSettings.newBuilder()
                        .setPositionPrecision(positionPrecision)
                        .build()
                )
                .build()
            val channel = ChannelProtos.Channel.newBuilder()
                .setIndex(index)
                .setRole(ChannelProtos.Channel.Role.forNumber(role) ?: ChannelProtos.Channel.Role.SECONDARY)
                .setSettings(settings)
                .build()
            val ok = container.radioManager.saveChannel(channel)
            if (ok) {
                val db = container.database.channelDao()
                if (role == 0) {
                    db.delete(index)
                    container.database.messageDao().deleteChannelMessages(index)
                } else {
                    db.upsert(
                        ChannelEntity(
                            index = index,
                            name = name,
                            role = role,
                            psk = psk,
                            positionPrecision = positionPrecision,
                            mute = container.database.channelDao().get(index)?.mute ?: false,
                            uplinkEnabled = uplink,
                            downlinkEnabled = downlink,
                        )
                    )
                }
            }
            _writeResult.value = ok
        }
    }

    /** generateChannelKey(size:): the editor's dice button. */
    fun generateChannelKey(size: Int): ByteArray = when {
        size <= 0 -> ByteArray(0)
        else -> ByteArray(size).also { java.security.SecureRandom().nextBytes(it) }
    }

    /**
     * LoRaConfig.save: on 2.8 the change applies without a reboot, so nothing re-reads the
     * config on its own; ask the radio for it, and for its node database, afterwards.
     */
    fun writeLoraConfig(lora: ConfigProtos.Config.LoRaConfig) {
        viewModelScope.launch {
            _writeResult.value = null
            val ok = container.radioManager.setConfig(ConfigProtos.Config.newBuilder().setLora(lora).build())
            _writeResult.value = ok
            if (ok && container.radioManager.appliesLoRaConfigWithoutReboot) container.radioManager.refreshAfterLoRaChange()
        }
    }

    /** Whether a LoRa save will keep the link up (firmware 2.8+); drives the form's wording. */
    val loraSavesWithoutReboot: Boolean get() = container.radioManager.appliesLoRaConfigWithoutReboot

    fun writeDeviceConfig(device: ConfigProtos.Config.DeviceConfig) =
        writeConfig { setDevice(device) }

    fun writeBluetoothConfig(bluetooth: ConfigProtos.Config.BluetoothConfig) =
        writeConfig { setBluetooth(bluetooth) }

    fun writeDisplayConfig(display: ConfigProtos.Config.DisplayConfig) =
        writeConfig { setDisplay(display) }

    fun writeNetworkConfig(network: ConfigProtos.Config.NetworkConfig) =
        writeConfig { setNetwork(network) }

    fun writePositionConfig(position: ConfigProtos.Config.PositionConfig) =
        writeConfig { setPosition(position) }

    fun writePowerConfig(power: ConfigProtos.Config.PowerConfig) = writeConfig { setPower(power) }

    fun writeSecurityConfig(security: ConfigProtos.Config.SecurityConfig) =
        writeConfig { setSecurity(security) }

    /**
     * saveExternalNotificationModuleConfig.swift: a standalone set_module_config admin write
     * (RadioManager.setModuleConfig), sharing the same writeResult line the LocalConfig forms use.
     */
    fun writeExternalNotificationConfig(config: ModuleConfigProtos.ModuleConfig.ExternalNotificationConfig) {
        viewModelScope.launch {
            _writeResult.value = null
            _writeResult.value = container.radioManager.setModuleConfig(
                ModuleConfigProtos.ModuleConfig.newBuilder().setExternalNotification(config).build()
            )
        }
    }

    private val _broadcastResult = MutableStateFlow<Boolean?>(null)
    val broadcastResult: StateFlow<Boolean?> = _broadcastResult.asStateFlow()

    fun broadcastNodeInfo() {
        viewModelScope.launch {
            _broadcastResult.value = container.radioManager.broadcastNodeInfo()
        }
    }

    /** Dismiss the sent/failed alert so a second broadcast can raise it again. */
    fun clearBroadcastResult() { _broadcastResult.value = null }
}
