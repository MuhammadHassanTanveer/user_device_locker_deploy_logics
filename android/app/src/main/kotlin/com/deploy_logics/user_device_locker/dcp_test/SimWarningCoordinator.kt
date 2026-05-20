package com.deploy_logics.user_device_locker

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * No SIM: persistent top text strip over all apps.
 * Notification: once per no-SIM period only; if user dismisses it, not sent again
 * until a SIM is inserted (slot 1 or 2) and removed again.
 */
object SimWarningCoordinator {

    private const val TAG = "SimWarningCoordinator"
    private const val PREFS_NAME = "sim_warning_prefs"
    private const val KEY_NOTIFICATION_SENT = "no_sim_notification_sent"
    private const val CHANNEL_ID = "sim_missing_channel"
    private const val NOTIFICATION_ID = 4102
    private const val REFRESH_INTERVAL_MS = 30_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private var monitoringContext: Context? = null

    private val periodicRefresh = object : Runnable {
        override fun run() {
            monitoringContext?.let { refreshWarningUi(it) }
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    private const val FLUTTER_PREFS = "FlutterSharedPreferences"
    private const val RETAILER_NAME_KEY = "flutter.retailer_name"
    private const val RETAILER_NAME_URDU_KEY = "flutter.retailer_name_urdu"
    private const val RETAILER_PHONE_KEY = "flutter.retailer_phone"

    private const val BASE_MESSAGE_URDU =
        "ڈیوائس قرض میں ہے۔ اگر یہ زائد المعاد ہے تو، افعال محدود ہو سکتے ہیں۔"

    /** Builds overlay/notification text with retailer (created-by user) contact if saved. */
    fun buildNoSimWarningMessage(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(
            FLUTTER_PREFS,
            Context.MODE_PRIVATE,
        )

        var nameUrdu = prefs.getString(RETAILER_NAME_URDU_KEY, "")?.trim().orEmpty()
        var nameEn = prefs.getString(RETAILER_NAME_KEY, "")?.trim().orEmpty()
        var phone = prefs.getString(RETAILER_PHONE_KEY, "")?.trim().orEmpty()

        if (nameUrdu == "null") nameUrdu = ""
        if (nameEn == "null") nameEn = ""
        if (phone == "null") phone = ""

        val companyLabel = when {
            nameUrdu.isNotEmpty() && nameEn.isNotEmpty() && nameUrdu != nameEn ->
                "$nameUrdu / $nameEn"
            nameUrdu.isNotEmpty() -> nameUrdu
            nameEn.isNotEmpty() -> nameEn
            else -> ""
        }

        if (companyLabel.isEmpty() && phone.isEmpty()) {
            return BASE_MESSAGE_URDU
        }

        val contact = StringBuilder("\n")
        contact.append("کسی بھی سوال کیلئے رابطہ: ")
        if (companyLabel.isNotEmpty()) {
            contact.append(companyLabel)
        }
        if (phone.isNotEmpty()) {
            if (companyLabel.isNotEmpty()) contact.append(" — ")
            contact.append(phone)
        }
        return BASE_MESSAGE_URDU + contact.toString()
    }

    fun registerMonitoring(context: Context) {
        val appContext = context.applicationContext
        monitoringContext = appContext
        SimStateReceiver.register(appContext)
        refreshWarningUi(appContext)
        mainHandler.removeCallbacks(periodicRefresh)
        mainHandler.postDelayed(periodicRefresh, REFRESH_INTERVAL_MS)
        Log.d(TAG, "SIM monitoring registered")
    }

    fun refreshWarningUi(context: Context) {
        val appContext = context.applicationContext
        val showWarning = SimDetailsCollector.shouldShowNoSimWarning(appContext)

        if (showWarning) {
            val message = buildNoSimWarningMessage(appContext)
            SimWarningOverlay.show(appContext, message)
            if (!wasNotificationSent(appContext)) {
                postMissingSimNotification(appContext, message)
                setNotificationSent(appContext, true)
                Log.d(TAG, "No-SIM: overlay shown + notification posted (once)")
            } else {
                Log.d(TAG, "No-SIM: overlay only (notification already sent this period)")
            }
        } else {
            SimWarningOverlay.hide(appContext)
            NotificationManagerCompat.from(appContext).cancel(NOTIFICATION_ID)
            if (wasNotificationSent(appContext)) {
                setNotificationSent(appContext, false)
                Log.d(TAG, "SIM inserted — overlay hidden, notification period reset")
            }
        }
    }

    fun onSimStateChanged(context: Context) {
        refreshWarningUi(context)
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun wasNotificationSent(context: Context): Boolean =
        prefs(context).getBoolean(KEY_NOTIFICATION_SENT, false)

    private fun setNotificationSent(context: Context, sent: Boolean) {
        prefs(context).edit().putBoolean(KEY_NOTIFICATION_SENT, sent).apply()
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SIM alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "One-time alert when no SIM card is inserted"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /**
     * Dismissible notification (not ongoing). User may clear it; we will not repost
     * until SIM is inserted and removed again.
     */
    @SuppressLint("MissingPermission")
    private fun postMissingSimNotification(context: Context, message: String) {
        ensureChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("Device Guardian")
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setOnlyAlertOnce(true)
            .setAutoCancel(true)
            .setOngoing(false)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.e(TAG, "Cannot post notification: ${e.message}")
        }
    }
}
