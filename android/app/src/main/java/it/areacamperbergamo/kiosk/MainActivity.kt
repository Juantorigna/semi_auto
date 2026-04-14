package it.areacamperbergamo.kiosk

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Main activity — displays a full-screen WebView loading the kiosk web app.
 *
 * Injects the `AndroidBridge` JavaScript interface so the web app can call
 * Kotlin code to trigger Stripe Terminal payments.
 *
 * The WebView loads the web app from the remote server. When the user
 * reaches the payment screen, the JS code calls:
 *   window.AndroidBridge.collectPayment(amountCents)
 *
 * The Kotlin PaymentBridge handles the Stripe Terminal flow and calls back:
 *   window.onPaymentResult(success, message)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var webView: WebView
    private lateinit var paymentBridge: PaymentBridge

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Go full-screen (immersive sticky — hides status bar + navigation)
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        webView = findViewById(R.id.webview)

        // Configure WebView settings
        webView.settings.apply {
            javaScriptEnabled = true            // Required for the web app
            domStorageEnabled = true             // Required for sessionStorage
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page loaded: $url")
            }
        }

        webView.webChromeClient = WebChromeClient()

        // Create the payment bridge and inject it as a JavaScript interface.
        // The web app can call window.AndroidBridge.collectPayment(cents)
        paymentBridge = PaymentBridge(
            webView = webView,
            apiBaseUrl = BuildConfig.API_BASE_URL,
            useSimulatedReader = BuildConfig.USE_SIMULATED_READER,
        )
        webView.addJavascriptInterface(paymentBridge, "AndroidBridge")

        // Request Bluetooth permissions before loading the app
        requestPermissionsIfNeeded()
    }

    private fun requestPermissionsIfNeeded() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Android 12+: need BLUETOOTH_CONNECT and BLUETOOTH_SCAN
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN)
                != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(
                this,
                permissions.toTypedArray(),
                PERMISSION_REQUEST_CODE,
            )
        } else {
            loadWebApp()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == PERMISSION_REQUEST_CODE) {
            // Load the app regardless — the simulated reader works without
            // Bluetooth permissions. Real reader will need them.
            loadWebApp()
        }
    }

    private fun loadWebApp() {
        val url = BuildConfig.KIOSK_BASE_URL
        Log.i(TAG, "Loading kiosk web app: $url")
        webView.loadUrl(url)
    }

    // Prevent the back button from leaving the app (kiosk mode)
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Do nothing — kiosk should not navigate away
    }

    override fun onResume() {
        super.onResume()
        // Re-apply immersive mode
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
