package com.deploy_logics.user_device_locker

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.core.app.NotificationCompat

/**
 * Service to show a message dialog overlay to the customer.
 * The overlay can be dismissed by clicking the close icon or OK button.
 */
class MessageOverlayService : Service() {

    companion object {
        private const val TAG = "MessageOverlayService"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "message_overlay_channel"
        private const val PREFS_NAME = "FlutterSharedPreferences"
        private const val RETAILER_NAME_KEY = "flutter.retailer_name"
        private const val ACTION_HIDE = "com.deploy_logics.user_device_locker.HIDE_OVERLAY"

        var isShowing = false
        private var instance: MessageOverlayService? = null

        /**
         * Show message overlay dialog
         * @param context Application context
         * @param message The message to display
         * @param companyName Optional company name (if not provided, reads from SharedPreferences)
         */
        fun show(context: Context, message: String, companyName: String? = null) {
            Log.d(TAG, "show() called - message: $message, companyName: $companyName")

            // Check overlay permission first
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Log.e(TAG, "ERROR: Cannot draw overlays - permission not granted!")
                return
            }

            val intent = Intent(context, MessageOverlayService::class.java)
            intent.putExtra("message", message)
            if (companyName != null) {
                intent.putExtra("companyName", companyName)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Log.d(TAG, "Starting foreground service (Android 8+)")
                    context.startForegroundService(intent)
                } else {
                    Log.d(TAG, "Starting service (Android 7 and below)")
                    context.startService(intent)
                }
                Log.d(TAG, "Service start requested successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting service: ${e.message}")
                e.printStackTrace()
            }
        }

        fun hide(context: Context) {
            Log.d(TAG, "hide() called - isShowing: $isShowing")

            // First, try to hide the overlay immediately via the instance
            try {
                instance?.let { service ->
                    Log.d(TAG, "Instance exists - removing overlay view directly...")
                    service.removeOverlayView()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding overlay via instance: ${e.message}")
            }

            // Reset the showing flag
            isShowing = false
            instance = null

            // Stop the service
            try {
                val intent = Intent(context, MessageOverlayService::class.java)
                context.stopService(intent)
                Log.d(TAG, "✅ Service stop requested")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping service: ${e.message}")
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var message: String = ""
    private var companyName: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")
        instance = this

        // Create notification channel for foreground service
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Customer Messages",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows customer notification messages"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Important Message")
            .setContentText("You have a message from your provider")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(false)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand()")

        // Start as foreground service for Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        message = intent?.getStringExtra("message") ?: ""
        companyName = intent?.getStringExtra("companyName") ?: ""

        // If company name not provided, read from SharedPreferences
        if (companyName.isEmpty()) {
            val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            companyName = prefs.getString(RETAILER_NAME_KEY, "Company") ?: "Company"
        }

        Log.d(TAG, "message: $message, companyName: $companyName, isShowing: $isShowing")

        if (!isShowing && message.isNotEmpty()) {
            showOverlay()
        } else if (message.isEmpty()) {
            Log.w(TAG, "No message provided, stopping service")
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun showOverlay() {
        Log.d(TAG, "showOverlay()")

        try {
            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                // Note: NOT using FLAG_NOT_FOCUSABLE so buttons can receive touch events
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                        WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START

            overlayView = createOverlayView()
            windowManager?.addView(overlayView, params)
            isShowing = true
            Log.d(TAG, "Overlay shown successfully!")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing overlay: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun createOverlayView(): View {
        // Main container - semi-transparent background
        val mainLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#99000000")) // Semi-transparent black
            setOnClickListener {
                // Dismiss when clicking outside the dialog
                hideOverlay()
            }
        }

        // Dialog card container
        val dialogCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                leftMargin = 48
                rightMargin = 48
            }
            setPadding(0, 0, 0, 0)
            val cardBg = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.WHITE)
            }
            background = cardBg
            // Prevent click through to background
            setOnClickListener { /* Do nothing, just consume click */ }
        }

        // Header container (company name + close icon)
        val headerContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(32, 32, 32, 16)
            gravity = Gravity.CENTER_VERTICAL
            // Header background with top rounded corners
            val headerBg = GradientDrawable().apply {
                cornerRadii = floatArrayOf(24f, 24f, 24f, 24f, 0f, 0f, 0f, 0f)
                setColor(Color.parseColor("#1a73e8")) // Blue header
            }
            background = headerBg
        }

        // Company name / title
        val titleText = TextView(this).apply {
            text = companyName
            textSize = 20f
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        }
        headerContainer.addView(titleText)

        // Close icon (X button)
        val closeIcon = TextView(this).apply {
            text = "✕"
            textSize = 24f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                80,
                80
            )
            val closeBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#40FFFFFF")) // Semi-transparent white
            }
            background = closeBg
            setOnClickListener {
                hideOverlay()
            }
        }
        headerContainer.addView(closeIcon)

        dialogCard.addView(headerContainer)

        // Message label
        val messageLabelText = TextView(this).apply {
            text = "Message"
            textSize = 14f
            setTextColor(Color.parseColor("#666666"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 24
            }
            setPadding(32, 0, 32, 8)
        }
        dialogCard.addView(messageLabelText)

        // Message content in a ScrollView (for long messages)
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
            }
            setPadding(32, 0, 32, 0)
            // Set max height to prevent very long messages from taking entire screen
            // This uses a workaround since ScrollView doesn't have maxHeight in API
        }

        val messageText = TextView(this).apply {
            text = message
            textSize = 16f
            setTextColor(Color.parseColor("#333333"))
            setLineSpacing(0f, 1.3f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        scrollView.addView(messageText)
        dialogCard.addView(scrollView)

        // Divider line
        val divider = View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2
            ).apply {
                topMargin = 24
            }
            setBackgroundColor(Color.parseColor("#EEEEEE"))
        }
        dialogCard.addView(divider)

        // OK Button container
        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(32, 24, 32, 32)
        }

        // OK button
        val okButton = Button(this).apply {
            text = "OK"
            textSize = 16f
            setTextColor(Color.WHITE)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(48, 24, 48, 24)
            val buttonBg = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(Color.parseColor("#1a73e8")) // Blue button
            }
            background = buttonBg
            setOnClickListener {
                hideOverlay()
            }
        }
        buttonContainer.addView(okButton)

        dialogCard.addView(buttonContainer)

        mainLayout.addView(dialogCard)
        return mainLayout
    }

    /**
     * Remove the overlay view from the window manager.
     * This is called from the static hide() method and from hideOverlay().
     */
    fun removeOverlayView() {
        Log.d(TAG, "removeOverlayView() - overlayView: $overlayView")
        try {
            overlayView?.let { view ->
                try {
                    windowManager?.removeView(view)
                    Log.d(TAG, "✅ Overlay view removed from WindowManager")
                } catch (e: IllegalArgumentException) {
                    // View not attached - ignore
                    Log.w(TAG, "View was not attached to window manager")
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing overlay view: ${e.message}")
                }
                overlayView = null
            }
            isShowing = false
        } catch (e: Exception) {
            Log.e(TAG, "Error in removeOverlayView(): ${e.message}")
        }
    }

    private fun hideOverlay() {
        Log.d(TAG, "hideOverlay()")
        try {
            removeOverlayView()
            Log.d(TAG, "hideOverlay() calling stopSelf()")
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Error in hideOverlay(): ${e.message}")
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy()")

        // Remove overlay view if still present
        removeOverlayView()

        instance = null
        isShowing = false
        Log.d(TAG, "onDestroy() COMPLETE")
    }
}


