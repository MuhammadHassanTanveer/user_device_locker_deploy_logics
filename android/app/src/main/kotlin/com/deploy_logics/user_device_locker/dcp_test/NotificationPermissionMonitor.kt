package com.deploy_logics.user_device_locker

import android.app.NotificationManager
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log

/**
 * Service that monitors notification permission status and automatically re-enables it
 * if the user somehow manages to disable notifications on devices like Realme/OPPO/Xiaomi.
 *
 * This service provides additional protection beyond setPermissionGrantState by:
 * 1. Periodically checking if notifications are enabled
 * 2. Re-granting the permission if user somehow disabled it
 * 3. Running as a foreground service to prevent being killed
 * 4. Checking more frequently on Realme/OPPO/Xiaomi devices
 */
class NotificationPermissionMonitor : Service() {

    companion object {
        private const val TAG = "NotifPermMonitor"
        private const val NOTIFICATION_ID = 99999

        // Check interval varies by manufacturer
        private const val CHECK_INTERVAL_DEFAULT_MS = 5000L // 5 seconds for normal devices
        private const val CHECK_INTERVAL_REALME_MS = 2000L  // 2 seconds for Realme/OPPO/Xiaomi

        @Volatile
        private var isRunning = false

        /**
         * Start the notification permission monitor service
         */
        fun start(context: Context) {
            if (isRunning) {
                Log.d(TAG, "Monitor already running")
                return
            }

            try {
                val intent = Intent(context, NotificationPermissionMonitor::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "✅ NotificationPermissionMonitor started")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting monitor: ${e.message}")
            }
        }

        /**
         * Stop the notification permission monitor service
         */
        fun stop(context: Context) {
            try {
                val intent = Intent(context, NotificationPermissionMonitor::class.java)
                context.stopService(intent)
                isRunning = false
                Log.d(TAG, "✅ NotificationPermissionMonitor stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping monitor: ${e.message}")
            }
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var checkRunnable: Runnable? = null
    private lateinit var dpm: DevicePolicyManager
    private lateinit var adminComponent: ComponentName
    private lateinit var notificationManager: NotificationManager
    private var isRealmeOppoDevice = false
    private var checkIntervalMs = CHECK_INTERVAL_DEFAULT_MS

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        adminComponent = ComponentName(this, MyDeviceAdminReceiver::class.java)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        // Detect manufacturer for special handling
        val manufacturer = Build.MANUFACTURER.lowercase()
        val brand = Build.BRAND.lowercase()
        isRealmeOppoDevice = manufacturer.contains("realme") ||
                            manufacturer.contains("oppo") ||
                            manufacturer.contains("oneplus") ||
                            manufacturer.contains("xiaomi") ||
                            manufacturer.contains("redmi") ||
                            brand.contains("realme") ||
                            brand.contains("oppo") ||
                            brand.contains("oneplus") ||
                            brand.contains("xiaomi") ||
                            brand.contains("redmi") ||
                            brand.contains("poco")

        // Use shorter check interval for Realme/OPPO/Xiaomi devices
        checkIntervalMs = if (isRealmeOppoDevice) CHECK_INTERVAL_REALME_MS else CHECK_INTERVAL_DEFAULT_MS
        Log.d(TAG, "Device: $manufacturer / $brand, isRealmeOppoDevice: $isRealmeOppoDevice, checkInterval: ${checkIntervalMs}ms")

        isRunning = true

        // Start as foreground service (using existing notification channel)
        startForegroundNotification()

        // Start periodic checking
        startPeriodicCheck()

        // Register for app settings changes
        registerAppSettingsReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")

        // Ensure we're running
        if (!isRunning) {
            isRunning = true
            startPeriodicCheck()
        }

        // Return STICKY so service restarts if killed
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")

        isRunning = false
        checkRunnable?.let { handler.removeCallbacks(it) }

        try {
            unregisterReceiver(appSettingsReceiver)
        } catch (e: Exception) {
            // Already unregistered
        }
    }

    private fun startForegroundNotification() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Use existing notification channel
                val builder = android.app.Notification.Builder(this, "high_importance_channel")
                    .setContentTitle("Device Protection")
                    .setContentText("Monitoring device security")
                    .setSmallIcon(android.R.drawable.ic_lock_lock)
                    .setOngoing(true)
                    .setAutoCancel(false)

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    builder.setForegroundServiceBehavior(android.app.Notification.FOREGROUND_SERVICE_IMMEDIATE)
                }

