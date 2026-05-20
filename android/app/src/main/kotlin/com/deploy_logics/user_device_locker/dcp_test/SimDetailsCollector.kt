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
    private const val API_BASE = "https://api.deviceguardian.net/api"

    data class SimSlotDetails(
        val slotIndex: Int,
        val networkName: String,
        val phoneNumber: String,
        val countryIso: String,
        val displayName: String,
        val inserted: Boolean,
    )

    /** Physical slots 0 and 1 — always length 2 on dual-SIM devices. */
    data class SimSnapshot(
        val slot0: SimSlotDetails?,
        val slot1: SimSlotDetails?,
        val insertedCount: Int,
        val dualSimDevice: Boolean,
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
    fun collectSnapshot(context: Context): SimSnapshot {
        if (!ensurePhonePermissions(context)) {
            return SimSnapshot(null, null, 0, false)
        }

        val appContext = context.applicationContext
        val telephonyManager =
            appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val dualSim = telephonyManager.phoneCount >= 2
        val maxSlot = if (dualSim) 1 else 0

        val slot0 = collectSlot(appContext, telephonyManager, maxSlot, 0)
        val slot1 = if (dualSim) collectSlot(appContext, telephonyManager, maxSlot, 1) else null

        val insertedCount = listOfNotNull(slot0, slot1).count { it.inserted }
        Log.d(
            TAG,
            "Snapshot dual=$dualSim inserted=$insertedCount " +
                "slot0=${slot0?.networkName ?: "empty"}/${slot0?.phoneNumber?.ifEmpty { "-" }} " +
                "slot1=${slot1?.networkName ?: "empty"}/${slot1?.phoneNumber?.ifEmpty { "-" }}",
        )
        return SimSnapshot(slot0, slot1, insertedCount, dualSim)
    }

    /** @deprecated Use [collectSnapshot]; kept for callers expecting a list. */
    @SuppressLint("MissingPermission")
    fun collectSlots(context: Context): List<SimSlotDetails> {
        val snapshot = collectSnapshot(context)
        return listOfNotNull(snapshot.slot0, snapshot.slot1).filter { it.inserted }
    }

    @SuppressLint("MissingPermission")
    private fun collectSlot(
        context: Context,
        telephonyManager: TelephonyManager,
        maxSlot: Int,
        slotIndex: Int,
    ): SimSlotDetails? {
        if (slotIndex > maxSlot) return null

        val inserted = isSimInserted(context, telephonyManager, slotIndex)
        if (!inserted) {
            return SimSlotDetails(
                slotIndex = slotIndex,
                networkName = "",
                phoneNumber = "",
                countryIso = "",
                displayName = "",
                inserted = false,
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val subscriptionManager =
                context.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                try {
                    val info = subscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(slotIndex)
                    if (info != null) {
                        return buildFromSubscription(
                            context,
                            subscriptionManager,
                            telephonyManager,
                            info,
                            inserted = true,
                        )
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "getActiveSubscriptionInfoForSimSlotIndex($slotIndex): ${e.message}")
                }
            }

            val list = subscriptionManager.activeSubscriptionInfoList
            if (list != null) {
                val match = list.firstOrNull { it.simSlotIndex == slotIndex }
                if (match != null) {
                    return buildFromSubscription(
                        context,
                        subscriptionManager,
                        telephonyManager,
                        match,
                        inserted = true,
                    )
                }
            }
        }

        // SIM present but no active subscription — read what we can from TelephonyManager.
        val perSlotTm = telephonyManagerForSlot(context, telephonyManager, slotIndex)
        return SimSlotDetails(
            slotIndex = slotIndex,
            networkName = resolveLegacyNetworkName(perSlotTm),
            phoneNumber = perSlotTm.line1Number?.trim().orEmpty(),
            countryIso = perSlotTm.simCountryIso?.trim()?.uppercase().orEmpty(),
            displayName = perSlotTm.simOperatorName?.trim().orEmpty(),
            inserted = true,
        )
    }

    @SuppressLint("MissingPermission")
    private fun telephonyManagerForSlot(
        context: Context,
        telephonyManager: TelephonyManager,
        slotIndex: Int,
    ): TelephonyManager {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                val subId = getSubscriptionIdForSlot(context, slotIndex)
                if (subId != SubscriptionManager.INVALID_SUBSCRIPTION_ID) {
                    return telephonyManager.createForSubscriptionId(subId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "createForSubscriptionId slot $slotIndex: ${e.message}")
            }
        }
        return telephonyManager
    }

    @SuppressLint("MissingPermission")
    private fun getSubscriptionIdForSlot(
        context: Context,
        slotIndex: Int,
    ): Int {
        val ctx = context.applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            val subscriptionManager =
                ctx.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as SubscriptionManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val info = subscriptionManager.getActiveSubscriptionInfoForSimSlotIndex(slotIndex)
                if (info != null) return info.subscriptionId
            }
            subscriptionManager.activeSubscriptionInfoList?.forEach { info ->
                if (info.simSlotIndex == slotIndex) return info.subscriptionId
            }
        }
        return SubscriptionManager.INVALID_SUBSCRIPTION_ID
    }

    @SuppressLint("MissingPermission")
    private fun getSimStateForSlot(telephonyManager: TelephonyManager, slotIndex: Int): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                telephonyManager.getSimState(slotIndex)
            } catch (e: Exception) {
                telephonyManager.simState
            }
        } else {
            telephonyManager.simState
        }
    }

    @SuppressLint("MissingPermission")
    private fun hasActiveSubscriptionForSlot(context: Context, slotIndex: Int): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP_MR1) return false
        val sm = context.applicationContext.getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
            as SubscriptionManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                if (sm.getActiveSubscriptionInfoForSimSlotIndex(slotIndex) != null) return true
            } catch (_: Exception) {
            }
        }
        val list = sm.activeSubscriptionInfoList ?: return false
        return list.any { it.simSlotIndex == slotIndex }
    }

    /**
     * True when a SIM is physically present and usable in [slotIndex].
     * Uses active subscription first (reliable on TECNO/Infinix), then SIM state.
     */
    @SuppressLint("MissingPermission")
    fun isSimInserted(context: Context, telephonyManager: TelephonyManager, slotIndex: Int): Boolean {
        if (hasActiveSubscriptionForSlot(context, slotIndex)) return true

        return when (getSimStateForSlot(telephonyManager, slotIndex)) {
            TelephonyManager.SIM_STATE_READY,
            TelephonyManager.SIM_STATE_PIN_REQUIRED,
            TelephonyManager.SIM_STATE_PUK_REQUIRED,
            TelephonyManager.SIM_STATE_NETWORK_LOCKED,
            -> true
            else -> false
        }
    }

    /** @deprecated Use [isSimInserted] with Context. */
    fun isSimInserted(telephonyManager: TelephonyManager, slotIndex: Int): Boolean {
        val state = getSimStateForSlot(telephonyManager, slotIndex)
        return when (state) {
            TelephonyManager.SIM_STATE_READY,
            TelephonyManager.SIM_STATE_PIN_REQUIRED,
            TelephonyManager.SIM_STATE_PUK_REQUIRED,
            TelephonyManager.SIM_STATE_NETWORK_LOCKED,
            -> true
            else -> false
        }
    }

    /**
     * Dual-SIM: show warning only when **no** SIM is in either slot.
     * Single-SIM: show when slot 0 has no SIM.
     */
    @SuppressLint("MissingPermission")
    fun shouldShowNoSimWarning(context: Context): Boolean {
        val appContext = context.applicationContext
        val tm = appContext.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val dual = tm.phoneCount >= 2

        ensurePhonePermissions(appContext)

        val slot0Present = isSimInserted(appContext, tm, 0)
        if (!dual) {
            val show = !slot0Present
            Log.d(TAG, "shouldShowNoSimWarning singleSim slot0=$slot0Present show=$show")
            return show
        }

        val slot1Present = isSimInserted(appContext, tm, 1)
        val show = !slot0Present && !slot1Present
        Log.d(
            TAG,
            "shouldShowNoSimWarning dualSim slot0=$slot0Present slot1=$slot1Present " +
                "phoneCount=${tm.phoneCount} show=$show",
        )
        return show
    }

    @SuppressLint("MissingPermission")
    private fun buildFromSubscription(
        context: Context,
        subscriptionManager: SubscriptionManager,
        telephonyManager: TelephonyManager,
        subscriptionInfo: SubscriptionInfo,
        inserted: Boolean,
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

        val display = subscriptionInfo.displayName?.toString()?.trim().orEmpty()
        return SimSlotDetails(
            slotIndex = slotIndex,
            networkName = resolveNetworkName(subscriptionInfo, perSubTm),
            phoneNumber = resolvePhoneNumber(context, subscriptionManager, perSubTm, subscriptionInfo),
            countryIso = resolveCountryIso(subscriptionInfo, perSubTm),
            displayName = if (isUsableLabel(display)) display else "",
            inserted = inserted,
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

        return ""
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

    /** Pakistan MCC 410 — common operator codes when OEM returns numeric operator only. */
    private val PK_MCC_MNC_CARRIERS = mapOf(
        "41001" to "Jazz",
        "41003" to "Ufone",
        "41004" to "Zong",
        "41006" to "Telenor",
        "41007" to "Jazz",
    )

    private fun carrierNameFromOperatorCode(operatorCode: String?): String {
        val code = operatorCode?.trim().orEmpty()
        if (code.length < 5) return ""
        PK_MCC_MNC_CARRIERS[code]?.let { return it }
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
    fun buildApiJson(snapshot: SimSnapshot, networkType: String): JSONObject {
        val slot0 = snapshot.slot0
        val slot1 = snapshot.slot1

        return JSONObject().apply {
            put("sim_count", snapshot.insertedCount)
            put("sim1_network_name", if (slot0?.inserted == true) slot0.networkName else "")
            put("sim1_number", if (slot0?.inserted == true) slot0.phoneNumber else "")
            put("sim1_country_iso", if (slot0?.inserted == true) slot0.countryIso else "")
            put("sim1_display_name", if (slot0?.inserted == true) slot0.displayName else "")
            put("sim2_network_name", if (slot1?.inserted == true) slot1.networkName else "")
            put("sim2_number", if (slot1?.inserted == true) slot1.phoneNumber else "")
            put("sim2_country_iso", if (slot1?.inserted == true) slot1.countryIso else "")
            put("sim2_display_name", if (slot1?.inserted == true) slot1.displayName else "")
            put("network_type", networkType)
        }
    }

    fun buildApiPayloadMap(context: Context): Map<String, Any> {
        val snapshot = collectSnapshot(context)
        val networkType = resolveNetworkType(context)
        val json = buildApiJson(snapshot, networkType)
        return mapOf(
            "sim_count" to json.optInt("sim_count", 0),
            "sim1_network_name" to json.optString("sim1_network_name", ""),
            "sim1_number" to json.optString("sim1_number", ""),
            "sim1_country_iso" to json.optString("sim1_country_iso", ""),
            "sim1_display_name" to json.optString("sim1_display_name", ""),
            "sim2_network_name" to json.optString("sim2_network_name", ""),
            "sim2_number" to json.optString("sim2_number", ""),
            "sim2_country_iso" to json.optString("sim2_country_iso", ""),
            "sim2_display_name" to json.optString("sim2_display_name", ""),
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

    /**
     * POST sim-details, then show or hide missing-SIM UI.
     */
    fun syncToServer(context: Context): Boolean {
        val posted = sendToServer(context)
        try {
            SimWarningCoordinator.onSimStateChanged(context.applicationContext)
        } catch (e: Exception) {
            Log.e(TAG, "Sim warning update failed: ${e.message}")
        }
        return posted
    }

    fun sendToServer(context: Context): Boolean {
        val customerId = readCustomerId(context)
        if (customerId == 0L) {
            Log.e(TAG, "Customer ID not found — cannot send SIM details")
            return false
        }

        val snapshot = collectSnapshot(context)
        val networkType = resolveNetworkType(context)
        val jsonBody = buildApiJson(snapshot, networkType)

        Log.d(TAG, "POST sim-details customerId=$customerId body=$jsonBody")

        return try {
            val url = URL("$API_BASE/mobile/sim-details/customer/$customerId")
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 15000
            connection.readTimeout = 15000

            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(jsonBody.toString())
                writer.flush()
            }

            val responseCode = connection.responseCode
            val responseText = if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().readText()
            } else {
                connection.errorStream?.bufferedReader()?.readText() ?: "No error body"
            }
            Log.d(TAG, "SIM details API response: $responseCode — $responseText")
            connection.disconnect()
            responseCode in 200..299
        } catch (e: Exception) {
            Log.e(TAG, "sendToServer failed: ${e.message}")
            false
        }
    }
}
