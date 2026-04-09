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
 * Utility class to call the update_actual_device_status API
 * This is called when lock/unlock commands are received
 */
object DeviceStatusApi {
    private const val TAG = "DeviceStatusApi"
    private const val BASE_URL = "https://locker.deploylogics.com/api"
    private const val PREFS_NAME = "FlutterSharedPreferences"
    private const val USER_ID_KEY = "flutter.registered_device_id"
    private const val LOCK_CODE_KEY = "flutter.lock_code"
    private const val LOCK_PIN_KEY = "flutter.lock_pin"
    private const val PENDING_SYNC_KEY = "flutter.pending_device_status_sync"

    /**
     * Update actual device status on the server
     * @param context Android context
     * @param lockCode The current lock code
     * @param actualDeviceStatus Either "lock" or "unlock"
     */
    fun updateActualDeviceStatus(
        context: Context,
        lockCode: String,
        actualDeviceStatus: String
    ) {
        thread {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

                // Get user ID (customer_id)
                val userId = try {
                    prefs.getLong(USER_ID_KEY, 0L)
                } catch (e: ClassCastException) {
                    try {
                        prefs.getInt(USER_ID_KEY, 0).toLong()
                    } catch (e2: Exception) {
                        0L
                    }
                }

                if (userId == 0L) {
                    Log.e(TAG, "❌ User ID not found - cannot update device status")
                    return@thread
                }

                Log.d(TAG, "📤 Calling updateActualDeviceStatus API")
                Log.d(TAG, "   customer_id: $userId")
                Log.d(TAG, "   lock_code: $lockCode")
                Log.d(TAG, "   actual_device_status: $actualDeviceStatus")

                val success = makeApiCall(userId, lockCode, actualDeviceStatus)

                if (success) {
                    Log.d(TAG, "✅ Device status updated successfully on server")
                    // Clear any pending sync
                    prefs.edit().remove(PENDING_SYNC_KEY).apply()
                } else {
                    Log.w(TAG, "⚠️ API call failed - saving for later sync")
                    // Save for later sync when internet is available
                    savePendingSync(prefs, userId, lockCode, actualDeviceStatus)
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error updating device status: ${e.message}")
                e.printStackTrace()

                // Save for later sync
                try {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val userId = try {
                        prefs.getLong(USER_ID_KEY, 0L)
                    } catch (e: Exception) { 0L }

                    if (userId != 0L) {
                        savePendingSync(prefs, userId, lockCode, actualDeviceStatus)
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "❌ Error saving pending sync: ${e2.message}")
                }
            }
        }
    }

    private fun makeApiCall(customerId: Long, lockCode: String, actualDeviceStatus: String): Boolean {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/update_actual_device_status")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            // Create JSON body
            val jsonBody = JSONObject().apply {
                put("customer_id", customerId)
                put("lock_code", lockCode)
                put("actual_device_status", actualDeviceStatus)
            }

            Log.d(TAG, "🌐 API URL: ${url}")
            Log.d(TAG, "🌐 Request body: $jsonBody")

            // Write body
            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonBody.toString())
                writer.flush()
            }

            // Get response
            val responseCode = connection.responseCode
            Log.d(TAG, "🌐 Response code: $responseCode")

            if (responseCode == HttpURLConnection.HTTP_OK) {
                val response = BufferedReader(InputStreamReader(connection.inputStream)).use { reader ->
                    reader.readText()
                }
                Log.d(TAG, "🌐 Response: $response")

                val jsonResponse = JSONObject(response)
                return jsonResponse.optBoolean("success", false)
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

    private fun savePendingSync(
        prefs: android.content.SharedPreferences,
        customerId: Long,
        lockCode: String,
        actualDeviceStatus: String
    ) {
        val pendingData = JSONObject().apply {
            put("customer_id", customerId)
            put("lock_code", lockCode)
            put("actual_device_status", actualDeviceStatus)
            put("timestamp", System.currentTimeMillis())
        }
        prefs.edit().putString(PENDING_SYNC_KEY, pendingData.toString()).apply()
        Log.d(TAG, "💾 Pending sync data saved: $pendingData")
    }

    /**
     * Sync any pending device status updates
     * Call this when internet becomes available
     */
    fun syncPendingStatus(context: Context) {
        thread {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val pendingDataStr = prefs.getString(PENDING_SYNC_KEY, null)

                if (pendingDataStr.isNullOrEmpty()) {
                    Log.d(TAG, "No pending sync data found")
                    return@thread
                }

                Log.d(TAG, "📤 Found pending sync data: $pendingDataStr")

                val pendingData = JSONObject(pendingDataStr)
                val customerId = pendingData.getLong("customer_id")
                val lockCode = pendingData.getString("lock_code")
                val actualDeviceStatus = pendingData.getString("actual_device_status")

                val success = makeApiCall(customerId, lockCode, actualDeviceStatus)

                if (success) {
                    prefs.edit().remove(PENDING_SYNC_KEY).apply()
                    Log.d(TAG, "✅ Pending data synced successfully")
                } else {
                    Log.w(TAG, "⚠️ Pending sync failed - will retry later")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error syncing pending status: ${e.message}")
            }
        }
    }
}

