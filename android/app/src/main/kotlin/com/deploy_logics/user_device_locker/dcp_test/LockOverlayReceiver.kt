package com.deploy_logics.user_device_locker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Receives broadcast to show/hide lock overlay
 * Can be triggered from Flutter or other components
 */
class LockOverlayReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "LockOverlayReceiver"
        const val ACTION_SHOW_OVERLAY = "com.deploy_logics.user_device_locker.SHOW_OVERLAY"
        const val ACTION_HIDE_OVERLAY = "com.deploy_logics.user_device_locker.HIDE_OVERLAY"
        const val ACTION_ENABLE_STATUS_BAR = "com.deploy_logics.user_device_locker.ENABLE_STATUS_BAR"
        const val ACTION_DISABLE_STATUS_BAR = "com.deploy_logics.user_device_locker.DISABLE_STATUS_BAR"
        const val EXTRA_USER_ID = "userId"
        const val EXTRA_PIN = "pin"

        /**
         * Send broadcast to show overlay
         */
        fun showOverlay(context: Context, userId: String = "", pin: String = "1234") {
            Log.d(TAG, "Sending show overlay broadcast - userId: $userId, pin: $pin")
            val intent = Intent(ACTION_SHOW_OVERLAY).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_USER_ID, userId)
                putExtra(EXTRA_PIN, pin)
            }
            context.sendBroadcast(intent)
        }

        /**
         * Send broadcast to hide overlay
         */
        fun hideOverlay(context: Context) {
            Log.d(TAG, "Sending hide overlay broadcast")
            val intent = Intent(ACTION_HIDE_OVERLAY).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }

        /**
         * Send broadcast to enable status bar (unlock)
         */
        fun enableStatusBar(context: Context) {
            Log.d(TAG, "Sending enable status bar broadcast")
            val intent = Intent(ACTION_ENABLE_STATUS_BAR).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }

        /**
         * Send broadcast to disable status bar (lock)
         */
        fun disableStatusBar(context: Context) {
            Log.d(TAG, "Sending disable status bar broadcast")
            val intent = Intent(ACTION_DISABLE_STATUS_BAR).apply {
                setPackage(context.packageName)
            }
            context.sendBroadcast(intent)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "===========================================")
        Log.d(TAG, "onReceive called!")
        Log.d(TAG, "Action: ${intent.action}")
        Log.d(TAG, "Extras: ${intent.extras}")
        Log.d(TAG, "===========================================")
        Log.d(TAG, "===========================================")

        when (intent.action) {
            ACTION_SHOW_OVERLAY -> {
                // Try different ways to get extras (Flutter sends them differently)
                var userId = intent.getStringExtra(EXTRA_USER_ID) ?: ""
                var pin = intent.getStringExtra(EXTRA_PIN) ?: ""

                // Also try getting from bundle (Flutter may send as bundle)
                intent.extras?.let { extras ->
                    if (userId.isEmpty()) {
                        userId = extras.getString(EXTRA_USER_ID, "")
                    }
                    if (pin.isEmpty()) {
                        pin = extras.getString(EXTRA_PIN, "")
                    }
                    // Also try flutter's argument key format
                    if (userId.isEmpty()) {
                        userId = extras.getString("flutter.userId", "")
                    }
                    if (pin.isEmpty()) {
                        pin = extras.getString("flutter.pin", "")
                    }
                }

                // If no PIN provided, get from SharedPreferences
                if (pin.isEmpty()) {
                    val prefs = context.getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
                    pin = UnlockCodeApi.getStoredUnlockCode(prefs).ifEmpty { "000000" }
                    Log.d(TAG, "Using existing lock code from SharedPreferences: $pin")
                }

                Log.d(TAG, "Showing overlay - userId: '$userId', pin: '$pin'")
                LockOverlayService.show(context, userId, pin)
            }
            ACTION_HIDE_OVERLAY -> {
                Log.d(TAG, "Hiding overlay")
                LockOverlayService.hide(context)
            }
            ACTION_ENABLE_STATUS_BAR -> {
                Log.d(TAG, "Enabling status bar via broadcast")
                try {
                    val dpmHelper = DevicePolicyManagerHelper(context)
                    if (dpmHelper.isDeviceOwner()) {
                        val result = dpmHelper.setStatusBarDisabled(false)
                        Log.d(TAG, "✅ Status bar enabled: $result")

                        // Also unlock settings
                        val unlockResult = dpmHelper.unlockSettingsWhenOverlayHidden()
                        Log.d(TAG, "✅ Settings unlocked: $unlockResult")
                    } else {
                        Log.d(TAG, "⚠️ Not device owner, cannot enable status bar")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error enabling status bar: ${e.message}")
                }
            }
            ACTION_DISABLE_STATUS_BAR -> {
                Log.d(TAG, "Disabling status bar via broadcast")
                try {
                    val dpmHelper = DevicePolicyManagerHelper(context)
                    if (dpmHelper.isDeviceOwner()) {
                        val result = dpmHelper.setStatusBarDisabled(true)
                        Log.d(TAG, "✅ Status bar disabled: $result")

                        // Also lock settings
                        val lockResult = dpmHelper.lockSettingsWhenOverlayShown()
                        Log.d(TAG, "✅ Settings locked: $lockResult")
                    } else {
                        Log.d(TAG, "⚠️ Not device owner, cannot disable status bar")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error disabling status bar: ${e.message}")
                }
            }
            else -> {
                Log.d(TAG, "Unknown action: ${intent.action}")
            }
        }
    }
}


