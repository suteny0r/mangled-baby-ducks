package com.suteny0r.mangledbabyducks

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.suteny0r.mangledbabyducks.ui.ConnectScreen
import com.suteny0r.mangledbabyducks.ui.FloatingTabBar
import com.suteny0r.mangledbabyducks.ui.TabSpec
import com.suteny0r.mangledbabyducks.ui.MapScreen
import com.suteny0r.mangledbabyducks.ui.MessagesScreen
import com.suteny0r.mangledbabyducks.ui.NodesScreen
import com.suteny0r.mangledbabyducks.ui.SettingsScreen
import com.suteny0r.mangledbabyducks.ui.ThreadTarget
import com.suteny0r.mangledbabyducks.ui.theme.MeshtasticTheme
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveableStateHolder

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
                // Tab order + labels mirror the iOS TabView (ContentView.swift).
                val tabs = listOf(
                    TabSpec("Messages", Icons.Filled.ChatBubble),
                    TabSpec("Nodes", Icons.Filled.Router),
                    TabSpec("Map", Icons.Filled.Map),
                    TabSpec("Settings", Icons.Filled.Settings),
                    TabSpec("Connect", Icons.Filled.Link),
                )
                val selected by router.selectedTab.collectAsState()
                val unread by remember {
                    container.database.messageDao().unreadCount()
                }.collectAsState(initial = 0)
                Scaffold(
                    containerColor = MaterialTheme.colorScheme.background,
                    bottomBar = {
                        FloatingTabBar(
                            tabs = tabs,
                            selected = selected,
                            onSelect = { router.selectedTab.value = it },
                            badgeIndex = com.suteny0r.mangledbabyducks.ui.Router.TAB_MESSAGES,
                            badgeCount = unread,
                        )
                    },
                ) { padding ->
                    // Nodes is home: system back from any other tab root returns there,
                    // and from Nodes it exits. Sub-screens register their own handlers
                    // later in composition, so they win while open.
                    BackHandler(enabled = selected != com.suteny0r.mangledbabyducks.ui.Router.TAB_NODES) {
                        router.selectedTab.value = com.suteny0r.mangledbabyducks.ui.Router.TAB_NODES
                    }
                    // Each iOS tab owns a NavigationStack that survives switching tabs:
                    // "Show on Map" from a traceroute log jumps to the Map tab, and coming
                    // back to Nodes lands on the same node detail and log. Keying the
                    // saveable state by tab gives rememberSaveable in each screen that
                    // lifetime instead of resetting on every switch.
                    val tabStates = rememberSaveableStateHolder()
                    androidx.compose.foundation.layout.Box(Modifier.padding(padding)) {
                        tabStates.SaveableStateProvider(selected) {
                            when (selected) {
                                0 -> MessagesScreen()
                                1 -> NodesScreen()
                                2 -> MapScreen()
                                3 -> SettingsScreen()
                                else -> ConnectScreen()
                            }
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

    override fun onResume() {
        super.onResume()
        container.locationSharer.ensureTracking()
        // A deliberate Disconnect forgets the radio, so this stays a no-op then.
        autoConnectIfRemembered()
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

    /** See [AppContainer.autoConnectIfRemembered]; every lifecycle entry point lands here. */
    private fun autoConnectIfRemembered() {
        lifecycleScope.launch { container.autoConnectIfRemembered(this@MainActivity) }
    }

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
