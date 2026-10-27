package org.screenlite.webkiosk.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Build
import android.util.DisplayMetrics
import android.util.Log
import android.webkit.*
import android.webkit.WebView.setWebContentsDebuggingEnabled
import androidx.annotation.RequiresApi
import org.screenlite.webkiosk.components.RotatedWebView
import org.screenlite.webkiosk.data.Rotation

class WebViewManager(
    private val context: Context,
    private val onError: (Boolean) -> Unit,
    private val onPageLoading: (Boolean) -> Unit,
    private val onSnapshot: (Bitmap?) -> Unit = {}
) {
    private var currentWebView: WebView? = null
    private var isLoading = false
    private var hasFailed = false
    private var isPageShown = false

    fun createWebView(rotation: Rotation = Rotation.ROTATION_0): WebView {
        isLoading = false
        hasFailed = false
        isPageShown = false

        val webView = RotatedWebView(context).apply {
            layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            appliedRotation = rotation.degrees.toFloat()

            isFocusable = true
            isFocusableInTouchMode = true
            requestFocus()

            configureWebViewSettings()
            setupWebViewListeners()
        }

        currentWebView = webView
        return webView
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun WebView.configureWebViewSettings() {
        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            javaScriptCanOpenWindowsAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            setSupportMultipleWindows(true)
            setWebContentsDebuggingEnabled(true)
            val displayMetrics = context.resources.displayMetrics
            setInitialScale(calculateScale(displayMetrics))

            displayZoomControls = false
            builtInZoomControls = false
            setSupportZoom(false)

            textZoom = 100
            minimumFontSize = 1
            minimumLogicalFontSize = 1
            useWideViewPort = true
        }
    }

    private fun calculateScale(displayMetrics: DisplayMetrics): Int {
        val density = displayMetrics.density
        return (100 / density).toInt()
    }

    fun updateRotation(rotation: Rotation) {
        currentWebView?.let { webView ->
            if (webView is RotatedWebView) {
                webView.appliedRotation = rotation.degrees.toFloat()
            }

            ViewportMetaInjector.inject(webView)
        }
    }

    // Returns the currently displayed page as a bitmap, already rotated the way it appears on screen
    private fun captureSnapshot(view: WebView): Bitmap? {
        if (view.width <= 0 || view.height <= 0) return null

        return try {
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))

            if (view.rotation % 360f == 0f) {
                bitmap
            } else {
                val matrix = Matrix().apply { postRotate(view.rotation) }
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
        } catch (e: Throwable) {
            Log.w("WebViewManager", "Failed to capture page snapshot", e)
            null
        }
    }

    private fun WebView.setupWebViewListeners() {
        webViewClient = object : WebViewClient() {
            private val revealRunnable = Runnable {
                isLoading = false
                isPageShown = !hasFailed
                onPageLoading(false)
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                view.removeCallbacks(revealRunnable)
                // Redirects start a page again while the old one is already gone, keep the first snapshot
                if (!isLoading) {
                    onSnapshot(if (isPageShown) captureSnapshot(view) else null)
                }
                isLoading = true
                hasFailed = false
                onPageLoading(true)
            }

            override fun onPageFinished(view: WebView, url: String?) {
                super.onPageFinished(view, url)
                ViewportMetaInjector.inject(view)
                view.removeCallbacks(revealRunnable)
                view.postDelayed(revealRunnable, 1000)
            }

            @Suppress("DEPRECATION")
            @Deprecated("Deprecated in API 23")
            override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
                Log.e("WebViewManager", "Legacy page failed: $failingUrl, code=$errorCode, desc=$description")
                hasFailed = true
                onPageLoading(false)
                onError(true)
                super.onReceivedError(view, errorCode, description, failingUrl)
            }

            @RequiresApi(Build.VERSION_CODES.M)
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) {
                    hasFailed = true
                    onPageLoading(false)
                    onError(true)
                    Log.e(
                        "WebViewManager",
                        "Main page failed: ${request.url}, code=${error.errorCode}, desc=${error.description}"
                    )
                } else {
                    Log.w(
                        "WebViewManager",
                        "Subresource failed: ${request.url}, code=${error.errorCode}, desc=${error.description}"
                    )
                }
            }

            override fun onScaleChanged(view: WebView, oldScale: Float, newScale: Float) {
                super.onScaleChanged(view, oldScale, newScale)
                if (newScale != 1.0f) {
                    view.scaleX = 1.0f
                    view.scaleY = 1.0f
                }
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                val transport = resultMsg?.obj as? WebView.WebViewTransport

                val tempWebView = WebView(context).apply {
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                            val url = request?.url.toString()
                            this@WebViewManager.currentWebView?.loadUrl(url)
                            return true
                        }
                    }
                }

                transport?.webView = tempWebView
                resultMsg?.sendToTarget()
                return true
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                Log.d(
                    "WebViewConsole",
                    "JS ${consoleMessage.messageLevel()}: ${consoleMessage.message()} @ ${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}"
                )
                return true
            }
        }
    }
}