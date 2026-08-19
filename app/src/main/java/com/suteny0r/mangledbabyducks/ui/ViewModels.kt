package com.suteny0r.mangledbabyducks.ui

import android.app.Application
import androidx.datastore.preferences.core.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.suteny0r.mangledbabyducks.PrefKeys
import com.suteny0r.mangledbabyducks.container
import com.suteny0r.mangledbabyducks.db.ChannelEntity
import com.suteny0r.mangledbabyducks.db.MessageEntity
import com.suteny0r.mangledbabyducks.db.MyInfoEntity
import com.suteny0r.mangledbabyducks.db.NodeWithUser
import com.suteny0r.mangledbabyducks.db.UserEntity
import com.suteny0r.mangledbabyducks.radio.ChannelCodec
import com.suteny0r.mangledbabyducks.radio.DiscoveredDevice
import com.suteny0r.mangledbabyducks.radio.RadioService
import com.suteny0r.mangledbabyducks.radio.RadioState
import com.suteny0r.mangledbabyducks.radio.TcpConnection
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.meshtastic.proto.AppOnlyProtos
import org.meshtastic.proto.ConfigProtos

class ConnectViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    private val radio = container.radioManager

    val state: StateFlow<RadioState> = radio.state
    val deviceName: StateFlow<String?> = radio.deviceName

    private val _devices = MutableStateFlow<Map<String, DiscoveredDevice>>(emptyMap())
    val devices: StateFlow<Map<String, DiscoveredDevice>> = _devices.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private var scanJob: Job? = null

    fun toggleScan() {
        if (_scanning.value) {
            scanJob?.cancel()
            _scanning.value = false
            return
        }
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

    fun connectBle(device: DiscoveredDevice) {
        scanJob?.cancel()
        _scanning.value = false
        viewModelScope.launch {
            RadioService.start(getApplication(), device.name)
            runCatching {
                radio.connect(device.name) { container.bleScanner.connection(device.id) }
            }
            if (radio.isConnected) rememberRadio("ble", device.id, device.name)
        }
    }

    fun connectTcp(host: String, port: Int) {
        viewModelScope.launch {
            RadioService.start(getApplication(), host)
            runCatching {
                radio.connect(host) { TcpConnection(host, port) }
            }
            if (radio.isConnected) rememberRadio("tcp", "$host:$port", host)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            radio.disconnect()
            RadioService.stop(getApplication())
            // A deliberate disconnect also forgets the radio so the app stops
            // reconnecting to it on launch.
            container.prefs.edit { prefs ->
                prefs.remove(PrefKeys.RADIO_TYPE)
                prefs.remove(PrefKeys.RADIO_ADDRESS)
                prefs.remove(PrefKeys.RADIO_NAME)
            }
        }
    }

    private suspend fun rememberRadio(type: String, address: String, name: String?) {
        container.prefs.edit { prefs ->
            prefs[PrefKeys.RADIO_TYPE] = type
            prefs[PrefKeys.RADIO_ADDRESS] = address
            name?.let { prefs[PrefKeys.RADIO_NAME] = it }
        }
    }
}

class NodesViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container

    val nodes: StateFlow<List<NodeWithUser>> = container.database.nodeDao().nodesWithUsers()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val myNodeNum: StateFlow<Long> = container.radioManager.myNodeNum

    fun toggleFavorite(num: Long, favorite: Boolean) {
        viewModelScope.launch { container.database.nodeDao().setFavorite(num, favorite) }
    }
}

class MessagesViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container
    private val db = container.database

    val channels: StateFlow<List<ChannelEntity>> = db.channelDao().activeChannels()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val dmContacts: StateFlow<List<UserEntity>> = db.userDao().dmContacts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val myNodeNum: StateFlow<Long> = container.radioManager.myNodeNum

    fun channelMessages(channel: Int) = db.messageDao().channelMessages(channel)

    fun directMessages(peer: Long) =
        db.messageDao().directMessages(container.radioManager.myNodeNum.value, peer)

    fun channelTapbacks(channel: Int) = db.messageDao().channelTapbacks(channel)

    fun directTapbacks(peer: Long) =
        db.messageDao().directTapbacks(container.radioManager.myNodeNum.value, peer)

    fun sendToChannel(text: String, channel: Int, replyId: Long = 0, isEmoji: Boolean = false) {
        viewModelScope.launch {
            container.radioManager.sendTextMessage(
                text, channel = channel, replyId = replyId, isEmoji = isEmoji,
            )
        }
    }

    fun sendDirect(text: String, toNum: Long, replyId: Long = 0, isEmoji: Boolean = false) {
        viewModelScope.launch {
            container.radioManager.sendTextMessage(
                text, toNum = toNum, replyId = replyId, isEmoji = isEmoji,
            )
        }
    }

    fun markChannelRead(channel: Int) {
        viewModelScope.launch { db.messageDao().markChannelRead(channel) }
    }

    fun markDmRead(peer: Long) {
        viewModelScope.launch {
            db.messageDao().markDmRead(container.radioManager.myNodeNum.value, peer)
        }
    }

    suspend fun userFor(num: Long): UserEntity? = db.userDao().get(num)
}

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container

    val myInfo: StateFlow<MyInfoEntity?> = container.database.myInfoDao().myInfo()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val state: StateFlow<RadioState> = container.radioManager.state
    val nodeCount: StateFlow<Int> = container.database.nodeDao().count()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

    val loraConfig: StateFlow<ConfigProtos.Config.LoRaConfig?> =
        container.database.configDao().config("config.lora")
            .map { entity ->
                entity?.let { runCatching { ConfigProtos.Config.parseFrom(it.bytes).lora }.getOrNull() }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val deviceConfig: StateFlow<ConfigProtos.Config.DeviceConfig?> =
        container.database.configDao().config("config.device")
            .map { entity ->
                entity?.let { runCatching { ConfigProtos.Config.parseFrom(it.bytes).device }.getOrNull() }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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

    /** Write an edited LoRa config; the radio saves and usually reboots. */
    fun writeLoraConfig(lora: ConfigProtos.Config.LoRaConfig) {
        viewModelScope.launch {
            container.radioManager.setConfig(
                ConfigProtos.Config.newBuilder().setLora(lora).build()
            )
        }
    }

    fun writeDeviceConfig(device: ConfigProtos.Config.DeviceConfig) {
        viewModelScope.launch {
            container.radioManager.setConfig(
                ConfigProtos.Config.newBuilder().setDevice(device).build()
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
}
