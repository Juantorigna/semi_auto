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
 *   - Reader discovery + auto-connect (WisePOS E via Internet; WisePad 3 via BLE)
 *   - Payment flow: retrievePaymentIntent → collectPaymentMethod → confirmPaymentIntent
 *   - Cancel of in-flight collection
 *
 * @SuppressLint("MissingPermission") is applied at the object level because:
 *   1. Every BLE API call is preceded by hasBluetoothPermission() which calls
 *      checkSelfPermission and early-returns on failure.
 *   2. Every BLE try-block catches SecurityException as a secondary safety net for
 *      the race where permission is revoked between the check and the SDK call.
 *   3. The lint engine cannot statically trace the guard when the SDK call
 *      sits inside an anonymous class body, so the annotation is the correct tool here.
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

    // ── Initialisation ────────────────────────────────────────────────────────

    /**
     * Called once from KioskApplication.onCreate.
     * Initialises the Terminal SDK and immediately starts reader discovery.
     *
     * SDK 4.x initTerminal positional signature (@JvmOverloads — LogLevel cannot be skipped):
     *   initTerminal(context, logLevel, tokenProvider, listener)
     *
     * SDK 4.x TerminalListener no longer has onUnexpectedReaderDisconnect.
     * Disconnect events are delegated to InternetReaderListener / MobileReaderListener.
     */
    fun init(context: Context) {
        appContext = context.applicationContext

        if (Terminal.isInitialized()) {
            Log.d(TAG, "Terminal already initialised — skipping init")
            startDiscovery()
            return
        }

        val terminalListener = object : TerminalListener {
            override fun onConnectionStatusChange(status: ConnectionStatus) {
                Log.d(TAG, "Terminal connection status: $status")
            }

            override fun onPaymentStatusChange(status: PaymentStatus) {
                Log.d(TAG, "Terminal payment status: $status")
            }
        }

        Terminal.initTerminal(
            appContext,
            LogLevel.VERBOSE,
            TerminalTokenProvider(),
            terminalListener
        )

        Log.i(TAG, "Terminal SDK initialised")
        startDiscovery()
    }

    // ── Discovery entry point ─────────────────────────────────────────────────

    /**
     * WisePOS E (Internet) is the default — no Bluetooth required.
     * Bluetooth path activates only when TERMINAL_LOCATION_ID is set in KioskConfig,
     * which is required only for WisePad 3 (BLE reader).
     */
    private fun startDiscovery() {
        if (KioskConfig.TERMINAL_LOCATION_ID.isNotBlank()) {
            discoverBluetooth()
        } else {
            discoverInternet()
        }
    }

    // ── Internet discovery (WisePOS E) ────────────────────────────────────────

    private fun discoverInternet() {
        val config = DiscoveryConfiguration.InternetDiscoveryConfiguration(isSimulated = false)

        Terminal.getInstance().discoverReaders(
            config,
            object : DiscoveryListener {
                override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                    val target = pickReader(readers) ?: return
                    connectInternet(target)
                }
            },
            object : Callback {
                override fun onSuccess() {
                    Log.d(TAG, "Internet discovery complete")
                }

                override fun onFailure(e: TerminalException) {
                    Log.e(TAG, "Internet discovery failed: ${e.errorMessage}")
                    updateStatus("disconnected")
                }
            }
        )
    }

    private fun connectInternet(reader: Reader) {
        val config = ConnectionConfiguration.InternetConnectionConfiguration(
            internetReaderListener = internetReaderListener,
            failIfInUse = true
        )

        Terminal.getInstance().connectReader(
            reader,
            config,
            object : ReaderCallback {
                override fun onSuccess(reader: Reader) {
                    Log.i(TAG, "Internet reader connected: ${reader.serialNumber}")
                    updateStatus("connected")
                }

                override fun onFailure(e: TerminalException) {
                    Log.e(TAG, "Internet reader connect failed: ${e.errorMessage}")
                    updateStatus("disconnected")
                }
            }
        )
    }

    // ── Bluetooth discovery (WisePad 3) ───────────────────────────────────────

    private fun discoverBluetooth() {
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "BLE permission not granted — skipping Bluetooth discovery")
            updateStatus("permission_denied")
            return
        }

        val config = DiscoveryConfiguration.BluetoothDiscoveryConfiguration(
            timeout = 60,
            isSimulated = false
        )

        try {
            Terminal.getInstance().discoverReaders(
                config,
                object : DiscoveryListener {
                    override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                        val target = pickReader(readers) ?: return
                        connectBluetooth(target)
                    }
                },
                object : Callback {
                    override fun onSuccess() {
                        Log.d(TAG, "BLE discovery complete")
                    }

                    override fun onFailure(e: TerminalException) {
                        Log.e(TAG, "BLE discovery failed: ${e.errorMessage}")
                        updateStatus("disconnected")
                    }
                }
            )
        } catch (se: SecurityException) {
            // Race: permission revoked between hasBluetoothPermission() and SDK call
            Log.e(TAG, "SecurityException during BLE discovery: ${se.message}")
            updateStatus("permission_denied")
        }
    }

    private fun connectBluetooth(reader: Reader) {
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "BLE permission lost before connect — aborting")
            updateStatus("permission_denied")
            return
        }

        val locationId = KioskConfig.TERMINAL_LOCATION_ID.ifBlank {
            Log.e(TAG, "TERMINAL_LOCATION_ID blank — cannot connect BLE reader")
            updateStatus("config_error")
            return
        }

        val config = ConnectionConfiguration.BluetoothConnectionConfiguration(
            locationId = locationId,
            bluetoothReaderListener = mobileReaderListener
        )

        try {
            Terminal.getInstance().connectReader(
                reader,
                config,
                object : ReaderCallback {
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
     * clientSecret originates server-side (create-payment-intent.php).
     * Amount is never read from or trusted on the client.
     */
    fun processPayment(
        clientSecret: String,
        onSuccess: (paymentIntentId: String) -> Unit,
        onFailure: (message: String) -> Unit
    ) {
        scope.launch {
            Terminal.getInstance().retrievePaymentIntent(
                clientSecret,
                object : PaymentIntentCallback {
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
            paymentIntent,
            object : PaymentIntentCallback {
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
            collectConfig
        )
    }

    private fun confirmPayment(
        paymentIntent: PaymentIntent,
        onSuccess: (String) -> Unit,
        onFailure: (String) -> Unit
    ) {
        // SDK 4.x: confirmPaymentIntent returns a Cancelable — suppressed, not needed here.
        @Suppress("UNUSED_VARIABLE")
        val confirmCancelable = Terminal.getInstance().confirmPaymentIntent(
            paymentIntent,
            object : PaymentIntentCallback {
                override fun onSuccess(confirmedIntent: PaymentIntent) {
                    if (confirmedIntent.status == PaymentIntentStatus.SUCCEEDED) {
                        val piId = confirmedIntent.id ?: run {
                            Log.w(TAG, "PaymentIntent.id null after confirm")
                            "unknown"
                        }
                        Log.i(TAG, "Payment succeeded: $piId")
                        onSuccess(piId)
                    } else {
                        val status = confirmedIntent.status
                        Log.w(TAG, "Unexpected status after confirm: $status")
                        onFailure("unexpected_status_$status")
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

    fun cancelPayment() {
        val cancelable = collectCancelable
        if (cancelable == null) {
            Log.d(TAG, "cancelPayment — no active collect operation")
            return
        }

        cancelable.cancel(object : Callback {
            override fun onSuccess() {
                Log.d(TAG, "Payment collection cancelled by user")
                collectCancelable = null
            }

            override fun onFailure(e: TerminalException) {
                Log.e(TAG, "Cancel failed: ${e.errorMessage}")
            }
        })
    }

    // ── Listener objects ──────────────────────────────────────────────────────

    private val internetReaderListener = object : InternetReaderListener {
        override fun onDisconnect(reason: DisconnectReason) {
            Log.w(TAG, "Internet reader disconnected: $reason")
            updateStatus("disconnected")
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
            Log.w(TAG, "BLE reader low battery")
        }

        override fun onStartInstallingUpdate(
            update: ReaderSoftwareUpdate,
            cancelable: Cancelable?
        ) {
            Log.i(TAG, "BLE reader firmware update started")
        }

        override fun onReportReaderSoftwareUpdateProgress(progress: Float) {
            Log.d(TAG, "BLE update progress: ${(progress * 100).toInt()}%")
        }

        override fun onFinishInstallingUpdate(
            update: ReaderSoftwareUpdate?,
            e: TerminalException?
        ) {
            if (e != null) {
                Log.e(TAG, "BLE update failed: ${e.errorMessage}")
            } else {
                Log.i(TAG, "BLE firmware update complete")
            }
        }

        // ReaderReconnectionListener

        override fun onReaderReconnectStarted(
            reader: Reader,
            cancelReconnect: Cancelable,
            reason: DisconnectReason
        ) {
            Log.i(TAG, "BLE reader reconnecting: ${reader.serialNumber}")
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

    /** Returns the target reader by serial, or the first discovered reader. */
    private fun pickReader(readers: List<Reader>): Reader? {
        return if (KioskConfig.READER_SERIAL.isNotBlank()) {
            readers.firstOrNull { it.serialNumber == KioskConfig.READER_SERIAL }
        } else {
            readers.firstOrNull()
        }
    }

    /**
     * Checks BLE permission for the current API level.
     * API 31+ (Android 12+): BLUETOOTH_SCAN.
     * Below API 31: ACCESS_FINE_LOCATION (required for BLE discovery on older devices).
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