package com.campsite.kiosk

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.external.callable.Callback
import com.stripe.stripeterminal.external.callable.Cancelable
import com.stripe.stripeterminal.external.callable.DiscoveryListener
import com.stripe.stripeterminal.external.callable.InternetReaderListener
import com.stripe.stripeterminal.external.callable.MobileReaderListener
import com.stripe.stripeterminal.external.callable.PaymentIntentCallback
import com.stripe.stripeterminal.external.callable.ReaderCallback
import com.stripe.stripeterminal.external.callable.TerminalListener
import com.stripe.stripeterminal.external.models.CollectConfiguration
import com.stripe.stripeterminal.external.models.ConnectionConfiguration
import com.stripe.stripeterminal.external.models.ConnectionStatus
import com.stripe.stripeterminal.external.models.DisconnectReason
import com.stripe.stripeterminal.external.models.DiscoveryConfiguration
import com.stripe.stripeterminal.external.models.PaymentIntent
import com.stripe.stripeterminal.external.models.PaymentIntentStatus
import com.stripe.stripeterminal.external.models.PaymentStatus
import com.stripe.stripeterminal.external.models.Reader
import com.stripe.stripeterminal.external.models.ReaderEvent
import com.stripe.stripeterminal.external.models.ReaderSoftwareUpdate
import com.stripe.stripeterminal.external.models.TerminalException
import com.stripe.stripeterminal.log.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "TerminalManager"

/**
 * TerminalManager
 *
 * Singleton that owns the full Stripe Terminal lifecycle:
 *   - SDK initialisation (called once from KioskApplication.onCreate)
 *   - Reader discovery + auto-connect (WisePOS E via Internet; WisePad 3 via BLE — inactive)
 *   - Payment flow: retrievePaymentIntent → collectPaymentMethod → confirmPaymentIntent
 *   - Cancel of in-flight collection
 *
 * Named parameters are used for all ConnectionConfiguration constructors to decouple
 * this code from positional-argument changes between SDK 4.x patch releases.
 *
 * @SuppressLint("MissingPermission") is applied at the object level because:
 *   1. Every BLE API call is preceded by hasBluetoothPermission(), which calls
 *      checkSelfPermission and early-returns on failure.
 *   2. Every BLE try-block catches SecurityException as a secondary safety net for
 *      the race where permission is revoked between the check and the SDK call.
 *   3. The lint engine cannot statically trace the guard when the SDK call sits
 *      inside an anonymous class body, so the annotation is the correct tool here.
 *   Internet-mode calls (WisePOS E) require no Bluetooth permission.
 */
@SuppressLint("MissingPermission")
object TerminalManager {

    // ── State exposed to JsBridge ─────────────────────────────────────────────

    @Volatile
    var readerStatus: String = "disconnected"
        private set

    // ── Internal state ────────────────────────────────────────────────────────

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Held so callers can cancel an in-progress collectPaymentMethod. */
    @Volatile
    private var collectCancelable: Cancelable? = null

    /** Guards against concurrent discovery attempts. */
    @Volatile
    private var discoveryInProgress: Boolean = false

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Called once from KioskApplication.onCreate.
     * Initialises the Terminal SDK and immediately starts reader discovery.
     *
     * Named params used on initTerminal to be resilient against SDK overload ordering.
     */
    fun init(context: Context) {
        appContext = context.applicationContext

        if (Terminal.isInitialized()) {
            Log.d(TAG, "Terminal already initialised — skipping init, starting discovery")
            startDiscovery()
            return
        }

        val terminalListener = object : TerminalListener {
            override fun onConnectionStatusChange(status: ConnectionStatus) {
                Log.d(TAG, "Connection status changed: $status")
            }

            override fun onPaymentStatusChange(status: PaymentStatus) {
                Log.d(TAG, "Payment status changed: $status")
            }
        }

        Terminal.initTerminal(
            context = appContext,
            logLevel = LogLevel.VERBOSE,
            tokenProvider = TerminalTokenProvider(),
            listener = terminalListener
        )

        Log.i(TAG, "Terminal SDK initialised")
        startDiscovery()
    }

    // ── Discovery entry point ─────────────────────────────────────────────────

