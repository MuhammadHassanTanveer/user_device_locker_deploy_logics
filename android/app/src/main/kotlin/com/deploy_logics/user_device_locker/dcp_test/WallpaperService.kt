package com.deploy_logics.user_device_locker

import android.app.Service
import android.app.WallpaperManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.util.Log
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Service to manage wallpaper operations.
 * Handles setting and removing wallpapers on both home and lock screens.
 */
class WallpaperService : Service() {

    companion object {
        private const val TAG = "WallpaperService"
        private const val NOTIFICATION_ID = 1003
        private const val CHANNEL_ID = "wallpaper_channel"
        private const val PREFS_NAME = "FlutterSharedPreferences"
        private const val ORIGINAL_WALLPAPER_KEY = "original_wallpaper_saved"
        private const val WALLPAPER_BACKUP_DIR = "wallpaper_backup"

        const val ACTION_CHANGE_WALLPAPER = "com.deploy_logics.user_device_locker.CHANGE_WALLPAPER"
        const val ACTION_REMOVE_WALLPAPER = "com.deploy_logics.user_device_locker.REMOVE_WALLPAPER"
        const val EXTRA_WALLPAPER_URL = "wallpaper_url"

        /**
         * Change wallpaper from a URL
         * @param context Application context
         * @param wallpaperUrl URL of the wallpaper image
         */
        fun changeWallpaper(context: Context, wallpaperUrl: String) {
            Log.d(TAG, "changeWallpaper() called with URL: $wallpaperUrl")

            val intent = Intent(context, WallpaperService::class.java).apply {
                action = ACTION_CHANGE_WALLPAPER
                putExtra(EXTRA_WALLPAPER_URL, wallpaperUrl)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "Wallpaper service started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting wallpaper service: ${e.message}")
                e.printStackTrace()
            }
        }

