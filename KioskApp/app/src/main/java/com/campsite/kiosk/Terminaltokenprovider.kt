package com.campsite.kiosk

import android.util.Log
import com.stripe.stripeterminal.external.callable.ConnectionTokenCallback
import com.stripe.stripeterminal.external.callable.ConnectionTokenProvider
import com.stripe.stripeterminal.external.models.ConnectionTokenException
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * TerminalTokenProvider
 *
 * Fetches a short-lived Stripe Terminal connection token from the backend
 * whenever the SDK calls [fetchConnectionToken].
 *
 * The backend endpoint (connection-token.php) holds the Stripe secret key;
 * no credentials are stored in the APK.
 *
 * Network call runs on a background thread via the SDK-supplied executor.
 * All errors are forwarded through [ConnectionTokenCallback.onFailure].
 */
class TerminalTokenProvider : ConnectionTokenProvider {

    companion object {
        private const val TAG            = "TerminalTokenProvider"
        private const val CONNECT_TIMEOUT = 10_000   // ms
        private const val READ_TIMEOUT    = 15_000   // ms
    }

    override fun fetchConnectionToken(callback: ConnectionTokenCallback) {
        try {
            val url  = URL(KioskConfig.CONNECTION_TOKEN_URL)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod  = "GET"
                connectTimeout = CONNECT_TIMEOUT
                readTimeout    = READ_TIMEOUT
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", KioskConfig.USER_AGENT)
                doInput = true
            }

            val code = conn.responseCode

            if (code != HttpURLConnection.HTTP_OK) {
                val msg = "HTTP $code from connection-token endpoint"
                Log.e(TAG, msg)
                callback.onFailure(ConnectionTokenException(msg))
                conn.disconnect()
                return
            }

            val body = BufferedReader(InputStreamReader(conn.inputStream, "UTF-8"))
                .use { it.readText() }

            conn.disconnect()

            val secret = JSONObject(body).optString("secret", "")

            if (secret.isEmpty()) {
                val msg = "Empty secret in connection-token response"
                Log.e(TAG, msg)
                callback.onFailure(ConnectionTokenException(msg))
                return
            }

            Log.d(TAG, "Connection token fetched OK")
            callback.onSuccess(secret)

        } catch (ex: Exception) {
            Log.e(TAG, "fetchConnectionToken failed", ex)
            callback.onFailure(
                ConnectionTokenException("Token fetch error: ${ex.message}", ex)
            )
        }
    }
}