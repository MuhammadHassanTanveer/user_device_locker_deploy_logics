package com.deploy_logics.user_device_locker

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Collects per-slot SIM data and maps to API fields:
 * sim1_* = physical slot 0, sim2_* = physical slot 1 (empty if no SIM in that slot).
 */
object SimDetailsCollector {

    private const val TAG = "SimDetailsCollector"
    private const val PREFS_NAME = "FlutterSharedPreferences"

    data class SimSlotDetails(
        val slotIndex: Int,
        val networkName: String,
        val phoneNumber: String,
        val countryIso: String,
    )

    /** Pakistan MCC 410 — common operator codes when OEM returns numeric operator only. */
    private val PK_MCC_MNC_CARRIERS = mapOf(
        "41001" to "Jazz",
        "41003" to "Ufone",
        "41004" to "Zong",
        "41006" to "Telenor",
        "41007" to "Jazz",
    )

    @SuppressLint("MissingPermission")
    fun ensurePhonePermissions(context: Context): Boolean {
        val appContext = context.applicationContext
        val dpmHelper = DevicePolicyManagerHelper(appContext)
        if (dpmHelper.isDeviceOwner()) {
            val granted = DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            dpmHelper.setPermissionGrantState(
                appContext.packageName,
                Manifest.permission.READ_PHONE_STATE,
                granted,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dpmHelper.setPermissionGrantState(
                    appContext.packageName,
                    Manifest.permission.READ_PHONE_NUMBERS,
                    granted,
                )
            }
        }

        val hasState = ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.READ_PHONE_STATE,
        ) == PackageManager.PERMISSION_GRANTED

