package com.suteny0r.mangledbabyducks.ui

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

    fun openThread(target: ThreadTarget) {
        pendingThread.value = target
        selectedTab.value = TAB_MESSAGES
    }

    companion object {
        const val TAB_CONNECT = 0
        const val TAB_NODES = 1
        const val TAB_MESSAGES = 2
        const val TAB_SETTINGS = 3
    }
}
