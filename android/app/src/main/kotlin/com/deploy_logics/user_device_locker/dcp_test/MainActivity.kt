package com.deploy_logics.user_device_locker

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.telephony.TelephonyManager
import android.text.TextUtils
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import android.os.Bundle
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val CHANNEL = "kiosk_channel"
    }

    private lateinit var dpmHelper: DevicePolicyManagerHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate() called")

        // Initialize DPM Helper early
        dpmHelper = DevicePolicyManagerHelper(this)

        // Start persistent FCM service for device owner apps
        // This keeps the app process alive for reliable FCM delivery
        if (dpmHelper.isDeviceOwner()) {
            Log.d(TAG, " Device Owner app - starting persistent FCM service")
            PersistentFCMService.start(this)

            // Ensure password reset token is set up for remote password management
            // This allows changing/removing Android lock screen PIN via FCM
            ensurePasswordResetTokenSetup()
            
            // IMPORTANT: Ensure FRP (Factory Reset Protection) is set up
            // This ensures that after factory reset, only the company account can unlock the device
            ensureFrpSetup()
        }

        // Register network callback for pending sync when internet becomes available
        NetworkChangeReceiver.registerNetworkCallback(applicationContext)
        
        // Sync any pending API calls
        syncPendingApiCalls()

        // Handle FCM notification data if app was opened from notification
        handleFCMIntent(intent)
    }

    /**
     * Sync any pending API calls (device status, uninstall status, etc.)
     */
    private fun syncPendingApiCalls() {
        try {
            Log.d(TAG, " Checking for pending API calls to sync...")
            // Sync pending device status
            DeviceStatusApi.syncPendingStatus(this)
            // Sync pending uninstall status
            CustomerStatusApi.syncPendingUninstallStatus(this, null)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error syncing pending API calls: ${e.message}")
        }
    }

    /**
     * Ensure password reset token is set up for Android 8.0+
     * This enables remote lock screen password change/removal via FCM notifications.
     */
    private fun ensurePasswordResetTokenSetup() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d(TAG, "Password reset token not needed for Android < 8.0")
            return
        }

        try {
            val tokenActive = dpmHelper.isResetPasswordTokenActive()
            if (tokenActive) {
                Log.d(TAG, "✅ Password reset token is already active - remote password management ready")
            } else {
                Log.d(TAG, "⚠️ Password reset token not active, attempting to set...")
                val success = dpmHelper.setResetPasswordToken()
                if (success) {
                    if (dpmHelper.isResetPasswordTokenActive()) {
                        Log.d(TAG, "✅ Token set and ACTIVE - remote password management ready")
                    } else {
                        Log.d(TAG, "⚠️ Token set but NOT active - will activate when user unlocks device")
                    }
                } else {
                    Log.w(TAG, "❌ Failed to set password reset token")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up password reset token: ${e.message}")
        }
    }

    /**
     * Ensure FRP (Factory Reset Protection) is set up for Android 11+
     * This ensures that after a factory reset, only the company account can unlock the device.
     * 
     * Default FRP account: ghazanfar@tech4uk.uk
     */
    private fun ensureFrpSetup() {
        Log.d(TAG, "")
        Log.d(TAG, "========== ENSURE FRP SETUP (MainActivity) ==========")
        Log.d(TAG, "Android SDK Version: ${Build.VERSION.SDK_INT}")
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "❌ FRP requires Android 11 (API 30) or higher")
            Log.w(TAG, "❌ Current version: ${Build.VERSION.SDK_INT}")
            return
        }

        try {
            val frpAccounts = dpmHelper.getFrpAccounts()
            Log.d(TAG, "Current FRP accounts: $frpAccounts")
            
            if (frpAccounts.isNotEmpty()) {
                Log.d(TAG, "✅ FRP already configured with accounts: $frpAccounts")
            } else {
                Log.d(TAG, "⚠️ FRP not configured, setting up now...")
                val defaultFrpEmail = "ghazanfar@tech4uk.uk"
                Log.d(TAG, "Setting FRP to: $defaultFrpEmail")
                
                val success = dpmHelper.setFrpAccount(defaultFrpEmail)
                if (success) {
                    // Verify it was set
                    val verifyAccounts = dpmHelper.getFrpAccounts()
                    Log.d(TAG, "Verification - FRP accounts after set: $verifyAccounts")
                    
                    if (verifyAccounts.contains(defaultFrpEmail)) {
                        Log.d(TAG, "✅✅✅ FRP VERIFIED: $defaultFrpEmail")
                        Log.d(TAG, " After factory reset, device can ONLY be unlocked with: $defaultFrpEmail")
                    } else {
                        Log.e(TAG, "❌ FRP verification failed!")
                    }
                } else {
                    Log.w(TAG, "❌ Failed to set FRP account")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting up FRP: ${e.message}")
            e.printStackTrace()
        }
        
        Log.d(TAG, "========== FRP SETUP CHECK COMPLETE ==========")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent() called")

        // Handle FCM notification data if app received new intent
        handleFCMIntent(intent)
    }

    /**
     * Handle FCM data from notification tap.
     * When FCM notification has a notification payload, onMessageReceived is NOT called
     * when app is killed. Instead, the data is passed in the intent when user taps notification.
     */
    private fun handleFCMIntent(intent: Intent?) {
        if (intent == null) return

        val extras = intent.extras ?: return

        Log.d(TAG, "handleFCMIntent - extras keys: ${extras.keySet()}")

        // Check for device command in intent extras
        val deviceCommand = (extras.getString("device")
            ?: extras.getString("Device")
            ?: extras.keySet().find { it.equals("device", ignoreCase = true) }?.let { extras.getString(it) }
        )?.lowercase()

        if (!deviceCommand.isNullOrEmpty()) {
            Log.d(TAG, " FCM command found in intent: $deviceCommand")

            // Execute the command via DeviceCommandService
            val serviceIntent = Intent(this, DeviceCommandService::class.java).apply {
                action = DeviceCommandService.ACTION_EXECUTE_COMMAND
                putExtra(DeviceCommandService.EXTRA_COMMAND, deviceCommand)
                // Copy all extras
                putExtras(extras)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                Log.d(TAG, "✅ DeviceCommandService started for: $deviceCommand")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error starting DeviceCommandService: ${e.message}")
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        // Initialize DPM Helper
        dpmHelper = DevicePolicyManagerHelper(this)

        // Prepare device policy manager and whitelist this package for lock task mode.
        val devicePolicyManager =
            getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)

        // It's safe to call setLockTaskPackages repeatedly; it will ensure your package
        // is whitelisted so startLockTask() becomes "true kiosk" (not just screen pinning).
        try {
            if (dpmHelper.isDeviceOwner()) {
                devicePolicyManager.setLockTaskPackages(adminComponent, arrayOf(packageName))
            }
        } catch (e: Exception) {
            Log.w(TAG, "setLockTaskPackages failed: ${e.message}")
        }

        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL
        ).setMethodCallHandler { call, result ->

            when (call.method) {

                // ==================== Status Checks ====================

                "isDeviceOwner" -> {
                    result.success(dpmHelper.isDeviceOwner())
                }

                "isProfileOwner" -> {
                    result.success(dpmHelper.isProfileOwner())
                }

                "isDeviceOrProfileOwner" -> {
                    result.success(dpmHelper.isDeviceOrProfileOwner())
                }

                "isAdminActive" -> {
                    result.success(dpmHelper.isAdminActive())
                }

                // ==================== Kiosk Mode ====================

                "startKiosk" -> {
                    if (dpmHelper.isDeviceOwner()) {
                        startLockTask()
                        result.success(true)
                    } else {
                        result.success(false)
                    }
                }

                "stopKiosk" -> {
                    stopLockTask()
                    result.success(true)
                }

                "setLockTaskPackages" -> {
                    val packages = call.argument<List<String>>("packages") ?: listOf(packageName)
                    result.success(dpmHelper.setLockTaskPackages(packages.toTypedArray()))
                }

                "setLockTaskFeatures" -> {
                    val flags = call.argument<Int>("flags") ?: 0
                    result.success(dpmHelper.setLockTaskFeatures(flags))
                }

                // ==================== Camera Control ====================

                "disableCamera" -> {
                    result.success(dpmHelper.setCameraDisabled(true))
                }

                "enableCamera" -> {
                    result.success(dpmHelper.setCameraDisabled(false))
                }

                "isCameraDisabled" -> {
                    result.success(dpmHelper.isCameraDisabled())
                }

                // ==================== Screen Capture Control ====================

                "disableScreenCapture" -> {
                    result.success(dpmHelper.setScreenCaptureDisabled(true))
                }

                "enableScreenCapture" -> {
                    result.success(dpmHelper.setScreenCaptureDisabled(false))
                }

                "isScreenCaptureDisabled" -> {
                    result.success(dpmHelper.isScreenCaptureDisabled())
                }

                // ==================== Factory Reset Control ====================

                "disableFactoryReset" -> {
                    result.success(dpmHelper.disableFactoryReset())
                }

                "enableFactoryReset" -> {
                    result.success(dpmHelper.enableFactoryReset())
                }

                // ==================== Safe Mode Control ====================

                "disableSafeMode" -> {
                    result.success(dpmHelper.disableSafeMode())
                }

                "enableSafeMode" -> {
                    result.success(dpmHelper.enableSafeMode())
                }

                // ==================== USB File Transfer Control ====================

                "disableUSBFileTransfer" -> {
                    result.success(dpmHelper.disableUSBFileTransfer())
                }

                "enableUSBFileTransfer" -> {
                    result.success(dpmHelper.enableUSBFileTransfer())
                }

                // ==================== App Install/Uninstall Control ====================

                "disableInstallApps" -> {
                    result.success(dpmHelper.disableInstallApps())
                }

                "enableInstallApps" -> {
                    result.success(dpmHelper.enableInstallApps())
                }

                "disableUninstallApps" -> {
                    result.success(dpmHelper.disableUninstallApps())
                }

                "enableUninstallApps" -> {
                    result.success(dpmHelper.enableUninstallApps())
                }

                // ==================== Location Control ====================

                "enableLocationAndLock" -> {
                    // Turn ON location AND disable the system toggle (user can't turn it off)
                    if (dpmHelper.isDeviceOwner()) {
                        try {
                            val success = dpmHelper.enableLocationWithLock()
                            Log.d(TAG, "enableLocationAndLock result: $success")
                            result.success(success)
                        } catch (e: Exception) {
                            Log.e(TAG, "enableLocationAndLock error: ${e.message}")
                            result.error("ERROR", e.message, null)
                        }
                    } else {
                        result.error("NOT_DEVICE_OWNER", "App is not device owner", null)
                    }
                }

                "disableLocationAndLock" -> {
                    // Turn OFF location AND disable the system toggle (user can't turn it on)
                    if (dpmHelper.isDeviceOwner()) {
                        try {
                            val success = dpmHelper.disableLocationWithLock()
                            Log.d(TAG, "disableLocationAndLock result: $success")
                            result.success(success)
                        } catch (e: Exception) {
                            Log.e(TAG, "disableLocationAndLock error: ${e.message}")
                            result.error("ERROR", e.message, null)
                        }
                    } else {
                        result.error("NOT_DEVICE_OWNER", "App is not device owner", null)
                    }
                }

                "unlockLocationSettings" -> {
                    // Allow user to change location settings (enable the toggle button)
                    result.success(dpmHelper.enableConfigLocation())
                }

                "disableLocationConfig" -> {
                    // ONLY disable the system toggle - does NOT change location state
                    // If location is ON, it stays ON but user can't turn it OFF
                    // If location is OFF, it stays OFF but user can't turn it ON
                    if (dpmHelper.isDeviceOwner()) {
                        try {
                            val success = dpmHelper.disableConfigLocation()
                            Log.d(TAG, "disableLocationConfig (toggle only) result: $success")
                            result.success(success)
                        } catch (e: Exception) {
                            Log.e(TAG, "disableLocationConfig error: ${e.message}")
                            result.error("ERROR", e.message, null)
                        }
                    } else {
                        Log.w(TAG, "disableLocationConfig: Not device owner")
                        result.success(false)
                    }
                }

                "enableLocationConfig" -> {
                    // Enable the system toggle - user CAN now toggle location on/off
                    // This clears all location restrictions
                    if (dpmHelper.isDeviceOwner()) {
                        try {
                            val success = dpmHelper.enableConfigLocation()
                            Log.d(TAG, "enableLocationConfig (unlock toggle) result: $success")
                            result.success(success)
                        } catch (e: Exception) {
                            Log.e(TAG, "enableLocationConfig error: ${e.message}")
                            result.error("ERROR", e.message, null)
                        }
                    } else {
                        Log.w(TAG, "enableLocationConfig: Not device owner")
                        result.success(false)
                    }
                }

                "setLocationEnabled" -> {
                    val enabled = call.argument<Boolean>("enabled") ?: true
                    // Just enable location config restriction (don't change the location state)
                    result.success(dpmHelper.enableConfigLocation())
                }

                "setLocationDisabled" -> {
                    // Block user from toggling location (don't change the location state)
                    result.success(dpmHelper.disableConfigLocation())
                }

                "turnOnLocation" -> {
                    // Just turn on location without locking the toggle
                    result.success(dpmHelper.setLocationEnabled(true))
                }

                // ==================== WiFi Control ====================

                "disableConfigWifi" -> {
                    result.success(dpmHelper.disableConfigWifi())
                }

                "enableConfigWifi" -> {
                    result.success(dpmHelper.enableConfigWifi())
                }

                // ==================== Bluetooth Control ====================

                "disableConfigBluetooth" -> {
                    result.success(dpmHelper.disableConfigBluetooth())
                }

                "enableConfigBluetooth" -> {
                    result.success(dpmHelper.enableConfigBluetooth())
                }

                // ==================== Volume Control ====================

                "disableAdjustVolume" -> {
                    result.success(dpmHelper.disableAdjustVolume())
                }

                "enableAdjustVolume" -> {
                    result.success(dpmHelper.enableAdjustVolume())
                }

                // ==================== Calls/SMS Control ====================

                "disableOutgoingCalls" -> {
                    result.success(dpmHelper.disableOutgoingCalls())
                }

                "enableOutgoingCalls" -> {
                    result.success(dpmHelper.enableOutgoingCalls())
                }

                "disableSMS" -> {
                    result.success(dpmHelper.disableSMS())
                }

                "enableSMS" -> {
                    result.success(dpmHelper.enableSMS())
                }

                // ==================== App Management ====================

                "hideApp" -> {
                    val packageName = call.argument<String>("packageName")
                    val hidden = call.argument<Boolean>("hidden") ?: true
                    if (packageName != null) {
                        result.success(dpmHelper.hideApp(packageName, hidden))
                    } else {
                        result.error("INVALID_ARGUMENT", "Package name required", null)
                    }
                }

                "hideSelf" -> {
                    result.success(dpmHelper.hideSelf())
                }

                "showSelf" -> {
                    result.success(dpmHelper.showSelf())
                }

                "exitApp" -> {
                    try {
                        moveTaskToBack(true)
                        finish()
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "exitApp failed: ${e.message}")
                        result.success(false)
                    }
                }

                "openAppSettings" -> {
                    try {
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = Uri.parse("package:$packageName")
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "openAppSettings failed: ${e.message}")
                        result.success(false)
                    }
                }

                "prepareForUninstallAndShowUi" -> {
                    try {
                        UninstallFlowHelper.executeUninstallFlow(this)
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "prepareForUninstallAndShowUi failed: ${e.message}")
                        result.success(false)
                    }
                }

                "hasPendingUninstallUi" -> {
                    result.success(UninstallFlowHelper.hasPendingUninstallUi(this))
                }

                "clearPendingUninstallUi" -> {
                    UninstallFlowHelper.clearPendingUninstallUi(this)
                    result.success(true)
                }

                "isSelfHidden" -> {
                    result.success(dpmHelper.isSelfHidden())
                }

                "isAppHidden" -> {
                    val packageName = call.argument<String>("packageName")
                    if (packageName != null) {
                        result.success(dpmHelper.isAppHidden(packageName))
                    } else {
                        result.error("INVALID_ARGUMENT", "Package name required", null)
                    }
                }

                "hideAllApps" -> {
                    result.success(dpmHelper.hideAllUserApps(true))
                }

                "showAllApps" -> {
                    result.success(dpmHelper.showAllHiddenApps())
                }

                "getAllUserApps" -> {
                    result.success(dpmHelper.getAllUserApps())
                }

                "getHiddenApps" -> {
                    result.success(dpmHelper.getHiddenApps())
                }

                "suspendApp" -> {
                    val packageName = call.argument<String>("packageName")
                    val suspended = call.argument<Boolean>("suspended") ?: true
                    if (packageName != null) {
                        result.success(dpmHelper.setAppSuspended(packageName, suspended))
                    } else {
                        result.error("INVALID_ARGUMENT", "Package name required", null)
                    }
                }

                "blockUninstall" -> {
                    val packageName = call.argument<String>("packageName")
                    val blocked = call.argument<Boolean>("blocked") ?: true
                    if (packageName != null) {
                        result.success(dpmHelper.blockUninstall(packageName, blocked))
                    } else {
                        result.error("INVALID_ARGUMENT", "Package name required", null)
                    }
                }

                // ==================== Keyguard/Status Bar ====================

                "setKeyguardDisabled" -> {
                    val disabled = call.argument<Boolean>("disabled") ?: true
                    result.success(dpmHelper.setKeyguardDisabled(disabled))
                }

                "setStatusBarDisabled" -> {
                    val disabled = call.argument<Boolean>("disabled") ?: true
                    result.success(dpmHelper.setStatusBarDisabled(disabled))
                }

                // ==================== Device Actions ====================

                "lockNow" -> {
                    result.success(dpmHelper.lockNow())
                }

                "reboot" -> {
                    result.success(dpmHelper.reboot())
                }

                "wipeData" -> {
                    val flags = call.argument<Int>("flags") ?: 0
                    result.success(dpmHelper.wipeData(flags))
                }

                // ==================== Time/Timezone ====================

                "setTime" -> {
                    val timeMillis = call.argument<Long>("timeMillis")
                    if (timeMillis != null) {
                        result.success(dpmHelper.setTime(timeMillis))
                    } else {
                        result.error("INVALID_ARGUMENT", "Time in milliseconds required", null)
                    }
                }

                "setTimeZone" -> {
                    val timeZone = call.argument<String>("timeZone")
                    if (timeZone != null) {
                        result.success(dpmHelper.setTimeZone(timeZone))
                    } else {
                        result.error("INVALID_ARGUMENT", "Timezone required", null)
                    }
                }

                // ==================== System Update Policy ====================

                "setAutomaticSystemUpdates" -> {
                    result.success(dpmHelper.setAutomaticSystemUpdates())
                }

                "postponeSystemUpdates" -> {
                    result.success(dpmHelper.postponeSystemUpdates())
                }

                // ==================== Logging ====================

                "setNetworkLoggingEnabled" -> {
                    val enabled = call.argument<Boolean>("enabled") ?: true
                    result.success(dpmHelper.setNetworkLoggingEnabled(enabled))
                }

                "setSecurityLoggingEnabled" -> {
                    val enabled = call.argument<Boolean>("enabled") ?: true
                    result.success(dpmHelper.setSecurityLoggingEnabled(enabled))
                }

                // ==================== Launcher ====================

                "setAsDefaultLauncher" -> {
                    result.success(dpmHelper.setDefaultLauncher())
                }

                "clearDefaultLauncher" -> {
                    result.success(dpmHelper.clearDefaultLauncher())
                }

                // ==================== IMEI ====================

                "getIMEI" -> {
                    try {
                        val imei = getDeviceIMEI(0)
                        if (imei != null) {
                            result.success(imei)
                        } else {
                            result.error("IMEI_ERROR", "Could not retrieve IMEI", null)
                        }
                    } catch (e: Exception) {
                        result.error("IMEI_ERROR", e.message, null)
                    }
                }

                "getAllIMEIs" -> {
                    try {
                        val imeis = getAllDeviceIMEIs()
                        result.success(imeis)
                    } catch (e: Exception) {
                        result.error("IMEI_ERROR", e.message, null)
                    }
                }

                // ==================== Accessibility ====================

                "isAccessibilityEnabled" -> {
                    result.success(isAccessibilityServiceEnabled())
                }

                "openAccessibilitySettings" -> {
                    try {
                        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("ERROR", e.message, null)
                    }
                }

                // ==================== User Restrictions ====================

                "addUserRestriction" -> {
                    val restriction = call.argument<String>("restriction")
                    if (restriction != null) {
                        result.success(dpmHelper.addUserRestriction(restriction))
                    } else {
                        result.error("INVALID_ARGUMENT", "Restriction key required", null)
                    }
                }

                "clearUserRestriction" -> {
                    val restriction = call.argument<String>("restriction")
                    if (restriction != null) {
                        result.success(dpmHelper.clearUserRestriction(restriction))
                    } else {
                        result.error("INVALID_ARGUMENT", "Restriction key required", null)
                    }
                }

                // ==================== Lock Overlay ====================

                "showLockOverlay" -> {
                    val userId = call.argument<String>("userId") ?: ""
                    val pin = call.argument<String>("pin") ?: "1234"
                    try {
                        // Use broadcast receiver for better background support
                        LockOverlayReceiver.showOverlay(this, userId, pin)
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("ERROR", e.message, null)
                    }
                }

                "hideLockOverlay" -> {
                    try {
                        LockOverlayReceiver.hideOverlay(this)
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("ERROR", e.message, null)
                    }
                }

                "sendLockBroadcast" -> {
                    val userId = call.argument<String>("userId") ?: ""
                    val pin = call.argument<String>("pin") ?: "1234"
                    try {
                        LockOverlayReceiver.showOverlay(applicationContext, userId, pin)
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("ERROR", e.message, null)
                    }
                }

                "sendUnlockBroadcast" -> {
                    try {
                        LockOverlayReceiver.hideOverlay(applicationContext)
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("ERROR", e.message, null)
                    }
                }

                "sendEnableStatusBarBroadcast" -> {
                    try {
                        LockOverlayReceiver.enableStatusBar(applicationContext)
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("ERROR", e.message, null)
                    }
                }

                "sendDisableStatusBarBroadcast" -> {
                    try {
                        LockOverlayReceiver.disableStatusBar(applicationContext)
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("ERROR", e.message, null)
                    }
                }

                "isLockOverlayShowing" -> {
                    result.success(LockOverlayService.isShowing)
                }

                // ==================== Lock Activity (Lock Task Mode) ====================

                "startLockActivity" -> {
                    try {
                        LockActivity.setLocked(applicationContext, true)
                        LockActivity.start(applicationContext)
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("ERROR", e.message, null)
                    }
                }

                "stopLockActivity" -> {
                    try {
                        LockActivity.setLocked(applicationContext, false)
                        // The activity will finish itself when lock state is false
                        result.success(true)
                    } catch (e: Exception) {
                        result.error("ERROR", e.message, null)
                    }
                }

                "isLockActivityShowing" -> {
                    result.success(LockActivity.isLocked(applicationContext))
                }

                // ==================== Permission Locking ====================

                "lockAllAppPermissions" -> {
                    result.success(dpmHelper.lockAllAppPermissions())
                }

                "unlockAllAppPermissions" -> {
                    result.success(dpmHelper.unlockAllAppPermissions())
                }

                "setPermissionGrantState" -> {
                    val packageName = call.argument<String>("packageName") ?: this.packageName
                    val permission = call.argument<String>("permission")
                    val grantState = call.argument<Int>("grantState") ?: 0
                    if (permission != null) {
                        result.success(dpmHelper.setPermissionGrantState(packageName, permission, grantState))
                    } else {
                        result.error("INVALID_ARGUMENT", "Permission required", null)
                    }
                }

                "getPermissionGrantState" -> {
                    val packageName = call.argument<String>("packageName") ?: this.packageName
                    val permission = call.argument<String>("permission")
                    if (permission != null) {
                        result.success(dpmHelper.getPermissionGrantState(packageName, permission))
                    } else {
                        result.error("INVALID_ARGUMENT", "Permission required", null)
                    }
                }

                "disableConfigApps" -> {
                    result.success(dpmHelper.disableConfigApps())
                }

                "enableConfigApps" -> {
                    result.success(dpmHelper.enableConfigApps())
                }

                "blockSelfUninstall" -> {
                    result.success(dpmHelper.blockSelfUninstall())
                }

                "allowSelfUninstall" -> {
                    result.success(dpmHelper.allowSelfUninstall())
                }

                "lockPermissionsAfterGrant" -> {
                    result.success(dpmHelper.lockPermissionsAfterGrant())
                }

                "unlockPermissions" -> {
                    result.success(dpmHelper.unlockPermissions())
                }

                // ==================== Overlay Permission Locking ====================

                "lockOverlayPermission" -> {
                    result.success(dpmHelper.lockOverlayPermission())
                }

                "unlockOverlayPermission" -> {
                    result.success(dpmHelper.unlockOverlayPermission())
                }

                "isOverlayPermissionLocked" -> {
                    result.success(dpmHelper.isOverlayPermissionLocked())
                }

                "lockSettingsWhenOverlayShown" -> {
                    result.success(dpmHelper.lockSettingsWhenOverlayShown())
                }

                "unlockSettingsWhenOverlayHidden" -> {
                    result.success(dpmHelper.unlockSettingsWhenOverlayHidden())
                }

                // ==================== Notification Permission Locking ====================

                "lockNotificationPermission" -> {
                    result.success(dpmHelper.lockNotificationPermission())
                }

                "unlockNotificationPermission" -> {
                    result.success(dpmHelper.unlockNotificationPermission())
                }

                "disableNotificationPermission" -> {
                    result.success(dpmHelper.disableNotificationPermission())
                }

                "isNotificationPermissionLocked" -> {
                    result.success(dpmHelper.isNotificationPermissionLocked())
                }

                "isNotificationPermissionDisabled" -> {
                    result.success(dpmHelper.isNotificationPermissionDisabled())
                }

                "forceRelockNotificationPermission" -> {
                    result.success(dpmHelper.forceRelockNotificationPermission())
                }

                "lockNotificationPermissionForRealme" -> {
                    result.success(dpmHelper.lockNotificationPermissionForRealme())
                }

                "startNotificationMonitor" -> {
                    NotificationPermissionMonitor.start(applicationContext)
                    result.success(true)
                }

                "stopNotificationMonitor" -> {
                    NotificationPermissionMonitor.stop(applicationContext)
                    result.success(true)
                }

                // ==================== FRP (Factory Reset Protection) ====================

                "setFrpAccount" -> {
                    val accountEmail = call.argument<String>("accountEmail")
                    if (accountEmail != null) {
                        result.success(dpmHelper.setFrpAccount(accountEmail))
                    } else {
                        result.error("INVALID_ARGUMENT", "Account email required", null)
                    }
                }

                "setFrpAccounts" -> {
                    val accountEmails = call.argument<List<String>>("accountEmails")
                    if (accountEmails != null && accountEmails.isNotEmpty()) {
                        result.success(dpmHelper.setFrpAccounts(accountEmails))
                    } else {
                        result.error("INVALID_ARGUMENT", "Account emails list required", null)
                    }
                }

                "clearFrpPolicy" -> {
                    result.success(dpmHelper.clearFrpPolicy())
                }

                "getFrpAccounts" -> {
                    result.success(dpmHelper.getFrpAccounts())
                }

                "isFrpEnabled" -> {
                    result.success(dpmHelper.isFrpEnabled())
                }

                "enableFrpProtection" -> {
                    val accountEmail = call.argument<String>("accountEmail")
                    if (accountEmail != null) {
                        result.success(dpmHelper.enableFrpProtection(accountEmail))
                    } else {
                        result.error("INVALID_ARGUMENT", "Account email required", null)
                    }
                }

                "disableFrpProtection" -> {
                    result.success(dpmHelper.disableFrpProtection())
                }

                // ==================== Android Lock Screen Password ====================

                "setResetPasswordToken" -> {
                    result.success(dpmHelper.setResetPasswordToken())
                }

                "isResetPasswordTokenActive" -> {
                    result.success(dpmHelper.isResetPasswordTokenActive())
                }

                "clearResetPasswordToken" -> {
                    result.success(dpmHelper.clearResetPasswordToken())
                }

                "setLockScreenPassword" -> {
                    val password = call.argument<String>("password") ?: ""
                    result.success(dpmHelper.setLockScreenPassword(password))
                }

                "removeLockScreenPassword" -> {
                    result.success(dpmHelper.removeLockScreenPassword())
                }

                "setPasswordQuality" -> {
                    val quality = call.argument<Int>("quality") ?: 0
                    result.success(dpmHelper.setPasswordQuality(quality))
                }

                "allowNoPassword" -> {
                    result.success(dpmHelper.allowNoPassword())
                }

                "getLockScreenStatus" -> {
                    result.success(dpmHelper.getLockScreenStatus())
                }

                "setupPasswordResetTokenWithStatus" -> {
                    result.success(dpmHelper.setupPasswordResetTokenWithStatus())
                }

                "changePasswordWithStatus" -> {
                    val password = call.argument<String>("password") ?: ""
                    result.success(dpmHelper.changePasswordWithStatus(password))
                }

                "changePasswordWithForcedTokenReset" -> {
                    val password = call.argument<String>("password") ?: ""
                    Log.d(TAG, "changePasswordWithForcedTokenReset called")
                    result.success(dpmHelper.changePasswordWithForcedTokenReset(password))
                }

                "removePasswordViaTemporaryPin" -> {
                    Log.d(TAG, "removePasswordViaTemporaryPin called")
                    result.success(dpmHelper.removePasswordViaTemporaryPin())
                }

                "isDeviceSecure" -> {
                    result.success(dpmHelper.isDeviceSecure())
                }

                // ==================== Wallpaper Control ====================

                "changeWallpaper" -> {
                    val wallpaperUrl = call.argument<String>("wallpaperUrl")
                    if (wallpaperUrl != null && wallpaperUrl.isNotEmpty()) {
                        Log.d(TAG, "changeWallpaper called with URL: $wallpaperUrl")
                        WallpaperService.changeWallpaper(this, wallpaperUrl)
                        result.success(true)
                    } else {
                        result.error("INVALID_ARGUMENT", "wallpaperUrl is required", null)
                    }
                }

                "removeWallpaper" -> {
                    Log.d(TAG, "removeWallpaper called")
                    WallpaperService.removeWallpaper(this)
                    result.success(true)
                }

                "showMessageOverlay" -> {
                    val message = call.argument<String>("message")
                    val companyName = call.argument<String>("companyName")
                    Log.d(TAG, "showMessageOverlay called - message: $message, companyName: $companyName")
                    try {
                        if (message != null && message.isNotEmpty()) {
                            MessageOverlayService.show(this, message, companyName)
                            Log.d(TAG, "✅ MessageOverlayService started")
                            result.success(true)
                        } else {
                            Log.w(TAG, "⚠️ showMessageOverlay called with empty message")
                            result.success(false)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error showing MessageOverlay: ${e.message}")
                        result.success(false)
                    }
                }

                "hideMessageOverlay" -> {
                    Log.d(TAG, "hideMessageOverlay called")
                    try {
                        if (MessageOverlayService.isShowing) {
                            MessageOverlayService.hide(this)
                            Log.d(TAG, "✅ MessageOverlayService hidden")
                            result.success(true)
                        } else {
                            Log.d(TAG, " MessageOverlay not showing")
                            result.success(true)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error hiding MessageOverlay: ${e.message}")
                        result.success(false)
                    }
                }

                "hideLockOverlay" -> {
                    Log.d(TAG, "hideLockOverlay called")
                    try {
                        if (LockOverlayService.isShowing) {
                            LockOverlayService.hide(this)
                            Log.d(TAG, "✅ LockOverlayService hidden")
                            result.success(true)
                        } else {
                            Log.d(TAG, " LockOverlay not showing")
                            result.success(true)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error hiding LockOverlay: ${e.message}")
                        result.success(false)
                    }
                }

                "hideAllOverlays" -> {
                    Log.d(TAG, "hideAllOverlays called")
                    try {
                        // Hide LockOverlayService
                        if (LockOverlayService.isShowing) {
                            LockOverlayService.hide(this)
                            Log.d(TAG, "✅ LockOverlayService hidden")
                        }
                        // Hide MessageOverlayService
                        if (MessageOverlayService.isShowing) {
                            MessageOverlayService.hide(this)
                            Log.d(TAG, "✅ MessageOverlayService hidden")
                        }
                        // Stop LockActivity
                        LockActivity.setLocked(this, false)
                        Log.d(TAG, "✅ LockActivity stopped")

                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error hiding overlays: ${e.message}")
                        result.success(false)
                    }
                }

                // ==================== SIM Details ====================

                "getSimDetails" -> {
                    Log.d(TAG, "getSimDetails called - delegating to DeviceCommandService")
                    try {
                        DeviceCommandService.executeCommand(this, "sim_details")
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error executing getSimDetails: ${e.message}")
                        result.success(false)
                    }
                }

                // ==================== Factory Reset Warning ====================

                "showFactoryResetWarning" -> {
                    val title = call.argument<String>("title")
                    val message = call.argument<String>("message")
                    Log.d(TAG, "showFactoryResetWarning called")
                    try {
                        FactoryResetWarningService.show(this, title, message)
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error showing factory reset warning: ${e.message}")
                        result.success(false)
                    }
                }

                "hideFactoryResetWarning" -> {
                    Log.d(TAG, "hideFactoryResetWarning called")
                    try {
                        FactoryResetWarningService.hide(this)
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error hiding factory reset warning: ${e.message}")
                        result.success(false)
                    }
                }

                "isFactoryResetWarningShowing" -> {
                    result.success(FactoryResetWarningService.isShowing)
                }

                "setFactoryResetWarningEnabled" -> {
                    val enabled = call.argument<Boolean>("enabled") ?: true
                    Log.d(TAG, "setFactoryResetWarningEnabled: $enabled")
                    try {
                        MyAccessibilityService.instance?.setWarningEnabled(enabled)
                        result.success(true)
                    } catch (e: Exception) {
                        Log.e(TAG, "Error setting factory reset warning enabled: ${e.message}")
                        result.success(false)
                    }
                }

                else -> result.notImplemented()
            }
        }
    }

    @SuppressLint("HardwareIds")
    private fun getDeviceIMEI(slotIndex: Int = 0): String? {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ - Only works for Device Owner apps
                val devicePolicyManager = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (devicePolicyManager.isDeviceOwnerApp(packageName)) {
                    telephonyManager.getImei(slotIndex)
                } else {
                    // Fallback to Android ID for non-device-owner apps on Android 10+
                    Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8 & 9
                @Suppress("DEPRECATION")
                telephonyManager.getImei(slotIndex)
            } else {
                // Android 7 and below
                @Suppress("DEPRECATION")
                if (slotIndex == 0) {
                    telephonyManager.deviceId
                } else {
                    // For older devices, try to get second IMEI using reflection
                    try {
                        val method = telephonyManager.javaClass.getMethod("getDeviceId", Int::class.javaPrimitiveType)
                        method.invoke(telephonyManager, slotIndex) as? String
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        } catch (e: SecurityException) {
            // If permission denied, return Android ID as fallback
            Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        }
    }

    @SuppressLint("HardwareIds")
    private fun getAllDeviceIMEIs(): List<String> {
        val telephonyManager = getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val imeis = mutableListOf<String>()

        // Get phone count (number of SIM slots)
        val phoneCount = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            telephonyManager.activeModemCount
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            telephonyManager.phoneCount
        } else {
            1
        }

        for (i in 0 until phoneCount) {
            try {
                val imei = getDeviceIMEI(i)
                if (imei != null && imei.isNotEmpty()) {
                    imeis.add(imei)
                }
            } catch (e: Exception) {
                // Skip this slot if error
            }
        }

        // If no IMEIs found, return Android ID as fallback
        if (imeis.isEmpty()) {
            val androidId = Settings.Secure.getString(
                contentResolver,
                Settings.Secure.ANDROID_ID
            )
            if (androidId != null) {
                imeis.add(androidId)
            }
        }

        return imeis
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "$packageName/.MyAccessibilityService"
        val enabledServices = Settings.Secure.getString(
            contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )

        return if (enabledServices.isNullOrEmpty()) {
            false
        } else {
            TextUtils.SimpleStringSplitter(':').apply {
                setString(enabledServices)
            }.any { it.equals(serviceName, ignoreCase = true) || it.contains(packageName) }
        }
    }
}