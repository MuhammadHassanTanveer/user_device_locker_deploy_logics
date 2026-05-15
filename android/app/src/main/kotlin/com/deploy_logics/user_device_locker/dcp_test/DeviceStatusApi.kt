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
    private const val BASE_URL = "https://api.deviceguardian.net/api"
    private const val PREFS_NAME = "FlutterSharedPreferences"
    private const val IMEI1_KEY = "flutter.device_imei1"
    private const val IMEI2_KEY = "flutter.device_imei2"
    private const val PENDING_SYNC_KEY = "flutter.pending_device_status_sync"

    /**
     * Normalize lock status for API body: server expects **1** = locked, **0** = unlocked.
     * Accepts legacy strings ("lock" / "unlock") for call-site compatibility.
     */
    fun actualLockStatusToInt(status: String): Int {
        val s = status.trim().lowercase()
        if (s == "1" || s == "true" || s == "lock" || s == "locked") return 1
        if (s == "0" || s == "false" || s == "unlock" || s == "unlocked") return 0
        return s.toIntOrNull()?.let { if (it != 0) 1 else 0 } ?: 0
    }

    /**
     * Update actual device status on the server
     * @param actualDeviceStatus "lock"|"unlock" or "1"|"0" — sent as integer in JSON
     */
    fun updateActualDeviceStatus(
        context: Context,
        lockCode: String,
        actualDeviceStatus: String
    ) {
        thread {
            try {
                val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val imei1 = prefs.getString(IMEI1_KEY, "") ?: ""
                val imei2 = prefs.getString(IMEI2_KEY, "") ?: ""

                if (imei1.isEmpty()) {
                    Log.e(TAG, "❌ IMEI 1 not found - cannot update device status")
                    return@thread
                }

                Log.d(TAG, "📤 Calling updateActualDeviceStatus API")
                Log.d(TAG, "   imei_1: $imei1")
                Log.d(TAG, "   imei_2: $imei2")
                val statusInt = actualLockStatusToInt(actualDeviceStatus)
                Log.d(TAG, "   actual_lock_status: $statusInt")

                val success = makeApiCall(imei1, imei2, statusInt)

                if (success) {
                    Log.d(TAG, "✅ Device status updated successfully on server")
                    prefs.edit().remove(PENDING_SYNC_KEY).apply()
                } else {
                    Log.w(TAG, "⚠️ API call failed - saving for later sync")
                    savePendingSync(prefs, imei1, imei2, actualLockStatusToInt(actualDeviceStatus))
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error updating device status: ${e.message}")
                e.printStackTrace()

                try {
                    val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    val imei1 = prefs.getString(IMEI1_KEY, "") ?: ""
                    val imei2 = prefs.getString(IMEI2_KEY, "") ?: ""
                    if (imei1.isNotEmpty()) {
                        savePendingSync(prefs, imei1, imei2, actualLockStatusToInt(actualDeviceStatus))
                    }
                } catch (e2: Exception) {
                    Log.e(TAG, "❌ Error saving pending sync: ${e2.message}")
                }
            }
        }
    }

    private fun makeApiCall(imei1: String, imei2: String, actualLockStatus: Int): Boolean {
        var connection: HttpURLConnection? = null
        try {
            val url = URL("$BASE_URL/mobile/update_actual_device_status")
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            val statusOut = if (actualLockStatus != 0) 1 else 0
            val jsonBody = JSONObject().apply {
                put("imei_1", imei1)
                put("actual_lock_status", statusOut)
                if (imei2.isNotEmpty()) {
                    put("imei_2", imei2)
                }
            }

            Log.d(TAG, "🌐 API URL: $url")
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
        imei1: String,
        imei2: String,
        actualLockStatus: Int
    ) {
        val statusNumeric = if (actualLockStatus != 0) 1 else 0
        val ts = System.currentTimeMillis()
        // Encode `actual_lock_status` as JSON number literals 0/1 (never "lock"/"unlock" strings).
        val pendingJson =
            "{\"imei_1\":${JSONObject.quote(imei1)}," +
                "\"imei_2\":${JSONObject.quote(imei2)}," +
                "\"actual_lock_status\":$statusNumeric," +
                "\"timestamp\":$ts}"
        prefs.edit().putString(PENDING_SYNC_KEY, pendingJson).apply()
        Log.d(TAG, "💾 Pending sync data saved: $pendingJson")
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
                val imei1 = pendingData.optString("imei_1", "")
                    .ifEmpty { prefs.getString(IMEI1_KEY, "") ?: "" }
                val imei2 = pendingData.optString("imei_2", "")
                    .ifEmpty { prefs.getString(IMEI2_KEY, "") ?: "" }
                val statusInt = readPendingLockStatusInt(pendingData)

                if (imei1.isEmpty()) {
                    Log.e(TAG, "❌ IMEI 1 missing in pending sync")
                    return@thread
                }

                val success = makeApiCall(imei1, imei2, statusInt)

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

    private fun readPendingLockStatusInt(pendingData: JSONObject): Int {
        if (pendingData.has("actual_lock_status")) {
            when (val raw = pendingData.opt("actual_lock_status")) {
                is Int -> return if (raw != 0) 1 else 0
                is Number -> return if (raw.toInt() != 0) 1 else 0
                else -> return actualLockStatusToInt(raw?.toString() ?: "0")
            }
        }
        return actualLockStatusToInt(
            pendingData.optString("actual_device_status", "unlock")
        )
    }
}
