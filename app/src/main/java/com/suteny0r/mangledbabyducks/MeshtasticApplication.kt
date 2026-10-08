package com.suteny0r.mangledbabyducks

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

class MeshtasticApplication : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) MainThreadWatchdog.start()
    }
}

/**
 * Debug builds only: posts a no-op to the main looper every second and, when it has not
 * run within 2 s, logs the main thread's stack so a stall can be read off logcat instead
 * of guessed at. Android only reports a stall itself when input is pending (ANR) or a
 * frame was due (Choreographer); a quiet screen with a blocked main thread logs nothing.
 */
object MainThreadWatchdog {
    private const val TAG = "MainWatchdog"

    fun start() {
        val handler = Handler(Looper.getMainLooper())
        val main = Looper.getMainLooper().thread
        Thread({
            while (true) {
                val ran = AtomicBoolean(false)
                handler.post { ran.set(true) }
                Thread.sleep(2000)
                if (!ran.get()) {
                    val stack = main.stackTrace.joinToString("\n    ")
                    Log.w(TAG, "Main thread unresponsive for 2 s; main is at:\n    $stack")
                    while (!ran.get()) Thread.sleep(500)
                    Log.w(TAG, "Main thread responsive again")
                }
                Thread.sleep(1000)
            }
        }, "MainWatchdog").apply { isDaemon = true }.start()
    }
}
