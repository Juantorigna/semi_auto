package com.campsite.kiosk

import android.app.Application
import android.util.Log
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.external.callable.Callback
import com.stripe.stripeterminal.external.callable.Cancelable
import com.stripe.stripeterminal.external.callable.DiscoveryListener
import com.stripe.stripeterminal.external.callable.PaymentIntentCallback
import com.stripe.stripeterminal.external.callable.ReaderCallback
import com.stripe.stripeterminal.external.callable.TerminalListener
import com.stripe.stripeterminal.external.models.CollectConfiguration
import com.stripe.stripeterminal.external.models.ConnectionConfiguration
import com.stripe.stripeterminal.external.models.DiscoveryConfiguration
import com.stripe.stripeterminal.external.models.PaymentIntent
import com.stripe.stripeterminal.external.models.PaymentIntentParameters
import com.stripe.stripeterminal.external.models.PaymentStatus
import com.stripe.stripeterminal.external.models.Reader
import com.stripe.stripeterminal.external.models.TerminalException
import com.stripe.stripeterminal.log.LogLevel

/**
 * TerminalManager  (Steps 4 + 5 + 7)
 *
 * Single object that owns the full Stripe Terminal lifecycle:
 *   – SDK initialisation (called once from KioskApplication.onCreate)
 *   – WisePOS E discovery + auto-connect (Internet reader, no BLE)
 *   – PaymentIntent retrieve → collectPaymentMethod → confirm flow
 *   – Public surface consumed by JsBridge
 *
 * Thread safety: Terminal SDK callbacks arrive on a background thread.
 * Any UI work must be posted to the main thread by the caller (JsBridge does this).
 */
object TerminalManager {

    // ── Constants ─────────────────────────────────────────────────────────────

    private const val TAG = "TerminalManager"

    // ── State ─────────────────────────────────────────────────────────────────

    /** Set to non-null by [cancelPayment] so in-flight collection can be aborted. */
    @Volatile private var collectCancelable: Cancelable? = null

    /** Discovery operation; cancelled once a reader connects. */
    @Volatile private var discoveryCancelable: Cancelable? = null

    // ── Public read ───────────────────────────────────────────────────────────

    val isInitialized: Boolean get() = Terminal.isInitialized()

    val connectedReader: Reader? get() =
        if (Terminal.isInitialized()) Terminal.getInstance().connectedReader else null

    val readerStatus: String
        get() = when {
            !Terminal.isInitialized()              -> "not_initialized"
            connectedReader != null                -> "connected"
            else                                   -> "disconnected"
        }

    // ── Step 4: SDK init ─────────────────────────────────────────────────────

    /**
     * Initialise the Terminal SDK.
     * Must be called exactly once from [KioskApplication.onCreate].
     * Safe to call if already initialised — logs and returns.
     */
    fun init(app: Application) {
        if (Terminal.isInitialized()) {
            Log.i(TAG, "Terminal already initialised — skipping")
            return
        }

        val logLevel = if (BuildConfig.DEBUG) LogLevel.VERBOSE else LogLevel.NONE

        Terminal.initTerminal(
            context               = app,
            logLevel              = logLevel,
            tokenProvider         = TerminalTokenProvider(),
            listener              = KioskTerminalListener()
        )

        Log.i(TAG, "Terminal SDK initialised (logLevel=$logLevel)")

        // Kick off reader discovery immediately after init
        startDiscovery()
    }

    // ── Step 5: Discovery + connect ──────────────────────────────────────────

