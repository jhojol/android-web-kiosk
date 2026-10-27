package org.screenlite.webkiosk.app

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.screenlite.webkiosk.data.KioskSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

const val TAG = "IdleBrightness"
class IdleBrightnessController(
    private val activity: Activity,
    private val settings: KioskSettings
) {
    private val handler = Handler(Looper.getMainLooper())
    private var idleTimeout: Long = 60_000L
    private var idleBrightness: Int = 0
    private var activeBrightness: Int = 100
    // Set by the page through the kiosk bridge (JHome's night mode); null leaves it to the settings
    private var pageBrightness: Int? = null
    private var idle = false

    private val checkIdleRunnable = Runnable {
        idle = true
        Log.d(TAG, "Idle timeout reached → switching to idle brightness: $idleBrightness%")
        setBrightness(idleBrightness)
        if (idleBrightness == 0) {
            _isIdleMode.value = true
        }
    }

    private val _isIdleMode = MutableStateFlow(false)
    val isIdleMode = _isIdleMode.asStateFlow()

    fun start() {
        Log.d(TAG, "Starting IdleBrightnessController...")

        CoroutineScope(Dispatchers.Main).launch {
            idleTimeout = (settings.getIdleTimeout().first() * 1000)
            idleBrightness = settings.getIdleBrightness().first()
            activeBrightness = settings.getActiveBrightness().first()

            Log.d(
                TAG,
                "Loaded settings → timeout=${idleTimeout}ms, idleBrightness=$idleBrightness%, activeBrightness=$activeBrightness%"
            )

            resetIdleTimer()
        }
    }

    private fun resetIdleTimer() {
        handler.removeCallbacks(checkIdleRunnable)
        idle = false
        setBrightness(pageBrightness ?: activeBrightness)
        _isIdleMode.value = false
        Log.d(
            TAG,
            "Idle timer reset → switching to active brightness: ${pageBrightness ?: activeBrightness}% " +
                if (idleTimeout > 0) "(next idle in ${idleTimeout}ms)" else "(never idle)"
        )
        // A timeout of 0 never dims
        if (idleTimeout > 0) handler.postDelayed(checkIdleRunnable, idleTimeout)
    }

    fun setPageBrightness(level: Int?) {
        Log.d(TAG, "Page brightness → ${level ?: "settings"}")
        pageBrightness = level
        if (!idle) setBrightness(level ?: activeBrightness)
    }

    private fun setBrightness(level: Int) {
        try {
            val lp = activity.window.attributes
            // Below 0 hands the screen back to the system's brightness, auto-brightness included
            lp.screenBrightness = if (level < 0) WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE else level / 100f
            activity.window.attributes = lp
            Log.d(TAG, "Screen brightness set to $level%")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set brightness", e)
        }
    }

    fun stop() {
        handler.removeCallbacks(checkIdleRunnable)
        Log.d(TAG, "Stopped IdleBrightnessController")
    }

    fun onUserInteraction() {
        Log.d(TAG, "User interaction detected → resetting idle timer")
        resetIdleTimer()
    }
}
