package com.deploy_logics.user_device_locker

import android.app.Notification
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * NotificationListenerService that intercepts FCM notifications
 * This is used as a fallback when FCM notifications have a notification payload
 * and onMessageReceived is not called when app is killed.
 */
class FCMNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "FCMNotificationListener"
        private const val FCM_PACKAGE = "com.google.android.gms"
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FCMNotificationListener created")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return

        // Only process notifications from our app
        if (sbn.packageName != packageName) return

        Log.d(TAG, "===========================================")
        Log.d(TAG, "📬 Notification posted for our app!")
        Log.d(TAG, "Package: ${sbn.packageName}")
        Log.d(TAG, "ID: ${sbn.id}")
        Log.d(TAG, "Tag: ${sbn.tag}")
        Log.d(TAG, "===========================================")

        val notification = sbn.notification
        val extras = notification.extras

        if (extras != null) {
            Log.d(TAG, "Notification extras keys: ${extras.keySet()}")

            // Try to extract device command from notification extras
            val deviceCommand = extractDeviceCommand(extras)

            if (!deviceCommand.isNullOrEmpty()) {
                Log.d(TAG, "🎯 Device command found in notification: $deviceCommand")
                executeCommand(deviceCommand, extras)

                // Cancel the notification since we've handled it
                cancelNotification(sbn.key)
            }
        }
    }

    private fun extractDeviceCommand(extras: Bundle): String? {
        // Try various keys that FCM might use
        val possibleKeys = listOf(
            "device", "Device", "DEVICE",
            "android.text", "android.bigText",
            "google.message_id", "google.c.a.c_l"
        )

        for (key in possibleKeys) {
            val value = extras.getString(key)
            if (!value.isNullOrEmpty() && isValidCommand(value.lowercase())) {
                return value.lowercase()
            }
        }

        // Also check in nested bundles
        for (key in extras.keySet()) {
            val value = extras.get(key)
            if (value is Bundle) {
                val nestedCommand = extractDeviceCommand(value)
                if (!nestedCommand.isNullOrEmpty()) {
                    return nestedCommand
                }
            }
        }

        return null
    }

    private fun isValidCommand(command: String): Boolean {
        val validCommands = listOf(
            "lock", "unlock",
            "enable_camera", "disable_camera",
            "enable_location", "disable_location",
            "enable_location_config", "disable_location_config",
            "turn_on_location_lock", "turn_off_location_lock",
            "enable_factory_reset", "disable_factory_reset",
            "enable_screen_capture", "disable_screen_capture",
            "enable_safe_mode", "disable_safe_mode",
            "enable_usb_transfer", "disable_usb_transfer",
            "enable_install_apps", "disable_install_apps",
            "enable_uninstall_apps", "disable_uninstall_apps",
            "enable_wifi_config", "disable_wifi_config",
            "enable_bluetooth_config", "disable_bluetooth_config",
            "enable_volume", "disable_volume",
            "enable_outgoing_calls", "disable_outgoing_calls",
            "enable_sms", "disable_sms",
            "enable_notification_permission", "disable_notification_permission",
            "enable_notifications", "disable_notifications",
            "lock_notification_permission", "unlock_notification_permission",
            "get_current_location",
            "uninstall", "prepare_uninstall", "allow_uninstall"
        )
        return validCommands.contains(command)
    }

    private fun executeCommand(command: String, extras: Bundle) {
        Log.d(TAG, "Executing command via NotificationListener: $command")

        val intent = Intent(this, DeviceCommandService::class.java).apply {
            action = DeviceCommandService.ACTION_EXECUTE_COMMAND
            putExtra(DeviceCommandService.EXTRA_COMMAND, command)
            putExtras(extras)
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d(TAG, "✅ DeviceCommandService started for: $command")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting DeviceCommandService: ${e.message}")
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Optional: handle notification removal
    }
}

