package it.areacamperbergamo.kiosk

import android.app.Application
import android.util.Log
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.external.callable.TerminalListener
import com.stripe.stripeterminal.external.models.ConnectionStatus
import com.stripe.stripeterminal.external.models.PaymentStatus
import com.stripe.stripeterminal.external.models.Reader
import com.stripe.stripeterminal.log.LogLevel

/**
 * Application subclass that initializes the Stripe Terminal SDK.
 *
 * Terminal.initTerminal() must be called exactly once before any
 * Terminal operations. We do it here so it's ready when the WebView
 * loads and the JS bridge calls collectPayment().
 */
class KioskApplication : Application() {

    companion object {
        private const val TAG = "KioskApp"
    }

    override fun onCreate() {
        super.onCreate()

        if (!Terminal.isInitialized()) {
            Terminal.initTerminal(
                this,
                LogLevel.VERBOSE,
                TokenProvider(BuildConfig.API_BASE_URL),
                object : TerminalListener {
                    override fun onConnectionStatusChange(status: ConnectionStatus) {
                        Log.d(TAG, "Terminal connection status: $status")
                    }

                    override fun onPaymentStatusChange(status: PaymentStatus) {
                        Log.d(TAG, "Terminal payment status: $status")
                    }

                    override fun onUnexpectedReaderDisconnect(reader: Reader) {
                        Log.w(TAG, "Reader disconnected unexpectedly: ${reader.serialNumber}")
                    }
                }
            )
            Log.i(TAG, "Stripe Terminal initialized (simulated=${BuildConfig.USE_SIMULATED_READER})")
        }
    }
}
