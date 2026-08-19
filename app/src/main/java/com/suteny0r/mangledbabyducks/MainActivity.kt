package com.suteny0r.mangledbabyducks

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Message
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.suteny0r.mangledbabyducks.radio.RadioService
import com.suteny0r.mangledbabyducks.radio.RadioState
import com.suteny0r.mangledbabyducks.radio.TcpConnection
import com.suteny0r.mangledbabyducks.ui.ConnectScreen
import com.suteny0r.mangledbabyducks.ui.MapScreen
import com.suteny0r.mangledbabyducks.ui.MessagesScreen
import com.suteny0r.mangledbabyducks.ui.NodesScreen
import com.suteny0r.mangledbabyducks.ui.SettingsScreen
import com.suteny0r.mangledbabyducks.ui.ThreadTarget
import com.suteny0r.mangledbabyducks.ui.theme.MeshtasticTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

private data class Tab(val label: String, val icon: ImageVector)

class MainActivity : ComponentActivity() {

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            autoConnectIfRemembered()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNeededPermissions()
        handleIntent(intent)
        setContent {
            MeshtasticTheme {
                val router = container.router
                val tabs = listOf(
                    Tab("Connect", Icons.Default.SettingsRemote),
                    Tab("Nodes", Icons.Default.Router),
                    Tab("Map", Icons.Default.Map),
                    Tab("Messages", Icons.AutoMirrored.Filled.Message),
                    Tab("Settings", Icons.Default.Settings),
                )
                val selected by router.selectedTab.collectAsState()
                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            tabs.forEachIndexed { index, tab ->
                                NavigationBarItem(
                                    selected = selected == index,
                                    onClick = { router.selectedTab.value = index },
                                    icon = { Icon(tab.icon, contentDescription = tab.label) },
                                    label = { Text(tab.label) },
                                )
                            }
                        }
                    },
                ) { padding ->
                    androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                        when (selected) {
                            0 -> ConnectScreen()
                            1 -> NodesScreen()
                            2 -> MapScreen()
                            3 -> MessagesScreen()
                            else -> SettingsScreen()
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_THREAD, false) != true) return
        val name = intent.getStringExtra(EXTRA_THREAD_NAME) ?: return
        val peer = intent.getLongExtra(EXTRA_DM_PEER, -1L)
        val channel = intent.getIntExtra(EXTRA_CHANNEL, -1)
        val target = when {
            peer >= 0 -> ThreadTarget.Direct(peer, name)
            channel >= 0 -> ThreadTarget.Channel(channel, name)
            else -> return
        }
        container.router.openThread(target)
    }

    /** Reconnect to the last radio on app start, mirroring iOS's preferredPeripheral. */
    private fun autoConnectIfRemembered() {
        val container = container
        lifecycleScope.launch {
            if (container.radioManager.state.value != RadioState.Idle) return@launch
            val prefs = container.prefs.data.first()
            val type = prefs[PrefKeys.RADIO_TYPE] ?: return@launch
            val address = prefs[PrefKeys.RADIO_ADDRESS] ?: return@launch
            val name = prefs[PrefKeys.RADIO_NAME]
            when (type) {
                "ble" -> {
                    if (!hasBlePermission() || container.bleScanner.adapter?.isEnabled != true) return@launch
                    RadioService.start(this@MainActivity, name)
                    container.radioManager.connect(name) { container.bleScanner.connection(address) }
                }
                "tcp" -> {
                    val host = address.substringBefore(':')
                    val port = address.substringAfter(':', "4403").toIntOrNull() ?: 4403
                    RadioService.start(this@MainActivity, name)
                    container.radioManager.connect(name) { TcpConnection(host, port) }
                }
            }
        }
    }

    private fun hasBlePermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    private fun requestNeededPermissions() {
        val permissions = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(Manifest.permission.BLUETOOTH_SCAN)
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            // For phone-GPS position sharing (and pre-S BLE scanning).
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    companion object {
        const val EXTRA_OPEN_THREAD = "open_thread"
        const val EXTRA_DM_PEER = "dm_peer"
        const val EXTRA_CHANNEL = "channel"
        const val EXTRA_THREAD_NAME = "thread_name"
    }
}
