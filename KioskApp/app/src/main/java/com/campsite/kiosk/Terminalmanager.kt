package com.campsite.kiosk

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.external.callable.Callback
import com.stripe.stripeterminal.external.callable.Cancelable
import com.stripe.stripeterminal.external.callable.DiscoveryListener
import com.stripe.stripeterminal.external.callable.PaymentIntentCallback
import com.stripe.stripeterminal.external.callable.ReaderCallback
import com.stripe.stripeterminal.external.models.CollectConfiguration
import com.stripe.stripeterminal.external.models.ConnectionConfiguration
import com.stripe.stripeterminal.external.models.DiscoveryConfiguration
import com.stripe.stripeterminal.external.models.PaymentIntent
import com.stripe.stripeterminal.external.models.PaymentIntentStatus
import com.stripe.stripeterminal.external.models.Reader
import com.stripe.stripeterminal.external.models.TerminalException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "TerminalManager"

class TerminalManager(
    private val context: Context,
    private val onPaymentSuccess: (paymentIntentId: String) -> Unit,
    private val onPaymentFailure: (message: String) -> Unit,
    private val onReaderStatusChanged: (status: String) -> Unit
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Held so callers can cancel an in-progress collectPaymentMethod. */
    private var collectCancelable: Cancelable? = null

    // ── Reader discovery ──────────────────────────────────────────────────────

    /**
     * WisePOS E — Internet discovery.
     * No Bluetooth permission needed.
     */
    fun discoverAndConnect(readerSerialNumber: String) {
        val config = DiscoveryConfiguration.InternetDiscoveryConfiguration(
            isSimulated = false
        )

        Terminal.getInstance().discoverReaders(
            config,
            object : DiscoveryListener {
                override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                    val target = if (readerSerialNumber.isNotBlank()) {
                        readers.firstOrNull { it.serialNumber == readerSerialNumber }
                    } else {
                        readers.firstOrNull()
                    } ?: return
                    connectInternetReader(target)
                }
            },
            object : Callback {
                override fun onSuccess() {
                    Log.d(TAG, "Internet discovery completed")
                }

                override fun onFailure(e: TerminalException) {
                    Log.e(TAG, "Internet discovery failed: ${e.errorMessage}")
                    onReaderStatusChanged("disconnected")
                }
            }
        )
    }

    /**
     * WisePad 3 — Bluetooth discovery.
     * Guards permission before touching BLE APIs.
     */
    fun discoverAndConnectBluetooth(readerSerialNumber: String) {
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "BLUETOOTH_SCAN permission not granted — skipping BLE discovery")
            onReaderStatusChanged("permission_denied")
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
                        val target = if (readerSerialNumber.isNotBlank()) {
                            readers.firstOrNull { it.serialNumber == readerSerialNumber }
                        } else {
                            readers.firstOrNull()
                        } ?: return
                        connectBluetoothReader(target)
                    }
                },
                object : Callback {
                    override fun onSuccess() {
                        Log.d(TAG, "BLE discovery completed")
                    }

                    override fun onFailure(e: TerminalException) {
                        Log.e(TAG, "BLE discovery failed: ${e.errorMessage}")
                        onReaderStatusChanged("disconnected")
                    }
                }
            )
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException during BLE discovery: ${se.message}")
            onReaderStatusChanged("permission_denied")
        }
    }

    // ── Connect helpers ───────────────────────────────────────────────────────

    /**
     * SDK 3+: connectInternetReader / connectBluetoothReader collapsed into
     * connectReader with typed ConnectionConfiguration.
     */
    private fun connectInternetReader(reader: Reader) {
        val config = ConnectionConfiguration.InternetConnectionConfiguration(
            failIfInUse = true
        )

        Terminal.getInstance().connectReader(
            reader,
            config,
            object : ReaderCallback {
                override fun onSuccess(reader: Reader) {
                    Log.d(TAG, "Internet reader connected: ${reader.serialNumber}")
                    onReaderStatusChanged("connected")
                }

                override fun onFailure(e: TerminalException) {
                    Log.e(TAG, "Internet reader connect failed: ${e.errorMessage}")
                    onReaderStatusChanged("disconnected")
                }
            }
        )
    }

    private fun connectBluetoothReader(reader: Reader) {
        if (!hasBluetoothPermission()) {
            Log.w(TAG, "BLUETOOTH_SCAN lost before connect — aborting")
            onReaderStatusChanged("permission_denied")
            return
        }

        // TERMINAL_LOCATION_ID must be set in KioskConfig before production use.
        val locationId = KioskConfig.TERMINAL_LOCATION_ID.ifBlank {
            Log.e(TAG, "TERMINAL_LOCATION_ID not configured in KioskConfig")
            onReaderStatusChanged("config_error")
            return
        }

        val config = ConnectionConfiguration.BluetoothConnectionConfiguration(
            locationId = locationId
        )

        try {
            Terminal.getInstance().connectReader(
                reader,
                config,
                object : ReaderCallback {
                    override fun onSuccess(reader: Reader) {
                        Log.d(TAG, "BLE reader connected: ${reader.serialNumber}")
                        onReaderStatusChanged("connected")
                    }

                    override fun onFailure(e: TerminalException) {
                        Log.e(TAG, "BLE reader connect failed: ${e.errorMessage}")
                        onReaderStatusChanged("disconnected")
                    }
                }
            )
        } catch (se: SecurityException) {
            Log.e(TAG, "SecurityException during BLE connect: ${se.message}")
            onReaderStatusChanged("permission_denied")
        }
    }

    // ── Payment flow ──────────────────────────────────────────────────────────

    /**
     * Full payment flow:
     *   1. retrievePaymentIntent(clientSecret)
     *   2. collectPaymentMethod
     *   3. confirmPaymentIntent
     *
     * clientSecret is always fetched server-side — never passed from JS directly.
     */
    fun requestPayment(clientSecret: String, bookingRef: String) {
        scope.launch {
            Terminal.getInstance().retrievePaymentIntent(
                clientSecret,
                object : PaymentIntentCallback {
                    override fun onSuccess(paymentIntent: PaymentIntent) {
                        collectPayment(paymentIntent, bookingRef)
                    }

                    override fun onFailure(e: TerminalException) {
                        Log.e(TAG, "retrievePaymentIntent failed: ${e.errorMessage}")
                        onPaymentFailure(e.errorMessage ?: "retrieve_failed")
                    }
                }
            )
        }
    }

    private fun collectPayment(paymentIntent: PaymentIntent, bookingRef: String) {
        // SDK 3+: CollectPaymentMethodParameters → CollectConfiguration
        val collectConfig = CollectConfiguration.Builder().build()

        collectCancelable = Terminal.getInstance().collectPaymentMethod(
            paymentIntent,
            object : PaymentIntentCallback {
                override fun onSuccess(paymentIntent: PaymentIntent) {
                    collectCancelable = null
                    confirmPayment(paymentIntent, bookingRef)
                }

                override fun onFailure(e: TerminalException) {
                    collectCancelable = null
                    Log.e(TAG, "collectPaymentMethod failed: ${e.errorMessage}")
                    onPaymentFailure(e.errorMessage ?: "collect_failed")
                }
            },
            collectConfig
        )
    }

    private fun confirmPayment(paymentIntent: PaymentIntent, bookingRef: String) {
        Terminal.getInstance().confirmPaymentIntent(
            paymentIntent,
            object : PaymentIntentCallback {
                override fun onSuccess(paymentIntent: PaymentIntent) {
                    if (paymentIntent.status == PaymentIntentStatus.SUCCEEDED) {
                        // id is nullable in SDK 4.x — fall back to bookingRef for logging
                        val piId = paymentIntent.id ?: run {
                            Log.w(TAG, "PaymentIntent.id null — using bookingRef as fallback")
                            bookingRef
                        }
                        Log.d(TAG, "Payment succeeded: $piId — booking: $bookingRef")
                        onPaymentSuccess(piId)
                    } else {
                        val status = paymentIntent.status
                        Log.w(TAG, "Unexpected status after confirm: $status")
                        onPaymentFailure("unexpected_status_$status")
                    }
                }

                override fun onFailure(e: TerminalException) {
                    Log.e(TAG, "confirmPaymentIntent failed: ${e.errorMessage}")
                    onPaymentFailure(e.errorMessage ?: "confirm_failed")
                }
            }
        )
    }

    // ── Cancel ────────────────────────────────────────────────────────────────

    /**
     * Cancels an in-progress collectPaymentMethod if one is active.
     */
    fun cancelPayment() {
        val cancelable = collectCancelable
        if (cancelable == null) {
            Log.d(TAG, "cancelPayment — no active operation")
            return
        }
        cancelable.cancel(object : Callback {
            override fun onSuccess() {
                Log.d(TAG, "Payment collection cancelled")
                collectCancelable = null
                onPaymentFailure("cancelled_by_user")
            }

            override fun onFailure(e: TerminalException) {
                Log.e(TAG, "Cancel failed: ${e.errorMessage}")
            }
        })
    }

    // ── Status ────────────────────────────────────────────────────────────────

    fun getReaderStatus(): String {
        val reader = Terminal.getInstance().connectedReader ?: return "disconnected"
        return if (reader.networkStatus == Reader.NetworkStatus.ONLINE) "connected" else "offline"
    }

    // ── Permission helpers ────────────────────────────────────────────────────

    private fun hasBluetoothPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}