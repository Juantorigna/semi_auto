package com.campsite.kiosk

import android.app.Application
import android.util.Log

/**
 * KioskApplication
 *
 * Application subclass.
 * Initialises the Stripe Terminal SDK once at process start via [TerminalManager].
 * Discovery starts automatically inside [TerminalManager.init] after SDK init.
 */
class KioskApplication : Application() {
    companion object { private const val TAG = "KioskApp" }

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "KioskApplication started")
        // Terminal init moved to MainActivity — needs location permission first
    }
}