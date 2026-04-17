package com.campsite.kiosk

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.external.callable.*
import com.stripe.stripeterminal.external.models.*
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

    // ── Reader discovery ──────────────────────────────────────────────────────

    fun discoverAndConnect(readerSerialNumber: String) {
        // WisePOS E uses Internet discovery; no Bluetooth needed.
        val config = DiscoveryConfiguration.InternetDiscoveryConfiguration(
            isSimulated = false
        )

        Terminal.getInstance().discoverReaders(
            config,
            object : DiscoveryListener {
                override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                    val target = readers.firstOrNull { it.serialNumber == readerSerialNumber }
                        ?: return
                    connectInternetReader(target)
                }
            },
            object : Callback {
                override fun onSuccess() {
                    Log.d(TAG, "Discovery completed")
                }

                override fun onFailure(e: TerminalException) {
                    Log.e(TAG, "Discovery failed: ${e.errorMessage}")
                    onReaderStatusChanged("disconnected")
                }
            }
        )
    }

    fun discoverAndConnectBluetooth(readerSerialNumber: String) {
        // WisePad 3 uses Bluetooth — guard with explicit permission check.
        if (!hasBluetoothScanPermission()) {
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
                        val target = readers.firstOrNull { it.serialNumber == readerSerialNumber }
                            ?: return
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

    private fun connectInternetReader(reader: Reader) {
        val config = ConnectionConfiguration.InternetConnectionConfiguration(
            failIfInUse = true
        )

        Terminal.getInstance().connectInternetReader(
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
        if (!hasBluetoothScanPermission()) {
            Log.w(TAG, "BLUETOOTH_SCAN lost before connect — aborting")
            onReaderStatusChanged("permission_denied")
            return
        }

        val config = ConnectionConfiguration.BluetoothConnectionConfiguration(
            locationId = KioskConfig.STRIPE_LOCATION_ID
        )

        try {
            Terminal.getInstance().connectBluetoothReader(
                reader,
                config,
                object : BluetoothReaderListener {
                    override fun onReportLowBattery() {
                        Log.w(TAG, "BLE reader low battery")
                    }

                    override fun onReportReaderEvent(event: ReaderEvent) {
                        Log.d(TAG, "Reader event: $event")
                    }

                    override fun onRequestReaderDisplayMessage(message: ReaderDisplayMessage) {
                        Log.d(TAG, "Display: $message")
                    }

                    override fun onRequestReaderInput(options: ReaderInputOptions) {
                        Log.d(TAG, "Input options: $options")
                    }

                    override fun onStartInstallingUpdate(
                        update: ReaderSoftwareUpdate,
                        cancelable: Cancelable?
                    ) {
                        Log.d(TAG, "Installing update: ${update.version}")
                    }

                    override fun onReportReaderSoftwareUpdateProgress(progress: Float) {
                        Log.d(TAG, "Update progress: $progress")
                    }

                    override fun onFinishInstallingUpdate(
                        update: ReaderSoftwareUpdate?,
                        e: TerminalException?
                    ) {
                        if (e != null) {
                            Log.e(TAG, "Update failed: ${e.errorMessage}")
                        } else {
                            Log.d(TAG, "Update done: ${update?.version}")
                        }
                    }
                },
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
     *   1. Retrieve PaymentIntent by client_secret (fetched server-side)
     *   2. collectPaymentMethod
     *   3. confirmPaymentIntent
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
        val params = CollectPaymentMethodParameters.Builder().build()

        Terminal.getInstance().collectPaymentMethod(
            paymentIntent,
            object : PaymentIntentCallback {
                override fun onSuccess(paymentIntent: PaymentIntent) {
                    confirmPayment(paymentIntent, bookingRef)
                }

                override fun onFailure(e: TerminalException) {
                    Log.e(TAG, "collectPaymentMethod failed: ${e.errorMessage}")
                    onPaymentFailure(e.errorMessage ?: "collect_failed")
                }
            },
            params
        )
    }

    private fun confirmPayment(paymentIntent: PaymentIntent, bookingRef: String) {
        Terminal.getInstance().confirmPaymentIntent(
            paymentIntent,
            object : PaymentIntentCallback {
                override fun onSuccess(paymentIntent: PaymentIntent) {
                    val status = paymentIntent.status
                    if (status == PaymentIntentStatus.SUCCEEDED) {
                        val piId = paymentIntent.id
                        Log.d(TAG, "Payment succeeded: $piId — booking: $bookingRef")
                        onPaymentSuccess(piId)
                    } else {
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

    fun cancelPayment() {
        // No active Cancelable stored here; cancellation is handled per-operation.
        // Extend by holding a reference to the Cancelable returned by collectPaymentMethod.
        Log.d(TAG, "cancelPayment called — no active operation to cancel")
    }

    // ── Status ────────────────────────────────────────────────────────────────

    fun getReaderStatus(): String {
        val reader = Terminal.getInstance().connectedReader ?: return "disconnected"
        return if (reader.networkStatus == Reader.NetworkStatus.ONLINE) "connected" else "offline"
    }

    // ── Permission helpers ────────────────────────────────────────────────────

    private fun hasBluetoothScanPermission(): Boolean {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            // Pre-Android 12: only ACCESS_FINE_LOCATION needed for BLE
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        }
    }
}