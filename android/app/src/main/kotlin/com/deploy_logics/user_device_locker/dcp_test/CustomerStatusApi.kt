package com.deploy_logics.user_device_locker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Utility class to call the update_customer_is_active_status API
 * This is called when uninstall command is received
 */
object CustomerStatusApi {
    private const val TAG = "CustomerStatusApi"
    private const val BASE_URL = "https://api.deviceguardian.net/api"
    private const val PREFS_NAME = "FlutterSharedPreferences"
    private const val IMEI1_KEY = "flutter.device_imei1"
    private const val IMEI2_KEY = "flutter.device_imei2"
    private const val PENDING_UNINSTALL_SYNC_KEY = "flutter.pending_uninstall_status_sync"
    private const val UNINSTALL_IS_ACTIVE_STATUS = 2

    /**
     * Update customer is_active status to inactive (is_active=2) before uninstall
     */
    fun updateCustomerStatusForUninstall(
        context: Context,
        callback: ((success: Boolean) -> Unit)? = null
    ) {
        thread {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val imei1 = prefs.getString(IMEI1_KEY, "") ?: ""
                val imei2 = prefs.getString(IMEI2_KEY, "") ?: ""

                if (imei1.isEmpty()) {
                    Log.e(TAG, "❌ IMEI 1 not found - cannot update customer status")
                    callback?.invoke(false)
                    return@thread
                }

                Log.d(TAG, "╔══════════════════════════════════════════════════════════╗")
                Log.d(TAG, "║  📤 Updating Customer Status for Uninstall              ║")
                Log.d(TAG, "║  imei_1: $imei1")
                if (imei2.isNotEmpty()) Log.d(TAG, "║  imei_2: $imei2")
                Log.d(TAG, "║  is_active: $UNINSTALL_IS_ACTIVE_STATUS (inactive)       ║")
                Log.d(TAG, "╚══════════════════════════════════════════════════════════╝")

                if (!isNetworkAvailable(context)) {
                    Log.w(TAG, "⚠️ No internet connection - saving for later sync")
                    savePendingUninstallSync(prefs, imei1, imei2, UNINSTALL_IS_ACTIVE_STATUS)
                    callback?.invoke(false)
                    return@thread
                }

                val success = makeApiCall(imei1, imei2, UNINSTALL_IS_ACTIVE_STATUS)

                if (success) {
                    Log.d(TAG, "✅ Customer status updated successfully on server")
                    prefs.edit().remove(PENDING_UNINSTALL_SYNC_KEY).apply()
                } else {
                    Log.w(TAG, "⚠️ API call failed - saving for later sync")
                    savePendingUninstallSync(prefs, imei1, imei2, UNINSTALL_IS_ACTIVE_STATUS)
                }

                callback?.invoke(success)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error updating customer status: ${e.message}")
                e.printStackTrace()

                try {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val imei1 = prefs.getString(IMEI1_KEY, "") ?: ""
                    val imei2 = prefs.getString(IMEI2_KEY, "") ?: ""
                    if (imei1.isNotEmpty()) {
                        savePendingUninstallSync(prefs, imei1, imei2, UNINSTALL_IS_ACTIVE_STATUS)
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "❌ Error saving pending sync: ${e2.message}")
                }

                callback?.invoke(false)
            }
        }
    }

    private fun isNetworkAvailable(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } else {
            @Suppress("DEPRECATION")
            val networkInfo = connectivityManager.activeNetworkInfo
            @Suppress("DEPRECATION")
            return networkInfo?.isConnected == true
        }
    }

    private fun makeApiCall(imei1: String, imei2: String, isActive: Int): Boolean {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/mobile/update_customer_is_active_status")
            Log.d(TAG, "🌐 API URL: $url")

            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val jsonBody = JSONObject().apply {
                put("imei_1", imei1)
                put("is_active", isActive)
                if (imei2.isNotEmpty()) {
                    put("imei_2", imei2)
                }
            }

            Log.d(TAG, "🌐 Request body: $jsonBody")

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            Log.d(TAG, "🌐 Response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                    reader.readText()
                }
                Log.d(TAG, "🌐 Response: $response")

                try {
                    val jsonResponse = JSONObject(response)
                    return jsonResponse.optBoolean("success", true)
                } catch (e: Exception) {
                    Log.d(TAG, "Response is not JSON, considering successful based on HTTP 200")
                    return true
                }
            } else {
                val errorResponse = try {
                    BufferedReader(InputStreamReader(connection.errorStream)).use { reader ->
                        reader.readText()
                    }
                } catch (e: Exception) { "Unable to read error" }
                Log.e(TAG, "❌ API error: $responseCode - $errorResponse")
                return false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Network error: ${e.message}")
            return false
        } finally {
            connection?.disconnect()
        }
    }

    private fun savePendingUninstallSync(
        prefs: android.content.SharedPreferences,
        imei1: String,
        imei2: String,
        isActive: Int
    ) {
        val pendingData = JSONObject().apply {
            put("imei_1", imei1)
            put("imei_2", imei2)
            put("is_active", isActive)
            put("timestamp", System.currentTimeMillis())
        }
        prefs.edit().putString(PENDING_UNINSTALL_SYNC_KEY, pendingData.toString()).apply()
        Log.d(TAG, "💾 Pending uninstall sync data saved: $pendingData")
    }

    fun syncPendingUninstallStatus(context: Context, callback: ((success: Boolean) -> Unit)? = null) {
        thread {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val pendingDataStr = prefs.getString(PENDING_UNINSTALL_SYNC_KEY, null)

                if (pendingDataStr.isNullOrEmpty()) {
                    Log.d(TAG, "No pending uninstall sync data found")
                    callback?.invoke(true)
                    return@thread
                }

                Log.d(TAG, "📤 Found pending uninstall sync data: $pendingDataStr")

                val pendingData = JSONObject(pendingDataStr)
                val imei1 = pendingData.optString("imei_1", "")
                    .ifEmpty { prefs.getString(IMEI1_KEY, "") ?: "" }
                val imei2 = pendingData.optString("imei_2", "")
                    .ifEmpty { prefs.getString(IMEI2_KEY, "") ?: "" }
                val isActive = pendingData.optInt(
                    "is_active",
                    pendingData.optInt("status", UNINSTALL_IS_ACTIVE_STATUS)
                )

                if (imei1.isEmpty()) {
                    Log.e(TAG, "❌ IMEI 1 missing in pending sync")
                    callback?.invoke(false)
                    return@thread
                }

                val success = makeApiCall(imei1, imei2, isActive)

                if (success) {
                    prefs.edit().remove(PENDING_UNINSTALL_SYNC_KEY).apply()
                    Log.d(TAG, "✅ Pending uninstall data synced successfully")
                } else {
                    Log.w(TAG, "⚠️ Pending uninstall sync failed - will retry later")
                }

                callback?.invoke(success)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error syncing pending uninstall status: ${e.message}")
                callback?.invoke(false)
            }
        }
    }

    fun hasPendingUninstallSync(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(PENDING_UNINSTALL_SYNC_KEY, null).isNullOrEmpty()
    }
}
