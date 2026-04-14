package it.areacamperbergamo.kiosk

import android.util.Log
import com.stripe.stripeterminal.external.callable.ConnectionTokenCallback
import com.stripe.stripeterminal.external.callable.ConnectionTokenProvider
import com.stripe.stripeterminal.external.models.ConnectionTokenException
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Provides connection tokens to the Stripe Terminal SDK.
 *
 * The SDK calls fetchConnectionToken() whenever it needs a fresh token.
 * This class fetches it from the PHP server endpoint:
 *   GET /api/connection-token.php → {"secret": "pst_test_..."}
 *
 * This is the bridge between your remote PHP server and the local
 * Android Terminal SDK — the server holds the Stripe secret key and
 * creates the token, while the SDK on the device uses it to authenticate.
 */
class TokenProvider(private val apiBaseUrl: String) : ConnectionTokenProvider {

    companion object {
        private const val TAG = "TokenProvider"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun fetchConnectionToken(callback: ConnectionTokenCallback) {
        val url = "$apiBaseUrl/connection-token.php"
        Log.d(TAG, "Fetching connection token from $url")

        val request = Request.Builder()
            .url(url)
            .get()
            .build()

        try {
            val response = client.newCall(request).execute()

            if (!response.isSuccessful) {
                val msg = "Server returned HTTP ${response.code}"
                Log.e(TAG, msg)
                callback.onFailure(ConnectionTokenException(msg))
                return
            }

            val body = response.body?.string()
            if (body == null) {
                val msg = "Empty response body"
                Log.e(TAG, msg)
                callback.onFailure(ConnectionTokenException(msg))
                return
            }

            val json = JSONObject(body)
            val secret = json.getString("secret")
            Log.d(TAG, "Connection token received")
            callback.onSuccess(secret)

        } catch (e: IOException) {
            val msg = "Network error fetching connection token: ${e.message}"
            Log.e(TAG, msg, e)
            callback.onFailure(ConnectionTokenException(msg, e))
        } catch (e: Exception) {
            val msg = "Error parsing connection token: ${e.message}"
            Log.e(TAG, msg, e)
            callback.onFailure(ConnectionTokenException(msg, e))
        }
    }
}
