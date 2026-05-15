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
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import android.widget.ImageView
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.app.NotificationCompat

class LockOverlayService : Service() {

    companion object {
        private const val TAG = "LockOverlayService"
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "lock_overlay_channel"

        var isShowing = false
        var instance: LockOverlayService? = null
            private set  // Allow reading but only internal setting
        private var unlockPin: String = "1234" // Default PIN
        private var userId: String = ""

        fun show(context: Context, userId: String = "", pin: String = "1234") {
            Log.d(TAG, "show() called - userId: $userId, pin: $pin")

            // Check overlay permission first
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Log.e(TAG, "ERROR: Cannot draw overlays - permission not granted!")
                return
            }

            this.userId = userId
            this.unlockPin = pin
            val intent = Intent(context, LockOverlayService::class.java)
            intent.putExtra("userId", userId)
            intent.putExtra("pin", pin)

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

            // First, try to remove overlay views immediately via the instance
            try {
                instance?.let { service ->
                    Log.d(TAG, "Instance exists - removing overlay views directly...")
                    service.removeAllOverlayViews()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay views via instance: ${e.message}")
            }

            // Reset the showing flag
            isShowing = false
            instance = null

            // Stop the service
            try {
                val intent = Intent(context, LockOverlayService::class.java)
                context.stopService(intent)
                Log.d(TAG, "✅ Service stop requested")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping service: ${e.message}")
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var statusBarBlockerView: View? = null
    private var pinDialogView: View? = null
    private var dpmHelper: DevicePolicyManagerHelper? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")
        instance = this

        // Initialize Device Policy Manager Helper
        dpmHelper = DevicePolicyManagerHelper(this)

        // Create notification channel for foreground service
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Device Lock",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows when device is locked"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Device Locked")
            .setContentText("This device is locked by admin")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand()")

        // Start as foreground service for Android 8+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        userId = intent?.getStringExtra("userId") ?: ""

        // Get PIN from intent, or fallback to SharedPreferences
        val intentPin = intent?.getStringExtra("pin")
        if (!intentPin.isNullOrEmpty()) {
            unlockPin = intentPin
        } else {
            // Get existing lock code from SharedPreferences
            val prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
            unlockPin = UnlockCodeApi.getStoredUnlockCode(prefs).ifEmpty { "000000" }
            Log.d(TAG, "Using unlock code from SharedPreferences: $unlockPin")
        }

        Log.d(TAG, "userId: $userId, pin: $unlockPin, isShowing: $isShowing")

        if (!isShowing) {
            showOverlay()
        }
        return START_STICKY
    }

    private fun showOverlay() {
        Log.d(TAG, "showOverlay()")

        try {
            // Lock settings access BEFORE showing overlay (redundancy - Flutter also calls this)
            dpmHelper?.let { helper ->
                if (helper.isDeviceOwner()) {
                    Log.d(TAG, "Locking settings for kiosk mode...")
                    helper.lockSettingsWhenOverlayShown()
                    helper.setStatusBarDisabled(true)
                    Log.d(TAG, "Settings locked, status bar disabled")
                }
            }

            // Collapse status bar if it's open
            collapseStatusBar()

            windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

            // ==================== 1. STATUS BAR BLOCKER OVERLAY ====================
            // This overlay specifically blocks the status bar area and intercepts swipe-down gestures
            showStatusBarBlocker()

            // ==================== 2. MAIN LOCK OVERLAY ====================
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_PHONE,
                // Updated flags to better block touch events and status bar
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                        WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
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

    /**
     * Creates a separate overlay specifically to block the status bar area.
     * This overlay sits at the top of the screen and intercepts all touch events,
     * preventing the user from swiping down to access the notification panel.
     */
    private fun showStatusBarBlocker() {
        try {
            // Get status bar height
            val statusBarHeight = getStatusBarHeight()
            Log.d(TAG, "Status bar height: $statusBarHeight")

            // Create a view that covers the status bar area + extra padding to catch swipe gestures
            val blockerHeight = statusBarHeight + 100 // Extra height to catch swipe-down gestures

            val blockerParams = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                blockerHeight,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else
                    WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                // These flags make the view intercept all touches in the status bar area
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT
            )
            blockerParams.gravity = Gravity.TOP or Gravity.START
            blockerParams.y = 0

            // Create an invisible view that blocks touches
            statusBarBlockerView = View(this).apply {
                setBackgroundColor(Color.TRANSPARENT)
                // Consume all touch events to prevent swipe-down
                setOnTouchListener { _, _ ->
                    Log.d(TAG, "Status bar touch intercepted!")
                    true // Consume the touch event
                }
            }

            windowManager?.addView(statusBarBlockerView, blockerParams)
            Log.d(TAG, "Status bar blocker added")
        } catch (e: Exception) {
            Log.e(TAG, "Error adding status bar blocker: ${e.message}")
        }
    }

    /**
     * Get the height of the status bar
     */
    private fun getStatusBarHeight(): Int {
        var result = 0
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        if (resourceId > 0) {
            result = resources.getDimensionPixelSize(resourceId)
        }
        // Default to 24dp if we can't get the actual height
        if (result == 0) {
            result = (24 * resources.displayMetrics.density).toInt()
        }
        return result
    }

    /**
     * Collapse the status bar / notification panel if it's expanded.
     * Uses reflection to call the hidden StatusBarManager.collapsePanels() method.
     */
    @Suppress("DEPRECATION")
    private fun collapseStatusBar() {
        try {
            val statusBarService = getSystemService("statusbar")
            if (statusBarService != null) {
                val statusBarManager = Class.forName("android.app.StatusBarManager")
                val collapseMethod = statusBarManager.getMethod("collapsePanels")
                collapseMethod.invoke(statusBarService)
                Log.d(TAG, "Status bar collapsed via reflection")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error collapsing status bar: ${e.message}")
            // Try alternative method for older devices
            try {
                @Suppress("DEPRECATION")
                val intent = Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS)
                sendBroadcast(intent)
                Log.d(TAG, "Status bar collapsed via broadcast")
            } catch (e2: Exception) {
                Log.e(TAG, "Error collapsing status bar via broadcast: ${e2.message}")
            }
        }
    }

    private fun createOverlayView(): View {
        // Main container
        val mainLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }

        // Background gradient
        val gradientDrawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Color.parseColor("#1a1a2e"), Color.parseColor("#16213e"))
        )
        mainLayout.background = gradientDrawable

        // ScrollView to make content scrollable
        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
            isVerticalScrollBarEnabled = true
        }

