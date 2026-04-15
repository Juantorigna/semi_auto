package com.campsite.kiosk

import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * KioskWebChromeClient
 *
 * - Pipes JS console.log / warn / error → Logcat (visible in Android Studio / adb logcat)
 * - Suppresses all native JS dialogs (alert / confirm / prompt).
 *   These would freeze the kiosk UI waiting for user interaction that never comes.
 */
class KioskWebChromeClient : WebChromeClient() {

    companion object {
        private const val TAG = "KioskJS"
    }

    // ── Console bridging ──────────────────────────────────────────────────────

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        val level = consoleMessage.messageLevel()
        val msg   = "[${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}] ${consoleMessage.message()}"

        when (level) {
            ConsoleMessage.MessageLevel.ERROR   -> Log.e(TAG, msg)
            ConsoleMessage.MessageLevel.WARNING -> Log.w(TAG, msg)
            ConsoleMessage.MessageLevel.DEBUG   -> Log.d(TAG, msg)
            else                                -> Log.i(TAG, msg)
        }

        return true     // handled — suppress default WebView behaviour
    }

    // ── Dialog suppression ────────────────────────────────────────────────────
    // All three must be overridden. Each must call result.cancel() (or
    // result.confirm() for confirm) to unblock WebView; without this
    // the page thread hangs indefinitely.

    override fun onJsAlert(
        view: WebView, url: String, message: String, result: JsResult
    ): Boolean {
        Log.w(TAG, "Suppressed JS alert: $message")
        result.cancel()
        return true
    }

    override fun onJsConfirm(
        view: WebView, url: String, message: String, result: JsResult
    ): Boolean {
        Log.w(TAG, "Suppressed JS confirm: $message")
        result.cancel()
        return true
    }

    override fun onJsPrompt(
        view: WebView, url: String, message: String,
        defaultValue: String?, result: JsPromptResult
    ): Boolean {
        Log.w(TAG, "Suppressed JS prompt: $message")
        result.cancel()
        return true
    }

    // ── Progress ──────────────────────────────────────────────────────────────
    // Optional: useful during development to see load progress in Logcat.

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        super.onProgressChanged(view, newProgress)
        if (newProgress == 100) Log.d(TAG, "Page load complete")
    }
}