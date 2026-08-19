package com.suteny0r.meshtastic.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.suteny0r.meshtastic.container
import com.suteny0r.meshtastic.db.ChannelEntity
import com.suteny0r.meshtastic.db.MessageEntity
import com.suteny0r.meshtastic.db.MyInfoEntity
import com.suteny0r.meshtastic.db.NodeWithUser
import com.suteny0r.meshtastic.db.UserEntity
import com.suteny0r.meshtastic.radio.DiscoveredDevice
import com.suteny0r.meshtastic.radio.RadioService
import com.suteny0r.meshtastic.radio.RadioState
import com.suteny0r.meshtastic.radio.TcpConnection
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

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
            radio.connect(container.bleScanner.connection(device.id), device.name)
        }
    }

    fun connectTcp(host: String, port: Int) {
        viewModelScope.launch {
            RadioService.start(getApplication(), host)
            radio.connect(TcpConnection(host, port), host)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            radio.disconnect()
            RadioService.stop(getApplication())
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

    fun sendToChannel(text: String, channel: Int) {
        viewModelScope.launch { container.radioManager.sendTextMessage(text, channel = channel) }
    }

    fun sendDirect(text: String, toNum: Long) {
        viewModelScope.launch { container.radioManager.sendTextMessage(text, toNum = toNum) }
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

class SettingsViewModel(app: Application) : AndroidViewModel(app) {
    private val container = app.container

    val myInfo: StateFlow<MyInfoEntity?> = container.database.myInfoDao().myInfo()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val state: StateFlow<RadioState> = container.radioManager.state
    val nodeCount: StateFlow<Int> = container.database.nodeDao().count()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
}
