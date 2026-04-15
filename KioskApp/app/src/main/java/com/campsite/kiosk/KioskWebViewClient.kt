package com.campsite.kiosk

import android.graphics.Bitmap
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.annotation.RequiresApi
import android.os.Build

class KioskWebViewClient(
    private val allowedOrigin: String,
    private val onPageStarted: () -> Unit,
    private val onPageFinished: () -> Unit,
    private val onNetworkError: () -> Unit
) : WebViewClient() {

    // ── Navigation guard ──────────────────────────────────────────────────────
    // Allow only URLs that start with the configured allowed origin.
    // Everything else (deep links, redirects to third-party domains) is blocked.

    override fun shouldOverrideUrlLoading(
        view: WebView,
        request: WebResourceRequest
    ): Boolean {
        val url = request.url.toString()
        return if (url.startsWith(allowedOrigin)) {
            false   // let WebView handle it
        } else {
            // Silently drop — no browser launch, no external navigation
            true
        }
    }

    // ── Page lifecycle ────────────────────────────────────────────────────────

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageStarted()
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        onPageFinished()
    }

    // ── Error handling — API 23+ ──────────────────────────────────────────────

    @RequiresApi(Build.VERSION_CODES.M)
    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        super.onReceivedError(view, request, error)

        // Only trigger the overlay for the main frame — subresource failures
        // (fonts, images) should not kill the kiosk UI.
        if (request.isForMainFrame) {
            onNetworkError()
        }
    }

    // ── HTTP error handling ───────────────────────────────────────────────────

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request.isForMainFrame) {
            onNetworkError()
        }
    }

    // ── SSL errors — abort, never proceed ────────────────────────────────────
    // Calling handler.proceed() on SSL errors would silently accept invalid
    // certificates. For a payment kiosk this is never acceptable.

    override fun onReceivedSslError(
        view: WebView,
        handler: SslErrorHandler,
        error: SslError
    ) {
        handler.cancel()    // always cancel — never handler.proceed()
        onNetworkError()
    }
}