    /**
     * Discover WisePOS E readers via INTERNET transport.
     * Auto-connects to the reader matching [KioskConfig.READER_SERIAL],
     * or to the first discovered reader if READER_SERIAL is blank.
     *
     * Safe to call even if already connected — returns early.
     */
    fun startDiscovery() {
        if (!Terminal.isInitialized()) {
            Log.w(TAG, "startDiscovery: Terminal not initialised")
            return
        }

        if (connectedReader != null) {
            Log.i(TAG, "startDiscovery: reader already connected, skipping")
            return
        }

        // Cancel any previous discovery loop
        discoveryCancelable?.cancel(object : Callback {
            override fun onSuccess() { Log.d(TAG, "Previous discovery cancelled") }
            override fun onFailure(e: TerminalException) { /* ignore */ }
        })

        val config = DiscoveryConfiguration.InternetDiscoveryConfiguration(
            isSimulated = false,
            location    = KioskConfig.TERMINAL_LOCATION_ID.ifEmpty { null }
        )

        Log.i(TAG, "Starting Internet reader discovery…")

        discoveryCancelable = Terminal.getInstance().discoverReaders(
            config    = config,
            listener  = ReaderDiscoveryListener(),
            callback  = object : Callback {
                override fun onSuccess() {
                    Log.i(TAG, "Discovery finished")
                    discoveryCancelable = null
                }
                override fun onFailure(e: TerminalException) {
                    Log.e(TAG, "Discovery failed: ${e.errorMessage}", e)
                    discoveryCancelable = null
                }
            }
        )
    }

    /**
     * Connect to a specific reader using INTERNET transport (WisePOS E).
     */
    private fun connectToReader(reader: Reader) {
        val locationId = KioskConfig.TERMINAL_LOCATION_ID.ifEmpty {
            reader.location?.id
        }

        if (locationId.isNullOrEmpty()) {
            Log.e(TAG, "connectToReader: no location ID available — cannot connect")
            return
        }

        val config = ConnectionConfiguration.InternetConnectionConfiguration(
            locationId    = locationId,
            failIfInUse   = false
        )

        Log.i(TAG, "Connecting to reader: ${reader.serialNumber}")

        Terminal.getInstance().connectInternetReader(
            reader   = reader,
            config   = config,
            callback = object : ReaderCallback {
                override fun onSuccess(reader: Reader) {
                    Log.i(TAG, "Reader connected: ${reader.serialNumber} (${reader.label})")
                    discoveryCancelable?.cancel(object : Callback {
                        override fun onSuccess() {}
                        override fun onFailure(e: TerminalException) {}
                    })
                    discoveryCancelable = null
                }
                override fun onFailure(e: TerminalException) {
                    Log.e(TAG, "Reader connect failed: ${e.errorMessage}", e)
                }
            }
        )
    }

    // ── Step 7: Payment flow ─────────────────────────────────────────────────

    /**
     * Full payment flow:
     *   1. Retrieve PaymentIntent by [clientSecret]
     *   2. collectPaymentMethod
     *   3. confirmPaymentIntent
     *
     * Callbacks [onSuccess] and [onFailure] are invoked on a background thread;
     * callers (JsBridge) must dispatch to UI thread themselves.
     *
     * @param clientSecret     From create-payment-intent.php response
     * @param onSuccess        Invoked with PaymentIntent ID on successful confirmation
     * @param onFailure        Invoked with human-readable error message on any failure
     */
    fun processPayment(
        clientSecret: String,
        onSuccess: (paymentIntentId: String) -> Unit,
        onFailure: (errorMessage: String) -> Unit
    ) {
        if (!Terminal.isInitialized()) {
            onFailure("Terminal not initialised")
            return
        }

        if (connectedReader == null) {
            onFailure("No reader connected")
            return
        }

        Log.d(TAG, "Retrieving PaymentIntent…")

        Terminal.getInstance().retrievePaymentIntent(
            clientSecret = clientSecret,
            callback     = object : PaymentIntentCallback {

                override fun onSuccess(paymentIntent: PaymentIntent) {
                    Log.d(TAG, "PaymentIntent retrieved: ${paymentIntent.id}")
                    collectPayment(paymentIntent, onSuccess, onFailure)
                }

                override fun onFailure(e: TerminalException) {
                    Log.e(TAG, "retrievePaymentIntent failed: ${e.errorMessage}", e)
                    onFailure(e.errorMessage)
                }
            }
        )
    }

