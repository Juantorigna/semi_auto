package com.campsite.kiosk

import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity

/**
 * JsBridge
 *
 * Exposed to JavaScript as `window.KioskBridge`.
 * All public methods annotated with @JavascriptInterface are callable from JS.
 *
 * ProGuard rule in proguard-rules.pro ensures these are never stripped in release:
 *   -keepclassmembers class com.campsite.kiosk.JsBridge { @android.webkit.JavascriptInterface *; }
 *
 * Thread note: @JavascriptInterface methods are called on a background thread.
 * Use activity.runOnUiThread { } for any UI work.
 */
class JsBridge(
    private val activity: AppCompatActivity,
    private val webView: WebView
) {

    companion object {
        private const val TAG = "JsBridge"
    }

    // ── App metadata ──────────────────────────────────────────────────────────

    @JavascriptInterface
    fun getAppVersion(): String {
        return try {
            val pm      = activity.packageManager
            val info    = pm.getPackageInfo(activity.packageName, 0)
            info.versionName ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "getAppVersion error", e)
            "unknown"
        }
    }

    // ── Payment stubs (wired in Step 7) ──────────────────────────────────────

    /**
     * Called by payment.html when the guest must pay.
     * @param amountCents     Integer cents — always sourced from server, never trusted from JS.
     * @param registrationRef Registration reference string for PaymentIntent metadata.
     *
     * Currently a stub: logs and fires window.onPaymentFailure('not_implemented').
     * Replace body in Step 7 with real Stripe Terminal flow.
     */
    @JavascriptInterface
    fun initiatePayment(amountCents: Int, registrationRef: String) {
        Log.d(TAG, "initiatePayment stub — amountCents=$amountCents ref=$registrationRef")
        evaluateJs("window.onPaymentFailure && window.onPaymentFailure('not_implemented')")
    }

    /**
     * Called by the JS layer when the guest taps "Cancel" on the payment screen.
     * Stub: logs only.
     */
    @JavascriptInterface
    fun cancelPayment() {
        Log.d(TAG, "cancelPayment stub called")
    }

    /**
     * Called by JS to query reader connection status.
     * Returns "connected" | "disconnected" | "unknown".
     * Stub always returns "disconnected" until Step 5 wires real discovery.
     */
    @JavascriptInterface
    fun getReaderStatus(): String {
        Log.d(TAG, "getReaderStatus stub called")
        return "disconnected"
    }

    // ── Overlay control ───────────────────────────────────────────────────────

    /**
     * Called by JS heartbeat / connectivity watcher to dismiss the no-connection
     * overlay once network is restored.
     */
    @JavascriptInterface
    fun dismissConnectionError() {
        Log.d(TAG, "dismissConnectionError called from JS")
        activity.runOnUiThread {
            (activity as? MainActivity)?.showNoConnectionOverlay(false)
        }
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun evaluateJs(script: String) {
        activity.runOnUiThread {
            webView.evaluateJavascript(script, null)
        }
    }
}