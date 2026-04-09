package com.deploy_logics.user_device_locker

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import android.widget.Toast

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "MyDeviceAdminReceiver"

        /**
         * Get the ComponentName for this DeviceAdminReceiver
         */
        fun getComponentName(context: Context): ComponentName {
            return ComponentName(context.applicationContext, MyDeviceAdminReceiver::class.java)
        }

        /**
         * Check if this app is the device owner
         */
        fun isDeviceOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isDeviceOwnerApp(context.packageName)
        }

        /**
         * Check if this app is a profile owner
         */
        fun isProfileOwner(context: Context): Boolean {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            return dpm.isProfileOwnerApp(context.packageName)
        }

        /**
         * Check if this app is either device owner or profile owner
         */
        fun isDeviceOrProfileOwner(context: Context): Boolean {
            return isDeviceOwner(context) || isProfileOwner(context)
        }
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.d(TAG, "")
        Log.d(TAG, "╔══════════════════════════════════════════════════════════╗")
        Log.d(TAG, "║               DEVICE ADMIN ENABLED!                      ║")
        Log.d(TAG, "╚══════════════════════════════════════════════════════════╝")
        Log.d(TAG, "")
        
        // IMPORTANT: Check if we're device owner and initialize policies including FRP
        // This is called when device owner is set via ADB command
        val isOwner = isDeviceOwner(context)
        Log.d(TAG, "Is Device Owner: $isOwner")
        
        if (isOwner) {
            Log.d(TAG, "✅ App is Device Owner - Initializing policies including FRP...")
            initializeDeviceOwnerPolicies(context)
        } else {
            Log.d(TAG, "⚠️ App is NOT Device Owner - FRP will not be set")
        }
        
        showToast(context, "Device Admin Enabled")
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.d(TAG, "Device Admin Disabled")
        showToast(context, "Device Admin Disabled")
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        Log.d(TAG, "Device Admin Disable Requested")
        return "Warning: Disabling device admin will remove all security policies."
    }

    override fun onPasswordChanged(context: Context, intent: Intent, userHandle: UserHandle) {
        super.onPasswordChanged(context, intent, userHandle)
        Log.d(TAG, "Password Changed")
    }

    override fun onPasswordFailed(context: Context, intent: Intent, userHandle: UserHandle) {
        super.onPasswordFailed(context, intent, userHandle)
        Log.d(TAG, "Password Failed")

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val failedAttempts = dpm.currentFailedPasswordAttempts
        Log.d(TAG, "Failed password attempts: $failedAttempts")
    }

    override fun onPasswordSucceeded(context: Context, intent: Intent, userHandle: UserHandle) {
        super.onPasswordSucceeded(context, intent, userHandle)
        Log.d(TAG, "Password Succeeded - User authenticated")

        // When user successfully authenticates, try to ensure password reset token is active
        // This is the perfect time to set/activate the token
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && isDeviceOwner(context)) {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val componentName = getComponentName(context)

            try {
                if (!dpm.isResetPasswordTokenActive(componentName)) {
                    Log.d(TAG, "Token not active, setting new token after successful auth...")
                    // The token should become active now since user just authenticated
                    val token = ByteArray(32)
                    java.security.SecureRandom().nextBytes(token)

                    val success = dpm.setResetPasswordToken(componentName, token)
                    if (success) {
                        val prefs = context.getSharedPreferences("PasswordTokenPrefs", Context.MODE_PRIVATE)
                        prefs.edit().putString("reset_token",
                            android.util.Base64.encodeToString(token, android.util.Base64.NO_WRAP)
                        ).apply()
                        Log.d(TAG, "✅ Password reset token set after successful authentication")

                        // Check if active now
                        if (dpm.isResetPasswordTokenActive(componentName)) {
                            Log.d(TAG, "✅ Token is now ACTIVE!")
                        }
                    }
                } else {
                    Log.d(TAG, "✅ Password reset token already active")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling password success: ${e.message}")
            }
        }
    }

    override fun onPasswordExpiring(context: Context, intent: Intent, userHandle: UserHandle) {
        super.onPasswordExpiring(context, intent, userHandle)
        Log.d(TAG, "Password Expiring")

        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val expirationTime = dpm.getPasswordExpiration(getComponentName(context))
        Log.d(TAG, "Password expiration time: $expirationTime")
    }

    override fun onLockTaskModeEntering(context: Context, intent: Intent, pkg: String) {
        super.onLockTaskModeEntering(context, intent, pkg)
        Log.d(TAG, "Lock Task Mode Entering: $pkg")
    }

    override fun onLockTaskModeExiting(context: Context, intent: Intent) {
        super.onLockTaskModeExiting(context, intent)
        Log.d(TAG, "Lock Task Mode Exiting")
    }

    // Called when this app receives Device Owner via transferOwnership()
    override fun onTransferOwnershipComplete(context: Context, bundle: PersistableBundle?) {
        super.onTransferOwnershipComplete(context, bundle)
        Log.d(TAG, "")
        Log.d(TAG, "╔══════════════════════════════════════════════════════════╗")
        Log.d(TAG, "║     DEVICE OWNERSHIP TRANSFER COMPLETE!                  ║")
        Log.d(TAG, "╚══════════════════════════════════════════════════════════╝")
        Log.d(TAG, "")
        showToast(context, "Device Ownership Transfer Complete!")

        // Initialize default policies after becoming device owner (includes FRP!)
        Log.d(TAG, "Calling initializeDeviceOwnerPolicies()...")
        initializeDeviceOwnerPolicies(context)

        // Start persistent FCM service for reliable message delivery
        try {
            Log.d(TAG, "📡 Starting persistent FCM service after ownership transfer")
            PersistentFCMService.start(context)
        } catch (e: Exception) {
            Log.e(TAG, "Error starting persistent FCM service: ${e.message}")
        }
    }


    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.d(TAG, "Profile Provisioning Complete")

        // Enable the profile
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = getComponentName(context)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            dpm.setProfileName(componentName, "Device Locker Profile")
        }

        // Set profile enabled
        dpm.setProfileEnabled(componentName)
    }

    override fun onNetworkLogsAvailable(context: Context, intent: Intent, batchToken: Long, networkLogsCount: Int) {
        super.onNetworkLogsAvailable(context, intent, batchToken, networkLogsCount)
        Log.d(TAG, "Network Logs Available: batchToken=$batchToken, count=$networkLogsCount")
    }

    override fun onSecurityLogsAvailable(context: Context, intent: Intent) {
        super.onSecurityLogsAvailable(context, intent)
        Log.d(TAG, "Security Logs Available")
    }

    override fun onSystemUpdatePending(context: Context, intent: Intent, receivedTime: Long) {
        super.onSystemUpdatePending(context, intent, receivedTime)
        Log.d(TAG, "System Update Pending: receivedTime=$receivedTime")
    }

    override fun onUserAdded(context: Context, intent: Intent, addedUser: UserHandle) {
        super.onUserAdded(context, intent, addedUser)
        Log.d(TAG, "User Added: $addedUser")
    }

    override fun onUserRemoved(context: Context, intent: Intent, removedUser: UserHandle) {
        super.onUserRemoved(context, intent, removedUser)
        Log.d(TAG, "User Removed: $removedUser")
    }

    override fun onUserStarted(context: Context, intent: Intent, startedUser: UserHandle) {
        super.onUserStarted(context, intent, startedUser)
        Log.d(TAG, "User Started: $startedUser")
    }

    override fun onUserStopped(context: Context, intent: Intent, stoppedUser: UserHandle) {
        super.onUserStopped(context, intent, stoppedUser)
        Log.d(TAG, "User Stopped: $stoppedUser")
    }

    override fun onUserSwitched(context: Context, intent: Intent, switchedUser: UserHandle) {
        super.onUserSwitched(context, intent, switchedUser)
        Log.d(TAG, "User Switched: $switchedUser")
    }

    /**
     * Initialize default policies when becoming device owner
     * NOTE: Factory reset is NOT disabled here - it will be disabled only after device registration
     */
    private fun initializeDeviceOwnerPolicies(context: Context) {
        Log.d(TAG, "========== INITIALIZING DEVICE OWNER POLICIES ==========")
        
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val componentName = getComponentName(context)
        
        // Verify we are actually device owner
        val isOwner = dpm.isDeviceOwnerApp(context.packageName)
        Log.d(TAG, "Is Device Owner: $isOwner")
        
        if (!isOwner) {
            Log.e(TAG, "❌ NOT DEVICE OWNER - Cannot set policies!")
            return
        }

        try {
            // Set this package for lock task mode
            dpm.setLockTaskPackages(componentName, arrayOf(context.packageName))
            Log.d(TAG, "✅ Lock task packages set")

            // NOTE: Factory reset is NOT disabled by default anymore
            // It will be disabled only after successful device registration via API
            // This allows the device to be reset if registration fails or before registration
            Log.d(TAG, "Factory reset remains enabled until device registration")

            // Optional: Set affiliation IDs for managed device
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                dpm.setAffiliationIds(componentName, setOf("device-locker-affiliation"))
                Log.d(TAG, "✅ Affiliation IDs set")
            }

            // IMPORTANT: Set Factory Reset Protection (FRP) account early!
            // This ensures that after a factory reset, only the company account can unlock the device.
            Log.d(TAG, "Calling setupFrpAccount()...")
            setupFrpAccount(context, dpm, componentName)

            // IMPORTANT: Set password reset token early!
            // This should be done when there's no lock screen password yet.
            // If set now, the token will be immediately active, allowing
            // remote password change/removal without user interaction.
            setupPasswordResetToken(context, dpm, componentName)
            
            Log.d(TAG, "========== DEVICE OWNER POLICIES INITIALIZED ==========")

        } catch (e: Exception) {
            Log.e(TAG, "❌ Error initializing device owner policies: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Setup Factory Reset Protection (FRP) account for Android 11+.
     * This ensures that after a factory reset, only the specified account can unlock the device.
     *
     * Default FRP account: ghazanfar@tech4uk.uk
     */
    private fun setupFrpAccount(context: Context, dpm: DevicePolicyManager, componentName: ComponentName) {
        Log.d(TAG, "========== FRP SETUP STARTED ==========")
        Log.d(TAG, "Android SDK Version: ${Build.VERSION.SDK_INT}")
        Log.d(TAG, "Required SDK Version: ${Build.VERSION_CODES.R} (Android 11)")
        
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            Log.w(TAG, "❌ FRP NOT AVAILABLE - Android version is below 11 (API 30)")
            Log.w(TAG, "❌ Current Android version: ${Build.VERSION.SDK_INT}")
            Log.w(TAG, "❌ FRP will use default Google account behavior")
            return
        }

        try {
            // Default company FRP account - this can be changed via app settings or FCM
            val defaultFrpEmail = "ghazanfar@tech4uk.uk"
            Log.d(TAG, "FRP Email to set: $defaultFrpEmail")
            
            // Check if FRP is already configured
            val existingPolicy = dpm.getFactoryResetProtectionPolicy(componentName)
            if (existingPolicy != null) {
                val existingAccounts = existingPolicy.factoryResetProtectionAccounts
                Log.d(TAG, "Existing FRP Policy found: ${existingAccounts}")
                if (existingAccounts.isNotEmpty()) {
                    Log.d(TAG, "✅ FRP already configured with accounts: $existingAccounts")
                    Log.d(TAG, "========== FRP SETUP COMPLETE (ALREADY SET) ==========")
                    return
                }
            } else {
                Log.d(TAG, "No existing FRP policy found - will create new one")
            }

            // Set FRP policy with the default company account
            Log.d(TAG, "Creating new FRP policy...")
            val policy = android.app.admin.FactoryResetProtectionPolicy.Builder()
                .setFactoryResetProtectionAccounts(listOf(defaultFrpEmail))
                .setFactoryResetProtectionEnabled(true)
                .build()

            Log.d(TAG, "Setting FRP policy...")
            dpm.setFactoryResetProtectionPolicy(componentName, policy)
            
            // VERIFY the policy was set correctly
            val verifyPolicy = dpm.getFactoryResetProtectionPolicy(componentName)
            if (verifyPolicy != null && verifyPolicy.factoryResetProtectionAccounts.contains(defaultFrpEmail)) {
                Log.d(TAG, "✅✅✅ FRP SUCCESSFULLY SET AND VERIFIED ✅✅✅")
                Log.d(TAG, "✅ FRP account: $defaultFrpEmail")
                Log.d(TAG, "✅ FRP accounts list: ${verifyPolicy.factoryResetProtectionAccounts}")
                Log.d(TAG, "📱 After factory reset, device can ONLY be unlocked with: $defaultFrpEmail")
            } else {
                Log.e(TAG, "❌❌❌ FRP VERIFICATION FAILED ❌❌❌")
                Log.e(TAG, "❌ Policy after set: $verifyPolicy")
                if (verifyPolicy != null) {
                    Log.e(TAG, "❌ Accounts in policy: ${verifyPolicy.factoryResetProtectionAccounts}")
                }
            }
            
            Log.d(TAG, "========== FRP SETUP COMPLETE ==========")

        } catch (e: Exception) {
            Log.e(TAG, "❌❌❌ FRP SETUP EXCEPTION ❌❌❌")
            Log.e(TAG, "❌ Error setting FRP account: ${e.message}")
            Log.e(TAG, "❌ Exception type: ${e.javaClass.simpleName}")
            e.printStackTrace()
        }
    }

    /**
     * Setup password reset token for Android 8.0+
     * This allows remote password change/removal via FCM notifications.
     *
     * IMPORTANT: Token must be set when:
     * 1. Device has no lock screen password (token activates immediately), OR
     * 2. User has just authenticated with current credentials (token activates after)
     *
     * If set when there's already a password, the token won't be active until user unlocks.
     */
    private fun setupPasswordResetToken(context: Context, dpm: DevicePolicyManager, componentName: ComponentName) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Log.d(TAG, "Password reset token not needed for Android < 8.0 (using legacy API)")
            return
        }

        try {
            // Check if token is already active
            if (dpm.isResetPasswordTokenActive(componentName)) {
                Log.d(TAG, "✅ Password reset token is already active")
                return
            }

            // Generate and set a new token
            val token = ByteArray(32)
            java.security.SecureRandom().nextBytes(token)

            val success = dpm.setResetPasswordToken(componentName, token)
            if (success) {
                // Store token in SharedPreferences for later use
                val prefs = context.getSharedPreferences("PasswordTokenPrefs", Context.MODE_PRIVATE)
                prefs.edit().putString("reset_token",
                    android.util.Base64.encodeToString(token, android.util.Base64.NO_WRAP)
                ).apply()

                Log.d(TAG, "✅ Password reset token set successfully")

                // Check if it's immediately active (no password set)
                if (dpm.isResetPasswordTokenActive(componentName)) {
                    Log.d(TAG, "✅ Token is ACTIVE - can change/remove password immediately")
                } else {
                    Log.d(TAG, "⚠️ Token set but NOT active - user needs to unlock once to activate")
                }
            } else {
                Log.w(TAG, "❌ Failed to set password reset token")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error setting password reset token: ${e.message}")
        }
    }

    private fun showToast(context: Context, message: String) {
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
