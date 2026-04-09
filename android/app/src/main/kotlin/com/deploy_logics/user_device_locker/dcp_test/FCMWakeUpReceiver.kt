package com.deploy_logics.user_device_locker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.google.firebase.messaging.FirebaseMessaging

/**
 * BroadcastReceiver that wakes up on boot and ensures FCM service is active.
 * This helps ensure FCM messages are received even after device reboot or app kill.
 */
class FCMWakeUpReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "FCMWakeUpReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "===========================================")
        Log.d(TAG, "📡 FCMWakeUpReceiver.onReceive() - action: ${intent.action}")
        Log.d(TAG, "===========================================")

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            "android.intent.action.QUICKBOOT_POWERON",
            "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                Log.d(TAG, "📱 Device booted - ensuring FCM is active")
                ensureFCMActive(context)
                startPersistentServiceIfDeviceOwner(context)
                // Register network callback for pending sync
                NetworkChangeReceiver.registerNetworkCallback(context.applicationContext)
                // Sync any pending data
                syncPendingData(context)
            }
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                Log.d(TAG, "📱 App updated - ensuring FCM is active")
                ensureFCMActive(context)
                startPersistentServiceIfDeviceOwner(context)
                // Register network callback for pending sync
                NetworkChangeReceiver.registerNetworkCallback(context.applicationContext)
                // Sync any pending data
                syncPendingData(context)
            }
            else -> {
                Log.d(TAG, "📡 Other action received: ${intent.action}")
            }
        }
    }

    private fun syncPendingData(context: Context) {
        try {
            Log.d(TAG, "🔄 Checking for pending data to sync...")
            // Sync pending device status
            DeviceStatusApi.syncPendingStatus(context)
            // Sync pending uninstall status
            CustomerStatusApi.syncPendingUninstallStatus(context, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error syncing pending data: ${e.message}")
        }
    }

    private fun ensureFCMActive(context: Context) {
        try {
            // Trigger FCM token refresh which will ensure the service is registered
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "✅ FCM token retrieved: ${task.result?.take(20)}...")
                } else {
                    Log.e(TAG, "❌ Failed to get FCM token: ${task.exception?.message}")
                }
            }
            Log.d(TAG, "✅ FCM activation triggered")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error activating FCM: ${e.message}")
        }
    }

    private fun startPersistentServiceIfDeviceOwner(context: Context) {
        try {
            val dpmHelper = DevicePolicyManagerHelper(context)
            if (dpmHelper.isDeviceOwner()) {
                Log.d(TAG, "📡 Device Owner app - starting persistent FCM service")
                PersistentFCMService.start(context)
                
                // IMPORTANT: Ensure FRP is set on every boot
                ensureFrpSetup(context, dpmHelper)
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting persistent service: ${e.message}")
        }
    }
    
    /**
     * Ensure FRP (Factory Reset Protection) is configured.
     * This is called on every boot to make sure FRP is always set.
     */
    private fun ensureFrpSetup(context: Context, dpmHelper: DevicePolicyManagerHelper) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.d(TAG, "FRP requires Android 11+")
            return
        }
        
        try {
            val frpAccounts = dpmHelper.getFrpAccounts()
            if (frpAccounts.isEmpty()) {
                Log.d(TAG, "⚠️ FRP not configured, setting up on boot...")
                val defaultFrpEmail = "ghazanfar@tech4uk.uk"
                val success = dpmHelper.setFrpAccount(defaultFrpEmail)
                if (success) {
                    Log.d(TAG, "✅ FRP account set on boot: $defaultFrpEmail")
                } else {
                    Log.w(TAG, "❌ Failed to set FRP account on boot")
                }
            } else {
                Log.d(TAG, "✅ FRP already configured: $frpAccounts")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up FRP on boot: ${e.message}")
        }
    }
}


