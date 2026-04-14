package it.areacamperbergamo.kiosk

import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import com.stripe.stripeterminal.Terminal
import com.stripe.stripeterminal.external.callable.Cancelable
import com.stripe.stripeterminal.external.callable.Callback
import com.stripe.stripeterminal.external.callable.DiscoveryListener
import com.stripe.stripeterminal.external.callable.PaymentIntentCallback
import com.stripe.stripeterminal.external.callable.ReaderCallback
import com.stripe.stripeterminal.external.models.ConnectionConfiguration
import com.stripe.stripeterminal.external.models.DiscoveryConfiguration
import com.stripe.stripeterminal.external.models.PaymentIntent
import com.stripe.stripeterminal.external.models.Reader
import com.stripe.stripeterminal.external.models.TerminalException
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * JavaScript interface exposed to the WebView as `window.AndroidBridge`.
 *
 * ┌────────────────────────────────────────────────────────────────────┐
 * │  HOW IT WORKS (the "bridge" between server and local SDK)         │
 * │                                                                    │
 * │  1. JS calls  AndroidBridge.collectPayment(amountCents)           │
 * │  2. Kotlin    POSTs to PHP server → create-payment-intent.php     │
 * │     ↳ Server creates a PaymentIntent using the Stripe secret key  │
 * │     ↳ Returns { client_secret: "pi_..._secret_..." }             │
 * │  3. Kotlin    Terminal SDK retrieves the PaymentIntent locally     │
 * │  4. Kotlin    Terminal SDK collects payment method from reader     │
 * │     ↳ Simulated reader auto-accepts (for testing)                 │
 * │     ↳ Real WisePad 3: user taps/inserts card on Bluetooth reader  │
 * │  5. Kotlin    Terminal SDK processes the payment                   │
 * │  6. Kotlin    calls back JS: window.onPaymentResult(true/false)   │
 * └────────────────────────────────────────────────────────────────────┘
 *
 * The simulated reader is enabled via BuildConfig.USE_SIMULATED_READER.
 * It lets you test the full payment flow without a physical BBPOS WisePad 3.
 */
