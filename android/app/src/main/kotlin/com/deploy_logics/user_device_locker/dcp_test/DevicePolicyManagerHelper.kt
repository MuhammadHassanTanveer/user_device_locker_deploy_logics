package com.deploy_logics.user_device_locker

import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log

/**
 * Helper class for Device Policy Manager operations
 * Similar to TestDPC's PolicyManagementFragment
 */
class DevicePolicyManagerHelper(private val context: Context) {

    companion object {
        private const val TAG = "DPMHelper"
        private const val TOKEN_PREFS = "PasswordTokenPrefs"
        private const val TOKEN_KEY = "reset_token"
        // Store the password reset token (should persist across app restarts)
        private var resetToken: ByteArray? = null
    }

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent: ComponentName = MyDeviceAdminReceiver.getComponentName(context)

    // ==================== Status Checks ====================

    fun isDeviceOwner(): Boolean = dpm.isDeviceOwnerApp(context.packageName)

    fun isProfileOwner(): Boolean = dpm.isProfileOwnerApp(context.packageName)

    fun isDeviceOrProfileOwner(): Boolean = isDeviceOwner() || isProfileOwner()

    fun isAdminActive(): Boolean = dpm.isAdminActive(adminComponent)

    // ==================== Camera Control ====================

    fun setCameraDisabled(disabled: Boolean): Boolean {
        return try {
            if (isDeviceOrProfileOwner()) {
                dpm.setCameraDisabled(adminComponent, disabled)
                Log.d(TAG, "Camera disabled: $disabled")
                true
            } else {
                Log.w(TAG, "Not device/profile owner, cannot control camera")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting camera disabled: ${e.message}")
            false
        }
    }

    fun isCameraDisabled(): Boolean {
        return try {
            dpm.getCameraDisabled(adminComponent)
        } catch (e: Exception) {
            false
        }
    }

    // ==================== Screen Capture Control ====================

    fun setScreenCaptureDisabled(disabled: Boolean): Boolean {
        return try {
            if (isDeviceOrProfileOwner()) {
                dpm.setScreenCaptureDisabled(adminComponent, disabled)
                Log.d(TAG, "Screen capture disabled: $disabled")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting screen capture disabled: ${e.message}")
            false
        }
    }

    fun isScreenCaptureDisabled(): Boolean {
        return try {
            dpm.getScreenCaptureDisabled(adminComponent)
        } catch (e: Exception) {
            false
        }
    }

    // ==================== Lock Task (Kiosk Mode) ====================

    fun setLockTaskPackages(packages: Array<String>): Boolean {
        return try {
            if (isDeviceOwner()) {
                dpm.setLockTaskPackages(adminComponent, packages)
                Log.d(TAG, "Lock task packages set: ${packages.joinToString()}")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting lock task packages: ${e.message}")
            false
        }
    }

    fun getLockTaskPackages(): Array<String> {
        return try {
            if (isDeviceOwner()) {
                dpm.getLockTaskPackages(adminComponent)
            } else {
                emptyArray()
            }
        } catch (e: Exception) {
            emptyArray()
        }
    }

    fun setLockTaskFeatures(flags: Int): Boolean {
        return try {
            if (isDeviceOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLockTaskFeatures(adminComponent, flags)
                Log.d(TAG, "Lock task features set: $flags")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting lock task features: ${e.message}")
            false
        }
    }

    // ==================== User Restrictions ====================

    fun addUserRestriction(restriction: String): Boolean {
        return try {
            if (isDeviceOrProfileOwner()) {
                dpm.addUserRestriction(adminComponent, restriction)
                Log.d(TAG, "User restriction added: $restriction")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error adding user restriction: ${e.message}")
            false
        }
    }

    fun clearUserRestriction(restriction: String): Boolean {
        return try {
            if (isDeviceOrProfileOwner()) {
                dpm.clearUserRestriction(adminComponent, restriction)
                Log.d(TAG, "User restriction cleared: $restriction")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing user restriction: ${e.message}")
            false
        }
    }

    fun getUserRestrictions(): android.os.Bundle? {
        return try {
            if (isDeviceOrProfileOwner()) {
                dpm.getUserRestrictions(adminComponent)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    // Common user restrictions
    /**
     * Disable factory reset AND auto-enable FRP protection.
     * This ensures that even if user hard resets, they need the FRP account.
     */
    fun disableFactoryReset(): Boolean {
        val result = addUserRestriction(UserManager.DISALLOW_FACTORY_RESET)
        if (result) {
            // Auto-enable FRP when factory reset is disabled
            val frpResult = enableFrpProtection("ghazanfar@tech4uk.uk")
            Log.d(TAG, "🔒 Factory reset disabled, FRP auto-enabled: $frpResult")
        }
        return result
    }

    /**
     * Enable factory reset AND auto-disable FRP protection.
     * This allows user to reset without needing FRP account.
     */
    fun enableFactoryReset(): Boolean {
        val result = clearUserRestriction(UserManager.DISALLOW_FACTORY_RESET)
        if (result) {
            // Auto-disable FRP when factory reset is enabled
            val frpResult = disableFrpProtection()
            Log.d(TAG, "🔓 Factory reset enabled, FRP auto-disabled: $frpResult")
        }
        return result
    }

    /**
     * Check if factory reset is currently disabled (DISALLOW_FACTORY_RESET restriction is set).
     * Returns true if factory reset is DISABLED, false if it's ENABLED.
     */
    fun isFactoryResetDisabled(): Boolean {
        return try {
            val restrictions = getUserRestrictions()
            restrictions?.getBoolean(UserManager.DISALLOW_FACTORY_RESET, false) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking factory reset status: ${e.message}")
            false
        }
    }

    fun disableSafeMode(): Boolean = addUserRestriction(UserManager.DISALLOW_SAFE_BOOT)
    fun enableSafeMode(): Boolean = clearUserRestriction(UserManager.DISALLOW_SAFE_BOOT)

    fun disableUSBFileTransfer(): Boolean = addUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER)
    fun enableUSBFileTransfer(): Boolean = clearUserRestriction(UserManager.DISALLOW_USB_FILE_TRANSFER)

    fun disableInstallApps(): Boolean = addUserRestriction(UserManager.DISALLOW_INSTALL_APPS)
    fun enableInstallApps(): Boolean = clearUserRestriction(UserManager.DISALLOW_INSTALL_APPS)

    fun disableUninstallApps(): Boolean = addUserRestriction(UserManager.DISALLOW_UNINSTALL_APPS)
    fun enableUninstallApps(): Boolean = clearUserRestriction(UserManager.DISALLOW_UNINSTALL_APPS)

    fun disableLocationSharing(): Boolean = addUserRestriction(UserManager.DISALLOW_SHARE_LOCATION)
    fun enableLocationSharing(): Boolean = clearUserRestriction(UserManager.DISALLOW_SHARE_LOCATION)

    // Block user from changing location settings (the toggle button in Android settings)
    // This ONLY disables the toggle - does NOT change the current location state
    fun disableConfigLocation(): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "disableConfigLocation: Not device owner")
            return false
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Use DISALLOW_CONFIG_LOCATION to disable the toggle
                val result = addUserRestriction(UserManager.DISALLOW_CONFIG_LOCATION)
                Log.d(TAG, "disableConfigLocation: DISALLOW_CONFIG_LOCATION added=$result")
                result
            } else {
                Log.w(TAG, "disableConfigLocation: Not supported on Android < 9")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "disableConfigLocation error: ${e.message}")
            false
        }
    }

    // Allow user to change location settings - clear the config restriction
    fun enableConfigLocation(): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "enableConfigLocation: Not device owner")
            return false
        }

        return try {
            // Clear both restrictions to ensure toggle is fully unlocked
            var success = true

            // Clear DISALLOW_SHARE_LOCATION (in case it was set)
            try {
                clearUserRestriction(UserManager.DISALLOW_SHARE_LOCATION)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear DISALLOW_SHARE_LOCATION: ${e.message}")
            }

            // Clear DISALLOW_CONFIG_LOCATION
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                success = clearUserRestriction(UserManager.DISALLOW_CONFIG_LOCATION)
            }

            Log.d(TAG, "enableConfigLocation: success=$success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "enableConfigLocation error: ${e.message}")
            false
        }
    }

    // Turn OFF location AND disable the toggle (for turn off lock feature)
    // User cannot turn location back on
    fun disableLocationWithLock(): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "disableLocationWithLock: Not device owner")
            return false
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Step 1: Turn OFF location first
                dpm.setLocationEnabled(adminComponent, false)
                Log.d(TAG, "disableLocationWithLock: Location turned OFF")

                // Step 2: Add the restriction to lock the toggle
                val restrictionResult = addUserRestriction(UserManager.DISALLOW_CONFIG_LOCATION)
                Log.d(TAG, "disableLocationWithLock: restriction added=$restrictionResult")