    private fun collectPayment(
        paymentIntent: PaymentIntent,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        Log.d(TAG, "Collecting payment method…")

        val collectConfig = CollectConfiguration.Builder().build()

        collectCancelable = Terminal.getInstance().collectPaymentMethod(
            paymentIntent = paymentIntent,
            config        = collectConfig,
            callback      = object : PaymentIntentCallback {

                override fun onSuccess(paymentIntent: PaymentIntent) {
                    collectCancelable = null
                    Log.d(TAG, "Payment method collected — confirming…")
                    confirmPayment(paymentIntent, onSuccess, onFailure)
                }

                override fun onFailure(e: TerminalException) {
                    collectCancelable = null
                    Log.e(TAG, "collectPaymentMethod failed: ${e.errorMessage}", e)
                    onFailure(e.errorMessage)
                }
            }
        )
    }

    private fun confirmPayment(
        paymentIntent: PaymentIntent,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        Terminal.getInstance().confirmPaymentIntent(
            paymentIntent = paymentIntent,
            callback      = object : PaymentIntentCallback {

                override fun onSuccess(paymentIntent: PaymentIntent) {
                    Log.i(TAG, "Payment confirmed: ${paymentIntent.id} status=${paymentIntent.status}")
                    when (paymentIntent.status) {
                        PaymentStatus.SUCCEEDED -> onSuccess(paymentIntent.id)
                        else -> onFailure("Unexpected status: ${paymentIntent.status}")
                    }
                }

                override fun onFailure(e: TerminalException) {
                    Log.e(TAG, "confirmPaymentIntent failed: ${e.errorMessage}", e)
                    onFailure(e.errorMessage)
                }
            }
        )
    }

    /**
     * Cancel an in-flight [collectPaymentMethod] operation.
     * Safe to call if nothing is in-flight — no-op.
     */
    fun cancelPayment() {
        val op = collectCancelable ?: run {
            Log.d(TAG, "cancelPayment: nothing to cancel")
            return
        }

        op.cancel(object : Callback {
            override fun onSuccess()                   { Log.i(TAG, "Payment collection cancelled") }
            override fun onFailure(e: TerminalException) { Log.w(TAG, "Cancel failed: ${e.errorMessage}") }
        })

        collectCancelable = null
    }

    // ── Discovery listener ────────────────────────────────────────────────────

    private class ReaderDiscoveryListener : DiscoveryListener {

        override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
            if (readers.isEmpty()) {
                Log.d(TAG, "Discovery: no readers found yet")
                return
            }

            Log.i(TAG, "Discovery: found ${readers.size} reader(s)")

            val target = if (KioskConfig.READER_SERIAL.isNotEmpty()) {
                readers.firstOrNull { it.serialNumber == KioskConfig.READER_SERIAL }
                    ?: run {
                        Log.w(TAG, "Configured serial ${KioskConfig.READER_SERIAL} not in list — waiting")
                        return
                    }
            } else {
                readers.first()
            }

            // Only connect if not already connecting or connected
            if (Terminal.getInstance().connectedReader == null) {
                connectToReader(target)
            }
        }
    }

    // ── Terminal lifecycle listener ───────────────────────────────────────────

    private class KioskTerminalListener : TerminalListener {

        override fun onUnexpectedReaderDisconnect(reader: Reader) {
            Log.w(TAG, "Unexpected reader disconnect: ${reader.serialNumber}")
            // Attempt automatic reconnect
            startDiscovery()
        }

        override fun onConnectionStatusChange(
            status: com.stripe.stripeterminal.external.models.ConnectionStatus
        ) {
            Log.i(TAG, "Connection status → $status")
        }

        override fun onPaymentStatusChange(status: PaymentStatus) {
            Log.i(TAG, "Payment status → $status")
        }
    }
}