class PaymentBridge(
    private val webView: WebView,
    private val apiBaseUrl: String,
    private val useSimulatedReader: Boolean,
) {
    companion object {
        private const val TAG = "PaymentBridge"
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var discoveryCancelable: Cancelable? = null
    private var collectCancelable: Cancelable? = null

    // ─────────────────────────────────────────────────────────
    //  JS Interface: collectPayment(amountCents)
    // ─────────────────────────────────────────────────────────

    /**
     * Called from JavaScript:
     *   window.AndroidBridge.collectPayment(2500)  // €25.00
     *
     * Starts the full payment flow:
     *   discover reader → connect → create intent → collect → process
     */
    @JavascriptInterface
    fun collectPayment(amountCents: Int) {
        Log.i(TAG, "collectPayment called: $amountCents cents")

        if (amountCents < 50) {
            notifyResult(false, "Amount too small (minimum 50 cents)")
            return
        }

        // Run the payment flow on a background thread
        Thread {
            try {
                ensureReaderConnected { connected ->
                    if (!connected) {
                        notifyResult(false, "Could not connect to card reader")
                        return@ensureReaderConnected
                    }

                    // Step 1: Create PaymentIntent on the server
                    notifyStatus("Creating payment…")
                    val clientSecret = createPaymentIntentOnServer(amountCents)
                    if (clientSecret == null) {
                        notifyResult(false, "Could not create payment on server")
                        return@ensureReaderConnected
                    }

                    // Step 2: Retrieve the PaymentIntent in the Terminal SDK
                    notifyStatus("Preparing reader…")
                    retrieveAndCollectPayment(clientSecret)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Payment flow error", e)
                notifyResult(false, "Payment error: ${e.message}")
            }
        }.start()
    }

    /**
     * Called from JavaScript to cancel an in-progress payment.
     *   window.AndroidBridge.cancelPayment()
     */
    @JavascriptInterface
    fun cancelPayment() {
        Log.i(TAG, "cancelPayment called")
        collectCancelable?.cancel(object : Callback {
            override fun onSuccess() {
                Log.d(TAG, "Payment collection cancelled")
            }
            override fun onFailure(e: TerminalException) {
                Log.e(TAG, "Cancel failed: ${e.errorMessage}")
            }
        })
        discoveryCancelable?.cancel(object : Callback {
            override fun onSuccess() {
                Log.d(TAG, "Discovery cancelled")
            }
            override fun onFailure(e: TerminalException) {
                Log.e(TAG, "Discovery cancel failed: ${e.errorMessage}")
            }
        })
    }

    // ─────────────────────────────────────────────────────────
    //  Step A: Ensure a reader is discovered and connected
    // ─────────────────────────────────────────────────────────

    private fun ensureReaderConnected(onComplete: (Boolean) -> Unit) {
        val terminal = Terminal.getInstance()

        // If already connected, skip discovery
        if (terminal.connectedReader != null) {
            Log.d(TAG, "Reader already connected: ${terminal.connectedReader?.serialNumber}")
            mainHandler.post { onComplete(true) }
            return
        }

        notifyStatus("Searching for reader…")

        // Discover readers — simulated for testing, Bluetooth for production
        val config = DiscoveryConfiguration.BluetoothDiscoveryConfiguration(
            isSimulated = useSimulatedReader,
        )

        discoveryCancelable = terminal.discoverReaders(
            config,
            object : DiscoveryListener {
                override fun onUpdateDiscoveredReaders(readers: List<Reader>) {
                    if (readers.isEmpty()) return

                    // Cancel discovery (we found a reader)
                    discoveryCancelable?.cancel(object : Callback {
                        override fun onSuccess() {}
                        override fun onFailure(e: TerminalException) {}
                    })

                    // Connect to the first discovered reader
                    val reader = readers[0]
                    Log.d(TAG, "Discovered reader: ${reader.serialNumber ?: "simulated"}")
                    notifyStatus("Connecting to reader…")

                    val connConfig = ConnectionConfiguration.BluetoothConnectionConfiguration(
                        locationId = reader.location?.id ?: "tml_simulated",
                    )

                    terminal.connectReader(
                        reader,
                        connConfig,
                        object : ReaderCallback {
                            override fun onSuccess(connectedReader: Reader) {
                                Log.i(TAG, "Connected to reader: ${connectedReader.serialNumber}")
                                mainHandler.post { onComplete(true) }
                            }

                            override fun onFailure(e: TerminalException) {
                                Log.e(TAG, "Reader connection failed: ${e.errorMessage}")
                                mainHandler.post { onComplete(false) }
                            }
                        },
                    )
                }
            },
            object : Callback {
                override fun onSuccess() {
                    Log.d(TAG, "Discovery completed")
                }

                override fun onFailure(e: TerminalException) {
                    Log.e(TAG, "Discovery failed: ${e.errorMessage}")
                    mainHandler.post { onComplete(false) }
                }
            },
        )
    }

    // ─────────────────────────────────────────────────────────
    //  Step B: Create PaymentIntent on your PHP server
    // ─────────────────────────────────────────────────────────

    /**
     * POSTs to create-payment-intent.php on the remote server.
     * The server uses the Stripe secret key to create the PaymentIntent.
     * Returns the client_secret, or null on failure.
     */
    private fun createPaymentIntentOnServer(amountCents: Int): String? {
        val url = "$apiBaseUrl/create-payment-intent.php"
        val json = JSONObject().apply { put("amount", amountCents) }
        val body = json.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(url)
            .post(body)
            .build()

        return try {
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Server returned ${response.code}")
                return null
            }
            val responseBody = response.body?.string() ?: return null
            val responseJson = JSONObject(responseBody)
            responseJson.getString("client_secret")
        } catch (e: IOException) {
            Log.e(TAG, "Network error creating PaymentIntent: ${e.message}", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing PaymentIntent response: ${e.message}", e)
            null
        }
    }

    // ─────────────────────────────────────────────────────────
    //  Step C: Retrieve PaymentIntent + collect + process
    // ─────────────────────────────────────────────────────────

    private fun retrieveAndCollectPayment(clientSecret: String) {
        val terminal = Terminal.getInstance()

        // Retrieve the PaymentIntent from Stripe (using the client_secret)
        terminal.retrievePaymentIntent(clientSecret, object : PaymentIntentCallback {
            override fun onSuccess(paymentIntent: PaymentIntent) {
                Log.d(TAG, "PaymentIntent retrieved: ${paymentIntent.id}")
                notifyStatus("Tap or insert card…")

                // Collect payment method from the reader
                collectCancelable = terminal.collectPaymentMethod(
                    paymentIntent,
                    object : PaymentIntentCallback {
                        override fun onSuccess(collectedIntent: PaymentIntent) {
                            Log.d(TAG, "Payment method collected")
                            notifyStatus("Processing payment…")

                            // Process the payment
                            terminal.processPayment(collectedIntent, object : PaymentIntentCallback {
                                override fun onSuccess(processedIntent: PaymentIntent) {
                                    Log.i(TAG, "Payment successful! ID: ${processedIntent.id}")
                                    notifyResult(true, "Payment successful")
                                }

                                override fun onFailure(e: TerminalException) {
                                    Log.e(TAG, "Payment processing failed: ${e.errorMessage}")
                                    notifyResult(false, e.errorMessage)
                                }
                            })
                        }

                        override fun onFailure(e: TerminalException) {
                            Log.e(TAG, "Collect payment method failed: ${e.errorMessage}")
                            notifyResult(false, e.errorMessage)
                        }
                    },
                )
            }

            override fun onFailure(e: TerminalException) {
                Log.e(TAG, "Retrieve PaymentIntent failed: ${e.errorMessage}")
                notifyResult(false, e.errorMessage)
            }
        })
    }

    // ─────────────────────────────────────────────────────────
    //  Callbacks to JavaScript
    // ─────────────────────────────────────────────────────────

    /**
     * Calls window.onPaymentResult(success, message) in the WebView.
     * This is how the Android code communicates the payment result
     * back to the web UI running in the WebView.
     */
    private fun notifyResult(success: Boolean, message: String) {
        val escapedMsg = message.replace("'", "\\'").replace("\n", " ")
        val js = "if(typeof window.onPaymentResult==='function'){window.onPaymentResult($success,'$escapedMsg')}"
        mainHandler.post { webView.evaluateJavascript(js, null) }
    }

    /**
     * Calls window.onPaymentStatusUpdate(status) in the WebView.
     * Used to show real-time status updates during the payment flow.
     */
    private fun notifyStatus(status: String) {
        val escapedStatus = status.replace("'", "\\'")
        val js = "if(typeof window.onPaymentStatusUpdate==='function'){window.onPaymentStatusUpdate('$escapedStatus')}"
        mainHandler.post { webView.evaluateJavascript(js, null) }
    }
}
