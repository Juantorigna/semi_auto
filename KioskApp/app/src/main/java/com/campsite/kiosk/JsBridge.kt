package com.campsite.kiosk

import android.webkit.JavascriptInterface
import android.util.Log

/**
 * JsBridge
 *
 * Exposed to WebView JS as `window.KioskBridge`.
 * All @JavascriptInterface methods run on a background thread — post to main
 * thread before touching UI or WebView.
 *
 * Step 1: stub only (getAppVersion).
 * Payment methods wired in Step N (Stripe Terminal integration).
 */
class JsBridge(
    private val appVersion: String
) {

    companion object {
        private const val TAG = "JsBridge"
        /** Name exposed to JS: window.KioskBridge */
        const val JS_INTERFACE_NAME = "KioskBridge"
    }

    /**
     * JS: window.KioskBridge.getAppVersion()
     * Returns app version string so the frontend can log it.
     */
    @JavascriptInterface
    fun getAppVersion(): String {
        Log.d(TAG, "getAppVersion() called from JS")
        return appVersion
    }

    // ── Stripe Terminal stubs (implemented in Step N) ───────────────────────

    /**
     * JS: window.KioskBridge.initiatePayment(amountCents, bookingRef)
     * Called by frontend when server confirms a balance is owed.
     * Stub: logs call, returns immediately. Real impl triggers Stripe Terminal.
     */
    @JavascriptInterface
    fun initiatePayment(amountCents: Int, bookingRef: String) {
        Log.d(TAG, "initiatePayment() stub — amount=$amountCents ref=$bookingRef")
        // TODO Step N: launch Stripe Terminal payment flow
    }

    /**
     * JS: window.KioskBridge.cancelPayment()
     * Allows frontend to abort an in-progress payment.
     */
    @JavascriptInterface
    fun cancelPayment() {
        Log.d(TAG, "cancelPayment() stub")
        // TODO Step N: cancel active Stripe Terminal reader action
    }
}
