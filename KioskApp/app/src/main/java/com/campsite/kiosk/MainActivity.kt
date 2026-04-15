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
import com.campsite.kiosk.databinding.ActivityMainBinding

/**
 * MainActivity
 *
 * Single activity. Hosts the kiosk WebView.
 *
 * WebView config:
 *  - JS enabled
 *  - DOM storage enabled (localStorage for session state)
 *  - DOM storage database enabled (sessionStorage)
 *  - Mixed content: COMPATIBILITY mode (Aruba HTTPS; safe default)
 *  - No file access, no geolocation, no form autofill
 *  - Safe browsing enabled
 *
 * Kiosk hardening:
 *  - Back press intercepted — never leaves to launcher
 *  - Immersive sticky mode (no status/nav bar)
 *  - Screen stays on (FLAG_KEEP_SCREEN_ON)
 *  - Single-task launchMode (Manifest)
 */
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

        // Restore WebView state after rotation — avoids full reload
        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
            webView.loadUrl(KioskConfig.BASE_URL)
        }
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        enterImmersiveMode()                                    // re-assert after system UI intrusion
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
    }

    override fun onDestroy() {
        // Prevent WebView memory leak
        webView.stopLoading()
        webView.destroy()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    // ─── Back press — kiosk guard ─────────────────────────────────────────────

    @Deprecated("Deprecated in Java") // suppress lint; still correct API for minSdk 26
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        }
        // If cannot go back: swallow. Never call super → never exit to launcher.
    }

    // Volume keys: block to prevent accidental system UI reveal
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_DOWN,
            KeyEvent.KEYCODE_VOLUME_UP,
            KeyEvent.KEYCODE_VOLUME_MUTE -> true               // consumed
            else -> super.onKeyDown(keyCode, event)
        }
    }

    // ─── WebView configuration ────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")                       // JS required — intentional kiosk
    private fun configureWebView() {
        webView.settings.apply {

            // ── JS + storage ──────────────────────────────────────────────────
            javaScriptEnabled = true
            domStorageEnabled = true                            // localStorage / sessionStorage

            // ── Cache ─────────────────────────────────────────────────────────
            cacheMode = WebSettings.LOAD_DEFAULT               // respect HTTP cache headers

            // ── Mixed content ─────────────────────────────────────────────────
            // COMPATIBILITY: blocks active mixed content (scripts/iframes),
            // allows passive (images). Aruba backend should be full HTTPS.
            @Suppress("DEPRECATION")
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE

            // ── Disable unused capabilities (attack surface reduction) ─────────
            allowFileAccess = false
            allowContentAccess = false
            geolocationEnabled = false
            saveFormData = false
            savePassword = false                                // deprecated but explicit
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)

            // ── Rendering ─────────────────────────────────────────────────────
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            // ── Safe browsing ─────────────────────────────────────────────────
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                safeBrowsingEnabled = true
            }

            // ── User agent — identify kiosk build ────────────────────────────
            userAgentString = "$userAgentString KioskApp/${BuildConfig.VERSION_NAME}"
        }

        // ── Clients ───────────────────────────────────────────────────────────
        webView.webViewClient = KioskWebViewClient(
            allowedOrigin = KioskConfig.ALLOWED_ORIGIN,
            onPageStarted = { showLoading(true) },
            onPageFinished = { showLoading(false) },
            onError = { showError(it) }
        )
        webView.webChromeClient = KioskWebChromeClient()

        // ── JS Bridge ─────────────────────────────────────────────────────────
        webView.addJavascriptInterface(
            JsBridge(appVersion = BuildConfig.VERSION_NAME),
            KioskConfig.JS_INTERFACE_NAME
        )

        // ── Keep screen on ────────────────────────────────────────────────────
        webView.keepScreenOn = true
    }

    // ─── Immersive mode ───────────────────────────────────────────────────────

    private fun enterImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
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

    // ─── Loading / error UI ───────────────────────────────────────────────────

    private fun showLoading(visible: Boolean) {
        binding.loadingIndicator.visibility = if (visible) View.VISIBLE else View.GONE
    }

    private fun showError(message: String) {
        showLoading(false)
        // Step N: replace with branded error screen / retry button
        binding.webView.loadUrl("about:blank")
        webView.evaluateJavascript(
            """
            document.body.style.cssText='display:flex;align-items:center;justify-content:center;height:100vh;font-family:sans-serif;background:#1a1a2e;color:#fff;';
            document.body.innerHTML='<div style="text-align:center"><h2>Connessione non disponibile</h2><p>${message.replace("'", "\\'")}</p></div>';
            """.trimIndent(),
            null
        )
    }
}
