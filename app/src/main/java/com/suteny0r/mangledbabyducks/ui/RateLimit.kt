package com.suteny0r.mangledbabyducks.ui

import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import kotlin.math.ceil

/**
 * Port of RateLimitedButton.swift's RateLimitStorage: per-key action cooldowns held in
 * memory only, never persisted across launches. A key names the action, not the target,
 * so the iOS "traceroute" limit is one shared 30 s floor for every node.
 */
object RateLimitStorage {

    private data class Limiter(val occurredAt: Long, val limitMs: Long) {
        val expiresAt: Long get() = occurredAt + limitMs
    }

    /** Snapshot state so starting a cooldown recomposes every row watching that key. */
    private val limits = mutableStateMapOf<String, Limiter>()

    /** Start a cooldown, unless one already running would outlast it. */
    fun actionOccurred(key: String, limitSeconds: Double) {
        val now = SystemClock.elapsedRealtime()
        val limitMs = (limitSeconds * 1000).toLong()
        limits[key]?.let { if (it.expiresAt > now + limitMs) return }
        limits[key] = Limiter(now, limitMs)
    }

    fun isRunning(key: String): Boolean = limits.containsKey(key)

    fun millisRemaining(key: String): Long {
        val limiter = limits[key] ?: return 0L
        return (limiter.expiresAt - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    fun fractionRemaining(key: String): Float {
        val limiter = limits[key] ?: return 0f
        val remaining = limiter.expiresAt - SystemClock.elapsedRealtime()
        return (remaining.toFloat() / limiter.limitMs).coerceIn(0f, 1f)
    }

    /** Drop finished keys, which is what flips a row back to its normal label. */
    fun pruneExpired() {
        val now = SystemClock.elapsedRealtime()
        limits.entries.removeAll { it.value.expiresAt <= now }
    }
}

/** TraceRouteButton.swift: one 30 s floor shared by every node's Trace Route action. */
const val TRACEROUTE_RATE_LIMIT_KEY = "traceroute"
const val TRACEROUTE_RATE_LIMIT_SECONDS = 30.0

/** What a rate-limited row needs to draw itself: a draining ring and a whole-second count. */
data class RateLimitState(val fractionRemaining: Float, val secondsRemaining: Int) {
    val running: Boolean get() = fractionRemaining > 0f
}

/**
 * Watch one cooldown key. Ticks only while that key is running, so an idle screen costs
 * nothing; iOS runs a 1 s timer, this runs at 100 ms so the ring moves smoothly.
 */
@Composable
fun rememberRateLimit(key: String): RateLimitState {
    var remainingMs by remember(key) { mutableLongStateOf(RateLimitStorage.millisRemaining(key)) }
    var fraction by remember(key) { mutableFloatStateOf(RateLimitStorage.fractionRemaining(key)) }
    // Reading the snapshot map here is what wakes this composable when the action fires.
    val running = RateLimitStorage.isRunning(key)
    LaunchedEffect(key, running) {
        if (!running) {
            remainingMs = 0L
            fraction = 0f
            return@LaunchedEffect
        }
        while (true) {
            remainingMs = RateLimitStorage.millisRemaining(key)
            fraction = RateLimitStorage.fractionRemaining(key)
            if (remainingMs <= 0L) break
            delay(100)
        }
        RateLimitStorage.pruneExpired()
    }
    return RateLimitState(fraction, ceil(remainingMs / 1000.0).toInt())
}
