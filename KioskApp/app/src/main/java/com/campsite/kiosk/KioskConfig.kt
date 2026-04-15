package com.campsite.kiosk

/**
 * KioskConfig
 *
 * Single source of truth for environment-specific values.
 * In production builds, supply via BuildConfig or a secrets file — never hard-code credentials here.
 */
object KioskConfig {

    /**
     * Base URL of the Aruba-hosted backend.
     * Replace with actual subdomain before deployment.
     * Must be HTTPS — cleartext blocked by network_security_config.xml.
     */
    const val BASE_URL: String = "https://win.areacamperbergamo.it/semi_auto/"

    /**
     * Allowed origin for navigation guard in KioskWebViewClient.
     * Must match BASE_URL host exactly.
     */
    const val ALLOWED_ORIGIN: String = BASE_URL

    /**
     * WebView JS interface name.
     * Must match the string used in JS: window.KioskBridge.*
     */
    const val JS_INTERFACE_NAME: String = JsBridge.JS_INTERFACE_NAME

    /**
     * Inactivity timeout in milliseconds.
     * If user takes no action for this period, WebView resets to BASE_URL.
     * Wired in MainActivity in a later step.
     */
    const val INACTIVITY_TIMEOUT_MS: Long = 90_000L              // 90 seconds
}