                startForeground(NOTIFICATION_ID, builder.build())
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error starting foreground: ${e.message}")
        }
    }

    private fun startPeriodicCheck() {
        checkRunnable = object : Runnable {
            override fun run() {
                if (isRunning) {
                    checkAndRestoreNotificationPermission()
                    handler.postDelayed(this, checkIntervalMs)
                }
            }
        }
        handler.post(checkRunnable!!)
    }

    /**
     * Check if notification permission is still granted and locked.
     * If user somehow disabled it, re-grant and re-lock.
     * Enhanced for Realme/OPPO/Xiaomi devices.
     */
    private fun checkAndRestoreNotificationPermission() {
        try {
            // Check if we're device owner
            if (!dpm.isDeviceOwnerApp(packageName)) {
                return
            }

            // Check if notifications are enabled
            val areNotificationsEnabled = notificationManager.areNotificationsEnabled()

            if (!areNotificationsEnabled) {
                Log.w(TAG, "⚠️ Notifications were DISABLED by user! Attempting to re-enable...")

                // Re-grant and lock the permission
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // Method 1: Use setPermissionGrantState multiple times
                    var successCount = 0
                    for (i in 1..10) { // Increased from 5 to 10 attempts for Realme
                        val result = dpm.setPermissionGrantState(
                            adminComponent,
                            packageName,
                            android.Manifest.permission.POST_NOTIFICATIONS,
                            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                        )
                        if (result) successCount++
                        Log.d(TAG, "Re-lock attempt $i: $result")
                        Thread.sleep(30)
                    }

                    // Check if it worked
                    Thread.sleep(200)
                    if (notificationManager.areNotificationsEnabled()) {
                        Log.i(TAG, "✅ Notifications RE-ENABLED successfully via setPermissionGrantState!")
                    } else {
                        Log.w(TAG, "❌ Could not re-enable notifications via setPermissionGrantState")

                        // Method 2: Try to force grant via user restriction toggle
                        try {
                            // Toggle DISALLOW_APPS_CONTROL to force system refresh
                            dpm.addUserRestriction(adminComponent, android.os.UserManager.DISALLOW_APPS_CONTROL)
                            Thread.sleep(150)
                            
                            // Re-apply permission while restriction is active
                            for (i in 1..5) {
                                dpm.setPermissionGrantState(
                                    adminComponent,
                                    packageName,
                                    android.Manifest.permission.POST_NOTIFICATIONS,
                                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                                )
                                Thread.sleep(30)
                            }
                            
                            Thread.sleep(100)
                            dpm.clearUserRestriction(adminComponent, android.os.UserManager.DISALLOW_APPS_CONTROL)

                            // Final permission grant
                            dpm.setPermissionGrantState(
                                adminComponent,
                                packageName,
                                android.Manifest.permission.POST_NOTIFICATIONS,
                                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                            )
                            
                            Log.d(TAG, "Method 2 (user restriction toggle) applied")
                        } catch (e: Exception) {
                            Log.w(TAG, "Method 2 failed: ${e.message}")
                        }
                        
                        // Method 3: For Realme/OPPO - lock ALL dangerous permissions
                        if (isRealmeOppoDevice) {
                            try {
                                Log.d(TAG, "Applying Realme-specific Method 3: Lock all permissions")
                                val packageInfo = packageManager.getPackageInfo(
                                    packageName,
                                    android.content.pm.PackageManager.GET_PERMISSIONS
                                )
                                packageInfo.requestedPermissions?.forEach { permission ->
                                    try {
                                        val isGranted = checkSelfPermission(permission) == 
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
                                Log.d(TAG, "Method 3: Locked all permissions")
                            } catch (e: Exception) {
                                Log.w(TAG, "Method 3 failed: ${e.message}")
                            }
                        }
                        
                        // Check again after all methods
                        Thread.sleep(300)
                        if (notificationManager.areNotificationsEnabled()) {
                            Log.i(TAG, "✅ Notifications finally RE-ENABLED after fallback methods!")
                        } else {
                            Log.e(TAG, "❌ Failed to re-enable notifications after all methods")
                        }
                    }
                }
            } else {
                // Notifications are enabled - ensure they stay locked
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    val currentState = dpm.getPermissionGrantState(
                        adminComponent,
                        packageName,
                        android.Manifest.permission.POST_NOTIFICATIONS
                    )

                    // If not in GRANTED state, re-lock it
                    if (currentState != DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED) {
                        Log.d(TAG, "Permission not in GRANTED state ($currentState), re-locking...")
                        
                        // Apply multiple times for Realme devices
                        val lockCount = if (isRealmeOppoDevice) 5 else 1
                        for (i in 1..lockCount) {
                            dpm.setPermissionGrantState(
                                adminComponent,
                                packageName,
                                android.Manifest.permission.POST_NOTIFICATIONS,
                                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                            )
                            if (i < lockCount) Thread.sleep(20)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error in checkAndRestoreNotificationPermission: ${e.message}")
        }
    }

    /**
     * Receiver to detect when user opens app settings
     */
    private val appSettingsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "App settings intent detected: ${intent?.action}")

            // When user accesses our app's settings, immediately re-lock notification permission
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                try {
                    dpm.setPermissionGrantState(
                        adminComponent,
                        packageName,
                        android.Manifest.permission.POST_NOTIFICATIONS,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Error re-locking on settings access: ${e.message}")
                }
            }
        }
    }

    private fun registerAppSettingsReceiver() {
        try {
            val filter = IntentFilter().apply {
                // Listen for app details settings
                addAction(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                // Listen for app notification settings
                addAction(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                // Listen for general settings
                addAction(android.provider.Settings.ACTION_SETTINGS)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(appSettingsReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(appSettingsReceiver, filter)
            }

            Log.d(TAG, "App settings receiver registered")
        } catch (e: Exception) {
            Log.w(TAG, "Could not register app settings receiver: ${e.message}")
        }
    }
}