        if (!hasState) {
            Log.e(TAG, "READ_PHONE_STATE not granted")
            return false
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNumbers = ContextCompat.checkSelfPermission(
                appContext,
                Manifest.permission.READ_PHONE_NUMBERS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!hasNumbers) {
                Log.w(TAG, "READ_PHONE_NUMBERS not granted — MSISDN may be empty on Android 13+")
            }
        }
        return true
    }

    @SuppressLint("MissingPermission")
    fun collectSlots(context: Context): List<SimSlotDetails> {
        if (!ensurePhonePermissions(context)) {
            return emptyList()
        }

        val appContext = context.applicationContext
        val telephonyManager =
            appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) {
            return listOf(
                SimSlotDetails(
                    slotIndex = 0,
                    networkName = resolveLegacyNetworkName(telephonyManager),
                    phoneNumber = telephonyManager.line1Number?.trim().orEmpty(),
                    countryIso = telephonyManager.simCountryIso?.trim().orEmpty(),
                ),
            ).filter { it.networkName.isNotEmpty() || it.phoneNumber.isNotEmpty() }
        }

        val subscriptionManager =
            appContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager

        val bySlot = linkedMapOf<Int, SimSlotDetails>()

        // Prefer per-slot lookup so SIM in slot 2 only fills sim2_* (not sim1_*).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            for (slotIndex in 0..1) {
                try {
                    val info = subscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(slotIndex)
                    if (info != null) {
                        bySlot[slotIndex] = buildFromSubscription(
                            appContext,
                            subscriptionManager,
                            telephonyManager,
                            info,
                        )
                        Log.d(
                            TAG,
                            "Slot $slotIndex (explicit): network=${bySlot[slotIndex]?.networkName}, " +
                                "number=${bySlot[slotIndex]?.phoneNumber?.ifEmpty { "(empty)" }}",
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "getActiveSubscriptionInfoForSimSlotIndex($slotIndex): ${e.message}")
                }
            }
        }

        if (bySlot.isEmpty()) {
            val list = subscriptionManager.activeSubscriptionInfoList
            if (list != null) {
                for (info in list) {
                    val slot = info.simSlotIndex
                    if (bySlot.containsKey(slot)) continue
                    bySlot[slot] = buildFromSubscription(
                        appContext,
                        subscriptionManager,
                        telephonyManager,
                        info,
                    )
                    Log.d(
                        TAG,
                        "Slot $slot (list): network=${bySlot[slot]?.networkName}, " +
                            "number=${bySlot[slot]?.phoneNumber?.ifEmpty { "(empty)" }}",
                    )
                }
            }
        }

        return bySlot.values.sortedBy { it.slotIndex }
    }

    @SuppressLint("MissingPermission")
    private fun buildFromSubscription(
        context: Context,
        subscriptionManager: SubscriptionManager,
        telephonyManager: TelephonyManager,
        subscriptionInfo: SubscriptionInfo,
    ): SimSlotDetails {
        val slotIndex = subscriptionInfo.simSlotIndex
        val subscriptionId = subscriptionInfo.subscriptionId
        val perSubTm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                telephonyManager.createForSubscriptionId(subscriptionId)
            } catch (_: Exception) {
                telephonyManager
            }
        } else {
            telephonyManager
        }

        return SimSlotDetails(
            slotIndex = slotIndex,
            networkName = resolveNetworkName(subscriptionInfo, perSubTm),
            phoneNumber = resolvePhoneNumber(context, subscriptionManager, perSubTm, subscriptionInfo),
            countryIso = resolveCountryIso(subscriptionInfo, perSubTm),
        )
    }

    @SuppressLint("MissingPermission")
    private fun resolvePhoneNumber(
        context: Context,
        subscriptionManager: SubscriptionManager,
        telephonyManager: TelephonyManager,
        subscriptionInfo: SubscriptionInfo,
    ): String {
        val subscriptionId = subscriptionInfo.subscriptionId

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val hasNumbers = ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.READ_PHONE_NUMBERS,
            ) == PackageManager.PERMISSION_GRANTED
            if (hasNumbers) {
                val fromApi = subscriptionManager.getPhoneNumber(subscriptionId)?.trim().orEmpty()
                if (fromApi.isNotEmpty()) return fromApi
            }
        } else {
            val fromInfo = subscriptionInfo.number?.trim().orEmpty()
            if (fromInfo.isNotEmpty()) return fromInfo
        }

        val fromTm = telephonyManager.line1Number?.trim().orEmpty()
        if (fromTm.isNotEmpty()) return fromTm

        return telephonyManager.line1Number?.trim().orEmpty()
    }

    private fun resolveNetworkName(
        subscriptionInfo: SubscriptionInfo,
        telephonyManager: TelephonyManager,
    ): String {
        val carrier = subscriptionInfo.carrierName?.toString()?.trim().orEmpty()
        if (isUsableLabel(carrier)) return carrier

        val display = subscriptionInfo.displayName?.toString()?.trim().orEmpty()
        if (isUsableLabel(display)) return display

        val simOpName = telephonyManager.simOperatorName?.trim().orEmpty()
        if (isUsableLabel(simOpName)) return simOpName

        val netOpName = telephonyManager.networkOperatorName?.trim().orEmpty()
        if (isUsableLabel(netOpName)) return netOpName

        val fromCode = carrierNameFromOperatorCode(telephonyManager.simOperator)
            .ifEmpty { carrierNameFromOperatorCode(telephonyManager.networkOperator) }
        if (fromCode.isNotEmpty()) return fromCode

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val mcc = subscriptionInfo.mccString?.trim().orEmpty()
            val mnc = subscriptionInfo.mncString?.trim().orEmpty()
            if (mcc.isNotEmpty() && mnc.isNotEmpty()) {
                val fromMccMnc = carrierNameFromOperatorCode("$mcc${mnc.padStart(2, '0')}")
                if (fromMccMnc.isNotEmpty()) return fromMccMnc
            }
        }

        return carrier.ifEmpty { display.ifEmpty { simOpName.ifEmpty { netOpName } } }
    }

    private fun resolveCountryIso(
        subscriptionInfo: SubscriptionInfo,
        telephonyManager: TelephonyManager,
    ): String {
        val fromSub = subscriptionInfo.countryIso?.trim().orEmpty()
        if (fromSub.isNotEmpty()) return fromSub.uppercase()
        val fromTm = telephonyManager.simCountryIso?.trim().orEmpty()
        if (fromTm.isNotEmpty()) return fromTm.uppercase()
        return ""
    }

    private fun resolveLegacyNetworkName(telephonyManager: TelephonyManager): String {
        val simOp = telephonyManager.simOperatorName?.trim().orEmpty()
        if (isUsableLabel(simOp)) return simOp
        val netOp = telephonyManager.networkOperatorName?.trim().orEmpty()
        if (isUsableLabel(netOp)) return netOp
        return carrierNameFromOperatorCode(telephonyManager.simOperator)
            .ifEmpty { carrierNameFromOperatorCode(telephonyManager.networkOperator) }
            .ifEmpty { netOp }
    }

    private fun isUsableLabel(value: String): Boolean {
        if (value.isEmpty()) return false
        if (value.equals("Unknown", ignoreCase = true)) return false
        if (value.equals("null", ignoreCase = true)) return false
        return true
    }

    private fun carrierNameFromOperatorCode(operatorCode: String?): String {
        val code = operatorCode?.trim().orEmpty()
        if (code.length < 5) return ""
        PK_MCC_MNC_CARRIERS[code]?.let { return it }
        // Try 5-digit MCC+MNC (e.g. 41004)
        if (code.length >= 5) {
            PK_MCC_MNC_CARRIERS[code.substring(0, 5)]?.let { return it }
        }
        return ""
    }

    @SuppressLint("MissingPermission")
    fun resolveNetworkType(context: Context): String {
        val telephonyManager =
            context.applicationContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            when (telephonyManager.dataNetworkType) {
                TelephonyManager.NETWORK_TYPE_LTE -> "4G LTE"
                TelephonyManager.NETWORK_TYPE_NR -> "5G"
                TelephonyManager.NETWORK_TYPE_HSPAP,
                TelephonyManager.NETWORK_TYPE_HSPA,
                TelephonyManager.NETWORK_TYPE_HSDPA,
                TelephonyManager.NETWORK_TYPE_HSUPA -> "3G HSPA"
                TelephonyManager.NETWORK_TYPE_UMTS -> "3G UMTS"
                TelephonyManager.NETWORK_TYPE_EDGE -> "2G EDGE"
                TelephonyManager.NETWORK_TYPE_GPRS -> "2G GPRS"
                TelephonyManager.NETWORK_TYPE_CDMA,
                TelephonyManager.NETWORK_TYPE_EVDO_0,
                TelephonyManager.NETWORK_TYPE_EVDO_A,
                TelephonyManager.NETWORK_TYPE_EVDO_B -> "CDMA"
                else -> "Unknown"
            }
        } else {
            "Unknown"
        }
    }

    /** API body: sim1 = slot 0, sim2 = slot 1. */
    fun buildApiJson(slots: List<SimSlotDetails>, networkType: String): JSONObject {
        val slot0 = slots.firstOrNull { it.slotIndex == 0 }
        val slot1 = slots.firstOrNull { it.slotIndex == 1 }

        return JSONObject().apply {
            put("sim_count", slots.size)
            put("sim1_network_name", slot0?.networkName ?: "")
            put("sim1_number", slot0?.phoneNumber ?: "")
            put("sim1_country_iso", slot0?.countryIso ?: "")
            put("sim2_network_name", slot1?.networkName ?: "")
            put("sim2_number", slot1?.phoneNumber ?: "")
            put("sim2_country_iso", slot1?.countryIso ?: "")
            put("network_type", networkType)
        }
    }

    fun buildApiPayloadMap(context: Context): Map<String, Any> {
        val slots = collectSlots(context)
        val networkType = resolveNetworkType(context)
        val json = buildApiJson(slots, networkType)
        return mapOf(
            "sim_count" to json.optInt("sim_count", 0),
            "sim1_network_name" to json.optString("sim1_network_name", ""),
            "sim1_number" to json.optString("sim1_number", ""),
            "sim1_country_iso" to json.optString("sim1_country_iso", ""),
            "sim2_network_name" to json.optString("sim2_network_name", ""),
            "sim2_number" to json.optString("sim2_number", ""),
            "sim2_country_iso" to json.optString("sim2_country_iso", ""),
            "network_type" to json.optString("network_type", ""),
        )
    }

    fun readCustomerId(context: Context): Long {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return try {
            prefs.getLong("flutter.registered_device_id", 0L)
        } catch (_: Exception) {
            try {
                prefs.getInt("flutter.registered_device_id", 0).toLong()
            } catch (_: Exception) {
                prefs.getString("flutter.registered_device_id", null)?.toLongOrNull() ?: 0L
            }
        }
    }

    fun sendToServer(context: Context): Boolean {
        val customerId = readCustomerId(context)
        if (customerId == 0L) {
            Log.e(TAG, "Customer ID not found — cannot send SIM details")
            return false
        }

        val slots = collectSlots(context)
        val networkType = resolveNetworkType(context)
        val jsonBody = buildApiJson(slots, networkType)

        Log.d(TAG, "POST sim-details customerId=$customerId body=$jsonBody")

        return try {
            val url = URL(
                "https://api.deviceguardian.net/api/mobile/sim-details/customer/$customerId",
            )
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            OutputStreamWriter(connection.outputStream).use { writer ->
                writer.write(jsonBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            val responseText = if (responseCode == HttpURLConnection.HTTP_OK) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream?.bufferedReader()?.readText() ?: "No error body"
            }
            Log.d(TAG, "SIM details API response: $responseCode — $responseText")
            connection.disconnect()
            responseCode == HttpURLConnection.HTTP_OK
        } catch (e: Exception) {
            Log.e(TAG, "sendToServer failed: ${e.message}")
            false
        }
    }
}
