package com.deploy_logics.user_device_locker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Persistent foreground service that keeps the app process alive.
 * This ensures that FCM messages are always delivered to our custom
 * MyFirebaseMessagingService even when the app is "killed".
 *
 * For Device Owner apps, this service runs continuously and:
 * 1. Keeps the app process alive so FCM can deliver messages
 * 2. Ensures DevicePolicyManager commands can execute immediately
 * 3. Provides reliable background execution for kiosk functionality
 */
class PersistentFCMService : Service() {

    companion object {
        private const val TAG = "PersistentFCMService"
        private const val NOTIFICATION_ID = 4001
        private const val CHANNEL_ID = "persistent_fcm_channel"

        /**
         * Start the persistent service
         */
        fun start(context: Context) {
            Log.d(TAG, "start() called")
            val intent = Intent(context, PersistentFCMService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "✅ Persistent service start requested")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error starting persistent service: ${e.message}")
            }
        }

        /**
         * Stop the persistent service
         */
        fun stop(context: Context) {
            Log.d(TAG, "stop() called")
            val intent = Intent(context, PersistentFCMService::class.java)
            context.stopService(intent)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Device Management",
                NotificationManager.IMPORTANCE_MIN  // Minimal importance to reduce visibility
            ).apply {
                description = "Keeps device management active"
                setShowBadge(false)
                enableLights(false)
                enableVibration(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Device Management Active")
            .setContentText("Device is being managed remotely")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand()")

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification())

        // Ensure FCM is registered
        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    Log.d(TAG, "✅ FCM token active: ${task.result?.take(20)}...")
                } else {
                    Log.e(TAG, "❌ FCM token error: ${task.exception?.message}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ FCM error: ${e.message}")
        }

        // Return START_STICKY to restart the service if killed
        return START_STICKY
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy() - service is being destroyed")
        super.onDestroy()

        // Restart the service if it was destroyed (for Device Owner apps)
        try {
            val dpmHelper = DevicePolicyManagerHelper(this)
            if (dpmHelper.isDeviceOwner()) {
                Log.d(TAG, "Device Owner app - restarting service")
                start(applicationContext)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restarting service: ${e.message}")
        }
    }
}

