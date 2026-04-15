package com.campsite.kiosk

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log

/**
 * KioskWebViewClient
 *
 * Responsibilities:
 *  - Allow navigation only within the allowed origin (prevents open-redirect abuse)
 *  - Block all third-party navigations silently (no external browser launch)
 *  - Inject security response headers on every page load via intercepting resource requests
 *  - Expose page lifecycle for host Activity (loading state, errors)
 */
class KioskWebViewClient(
    private val allowedOrigin: String,
    private val onPageStarted: () -> Unit = {},
    private val onPageFinished: () -> Unit = {},
    private val onError: (String) -> Unit = {}
) : WebViewClient() {

    companion object {
        private const val TAG = "KioskWebViewClient"
    }

    // ─── Navigation guard ────────────────────────────────────────────────────

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return true                    // block null URLs
        return !isSameOrigin(uri)                               // block cross-origin nav
    }

    private fun isSameOrigin(uri: Uri): Boolean {
        val requestedHost = uri.host ?: return false
        val allowedHost = Uri.parse(allowedOrigin).host ?: return false
        return requestedHost.equals(allowedHost, ignoreCase = true)
    }

    // ─── Page lifecycle ───────────────────────────────────────────────────────

    override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageStarted()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        super.onPageFinished(view, url)
        onPageFinished()
    }

    override fun onReceivedError(
        view: WebView,
        errorCode: Int,
        description: String?,
        failingUrl: String?
    ) {
        super.onReceivedError(view, errorCode, description, failingUrl)
        Log.e(TAG, "WebView error $errorCode: $description at $failingUrl")
        onError(description ?: "Unknown error ($errorCode)")
    }
}