        // Content layout inside ScrollView
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(64, 64, 64, 64)
        }

        // Lock icon container
        val iconContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(160, 160).apply {
                gravity = Gravity.CENTER
                bottomMargin = 48
            }
            val iconBg = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#e94560"))
            }
            background = iconBg
        }

        // Lock icon (using a TextView with emoji as fallback)
        val lockIcon = TextView(this).apply {
            text = "🔒"
            textSize = 48f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        iconContainer.addView(lockIcon)
        contentLayout.addView(iconContainer)

        // Title
        val titleText = TextView(this).apply {
            text = "Device Locked"
            textSize = 28f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }
        contentLayout.addView(titleText)

        // Subtitle
        val subtitleText = TextView(this).apply {
            text = "This device is locked by admin"
            textSize = 16f
            setTextColor(Color.parseColor("#aaaaaa"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 48
            }
        }
        contentLayout.addView(subtitleText)

        // User ID Card
        if (userId.isNotEmpty()) {
            val userIdCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    bottomMargin = 48
                }
                setPadding(32, 16, 32, 16)
                val cardBg = GradientDrawable().apply {
                    cornerRadius = 24f
                    setColor(Color.parseColor("#2a2a4a"))
                }
                background = cardBg
            }

            val userIdLabel = TextView(this).apply {
                text = "User ID: "
                textSize = 14f
                setTextColor(Color.parseColor("#888888"))
            }
            userIdCard.addView(userIdLabel)

            val userIdValue = TextView(this).apply {
                text = userId
                textSize = 14f
                setTextColor(Color.WHITE)
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            userIdCard.addView(userIdValue)

            contentLayout.addView(userIdCard)
        }

        // Unlock button
        val unlockButton = Button(this).apply {
            text = "Enter PIN to Unlock"
            textSize = 16f
            setTextColor(Color.WHITE)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            setPadding(64, 32, 64, 32)
            val buttonBg = GradientDrawable().apply {
                cornerRadius = 48f
                setColor(Color.parseColor("#e94560"))
            }
            background = buttonBg
            setOnClickListener {
                showPinDialog()
            }
        }
        contentLayout.addView(unlockButton)

        // ==================== WiFi Section ====================
        // WiFi button container with icon and status
        val wifiContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 48
                gravity = Gravity.CENTER
            }
        }

        // WiFi button with icon
        val wifiButton = Button(this).apply {
            text = "📶 WiFi Settings"
            textSize = 14f
            setTextColor(Color.WHITE)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
            }
            setPadding(48, 24, 48, 24)
            val wifiBtnBg = GradientDrawable().apply {
                cornerRadius = 48f
                setColor(Color.parseColor("#3498db"))
            }
            background = wifiBtnBg
            setOnClickListener {
                openWifiSettings()
            }
        }
        wifiContainer.addView(wifiButton)

        // WiFi status text
        val wifiStatusText = TextView(this).apply {
            text = getWifiStatusText()
            textSize = 12f
            setTextColor(Color.parseColor("#aaaaaa"))
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
            }
        }
        wifiContainer.addView(wifiStatusText)

        contentLayout.addView(wifiContainer)

        // Add content to ScrollView, then ScrollView to main layout
        scrollView.addView(contentLayout)
        mainLayout.addView(scrollView)
        return mainLayout
    }

    /**
     * Get the current WiFi connection status text
     */
    private fun getWifiStatusText(): String {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)

                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    val wifiInfo = wifiManager.connectionInfo
                    val ssid = wifiInfo?.ssid?.replace("\"", "") ?: "Unknown"
                    if (ssid != "<unknown ssid>" && ssid.isNotEmpty()) {
                        "Connected to: $ssid"
                    } else {
                        "WiFi Connected"
                    }
                } else if (wifiManager.isWifiEnabled) {
                    "WiFi: On (Not connected)"
                } else {
                    "WiFi: Off"
                }
            } else {
                @Suppress("DEPRECATION")
                if (wifiManager.isWifiEnabled) {
                    val wifiInfo = wifiManager.connectionInfo
                    if (wifiInfo != null && wifiInfo.networkId != -1) {
                        val ssid = wifiInfo.ssid?.replace("\"", "") ?: "Unknown"
                        "Connected to: $ssid"
                    } else {
                        "WiFi: On (Not connected)"
                    }
                } else {
                    "WiFi: Off"
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting WiFi status: ${e.message}")
            "WiFi status unavailable"
        }
    }

    /**
     * Open WiFi settings through the WifiSettingsActivity
     * This ensures that when user presses back, they return to the lock overlay
     */
    private fun openWifiSettings() {
        Log.d(TAG, "Opening WiFi settings from lock overlay")
        try {
            // Temporarily allow WiFi settings access (unhide settings app)
            dpmHelper?.let { helper ->
                if (helper.isDeviceOwner()) {
                    helper.temporarilyAllowWifiSettings()
                    Log.d(TAG, "Temporarily allowed WiFi settings access")
                }
            }

            // Temporarily hide the overlay so WiFi settings is visible
            temporarilyHideOverlay()

            // Launch WiFi settings activity which handles the back navigation
            WifiSettingsActivity.launch(this, userId, unlockPin)
        } catch (e: Exception) {
            Log.e(TAG, "Error opening WiFi settings: ${e.message}")
            Toast.makeText(this, "Unable to open WiFi settings", Toast.LENGTH_SHORT).show()
            // If failed, make sure overlay is visible again
            restoreOverlay()
        }
    }

    /**
     * Temporarily hide the overlay view (but keep the service running)
     * Used when opening WiFi settings so user can see the settings screen
     * We need to actually REMOVE the views from WindowManager, not just hide them
     */
    private fun temporarilyHideOverlay() {
        Log.d(TAG, "Temporarily removing overlay for WiFi settings")
        try {
            // Remove main overlay view from window manager
            overlayView?.let { view ->
                try {
                    windowManager?.removeView(view)
                    Log.d(TAG, "Main overlay view removed temporarily")
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing main overlay: ${e.message}")
                }
            }

            // Remove status bar blocker view from window manager
            statusBarBlockerView?.let { view ->
                try {
                    windowManager?.removeView(view)
                    Log.d(TAG, "Status bar blocker view removed temporarily")
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing status bar blocker: ${e.message}")
                }
            }

            Log.d(TAG, "Overlay temporarily removed - WiFi settings should be visible now")
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay temporarily: ${e.message}")
        }
    }

    /**
     * Restore the overlay view after returning from WiFi settings
     * Re-adds the views to WindowManager
     */
    fun restoreOverlay() {
        Log.d(TAG, "Restoring overlay after WiFi settings")
        try {
            // Re-add status bar blocker first (matching showStatusBarBlocker parameters)
            statusBarBlockerView?.let { view ->
                try {
                    val statusBarHeight = getStatusBarHeight()
                    val blockerHeight = statusBarHeight + 100 // Extra height to catch swipe-down gestures

                    val blockerParams = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        blockerHeight,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        else
                            WindowManager.LayoutParams.TYPE_SYSTEM_ERROR,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                        PixelFormat.TRANSLUCENT
                    )
                    blockerParams.gravity = Gravity.TOP or Gravity.START
                    blockerParams.y = 0
                    windowManager?.addView(view, blockerParams)
                    Log.d(TAG, "Status bar blocker view restored")
                } catch (e: Exception) {
                    Log.e(TAG, "Error restoring status bar blocker: ${e.message}")
                }
            }

            // Re-add main overlay view
            overlayView?.let { view ->
                try {
                    val params = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        else
                            WindowManager.LayoutParams.TYPE_PHONE,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                        PixelFormat.TRANSLUCENT
                    )
                    params.gravity = Gravity.TOP or Gravity.START
                    windowManager?.addView(view, params)
                    Log.d(TAG, "Main overlay view restored")
                } catch (e: Exception) {
                    Log.e(TAG, "Error restoring main overlay: ${e.message}")
                }
            }

            // Re-block WiFi settings access
            dpmHelper?.let { helper ->
                if (helper.isDeviceOwner()) {
                    helper.reblockWifiSettings()
                    Log.d(TAG, "Re-blocked WiFi settings access")
                }
            }

            Log.d(TAG, "Overlay fully restored")
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring overlay: ${e.message}")
        }
    }

    private fun showPinDialog() {
        // Make window focusable for input
        val params = (overlayView?.layoutParams as? WindowManager.LayoutParams)?.apply {
            flags = flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        }
        if (params != null) {
            windowManager?.updateViewLayout(overlayView, params)
        }

        // Create dialog container
        val dialogContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#80000000"))
            setOnClickListener { /* Prevent click through */ }
        }

        // Dialog card
        val dialogCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            ).apply {
                leftMargin = 48
                rightMargin = 48
            }
            setPadding(48, 48, 48, 48)
            minimumWidth = 600
            val cardBg = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.parseColor("#2a2a4a"))
            }
            background = cardBg
        }

        // Dialog title
        val dialogTitle = TextView(this).apply {
            text = "Enter PIN"
            textSize = 20f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 32
            }
        }
        dialogCard.addView(dialogTitle)

        // PIN input (6 digits)
        val pinInput = EditText(this).apply {
            hint = "Enter 6-digit code"
            setHintTextColor(Color.parseColor("#666666"))
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or
                       android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 32
            }
            setPadding(32, 24, 32, 24)
            val inputBg = GradientDrawable().apply {
                cornerRadius = 12f
                setColor(Color.parseColor("#1a1a2e"))
                setStroke(2, Color.parseColor("#444466"))
            }
            background = inputBg
        }
        dialogCard.addView(pinInput)

        // Button container
        val buttonContainer = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Close button
        val closeButton = Button(this).apply {
            text = "Close"
            textSize = 14f
            setTextColor(Color.WHITE)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                rightMargin = 16
            }
            setPadding(32, 24, 32, 24)
            val buttonBg = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.parseColor("#444466"))
            }
            background = buttonBg
            setOnClickListener {
                hidePinDialog(dialogContainer)
            }
        }
        buttonContainer.addView(closeButton)

        // Done button
        val doneButton = Button(this).apply {
            text = "Done"
            textSize = 14f
            setTextColor(Color.WHITE)
            isAllCaps = false
            layoutParams = LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            ).apply {
                leftMargin = 16
            }
            setPadding(32, 24, 32, 24)
            val buttonBg = GradientDrawable().apply {
                cornerRadius = 24f
                setColor(Color.parseColor("#e94560"))
            }
            background = buttonBg
            setOnClickListener {
                val enteredPin = pinInput.text.toString().trim()
                if (enteredPin.length != 6) {
                    Toast.makeText(
                        this@LockOverlayService,
                        "Please enter a 6-digit code",
                        Toast.LENGTH_SHORT
                    ).show()
                    return@setOnClickListener
                }
                if (enteredPin == unlockPin) {
                    val newCode = UnlockCodeApi.generateUnlockCode()
                    val prefs = getSharedPreferences("FlutterSharedPreferences", MODE_PRIVATE)
                    UnlockCodeApi.saveUnlockCodeLocally(prefs, newCode)
                    unlockPin = newCode
                    UnlockCodeApi.updateUnlockCode(applicationContext, newCode)
                    hidePinDialog(dialogContainer)
                    hideOverlay()
                } else {
                    Toast.makeText(this@LockOverlayService, "Incorrect code", Toast.LENGTH_SHORT).show()
                    pinInput.setText("")
                }
            }
        }
        buttonContainer.addView(doneButton)

        dialogCard.addView(buttonContainer)
        dialogContainer.addView(dialogCard)

        // Add dialog to overlay
        (overlayView as? FrameLayout)?.addView(dialogContainer)
        pinDialogView = dialogContainer

        // Show keyboard
        pinInput.requestFocus()
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        pinInput.postDelayed({
            imm.showSoftInput(pinInput, InputMethodManager.SHOW_IMPLICIT)
        }, 200)
    }

    private fun hidePinDialog(dialogContainer: View) {
        // Hide keyboard
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(dialogContainer.windowToken, 0)

        // Remove dialog
        (overlayView as? FrameLayout)?.removeView(dialogContainer)
        pinDialogView = null

        // Make window not focusable again
        val params = (overlayView?.layoutParams as? WindowManager.LayoutParams)?.apply {
            flags = flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        }
        if (params != null) {
            windowManager?.updateViewLayout(overlayView, params)
        }
    }

    private fun hideOverlay() {
        Log.d(TAG, "hideOverlay() START")
        try {
            // FIRST: Remove status bar blocker view BEFORE calling DPM methods
            // This ensures the physical blocker is gone even if DPM calls fail
            statusBarBlockerView?.let {
                try {
                    windowManager?.removeView(it)
                    Log.d(TAG, "✅ Status bar blocker view removed FIRST")
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing status bar blocker: ${e.message}")
                }
                statusBarBlockerView = null
            }

            // SECOND: Unlock settings and re-enable status bar via DPM
            dpmHelper?.let { helper ->
                if (helper.isDeviceOwner()) {
                    Log.d(TAG, "Unlocking settings after overlay hidden...")

                    // Enable status bar FIRST
                    val statusBarResult = helper.setStatusBarDisabled(false)
                    Log.d(TAG, "✅ Status bar enabled result: $statusBarResult")

                    // Then unlock settings
                    val unlockResult = helper.unlockSettingsWhenOverlayHidden()
                    Log.d(TAG, "✅ Settings unlock result: $unlockResult")
                }
            }

            // Remove main overlay view
            overlayView?.let {
                try {
                    windowManager?.removeView(it)
                    Log.d(TAG, "✅ Main overlay view removed")
                } catch (e: Exception) {
                    Log.e(TAG, "Error removing main overlay: ${e.message}")
                }
                overlayView = null
            }

            isShowing = false
            Log.d(TAG, "hideOverlay() calling stopSelf()")
            stopSelf()
        } catch (e: Exception) {
            Log.e(TAG, "Error in hideOverlay(): ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Remove all overlay views immediately.
     * This is called from the static hide() method and from onDestroy().
     */
    fun removeAllOverlayViews() {
        Log.d(TAG, "removeAllOverlayViews() START")

        // Remove PIN dialog if showing
        pinDialogView?.let { view ->
            try {
                windowManager?.removeView(view)
                Log.d(TAG, "✅ PIN dialog view removed")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "PIN dialog was not attached")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing PIN dialog: ${e.message}")
            }
            pinDialogView = null
        }

        // Remove status bar blocker
        statusBarBlockerView?.let { view ->
            try {
                windowManager?.removeView(view)
                Log.d(TAG, "✅ Status bar blocker removed")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Status bar blocker was not attached")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing status bar blocker: ${e.message}")
            }
            statusBarBlockerView = null
        }

        // Unlock DPM settings and enable status bar
        dpmHelper?.let { helper ->
            if (helper.isDeviceOwner()) {
                try {
                    val statusBarResult = helper.setStatusBarDisabled(false)
                    Log.d(TAG, "✅ Status bar enabled: $statusBarResult")

                    val unlockResult = helper.unlockSettingsWhenOverlayHidden()
                    Log.d(TAG, "✅ Settings unlocked: $unlockResult")
                } catch (e: Exception) {
                    Log.e(TAG, "Error unlocking settings: ${e.message}")
                }
            }
        }

        // Remove main overlay view
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
                Log.d(TAG, "✅ Main overlay view removed")
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Main overlay was not attached")
            } catch (e: Exception) {
                Log.e(TAG, "Error removing main overlay: ${e.message}")
            }
            overlayView = null
        }

        isShowing = false
        Log.d(TAG, "removeAllOverlayViews() COMPLETE")
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy() START - Ensuring complete cleanup")

        // Remove all overlay views
        removeAllOverlayViews()

        instance = null
        isShowing = false
        Log.d(TAG, "onDestroy() COMPLETE")
    }
}


