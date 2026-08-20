package com.suteny0r.mangledbabyducks.ui

import android.app.Application
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

    /** The radio the app would reconnect to, so the UI can offer it by name. */
    val remembered: StateFlow<RememberedRadio?> = container.prefs.data
        .map { it.rememberedRadio() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    /** Radios already connected to at least once: pick one instead of scanning. */
    val knownRadios: StateFlow<List<RememberedRadio>> = container.prefs.data
        .map { it.knownRadios() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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
            if (radio.isConnected) container.rememberRadio("ble", device.id, device.name)
        }
    }

    fun connectTcp(host: String, port: Int) {
        viewModelScope.launch {
            RadioService.start(getApplication(), host)
            runCatching {
                radio.connect(host) { TcpConnection(host, port) }
            }
            if (radio.isConnected) container.rememberRadio("tcp", "$host:$port", host)
        }
    }

    /** Connect to a saved radio: the no-scan path, also used by the failed-state retry. */
    fun connectKnown(target: RememberedRadio) {
        scanJob?.cancel()
        _scanning.value = false
        viewModelScope.launch {
            val factory = container.connectionFactory(target) ?: return@launch
            RadioService.start(getApplication(), target.label)
            runCatching {
                radio.connect(target.label, container.presenceProbe(target), factory)
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
        viewModelScope.launch {
            radio.disconnect()
            RadioService.stop(getApplication())
            // A deliberate disconnect stops auto-connect on the next launch, but the
            // radio stays in the saved list so it can be picked without scanning.
            container.clearAutoConnectTarget()
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

    fun writeLoraConfig(lora: ConfigProtos.Config.LoRaConfig) = writeConfig { setLora(lora) }

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

    private val _broadcastResult = MutableStateFlow<Boolean?>(null)
    val broadcastResult: StateFlow<Boolean?> = _broadcastResult.asStateFlow()

    fun broadcastNodeInfo() {
        viewModelScope.launch {
            _broadcastResult.value = container.radioManager.broadcastNodeInfo()
        }
    }
}
