package com.campsite.kiosk

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.campsite.kiosk.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        webView = binding.webView

        configureWebView()
        enterImmersiveMode()

        binding.btnRetry.setOnClickListener {
            showError(false)
            webView.reload()
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(KioskConfig.BASE_URL)
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        enterImmersiveMode()
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    // ─── Back press — kiosk guard ─────────────────────────────────────────────

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        }
        // intentionally NOT calling super — kiosk must never exit to launcher
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_MUTE -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ─── WebView config ───────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        val appVersion = packageManager
            .getPackageInfo(packageName, 0)
            .versionName
            ?: "unknown"

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            @Suppress("DEPRECATION")
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
            @Suppress("DEPRECATION")
            setGeolocationEnabled(false)
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            safeBrowsingEnabled = true
            userAgentString = "$userAgentString KioskApp/$appVersion"
        }

        webView.webViewClient = KioskWebViewClient(
            allowedOrigin = KioskConfig.ALLOWED_ORIGIN,
            onPageStarted = { showLoading(true) },
            onPageFinished = {
                showLoading(false)
                showError(false)
            },
            onError = { msg ->
                showLoading(false)
                showError(true, msg)
            }
        )

        webView.webChromeClient = KioskWebChromeClient()

        webView.addJavascriptInterface(
            JsBridge(appVersion = appVersion),
            JsBridge.JS_INTERFACE_NAME
        )

        webView.keepScreenOn = true
    }

    // ─── Immersive mode ───────────────────────────────────────────────────────

    private fun enterImmersiveMode() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ─── UI helpers ───────────────────────────────────────────────────────────

    private fun showLoading(visible: Boolean) {
        binding.loadingIndicator.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun showError(visible: Boolean, message: String = "") {
        if (visible) {
            binding.webView.visibility = View.INVISIBLE
            binding.noConnectionOverlay.visibility = View.VISIBLE
            if (message.isNotEmpty()) binding.tvErrorMessage.text = message
        } else {
            binding.noConnectionOverlay.visibility = View.GONE
            binding.webView.visibility = View.VISIBLE
        }
    }
}