                true
            } else {
                // On older versions, use DISALLOW_SHARE_LOCATION which does both
                addUserRestriction(UserManager.DISALLOW_SHARE_LOCATION)
            }
        } catch (e: Exception) {
            Log.e(TAG, "disableLocationWithLock error: ${e.message}")
            false
        }
    }

    // Turn ON location AND disable the toggle (for turn on lock feature)
    // User cannot turn location off
    fun enableLocationWithLock(): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "enableLocationWithLock: Not device owner")
            return false
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // IMPORTANT: We need to add restriction first, then turn on location
                // because on some devices adding DISALLOW_CONFIG_LOCATION turns off location

                // Step 1: Add the restriction to lock the toggle
                val restrictionResult = addUserRestriction(UserManager.DISALLOW_CONFIG_LOCATION)
                Log.d(TAG, "enableLocationWithLock: restriction added=$restrictionResult")

                // Small delay
                Thread.sleep(200)

                // Step 2: Turn ON location (this should work even with restriction in place for device owner)
                dpm.setLocationEnabled(adminComponent, true)
                Log.d(TAG, "enableLocationWithLock: Location turned ON")

                true
            } else {
                // On older versions, we can only turn on location
                setLocationEnabled(true)
            }
        } catch (e: Exception) {
            Log.e(TAG, "enableLocationWithLock error: ${e.message}")
            false
        }
    }

    fun disableConfigWifi(): Boolean = addUserRestriction(UserManager.DISALLOW_CONFIG_WIFI)
    fun enableConfigWifi(): Boolean = clearUserRestriction(UserManager.DISALLOW_CONFIG_WIFI)

    fun disableConfigBluetooth(): Boolean = addUserRestriction(UserManager.DISALLOW_CONFIG_BLUETOOTH)
    fun enableConfigBluetooth(): Boolean = clearUserRestriction(UserManager.DISALLOW_CONFIG_BLUETOOTH)

    fun disableAdjustVolume(): Boolean = addUserRestriction(UserManager.DISALLOW_ADJUST_VOLUME)
    fun enableAdjustVolume(): Boolean = clearUserRestriction(UserManager.DISALLOW_ADJUST_VOLUME)

    fun disableOutgoingCalls(): Boolean = addUserRestriction(UserManager.DISALLOW_OUTGOING_CALLS)
    fun enableOutgoingCalls(): Boolean = clearUserRestriction(UserManager.DISALLOW_OUTGOING_CALLS)

    fun disableSMS(): Boolean = addUserRestriction(UserManager.DISALLOW_SMS)
    fun enableSMS(): Boolean = clearUserRestriction(UserManager.DISALLOW_SMS)

    // ==================== Account Modification Control ====================

    /**
     * Lock accounts - Block adding/removing Google accounts.
     * This prevents users from:
     * - Adding their personal Gmail (which could become FRP owner)
     * - Removing existing company accounts
     *
     * IMPORTANT for FRP: Call this AFTER company account is added to device
     *
     * @return true if successful
     */
    fun lockAccounts(): Boolean {
        return try {
            if (!isDeviceOwner()) {
                Log.w(TAG, "lockAccounts: Not device owner")
                return false
            }

            val result = addUserRestriction(UserManager.DISALLOW_MODIFY_ACCOUNTS)
            if (result) {
                Log.d(TAG, "🔒 Accounts locked - Personal Gmail add nahi ho sakta, Existing accounts remove nahi ho sakte")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error locking accounts: ${e.message}")
            false
        }
    }

    /**
     * Unlock accounts - Allow adding/removing Google accounts.
     *
     * @return true if successful
     */
    fun unlockAccounts(): Boolean {
        return try {
            if (!isDeviceOwner()) {
                Log.w(TAG, "unlockAccounts: Not device owner")
                return false
            }

            val result = clearUserRestriction(UserManager.DISALLOW_MODIFY_ACCOUNTS)
            if (result) {
                Log.d(TAG, "🔓 Accounts unlocked - User can add/remove accounts")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error unlocking accounts: ${e.message}")
            false
        }
    }

    /**
     * Check if account modification is blocked.
     *
     * @return true if accounts are locked
     */
    fun isAccountsLocked(): Boolean {
        return try {
            val restrictions = getUserRestrictions()
            restrictions?.getBoolean(UserManager.DISALLOW_MODIFY_ACCOUNTS, false) ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking accounts lock status: ${e.message}")
            false
        }
    }

    // ==================== Location Control ====================

    fun setLocationEnabled(enabled: Boolean): Boolean {
        return try {
            if (isDeviceOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLocationEnabled(adminComponent, enabled)
                Log.d(TAG, "Location enabled: $enabled")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting location enabled: ${e.message}")
            false
        }
    }

    /**
     * Turn ON location without affecting any restrictions
     * Just enables location services
     */
    fun turnOnLocation(): Boolean {
        return try {
            if (isDeviceOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setLocationEnabled(adminComponent, true)
                Log.d(TAG, "Location turned ON")
                true
            } else {
                Log.w(TAG, "turnOnLocation: Not device owner or Android < P")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error turning on location: ${e.message}")
            false
        }
    }

    // ==================== App Management ====================

    fun hideApp(packageName: String, hidden: Boolean): Boolean {
        return try {
            if (isDeviceOrProfileOwner()) {
                dpm.setApplicationHidden(adminComponent, packageName, hidden)
                Log.d(TAG, "App $packageName hidden: $hidden")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding app: ${e.message}")
            false
        }
    }

    /**
     * Hide this app from the launcher
     * The app will still be running but won't appear in app drawer
     */
    fun hideSelf(): Boolean {
        return try {
            if (isDeviceOrProfileOwner()) {
                val packageName = context.packageName
                dpm.setApplicationHidden(adminComponent, packageName, true)
                Log.d(TAG, "Self app hidden: $packageName")
                true
            } else {
                Log.w(TAG, "hideSelf: Not device or profile owner")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding self: ${e.message}")
            false
        }
    }

    /**
     * Show this app in the launcher again
     */
    fun showSelf(): Boolean {
        return try {
            if (isDeviceOrProfileOwner()) {
                val packageName = context.packageName
                dpm.setApplicationHidden(adminComponent, packageName, false)
                Log.d(TAG, "Self app shown: $packageName")
                true
            } else {
                Log.w(TAG, "showSelf: Not device or profile owner")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error showing self: ${e.message}")
            false
        }
    }

    /**
     * Check if this app is hidden
     */
    fun isSelfHidden(): Boolean {
        return try {
            if (isDeviceOrProfileOwner()) {
                val packageName = context.packageName
                dpm.isApplicationHidden(adminComponent, packageName)
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun isAppHidden(packageName: String): Boolean {
        return try {
            if (isDeviceOrProfileOwner()) {
                dpm.isApplicationHidden(adminComponent, packageName)
            } else {
                false
            }
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Get all installed user apps (excluding system apps and this app)
     * Returns a list of package names
     */
    fun getAllUserApps(): List<String> {
        return try {
            val pm = context.packageManager
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val ownPackageName = context.packageName
            
            installedApps
                .filter { appInfo ->
                    // Exclude system apps
                    (appInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 &&
                    // Exclude this app itself
                    appInfo.packageName != ownPackageName &&
                    // Exclude common launcher/system packages that shouldn't be hidden
                    !isProtectedSystemApp(appInfo.packageName)
                }
                .map { it.packageName }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting user apps: ${e.message}")
            emptyList()
        }
    }

    /**
     * Check if the package is a protected system app that shouldn't be hidden
     */
    private fun isProtectedSystemApp(packageName: String): Boolean {
        val protectedPackages = listOf(
            // Essential Google services
            "com.google.android.gms",                 // Google Play Services
            "com.google.android.gsf",                 // Google Services Framework
            "com.android.vending",                    // Play Store
            // Phone & Contacts (essential)
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.dialer",
            "com.android.phone",
            "com.android.contacts",
            "com.samsung.android.contacts",
            "com.google.android.contacts",
            // Settings
            "com.android.settings",
            // Default launchers
            "com.google.android.apps.nexuslauncher",
            "com.sec.android.app.launcher",
            "com.miui.home",
            "com.oppo.launcher",
            "com.realme.launcher",
            // This app
            context.packageName
        )
        return protectedPackages.any { packageName.startsWith(it) }
    }

    /**
     * Hide or show all user apps
     * @param hidden true to hide all apps, false to show all apps
     * @return number of apps successfully hidden/shown
     */
    fun hideAllUserApps(hidden: Boolean): Int {
        return try {
            if (!isDeviceOrProfileOwner()) {
                Log.w(TAG, "hideAllUserApps: Not device or profile owner")
                return 0
            }
            
            val userApps = getAllUserApps()
            var successCount = 0
            
            Log.d(TAG, "hideAllUserApps: ${if (hidden) "Hiding" else "Showing"} ${userApps.size} user apps")
            
            for (packageName in userApps) {
                try {
                    dpm.setApplicationHidden(adminComponent, packageName, hidden)
                    successCount++
                    Log.d(TAG, "  ${if (hidden) "Hidden" else "Shown"}: $packageName")
                } catch (e: Exception) {
                    Log.w(TAG, "  Failed to ${if (hidden) "hide" else "show"}: $packageName - ${e.message}")
                }
            }
            
            Log.d(TAG, "${if (hidden) "Hidden" else "Shown"} $successCount/${userApps.size} apps")
            successCount
        } catch (e: Exception) {
            Log.e(TAG, "Error ${if (hidden) "hiding" else "showing"} all apps: ${e.message}")
            0
        }
    }

    /**
     * Get the list of currently hidden apps
     */
    fun getHiddenApps(): List<String> {
        return try {
            if (!isDeviceOrProfileOwner()) {
                return emptyList()
            }
            
            val pm = context.packageManager
            val installedApps = pm.getInstalledApplications(PackageManager.GET_META_DATA or PackageManager.MATCH_UNINSTALLED_PACKAGES)
            
            installedApps
                .filter { appInfo ->
                    try {
                        dpm.isApplicationHidden(adminComponent, appInfo.packageName)
                    } catch (e: Exception) {
                        false
                    }
                }
                .map { it.packageName }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting hidden apps: ${e.message}")
            emptyList()
        }
    }

    /**
     * Show all currently hidden apps (restore visibility)
     * @return number of apps successfully shown
     */
    fun showAllHiddenApps(): Int {
        return try {
            if (!isDeviceOrProfileOwner()) {
                Log.w(TAG, "showAllHiddenApps: Not device or profile owner")
                return 0
            }
            
            val hiddenApps = getHiddenApps()
            var successCount = 0
            
            Log.d(TAG, "showAllHiddenApps: Showing ${hiddenApps.size} hidden apps")
            
            for (packageName in hiddenApps) {
                try {
                    dpm.setApplicationHidden(adminComponent, packageName, false)
                    successCount++
                    Log.d(TAG, "  Shown: $packageName")
                } catch (e: Exception) {
                    Log.w(TAG, "  Failed to show: $packageName - ${e.message}")
                }
            }
            
            Log.d(TAG, "Shown $successCount/${hiddenApps.size} apps")
            successCount
        } catch (e: Exception) {
            Log.e(TAG, "Error showing all hidden apps: ${e.message}")
            0
        }
    }

    fun setAppSuspended(packageName: String, suspended: Boolean): Boolean {
        return try {
            if (isDeviceOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                val packages = arrayOf(packageName)
                val result = dpm.setPackagesSuspended(adminComponent, packages, suspended)
                Log.d(TAG, "App $packageName suspended: $suspended, result: ${result.joinToString()}")
                result.isEmpty()
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error suspending app: ${e.message}")
            false
        }
    }

    fun blockUninstall(packageName: String, blocked: Boolean): Boolean {
        return try {
            if (isDeviceOrProfileOwner()) {
                dpm.setUninstallBlocked(adminComponent, packageName, blocked)
                Log.d(TAG, "App $packageName uninstall blocked: $blocked")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error blocking uninstall: ${e.message}")
            false
        }
    }

    // ==================== System Update Policy ====================

    fun setSystemUpdatePolicy(policy: SystemUpdatePolicy?): Boolean {
        return try {
            if (isDeviceOwner()) {
                dpm.setSystemUpdatePolicy(adminComponent, policy)
                Log.d(TAG, "System update policy set")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting system update policy: ${e.message}")
            false
        }
    }

    fun setAutomaticSystemUpdates(): Boolean {
        return setSystemUpdatePolicy(SystemUpdatePolicy.createAutomaticInstallPolicy())
    }

    fun setWindowedSystemUpdates(startTime: Int, endTime: Int): Boolean {
        return setSystemUpdatePolicy(SystemUpdatePolicy.createWindowedInstallPolicy(startTime, endTime))
    }

    fun postponeSystemUpdates(): Boolean {
        return setSystemUpdatePolicy(SystemUpdatePolicy.createPostponeInstallPolicy())
    }

    // ==================== Keyguard (Lock Screen) ====================

    fun setKeyguardDisabled(disabled: Boolean): Boolean {
        return try {
            if (isDeviceOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.setKeyguardDisabled(adminComponent, disabled)
                Log.d(TAG, "Keyguard disabled: $disabled")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting keyguard disabled: ${e.message}")
            false
        }
    }

    fun setKeyguardDisabledFeatures(features: Int): Boolean {
        return try {
            if (isDeviceOrProfileOwner()) {
                dpm.setKeyguardDisabledFeatures(adminComponent, features)
                Log.d(TAG, "Keyguard disabled features set: $features")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting keyguard disabled features: ${e.message}")
            false
        }
    }

    // ==================== Status Bar ====================

    fun setStatusBarDisabled(disabled: Boolean): Boolean {
        return try {
            if (isDeviceOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.setStatusBarDisabled(adminComponent, disabled)
                Log.d(TAG, "Status bar disabled: $disabled")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting status bar disabled: ${e.message}")
            false
        }
    }

    // ==================== Device Lock/Wipe ====================

    fun lockNow(): Boolean {
        return try {
            if (isAdminActive()) {
                dpm.lockNow()
                Log.d(TAG, "Device locked")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error locking device: ${e.message}")
            false
        }
    }

    fun reboot(): Boolean {
        return try {
            if (isDeviceOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.reboot(adminComponent)
                Log.d(TAG, "Device rebooting")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error rebooting device: ${e.message}")
            false
        }
    }

    fun wipeData(flags: Int = 0): Boolean {
        return try {
            if (isAdminActive()) {
                dpm.wipeData(flags)
                Log.d(TAG, "Device wiped")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error wiping device: ${e.message}")
            false
        }
    }

    // ==================== Time/Timezone ====================

    fun setTime(timeMillis: Long): Boolean {
        return try {
            if (isDeviceOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setTime(adminComponent, timeMillis)
                Log.d(TAG, "Time set to: $timeMillis")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting time: ${e.message}")
            false
        }
    }

    fun setTimeZone(timeZone: String): Boolean {
        return try {
            if (isDeviceOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setTimeZone(adminComponent, timeZone)
                Log.d(TAG, "Timezone set to: $timeZone")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting timezone: ${e.message}")
            false
        }
    }

    // ==================== Network Logging ====================

    fun setNetworkLoggingEnabled(enabled: Boolean): Boolean {
        return try {
            if (isDeviceOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dpm.setNetworkLoggingEnabled(adminComponent, enabled)
                Log.d(TAG, "Network logging enabled: $enabled")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting network logging: ${e.message}")
            false
        }
    }

    // ==================== Security Logging ====================

    fun setSecurityLoggingEnabled(enabled: Boolean): Boolean {
        return try {
            if (isDeviceOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.setSecurityLoggingEnabled(adminComponent, enabled)
                Log.d(TAG, "Security logging enabled: $enabled")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting security logging: ${e.message}")
            false
        }
    }

    // ==================== WiFi ====================

    fun setWifiEnabled(enabled: Boolean): Boolean {
        return try {
            if (isDeviceOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // On Android 11+, use the new method
                // Note: This requires the device to be in a managed profile or device owner mode
                Log.d(TAG, "WiFi control requires Android 11+ Device Owner")
                // WiFi control on Android 11+ is more restricted
                false
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting WiFi: ${e.message}")
            false
        }
    }

    // ==================== Bluetooth ====================

    fun setBluetoothContactSharingDisabled(disabled: Boolean): Boolean {
        return try {
            if (isProfileOwner()) {
                dpm.setBluetoothContactSharingDisabled(adminComponent, disabled)
                Log.d(TAG, "Bluetooth contact sharing disabled: $disabled")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting bluetooth contact sharing: ${e.message}")
            false
        }
    }

    // ==================== Permitted Input Methods ====================

    fun setPermittedInputMethods(packageNames: List<String>?): Boolean {
        return try {
            if (isDeviceOrProfileOwner()) {
                dpm.setPermittedInputMethods(adminComponent, packageNames)
                Log.d(TAG, "Permitted input methods set: ${packageNames?.joinToString()}")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting permitted input methods: ${e.message}")
            false
        }
    }

    // ==================== Default Launcher ====================

    fun setDefaultLauncher(): Boolean {
        return try {
            if (isDeviceOwner()) {
                val filter = IntentFilter(Intent.ACTION_MAIN)
                filter.addCategory(Intent.CATEGORY_HOME)
                filter.addCategory(Intent.CATEGORY_DEFAULT)

                dpm.addPersistentPreferredActivity(
                    adminComponent,
                    filter,
                    ComponentName(context.packageName, "${context.packageName}.MainActivity")
                )
                Log.d(TAG, "Default launcher set")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting default launcher: ${e.message}")
            false
        }
    }

    fun clearDefaultLauncher(): Boolean {
        return try {
            if (isDeviceOwner()) {
                dpm.clearPackagePersistentPreferredActivities(adminComponent, context.packageName)
                Log.d(TAG, "Default launcher cleared")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing default launcher: ${e.message}")
            false
        }
    }

    // ==================== Affiliation IDs ====================

    fun setAffiliationIds(ids: Set<String>): Boolean {
        return try {
            if (isDeviceOrProfileOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dpm.setAffiliationIds(adminComponent, ids)
                Log.d(TAG, "Affiliation IDs set: ${ids.joinToString()}")
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting affiliation IDs: ${e.message}")
            false
        }
    }

    fun getAffiliationIds(): Set<String> {
        return try {
            if (isDeviceOrProfileOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dpm.getAffiliationIds(adminComponent)
            } else {
                emptySet()
            }
        } catch (e: Exception) {
            emptySet()
        }
    }

    // ==================== Permission Locking ====================

    /**
     * Lock a runtime permission for a specific app.
     * This prevents the user from changing the permission state.
     * @param packageName The package name of the app
     * @param permission The permission to lock (e.g., android.permission.CAMERA)
     * @param grantState One of: PERMISSION_GRANT_STATE_GRANTED, PERMISSION_GRANT_STATE_DENIED, PERMISSION_GRANT_STATE_DEFAULT
     */
    fun setPermissionGrantState(packageName: String, permission: String, grantState: Int): Boolean {
        return try {
            if (isDeviceOrProfileOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val result = dpm.setPermissionGrantState(adminComponent, packageName, permission, grantState)
                Log.d(TAG, "Permission $permission for $packageName set to state $grantState: $result")
                result
            } else {
                Log.w(TAG, "setPermissionGrantState: Not device/profile owner or API < 23")
                false
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting permission grant state: ${e.message}")
            false
        }
    }

    /**
     * Get the current grant state of a permission
     */
    fun getPermissionGrantState(packageName: String, permission: String): Int {
        return try {
            if (isDeviceOrProfileOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                dpm.getPermissionGrantState(adminComponent, packageName, permission)
            } else {
                DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting permission grant state: ${e.message}")
            DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
        }
    }

    /**
     * Lock all granted permissions for this app.
     * This ensures the user cannot revoke any permissions.
     */
    fun lockAllAppPermissions(): Boolean {
        return try {
            if (!isDeviceOrProfileOwner()) {
                Log.w(TAG, "lockAllAppPermissions: Not device/profile owner")
                return false
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                Log.w(TAG, "lockAllAppPermissions: API < 23")
                return false
            }

            val packageName = context.packageName
            val packageInfo = context.packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            val permissions = packageInfo.requestedPermissions ?: return true

            var allSuccess = true
            for (permission in permissions) {
                try {
                    // Check if permission is granted
                    val isGranted = context.checkSelfPermission(permission) == android.content.pm.PackageManager.PERMISSION_GRANTED
                    if (isGranted) {
                        // Lock the permission in granted state
                        val result = dpm.setPermissionGrantState(
                            adminComponent,
                            packageName,
                            permission,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                        )
                        Log.d(TAG, "Locked permission $permission: $result")
                        if (!result) allSuccess = false
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Could not lock permission $permission: ${e.message}")
                    // Some permissions cannot be managed, skip them
                }
            }
            allSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Error locking app permissions: ${e.message}")
            false
        }
    }

    /**
     * Unlock all app permissions (allow user to change them again)
     */
    fun unlockAllAppPermissions(): Boolean {
        return try {
            if (!isDeviceOrProfileOwner()) {
                Log.w(TAG, "unlockAllAppPermissions: Not device/profile owner")
                return false
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                Log.w(TAG, "unlockAllAppPermissions: API < 23")
                return false
            }

            val packageName = context.packageName
            val packageInfo = context.packageManager.getPackageInfo(packageName, android.content.pm.PackageManager.GET_PERMISSIONS)
            val permissions = packageInfo.requestedPermissions ?: return true

            var allSuccess = true
            for (permission in permissions) {
                try {
                    // Reset permission to default (user-controllable)
                    val result = dpm.setPermissionGrantState(
                        adminComponent,
                        packageName,
                        permission,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
                    )
                    Log.d(TAG, "Unlocked permission $permission: $result")
                    if (!result) allSuccess = false
                } catch (e: Exception) {
                    Log.w(TAG, "Could not unlock permission $permission: ${e.message}")
                }
            }
            allSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Error unlocking app permissions: ${e.message}")
            false
        }
    }

    /**
     * Disable access to app settings (prevents user from going to app info screen)
     */
    fun disableConfigApps(): Boolean {
        return addUserRestriction(UserManager.DISALLOW_APPS_CONTROL)
    }

    /**
     * Enable access to app settings
     */
    fun enableConfigApps(): Boolean {
        return clearUserRestriction(UserManager.DISALLOW_APPS_CONTROL)
    }

    /**
     * Block uninstall for this app itself
     */
    fun blockSelfUninstall(): Boolean {
        return blockUninstall(context.packageName, true)
    }

    /**
     * Allow uninstall for this app (requires reboot or other means)
     */
    fun allowSelfUninstall(): Boolean {
        return blockUninstall(context.packageName, false)
    }

    /**
     * Lock all permissions after they are granted.
     * Also blocks access to app settings to prevent user from changing permissions.
     */
    fun lockPermissionsAfterGrant(): Boolean {
        return try {
            var success = true

            // 1. Lock all runtime permissions
            if (!lockAllAppPermissions()) {
                Log.w(TAG, "Failed to lock all app permissions")
                success = false
            }

            // 2. Block access to app settings (DISALLOW_APPS_CONTROL)
            if (!disableConfigApps()) {
                Log.w(TAG, "Failed to disable config apps")
                // This is not critical, continue
            }

            // 3. Block uninstall of this app
            if (!blockSelfUninstall()) {
                Log.w(TAG, "Failed to block self uninstall")
                // This is not critical, continue
            }

            Log.d(TAG, "lockPermissionsAfterGrant completed with success=$success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error in lockPermissionsAfterGrant: ${e.message}")
            false
        }
    }

    /**
     * Unlock permissions and allow user to change them again.
     */
    fun unlockPermissions(): Boolean {
        return try {
            var success = true

            // 1. Unlock all runtime permissions
            if (!unlockAllAppPermissions()) {
                Log.w(TAG, "Failed to unlock all app permissions")
                success = false
            }

            // 2. Enable access to app settings
            if (!enableConfigApps()) {
                Log.w(TAG, "Failed to enable config apps")
            }

            // 3. Allow uninstall of this app
            if (!allowSelfUninstall()) {
                Log.w(TAG, "Failed to allow self uninstall")
            }

            Log.d(TAG, "unlockPermissions completed with success=$success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error in unlockPermissions: ${e.message}")
            false
        }
    }

    // ==================== Overlay Permission Locking ====================

    /**
     * Lock the SYSTEM_ALERT_WINDOW (overlay) permission for this app.
     * This is called when permission is granted - uses MINIMAL restrictions
     * to avoid blocking accessibility settings.
     */
    fun lockOverlayPermission(): Boolean {
        return try {
            if (!isDeviceOrProfileOwner()) {
                Log.w(TAG, "lockOverlayPermission: Not device/profile owner")
                return false
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                Log.w(TAG, "lockOverlayPermission: API < 23")
                return false
            }

            val packageName = context.packageName

            // Check if overlay permission is granted
            val canDrawOverlays = Settings.canDrawOverlays(context)
            if (!canDrawOverlays) {
                Log.w(TAG, "lockOverlayPermission: Overlay permission not granted yet")
                return false
            }

            var success = true

            // Strategy 1: Try setPermissionGrantState (may not work for special permissions)
            try {
                val permResult = dpm.setPermissionGrantState(
                    adminComponent,
                    packageName,
                    android.Manifest.permission.SYSTEM_ALERT_WINDOW,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
                Log.d(TAG, "lockOverlayPermission: setPermissionGrantState result=$permResult")
            } catch (e: Exception) {
                Log.w(TAG, "setPermissionGrantState for SYSTEM_ALERT_WINDOW failed: ${e.message}")
            }

            // Strategy 2: Intercept the overlay permission settings intent
            try {
                val filter = IntentFilter(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                filter.addDataScheme("package")
                filter.addDataSchemeSpecificPart(packageName, android.os.PatternMatcher.PATTERN_LITERAL)

                dpm.addPersistentPreferredActivity(
                    adminComponent,
                    filter,
                    ComponentName(packageName, "$packageName.MainActivity")
                )
                Log.d(TAG, "lockOverlayPermission: Added persistent preferred activity for overlay settings")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add persistent preferred activity: ${e.message}")
            }

            // Strategy 3: Block uninstall of this app
            try {
                if (isDeviceOwner()) {
                    dpm.setUninstallBlocked(adminComponent, packageName, true)
                    Log.d(TAG, "lockOverlayPermission: Set uninstall blocked")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set uninstall blocked: ${e.message}")
            }

            Log.d(TAG, "lockOverlayPermission: completed (minimal restrictions), success=$success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error locking overlay permission: ${e.message}")
            false
        }
    }

    /**
     * Apply aggressive restrictions when overlay is SHOWN (device is locked).
     * This blocks access to settings, apps control, etc.
     * Should be called when showing the lock overlay.
     *
     * Implements the typical kiosk app solution:
     * 1. Make app Device Owner (already done via ADB)
     * 2. Enable Lock Task Mode
     * 3. Block Settings app
     * 4. Run overlay service
     * Then the user cannot access settings to disable overlay.
     */

    /**
     * Temporarily allow WiFi settings access while keeping device locked.
     * This unhides the settings app and adds it to lock task packages.
     * Used when user clicks WiFi button on lock screen.
     */
    fun temporarilyAllowWifiSettings(): Boolean {
        return try {
            if (!isDeviceOwner()) {
                Log.w(TAG, "temporarilyAllowWifiSettings: Not device owner")
                return false
            }

            // Unhide the Settings app to allow WiFi settings
            try {
                dpm.setApplicationHidden(adminComponent, "com.android.settings", false)
                Log.d(TAG, "temporarilyAllowWifiSettings: Settings app unhidden")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unhide Settings app: ${e.message}")
            }

            // Also add settings to lock task packages so it can be opened
            try {
                val currentPackages = listOf(context.packageName, "com.android.settings")
                dpm.setLockTaskPackages(adminComponent, currentPackages.toTypedArray())
                Log.d(TAG, "temporarilyAllowWifiSettings: Added settings to lock task packages")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add settings to lock task packages: ${e.message}")
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error in temporarilyAllowWifiSettings: ${e.message}")
            false
        }
    }

    /**
     * Re-block WiFi settings access after user returns from WiFi settings.
     * This re-hides the settings app and removes it from lock task packages.
     */
    fun reblockWifiSettings(): Boolean {
        return try {
            if (!isDeviceOwner()) {
                Log.w(TAG, "reblockWifiSettings: Not device owner")
                return false
            }

            // Hide the Settings app again
            try {
                dpm.setApplicationHidden(adminComponent, "com.android.settings", true)
                Log.d(TAG, "reblockWifiSettings: Settings app hidden")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to hide Settings app: ${e.message}")
            }

            // Remove settings from lock task packages
            try {
                dpm.setLockTaskPackages(adminComponent, arrayOf(context.packageName))
                Log.d(TAG, "reblockWifiSettings: Removed settings from lock task packages")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove settings from lock task packages: ${e.message}")
            }

            true
        } catch (e: Exception) {
            Log.e(TAG, "Error in reblockWifiSettings: ${e.message}")
            false
        }
    }

    fun lockSettingsWhenOverlayShown(): Boolean {
        return try {
            if (!isDeviceOwner()) {
                Log.w(TAG, "lockSettingsWhenOverlayShown: Not device owner")
                return false
            }

            var success = true
            val packageName = context.packageName

            // ==================== 1. ENABLE LOCK TASK MODE ====================
            // Set this app as the only allowed app in lock task mode
            try {
                dpm.setLockTaskPackages(adminComponent, arrayOf(packageName))
                Log.d(TAG, "lockSettingsWhenOverlayShown: Set lock task packages")

                // Set lock task features - disable all system UI
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Allow nothing - no home button, no recents, no notifications
                    dpm.setLockTaskFeatures(adminComponent,
                        DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
                    Log.d(TAG, "lockSettingsWhenOverlayShown: Set lock task features to NONE")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to set lock task packages: ${e.message}")
            }

            // ==================== 2. BLOCK SETTINGS ACCESS ====================
            // Hide the Settings app completely
            try {
                dpm.setApplicationHidden(adminComponent, "com.android.settings", true)
                Log.d(TAG, "lockSettingsWhenOverlayShown: Settings app hidden")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to hide Settings app: ${e.message}")
            }

            // Also hide vendor-specific settings apps
            val settingsPackages = listOf(
                "com.android.settings",
                "com.samsung.android.app.settings", // Samsung
                "com.miui.securitycenter", // Xiaomi
                "com.coloros.safecenter", // Oppo/Realme
                "com.vivo.permissionmanager", // Vivo
                "com.oneplus.security", // OnePlus
                "com.huawei.systemmanager" // Huawei
            )
            for (pkg in settingsPackages) {
                try {
                    dpm.setApplicationHidden(adminComponent, pkg, true)
                } catch (e: Exception) {
                    // Ignore - package may not exist on this device
                }
            }

            // ==================== 3. USER RESTRICTIONS ====================
            // Block access to app control settings
            try {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_APPS_CONTROL)
                Log.d(TAG, "lockSettingsWhenOverlayShown: Added DISALLOW_APPS_CONTROL")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add DISALLOW_APPS_CONTROL: ${e.message}")
                success = false
            }

            // Block access to all app settings (API 28+)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.addUserRestriction(adminComponent, "no_config_apps")
                    Log.d(TAG, "lockSettingsWhenOverlayShown: Added no_config_apps")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add no_config_apps: ${e.message}")
            }

            // Block modifications to system settings
            try {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_CREDENTIALS)
                Log.d(TAG, "lockSettingsWhenOverlayShown: Added DISALLOW_CONFIG_CREDENTIALS")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add DISALLOW_CONFIG_CREDENTIALS: ${e.message}")
            }

            // Block debugging features (ADB access)
            try {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
                Log.d(TAG, "lockSettingsWhenOverlayShown: Added DISALLOW_DEBUGGING_FEATURES")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add DISALLOW_DEBUGGING_FEATURES: ${e.message}")
            }

            // Block safe boot (prevents user from booting in safe mode to bypass overlay)
            try {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
                Log.d(TAG, "lockSettingsWhenOverlayShown: Added DISALLOW_SAFE_BOOT")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add DISALLOW_SAFE_BOOT: ${e.message}")
            }

            // Block factory reset (prevents user from factory resetting)
            try {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                Log.d(TAG, "lockSettingsWhenOverlayShown: Added DISALLOW_FACTORY_RESET")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to add DISALLOW_FACTORY_RESET: ${e.message}")
            }

            // ==================== 4. INTERCEPT SETTINGS INTENTS ====================
            // Redirect any attempts to open overlay settings to our app
            try {
                val overlayFilter = IntentFilter(Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                overlayFilter.addDataScheme("package")
                dpm.addPersistentPreferredActivity(
                    adminComponent,
                    overlayFilter,
                    ComponentName(packageName, "$packageName.MainActivity")
                )
                Log.d(TAG, "lockSettingsWhenOverlayShown: Intercepted overlay permission intent")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to intercept overlay permission intent: ${e.message}")
            }

            // Intercept general app settings intent
            try {
                val appSettingsFilter = IntentFilter(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                appSettingsFilter.addDataScheme("package")
                dpm.addPersistentPreferredActivity(
                    adminComponent,
                    appSettingsFilter,
                    ComponentName(packageName, "$packageName.MainActivity")
                )
                Log.d(TAG, "lockSettingsWhenOverlayShown: Intercepted app details settings intent")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to intercept app details settings intent: ${e.message}")
            }

            // Intercept main settings intent
            try {
                val settingsFilter = IntentFilter(Settings.ACTION_SETTINGS)
                dpm.addPersistentPreferredActivity(
                    adminComponent,
                    settingsFilter,
                    ComponentName(packageName, "$packageName.MainActivity")
                )
                Log.d(TAG, "lockSettingsWhenOverlayShown: Intercepted main settings intent")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to intercept main settings intent: ${e.message}")
            }

            Log.d(TAG, "lockSettingsWhenOverlayShown: completed, success=$success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error in lockSettingsWhenOverlayShown: ${e.message}")
            false
        }
    }

    /**
     * Remove aggressive restrictions when overlay is HIDDEN (device is unlocked).
     * Should be called when closing the lock overlay.
     *
     * This reverses everything done by lockSettingsWhenOverlayShown:
     * 1. Clear Lock Task Mode
     * 2. Unhide Settings apps
     * 3. Clear user restrictions
     * 4. Clear intercepted intents
     */
    fun unlockSettingsWhenOverlayHidden(): Boolean {
        return try {
            if (!isDeviceOwner()) {
                Log.w(TAG, "unlockSettingsWhenOverlayHidden: Not device owner")
                return false
            }

            var success = true
            val packageName = context.packageName

            // ==================== 1. CLEAR LOCK TASK MODE ====================
            // Reset lock task features to allow normal system UI
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    // Restore all lock task features
                    dpm.setLockTaskFeatures(adminComponent,
                        DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                        DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
                        DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
                        DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or
                        DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS)
                    Log.d(TAG, "unlockSettingsWhenOverlayHidden: Restored lock task features")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to restore lock task features: ${e.message}")
            }

            // Clear lock task packages (allow all apps)
            try {
                dpm.setLockTaskPackages(adminComponent, emptyArray())
                Log.d(TAG, "unlockSettingsWhenOverlayHidden: Cleared lock task packages")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear lock task packages: ${e.message}")
            }

            // ==================== 2. UNHIDE SETTINGS APPS ====================
            // Unhide the Settings app
            try {
                dpm.setApplicationHidden(adminComponent, "com.android.settings", false)
                Log.d(TAG, "unlockSettingsWhenOverlayHidden: Settings app unhidden")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unhide Settings app: ${e.message}")
            }

            // Also unhide vendor-specific settings apps
            val settingsPackages = listOf(
                "com.android.settings",
                "com.samsung.android.app.settings", // Samsung
                "com.miui.securitycenter", // Xiaomi
                "com.coloros.safecenter", // Oppo/Realme
                "com.vivo.permissionmanager", // Vivo
                "com.oneplus.security", // OnePlus
                "com.huawei.systemmanager" // Huawei
            )
            for (pkg in settingsPackages) {
                try {
                    dpm.setApplicationHidden(adminComponent, pkg, false)
                } catch (e: Exception) {
                    // Ignore - package may not exist on this device
                }
            }

            // ==================== 3. CLEAR USER RESTRICTIONS ====================
            // Remove DISALLOW_APPS_CONTROL
            try {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_APPS_CONTROL)
                Log.d(TAG, "unlockSettingsWhenOverlayHidden: Cleared DISALLOW_APPS_CONTROL")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear DISALLOW_APPS_CONTROL: ${e.message}")
                success = false
            }

            // Remove no_config_apps (API 28+)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.clearUserRestriction(adminComponent, "no_config_apps")
                    Log.d(TAG, "unlockSettingsWhenOverlayHidden: Cleared no_config_apps")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear no_config_apps: ${e.message}")
            }

            // Remove DISALLOW_CONFIG_CREDENTIALS
            try {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_CREDENTIALS)
                Log.d(TAG, "unlockSettingsWhenOverlayHidden: Cleared DISALLOW_CONFIG_CREDENTIALS")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear DISALLOW_CONFIG_CREDENTIALS: ${e.message}")
            }

            // Remove DISALLOW_DEBUGGING_FEATURES
            try {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_DEBUGGING_FEATURES)
                Log.d(TAG, "unlockSettingsWhenOverlayHidden: Cleared DISALLOW_DEBUGGING_FEATURES")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear DISALLOW_DEBUGGING_FEATURES: ${e.message}")
            }

            // Remove DISALLOW_SAFE_BOOT
            try {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
                Log.d(TAG, "unlockSettingsWhenOverlayHidden: Cleared DISALLOW_SAFE_BOOT")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear DISALLOW_SAFE_BOOT: ${e.message}")
            }

            // Remove DISALLOW_FACTORY_RESET
            try {
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
                Log.d(TAG, "unlockSettingsWhenOverlayHidden: Cleared DISALLOW_FACTORY_RESET")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear DISALLOW_FACTORY_RESET: ${e.message}")
            }

            // ==================== 4. CLEAR INTERCEPTED INTENTS ====================
            // Clear all persistent preferred activities for this package
            try {
                dpm.clearPackagePersistentPreferredActivities(adminComponent, packageName)
                Log.d(TAG, "unlockSettingsWhenOverlayHidden: Cleared persistent preferred activities")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear persistent preferred activities: ${e.message}")
            }

            Log.d(TAG, "unlockSettingsWhenOverlayHidden: completed, success=$success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error in unlockSettingsWhenOverlayHidden: ${e.message}")
            false
        }
    }

    /**
     * Unlock the SYSTEM_ALERT_WINDOW (overlay) permission.
     * Allows user to change the permission again.
     * This clears all restrictions applied by lockOverlayPermission.
     */
    fun unlockOverlayPermission(): Boolean {
        return try {
            if (!isDeviceOrProfileOwner()) {
                Log.w(TAG, "unlockOverlayPermission: Not device/profile owner")
                return false
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                Log.w(TAG, "unlockOverlayPermission: API < 23")
                return false
            }

            val packageName = context.packageName
            var success = true

            // Clear Strategy 1: Reset permission grant state to default
            try {
                val result = dpm.setPermissionGrantState(
                    adminComponent,
                    packageName,
                    android.Manifest.permission.SYSTEM_ALERT_WINDOW,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
                )
                Log.d(TAG, "unlockOverlayPermission: setPermissionGrantState result=$result")
            } catch (e: Exception) {
                Log.w(TAG, "setPermissionGrantState reset failed: ${e.message}")
            }

            // Clear Strategy 2: Clear persistent preferred activities
            try {
                dpm.clearPackagePersistentPreferredActivities(adminComponent, packageName)
                Log.d(TAG, "unlockOverlayPermission: Cleared persistent preferred activities")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear persistent preferred activities: ${e.message}")
            }

            // Clear Strategy 3: Allow uninstall of this app
            try {
                if (isDeviceOwner()) {
                    dpm.setUninstallBlocked(adminComponent, packageName, false)
                    Log.d(TAG, "unlockOverlayPermission: Cleared uninstall blocked")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear uninstall blocked: ${e.message}")
            }

            // Also ensure settings restrictions are cleared (in case overlay was shown)
            unlockSettingsWhenOverlayHidden()

            Log.d(TAG, "unlockOverlayPermission: completed, success=$success")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error unlocking overlay permission: ${e.message}")
            false
        }
    }

    /**
     * Check if overlay permission is locked
     * Checks if any of the locking strategies are active
     */
    fun isOverlayPermissionLocked(): Boolean {
        return try {
            if (!isDeviceOrProfileOwner() || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
                return false
            }

            // Check Strategy 1: setPermissionGrantState
            val state = dpm.getPermissionGrantState(
                adminComponent,
                context.packageName,
                android.Manifest.permission.SYSTEM_ALERT_WINDOW
            )
            if (state == DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) {
                return true
            }

            // Check Strategy 3: DISALLOW_APPS_CONTROL restriction
            val bundle = dpm.getUserRestrictions(adminComponent)
            if (bundle.getBoolean(UserManager.DISALLOW_APPS_CONTROL, false)) {
                return true
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking overlay permission lock state: ${e.message}")
            false
        }
    }

    // ==================== Notification Permission Locking ====================

    /**
     * Lock the POST_NOTIFICATIONS permission for THIS APP ONLY.
     * This disables the notification toggle button in app settings so user cannot turn off notifications.
     * The permission must already be granted before calling this.
     * Works on Android 13+ (API 33+).
     * 
     * When setPermissionGrantState is called with PERMISSION_GRANT_STATE_GRANTED:
     * - The permission is locked in "granted" state
     * - The toggle button in Settings > Apps > [App] > Notifications becomes disabled/greyed out
     * - User cannot disable notifications for this specific app
     * 
     * NOTE: Some manufacturers (Realme, OPPO, OnePlus, Xiaomi) may not fully respect this API.
     * For those devices, we use additional protection mechanisms.
     */
    fun lockNotificationPermission(): Boolean {
        return try {
            if (!isDeviceOrProfileOwner()) {
                Log.w(TAG, "lockNotificationPermission: Not device/profile owner")
                return false
            }

            val packageName = context.packageName
            var overallResult = true
            
            // Detect manufacturer for special handling
            val manufacturer = Build.MANUFACTURER.lowercase()
            val brand = Build.BRAND.lowercase()
            Log.d(TAG, "lockNotificationPermission: Manufacturer=$manufacturer, Brand=$brand")
            
            val isRealmeOppo = manufacturer.contains("realme") || 
                               manufacturer.contains("oppo") || 
                               manufacturer.contains("oneplus") ||
                               brand.contains("realme") || 
                               brand.contains("oppo") ||
                               brand.contains("oneplus")
            
            val isXiaomi = manufacturer.contains("xiaomi") || 
                           manufacturer.contains("redmi") ||
                           brand.contains("xiaomi") ||
                           brand.contains("redmi") ||
                           brand.contains("poco")

            // For Android 13+ (API 33+), lock the POST_NOTIFICATIONS permission for THIS APP ONLY
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Method 1: Standard setPermissionGrantState (works on Samsung and stock Android)
                val permResult = dpm.setPermissionGrantState(
                    adminComponent,
                    packageName,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
                Log.d(TAG, "lockNotificationPermission: setPermissionGrantState result=$permResult")
                
                if (!permResult) {
                    overallResult = false
                    Log.e(TAG, "lockNotificationPermission: FAILED to lock POST_NOTIFICATIONS permission")
                } else {
                    Log.i(TAG, "✅ Notification permission LOCKED via setPermissionGrantState")
                }
                
                // For Realme/OPPO/OnePlus/Xiaomi: Apply additional protection
                if (isRealmeOppo || isXiaomi) {
                    Log.d(TAG, "lockNotificationPermission: Applying additional protection for $manufacturer")
                    
                    // Method 2: Lock ALL runtime permissions for this app to prevent any changes
                    try {
                        val permissions = context.packageManager.getPackageInfo(
                            packageName, 
                            android.content.pm.PackageManager.GET_PERMISSIONS
                        ).requestedPermissions
                        
                        permissions?.forEach { perm ->
                            try {
                                // Lock each granted permission
                                val currentState = dpm.getPermissionGrantState(adminComponent, packageName, perm)
                                if (currentState == DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT) {
                                    // Check if permission is granted
                                    val isGranted = context.checkSelfPermission(perm) == android.content.pm.PackageManager.PERMISSION_GRANTED
                                    if (isGranted) {
                                        dpm.setPermissionGrantState(
                                            adminComponent,
                                            packageName,
                                            perm,
                                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                                        )
                                    }
                                }
                            } catch (e: Exception) {
                                // Ignore individual permission errors
                            }
                        }
                        Log.d(TAG, "lockNotificationPermission: Locked all permissions for $manufacturer device")
                    } catch (e: Exception) {
                        Log.w(TAG, "lockNotificationPermission: Could not lock all permissions: ${e.message}")
                    }
                    
                    // Method 3: Use addUserRestriction to restrict app control for this device
                    // This makes the app settings less accessible
                    try {
                        dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_APPS_CONTROL)
                        Log.d(TAG, "lockNotificationPermission: DISALLOW_APPS_CONTROL restriction added")

                        // Immediately remove it but keep the app protected
                        // This sometimes triggers the system to lock app settings properly
                        Thread.sleep(100)
                        dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_APPS_CONTROL)
                        Log.d(TAG, "lockNotificationPermission: DISALLOW_APPS_CONTROL restriction cleared")
                    } catch (e: Exception) {
                        Log.w(TAG, "lockNotificationPermission: User restriction toggle failed: ${e.message}")
                    }
                    
                    // Method 4: Re-apply the notification permission lock multiple times
                    // Some OEMs need multiple calls to properly lock
                    for (i in 1..3) {
                        Thread.sleep(50)
                        dpm.setPermissionGrantState(
                            adminComponent,
                            packageName,
                            android.Manifest.permission.POST_NOTIFICATIONS,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                        )
                    }
                    Log.d(TAG, "lockNotificationPermission: Re-applied permission lock 3 times for $manufacturer")
                }
            } else {
                // For Android 12 and below, notifications are always enabled by default
                Log.d(TAG, "lockNotificationPermission: API < 33, no notification runtime permission needed")
            }

            // Additional protection: Block uninstall for this app
            try {
                blockUninstall(packageName, true)
                Log.d(TAG, "lockNotificationPermission: App uninstall blocked for $packageName")
            } catch (e: Exception) {
                Log.w(TAG, "lockNotificationPermission: Could not block uninstall: ${e.message}")
            }

            // Mark app as unsuspendable (critical app)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    val packagesArray = arrayOf(packageName)
                    dpm.setPackagesSuspended(adminComponent, packagesArray, false)
                    Log.d(TAG, "lockNotificationPermission: App marked as unsuspendable")
                }
            } catch (e: Exception) {
                Log.w(TAG, "lockNotificationPermission: Could not mark app as unsuspendable: ${e.message}")
            }
            
            // For Realme/OPPO: Also try to set this app as a "keep uninstalled" package
            // This adds additional system protection
            if (isRealmeOppo || isXiaomi) {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        // Set the app in the "always on" list if available
                        dpm.setKeepUninstalledPackages(adminComponent, listOf(packageName))
                        Log.d(TAG, "lockNotificationPermission: App added to keep uninstalled list")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "lockNotificationPermission: Could not add to keep list: ${e.message}")
                }
            }

            Log.d(TAG, "lockNotificationPermission: overall result=$overallResult for $manufacturer")

            // Additional Strategy: Intercept notification settings intent for THIS APP
            // This redirects the notification settings page to our app so user can't change settings
            try {
                val notifSettingsFilter = IntentFilter(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                notifSettingsFilter.addCategory(Intent.CATEGORY_DEFAULT)

                dpm.addPersistentPreferredActivity(
                    adminComponent,
                    notifSettingsFilter,
                    ComponentName(packageName, "$packageName.MainActivity")
                )
                Log.d(TAG, "lockNotificationPermission: Added persistent preferred activity for notification settings")
            } catch (e: Exception) {
                Log.w(TAG, "lockNotificationPermission: Could not intercept notification settings: ${e.message}")
            }

            // For Realme/OPPO/Xiaomi: Also intercept the channel settings
            if (isRealmeOppo || isXiaomi) {
                try {
                    val channelSettingsFilter = IntentFilter(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    channelSettingsFilter.addCategory(Intent.CATEGORY_DEFAULT)

                    dpm.addPersistentPreferredActivity(
                        adminComponent,
                        channelSettingsFilter,
                        ComponentName(packageName, "$packageName.MainActivity")
                    )
                    Log.d(TAG, "lockNotificationPermission: Added persistent preferred activity for channel settings")
                } catch (e: Exception) {
                    Log.w(TAG, "lockNotificationPermission: Could not intercept channel settings: ${e.message}")
                }
            }

            overallResult
        } catch (e: Exception) {
            Log.e(TAG, "Error locking notification permission: ${e.message}")
            false
        }
    }

    /**
     * Unlock the POST_NOTIFICATIONS permission for THIS APP ONLY.
     * Allows user to change the notification permission again (enables the toggle).
     */
    fun unlockNotificationPermission(): Boolean {
        return try {
            if (!isDeviceOrProfileOwner()) {
                Log.w(TAG, "unlockNotificationPermission: Not device/profile owner")
                return false
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Log.w(TAG, "unlockNotificationPermission: API < 33")
                return true
            }

            val packageName = context.packageName

            // Reset to default state (user can change) - enables the toggle button
            val result = dpm.setPermissionGrantState(
                adminComponent,
                packageName,
                android.Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
            )

            Log.d(TAG, "unlockNotificationPermission: result=$result for package=$packageName")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error unlocking notification permission: ${e.message}")
            false
        }
    }

    /**
     * Disable/Deny the POST_NOTIFICATIONS permission for THIS APP.
     * This revokes the notification permission and locks it in denied state.
     * The user will not be able to re-enable notifications from settings.
     */
    fun disableNotificationPermission(): Boolean {
        return try {
            if (!isDeviceOrProfileOwner()) {
                Log.w(TAG, "disableNotificationPermission: Not device/profile owner")
                return false
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Log.w(TAG, "disableNotificationPermission: API < 33, notifications cannot be disabled via permission")
                return false
            }

            val packageName = context.packageName

            // Set to DENIED state - this revokes and locks the permission
            val result = dpm.setPermissionGrantState(
                adminComponent,
                packageName,
                android.Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
            )

            Log.d(TAG, "disableNotificationPermission: result=$result for package=$packageName")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error disabling notification permission: ${e.message}")
            false
        }
    }

    /**
     * Check if notification permission is disabled (denied and locked)
     */
    fun isNotificationPermissionDisabled(): Boolean {
        return try {
            if (!isDeviceOrProfileOwner()) {
                return false
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return false // On older versions, notifications cannot be disabled via permission
            }

            val state = dpm.getPermissionGrantState(
                adminComponent,
                context.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS
            )

            // If state is DENIED (2), the permission is locked in denied state
            val isDisabled = state == DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
            
            Log.d(TAG, "isNotificationPermissionDisabled: state=$state, disabled=$isDisabled")
            isDisabled
        } catch (e: Exception) {
            Log.e(TAG, "Error checking notification permission disabled state: ${e.message}")
            false
        }
    }

    /**
     * Check if notification permission is locked
     */
    fun isNotificationPermissionLocked(): Boolean {
        return try {
            if (!isDeviceOrProfileOwner()) {
                return false
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return true // On older versions, notification permission is always granted
            }

            val state = dpm.getPermissionGrantState(
                adminComponent,
                context.packageName,
                android.Manifest.permission.POST_NOTIFICATIONS
            )

            // If state is GRANTED (1), the permission is locked in granted state
            val isLocked = state == DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            
            // Also check if notifications are actually enabled
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            val areNotificationsEnabled = notificationManager?.areNotificationsEnabled() ?: false
            
            Log.d(TAG, "isNotificationPermissionLocked: state=$state, locked=$isLocked, notificationsEnabled=$areNotificationsEnabled")
            
            // Return true only if both locked AND notifications are enabled
            isLocked && areNotificationsEnabled
        } catch (e: Exception) {
            Log.e(TAG, "Error checking notification permission lock state: ${e.message}")
            false
        }
    }
    
    /**
     * Force re-lock the notification permission.
     * Use this for Realme/OPPO devices where the lock might not stick.
     * This method checks if notifications are disabled and re-enables/re-locks them.
     */
    fun forceRelockNotificationPermission(): Boolean {
        return try {
            if (!isDeviceOrProfileOwner()) {
                Log.w(TAG, "forceRelockNotificationPermission: Not device/profile owner")
                return false
            }
            
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                return true
            }
            
            val packageName = context.packageName
            val manufacturer = Build.MANUFACTURER.lowercase()

            // Check current notification status
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            val areNotificationsEnabled = notificationManager?.areNotificationsEnabled() ?: false
            
            Log.d(TAG, "forceRelockNotificationPermission: notificationsEnabled=$areNotificationsEnabled, manufacturer=$manufacturer")

            // If notifications are disabled, user managed to turn them off
            // We need to re-grant and re-lock
            if (!areNotificationsEnabled) {
                Log.w(TAG, "forceRelockNotificationPermission: Notifications were disabled! Re-locking...")
            }
            
            // Force grant the permission again
            val result = dpm.setPermissionGrantState(
                adminComponent,
                packageName,
                android.Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
            )

            // Apply it multiple times for stubborn OEMs
            for (i in 1..5) {
                Thread.sleep(20)
                dpm.setPermissionGrantState(
                    adminComponent,
                    packageName,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            }
            
            Log.d(TAG, "forceRelockNotificationPermission: result=$result")
            result
        } catch (e: Exception) {
            Log.e(TAG, "Error in forceRelockNotificationPermission: ${e.message}")
            false
        }
    }

    /**
     * Ultra-aggressive notification permission lock specifically for Realme/OPPO/ColorOS devices.
     * This method uses ALL available techniques to prevent user from disabling notifications.
     * 
     * Techniques used:
     * 1. Multiple setPermissionGrantState calls with delays
     * 2. Lock all app permissions to prevent any changes
     * 3. Add/clear user restrictions to trigger system refresh
     * 4. Intercept notification settings intent
     * 5. Block access to app info page
     */
    fun lockNotificationPermissionForRealme(): Boolean {
        return try {
            if (!isDeviceOrProfileOwner()) {
                Log.w(TAG, "lockNotificationPermissionForRealme: Not device/profile owner")
                return false
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Log.w(TAG, "lockNotificationPermissionForRealme: API < 33")
                return true
            }

            val packageName = context.packageName
            val manufacturer = Build.MANUFACTURER.lowercase()
            val brand = Build.BRAND.lowercase()

            Log.d(TAG, "🔒 lockNotificationPermissionForRealme starting for $manufacturer / $brand")

            var overallSuccess = true

            // Step 1: Force grant notification permission multiple times with varying delays
            Log.d(TAG, "   Step 1: Force grant permission (10 times with delays)")
            for (i in 1..10) {
                val result = dpm.setPermissionGrantState(
                    adminComponent,
                    packageName,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
                Log.d(TAG, "      Attempt $i: $result")
                Thread.sleep(30L + (i * 10)) // Increasing delays
            }

            // Step 2: Lock ALL dangerous permissions for this app
            Log.d(TAG, "   Step 2: Lock all dangerous permissions")
            try {
                val packageInfo = context.packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.GET_PERMISSIONS
                )
                val dangerousPermissions = packageInfo.requestedPermissions?.filter {
                    try {
                        val permInfo = context.packageManager.getPermissionInfo(it, 0)
                        permInfo.protection == android.content.pm.PermissionInfo.PROTECTION_DANGEROUS
                    } catch (e: Exception) { false }
                }

                dangerousPermissions?.forEach { permission ->
                    try {
                        val isGranted = context.checkSelfPermission(permission) == 
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                        if (isGranted) {
                            dpm.setPermissionGrantState(
                                adminComponent,
                                packageName,
                                permission,
                                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                            )
                        }
                    } catch (e: Exception) {
                        // Ignore individual permission errors
                    }
                }
                Log.d(TAG, "      Locked ${dangerousPermissions?.size ?: 0} permissions")
            } catch (e: Exception) {
                Log.w(TAG, "      Error locking permissions: ${e.message}")
            }

            // Step 3: Toggle user restrictions to force system state refresh
            Log.d(TAG, "   Step 3: Toggle user restrictions")
            try {
                // Add DISALLOW_APPS_CONTROL temporarily
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_APPS_CONTROL)
                Thread.sleep(150)
                
                // Re-apply permission while restriction is active
                dpm.setPermissionGrantState(
                    adminComponent,
                    packageName,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
                Thread.sleep(100)
                
                // Clear restriction
                dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_APPS_CONTROL)
                Log.d(TAG, "      User restrictions toggled")
            } catch (e: Exception) {
                Log.w(TAG, "      User restriction toggle failed: ${e.message}")
            }

            // Step 4: Intercept notification settings intent specifically for THIS app
            Log.d(TAG, "   Step 4: Intercept notification settings intents")
            try {
                // Intercept ACTION_APP_NOTIFICATION_SETTINGS
                val notifSettingsFilter = IntentFilter(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                notifSettingsFilter.addCategory(Intent.CATEGORY_DEFAULT)

                dpm.addPersistentPreferredActivity(
                    adminComponent,
                    notifSettingsFilter,
                    ComponentName(packageName, "$packageName.MainActivity")
                )
                Log.d(TAG, "      ACTION_APP_NOTIFICATION_SETTINGS intercepted")
            } catch (e: Exception) {
                Log.w(TAG, "      Intercept notification settings failed: ${e.message}")
            }

            // Step 5: For Realme/OPPO, also intercept channel settings
            if (manufacturer.contains("realme") || manufacturer.contains("oppo") || 
                brand.contains("realme") || brand.contains("oppo")) {
                Log.d(TAG, "   Step 5: Realme/OPPO specific - intercept channel settings")
                try {
                    val channelFilter = IntentFilter(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    channelFilter.addCategory(Intent.CATEGORY_DEFAULT)

                    dpm.addPersistentPreferredActivity(
                        adminComponent,
                        channelFilter,
                        ComponentName(packageName, "$packageName.MainActivity")
                    )
                    Log.d(TAG, "      ACTION_CHANNEL_NOTIFICATION_SETTINGS intercepted")
                } catch (e: Exception) {
                    Log.w(TAG, "      Channel intercept failed: ${e.message}")
                }
            }

            // Step 6: Block uninstall to prevent removal
            Log.d(TAG, "   Step 6: Block uninstall")
            try {
                blockUninstall(packageName, true)
                Log.d(TAG, "      Uninstall blocked")
            } catch (e: Exception) {
                Log.w(TAG, "      Block uninstall failed: ${e.message}")
            }

            // Step 7: Mark app as unsuspendable
            Log.d(TAG, "   Step 7: Mark as unsuspendable")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), false)
                    Log.d(TAG, "      App marked as unsuspendable")
                }
            } catch (e: Exception) {
                Log.w(TAG, "      Unsuspendable failed: ${e.message}")
            }

            // Step 8: Final permission lock (5 more times)
            Log.d(TAG, "   Step 8: Final permission lock (5 times)")
            for (i in 1..5) {
                Thread.sleep(50)
                dpm.setPermissionGrantState(
                    adminComponent,
                    packageName,
                    android.Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                )
            }

            // Verify final state
            val finalState = dpm.getPermissionGrantState(
                adminComponent, 
                packageName,
                android.Manifest.permission.POST_NOTIFICATIONS
            )
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
            val notificationsEnabled = notificationManager?.areNotificationsEnabled() ?: false

            Log.d(TAG, "🔒 lockNotificationPermissionForRealme complete:")
            Log.d(TAG, "      Final permission state: $finalState (1=GRANTED/locked)")
            Log.d(TAG, "      Notifications enabled: $notificationsEnabled")

            overallSuccess = (finalState == DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            overallSuccess
        } catch (e: Exception) {
            Log.e(TAG, "Error in lockNotificationPermissionForRealme: ${e.message}")
            false
        }
    }

    // ==================== FRP (Factory Reset Protection) Control ====================

    /**
     * Set FRP (Factory Reset Protection) account.
     * After factory reset, the device will require this Google account to unlock.
     *
     * IMPORTANT: This is a "hidden" FRP account - it is NOT added to Settings > Accounts.
     * It is only set in the FRP policy, so the user cannot see or remove it.
     *
     * After factory reset:
     * 1. Device starts setup wizard
     * 2. Google verifies FRP
     * 3. User must sign in with the specified account
     * 4. If user doesn't know the account, device is locked
     *
     * @param accountEmail The Google account email for FRP (e.g., "ghazanfar@tech4uk.uk")
     * @return true if successful
     */
    fun setFrpAccount(accountEmail: String): Boolean {
        return try {
            if (!isDeviceOwner()) {
                Log.w(TAG, "setFrpAccount: Not device owner")
                return false
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Log.w(TAG, "setFrpAccount: Requires Android 11 (API 30) or higher")
                return false
            }

            // Create FRP policy with the specified account
            val frpAccounts = listOf(accountEmail)
            val policy = android.app.admin.FactoryResetProtectionPolicy.Builder()
                .setFactoryResetProtectionAccounts(frpAccounts)
                .setFactoryResetProtectionEnabled(true)
                .build()

            dpm.setFactoryResetProtectionPolicy(adminComponent, policy)
            Log.d(TAG, "✅ FRP account set: $accountEmail")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error setting FRP account: ${e.message}")
            false
        }
    }

    /**
     * Set multiple FRP accounts.
     * Any of these accounts can be used to unlock the device after factory reset.
     *
     * @param accountEmails List of Google account emails for FRP
     * @return true if successful
     */
    fun setFrpAccounts(accountEmails: List<String>): Boolean {
        return try {
            if (!isDeviceOwner()) {
                Log.w(TAG, "setFrpAccounts: Not device owner")
                return false
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Log.w(TAG, "setFrpAccounts: Requires Android 11 (API 30) or higher")
                return false
            }

            // Create FRP policy with the specified accounts
            val policy = android.app.admin.FactoryResetProtectionPolicy.Builder()
                .setFactoryResetProtectionAccounts(accountEmails)
                .setFactoryResetProtectionEnabled(true)
                .build()

            dpm.setFactoryResetProtectionPolicy(adminComponent, policy)
            Log.d(TAG, "✅ FRP accounts set: ${accountEmails.joinToString(", ")}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error setting FRP accounts: ${e.message}")
            false
        }
    }

    /**
     * Clear FRP policy (remove FRP protection).
     * After this, factory reset will not require any specific account.
     *
     * @return true if successful
     */
    fun clearFrpPolicy(): Boolean {
        return try {
            if (!isDeviceOwner()) {
                Log.w(TAG, "clearFrpPolicy: Not device owner")
                return false
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Log.w(TAG, "clearFrpPolicy: Requires Android 11 (API 30) or higher")
                return false
            }

            // Set policy to null to clear it
            dpm.setFactoryResetProtectionPolicy(adminComponent, null)
            Log.d(TAG, "✅ FRP policy cleared")
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing FRP policy: ${e.message}")
            false
        }
    }

    /**
     * Get current FRP accounts.
     *
     * @return List of FRP account emails, or empty list if not set
     */
    fun getFrpAccounts(): List<String> {
        return try {
            if (!isDeviceOwner()) {
                Log.w(TAG, "getFrpAccounts: Not device owner")
                return emptyList()
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Log.w(TAG, "getFrpAccounts: Requires Android 11 (API 30) or higher")
                return emptyList()
            }

            val policy = dpm.getFactoryResetProtectionPolicy(adminComponent)
            if (policy != null) {
                val accounts = policy.factoryResetProtectionAccounts
                Log.d(TAG, "FRP accounts: ${accounts.joinToString(", ")}")
                accounts
            } else {
                Log.d(TAG, "No FRP policy set")
                emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error getting FRP accounts: ${e.message}")
            emptyList()
        }
    }

    /**
     * Check if FRP is enabled (has accounts set).
     *
     * @return true if FRP is enabled with at least one account
     */
    fun isFrpEnabled(): Boolean {
        return try {
            if (!isDeviceOwner()) {
                return false
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return false
            }

            val policy = dpm.getFactoryResetProtectionPolicy(adminComponent)
            policy != null && policy.factoryResetProtectionAccounts.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "Error checking FRP status: ${e.message}")
            false
        }
    }

    /**
     * Enable FRP with a default/company account.
     * This is a convenience method that sets a single FRP account.
     *
     * The account is HIDDEN from the user - they cannot see it in Settings > Accounts.
     * After factory reset, the user must sign in with this account to unlock the device.
     *
     * @param accountEmail The Google account email for FRP
     * @return true if successful
     */
    fun enableFrpProtection(accountEmail: String): Boolean {
        Log.d(TAG, "🔒 Enabling FRP protection with account: $accountEmail")
        return setFrpAccount(accountEmail)
    }

    /**
     * Disable FRP protection (clear FRP policy).
     *
     * @return true if successful
     */
    fun disableFrpProtection(): Boolean {
        Log.d(TAG, "🔓 Disabling FRP protection")
        return clearFrpPolicy()
    }

    /**
     * Verify FRP policy is correctly set.
     * Logs the current FRP accounts and returns verification status.
     *
     * Use this to confirm policy is correctly applied after setup.
     *
     * @return Map containing verification status and accounts
     */
    fun verifyFrpPolicy(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        try {
            if (!isDeviceOwner()) {
                Log.w(TAG, "verifyFrpPolicy: Not device owner")
                result["success"] = false
                result["error"] = "Not device owner"
                return result
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                Log.w(TAG, "verifyFrpPolicy: Requires Android 11+")
                result["success"] = false
                result["error"] = "Requires Android 11 (API 30) or higher"
                return result
            }

            val policy = dpm.getFactoryResetProtectionPolicy(adminComponent)

            if (policy != null) {
                val accounts = policy.factoryResetProtectionAccounts
                Log.d(TAG, "✅ FRP Policy Verification:")
                Log.d(TAG, "   Accounts: $accounts")
                Log.d(TAG, "   Account count: ${accounts.size}")

                result["success"] = true
                result["accounts"] = accounts
                result["accountCount"] = accounts.size
                result["isEnabled"] = accounts.isNotEmpty()

                if (accounts.isNotEmpty()) {
                    Log.d(TAG, "✅ FRP policy correctly set - Only ${accounts.joinToString(", ")} can unlock after reset")
                } else {
                    Log.w(TAG, "⚠️ FRP policy exists but no accounts set")
                }
            } else {
                Log.w(TAG, "❌ No FRP policy set")
                result["success"] = true
                result["accounts"] = emptyList<String>()
                result["accountCount"] = 0
                result["isEnabled"] = false
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error verifying FRP policy: ${e.message}")
            result["success"] = false
            result["error"] = e.message ?: "Unknown error"
        }

        return result
    }

    /**
     * Check if a Google account is already logged in to the device.
     *
     * IMPORTANT: For FRP to work, the account MUST be logged in to the device
     * at least once before factory reset.
     *
     * @param accountEmail The email to check
     * @return true if account is logged in
     */
    fun isAccountLoggedIn(accountEmail: String): Boolean {
        return try {
            val accountManager = android.accounts.AccountManager.get(context)
            val accounts = accountManager.getAccountsByType("com.google")

            for (account in accounts) {
                if (account.name.equals(accountEmail, ignoreCase = true)) {
                    Log.d(TAG, "✅ Account found on device: $accountEmail")
                    return true
                }
            }

            Log.w(TAG, "❌ Account NOT found on device: $accountEmail")
            Log.w(TAG, "   Available accounts: ${accounts.map { it.name }}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error checking account: ${e.message}")
            false
        }
    }

    /**
     * Get list of all Google accounts on device.
     *
     * @return List of account emails
     */
    fun getDeviceGoogleAccounts(): List<String> {
        return try {
            val accountManager = android.accounts.AccountManager.get(context)
            val accounts = accountManager.getAccountsByType("com.google")
            accounts.map { it.name }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting accounts: ${e.message}")
            emptyList()
        }
    }

    /**
     * Complete FRP setup with account locking.
     *
     * This function implements the CORRECT deployment flow:
     * 1. Checks if company account is logged in (REQUIRED for FRP to work!)
     * 2. Sets FRP policy with company account
     * 3. Blocks account modification (prevents user from adding personal Gmail)
     * 4. Verifies the policy is correctly set
     *
     * ⚠️ IMPORTANT: Company Google account MUST BE ALREADY LOGGED IN to the device
     * before calling this function for FRP to work properly!
     *
     * Flow:
     *   Device Owner App → Company Google Account Added → FRP Policy Applied
     *   → Account Modification Blocked → Factory Reset → Setup Wizard
     *   → Only company@domain.com works
     *
     * @param accountEmail The company Google account email for FRP
     * @param requireAccountLogin If true, will fail if account is not logged in (recommended)
     * @return Map containing setup status and details
     */
    fun setupCompleteFrpProtection(accountEmail: String, requireAccountLogin: Boolean = false): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        Log.d(TAG, "🔒 Starting complete FRP protection setup...")
        Log.d(TAG, "   Account: $accountEmail")

        try {
            if (!isDeviceOwner()) {
                Log.w(TAG, "setupCompleteFrpProtection: Not device owner")
                result["success"] = false
                result["error"] = "Not device owner"
                return result
            }

            // Step 0: Check if account is logged in (CRITICAL for FRP to work!)
            val accountLoggedIn = isAccountLoggedIn(accountEmail)
            result["accountLoggedIn"] = accountLoggedIn

            if (!accountLoggedIn) {
                Log.w(TAG, "⚠️ WARNING: Account $accountEmail is NOT logged in to device!")
                Log.w(TAG, "   FRP may not work properly after factory reset!")
                Log.w(TAG, "   Please login $accountEmail in Settings > Accounts first!")

                if (requireAccountLogin) {
                    result["success"] = false
                    result["error"] = "Account $accountEmail is not logged in to device. FRP will not work! Please add account in Settings > Accounts first."
                    return result
                }
            } else {
                Log.d(TAG, "✅ Account is logged in to device")
            }

            // Step 1: Set FRP policy
            Log.d(TAG, "Step 1: Setting FRP policy...")
            val frpResult = setFrpAccount(accountEmail)
            result["frpPolicySet"] = frpResult

            if (!frpResult) {
                Log.e(TAG, "❌ Failed to set FRP policy")
                result["success"] = false
                result["error"] = "Failed to set FRP policy"
                return result
            }
            Log.d(TAG, "✅ FRP policy set")

            // Step 2: Lock accounts to prevent user from adding personal Gmail
            Log.d(TAG, "Step 2: Locking account modifications...")
            val lockResult = lockAccounts()
            result["accountsLocked"] = lockResult

            if (!lockResult) {
                Log.w(TAG, "⚠️ Failed to lock accounts (FRP still set)")
            } else {
                Log.d(TAG, "✅ Accounts locked - User cannot add/remove accounts")
            }

            // Step 3: Verify the policy
            Log.d(TAG, "Step 3: Verifying FRP policy...")
            val verification = verifyFrpPolicy()
            result["verification"] = verification

            // Determine overall success
            val isEnabled = verification["isEnabled"] as? Boolean ?: false
            result["success"] = frpResult && isEnabled
            result["account"] = accountEmail

            if (result["success"] == true) {
                Log.d(TAG, "═══════════════════════════════════════════════════")
                Log.d(TAG, "✅ COMPLETE FRP PROTECTION SETUP SUCCESSFUL")
                Log.d(TAG, "═══════════════════════════════════════════════════")
                Log.d(TAG, "   FRP Account: $accountEmail")
                Log.d(TAG, "   Accounts Locked: $lockResult")
                Log.d(TAG, "   ")
                Log.d(TAG, "   After factory reset:")
                Log.d(TAG, "   ❌ Personal Gmail add nahi ho sakta")
                Log.d(TAG, "   ❌ Existing accounts remove nahi ho sakte")
                Log.d(TAG, "   ✅ Only $accountEmail can unlock the device")
                Log.d(TAG, "═══════════════════════════════════════════════════")
            } else {
                Log.e(TAG, "❌ FRP protection setup failed")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in FRP setup: ${e.message}")
            result["success"] = false
            result["error"] = e.message ?: "Unknown error"
        }

        return result
    }

    /**
     * Disable complete FRP protection.
     *
     * This reverses setupCompleteFrpProtection:
     * 1. Clears FRP policy
     * 2. Unlocks account modification
     *
     * @return Map containing status
     */
    fun disableCompleteFrpProtection(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        Log.d(TAG, "🔓 Disabling complete FRP protection...")

        try {
            if (!isDeviceOwner()) {
                result["success"] = false
                result["error"] = "Not device owner"
                return result
            }

            // Step 1: Clear FRP policy
            val frpResult = clearFrpPolicy()
            result["frpPolicyCleared"] = frpResult

            // Step 2: Unlock accounts
            val unlockResult = unlockAccounts()
            result["accountsUnlocked"] = unlockResult

            result["success"] = frpResult

            if (result["success"] == true) {
                Log.d(TAG, "✅ Complete FRP protection disabled")
                Log.d(TAG, "   User can now add/remove accounts")
                Log.d(TAG, "   Factory reset will not require specific account")
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error disabling FRP protection: ${e.message}")
            result["success"] = false
            result["error"] = e.message ?: "Unknown error"
        }

        return result
    }

    /**
     * Get complete FRP status including account lock status and login status.
     *
     * @return Map containing all FRP-related status
     */
    fun getFrpStatus(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        try {
            result["isDeviceOwner"] = isDeviceOwner()
            result["androidVersion"] = Build.VERSION.SDK_INT
            result["supportsModernFrp"] = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

            // Get all Google accounts on device
            val deviceAccounts = getDeviceGoogleAccounts()
            result["deviceAccounts"] = deviceAccounts

            if (isDeviceOwner() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val policy = dpm.getFactoryResetProtectionPolicy(adminComponent)

                if (policy != null) {
                    val accounts = policy.factoryResetProtectionAccounts
                    result["frpEnabled"] = accounts.isNotEmpty()
                    result["frpAccounts"] = accounts

                    // Check if FRP accounts are actually logged in
                    val accountsLoggedIn = accounts.map { email ->
                        email to isAccountLoggedIn(email)
                    }.toMap()
                    result["frpAccountsLoggedIn"] = accountsLoggedIn

                    // Warning if any FRP account is not logged in
                    val allLoggedIn = accountsLoggedIn.values.all { it }
                    result["allFrpAccountsLoggedIn"] = allLoggedIn

                    if (!allLoggedIn) {
                        result["warning"] = "Some FRP accounts are NOT logged in. FRP may not work properly!"
                    }
                } else {
                    result["frpEnabled"] = false
                    result["frpAccounts"] = emptyList<String>()
                }

                result["accountsLocked"] = isAccountsLocked()
            } else {
                result["frpEnabled"] = false
                result["frpAccounts"] = emptyList<String>()
                result["accountsLocked"] = false
            }

            result["success"] = true

        } catch (e: Exception) {
            Log.e(TAG, "Error getting FRP status: ${e.message}")
            result["success"] = false
            result["error"] = e.message ?: "Unknown error"
        }

        return result
    }

    // ==================== Android Lock Screen Password Management ====================


    /**
     * Set a password reset token. Must be called once before resetPasswordWithToken can work.
     * The token needs to be set while the device has no password or when user authenticates.
     * @return true if token was set successfully
     */
    fun setResetPasswordToken(): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Not device owner, cannot set reset password token")
            return false
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.w(TAG, "resetPasswordWithToken requires Android 8.0+")
            return false
        }

        return try {
            // Generate a random token (32 bytes minimum required)
            val token = ByteArray(32)
            java.security.SecureRandom().nextBytes(token)

            val success = dpm.setResetPasswordToken(adminComponent, token)
            if (success) {
                // Store token for later use
                resetToken = token
                // Also persist to SharedPreferences
                val prefs = context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
                prefs.edit().putString(TOKEN_KEY, android.util.Base64.encodeToString(token, android.util.Base64.NO_WRAP)).apply()
                Log.d(TAG, "✅ Password reset token set successfully")
            } else {
                Log.w(TAG, "❌ Failed to set password reset token")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error setting reset password token: ${e.message}")
            false
        }
    }

    /**
     * Check if the reset password token is active
     */
    fun isResetPasswordTokenActive(): Boolean {
        if (!isDeviceOwner()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

        return try {
            dpm.isResetPasswordTokenActive(adminComponent)
        } catch (e: Exception) {
            Log.e(TAG, "Error checking reset password token: ${e.message}")
            false
        }
    }

    /**
     * Clear the reset password token
     */
    fun clearResetPasswordToken(): Boolean {
        if (!isDeviceOwner()) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

        return try {
            val success = dpm.clearResetPasswordToken(adminComponent)
            if (success) {
                resetToken = null
                val prefs = context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
                prefs.edit().remove(TOKEN_KEY).apply()
                Log.d(TAG, "✅ Password reset token cleared")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing reset password token: ${e.message}")
            false
        }
    }

    /**
     * Get the stored reset token
     */
    private fun getStoredToken(): ByteArray? {
        if (resetToken != null) return resetToken

        // Try to load from SharedPreferences
        val prefs = context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
        val tokenStr = prefs.getString(TOKEN_KEY, null) ?: return null

        return try {
            android.util.Base64.decode(tokenStr, android.util.Base64.NO_WRAP).also {
                resetToken = it
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Change the Android lock screen password/PIN
     * @param newPassword The new password/PIN. Use empty string "" to remove password.
     * @return true if password was changed successfully
     */
    fun setLockScreenPassword(newPassword: String): Boolean {
        if (!isDeviceOwner()) {
            Log.w(TAG, "Not device owner, cannot change lock screen password")
            return false
        }

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8.0+ - Use resetPasswordWithToken
                val token = getStoredToken()
                if (token == null) {
                    Log.w(TAG, "No reset token available. Call setResetPasswordToken first.")
                    // Try to set token now (may work if no password is set)
                    if (!setResetPasswordToken()) {
                        Log.e(TAG, "Failed to set reset token")
                        return false
                    }
                }

                val tokenToUse = getStoredToken() ?: return false

                if (!dpm.isResetPasswordTokenActive(adminComponent)) {
                    Log.w(TAG, "Reset token is not active. User needs to confirm credentials.")
                    return false
                }

                val success = dpm.resetPasswordWithToken(adminComponent, newPassword, tokenToUse, 0)
                if (success) {
                    Log.d(TAG, "✅ Lock screen password changed successfully")
                } else {
                    Log.w(TAG, "❌ Failed to change lock screen password")
                }
                success
            } else {
                // Android 7.x and below - Use deprecated resetPassword
                @Suppress("DEPRECATION")
                val success = dpm.resetPassword(newPassword, 0)
                if (success) {
                    Log.d(TAG, "✅ Lock screen password changed successfully (legacy)")
                } else {
                    Log.w(TAG, "❌ Failed to change lock screen password (legacy)")
                }
                success
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException changing password: ${e.message}")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Error changing lock screen password: ${e.message}")
            false
        }
    }

    /**
     * Remove the Android lock screen password/PIN (set to no password)
     * @return true if password was removed successfully
     */
    fun removeLockScreenPassword(): Boolean {
        Log.d(TAG, "Attempting to remove lock screen password...")
        return setLockScreenPassword("")
    }

    /**
     * Set minimum password quality requirement
     * PASSWORD_QUALITY_UNSPECIFIED = 0
     * PASSWORD_QUALITY_BIOMETRIC_WEAK = 32768
     * PASSWORD_QUALITY_SOMETHING = 65536
     * PASSWORD_QUALITY_NUMERIC = 131072
     * PASSWORD_QUALITY_NUMERIC_COMPLEX = 196608
     * PASSWORD_QUALITY_ALPHABETIC = 262144
     * PASSWORD_QUALITY_ALPHANUMERIC = 327680
     * PASSWORD_QUALITY_COMPLEX = 393216
     */
    fun setPasswordQuality(quality: Int): Boolean {
        if (!isDeviceOwner()) return false

        return try {
            dpm.setPasswordQuality(adminComponent, quality)
            Log.d(TAG, "Password quality set to: $quality")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error setting password quality: ${e.message}")
            false
        }
    }

    /**
     * Set password quality to allow no password
     */
    fun allowNoPassword(): Boolean {
        return setPasswordQuality(DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED)
    }

    /**
     * Get current lock screen password status
     */
    fun getLockScreenStatus(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        result["isDeviceOwner"] = isDeviceOwner()
        result["androidVersion"] = Build.VERSION.SDK_INT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            result["tokenActive"] = isResetPasswordTokenActive()
            result["hasStoredToken"] = getStoredToken() != null
            result["tokenSet"] = hasTokenBeenSet()
        } else {
            result["tokenActive"] = true // Legacy mode always "active"
            result["hasStoredToken"] = false
            result["tokenSet"] = false
            result["legacyMode"] = true
        }

        // Check if device has a password set
        result["hasPassword"] = isDeviceSecure()

        return result
    }

    /**
     * Check if the device has a secure lock screen (PIN/password/pattern)
     */
    fun isDeviceSecure(): Boolean {
        return try {
            val keyguardManager = context.getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager
            keyguardManager.isDeviceSecure
        } catch (e: Exception) {
            Log.e(TAG, "Error checking device secure: ${e.message}")
            false
        }
    }

    /**
     * Check if a token has been set (even if not active)
     */
    private fun hasTokenBeenSet(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false

        val prefs = context.getSharedPreferences(TOKEN_PREFS, Context.MODE_PRIVATE)
        return prefs.getString(TOKEN_KEY, null) != null
    }

    /**
     * Set up password reset token and return detailed status.
     * This is the main method to call to ensure token is ready.
     *
     * @return Map with status information:
     * - success: Boolean - whether token was set
     * - tokenActive: Boolean - whether token is active (can change password now)
     * - needsUserUnlock: Boolean - whether user needs to unlock to activate token
     * - message: String - human readable status
     */
    fun setupPasswordResetTokenWithStatus(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()

        if (!isDeviceOwner()) {
            result["success"] = false
            result["tokenActive"] = false
            result["needsUserUnlock"] = false
            result["message"] = "Not device owner"
            return result
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            result["success"] = true
            result["tokenActive"] = true
            result["needsUserUnlock"] = false
            result["legacyMode"] = true
            result["message"] = "Legacy mode (Android < 8.0) - can change password directly"
            return result
        }

        try {
            // Check if token is already active
            if (isResetPasswordTokenActive()) {
                result["success"] = true
                result["tokenActive"] = true
                result["needsUserUnlock"] = false
                result["message"] = "Token already active - ready to change/remove password"
                return result
            }

            // Token not active, try to set it
            val tokenSet = setResetPasswordToken()

            if (!tokenSet) {
                result["success"] = false
                result["tokenActive"] = false
                result["needsUserUnlock"] = false
                result["message"] = "Failed to set token"
                return result
            }

            // Check if it became active immediately (no password set)
            if (isResetPasswordTokenActive()) {
                result["success"] = true
                result["tokenActive"] = true
                result["needsUserUnlock"] = false
                result["message"] = "Token set and active - ready to change/remove password"
            } else {
                // Token set but not active - user needs to unlock
                result["success"] = true
                result["tokenActive"] = false
                result["needsUserUnlock"] = true
                result["hasPassword"] = isDeviceSecure()
                result["message"] = "Token set but NOT active - user must unlock device with current PIN/password once to activate"
            }

        } catch (e: Exception) {
            result["success"] = false
            result["tokenActive"] = false
            result["error"] = e.message ?: "Unknown error"
            result["message"] = "Error: ${e.message}"
        }

        return result
    }

    /**
     * ALL-IN-ONE function to set/change/remove Android lock screen password.
     * 
     * This single function handles EVERYTHING:
     * 1. Checks if device owner
     * 2. Sets up password reset token if not set
     * 3. Activates token if device has no password
     * 4. Handles user unlock requirement if password exists
     * 5. Sets password quality (NUMERIC for PIN, UNSPECIFIED for removal)
     * 6. Sets/changes/removes the password
     * 
     * @param newPassword The new PIN/password. Use empty string "" to remove password.
     * @return Map with detailed status:
     *   - success: Boolean - whether operation succeeded
     *   - tokenActive: Boolean - whether token is active
     *   - tokenSet: Boolean - whether token was set/exists
     *   - needsUserUnlock: Boolean - whether user must unlock to activate token
     *   - hasExistingPassword: Boolean - whether device had a password before
     *   - action: String - what was done (set/change/remove)
     *   - message: String - human readable status
     */
    fun changePasswordWithStatus(newPassword: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        Log.d(TAG, "🔐 changePasswordWithStatus - ALL-IN-ONE Password Manager")
        Log.d(TAG, "   New password: ${if (newPassword.isEmpty()) "(remove)" else "****"}")
        Log.d(TAG, "═══════════════════════════════════════════════════════════")

        // Step 1: Check device owner status
        if (!isDeviceOwner()) {
            Log.e(TAG, "❌ Step 1 FAILED: Not device owner")
            result["success"] = false
            result["message"] = "Not device owner - cannot manage lock screen"
            return result
        }
        Log.d(TAG, "✓ Step 1: Device owner confirmed")

        // Step 2: Check if device currently has a password
        val hasExistingPassword = isDeviceSecure()
        result["hasExistingPassword"] = hasExistingPassword
        Log.d(TAG, "✓ Step 2: Existing password check - hasPassword: $hasExistingPassword")

        // Determine action
        val action = when {
            newPassword.isEmpty() -> "remove"
            hasExistingPassword -> "change"
            else -> "set"
        }
        result["action"] = action
        Log.d(TAG, "   Action: $action password")

        // For Android 7.x and below - use legacy API
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d(TAG, "📱 Android 7.x or below - using legacy API")
            // Set appropriate password quality first
            if (newPassword.isEmpty()) {
                setPasswordQuality(DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED)
            } else {
                setPasswordQuality(DevicePolicyManager.PASSWORD_QUALITY_NUMERIC)
            }
            @Suppress("DEPRECATION")
            val success = dpm.resetPassword(newPassword, 0)
            result["success"] = success
            result["tokenActive"] = true // Legacy mode doesn't use tokens
            result["legacyMode"] = true
            result["message"] = if (success) "Password ${action}d successfully (legacy mode)" else "Failed to $action password"
            Log.d(TAG, if (success) "✅ Legacy password operation SUCCESS" else "❌ Legacy password operation FAILED")
            return result
        }

        // Android 8.0+ - use token-based API
        Log.d(TAG, "📱 Android 8.0+ - using token-based API")
        
        try {
            // Step 3: Set password quality FIRST (important!)
            Log.d(TAG, "✓ Step 3: Setting password quality...")
            if (newPassword.isEmpty()) {
                setPasswordQuality(DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED)
                Log.d(TAG, "   Quality: UNSPECIFIED (allow no password)")
            } else {
                setPasswordQuality(DevicePolicyManager.PASSWORD_QUALITY_NUMERIC)
                Log.d(TAG, "   Quality: NUMERIC (require PIN)")
            }

            // Step 4: Check/setup token
            Log.d(TAG, "✓ Step 4: Token management...")
            var tokenActive = isResetPasswordTokenActive()
            val tokenExists = hasTokenBeenSet()
            result["tokenSet"] = tokenExists
            
            Log.d(TAG, "   Token exists: $tokenExists, Token active: $tokenActive")

            if (!tokenActive) {
                Log.d(TAG, "   Token not active - attempting setup...")

                if (!hasExistingPassword) {
                    // No password = token should activate immediately
                    Log.d(TAG, "   No existing password - token should activate immediately")
                    
                    // Clear any old token first
                    if (tokenExists) {
                        Log.d(TAG, "   Clearing old token...")
                        clearResetPasswordToken()
                        Thread.sleep(100)
                    }
                    
                    // Set new token
                    Log.d(TAG, "   Setting new token...")
                    val tokenSet = setResetPasswordToken()
                    Thread.sleep(150) // Allow time for activation
                    
                    if (!tokenSet) {
                        Log.e(TAG, "❌ Failed to set token")
                        result["success"] = false
                        result["tokenActive"] = false
                        result["message"] = "Failed to set password reset token"
                        return result
                    }
                    
                    // Verify token is now active
                    tokenActive = isResetPasswordTokenActive()
                    Log.d(TAG, "   Token active after setup: $tokenActive")
                    
                    if (!tokenActive) {
                        Log.e(TAG, "❌ Token not activating even with no password")
                        result["success"] = false
                        result["tokenActive"] = false
                        result["needsUserUnlock"] = false
                        result["message"] = "Token not activating - device may need restart"
                        return result
                    }
                } else {
                    // Has existing password - need user to unlock once
                    Log.w(TAG, "⚠️ Device has password - token needs user unlock to activate")
                    
                    // Try to set token anyway (will activate after user unlocks)
                    if (!tokenExists) {
                        Log.d(TAG, "   Setting token for future activation...")
                        setResetPasswordToken()
                    }
                    
                    result["success"] = false
                    result["tokenActive"] = false
                    result["needsUserUnlock"] = true
                    result["message"] = "Cannot $action password - user must unlock device once with current PIN to activate token. Send 'set_reset_password_token' first, then ask user to unlock phone once."
                    return result
                }
            }
            
            result["tokenActive"] = true
            Log.d(TAG, "✅ Step 4 complete: Token is ACTIVE")

            // Step 5: Get the stored token
            Log.d(TAG, "✓ Step 5: Retrieving stored token...")
            val token = getStoredToken()
            if (token == null) {
                Log.e(TAG, "❌ Token not found in storage")
                result["success"] = false
                result["message"] = "Token not found in storage - try sending 'set_reset_password_token' command"
                return result
            }
            Log.d(TAG, "   Token retrieved successfully")

            // Step 6: Apply the password change
            Log.d(TAG, "✓ Step 6: Applying password ${action}...")
            val success = dpm.resetPasswordWithToken(adminComponent, newPassword, token, 0)
            
            result["success"] = success
            if (success) {
                // Step 7: Re-enable all keyguard features (fingerprint, face unlock, etc.)
                // This ensures biometrics are NOT blocked after setting the password
                if (newPassword.isNotEmpty()) {
                    Log.d(TAG, "✓ Step 7: Re-enabling all keyguard features (biometrics)...")
                    try {
                        dpm.setKeyguardDisabledFeatures(adminComponent, DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE)
                        Log.d(TAG, "   ✓ All keyguard features enabled (fingerprint, face unlock allowed)")
                    } catch (e: Exception) {
                        Log.w(TAG, "   Could not re-enable keyguard features: ${e.message}")
                    }
                }
                
                Log.d(TAG, "═══════════════════════════════════════════════════════════")
                Log.d(TAG, "✅ SUCCESS! Password ${action.uppercase()}D")
                if (newPassword.isNotEmpty()) {
                    Log.d(TAG, "   New PIN is now active on Android lock screen")
                    Log.d(TAG, "   Biometrics (fingerprint, face) remain enabled")
                } else {
                    Log.d(TAG, "   Lock screen password removed - device unlocks without PIN")
                }
                Log.d(TAG, "═══════════════════════════════════════════════════════════")
                result["message"] = when (action) {
                    "set" -> "PIN set successfully! Android lock screen now requires this PIN. Biometrics enabled."
                    "change" -> "PIN changed successfully! New PIN is now active. Biometrics enabled."
                    "remove" -> "Password removed! Device now unlocks without PIN."
                    else -> "Password operation completed successfully."
                }
            } else {
                Log.e(TAG, "❌ resetPasswordWithToken returned false")
                result["message"] = "Failed to $action password - system rejected the change"
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception in changePasswordWithStatus: ${e.message}", e)
            result["success"] = false
            result["error"] = e.message ?: "Unknown error"
            result["message"] = "Error: ${e.message}"
        }

        return result
    }

    /**
     * Force remove ALL device unlock methods - PIN, password, pattern, fingerprint, face unlock.
     * 
     * Strategy: 
     * 1. Check if token is already active - if yes, USE IT (don't clear!)
     * 2. If token not active, try to set one
     * 3. Disable biometrics (fingerprint, face, etc.)
     * 4. Set password quality to UNSPECIFIED
     * 5. Remove the password using the token
     * 6. Disable keyguard if possible
     * 7. KEEP the token for future commands (change_password, etc.)
     * 
     * @return Map with status and which method succeeded
     */
    fun forceRemovePassword(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        Log.d(TAG, "🔓 forceRemovePassword - Remove ALL unlock methods")
        Log.d(TAG, "═══════════════════════════════════════════════════════════")

        if (!isDeviceOwner()) {
            Log.e(TAG, "❌ Not device owner")
            result["success"] = false
            result["message"] = "Not device owner"
            return result
        }

        try {
            var passwordRemoved = false
            var keyguardDisabled = false
            
            // Step 1: Check if token is already active - DON'T CLEAR IT if active!
            Log.d(TAG, "Step 1: Checking token status...")
            var tokenActive = isResetPasswordTokenActive()
            Log.d(TAG, "   Token active: $tokenActive")
            
            // Only set new token if not active
            if (!tokenActive) {
                Log.d(TAG, "   Token not active, setting new token...")
                setResetPasswordToken()
                Thread.sleep(200)
                tokenActive = isResetPasswordTokenActive()
                Log.d(TAG, "   Token active after setup: $tokenActive")
            } else {
                Log.d(TAG, "   ✓ Using existing active token")
            }
            
            // Step 2: Set password quality to UNSPECIFIED (allow no password)
            Log.d(TAG, "Step 2: Setting password quality to UNSPECIFIED...")
            dpm.setPasswordQuality(adminComponent, DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED)
            dpm.setPasswordMinimumLength(adminComponent, 0)
            
            // Step 3: Disable ALL keyguard features (fingerprint, face, etc.)
            Log.d(TAG, "Step 3: Disabling biometrics (fingerprint, face, etc.)...")
            try {
                val KEYGUARD_DISABLE_FEATURES_ALL = 0x7FFFFFFF
                dpm.setKeyguardDisabledFeatures(adminComponent, KEYGUARD_DISABLE_FEATURES_ALL)
                Log.d(TAG, "   ✓ Biometrics disabled")
            } catch (e: Exception) {
                Log.w(TAG, "   Could not disable biometrics: ${e.message}")
            }
            
            // Step 4: Remove password using token or legacy API
            Log.d(TAG, "Step 4: Removing password...")
            
            // Method A: Use token if active
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && tokenActive) {
                val token = getStoredToken()
                if (token != null) {
                    Log.d(TAG, "   Trying to remove password with active token...")
                    try {
                        passwordRemoved = dpm.resetPasswordWithToken(adminComponent, "", token, 0)
                        if (passwordRemoved) {
                            Log.d(TAG, "   ✓ Password REMOVED with token!")
                        } else {
                            Log.w(TAG, "   resetPasswordWithToken returned false")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "   Token method failed: ${e.message}")
                    }
                }
            }
            
            // Method B: Try legacy resetPassword
            if (!passwordRemoved) {
                Log.d(TAG, "   Trying legacy resetPassword('')...")
                try {
                    @Suppress("DEPRECATION")
                    passwordRemoved = dpm.resetPassword("", 0)
                    if (passwordRemoved) {
                        Log.d(TAG, "   ✓ Password REMOVED with legacy API!")
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "   Legacy method failed: ${e.message}")
                }
            }
            
            // Step 5: Try to disable keyguard completely (no lock screen)
            Log.d(TAG, "Step 5: Attempting to disable keyguard...")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    dpm.setKeyguardDisabled(adminComponent, true)
                    keyguardDisabled = true
                    Log.d(TAG, "   ✓ Keyguard DISABLED - no lock screen!")
                } catch (e: Exception) {
                    Log.w(TAG, "   Could not disable keyguard: ${e.message}")
                }
            }
            
            // Final check
            Thread.sleep(200)
            val stillSecure = isDeviceSecure()
            
            Log.d(TAG, "")
            Log.d(TAG, "═══════════════════════════════════════════════════════════")
            Log.d(TAG, "📊 FINAL STATUS:")
            Log.d(TAG, "   - Token active: $tokenActive")
            Log.d(TAG, "   - Password removed: $passwordRemoved")
            Log.d(TAG, "   - Keyguard disabled: $keyguardDisabled")
            Log.d(TAG, "   - Device still secure: $stillSecure")
            Log.d(TAG, "═══════════════════════════════════════════════════════════")
            
            if (passwordRemoved || keyguardDisabled || !stillSecure) {
                Log.d(TAG, "✅ SUCCESS! Device should unlock without authentication")
                
                result["success"] = true
                result["tokenActive"] = tokenActive
                result["passwordRemoved"] = passwordRemoved
                result["keyguardDisabled"] = keyguardDisabled
                result["method"] = when {
                    passwordRemoved && keyguardDisabled -> "password_and_keyguard"
                    passwordRemoved -> "password_removed"
                    keyguardDisabled -> "keyguard_disabled"
                    else -> "security_cleared"
                }
                result["message"] = "All unlock methods removed! Device unlocks without PIN/password/fingerprint."
                
                // NOTE: We keep the token active for future commands (change_password, etc.)
            } else {
                Log.e(TAG, "❌ Could not remove password")
                
                if (!tokenActive) {
                    Log.e(TAG, "   Token is NOT active - user must unlock device once")
                    result["success"] = false
                    result["tokenActive"] = false
                    result["needsUserUnlock"] = true
                    result["message"] = "Token not active. User must unlock device with current PIN once, then send 'remove_password' again."
                } else {
                    Log.e(TAG, "   Token is active but password removal failed")
                    result["success"] = false
                    result["tokenActive"] = true
                    result["message"] = "Password removal failed. The device may have restrictions preventing password removal."
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception in forceRemovePassword: ${e.message}", e)
            result["success"] = false
            result["error"] = e.message ?: "Unknown error"
            result["message"] = "Error: ${e.message}"
        }

        return result
    }

    /**
     * Change password with forced token reset.
     * 
     * Strategy:
     * 1. Delete existing token if any exists
     * 2. Set a new token
     * 3. Set the new password
     * 
     * This ensures a fresh token is used for the password change.
     * 
     * @param newPassword The new password/PIN to set
     * @return Map with status and details
     */
    fun changePasswordWithForcedTokenReset(newPassword: String): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        Log.d(TAG, "🔐 changePasswordWithForcedTokenReset")
        Log.d(TAG, "   New PIN: ${"*".repeat(newPassword.length.coerceAtMost(8))}")
        Log.d(TAG, "═══════════════════════════════════════════════════════════")

        if (!isDeviceOwner()) {
            Log.e(TAG, "❌ Not device owner")
            result["success"] = false
            result["message"] = "Not device owner - cannot manage lock screen"
            return result
        }

        // For Android 7.x and below - use legacy API
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d(TAG, "📱 Android 7.x or below - using legacy API")
            setPasswordQuality(DevicePolicyManager.PASSWORD_QUALITY_NUMERIC)
            @Suppress("DEPRECATION")
            val success = dpm.resetPassword(newPassword, 0)
            
            // Re-enable all keyguard features (biometrics)
            if (success) {
                try {
                    dpm.setKeyguardDisabledFeatures(adminComponent, DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE)
                    Log.d(TAG, "   ✓ All keyguard features enabled")
                } catch (e: Exception) {
                    Log.w(TAG, "   Could not re-enable keyguard features: ${e.message}")
                }
            }
            
            result["success"] = success
            result["tokenActive"] = true
            result["legacyMode"] = true
            result["message"] = if (success) "Password set successfully (legacy mode). Biometrics enabled." else "Failed to set password"
            return result
        }

        try {
            // Step 1: Clear existing token first
            Log.d(TAG, "Step 1: Clearing existing token if any...")
            val hadToken = hasTokenBeenSet()
            if (hadToken) {
                clearResetPasswordToken()
                Thread.sleep(100)
                Log.d(TAG, "   ✓ Existing token cleared")
            } else {
                Log.d(TAG, "   No existing token to clear")
            }

            // Step 2: Set password quality for numeric PIN
            Log.d(TAG, "Step 2: Setting password quality to NUMERIC...")
            setPasswordQuality(DevicePolicyManager.PASSWORD_QUALITY_NUMERIC)

            // Step 3: Set new token
            Log.d(TAG, "Step 3: Setting new token...")
            val tokenSet = setResetPasswordToken()
            Thread.sleep(200)
            
            if (!tokenSet) {
                Log.e(TAG, "❌ Failed to set new token")
                result["success"] = false
                result["tokenActive"] = false
                result["message"] = "Failed to set password reset token"
                return result
            }

            // Step 4: Check if token is active
            val tokenActive = isResetPasswordTokenActive()
            Log.d(TAG, "Step 4: Token active: $tokenActive")
            
            if (!tokenActive) {
                // Device has a password, token needs user unlock to activate
                Log.w(TAG, "⚠️ Token not active - user must unlock device once")
                result["success"] = false
                result["tokenActive"] = false
                result["needsUserUnlock"] = true
                result["message"] = "Token not active. User must unlock device once with current PIN to activate token."
                return result
            }

            // Step 5: Get the stored token
            val token = getStoredToken()
            if (token == null) {
                Log.e(TAG, "❌ Token not found in storage")
                result["success"] = false
                result["message"] = "Token not found in storage"
                return result
            }

            // Step 6: Apply the new password
            Log.d(TAG, "Step 6: Setting new password...")
            val success = dpm.resetPasswordWithToken(adminComponent, newPassword, token, 0)
            
            result["success"] = success
            result["tokenActive"] = true
            
            if (success) {
                // Step 7: Re-enable all keyguard features (fingerprint, face unlock, etc.)
                // This ensures biometrics are NOT blocked after setting the password
                Log.d(TAG, "Step 7: Re-enabling all keyguard features (biometrics)...")
                try {
                    dpm.setKeyguardDisabledFeatures(adminComponent, DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE)
                    Log.d(TAG, "   ✓ All keyguard features enabled (fingerprint, face unlock allowed)")
                } catch (e: Exception) {
                    Log.w(TAG, "   Could not re-enable keyguard features: ${e.message}")
                }
                
                Log.d(TAG, "═══════════════════════════════════════════════════════════")
                Log.d(TAG, "✅ SUCCESS! Password CHANGED")
                Log.d(TAG, "   New PIN is now active on Android lock screen")
                Log.d(TAG, "   Biometrics (fingerprint, face) remain enabled")
                Log.d(TAG, "═══════════════════════════════════════════════════════════")
                result["message"] = "PIN changed successfully! New PIN is now active. Biometrics enabled."
            } else {
                Log.e(TAG, "❌ resetPasswordWithToken returned false")
                result["message"] = "Failed to set password - system rejected the change"
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception in changePasswordWithForcedTokenReset: ${e.message}", e)
            result["success"] = false
            result["error"] = e.message ?: "Unknown error"
            result["message"] = "Error: ${e.message}"
        }

        return result
    }

    /**
     * Remove password using the "0000 then empty" approach.
     * 
     * Strategy:
     * 1. First change the password to "0000"
     * 2. Then use "0000" password context to set no password (empty)
     * 3. Keep the token active for future commands
     * 
     * This is more reliable than directly removing the password because:
     * - Some devices don't allow direct removal of password
     * - Changing to a known password first, then removing is more compatible
     * 
     * @return Map with status and details
     */
    fun removePasswordViaTemporaryPin(): Map<String, Any> {
        val result = mutableMapOf<String, Any>()
        
        Log.d(TAG, "═══════════════════════════════════════════════════════════")
        Log.d(TAG, "🔓 removePasswordViaTemporaryPin - Remove password using 0000 approach")
        Log.d(TAG, "═══════════════════════════════════════════════════════════")

        if (!isDeviceOwner()) {
            Log.e(TAG, "❌ Not device owner")
            result["success"] = false
            result["message"] = "Not device owner - cannot manage lock screen"
            return result
        }

        // For Android 7.x and below - use legacy API
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d(TAG, "📱 Android 7.x or below - using legacy API")
            setPasswordQuality(DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED)
            @Suppress("DEPRECATION")
            val success = dpm.resetPassword("", 0)
            result["success"] = success
            result["tokenActive"] = true
            result["legacyMode"] = true
            result["message"] = if (success) "Password removed successfully (legacy mode)" else "Failed to remove password"
            return result
        }

        try {
            // Step 1: Check if token is active - use existing token, don't clear it!
            Log.d(TAG, "Step 1: Checking token status...")
            var tokenActive = isResetPasswordTokenActive()
            Log.d(TAG, "   Token active: $tokenActive")
            
            // If no token active, try to set one
            if (!tokenActive) {
                Log.d(TAG, "   Token not active, setting new token...")
                setResetPasswordToken()
                Thread.sleep(200)
                tokenActive = isResetPasswordTokenActive()
                Log.d(TAG, "   Token active after setup: $tokenActive")
                
                if (!tokenActive) {
                    Log.w(TAG, "⚠️ Token not active - user must unlock device once")
                    result["success"] = false
                    result["tokenActive"] = false
                    result["needsUserUnlock"] = true
                    result["message"] = "Token not active. User must unlock device once with current PIN to activate token."
                    return result
                }
            }

            // Step 2: Get the stored token
            val token = getStoredToken()
            if (token == null) {
                Log.e(TAG, "❌ Token not found in storage")
                result["success"] = false
                result["message"] = "Token not found in storage"
                return result
            }

            // Step 3: First change password to "0000"
            Log.d(TAG, "Step 3: Changing password to temporary PIN '0000'...")
            setPasswordQuality(DevicePolicyManager.PASSWORD_QUALITY_NUMERIC)
            val tempSuccess = dpm.resetPasswordWithToken(adminComponent, "0000", token, 0)
            
            if (!tempSuccess) {
                Log.e(TAG, "❌ Failed to set temporary password")
                result["success"] = false
                result["tokenActive"] = tokenActive
                result["message"] = "Failed to set temporary password"
                return result
            }
            Log.d(TAG, "   ✓ Temporary PIN '0000' set")
            
            // Small delay to ensure password change is processed
            Thread.sleep(150)

            // Step 4: Now remove the password completely
            Log.d(TAG, "Step 4: Removing password (setting to empty)...")
            setPasswordQuality(DevicePolicyManager.PASSWORD_QUALITY_UNSPECIFIED)
            dpm.setPasswordMinimumLength(adminComponent, 0)
            
            val removeSuccess = dpm.resetPasswordWithToken(adminComponent, "", token, 0)
            
            result["success"] = removeSuccess
            result["tokenActive"] = tokenActive
            
            if (removeSuccess) {
                // Step 5: Re-enable all keyguard features (fingerprint, face unlock, etc.)
                Log.d(TAG, "Step 5: Re-enabling all keyguard features (biometrics)...")
                try {
                    dpm.setKeyguardDisabledFeatures(adminComponent, DevicePolicyManager.KEYGUARD_DISABLE_FEATURES_NONE)
                    Log.d(TAG, "   ✓ All keyguard features enabled (fingerprint, face unlock allowed)")
                } catch (e: Exception) {
                    Log.w(TAG, "   Could not re-enable keyguard features: ${e.message}")
                }
                
                Log.d(TAG, "═══════════════════════════════════════════════════════════")
                Log.d(TAG, "✅ SUCCESS! Password REMOVED")
                Log.d(TAG, "   Device now unlocks without PIN")
                Log.d(TAG, "   Biometrics (fingerprint, face) enabled")
                Log.d(TAG, "   Token still active for future password commands")
                Log.d(TAG, "═══════════════════════════════════════════════════════════")
                result["message"] = "Password removed! Device unlocks without PIN. Token still active for future use."
            } else {
                // Even if final remove failed, we set it to 0000
                Log.w(TAG, "⚠️ Could not remove password completely, but it's set to '0000'")
                result["success"] = false
                result["message"] = "Password set to '0000' but could not remove completely"
            }

        } catch (e: Exception) {
            Log.e(TAG, "❌ Exception in removePasswordViaTemporaryPin: ${e.message}", e)
            result["success"] = false
            result["error"] = e.message ?: "Unknown error"
            result["message"] = "Error: ${e.message}"
        }

        return result
    }

    // ==================== Prepare for Uninstall ====================

    /**
     * Prepare the device for app uninstallation.
     * This will:
     * 1. Enable all disabled features (camera, location, factory reset, etc.)
     * 2. Clear all user restrictions
     * 3. Remove device admin privileges
     *
     * After this, the user can uninstall the app normally.
     */
    fun prepareForUninstall(): Boolean {
        Log.d(TAG, "🔓 prepareForUninstall: Starting cleanup...")

        try {
            if (!isDeviceOrProfileOwner()) {
                Log.w(TAG, "prepareForUninstall: Not device/profile owner, attempting basic admin removal")
                return removeDeviceAdmin()
            }

            // Step 1: Enable Camera
            try {
                if (isCameraDisabled()) {
                    setCameraDisabled(false)
                    Log.d(TAG, "✓ Camera enabled")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable camera: ${e.message}")
            }

            // Step 2: Enable Screen Capture
            try {
                if (isScreenCaptureDisabled()) {
                    setScreenCaptureDisabled(false)
                    Log.d(TAG, "✓ Screen capture enabled")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable screen capture: ${e.message}")
            }

            // Step 3: Enable Location and clear config restriction
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.setLocationEnabled(adminComponent, true)
                    clearUserRestriction(UserManager.DISALLOW_CONFIG_LOCATION)
                    clearUserRestriction(UserManager.DISALLOW_SHARE_LOCATION)
                    Log.d(TAG, "✓ Location enabled and unlocked")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable location: ${e.message}")
            }

            // Step 4: Clear all user restrictions
            val restrictionsToClear = listOf(
                UserManager.DISALLOW_FACTORY_RESET,
                UserManager.DISALLOW_SAFE_BOOT,
                UserManager.DISALLOW_USB_FILE_TRANSFER,
                UserManager.DISALLOW_INSTALL_APPS,
                UserManager.DISALLOW_UNINSTALL_APPS,
                UserManager.DISALLOW_CONFIG_WIFI,
                UserManager.DISALLOW_CONFIG_BLUETOOTH,
                UserManager.DISALLOW_ADJUST_VOLUME,
                UserManager.DISALLOW_OUTGOING_CALLS,
                UserManager.DISALLOW_SMS,
                UserManager.DISALLOW_MODIFY_ACCOUNTS,
                UserManager.DISALLOW_APPS_CONTROL,
                UserManager.DISALLOW_CONFIG_LOCATION,
                UserManager.DISALLOW_SHARE_LOCATION
            )

            for (restriction in restrictionsToClear) {
                try {
                    dpm.clearUserRestriction(adminComponent, restriction)
                    Log.d(TAG, "✓ Cleared restriction: $restriction")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear restriction $restriction: ${e.message}")
                }
            }

            // Step 5: Enable keyguard if disabled
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dpm.setKeyguardDisabled(adminComponent, false)
                    Log.d(TAG, "✓ Keyguard enabled")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable keyguard: ${e.message}")
            }

            // Step 6: Enable status bar if disabled
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    dpm.setStatusBarDisabled(adminComponent, false)
                    Log.d(TAG, "✓ Status bar enabled")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enable status bar: ${e.message}")
            }

            // Step 7: Allow self uninstall
            try {
                blockUninstall(context.packageName, false)
                Log.d(TAG, "✓ Self uninstall allowed")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to allow self uninstall: ${e.message}")
            }

            // Step 8: Disable FRP protection
            try {
                disableFrpProtection()
                Log.d(TAG, "✓ FRP protection disabled")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to disable FRP: ${e.message}")
            }

            // Step 9: Clear lock task packages (exit kiosk mode if active)
            try {
                if (isDeviceOwner()) {
                    dpm.setLockTaskPackages(adminComponent, emptyArray())
                    Log.d(TAG, "✓ Lock task packages cleared")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to clear lock task packages: ${e.message}")
            }

            // Step 10: Unlock all app permissions
            try {
                unlockAllAppPermissions()
                Log.d(TAG, "✓ All app permissions unlocked")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unlock permissions: ${e.message}")
            }

            // Step 11: Show self if hidden
            try {
                if (isSelfHidden()) {
                    showSelf()
                    Log.d(TAG, "✓ App shown in launcher")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to show self: ${e.message}")
            }

            // Step 12: Remove device owner/admin
            val removeResult = removeDeviceAdmin()
            Log.d(TAG, "Device admin removal result: $removeResult")

            Log.d(TAG, "🎉 prepareForUninstall: Cleanup completed!")
            return true

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error in prepareForUninstall: ${e.message}")
            e.printStackTrace()
            return false
        }
    }

    /**
     * Remove device admin privileges.
     * This must be called AFTER clearing all restrictions.
     */
    fun removeDeviceAdmin(): Boolean {
        return try {
            if (isDeviceOwner()) {
                // Clear device owner
                dpm.clearDeviceOwnerApp(context.packageName)
                Log.d(TAG, "✓ Device owner cleared")
                true
            } else if (isAdminActive()) {
                // Just remove admin
                dpm.removeActiveAdmin(adminComponent)
                Log.d(TAG, "✓ Device admin removed")
                true
            } else {
                Log.d(TAG, "No admin to remove")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error removing device admin: ${e.message}")
            false
        }
    }
}
