package com.campsite.kiosk

import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.util.Log

class KioskWebViewClient(
    private val allowedOrigin: String,
    private val onPageStarted: () -> Unit = {},
    private val onPageFinished: () -> Unit = {},
    private val onError: (String) -> Unit = {}
) : WebViewClient() {

    companion object {
        private const val TAG = "KioskWebViewClient"
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val uri = request.url ?: return true
        return !isSameOrigin(uri)
    }

    private fun isSameOrigin(uri: Uri): Boolean {
        val requestedHost = uri.host ?: return false
        val allowedHost = Uri.parse(allowedOrigin).host ?: return false
        return requestedHost.equals(allowedHost, ignoreCase = true)
    }

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
        onError(description ?: "Error $errorCode")
    }
}