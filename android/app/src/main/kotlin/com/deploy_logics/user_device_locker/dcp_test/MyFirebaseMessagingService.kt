package com.deploy_logics.user_device_locker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Custom Firebase Messaging Service that handles ALL FCM messages
 * This is the ONLY FCM service - Flutter's is disabled via manifest
 */
class MyFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "MyFirebaseMsgService"
        private const val CHANNEL_ID = "fcm_command_channel"
        private const val NOTIFICATION_ID = 3001
    }

    private lateinit var dpmHelper: DevicePolicyManagerHelper
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "🔥 MyFirebaseMessagingService onCreate() - Service created!")
        dpmHelper = DevicePolicyManagerHelper(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "FCM Commands",
                NotificationManager.IMPORTANCE_HIGH  // HIGH importance for reliability
            ).apply {
                description = "Processing device commands"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MyFirebaseMessagingService::CommandWakeLock"
            ).apply {
                acquire(60 * 1000L) // 60 seconds max
            }
            Log.d(TAG, "🔋 WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Failed to acquire WakeLock: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "🔋 WakeLock released")
                }
            }
            wakeLock = null
        } catch (e: Exception) {
            Log.e(TAG, "⚠️ Failed to release WakeLock: ${e.message}")
        }
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        // Acquire WakeLock immediately to prevent device from sleeping
        acquireWakeLock()

        Log.d(TAG, "===========================================")
        Log.d(TAG, "🔥🔥🔥 FCM Message Received in NATIVE Service! 🔥🔥🔥")
        Log.d(TAG, "From: ${remoteMessage.from}")
        Log.d(TAG, "Message ID: ${remoteMessage.messageId}")
        Log.d(TAG, "Priority: ${remoteMessage.priority}")
        Log.d(TAG, "Original Priority: ${remoteMessage.originalPriority}")
        Log.d(TAG, "Data payload: ${remoteMessage.data}")
        Log.d(TAG, "Data payload size: ${remoteMessage.data.size}")
        Log.d(TAG, "Has notification payload: ${remoteMessage.notification != null}")
        Log.d(TAG, "===========================================")

        // Initialize DPM helper if not already done
        if (!::dpmHelper.isInitialized) {
            dpmHelper = DevicePolicyManagerHelper(this)
        }

        // Check for device command - case-insensitive key lookup
        // Try both "device" and "Device" keys
        val deviceCommand = (remoteMessage.data["device"]
            ?: remoteMessage.data["Device"]
            ?: remoteMessage.data.entries.find { it.key.equals("device", ignoreCase = true) }?.value
        )?.lowercase()
        Log.d(TAG, "Device command extracted: '$deviceCommand'")

        if (deviceCommand.isNullOrEmpty()) {
            Log.d(TAG, "⚠️ No 'device' key found in data payload")
            Log.d(TAG, "Available keys: ${remoteMessage.data.keys}")
            releaseWakeLock()
            return
        }

        Log.d(TAG, "🚀 Processing command: $deviceCommand")
        Log.d(TAG, "📱 Is Device Owner: ${dpmHelper.isDeviceOwner()}")

        // Execute lock/unlock/message_customer commands directly (they don't need foreground service)
        // message_customer commands show an overlay which needs to be shown immediately
        if (deviceCommand == "lock" || deviceCommand == "unlock" || deviceCommand.startsWith("message_customer_")) {
            executeCommandDirectly(deviceCommand, remoteMessage.data, remoteMessage.sentTime)
        } else {
            // For other commands, use foreground service to ensure execution
            startCommandForegroundService(deviceCommand, remoteMessage.data)
        }

        releaseWakeLock()
    }

    /**
     * Start a foreground service to execute the command
     * This ensures the command runs even if the app process was just woken up
     */
    private fun startCommandForegroundService(command: String, data: Map<String, String>) {
        Log.d(TAG, "📤 Starting foreground service for command: $command")

        val intent = Intent(this, DeviceCommandService::class.java).apply {
            action = DeviceCommandService.ACTION_EXECUTE_COMMAND
            putExtra(DeviceCommandService.EXTRA_COMMAND, command)
            for ((key, value) in data) {
                putExtra(key, value)
            }
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Log.d(TAG, "✅ Foreground service started for: $command")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error starting foreground service: ${e.message}")
            // Fallback: execute directly
            Log.d(TAG, "🔄 Falling back to direct execution")
            executeCommandDirectly(command, data)
        }
    }

    /**
     * Execute device command directly in this service
     * This is the same approach that works for lock/unlock
     */
    private fun executeCommandDirectly(
        command: String,
        data: Map<String, String>,
        fcmSentTimeMillis: Long = 0L,
    ) {
        Log.d(TAG, "===========================================")
        Log.d(TAG, ">>> Executing command DIRECTLY: '$command' (fcmSentTime=$fcmSentTimeMillis)")
        Log.d(TAG, "===========================================")

        when (command) {
            // ==================== Device Lock/Unlock ====================
            "lock" -> {
                Log.d(TAG, ">>> LOCK command")
                handleLockCommand(data, fcmSentTimeMillis)
            }
            "unlock" -> {
                Log.d(TAG, ">>> UNLOCK command")
                handleUnlockCommand(fcmSentTimeMillis)
            }

            // ==================== Camera Control ====================
            "enable_camera" -> {
                Log.d(TAG, ">>> ENABLE_CAMERA command")
                val result = dpmHelper.setCameraDisabled(false)
                Log.d(TAG, "✅ Camera enabled: $result")
            }
            "disable_camera" -> {
                Log.d(TAG, ">>> DISABLE_CAMERA command")
                val result = dpmHelper.setCameraDisabled(true)
                Log.d(TAG, "✅ Camera disabled: $result")
            }

            // ==================== Location Control ====================
            "enable_location", "enable_location_config" -> {
                Log.d(TAG, ">>> ENABLE_LOCATION command")
                val result = dpmHelper.enableConfigLocation()
                Log.d(TAG, "✅ Location config enabled: $result")
            }
            "disable_location", "disable_location_config" -> {
                Log.d(TAG, ">>> DISABLE_LOCATION command")
                val result = dpmHelper.disableConfigLocation()
                Log.d(TAG, "✅ Location config disabled: $result")
            }
            "turn_on_location_lock", "enable_location_and_lock" -> {
                Log.d(TAG, ">>> TURN_ON_LOCATION_LOCK command")
                val result = dpmHelper.enableLocationWithLock()
                Log.d(TAG, "✅ Location ON with lock: $result")
            }
            "turn_off_location_lock", "disable_location_and_lock" -> {
                Log.d(TAG, ">>> TURN_OFF_LOCATION_LOCK command")
                val result = dpmHelper.disableLocationWithLock()
                Log.d(TAG, "✅ Location OFF with lock: $result")
            }
            "turn_on_location" -> {
                Log.d(TAG, ">>> TURN_ON_LOCATION command")
                val result = dpmHelper.turnOnLocation()
                Log.d(TAG, "✅ Location turned on: $result")
            }

            // ==================== Factory Reset Control ====================
            "enable_factory_reset" -> {
                Log.d(TAG, ">>> ENABLE_FACTORY_RESET command (will auto-disable FRP)")
                val result = dpmHelper.enableFactoryReset()
                Log.d(TAG, "✅ Factory reset enabled + FRP disabled: $result")
            }
            "disable_factory_reset" -> {
                Log.d(TAG, ">>> DISABLE_FACTORY_RESET command (will auto-enable FRP)")
                val result = dpmHelper.disableFactoryReset()
                Log.d(TAG, "✅ Factory reset disabled + FRP enabled: $result")
            }

            // ==================== Screen Capture Control ====================
            "enable_screen_capture" -> {
                Log.d(TAG, ">>> ENABLE_SCREEN_CAPTURE command")
                val result = dpmHelper.setScreenCaptureDisabled(false)
                Log.d(TAG, "✅ Screen capture enabled: $result")
            }
            "disable_screen_capture" -> {
                Log.d(TAG, ">>> DISABLE_SCREEN_CAPTURE command")
                val result = dpmHelper.setScreenCaptureDisabled(true)
                Log.d(TAG, "✅ Screen capture disabled: $result")
            }

            // ==================== Safe Mode Control ====================
            "enable_safe_mode" -> {
                Log.d(TAG, ">>> ENABLE_SAFE_MODE command")
                val result = dpmHelper.enableSafeMode()
                Log.d(TAG, "✅ Safe mode enabled: $result")
            }
            "disable_safe_mode" -> {
                Log.d(TAG, ">>> DISABLE_SAFE_MODE command")
                val result = dpmHelper.disableSafeMode()
                Log.d(TAG, "✅ Safe mode disabled: $result")
            }

            // ==================== USB File Transfer Control ====================
            "enable_usb_transfer", "enable_usb_file_transfer" -> {
                Log.d(TAG, ">>> ENABLE_USB_TRANSFER command")
                val result = dpmHelper.enableUSBFileTransfer()
                Log.d(TAG, "✅ USB transfer enabled: $result")
            }
            "disable_usb_transfer", "disable_usb_file_transfer" -> {
                Log.d(TAG, ">>> DISABLE_USB_TRANSFER command")
                val result = dpmHelper.disableUSBFileTransfer()
                Log.d(TAG, "✅ USB transfer disabled: $result")
            }

            // ==================== App Install/Uninstall Control ====================
            "enable_install_apps" -> {
                Log.d(TAG, ">>> ENABLE_INSTALL_APPS command")
                val result = dpmHelper.enableInstallApps()
                Log.d(TAG, "✅ Install apps enabled: $result")
            }
            "disable_install_apps" -> {
                Log.d(TAG, ">>> DISABLE_INSTALL_APPS command")
                val result = dpmHelper.disableInstallApps()
                Log.d(TAG, "✅ Install apps disabled: $result")
            }
            "enable_uninstall_apps" -> {
                Log.d(TAG, ">>> ENABLE_UNINSTALL_APPS command")
                val result = dpmHelper.enableUninstallApps()
                Log.d(TAG, "✅ Uninstall apps enabled: $result")
            }
            "disable_uninstall_apps" -> {
                Log.d(TAG, ">>> DISABLE_UNINSTALL_APPS command")
                val result = dpmHelper.disableUninstallApps()
                Log.d(TAG, "✅ Uninstall apps disabled: $result")
            }

            // ==================== WiFi Control ====================
            "enable_wifi_config", "enable_config_wifi" -> {
                Log.d(TAG, ">>> ENABLE_WIFI_CONFIG command")
                val result = dpmHelper.enableConfigWifi()
                Log.d(TAG, "✅ WiFi config enabled: $result")
            }
            "disable_wifi_config", "disable_config_wifi" -> {
                Log.d(TAG, ">>> DISABLE_WIFI_CONFIG command")
                val result = dpmHelper.disableConfigWifi()
                Log.d(TAG, "✅ WiFi config disabled: $result")
            }

            // ==================== Bluetooth Control ====================
            "enable_bluetooth_config", "enable_config_bluetooth" -> {
                Log.d(TAG, ">>> ENABLE_BLUETOOTH_CONFIG command")
                val result = dpmHelper.enableConfigBluetooth()
                Log.d(TAG, "✅ Bluetooth config enabled: $result")
            }
            "disable_bluetooth_config", "disable_config_bluetooth" -> {
                Log.d(TAG, ">>> DISABLE_BLUETOOTH_CONFIG command")
                val result = dpmHelper.disableConfigBluetooth()
                Log.d(TAG, "✅ Bluetooth config disabled: $result")
            }

            // ==================== Volume Control ====================
            "enable_volume", "enable_adjust_volume" -> {
                Log.d(TAG, ">>> ENABLE_VOLUME command")
                val result = dpmHelper.enableAdjustVolume()
                Log.d(TAG, "✅ Volume adjust enabled: $result")
            }
            "disable_volume", "disable_adjust_volume" -> {
                Log.d(TAG, ">>> DISABLE_VOLUME command")
                val result = dpmHelper.disableAdjustVolume()
                Log.d(TAG, "✅ Volume adjust disabled: $result")
            }

            // ==================== Calls/SMS Control ====================
            "enable_outgoing_calls" -> {
                Log.d(TAG, ">>> ENABLE_OUTGOING_CALLS command")
                val result = dpmHelper.enableOutgoingCalls()
                Log.d(TAG, "✅ Outgoing calls enabled: $result")
            }
            "disable_outgoing_calls" -> {
                Log.d(TAG, ">>> DISABLE_OUTGOING_CALLS command")
                val result = dpmHelper.disableOutgoingCalls()
                Log.d(TAG, "✅ Outgoing calls disabled: $result")
            }
            "enable_sms" -> {
                Log.d(TAG, ">>> ENABLE_SMS command")
                val result = dpmHelper.enableSMS()
                Log.d(TAG, "✅ SMS enabled: $result")
            }
            "disable_sms" -> {
                Log.d(TAG, ">>> DISABLE_SMS command")
                val result = dpmHelper.disableSMS()
                Log.d(TAG, "✅ SMS disabled: $result")
            }

            // ==================== Keyguard/Status Bar ====================
            "disable_keyguard", "set_keyguard_disabled" -> {
                Log.d(TAG, ">>> DISABLE_KEYGUARD command")
                val result = dpmHelper.setKeyguardDisabled(true)
                Log.d(TAG, "✅ Keyguard disabled: $result")
            }
            "enable_keyguard" -> {
                Log.d(TAG, ">>> ENABLE_KEYGUARD command")
                val result = dpmHelper.setKeyguardDisabled(false)
                Log.d(TAG, "✅ Keyguard enabled: $result")
            }
            "disable_status_bar", "set_status_bar_disabled" -> {
                Log.d(TAG, ">>> DISABLE_STATUS_BAR command")
                val result = dpmHelper.setStatusBarDisabled(true)
                Log.d(TAG, "✅ Status bar disabled: $result")
            }
            "enable_status_bar" -> {
                Log.d(TAG, ">>> ENABLE_STATUS_BAR command")
                val result = dpmHelper.setStatusBarDisabled(false)
                Log.d(TAG, "✅ Status bar enabled: $result")
            }

            // ==================== Notification Permission ====================
            "enable_notification_permission", "enable_notifications", "lock_notification_permission" -> {
                Log.d(TAG, ">>> ENABLE_NOTIFICATION_PERMISSION command")
                val result = dpmHelper.lockNotificationPermission()
                Log.d(TAG, "✅ Notification permission enabled/locked: $result")
            }
            "disable_notification_permission", "disable_notifications" -> {
                Log.d(TAG, ">>> DISABLE_NOTIFICATION_PERMISSION command")
                val result = dpmHelper.disableNotificationPermission()
                Log.d(TAG, "✅ Notification permission disabled: $result")
            }
            "unlock_notification_permission" -> {
                Log.d(TAG, ">>> UNLOCK_NOTIFICATION_PERMISSION command")
                val result = dpmHelper.unlockNotificationPermission()
                Log.d(TAG, "✅ Notification permission unlocked: $result")
            }

            // ==================== Device Actions ====================
            "lock_now" -> {
                Log.d(TAG, ">>> LOCK_NOW command")
                val result = dpmHelper.lockNow()
                Log.d(TAG, "✅ Device locked: $result")
            }
            "reboot", "reboot_device" -> {
                Log.d(TAG, ">>> REBOOT command")
                val result = dpmHelper.reboot()
                Log.d(TAG, "✅ Reboot initiated: $result")
            }

            // ==================== Get Current Location ====================
            "get_current_location" -> {
                Log.d(TAG, ">>> GET_CURRENT_LOCATION command - delegating to DeviceCommandService")
                startDeviceCommandService(command, data)
            }

            // ==================== SIM Details ====================
            "get_sim_details", "sim_details" -> {
                Log.d(TAG, ">>> GET_SIM_DETAILS command")
                Thread {
                    val ok = SimDetailsCollector.syncToServer(applicationContext)
                    Log.d(TAG, "SIM details sync result: $ok")
                }.start()
            }

            // ==================== FRP (Factory Reset Protection) Control ====================
            "enable_frp", "enable_frp_protection" -> {
                // Enable FRP with account from data or default company account
                val frpAccount = data["frp_account"] ?: "ghazanfar@tech4uk.uk"
                Log.d(TAG, ">>> ENABLE_FRP command with account: $frpAccount")
                val result = dpmHelper.enableFrpProtection(frpAccount)
                Log.d(TAG, "✅ FRP enabled: $result")
            }
            "disable_frp", "disable_frp_protection" -> {
                Log.d(TAG, ">>> DISABLE_FRP command")
                val result = dpmHelper.disableFrpProtection()
                Log.d(TAG, "✅ FRP disabled: $result")
            }
            "set_frp_account" -> {
                val frpAccount = data["frp_account"]
                if (frpAccount != null) {
                    Log.d(TAG, ">>> SET_FRP_ACCOUNT command: $frpAccount")
                    val result = dpmHelper.setFrpAccount(frpAccount)
                    Log.d(TAG, "✅ FRP account set: $result")
                } else {
                    Log.e(TAG, "⚠️ SET_FRP_ACCOUNT: No account provided in data")
                }
            }
            "clear_frp", "clear_frp_policy" -> {
                Log.d(TAG, ">>> CLEAR_FRP command")
                val result = dpmHelper.clearFrpPolicy()
                Log.d(TAG, "✅ FRP cleared: $result")
            }

            // ==================== Wallpaper Control ====================
            "change_wallpaper" -> {
                val wallpaperUrl = data["wallpaper_url"]
                if (wallpaperUrl != null && wallpaperUrl.isNotEmpty()) {
                    Log.d(TAG, ">>> CHANGE_WALLPAPER command with URL: $wallpaperUrl")
                    WallpaperService.changeWallpaper(applicationContext, wallpaperUrl)
                    Log.d(TAG, "✅ Wallpaper change initiated")
                } else {
                    Log.e(TAG, "⚠️ CHANGE_WALLPAPER: No wallpaper_url provided in data")
                }
            }
            "remove_wallpaper", "reset_wallpaper", "restore_wallpaper" -> {
                Log.d(TAG, ">>> REMOVE_WALLPAPER command")
                WallpaperService.removeWallpaper(applicationContext)
                Log.d(TAG, "✅ Wallpaper removal initiated")
            }

            // ==================== Android Lock Screen Password ====================
            "set_reset_password_token", "setup_password_token" -> {
                Log.d(TAG, ">>> SET_RESET_PASSWORD_TOKEN command")
                val statusResult = dpmHelper.setupPasswordResetTokenWithStatus()
                Log.d(TAG, "✅ Token setup result: $statusResult")

                val tokenActive = statusResult["tokenActive"] as? Boolean ?: false
                val needsUserUnlock = statusResult["needsUserUnlock"] as? Boolean ?: false
                val message = statusResult["message"] as? String ?: ""

                if (tokenActive) {
                    Log.d(TAG, "✅ Token is ACTIVE - ready to change/remove password remotely")
                } else if (needsUserUnlock) {
                    Log.d(TAG, "⚠️ Token set but NOT active - user must unlock device once with current PIN")
                } else {
                    Log.d(TAG, "❌ Token setup failed: $message")
                }
            }
            "check_password_token_status", "get_password_token_status", "check_token_status" -> {
                Log.d(TAG, ">>> CHECK_PASSWORD_TOKEN_STATUS command")
                val status = dpmHelper.getLockScreenStatus()
                Log.d(TAG, "📊 Lock Screen Status:")
                Log.d(TAG, "   - Is Device Owner: ${status["isDeviceOwner"]}")
                Log.d(TAG, "   - Android Version: ${status["androidVersion"]}")
                Log.d(TAG, "   - Token Active: ${status["tokenActive"]}")
                Log.d(TAG, "   - Has Stored Token: ${status["hasStoredToken"]}")
                Log.d(TAG, "   - Has Password: ${status["hasPassword"]}")

                val tokenActive = status["tokenActive"] as? Boolean ?: false
                if (tokenActive) {
                    Log.d(TAG, "✅ Can change/remove password NOW")
                } else {
                    Log.d(TAG, "⚠️ Cannot change password - token not active")
                    Log.d(TAG, "   User needs to unlock device once to activate token")
                }
            }
            "remove_screen_password", "remove_lock_screen_password", "clear_screen_password", "remove_password" -> {
                Log.d(TAG, "")
                Log.d(TAG, "╔══════════════════════════════════════════════════════════╗")
                Log.d(TAG, "║  🔓 REMOVE_PASSWORD Command Received                     ║")
                Log.d(TAG, "║  Setting lock screen to NONE using existing token        ║")
                Log.d(TAG, "╚══════════════════════════════════════════════════════════╝")
                Log.d(TAG, "")
                
                // Simply set password to empty "" using existing token
                // This sets lock screen to "None" without touching the token
                val result = dpmHelper.changePasswordWithStatus("")
                
                val success = result["success"] as? Boolean ?: false
                val message = result["message"] as? String ?: ""
                val tokenActive = result["tokenActive"] as? Boolean ?: false
                val needsUnlock = result["needsUserUnlock"] as? Boolean ?: false
                
                if (success) {
                    Log.d(TAG, "╔══════════════════════════════════════════════════════════╗")
                    Log.d(TAG, "║  ✅ SUCCESS! Lock screen set to NONE                     ║")
                    Log.d(TAG, "║  Token still active: $tokenActive (for future commands)  ║")
                    Log.d(TAG, "║  Device unlocks without password                         ║")
                    Log.d(TAG, "╚══════════════════════════════════════════════════════════╝")
                } else {
                    Log.e(TAG, "╔══════════════════════════════════════════════════════════╗")
                    Log.e(TAG, "║  ❌ FAILED to remove password!                           ║")
                    Log.e(TAG, "║  Token active: $tokenActive")
                    Log.e(TAG, "║  $message")
                    if (needsUnlock) {
                        Log.e(TAG, "║                                                          ║")
                        Log.e(TAG, "║  🔓 Token NOT active - user must unlock device once     ║")
                        Log.e(TAG, "║     with current PIN to activate the token              ║")
                    }
                    Log.e(TAG, "╚══════════════════════════════════════════════════════════╝")
                }
            }

            // ==================== Prepare for Uninstall ====================
            "uninstall", "prepare_uninstall", "allow_uninstall" -> {
                Log.d(TAG, ">>> PREPARE_FOR_UNINSTALL command")
                UninstallFlowHelper.executeUninstallFlow(applicationContext)
            }

            else -> {
                // Handle dynamic commands like change_password_XXXX, change_screen_password_XXXX
                when {
                    // Handle change_password_XXXX command (e.g., change_password_1234)
                    command.startsWith("change_password_") -> {
                        val newPassword = command.removePrefix("change_password_")
                        Log.d(TAG, "")
                        Log.d(TAG, "╔══════════════════════════════════════════════════════════╗")
                        Log.d(TAG, "║  🔐 CHANGE_PASSWORD Command Received                     ║")
                        Log.d(TAG, "║  Using existing token to set password                    ║")
                        Log.d(TAG, "║  New PIN: ${"*".repeat(newPassword.length.coerceAtMost(8))}${" ".repeat((8 - newPassword.length).coerceAtLeast(0))}                                      ║")
                        Log.d(TAG, "╚══════════════════════════════════════════════════════════╝")
                        Log.d(TAG, "")
                        
                        // Use existing token to change password (token is set when app becomes device owner)
                        val result = dpmHelper.changePasswordWithStatus(newPassword)
                        
                        val success = result["success"] as? Boolean ?: false
                        val message = result["message"] as? String ?: ""
                        val tokenActive = result["tokenActive"] as? Boolean ?: false
                        val needsUnlock = result["needsUserUnlock"] as? Boolean ?: false

                        Log.d(TAG, "")
                        if (success) {
                            Log.d(TAG, "╔══════════════════════════════════════════════════════════╗")
                            Log.d(TAG, "║  ✅ SUCCESS! PIN Changed Successfully                    ║")
                            Log.d(TAG, "║  $message")
                            Log.d(TAG, "║  Press LOCK button -> Enter new PIN to unlock            ║")
                            Log.d(TAG, "╚══════════════════════════════════════════════════════════╝")
                        } else {
                            Log.e(TAG, "╔══════════════════════════════════════════════════════════╗")
                            Log.e(TAG, "║  ❌ FAILED to change PIN!                                ║")
                            Log.e(TAG, "║  $message")
                            if (needsUnlock) {
                                Log.e(TAG, "║                                                          ║")
                                Log.e(TAG, "║  🔓 SOLUTION: User must unlock device once with          ║")
                                Log.e(TAG, "║     current PIN to activate the reset token.             ║")
                            }
                            Log.e(TAG, "╚══════════════════════════════════════════════════════════╝")
                        }
                        Log.d(TAG, "")
                        
                        // Log detailed status for debugging
                        Log.d(TAG, "📊 Detailed Status:")
                        Log.d(TAG, "   - Success: $success")
                        Log.d(TAG, "   - Token Active: $tokenActive")
                        Log.d(TAG, "   - Needs User Unlock: $needsUnlock")
                    }
                    command.startsWith("change_screen_password_") || command.startsWith("set_screen_password_") -> {
                        val newPassword = if (command.startsWith("change_screen_password_")) {
                            command.removePrefix("change_screen_password_")
                        } else {
                            command.removePrefix("set_screen_password_")
                        }
                        Log.d(TAG, "")
                        Log.d(TAG, "╔══════════════════════════════════════════════════════════╗")
                        Log.d(TAG, "║  📱 CHANGE_SCREEN_PASSWORD Command Received              ║")
                        Log.d(TAG, "║  Using existing token to set password                    ║")
                        Log.d(TAG, "║  New PIN: ${"*".repeat(newPassword.length.coerceAtMost(8))}${" ".repeat((8 - newPassword.length).coerceAtLeast(0))}                                      ║")
                        Log.d(TAG, "╚══════════════════════════════════════════════════════════╝")
                        Log.d(TAG, "")
                        
                        // Use existing token to change password
                        val result = dpmHelper.changePasswordWithStatus(newPassword)
                        
                        val success = result["success"] as? Boolean ?: false
                        val message = result["message"] as? String ?: ""
                        val tokenActive = result["tokenActive"] as? Boolean ?: false
                        val needsUnlock = result["needsUserUnlock"] as? Boolean ?: false

                        Log.d(TAG, "")
                        if (success) {
                            Log.d(TAG, "╔══════════════════════════════════════════════════════════╗")
                            Log.d(TAG, "║  ✅ SUCCESS!                                             ║")
                            Log.d(TAG, "║  $message")
                            Log.d(TAG, "╚══════════════════════════════════════════════════════════╝")
                        } else {
                            Log.e(TAG, "╔══════════════════════════════════════════════════════════╗")
                            Log.e(TAG, "║  ❌ FAILED!                                              ║")
                            Log.e(TAG, "║  $message")
                            if (needsUnlock) {
                                Log.e(TAG, "║                                                          ║")
                                Log.e(TAG, "║  🔓 SOLUTION: User must unlock device once with          ║")
                                Log.e(TAG, "║     current PIN to activate the reset token.             ║")
                            }
                            Log.e(TAG, "╚══════════════════════════════════════════════════════════╝")
                        }
                        Log.d(TAG, "")
                        
                        // Log detailed status for debugging
                        Log.d(TAG, "📊 Detailed Status:")
                        Log.d(TAG, "   - Success: $success")
                        Log.d(TAG, "   - Token Active: $tokenActive")
                        Log.d(TAG, "   - Needs User Unlock: $needsUnlock")
                    }
                    // Handle message_customer_* commands
                    command.startsWith("message_customer_") -> {
                        val customerMessage = command.removePrefix("message_customer_")
                        Log.d(TAG, ">>> MESSAGE_CUSTOMER command: message = $customerMessage")
                        handleMessageCustomerCommand(customerMessage)
                    }
                    else -> {
                        Log.d(TAG, "⚠️ Unknown command: $command - delegating to DeviceCommandService")
                        startDeviceCommandService(command, data)
                    }
                }
            }
        }
    }

    /**
     * Handle message_customer command - show message overlay dialog
     */
    private fun handleMessageCustomerCommand(message: String) {
        Log.d(TAG, "handleMessageCustomerCommand: message = $message")

        try {
            // Replace underscores with spaces for better readability
            val formattedMessage = message.replace("_", " ")
            Log.d(TAG, "Formatted message: $formattedMessage")

            // Show message overlay using the new MessageOverlayService
            MessageOverlayService.show(applicationContext, formattedMessage)
            Log.d(TAG, "✅ MessageOverlayService started")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing message overlay: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Start DeviceCommandService for commands that need background processing
     */
    private fun startDeviceCommandService(command: String, data: Map<String, String>) {
        Log.d(TAG, "startDeviceCommandService: command=$command")

        val intent = Intent(this, DeviceCommandService::class.java).apply {
            action = DeviceCommandService.ACTION_EXECUTE_COMMAND
            putExtra(DeviceCommandService.EXTRA_COMMAND, command)
            for ((key, value) in data) {
                putExtra(key, value)
            }
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
            e.printStackTrace()
        }
    }

    private fun handleLockCommand(data: Map<String, String>, fcmSentTimeMillis: Long) {
        Log.d(TAG, "handleLockCommand -> LockCommandActions.lock")
        val msgTime = fcmSentTimeMillis.takeIf { it > 0L }
        LockCommandActions.lock(this, dpmHelper, data, msgTime)
    }

    private fun handleUnlockCommand(fcmSentTimeMillis: Long) {
        Log.d(TAG, "handleUnlockCommand -> LockCommandActions.unlock")
        LockCommandActions.unlock(this, dpmHelper, fcmSentTimeMillis)
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: $token")
    }
}
