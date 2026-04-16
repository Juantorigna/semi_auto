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
            val pm   = activity.packageManager
            val info = pm.getPackageInfo(activity.packageName, 0)
            info.versionName ?: "unknown"
        } catch (e: Exception) {
            Log.e(TAG, "getAppVersion error", e)
            "unknown"
        }
    }

    // ── Reader status ─────────────────────────────────────────────────────────

    /**
     * Called by JS to query reader connection status.
     * Returns "connected" | "disconnected" | "not_initialized".
     */
    @JavascriptInterface
    fun getReaderStatus(): String {
        val status = TerminalManager.readerStatus
        Log.d(TAG, "getReaderStatus → $status")
        return status
    }

    // ── Payment ───────────────────────────────────────────────────────────────

    /**
     * Called by payment.html when the guest must pay.
     *
     * @param clientSecret     The PaymentIntent client_secret from the server response.
     *                         The server is the sole source of truth for the amount.
     * @param registrationRef  Registration reference for logging only.
     *
     * On success: evaluates window.onPaymentSuccess(paymentIntentId)
     * On failure: evaluates window.onPaymentFailure(errorMessage)
     */
    @JavascriptInterface
    fun initiatePayment(clientSecret: String, registrationRef: String) {
        Log.d(TAG, "initiatePayment — ref=$registrationRef")

        TerminalManager.processPayment(
            clientSecret = clientSecret,
            onSuccess    = { piId ->
                Log.i(TAG, "Payment success: $piId")
                evaluateJs(
                    "window.onPaymentSuccess && window.onPaymentSuccess(${escapeJsString(piId)})"
                )
            },
            onFailure    = { msg ->
                Log.e(TAG, "Payment failure: $msg")
                evaluateJs(
                    "window.onPaymentFailure && window.onPaymentFailure(${escapeJsString(msg)})"
                )
            }
        )
    }

    /**
     * Called by the JS layer when the guest taps "Cancel" on the payment screen.
     * Cancels any in-flight collectPaymentMethod operation.
     */
    @JavascriptInterface
    fun cancelPayment() {
        Log.d(TAG, "cancelPayment called")
        TerminalManager.cancelPayment()
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

    /**
     * Wraps a string in single-quoted JS literal, escaping backslashes,
     * single quotes, and newlines to prevent JS injection.
     */
    private fun escapeJsString(value: String): String {
        val escaped = value
            .replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
        return "'$escaped'"
    }
}