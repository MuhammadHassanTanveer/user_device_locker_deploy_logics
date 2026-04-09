package com.deploy_logics.user_device_locker

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Utility class to call the update_customer_is_active_status API
 * This is called when uninstall command is received
 */
object CustomerStatusApi {
    private const val TAG = "CustomerStatusApi"
    private const val BASE_URL = "https://locker.deploylogics.com/api"
    private const val PREFS_NAME = "FlutterSharedPreferences"
    private const val USER_ID_KEY = "flutter.registered_device_id"
    private const val PENDING_UNINSTALL_SYNC_KEY = "flutter.pending_uninstall_status_sync"

    /**
     * Update customer is_active status to inactive (status=2) before uninstall
     * @param context Android context
     * @param callback Optional callback to be invoked after API call completes
     */
    fun updateCustomerStatusForUninstall(
        context: Context,
        callback: ((success: Boolean) -> Unit)? = null
    ) {
        thread {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                // Get user ID (customer_id)
                val userId = getUserId(prefs)

                if (userId == 0L) {
                    Log.e(TAG, "❌ User ID not found - cannot update customer status")
                    callback?.invoke(false)
                    return@thread
                }

                Log.d(TAG, "╔══════════════════════════════════════════════════════════╗")
                Log.d(TAG, "║  📤 Updating Customer Status for Uninstall              ║")
                Log.d(TAG, "║  customer_id: $userId                                    ")
                Log.d(TAG, "║  status: 2 (inactive)                                    ║")
                Log.d(TAG, "╚══════════════════════════════════════════════════════════╝")

                // Check internet connectivity
                if (!isNetworkAvailable(context)) {
                    Log.w(TAG, "⚠️ No internet connection - saving for later sync")
                    savePendingUninstallSync(prefs, userId)
                    callback?.invoke(false)
                    return@thread
                }

                val success = makeApiCall(userId)

                if (success) {
                    Log.d(TAG, "✅ Customer status updated successfully on server")
                    // Clear any pending sync
                    prefs.edit().remove(PENDING_UNINSTALL_SYNC_KEY).apply()
                } else {
                    Log.w(TAG, "⚠️ API call failed - saving for later sync")
                    savePendingUninstallSync(prefs, userId)
                }
                
                callback?.invoke(success)
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error updating customer status: ${e.message}")
                e.printStackTrace()

                // Save for later sync
                try {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val userId = getUserId(prefs)

                    if (userId != 0L) {
                        savePendingUninstallSync(prefs, userId)
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "❌ Error saving pending sync: ${e2.message}")
                }
                
                callback?.invoke(false)
            }
        }
    }

    /**
     * Get user ID from SharedPreferences, handling different storage types
     */
    private fun getUserId(prefs: android.content.SharedPreferences): Long {
        return try {
            prefs.getLong(USER_ID_KEY, 0L)
        } catch (e: ClassCastException) {
            try {
                prefs.getInt(USER_ID_KEY, 0).toLong()
            } catch (e2: Exception) {
                try {
                    prefs.getString(USER_ID_KEY, null)?.toLongOrNull() ?: 0L
                } catch (e3: Exception) {
                    0L
                }
            }
        }
    }

    /**
     * Check if network is available
     */
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

    /**
     * Make the actual API call to update customer status
     */
    private fun makeApiCall(customerId: Long): Boolean {
        var connection: HttpURLConnection? = null
        try {
            // Build URL with query parameters
            val urlString = "$BASE_URL/update_cutomer_is_active_status?customer_id=$customerId&status=2"
            val url = URL(urlString)
            
            Log.d(TAG, "🌐 API URL: $urlString")
            
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            // Get response
            val responseCode = connection.responseCode
            Log.d(TAG, "🌐 Response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                    reader.readText()
                }
                Log.d(TAG, "🌐 Response: $response")

                // Try to parse response as JSON
                try {
                    val jsonResponse = JSONObject(response)
                    return jsonResponse.optBoolean("success", true) // Default to true if not specified
                } catch (e: Exception) {
                    // If response is not JSON, consider it successful if HTTP 200
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

    /**
     * Save pending uninstall status sync for when internet becomes available
     */
    private fun savePendingUninstallSync(
        prefs: android.content.SharedPreferences,
        customerId: Long
    ) {
        val pendingData = JSONObject().apply {
            put("customer_id", customerId)
            put("status", 2)
            put("timestamp", System.currentTimeMillis())
        }
        prefs.edit().putString(PENDING_UNINSTALL_SYNC_KEY, pendingData.toString()).apply()
        Log.d(TAG, "💾 Pending uninstall sync data saved: $pendingData")
    }

    /**
     * Sync any pending customer status updates
     * Call this when internet becomes available
     */
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
                val customerId = pendingData.getLong("customer_id")

                val success = makeApiCall(customerId)

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

    /**
     * Check if there's a pending uninstall sync
     */
    fun hasPendingUninstallSync(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return !prefs.getString(PENDING_UNINSTALL_SYNC_KEY, null).isNullOrEmpty()
    }
}

