package com.campsite.kiosk

/**
 * KioskConfig
 *
 * Single source of truth for all runtime constants.
 * Change BASE_URL here and nowhere else when deploying to a new environment.
 */
object KioskConfig {

    // ── URLs ──────────────────────────────────────────────────────────────────

    /** Entry point loaded on cold start. */
    const val BASE_URL: String = "https://win.areacamperbergamo.it/semi_auto/main/html/checkout.html"

    /**
     * Origin prefix used by KioskWebViewClient to allow navigation.
     * Any URL that does NOT start with this string is silently blocked.
     */
    const val ALLOWED_ORIGIN: String = "https://win.areacamperbergamo.it"

    // ── JS Bridge ─────────────────────────────────────────────────────────────

    /** Name under which JsBridge is exposed to JavaScript: window.KioskBridge */
    const val JS_BRIDGE_NAME: String = "KioskBridge"

    // ── User Agent ────────────────────────────────────────────────────────────

    /**
     * Custom UA string.
     * - Identifies the kiosk to the backend for potential server-side branching.
     * - Strips the default Android/Chrome UA to reduce fingerprinting surface.
     */
    const val USER_AGENT: String = "CampsiteKiosk/1.0"

    // ── Inactivity ────────────────────────────────────────────────────────────

    /** Milliseconds of inactivity before the JS layer triggers a session reset. */
    const val INACTIVITY_TIMEOUT_MS: Long = 60_000L

    // ── Connectivity watchdog ─────────────────────────────────────────────────

    /** How long (ms) network must be absent before the no-connection overlay fires. */
    const val NETWORK_LOSS_GRACE_MS: Long = 30_000L

    /** Heartbeat ping interval in milliseconds. */
    const val HEARTBEAT_INTERVAL_MS: Long = 300_000L   // 5 minutes

    // ── Stripe Terminal ───────────────────────────────────────────────────────

    /** Backend endpoint that returns a Stripe Terminal connection token secret. */
    const val CONNECTION_TOKEN_URL: String =
        "https://win.areacamperbergamo.it/semi_auto/app/api/connection-token.php"

    /**
     * Serial number of the WisePOS E reader to auto-connect.
     * Set this to the serial printed on the reader label.
     * Leave empty ("") to connect to the first discovered reader instead.
     */
    const val READER_SERIAL: String = ""

    /** Stripe Terminal location ID (Dashboard → Terminal → Locations). */
    /** Injected at build time from local.properties — never hardcoded. */
    val TERMINAL_LOCATION_ID: String get() = BuildConfig.TML_LOCATION_ID
}