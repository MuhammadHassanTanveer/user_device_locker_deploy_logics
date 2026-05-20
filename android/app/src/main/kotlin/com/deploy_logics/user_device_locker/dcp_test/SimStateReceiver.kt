package com.deploy_logics.user_device_locker

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Listens for SIM insert/remove and syncs API + warning UI.
 */
class SimStateReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SimStateReceiver"

        // String actions — TelephonyManager constants for these require API 33+.
        private const val ACTION_SIM_CARD_STATE_CHANGED =
            "android.telephony.action.SIM_CARD_STATE_CHANGED"
        private const val ACTION_SIM_APPLICATION_STATE_CHANGED =
            "android.telephony.action.SIM_APPLICATION_STATE_CHANGED"
        private const val ACTION_SUBSCRIPTION_INFO_RECORD_UPDATED =
            "android.telephony.action.SUBSCRIPTION_INFO_RECORD_UPDATED"
        private const val ACTION_LEGACY_SIM_STATE_CHANGED =
            "android.intent.action.SIM_STATE_CHANGED"

        @Volatile
        private var registered = false
        private val receiver = SimStateReceiver()

        fun register(appContext: Context) {
            if (registered) return
            synchronized(this) {
                if (registered) return
                val filter = IntentFilter().apply {
                    addAction(ACTION_LEGACY_SIM_STATE_CHANGED)
                    addAction(ACTION_SUBSCRIPTION_INFO_RECORD_UPDATED)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        addAction(ACTION_SIM_CARD_STATE_CHANGED)
                        addAction(ACTION_SIM_APPLICATION_STATE_CHANGED)
                    }
                }
                try {
                    ContextCompat.registerReceiver(
                        appContext,
                        receiver,
                        filter,
                        ContextCompat.RECEIVER_NOT_EXPORTED,
                    )
                    registered = true
                    Log.d(TAG, "SIM state receiver registered")
                    Thread {
                        SimDetailsCollector.syncToServer(appContext)
                    }.start()
                    SimWarningCoordinator.refreshWarningUi(appContext)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to register SIM receiver: ${e.message}")
                }
            }
        }
    }

    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        Log.d(TAG, "SIM broadcast: $action")
        val appContext = context.applicationContext
        SimWarningCoordinator.refreshWarningUi(appContext)
        Thread {
            try {
                SimDetailsCollector.syncToServer(appContext)
            } catch (e: Exception) {
                Log.e(TAG, "SIM sync failed: ${e.message}")
            }
        }.start()
    }
}
