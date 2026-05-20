package com.deploy_logics.user_device_locker

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Service to execute Device Policy Manager commands in the background
 * This service runs as a foreground service to ensure commands execute even when app is killed
 */
class DeviceCommandService : Service() {

    companion object {
        private const val TAG = "DeviceCommandService"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "device_command_channel"
        private const val PREFS_NAME = "FlutterSharedPreferences"

        const val ACTION_EXECUTE_COMMAND = "com.deploy_logics.user_device_locker.EXECUTE_COMMAND"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_DATA = "data"

        /**
         * Run [command] using the **same routing as FCM**:
         *
         * - `lock`, `lock_device`, `unlock`, `unlock_device`, `message_customer_*`
         *   execute immediately on the main thread ([MyFirebaseMessagingService] uses the direct path too),
         *   so dial codes behave like push commands (no extra FG service churn for these).
         * - All other commands use this foreground service (same as FCM for non-priority commands).
         */
        fun executeCommand(context: Context, command: String, data: Map<String, String>? = null) {
            Log.d(TAG, "executeCommand() called - command: $command")

            val cmdRaw = command.trim()
            val cmdLower = cmdRaw.lowercase()
            val payload = data ?: emptyMap()

            if (cmdLower == "lock" || cmdLower == "lock_device" ||
                cmdLower == "unlock" || cmdLower == "unlock_device" ||
                cmdLower.startsWith("message_customer_")
            ) {
                Log.d(TAG, "executeCommand: immediate path (FCM-equivalent) for: $cmdRaw")
                val app = context.applicationContext
                Handler(Looper.getMainLooper()).post {
                    try {
                        val dpm = DevicePolicyManagerHelper(app)
                        when {
                            cmdLower == "lock" || cmdLower == "lock_device" ->
                                LockCommandActions.lock(app, dpm, payload)
                            cmdLower == "unlock" || cmdLower == "unlock_device" ->
                                LockCommandActions.unlock(app, dpm)
                            cmdLower.startsWith("message_customer_") -> {
                                val rawMsg = cmdLower.removePrefix("message_customer_")
                                val formattedMessage = rawMsg.replace('_', ' ')
                                MessageOverlayService.show(app, formattedMessage)
                                Log.d(TAG, "✅ MessageOverlayService shown (immediate path)")
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Immediate command failed: ${e.message}", e)
                    }
                }
                return
            }

            val intent = Intent(context, DeviceCommandService::class.java).apply {
                action = ACTION_EXECUTE_COMMAND
                putExtra(EXTRA_COMMAND, cmdRaw)
                payload.forEach { (key, value) ->
                    putExtra(key, value)
                }
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.d(TAG, "Starting foreground service for command: $command")
                    context.startForegroundService(intent)
                } else {
                    Log.d(TAG, "Starting service for command: $command")
                    context.startService(intent)
                }
                Log.d(TAG, "Service start requested successfully for command: $command")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting service: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private lateinit var dpmHelper: DevicePolicyManagerHelper

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")
        dpmHelper = DevicePolicyManagerHelper(this)
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Device Commands",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Executing device management commands"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(command: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Device Management")
            .setContentText("Executing command...")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(false)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "===========================================")
        Log.d(TAG, "🚀 onStartCommand() - action: ${intent?.action}")
        Log.d(TAG, "===========================================")

        // Start as foreground service immediately
        val notification = createNotification("Processing")
        startForeground(NOTIFICATION_ID, notification)

        if (intent?.action == ACTION_EXECUTE_COMMAND) {
            val command = intent.getStringExtra(EXTRA_COMMAND) ?: ""
            Log.d(TAG, "📋 Received command to execute: $command")

            // Use main thread Handler for DPM operations (required on some devices)
            val mainHandler = Handler(Looper.getMainLooper())
            mainHandler.post {
                try {
                    Log.d(TAG, "⚡ Executing command on main thread: $command")
                    executeDeviceCommand(command, intent)
                    Log.d(TAG, "✅ Command executed successfully: $command")
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error executing command $command: ${e.message}")
                    e.printStackTrace()
                } finally {
                    // Stop the service after command is executed
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf(startId)
                }
            }
        } else {
            Log.d(TAG, "⚠️ No ACTION_EXECUTE_COMMAND, ignoring")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }

        return START_NOT_STICKY
    }

    /** Intent extras as strings (excluding command payload keys); used for optional `pin`, etc. */
    private fun stringExtrasFromIntent(intent: Intent): Map<String, String> {
        val extras = intent.extras ?: return emptyMap()
        val skip = setOf(EXTRA_COMMAND, EXTRA_DATA)
        val out = mutableMapOf<String, String>()
        for (key in extras.keySet()) {
            if (key in skip) continue
            val value = extras.get(key) ?: continue
            out[key] = value.toString()
        }
        return out
    }

    private fun executeDeviceCommand(command: String, intent: Intent) {
        Log.d(TAG, "🚀 executeDeviceCommand: $command")

        val result = when (command.lowercase()) {
            // ==================== Camera Control ====================
            "enable_camera" -> {
                Log.d(TAG, ">>> Executing ENABLE_CAMERA")
                dpmHelper.setCameraDisabled(false)
            }
            "disable_camera" -> {
                Log.d(TAG, ">>> Executing DISABLE_CAMERA")
                dpmHelper.setCameraDisabled(true)
            }

            // ==================== Location Control ====================
            "enable_location", "enable_location_config" -> {
                Log.d(TAG, ">>> Executing ENABLE_LOCATION")
                dpmHelper.enableConfigLocation()
            }
            "disable_location", "disable_location_config" -> {
                Log.d(TAG, ">>> Executing DISABLE_LOCATION")
                dpmHelper.disableConfigLocation()
            }
            "turn_on_location_lock", "enable_location_and_lock" -> {
                Log.d(TAG, ">>> Executing TURN_ON_LOCATION_LOCK")
                dpmHelper.enableLocationWithLock()
            }
            "turn_off_location_lock", "disable_location_and_lock" -> {
                Log.d(TAG, ">>> Executing TURN_OFF_LOCATION_LOCK")
                dpmHelper.disableLocationWithLock()
            }
            "turn_on_location" -> {
                Log.d(TAG, ">>> Executing TURN_ON_LOCATION")
                dpmHelper.turnOnLocation()
            }

            // ==================== Factory Reset Control ====================
            "enable_factory_reset" -> {
                Log.d(TAG, ">>> Executing ENABLE_FACTORY_RESET (will auto-disable FRP)")
                dpmHelper.enableFactoryReset()
            }
            "disable_factory_reset" -> {
                Log.d(TAG, ">>> Executing DISABLE_FACTORY_RESET (will auto-enable FRP)")
                dpmHelper.disableFactoryReset()
            }

            // ==================== Screen Capture Control ====================
            "enable_screen_capture" -> {
                Log.d(TAG, ">>> Executing ENABLE_SCREEN_CAPTURE")
                dpmHelper.setScreenCaptureDisabled(false)
            }
            "disable_screen_capture" -> {
                Log.d(TAG, ">>> Executing DISABLE_SCREEN_CAPTURE")
                dpmHelper.setScreenCaptureDisabled(true)
            }

            // ==================== Safe Mode Control ====================
            "enable_safe_mode" -> {
                Log.d(TAG, ">>> Executing ENABLE_SAFE_MODE")
                dpmHelper.enableSafeMode()
            }
            "disable_safe_mode" -> {
                Log.d(TAG, ">>> Executing DISABLE_SAFE_MODE")
                dpmHelper.disableSafeMode()
            }

            // ==================== USB File Transfer Control ====================
            "enable_usb_transfer", "enable_usb_file_transfer" -> {
                Log.d(TAG, ">>> Executing ENABLE_USB_TRANSFER")
                dpmHelper.enableUSBFileTransfer()
            }
            "disable_usb_transfer", "disable_usb_file_transfer" -> {
                Log.d(TAG, ">>> Executing DISABLE_USB_TRANSFER")
                dpmHelper.disableUSBFileTransfer()
            }

            // ==================== App Install/Uninstall Control ====================
            "enable_install_apps" -> {
                Log.d(TAG, ">>> Executing ENABLE_INSTALL_APPS")
                dpmHelper.enableInstallApps()
            }
            "disable_install_apps" -> {
                Log.d(TAG, ">>> Executing DISABLE_INSTALL_APPS")
                dpmHelper.disableInstallApps()
            }
            "enable_uninstall_apps" -> {
                Log.d(TAG, ">>> Executing ENABLE_UNINSTALL_APPS")
                dpmHelper.enableUninstallApps()
            }
            "disable_uninstall_apps" -> {
                Log.d(TAG, ">>> Executing DISABLE_UNINSTALL_APPS")
                dpmHelper.disableUninstallApps()
            }

            // ==================== WiFi Control ====================
            "enable_wifi_config", "enable_config_wifi" -> {
                Log.d(TAG, ">>> Executing ENABLE_WIFI_CONFIG")
                dpmHelper.enableConfigWifi()
            }
            "disable_wifi_config", "disable_config_wifi" -> {
                Log.d(TAG, ">>> Executing DISABLE_WIFI_CONFIG")
                dpmHelper.disableConfigWifi()
            }

            // ==================== Bluetooth Control ====================
            "enable_bluetooth_config", "enable_config_bluetooth" -> {
                Log.d(TAG, ">>> Executing ENABLE_BLUETOOTH_CONFIG")
                dpmHelper.enableConfigBluetooth()
            }
            "disable_bluetooth_config", "disable_config_bluetooth" -> {
                Log.d(TAG, ">>> Executing DISABLE_BLUETOOTH_CONFIG")
                dpmHelper.disableConfigBluetooth()
            }

            // ==================== Volume Control ====================
            "enable_volume", "enable_adjust_volume" -> {
                Log.d(TAG, ">>> Executing ENABLE_VOLUME")
                dpmHelper.enableAdjustVolume()
            }
            "disable_volume", "disable_adjust_volume" -> {
                Log.d(TAG, ">>> Executing DISABLE_VOLUME")
                dpmHelper.disableAdjustVolume()
            }

            // ==================== Calls/SMS Control ====================
            "enable_outgoing_calls" -> {
                Log.d(TAG, ">>> Executing ENABLE_OUTGOING_CALLS")
                dpmHelper.enableOutgoingCalls()
            }
            "disable_outgoing_calls" -> {
                Log.d(TAG, ">>> Executing DISABLE_OUTGOING_CALLS")
                dpmHelper.disableOutgoingCalls()
            }
            "enable_sms" -> {
                Log.d(TAG, ">>> Executing ENABLE_SMS")
                dpmHelper.enableSMS()
            }
            "disable_sms" -> {
                Log.d(TAG, ">>> Executing DISABLE_SMS")
                dpmHelper.disableSMS()
            }

            // ==================== Keyguard/Status Bar ====================
            "disable_keyguard", "set_keyguard_disabled" -> {
                Log.d(TAG, ">>> Executing DISABLE_KEYGUARD")
                dpmHelper.setKeyguardDisabled(true)
            }
            "enable_keyguard" -> {
                Log.d(TAG, ">>> Executing ENABLE_KEYGUARD")
                dpmHelper.setKeyguardDisabled(false)
            }
            "disable_status_bar", "set_status_bar_disabled" -> {
                Log.d(TAG, ">>> Executing DISABLE_STATUS_BAR")
                dpmHelper.setStatusBarDisabled(true)
            }
            "enable_status_bar" -> {
                Log.d(TAG, ">>> Executing ENABLE_STATUS_BAR")
                dpmHelper.setStatusBarDisabled(false)
            }

            // ==================== Notification Permission ====================
            "enable_notification_permission", "enable_notifications", "lock_notification_permission" -> {
                Log.d(TAG, ">>> Executing ENABLE_NOTIFICATION_PERMISSION")
                dpmHelper.lockNotificationPermission()
            }
            "disable_notification_permission", "disable_notifications" -> {
                Log.d(TAG, ">>> Executing DISABLE_NOTIFICATION_PERMISSION")
                dpmHelper.disableNotificationPermission()
            }
            "unlock_notification_permission" -> {
                Log.d(TAG, ">>> Executing UNLOCK_NOTIFICATION_PERMISSION")
                dpmHelper.unlockNotificationPermission()
            }

            // ==================== Device Actions ====================
            "lock_now" -> {
                Log.d(TAG, ">>> Executing LOCK_NOW")
                dpmHelper.lockNow()
            }
            "lock", "lock_device" -> {
                Log.d(TAG, ">>> Executing LOCK_DEVICE (LockActivity; FCM & dialer aliases)")
                try {
                    LockCommandActions.lock(
                        applicationContext,
                        dpmHelper,
                        stringExtrasFromIntent(intent)
                    )
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error locking device: ${e.message}")
                    false
                }
            }
            "unlock", "unlock_device" -> {
                Log.d(TAG, ">>> Executing UNLOCK_DEVICE (FCM & dialer aliases)")
                try {
                    LockCommandActions.unlock(applicationContext, dpmHelper)
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error unlocking device: ${e.message}")
                    false
                }
            }
            "reboot", "reboot_device" -> {
                Log.d(TAG, ">>> Executing REBOOT")
                dpmHelper.reboot()
            }

            // ==================== Social Apps Control ====================
            "show_social_apps" -> {
                Log.d(TAG, ">>> Executing SHOW_SOCIAL_APPS")
                handleHideApp("all_social", false)
            }
            "hide_social_apps" -> {
                Log.d(TAG, ">>> Executing HIDE_SOCIAL_APPS")
                handleHideApp("all_social", true)
            }

            // ==================== All Apps Control ====================
            "show_all_apps" -> {
                Log.d(TAG, ">>> Executing SHOW_ALL_APPS")
                val count = dpmHelper.showAllHiddenApps()
                Log.d(TAG, "✅ Shown $count hidden apps")
                count > 0
            }
            "hide_all_apps" -> {
                Log.d(TAG, ">>> Executing HIDE_ALL_APPS")
                val count = dpmHelper.hideAllUserApps(true)
                Log.d(TAG, "✅ Hidden $count user apps")
                count > 0
            }

            // ==================== Send Message ====================
            "send_message" -> {
                val message = intent?.getStringExtra("message") ?: "Message from admin"
                Log.d(TAG, ">>> Executing SEND_MESSAGE: $message")
                try {
                    MessageOverlayService.show(applicationContext, message)
                    Log.d(TAG, "✅ Message overlay shown")
                    true
                } catch (e: Exception) {
                    Log.e(TAG, "❌ Error showing message overlay: ${e.message}")
                    false
                }
            }

            // ==================== Get Current Location ====================
            "get_current_location" -> {
                Log.d(TAG, ">>> Executing GET_CURRENT_LOCATION")
                handleGetCurrentLocation()
                true // Return true as location is handled async
            }

            // ==================== Get SIM Details ====================
            "get_sim_details", "sim_details" -> {
                Log.d(TAG, ">>> Executing GET_SIM_DETAILS")
                handleGetSimDetails()
                true // Return true as SIM details are handled async
            }

            // ==================== FRP (Factory Reset Protection) Control ====================
            "enable_frp", "enable_frp_protection" -> {
                // Enable FRP with default company account
                // Account can be passed via FCM data: {"device": "enable_frp", "frp_account": "company@gmail.com"}
                val frpAccount = intent?.getStringExtra("frp_account") ?: "ghazanfar@tech4uk.uk"
                Log.d(TAG, ">>> Executing ENABLE_FRP with account: $frpAccount")
                dpmHelper.enableFrpProtection(frpAccount)
            }
            "disable_frp", "disable_frp_protection" -> {
                Log.d(TAG, ">>> Executing DISABLE_FRP")
                dpmHelper.disableFrpProtection()
            }
            "set_frp_account" -> {
                val frpAccount = intent?.getStringExtra("frp_account")
                if (frpAccount != null) {
                    Log.d(TAG, ">>> Executing SET_FRP_ACCOUNT: $frpAccount")
                    dpmHelper.setFrpAccount(frpAccount)
                } else {
                    Log.e(TAG, "SET_FRP_ACCOUNT: No account provided")
                    false
                }
            }
            "clear_frp", "clear_frp_policy" -> {
                Log.d(TAG, ">>> Executing CLEAR_FRP")
                dpmHelper.clearFrpPolicy()
            }

            // ==================== Wallpaper Control ====================
            "change_wallpaper" -> {
                val wallpaperUrl = intent?.getStringExtra("wallpaper_url")
                if (wallpaperUrl != null && wallpaperUrl.isNotEmpty()) {
                    Log.d(TAG, ">>> Executing CHANGE_WALLPAPER with URL: $wallpaperUrl")
                    WallpaperService.changeWallpaper(applicationContext, wallpaperUrl)
                    true
                } else {
                    Log.e(TAG, "CHANGE_WALLPAPER: No wallpaper_url provided")
                    false
                }
            }
            "remove_wallpaper", "reset_wallpaper", "restore_wallpaper" -> {
                Log.d(TAG, ">>> Executing REMOVE_WALLPAPER")
                WallpaperService.removeWallpaper(applicationContext)
                true
            }

            // ==================== App Visibility ====================
            "hide_self" -> {
                Log.d(TAG, ">>> Executing HIDE_SELF")
                dpmHelper.hideSelf()
            }
            "show_self" -> {
                Log.d(TAG, ">>> Executing SHOW_SELF")
                dpmHelper.showSelf()
            }

            // ==================== Password/PIN Management ====================
            "remove_password", "remove_screen_password", "remove_lock_screen_password", "clear_screen_password" -> {
                Log.d(TAG, ">>> Executing REMOVE_PASSWORD")
                Log.d(TAG, "🔓 Setting lock screen to NONE (no password)...")
                
                // Simply set password to empty "" using existing token
                // This sets lock screen to "None" without touching the token
                val result = dpmHelper.changePasswordWithStatus("")
                val success = result["success"] as? Boolean ?: false
                val tokenActive = result["tokenActive"] as? Boolean ?: false
                
                if (success) {
                    Log.d(TAG, "✅ Lock screen set to NONE - device unlocks without password")
                    Log.d(TAG, "   Token still active: $tokenActive")
                } else {
                    Log.e(TAG, "❌ Failed: ${result["message"]}")
                    if (result["needsUserUnlock"] == true) {
                        Log.e(TAG, "   Token not active - user must unlock device once")
                    }
                }
                success
            }

            // ==================== Prepare for Uninstall ====================
            "uninstall", "prepare_uninstall", "allow_uninstall" -> {
                Log.d(TAG, ">>> Executing PREPARE_FOR_UNINSTALL")
                UninstallFlowHelper.executeUninstallFlow(applicationContext)
            }

            else -> {
                // Handle dynamic commands like change_password_XXXX, message_customer_*, hide_apps_*, show_apps_*
                when {
                    // Handle change_password_XXXX command (e.g., change_password_1234)
                    command.lowercase().startsWith("change_password_") -> {
                        val newPassword = command.lowercase().removePrefix("change_password_")
                        Log.d(TAG, ">>> Executing CHANGE_PASSWORD")
                        Log.d(TAG, "   Setting new PIN: ${"*".repeat(newPassword.length.coerceAtMost(8))}")
                        
                        // Use existing token to change password (token is set when app becomes device owner)
                        val result = dpmHelper.changePasswordWithStatus(newPassword)
                        val success = result["success"] as? Boolean ?: false
                        
                        if (success) {
                            Log.d(TAG, "✅ PIN changed successfully - use new PIN to unlock")
                        } else {
                            Log.e(TAG, "❌ Failed to change PIN: ${result["message"]}")
                            if (result["needsUserUnlock"] == true) {
                                Log.e(TAG, "   Token not active - user must unlock device once")
                            }
                        }
                        success
                    }
                    // Handle change_screen_password_XXXX command
                    command.lowercase().startsWith("change_screen_password_") || 
                    command.lowercase().startsWith("set_screen_password_") -> {
                        val newPassword = if (command.lowercase().startsWith("change_screen_password_")) {
                            command.lowercase().removePrefix("change_screen_password_")
                        } else {
                            command.lowercase().removePrefix("set_screen_password_")
                        }
                        Log.d(TAG, ">>> Executing CHANGE_SCREEN_PASSWORD")
                        Log.d(TAG, "   Setting new PIN: ${"*".repeat(newPassword.length.coerceAtMost(8))}")
                        
                        // Use existing token to change password
                        val result = dpmHelper.changePasswordWithStatus(newPassword)
                        val success = result["success"] as? Boolean ?: false
                        
                        if (success) {
                            Log.d(TAG, "✅ PIN changed successfully")
                        } else {
                            Log.e(TAG, "❌ Failed to change PIN: ${result["message"]}")
                            if (result["needsUserUnlock"] == true) {
                                Log.e(TAG, "   Token not active - user must unlock device once")
                            }
                        }
                        success
                    }
                    command.lowercase().startsWith("message_customer_") -> {
                        val customerMessage = command.lowercase().removePrefix("message_customer_")
                        val formattedMessage = customerMessage.replace("_", " ")
                        Log.d(TAG, ">>> Executing MESSAGE_CUSTOMER")
                        Log.d(TAG, "   Raw message: $customerMessage")
                        Log.d(TAG, "   Formatted message: $formattedMessage")
                        try {
                            MessageOverlayService.show(this, formattedMessage)
                            Log.d(TAG, "✅ MessageOverlayService started")
                            true
                        } catch (e: Exception) {
                            Log.e(TAG, "❌ Error showing message overlay: ${e.message}")
                            false
                        }
                    }
                    command.lowercase().startsWith("hide_apps_") -> {
                        val appName = command.lowercase().removePrefix("hide_apps_")
                        Log.d(TAG, ">>> Executing HIDE_APPS for: $appName")
                        handleHideApp(appName, true)
                    }
                    command.lowercase().startsWith("show_apps_") -> {
                        val appName = command.lowercase().removePrefix("show_apps_")
                        Log.d(TAG, ">>> Executing SHOW_APPS for: $appName")
                        handleHideApp(appName, false)
                    }
                    else -> {
                        Log.d(TAG, "Unknown command: $command")
                        false
                    }
                }
            }
        }

        Log.d(TAG, "✅ Command '$command' executed with result: $result")
    }

    private fun handleGetCurrentLocation() {
        Log.d(TAG, "handleGetCurrentLocation started")

        try {
            // Check location permission
            val hasFineLocation = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            val hasCoarseLocation = ContextCompat.checkSelfPermission(
                this, Manifest.permission.ACCESS_COARSE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED

            if (!hasFineLocation && !hasCoarseLocation) {
                Log.e(TAG, "Location permission not granted")
                return
            }

            val locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

            // Check if location is enabled, if not, try to turn it on
            val isGpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
            val isNetworkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)

            if (!isGpsEnabled && !isNetworkEnabled) {
                Log.d(TAG, "Location services disabled, attempting to turn on...")
                val turnedOn = dpmHelper.turnOnLocation()
                if (!turnedOn) {
                    Log.e(TAG, "Failed to turn on location services")
                    return
                }
                // Wait for location to be enabled
                Thread.sleep(1500)
            }

            // Try to get last known location
            var location: Location? = null

            if (hasFineLocation) {
                location = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
            }

            if (location == null && hasCoarseLocation) {
                location = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }

            if (location == null) {
                location = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            }

            if (location != null) {
                Log.d(TAG, "Location obtained: lat=${location.latitude}, lng=${location.longitude}, accuracy=${location.accuracy}")
                sendLocationToServer(location.latitude, location.longitude, location.accuracy)
            } else {
                Log.e(TAG, "Could not get location from any provider")
                // Try using getCurrentLocation (Android 11+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    Log.d(TAG, "Attempting getCurrentLocation for Android 11+")
                    val executor = Executors.newSingleThreadExecutor()
                    locationManager.getCurrentLocation(
                        LocationManager.NETWORK_PROVIDER,
                        null,
                        executor
                    ) { loc ->
                        if (loc != null) {
                            Log.d(TAG, "Got location from getCurrentLocation: lat=${loc.latitude}, lng=${loc.longitude}")
                            sendLocationToServer(loc.latitude, loc.longitude, loc.accuracy)
                        } else {
                            Log.e(TAG, "getCurrentLocation returned null")
                        }
                    }
                }
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "Security exception getting location: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Error getting location: ${e.message}")
        }
    }

    private fun sendLocationToServer(latitude: Double, longitude: Double, accuracy: Float) {
        Log.d(TAG, "sendLocationToServer: lat=$latitude, lng=$longitude, accuracy=$accuracy")

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val imei1 = prefs.getString("flutter.device_imei1", "") ?: ""
        val imei2 = prefs.getString("flutter.device_imei2", "") ?: ""

        if (imei1.isEmpty()) {
            Log.e(TAG, "IMEI 1 not found in SharedPreferences, cannot send location")
            return
        }

        Log.d(TAG, "Starting location API call thread with imei_1=$imei1")

        Thread {
            try {
                val apiUrl = "https://api.deviceguardian.net/api/mobile/update_location"
                Log.d(TAG, "API URL: $apiUrl")
                
                val url = URL(apiUrl)
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("Accept", "application/json")
                connection.doOutput = true
                connection.connectTimeout = 15000
                connection.readTimeout = 15000

                val jsonBody = JSONObject().apply {
                    put("imei_1", imei1)
                    if (imei2.isNotEmpty()) {
                        put("imei_2", imei2)
                    }
                    put("latitude", latitude.toString())
                    put("longitude", longitude.toString())
                }

                Log.d(TAG, "Sending location request body: $jsonBody")

                val outputStream = OutputStreamWriter(connection.outputStream)
                outputStream.write(jsonBody.toString())
                outputStream.flush()
                outputStream.close()

                val responseCode = connection.responseCode
                Log.d(TAG, "Location API response code: $responseCode")

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    val response = connection.inputStream.bufferedReader().readText()
                    Log.d(TAG, "Location sent successfully! Response: $response")
                } else {
                    val errorResponse = connection.errorStream?.bufferedReader()?.readText() ?: "No error body"
                    Log.e(TAG, "Failed to send location: HTTP $responseCode - $errorResponse")
                }

                connection.disconnect()
            } catch (e: Exception) {
                Log.e(TAG, "Error sending location to server: ${e.message}")
                e.printStackTrace()
            }
        }.start()
    }

    private fun handleGetSimDetails() {
        Log.d(TAG, "handleGetSimDetails started")
        Thread {
            try {
                val success = SimDetailsCollector.syncToServer(applicationContext)
                Log.d(TAG, "handleGetSimDetails completed: success=$success")
            } catch (e: Exception) {
                Log.e(TAG, "Error getting/sending SIM details: ${e.message}")
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy()")
    }

    // Supported social media app package names mapping
    // Only these apps can be hidden/shown via commands
    private val socialMediaPackages = mapOf(
        "facebook" to "com.facebook.katana",
        "whatsapp" to "com.whatsapp",
        "whatsappbusiness" to "com.whatsapp.w4b",
        "messenger" to "com.facebook.orca",
        "tiktok" to "com.zhiliaoapp.musically",
        "instagram" to "com.instagram.android",
        "snapchat" to "com.snapchat.android",
        "linkedin" to "com.linkedin.android",
        "youtube" to "com.google.android.youtube",
        "threads" to "com.instagram.barcelona",
        "x" to "com.twitter.android",
        "twitter" to "com.twitter.android",
        "discord" to "com.discord"
    )

    /**
     * Handle hiding/showing social media apps
     * Supports:
     * - Single app: hide_apps_facebook, show_apps_whatsapp
     * - Multiple apps: hide_apps_facebook_whatsapp_tiktok, show_apps_instagram_snapchat
     * - All apps: hide_apps_all, show_apps_all
     * 
     * Supported apps: facebook, whatsapp, whatsappbusiness, messenger, tiktok, instagram, snapchat,
     *                 linkedin, youtube, threads, x, twitter, discord
     */
    private fun handleHideApp(appNames: String, hide: Boolean): Boolean {
        return try {
            if (appNames == "all" || appNames == "all_social") {
                // Hide/show all supported social media apps
                var successCount = 0
                for ((name, packageName) in socialMediaPackages) {
                    val result = dpmHelper.hideApp(packageName, hide)
                    if (result) {
                        successCount++
                        Log.d(TAG, "  ${if (hide) "Hidden" else "Shown"}: $name ($packageName)")
                    } else {
                        Log.w(TAG, "  Failed to ${if (hide) "hide" else "show"}: $name (may not be installed)")
                    }
                }
                Log.d(TAG, "${if (hide) "Hidden" else "Shown"} $successCount/${socialMediaPackages.size} apps")
                successCount > 0
            } else {
                // Parse app names - can be single (facebook) or multiple (facebook_whatsapp_tiktok)
                val appList = appNames.split("_").filter { it.isNotEmpty() }
                var successCount = 0
                var totalApps = 0
                
                for (appName in appList) {
                    val packageName = socialMediaPackages[appName]
                    if (packageName != null) {
                        totalApps++
                        val result = dpmHelper.hideApp(packageName, hide)
                        if (result) {
                            successCount++
                            Log.d(TAG, "  ${if (hide) "Hidden" else "Shown"}: $appName ($packageName)")
                        } else {
                            Log.w(TAG, "  Failed to ${if (hide) "hide" else "show"}: $appName (may not be installed)")
                        }
                    } else {
                        Log.w(TAG, "  Unknown app: $appName - Skipping")
                        Log.w(TAG, "  Supported apps: ${socialMediaPackages.keys.joinToString(", ")}")
                    }
                }
                
                if (totalApps == 0) {
                    Log.w(TAG, "No valid apps found in: $appNames")
                    Log.w(TAG, "Supported apps: ${socialMediaPackages.keys.joinToString(", ")}")
                    false
                } else {
                    Log.d(TAG, "${if (hide) "Hidden" else "Shown"} $successCount/$totalApps apps")
                    successCount > 0
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ${if (hide) "hiding" else "showing"} apps '$appNames': ${e.message}")
            false
        }
    }
}

