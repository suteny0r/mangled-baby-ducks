package com.suteny0r.mangledbabyducks.ui

import com.suteny0r.mangledbabyducks.db.TracerouteEntity
import kotlinx.coroutines.flow.MutableStateFlow

/** A conversation the UI should open. */
sealed interface ThreadTarget {
    data class Channel(val index: Int, val name: String) : ThreadTarget
    data class Direct(val peerNum: Long, val name: String) : ThreadTarget
}

/**
 * Minimal port of the iOS Router: cross-tab navigation state. The pending thread is
 * one-shot — consumed exactly once by the Messages tab.
 */
class Router {
    val selectedTab = MutableStateFlow(0)
    val pendingThread = MutableStateFlow<ThreadTarget?>(null)
    /** One-shot node detail request; consumed by whichever tab should show it. */
    val pendingNode = MutableStateFlow<Long?>(null)
    /**
     * Active traceroute to render on the Map tab: only the nodes on the route are
     * shown, connected by the forward (solid) and return (dashed) path.
     */
    val activeRoute = MutableStateFlow<TracerouteEntity?>(null)

    fun openThread(target: ThreadTarget) {
        pendingThread.value = target
        selectedTab.value = TAB_MESSAGES
    }

    fun openNode(num: Long) {
        pendingNode.value = num
        selectedTab.value = TAB_NODES
    }

    fun openRoute(route: TracerouteEntity) {
        activeRoute.value = route
        selectedTab.value = TAB_MAP
    }

    fun clearRoute() {
        activeRoute.value = null
    }

    companion object {
        const val TAB_CONNECT = 0
        const val TAB_NODES = 1
        const val TAB_MAP = 2
        const val TAB_MESSAGES = 3
        const val TAB_SETTINGS = 4
    }
}
