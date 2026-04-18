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
import com.stripe.stripeterminal.external.models.BatteryStatus
import com.stripe.stripeterminal.external.models.CollectConfiguration
import com.stripe.stripeterminal.external.models.ConnectionConfiguration
import com.stripe.stripeterminal.external.models.ConnectionStatus
import com.stripe.stripeterminal.external.models.DisconnectReason
import com.stripe.stripeterminal.external.models.DiscoveryConfiguration
import com.stripe.stripeterminal.external.models.PaymentIntent
import com.stripe.stripeterminal.external.models.PaymentIntentStatus
import com.stripe.stripeterminal.external.models.PaymentStatus
import com.stripe.stripeterminal.external.models.Reader
import com.stripe.stripeterminal.external.models.ReaderDisplayMessage
import com.stripe.stripeterminal.external.models.ReaderEvent
import com.stripe.stripeterminal.external.models.ReaderInputOptions
import com.stripe.stripeterminal.external.models.ReaderSoftwareUpdate
import com.stripe.stripeterminal.external.models.TerminalException
import com.stripe.stripeterminal.log.LogLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "TerminalManager"

/**
 * TerminalManager — SDK 4.1.0 compatible
 *
 * API contract for SDK 4.x (pre-5.0 rename):
 *   CollectConfiguration          (renamed CollectPaymentIntentConfiguration in 5.0)
 *   discoverReaders(config, discoveryListener, callback)  — positional, Java-compiled
 *   connectReader(reader, config, callback)               — positional, Java-compiled
 *   collectPaymentMethod(intent, callback, config)        — Kotlin @JvmOverloads
 *   confirmPaymentIntent(intent, callback)                — Kotlin @JvmOverloads
 *   retrievePaymentIntent(clientSecret, callback)         — positional
 *
 * MobileReaderListener requires ALL abstract overrides; Android Studio will
 * flag a compile error if any are missing, so all are implemented here.
 */
@SuppressLint("MissingPermission")
object TerminalManager {

    // ── Public state ──────────────────────────────────────────────────────────

    @Volatile
    var readerStatus: String = "disconnected"
        private set

    // ── Private state ─────────────────────────────────────────────────────────

    private lateinit var appContext: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    @Volatile
    private var collectCancelable: Cancelable? = null

    @Volatile
    private var discoveryInProgress: Boolean = false

    // ── Init ──────────────────────────────────────────────────────────────────

