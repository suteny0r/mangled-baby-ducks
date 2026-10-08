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
    /** One-shot: the top-left logo opens Settings > About (iOS deep link settings/about). */
    val pendingAbout = MutableStateFlow(false)
    /** One-shot: node detail's "Node Map" row; the Map tab centers on this node. */
    val pendingMapNode = MutableStateFlow<Long?>(null)

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

    fun openMapNode(num: Long) {
        pendingMapNode.value = num
        selectedTab.value = TAB_MAP
    }

    fun openAbout() {
        pendingAbout.value = true
        selectedTab.value = TAB_SETTINGS
    }

    /**
     * The switch flow's `router.popToRoot(tab:)` on every tab: nothing pending may survive
     * a database clear, or a consumer would open a thread or node that no longer exists.
     */
    fun resetNavigation() {
        pendingThread.value = null
        pendingNode.value = null
        activeRoute.value = null
        pendingAbout.value = false
        pendingMapNode.value = null
    }

    companion object {
        /** Tab order matches the iOS app: Messages, Nodes, Map, Settings, Connect. */
        const val TAB_MESSAGES = 0
        const val TAB_NODES = 1
        const val TAB_MAP = 2
        const val TAB_SETTINGS = 3
        const val TAB_CONNECT = 4
    }
}
