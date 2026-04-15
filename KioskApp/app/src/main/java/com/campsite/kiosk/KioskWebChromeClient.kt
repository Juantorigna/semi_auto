package com.campsite.kiosk

import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.JsResult
import android.webkit.WebChromeClient
import android.webkit.WebView

/**
 * KioskWebChromeClient
 *
 *  - Forwards console.log/warn/error from WebView JS to Logcat (debug aid)
 *  - Blocks JS alert/confirm/prompt dialogs (kiosk UI handles all feedback natively)
 */
class KioskWebChromeClient : WebChromeClient() {

    companion object {
        private const val TAG = "KioskJS"
    }

    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
        val level = when (consoleMessage.messageLevel()) {
            ConsoleMessage.MessageLevel.ERROR -> Log.ERROR
            ConsoleMessage.MessageLevel.WARNING -> Log.WARN
            else -> Log.DEBUG
        }
        Log.println(
            level,
            TAG,
            "${consoleMessage.message()} [${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}]"
        )
        return true                                             // consumed — don't show default UI
    }

    // Block JS dialogs — kiosk must not surface native OS popups
    override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
        result?.confirm()
        return true
    }

    override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
        result?.cancel()
        return true
    }
}
