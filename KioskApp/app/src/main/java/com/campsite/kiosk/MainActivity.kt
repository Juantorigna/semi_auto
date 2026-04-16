package com.campsite.kiosk

import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import com.campsite.kiosk.BuildConfig
import com.campsite.kiosk.databinding.ActivityMainBinding

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        webView = binding.webView

        configureWebView()
        enterImmersiveMode()

        binding.btnRetry.setOnClickListener {
            showError(show = false)
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack()
        // swallow — never exit to launcher
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_MUTE -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView() {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            @Suppress("DEPRECATION")
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            allowFileAccess = false
            allowContentAccess = false
            geolocationEnabled = false
            saveFormData = false
            @Suppress("DEPRECATION")
            savePassword = false
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }
            userAgentString = "$userAgentString KioskApp/${BuildConfig.VERSION_NAME}"
        }

        webView.webViewClient = KioskWebViewClient(
            allowedOrigin = KioskConfig.ALLOWED_ORIGIN,
            onPageStarted = { showLoading(true) },
            onPageFinished = {
                showLoading(false)
                showError(show = false)
            },
            onNetworkError = { message -> showError(show = true, message = message) }
        )
        webView.webChromeClient = KioskWebChromeClient()

        webView.addJavascriptInterface(
            JsBridge(appVersion = BuildConfig.VERSION_NAME),
            JsBridge.JS_INTERFACE_NAME          // direct ref — avoids KioskConfig circular dep
        )

        webView.keepScreenOn = true
    }

    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let {
                it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    )
        }
    }

    private fun showLoading(visible: Boolean) {
        binding.loadingIndicator.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun showError(show: Boolean, message: String = "") {
        if (show) {
            binding.noConnectionOverlay.visibility = View.VISIBLE
            if (message.isNotEmpty()) binding.tvErrorMessage.text = message
            binding.webView.visibility = View.INVISIBLE
        } else {
            binding.noConnectionOverlay.visibility = View.GONE
            binding.webView.visibility = View.VISIBLE
        }
    }
}