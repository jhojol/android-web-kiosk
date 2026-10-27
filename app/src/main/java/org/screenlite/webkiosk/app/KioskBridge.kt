package org.screenlite.webkiosk.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import android.webkit.WebView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONException
import org.json.JSONObject

/**
 * The page's line to the app: window.WebKiosk, given only to pages of the kiosk URL's own site.
 *
 * The page sends JSON strings with WebKiosk.postMessage():
 *  - {"type": "brightness", "level": 0–100} sets the screen brightness; "level": null hands it back to the settings
 *  - {"type": "hello"} asks for the battery state
 * and, once it has said hello, hears {"type": "battery", "level": 0.0–1.0, "charging": true|false}
 * through WebKiosk.addEventListener("message", ...) whenever the battery changes.
 */
class KioskBridge(
    private val context: Context,
    url: String,
    private val onBrightness: (Int?) -> Unit
) {
    private val origin = originOf(url)
    private var page: JavaScriptReplyProxy? = null
    private var battery: String? = null

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            battery = batteryMessage(intent)
            send(battery)
        }
    }

    init {
        // A sticky broadcast: the current state comes back at once, the changes follow
        val current = ContextCompat.registerReceiver(
            context, batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED
        )
        battery = current?.let(::batteryMessage)
    }

    // Before the page loads: the object is put into every page the WebView opens from then on
    fun attach(webView: WebView) {
        if (origin == null) {
            Log.w("KioskBridge", "No bridge: the kiosk URL is not a web address")
            return
        }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            try {
                WebViewCompat.addWebMessageListener(webView, "WebKiosk", setOf(origin)) { _, message, _, isMainFrame, replyProxy ->
                    if (isMainFrame) receive(message.data, replyProxy)
                }
            } catch (e: IllegalArgumentException) {
                Log.w("KioskBridge", "Cannot open the bridge to $origin", e)
            }
        } else {
            Log.w("KioskBridge", "No bridge: this WebView is too old")
        }
    }

    fun release() {
        context.unregisterReceiver(batteryReceiver)
        page = null
        onBrightness(null)
    }

    private fun receive(data: String?, replyProxy: JavaScriptReplyProxy) {
        val message = try {
            JSONObject(data ?: return)
        } catch (e: JSONException) {
            Log.w("KioskBridge", "Not a message: $data")
            return
        }
        when (message.optString("type")) {
            "hello" -> {
                page = replyProxy
                send(battery)
            }
            "brightness" -> onBrightness(
                if (message.isNull("level")) null else message.optInt("level", -1).takeIf { it in 0..100 }
            )
        }
    }

    private fun send(message: String?) {
        val target = page ?: return
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return
        try {
            target.postMessage(message ?: return)
        } catch (e: Exception) {
            Log.w("KioskBridge", "The page could not be reached", e)
        }
    }

    private fun batteryMessage(intent: Intent): String? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return JSONObject()
            .put("type", "battery")
            .put("level", level.toDouble() / scale)
            // On the charger, also once it is full
            .put("charging", intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
            .toString()
    }

    // What addWebMessageListener matches pages against: scheme://host[:port]
    private fun originOf(url: String): String? {
        val uri = url.toUri()
        val scheme = uri.scheme?.lowercase()
        val host = uri.host
        if (scheme != "http" && scheme != "https" || host.isNullOrEmpty()) return null
        return if (uri.port == -1) "$scheme://$host" else "$scheme://$host:${uri.port}"
    }
}