    /**
     * WisePOS E (Internet) is the primary and only active path for this kiosk.
     * BLE path (WisePad 3) is implemented but not called.
     */
    private fun startDiscovery() {
        if (discoveryInProgress) {
            Log.d(TAG, "Discovery already in progress — skipping")
            return
        }
        discoverInternet()
    }

    // ── Internet discovery (WisePOS E) ────────────────────────────────────────

    private fun discoverInternet() {
        discoveryInProgress = true
        updateStatus("discovering")

        val config = DiscoveryConfiguration.InternetDiscoveryConfiguration(isSimulated = false)

        Terminal.getInstance().discoverReaders(
            config = config,
            discoveryListener = object : DiscoveryListener {
                override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                    val target = pickReader(readers) ?: run {
                        Log.d(TAG, "No matching reader in discovered list (${readers.size} found)")
                        return
                    }
                    connectInternet(target)
                }
            },
            callback = object : Callback {
                override fun onSuccess() {
                    Log.d(TAG, "Internet discovery scan complete")
                    discoveryInProgress = false
                }

                override fun onFailure(e: TerminalException) {
                    Log.e(TAG, "Internet discovery failed: ${e.errorMessage}")
                    discoveryInProgress = false
                    updateStatus("disconnected")
                }
            }
        )
    }

    /**
     * Named parameters used on InternetConnectionConfiguration to be resilient
     * against positional-argument changes between SDK 4.x patch releases.
     * This was the root cause of the "Argument type mismatch" compile error.
     */
    private fun connectInternet(reader: Reader) {
        val config = ConnectionConfiguration.InternetConnectionConfiguration(
            internetReaderListener = internetReaderListener,
            failIfInUse = true
        )

        Terminal.getInstance().connectReader(
            reader = reader,
            config = config,
            callback = object : ReaderCallback {
                override fun onSuccess(reader: Reader) {
                    Log.i(TAG, "Internet reader connected: ${reader.serialNumber}")
                    discoveryInProgress = false
                    updateStatus("connected")
                }

                override fun onFailure(e: TerminalException) {
                    Log.e(TAG, "Internet reader connect failed: ${e.errorMessage}")
                    discoveryInProgress = false
                    updateStatus("disconnected")
                }
            }
        )
    }

    // ── Bluetooth discovery (WisePad 3) — NOT USED for this kiosk ────────────

    @Suppress("unused")
    private fun discoverBluetooth() {
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "BLE permission not granted — skipping Bluetooth discovery")
            updateStatus("permission_denied")
            return
        }

        discoveryInProgress = true
        updateStatus("discovering")

        val config = DiscoveryConfiguration.BluetoothDiscoveryConfiguration(
            timeout = 60,
            isSimulated = false
        )

        try {
            Terminal.getInstance().discoverReaders(
                config = config,
                discoveryListener = object : DiscoveryListener {
                    override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                        val target = pickReader(readers) ?: run {
                            Log.d(TAG, "No matching BLE reader found")
                            return
                        }
                        connectBluetooth(target)
                    }
                },
                callback = object : Callback {
                    override fun onSuccess() {
                        Log.d(TAG, "BLE discovery scan complete")
                        discoveryInProgress = false
                    }

                    override fun onFailure(e: TerminalException) {
                        Log.e(TAG, "BLE discovery failed: ${e.errorMessage}")
                        discoveryInProgress = false
                        updateStatus("disconnected")
                    }
                }
            )
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException during BLE discovery: ${se.message}")
            discoveryInProgress = false
            updateStatus("permission_denied")
        }
    }

    /**
     * Named parameters used on BluetoothConnectionConfiguration for the same
     * resilience reason as InternetConnectionConfiguration above.
     * locationId is still required for BLE in SDK 4.x.
     */
    @Suppress("unused")
    private fun connectBluetooth(reader: Reader) {
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "BLE permission lost before connect — aborting")
            updateStatus("permission_denied")
            return
        }

        val locationId = KioskConfig.TERMINAL_LOCATION_ID.ifBlank {
            Log.e(TAG, "TERMINAL_LOCATION_ID is blank — cannot connect BLE reader")
            updateStatus("config_error")
            return
        }

        val config = ConnectionConfiguration.BluetoothConnectionConfiguration(
            locationId = locationId,
            autoReconnectOnUnexpectedDisconnect = true,
            bluetoothReaderListener = mobileReaderListener
        )

        try {
            Terminal.getInstance().connectReader(
                reader = reader,
                config = config,
                callback = object : ReaderCallback {
                    override fun onSuccess(reader: Reader) {
                        Log.i(TAG, "BLE reader connected: ${reader.serialNumber}")
                        updateStatus("connected")
                    }

                    override fun onFailure(e: TerminalException) {
                        Log.e(TAG, "BLE reader connect failed: ${e.errorMessage}")
                        updateStatus("disconnected")
                    }
                }
            )
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException during BLE connect: ${se.message}")
            updateStatus("permission_denied")
        }
    }

    // ── Payment flow ──────────────────────────────────────────────────────────

    /**
     * Full payment sequence:
     *   retrievePaymentIntent → collectPaymentMethod → confirmPaymentIntent
     *
     * clientSecret originates server-side from create-payment-intent.php.
     * Amount is never read from or trusted on the client side.
     *
     * @param clientSecret  The client_secret returned by the server's PaymentIntent.
     * @param onSuccess     Called with the PaymentIntent ID on confirmed success.
     * @param onFailure     Called with a human-readable error string on any failure.
     */
    fun processPayment(
        clientSecret: String,
        onSuccess: (paymentIntentId: String) -> Unit,
        onFailure: (message: String) -> Unit
    ) {
        if (readerStatus != "connected") {
            Log.w(TAG, "processPayment called but reader not connected (status=$readerStatus)")
            onFailure("reader_not_connected")
            return
        }

        scope.launch {
            Terminal.getInstance().retrievePaymentIntent(
                clientSecret = clientSecret,
                callback = object : PaymentIntentCallback {
                    override fun onSuccess(paymentIntent: PaymentIntent) {
                        collectPayment(paymentIntent, onSuccess, onFailure)
                    }

                    override fun onFailure(e: TerminalException) {
                        Log.e(TAG, "retrievePaymentIntent failed: ${e.errorMessage}")
                        onFailure(e.errorMessage ?: "retrieve_failed")
                    }
                }
            )
        }
    }

    private fun collectPayment(
        paymentIntent: PaymentIntent,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        val collectConfig = CollectConfiguration.Builder().build()

        collectCancelable = Terminal.getInstance().collectPaymentMethod(
            paymentIntent = paymentIntent,
            paymentIntentCallback = object : PaymentIntentCallback {
                override fun onSuccess(updatedIntent: PaymentIntent) {
                    collectCancelable = null
                    confirmPayment(updatedIntent, onSuccess, onFailure)
                }

                override fun onFailure(e: TerminalException) {
                    collectCancelable = null
                    Log.e(TAG, "collectPaymentMethod failed: ${e.errorMessage}")
                    onFailure(e.errorMessage ?: "collect_failed")
                }
            },
            collectConfig = collectConfig
        )
    }

    private fun confirmPayment(
        paymentIntent: PaymentIntent,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        // SDK 4.x confirmPaymentIntent returns a Cancelable — not needed for confirm.
        @Suppress("UNUSED_VARIABLE")
        val confirmCancelable = Terminal.getInstance().confirmPaymentIntent(
            paymentIntent = paymentIntent,
            callback = object : PaymentIntentCallback {
                override fun onSuccess(confirmedIntent: PaymentIntent) {
                    when (confirmedIntent.status) {
                        PaymentIntentStatus.SUCCEEDED -> {
                            val piId = confirmedIntent.id ?: run {
                                Log.w(TAG, "PaymentIntent.id null after successful confirm")
                                "unknown"
                            }
                            Log.i(TAG, "Payment succeeded: $piId")
                            onSuccess(piId)
                        }
                        else -> {
                            val status = confirmedIntent.status
                            Log.w(TAG, "Unexpected status after confirm: $status")
                            onFailure("unexpected_status_$status")
                        }
                    }
                }

                override fun onFailure(e: TerminalException) {
                    Log.e(TAG, "confirmPaymentIntent failed: ${e.errorMessage}")
                    onFailure(e.errorMessage ?: "confirm_failed")
                }
            }
        )
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    /**
     * Cancels an in-progress collectPaymentMethod operation.
     * Safe to call when no collection is active — logs and returns silently.
     */
    fun cancelPayment() {
        val cancelable = collectCancelable ?: run {
            Log.d(TAG, "cancelPayment called — no active collect operation")
            return
        }

        cancelable.cancel(object : Callback {
            override fun onSuccess() {
                Log.d(TAG, "Payment collection cancelled successfully")
                collectCancelable = null
            }

            override fun onFailure(e: TerminalException) {
                Log.e(TAG, "Cancel failed: ${e.errorMessage}")
                // collectCancelable left in place; caller may retry
            }
        })
    }

    // ── Listener objects ──────────────────────────────────────────────────────

    private val internetReaderListener = object : InternetReaderListener {
        override fun onDisconnect(reason: DisconnectReason) {
            Log.w(TAG, "Internet reader disconnected: $reason")
            updateStatus("disconnected")
            scheduleReconnect()
        }
    }

    private val mobileReaderListener = object : MobileReaderListener {

        override fun onDisconnect(reason: DisconnectReason) {
            Log.w(TAG, "BLE reader disconnected: $reason")
            updateStatus("disconnected")
        }

        override fun onReportReaderEvent(event: ReaderEvent) {
            Log.d(TAG, "BLE reader event: $event")
        }

        override fun onReportLowBatteryWarning() {
            Log.w(TAG, "BLE reader battery low")
        }

        override fun onStartInstallingUpdate(
            update: ReaderSoftwareUpdate,
            cancelable: Cancelable?
        ) {
            Log.i(TAG, "BLE reader firmware update started: ${update.version}")
        }

        override fun onReportReaderSoftwareUpdateProgress(progress: Float) {
            Log.d(TAG, "BLE firmware update: ${(progress * 100).toInt()}%")
        }

        override fun onFinishInstallingUpdate(
            update: ReaderSoftwareUpdate?,
            e: TerminalException?
        ) {
            if (e != null) {
                Log.e(TAG, "BLE firmware update failed: ${e.errorMessage}")
            } else {
                Log.i(TAG, "BLE firmware update complete: ${update?.version}")
            }
        }

        override fun onReaderReconnectStarted(
            reader: Reader,
            cancelReconnect: Cancelable,
            reason: DisconnectReason
        ) {
            Log.i(TAG, "BLE reader reconnecting: ${reader.serialNumber}, reason: $reason")
            updateStatus("reconnecting")
        }

        override fun onReaderReconnectSucceeded(reader: Reader) {
            Log.i(TAG, "BLE reader reconnected: ${reader.serialNumber}")
            updateStatus("connected")
        }

        override fun onReaderReconnectFailed(reader: Reader) {
            Log.e(TAG, "BLE reader reconnect failed: ${reader.serialNumber}")
            updateStatus("disconnected")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Picks a reader by serial number if KioskConfig.READER_SERIAL is set,
     * otherwise picks the first reader in the discovered list.
     */
    private fun pickReader(readers: List<Reader>): Reader? {
        return if (KioskConfig.READER_SERIAL.isNotBlank()) {
            readers.firstOrNull { it.serialNumber == KioskConfig.READER_SERIAL }.also {
                if (it == null) Log.w(TAG, "Target serial ${KioskConfig.READER_SERIAL} not found in ${readers.size} readers")
            }
        } else {
            readers.firstOrNull()
        }
    }

    /**
     * Attempts to reconnect the Internet reader after an unexpected disconnect.
     * Debounced via discoveryInProgress flag so parallel calls collapse.
     */
    private fun scheduleReconnect() {
        if (discoveryInProgress) return
        Log.i(TAG, "Scheduling reconnect attempt")
        scope.launch {
            kotlinx.coroutines.delay(5_000L)
            startDiscovery()
        }
    }

    /**
     * Checks BLE permission for the running API level:
     *   API 31+ (Android 12+): BLUETOOTH_SCAN
     *   Below API 31:          ACCESS_FINE_LOCATION (required for BLE scan on older devices)
     */
    private fun hasBluetoothPermission(): Boolean {
        val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        return ContextCompat.checkSelfPermission(appContext, permission) ==
                PackageManager.PERMISSION_GRANTED
    }

    private fun updateStatus(status: String) {
        readerStatus = status
        Log.d(TAG, "readerStatus → $status")
    }
}