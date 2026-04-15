package com.campsite.kiosk

import android.app.Application
import android.util.Log

/**
 * KioskApplication
 *
 * Application subclass.
 * Currently minimal — declared now so Step 4 (Stripe Terminal SDK init)
 * can call Terminal.initTerminal() here in onCreate() without refactoring.
 */
class KioskApplication : Application() {

    companion object {
        private const val TAG = "KioskApp"
    }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "KioskApplication started")
        // Step 4: Terminal.initTerminal(this, TerminalListener, ConnectionTokenProvider) goes here
    }
}