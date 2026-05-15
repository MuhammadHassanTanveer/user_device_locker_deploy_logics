package com.deploy_logics.user_device_locker

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Calls POST /mobile/update_unlock_code when the user unlocks the device.
 */
object UnlockCodeApi {
    private const val TAG = "UnlockCodeApi"
    private const val BASE_URL = "https://api.deviceguardian.net/api"
    private const val PREFS_NAME = "FlutterSharedPreferences"
    private const val IMEI1_KEY = "flutter.device_imei1"
    private const val IMEI2_KEY = "flutter.device_imei2"
    private const val UNLOCK_CODE_KEY = "flutter.unlock_code"
    private const val LOCK_CODE_KEY = "flutter.lock_code"
    private const val LOCK_PIN_KEY = "flutter.lock_pin"

    fun updateUnlockCode(context: Context, unlockCode: String) {
        thread {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val imei1 = prefs.getString(IMEI1_KEY, "") ?: ""
                val imei2 = prefs.getString(IMEI2_KEY, "") ?: ""

                if (imei1.isEmpty()) {
                    Log.e(TAG, "❌ IMEI 1 not found - cannot update unlock code")
                    return@thread
                }

                val success = makeApiCall(imei1, imei2, unlockCode)
                if (success) {
                    saveUnlockCodeLocally(prefs, unlockCode)
                    Log.d(TAG, "✅ Unlock code updated on server: $unlockCode")
                } else {
                    Log.w(TAG, "⚠️ Failed to update unlock code on server")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error updating unlock code: ${e.message}")
            }
        }
    }

    fun saveUnlockCodeLocally(
        prefs: android.content.SharedPreferences,
        unlockCode: String
    ) {
        prefs.edit().apply {
            putString(UNLOCK_CODE_KEY, unlockCode)
            putString(LOCK_CODE_KEY, unlockCode)
            putString(LOCK_PIN_KEY, unlockCode)
            apply()
        }
    }

    fun getStoredUnlockCode(prefs: android.content.SharedPreferences): String {
        return prefs.getString(UNLOCK_CODE_KEY, null)
            ?: prefs.getString(LOCK_CODE_KEY, null)
            ?: prefs.getString(LOCK_PIN_KEY, null)
            ?: ""
    }

    /** Random 6-digit unlock code. */
    fun generateUnlockCode(): String {
        return (100000 + (Math.random() * 900000).toInt()).toString()
    }

    private fun makeApiCall(imei1: String, imei2: String, unlockCode: String): Boolean {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/mobile/update_unlock_code")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val jsonBody = JSONObject().apply {
                put("imei_1", imei1)
                put("unlock_code", unlockCode)
                if (imei2.isNotEmpty()) {
                    put("imei_2", imei2)
                }
            }

            Log.d(TAG, "🌐 POST $url body=$jsonBody")

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            val response = if (responseCode == HttpURLConnection.HTTP_OK) {
                BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            } else {
                BufferedReader(InputStreamReader(connection.errorStream)).use { it.readText() }
            }
            Log.d(TAG, "🌐 Response $responseCode: $response")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                try {
                    return JSONObject(response).optBoolean("success", true)
                } catch (_: Exception) {
                    return true
                }
            }
            return false
        } catch (e: Exception) {
            Log.e(TAG, "❌ Network error: ${e.message}")
            return false
        } finally {
            connection?.disconnect()
        }
    }
}