    fun init(context: Context) {
        appContext = context.applicationContext

        if (Terminal.isInitialized()) {
            Log.d(TAG, "Terminal already initialised — starting discovery")
            startDiscovery()
            return
        }

        val terminalListener = object : TerminalListener {
            override fun onConnectionStatusChange(status: ConnectionStatus) {
                Log.d(TAG, "Connection status: $status")
            }

            override fun onPaymentStatusChange(status: PaymentStatus) {
                Log.d(TAG, "Payment status: $status")
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

    // ── Discovery ─────────────────────────────────────────────────────────────

    private fun startDiscovery() {
        if (discoveryInProgress) {
            Log.d(TAG, "Discovery already in progress — skipping")
            return
        }
        discoverBluetooth()
    }

    private fun discoverInternet() {
        discoveryInProgress = true
        updateStatus("discovering")

        val config = DiscoveryConfiguration.InternetDiscoveryConfiguration(isSimulated = true)

        // Java-compiled: positional args only — (config, discoveryListener, callback)
        Terminal.getInstance().discoverReaders(
            config,
            object : DiscoveryListener {
                override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                    val target = pickReader(readers) ?: run {
                        Log.d(TAG, "No matching reader (${readers.size} found)")
                        return
                    }
                    connectInternet(target)
                }
            },
            object : Callback {
                override fun onSuccess() {
                    Log.d(TAG, "Internet discovery complete")
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

    private fun connectInternet(reader: Reader) {
        val config = ConnectionConfiguration.InternetConnectionConfiguration(
            internetReaderListener = internetReaderListener,
            failIfInUse = true
        )

        // Java-compiled: positional args only — (reader, config, callback)
        Terminal.getInstance().connectReader(
            reader,
            config,
            object : ReaderCallback {
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

    // ── Bluetooth (WisePad 3) — NOT ACTIVE for this kiosk ────────────────────

    private fun discoverBluetooth() {

        discoveryInProgress = true
        updateStatus("discovering")

        val config = DiscoveryConfiguration.BluetoothDiscoveryConfiguration(
            timeout = 60,
            isSimulated = true
        )

        try {
            Terminal.getInstance().discoverReaders(
                config,
                object : DiscoveryListener {
                    override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                        val target = pickReader(readers) ?: run {
                            Log.d(TAG, "No matching BLE reader")
                            return
                        }
                        connectBluetooth(target)
                    }
                },
                object : Callback {
                    override fun onSuccess() {
                        Log.d(TAG, "BLE discovery complete")
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

    @Suppress("unused")
    private fun connectBluetooth(reader: Reader) {
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "BLE permission lost before connect")
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
            autoReconnectOnUnexpectedDisconnect = true,
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

    fun processPayment(
        clientSecret: String,
        onSuccess: (paymentIntentId: String) -> Unit,
        onFailure: (message: String) -> Unit
    ) {
        if (readerStatus != "connected") {
            Log.w(TAG, "processPayment: reader not connected (status=$readerStatus)")
            onFailure("reader_not_connected")
            return
        }

        scope.launch {
            // Positional: (clientSecret, callback)
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
        // SDK 4.x class: CollectConfiguration (renamed CollectPaymentIntentConfiguration in 5.0)
        // Kotlin @JvmOverloads signature: collectPaymentMethod(intent, callback, config)
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
        // Kotlin @JvmOverloads signature: confirmPaymentIntent(intent, callback)
        Terminal.getInstance().confirmPaymentIntent(
            paymentIntent,
            object : PaymentIntentCallback {
                override fun onSuccess(confirmedIntent: PaymentIntent) {
                    when (confirmedIntent.status) {
                        PaymentIntentStatus.SUCCEEDED -> {
                            val piId = confirmedIntent.id ?: "unknown"
                            Log.i(TAG, "Payment succeeded: $piId")
                            onSuccess(piId)
                        }
                        else -> {
                            Log.w(TAG, "Unexpected status after confirm: ${confirmedIntent.status}")
                            onFailure("unexpected_status_${confirmedIntent.status}")
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

    fun cancelPayment() {
        val cancelable = collectCancelable ?: run {
            Log.d(TAG, "cancelPayment: no active collect operation")
            return
        }

        cancelable.cancel(object : Callback {
            override fun onSuccess() {
                Log.d(TAG, "Collection cancelled")
                collectCancelable = null
            }

            override fun onFailure(e: TerminalException) {
                Log.e(TAG, "Cancel failed: ${e.errorMessage}")
            }
        })
    }

    // ── Listeners ─────────────────────────────────────────────────────────────

    private val internetReaderListener = object : InternetReaderListener {
        override fun onDisconnect(reason: DisconnectReason) {
            Log.w(TAG, "Internet reader disconnected: $reason")
            updateStatus("disconnected")
            scheduleReconnect()
        }
    }

    /**
     * MobileReaderListener — ALL abstract members must be overridden.
     * Missing any override is a compile error; stubs are intentional for
     * callbacks irrelevant to this kiosk (display, battery, updates).
     */
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

        override fun onBatteryLevelUpdate(
            batteryLevel: Float,
            batteryStatus: BatteryStatus,
            isCharging: Boolean
        ) {
            Log.d(TAG, "BLE battery: ${(batteryLevel * 100).toInt()}% status=$batteryStatus charging=$isCharging")
        }

        override fun onRequestReaderInput(options: ReaderInputOptions) {
            Log.d(TAG, "BLE reader input requested: $options")
        }

        override fun onRequestReaderDisplayMessage(message: ReaderDisplayMessage) {
            Log.d(TAG, "BLE reader display message: $message")
        }

        override fun onReportAvailableUpdate(update: ReaderSoftwareUpdate) {
            Log.i(TAG, "BLE reader update available: ${update.version}")
        }

        override fun onStartInstallingUpdate(
            update: ReaderSoftwareUpdate,
            cancelable: Cancelable?
        ) {
            Log.i(TAG, "BLE firmware update started: ${update.version}")
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
            Log.i(TAG, "BLE reconnecting: ${reader.serialNumber}, reason=$reason")
            updateStatus("reconnecting")
        }

        override fun onReaderReconnectSucceeded(reader: Reader) {
            Log.i(TAG, "BLE reconnected: ${reader.serialNumber}")
            updateStatus("connected")
        }

        override fun onReaderReconnectFailed(reader: Reader) {
            Log.e(TAG, "BLE reconnect failed: ${reader.serialNumber}")
            updateStatus("disconnected")
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun pickReader(readers: List<Reader>): Reader? {
        return if (KioskConfig.READER_SERIAL.isNotBlank()) {
            readers.firstOrNull { it.serialNumber == KioskConfig.READER_SERIAL }.also {
                if (it == null) {
                    Log.w(TAG, "Target serial ${KioskConfig.READER_SERIAL} not in ${readers.size} readers")
                }
            }
        } else {
            readers.firstOrNull()
        }
    }

    private fun scheduleReconnect() {
        if (discoveryInProgress) return
        Log.i(TAG, "Scheduling reconnect in 5s")
        scope.launch {
            kotlinx.coroutines.delay(5_000L)
            startDiscovery()
        }
    }

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