        /**
         * Remove custom wallpaper and restore system default
         * @param context Application context
         */
        fun removeWallpaper(context: Context) {
            Log.d(TAG, "removeWallpaper() called")

            val intent = Intent(context, WallpaperService::class.java).apply {
                action = ACTION_REMOVE_WALLPAPER
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "Wallpaper removal service started successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting wallpaper removal service: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private val executor = Executors.newSingleThreadExecutor()

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
                "Wallpaper Management",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Managing device wallpaper"
                setShowBadge(false)
            }

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(message: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Wallpaper Update")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_gallery)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(false)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand() - action: ${intent?.action}")

        // Start as foreground service
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, createNotification("Processing wallpaper..."))
        }

        when (intent?.action) {
            ACTION_CHANGE_WALLPAPER -> {
                val wallpaperUrl = intent.getStringExtra(EXTRA_WALLPAPER_URL) ?: ""
                if (wallpaperUrl.isNotEmpty()) {
                    handleChangeWallpaper(wallpaperUrl, startId)
                } else {
                    Log.e(TAG, "No wallpaper URL provided")
                    stopSelf(startId)
                }
            }
            ACTION_REMOVE_WALLPAPER -> {
                handleRemoveWallpaper(startId)
            }
            else -> {
                Log.w(TAG, "Unknown action: ${intent?.action}")
                stopSelf(startId)
            }
        }

        return START_NOT_STICKY
    }

    private fun handleChangeWallpaper(wallpaperUrl: String, startId: Int) {
        Log.d(TAG, "handleChangeWallpaper() - URL: $wallpaperUrl")

        executor.execute {
            try {
                // Backup current wallpaper first (only if not already backed up)
                backupCurrentWallpaper()

                // Download the image
                Log.d(TAG, "Downloading wallpaper from: $wallpaperUrl")
                val bitmap = downloadImage(wallpaperUrl)

                if (bitmap != null) {
                    Log.d(TAG, "Image downloaded successfully. Size: ${bitmap.width}x${bitmap.height}")

                    // Set wallpaper on both home and lock screen
                    applyWallpaper(bitmap)

                    Log.d(TAG, "✅ Wallpaper changed successfully!")
                } else {
                    Log.e(TAG, "Failed to download wallpaper image")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error changing wallpaper: ${e.message}")
                e.printStackTrace()
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
    }

    private fun handleRemoveWallpaper(startId: Int) {
        Log.d(TAG, "handleRemoveWallpaper()")

        executor.execute {
            try {
                restoreOriginalWallpaper()
                Log.d(TAG, "✅ Wallpaper removed/restored successfully!")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing wallpaper: ${e.message}")
                e.printStackTrace()
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf(startId)
            }
        }
    }

    private fun downloadImage(imageUrl: String): Bitmap? {
        var connection: HttpURLConnection? = null
        var inputStream: InputStream? = null

        return try {
            val url = URL(imageUrl)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.doInput = true
            connection.connect()

            if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                inputStream = connection.inputStream

                // Decode with appropriate options to avoid OOM
                val options = BitmapFactory.Options().apply {
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }

                BitmapFactory.decodeStream(inputStream, null, options)
            } else {
                Log.e(TAG, "HTTP error: ${connection.responseCode}")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error downloading image: ${e.message}")
            e.printStackTrace()
            null
        } finally {
            inputStream?.close()
            connection?.disconnect()
        }
    }

    private fun applyWallpaper(bitmap: Bitmap) {
        try {
            val wallpaperManager = WallpaperManager.getInstance(this)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Android 7.0+ - Can set home and lock screen separately
                Log.d(TAG, "Setting wallpaper for home and lock screen (Android 7+)")

                // Set home screen wallpaper
                wallpaperManager.setBitmap(
                    bitmap,
                    null,
                    true,
                    WallpaperManager.FLAG_SYSTEM
                )
                Log.d(TAG, "Home screen wallpaper set")

                // Set lock screen wallpaper
                wallpaperManager.setBitmap(
                    bitmap,
                    null,
                    true,
                    WallpaperManager.FLAG_LOCK
                )
                Log.d(TAG, "Lock screen wallpaper set")
            } else {
                // Android 6 and below - single wallpaper for both
                Log.d(TAG, "Setting wallpaper (Android 6 and below)")
                wallpaperManager.setBitmap(bitmap)
            }

            // Save the custom wallpaper locally for reference
            saveCustomWallpaper(bitmap)

        } catch (e: Exception) {
            Log.e(TAG, "Error setting wallpaper: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun backupCurrentWallpaper() {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            val alreadyBackedUp = prefs.getBoolean(ORIGINAL_WALLPAPER_KEY, false)

            if (alreadyBackedUp) {
                Log.d(TAG, "Original wallpaper already backed up, skipping")
                return
            }

            val wallpaperManager = WallpaperManager.getInstance(this)

            // Try to get current wallpaper
            val drawable = wallpaperManager.drawable

            if (drawable != null) {
                Log.d(TAG, "Backing up current wallpaper...")

                // Create backup directory
                val backupDir = File(filesDir, WALLPAPER_BACKUP_DIR)
                if (!backupDir.exists()) {
                    backupDir.mkdirs()
                }

                // Convert drawable to bitmap and save
                val bitmap = android.graphics.drawable.BitmapDrawable::class.java
                    .cast(drawable)?.bitmap

                if (bitmap != null) {
                    val backupFile = File(backupDir, "original_wallpaper.png")
                    FileOutputStream(backupFile).use { out ->
                        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }

                    // Mark as backed up
                    prefs.edit().putBoolean(ORIGINAL_WALLPAPER_KEY, true).apply()
                    Log.d(TAG, "Original wallpaper backed up to: ${backupFile.absolutePath}")
                }
            } else {
                Log.d(TAG, "No current wallpaper to backup")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error backing up wallpaper: ${e.message}")
        }
    }

    private fun saveCustomWallpaper(bitmap: Bitmap) {
        try {
            val backupDir = File(filesDir, WALLPAPER_BACKUP_DIR)
            if (!backupDir.exists()) {
                backupDir.mkdirs()
            }

            val customFile = File(backupDir, "custom_wallpaper.png")
            FileOutputStream(customFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            Log.d(TAG, "Custom wallpaper saved to: ${customFile.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "Error saving custom wallpaper: ${e.message}")
        }
    }

    private fun restoreOriginalWallpaper() {
        try {
            val wallpaperManager = WallpaperManager.getInstance(this)

            // First, try to restore from backup
            val backupDir = File(filesDir, WALLPAPER_BACKUP_DIR)
            val backupFile = File(backupDir, "original_wallpaper.png")

            if (backupFile.exists()) {
                Log.d(TAG, "Restoring wallpaper from backup: ${backupFile.absolutePath}")

                val bitmap = BitmapFactory.decodeFile(backupFile.absolutePath)
                if (bitmap != null) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        wallpaperManager.setBitmap(
                            bitmap,
                            null,
                            true,
                            WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                        )
                    } else {
                        wallpaperManager.setBitmap(bitmap)
                    }
                    Log.d(TAG, "Original wallpaper restored from backup")

                    // Clean up backup after restore
                    backupFile.delete()
                    val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                    prefs.edit().putBoolean(ORIGINAL_WALLPAPER_KEY, false).apply()
                    return
                }
            }

            // If no backup, reset to system default
            Log.d(TAG, "No backup found, clearing to system default wallpaper")

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                // Clear both home and lock screen wallpapers
                wallpaperManager.clear(WallpaperManager.FLAG_SYSTEM)
                wallpaperManager.clear(WallpaperManager.FLAG_LOCK)
            } else {
                wallpaperManager.clear()
            }

            Log.d(TAG, "Wallpaper cleared to system default")

            // Clean up any stored custom wallpaper
            val customFile = File(backupDir, "custom_wallpaper.png")
            if (customFile.exists()) {
                customFile.delete()
            }

        } catch (e: Exception) {
            Log.e(TAG, "Error restoring wallpaper: ${e.message}")
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy()")
        executor.shutdown()
    }
}

