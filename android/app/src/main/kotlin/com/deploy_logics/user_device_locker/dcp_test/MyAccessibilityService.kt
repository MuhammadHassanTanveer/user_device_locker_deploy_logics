package com.deploy_logics.user_device_locker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Accessibility Service that monitors user navigation to sensitive settings pages
 * like Factory Reset, and displays warning overlays to prevent unauthorized actions.
 */
class MyAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "MyAccessibilityService"
        private const val PREFS_NAME = "factory_reset_warning_prefs"
        private const val KEY_WARNING_ENABLED = "warning_enabled"
        
        // SharedPreferences keys for retailer info
        private const val FLUTTER_PREFS_NAME = "FlutterSharedPreferences"
        private const val RETAILER_NAME_KEY = "flutter.retailer_name"
        private const val RETAILER_NAME_URDU_KEY = "flutter.retailer_name_urdu"
        private const val RETAILER_PHONE_KEY = "flutter.retailer_phone"
        
        // Package names for Android Settings (various manufacturers)
        private val SETTINGS_PACKAGES = listOf(
            "com.android.settings",
            "com.samsung.android.settings",
            "com.miui.settings",
            "com.coloros.settings",
            "com.oppo.settings",
            "com.realme.settings",
            "com.vivo.settings",
            "com.oneplus.settings",
            "com.huawei.settings",
            "com.asus.settings",
            "com.google.android.apps.wellbeing"
        )
        
        // Keywords that indicate factory reset related pages (English and Urdu)
        private val FACTORY_RESET_KEYWORDS = listOf(
            "factory data reset",
            "factory reset",
            "erase all data",
            "reset phone",
            "reset device",
            "reset tablet",
            "master clear",
            "master reset",
            "hard reset",
            "wipe all data",
            "erase everything",
            "delete all data",
            "reset to factory",
            "restore factory",
            "all data will be erased",
            "erase all user data",
            "reset options",
            "masterclear",
            "factoryreset",
            "wipedataactivity",
            "فیکٹری ری سیٹ",
            "ڈیٹا صاف کریں",
            "تمام ڈیٹا مٹائیں"
        )
        
        // Specific keywords that strongly indicate factory reset (higher priority)
        private val STRONG_FACTORY_RESET_KEYWORDS = listOf(
            "factory data reset",
            "factory reset",
            "erase all data",
            "reset your phone",
            "all data will be erased",
            "masterclear",
            "master clear"
        )
        
        // Activity/Class names commonly used for factory reset screens
        private val FACTORY_RESET_ACTIVITIES = listOf(
            "MasterClear",
            "MasterClearConfirm",
            "FactoryReset",
            "FactoryResetActivity",
            "ResetPhone",
            "ResetDevice",
            "WipeData",
            "EraseData",
            "ResetSettings",
            "BackupReset",
            "ResetDashboard",
            "ResetOptions"
        )
        
        var instance: MyAccessibilityService? = null
            private set
    }

    private var prefs: SharedPreferences? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Device Policy Manager Helper to check factory reset status
    private var dpmHelper: DevicePolicyManagerHelper? = null
    
    // Overlay state management
    private var overlayView: View? = null
    private var windowManager: WindowManager? = null
    private var isOverlayShowing = false
    private var isOverlayPending = false  // Track when we're in the process of showing overlay
    private var wasOverlayDismissedByUser = false
    private var lastFactoryResetDetectedTime: Long = 0
    private var currentSettingsPackage: String? = null
    private var lastOverlayShowTime: Long = 0  // Debounce overlay showing
    private var isOnFactoryResetScreen = false  // Track if we're on factory reset screen
    
    // Our own app package - ignore events from this
    private val OWN_PACKAGE = "com.deploy_logics.user_device_locker"
    
    // System packages to ignore - these can fire transitional events
    private val IGNORED_PACKAGES = listOf(
        "com.android.systemui",
        "com.android.launcher",
        "com.google.android.apps.nexuslauncher",
        "com.samsung.android.app.routines",
        "com.sec.android.app.launcher",
        "com.miui.home",
        "com.oppo.launcher",
        "com.android.inputmethod",
        "com.google.android.inputmethod",
        "com.samsung.android.honeyboard",
        "com.touchtype.swiftkey"
    )

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""
        val eventType = event.eventType
        
        // IMPORTANT: Ignore events from our own app (the overlay itself)
        // This prevents the overlay from hiding itself
        if (packageName.contains(OWN_PACKAGE) || packageName.contains("dcp_test")) {
            return
        }
        
        // Ignore events from system UI and other system packages
        // These can fire during transitions and cause false triggers
        if (IGNORED_PACKAGES.any { packageName.contains(it) }) {
            return
        }
        
        // Only process window state changes to reduce event spam
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && 
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            return
        }
        
        // Check if we're in any Settings app
        val isInSettings = SETTINGS_PACKAGES.any { packageName.contains(it) } || packageName.contains("settings")
        
        if (!isInSettings) {
            // Received event from non-settings app
            // DON'T hide overlay or reset state here - this could be a transitional event
            // (e.g., system UI, keyboard, etc.)
            // The overlay will only be hidden when user explicitly dismisses it
            
            // Only reset currentSettingsPackage if overlay is not showing
            // This prevents false "left settings" triggers during transitions
            if (!isOverlayShowing && !isOverlayPending) {
                if (currentSettingsPackage != null) {
                    Log.d(TAG, "Left settings (no overlay showing), package: $packageName")
                    currentSettingsPackage = null
                    wasOverlayDismissedByUser = false
                    isOnFactoryResetScreen = false
                }
            } else {
                // Overlay is showing - ignore this event completely
                // User must dismiss the overlay manually
                Log.d(TAG, "Ignoring non-settings event while overlay is showing: $packageName")
            }
            return
        }
        
        currentSettingsPackage = packageName
        
        // If overlay is already showing or pending, don't process further - prevents blinking
        if (isOverlayShowing || isOverlayPending) {
            return
        }
        
        // Debounce: don't process events too quickly after showing overlay
        val now = System.currentTimeMillis()
        if (now - lastOverlayShowTime < 3000) {
            return // Skip events for 3 seconds after showing overlay
        }
        
        // Only check on TYPE_WINDOW_STATE_CHANGED for major navigation
        // TYPE_WINDOW_CONTENT_CHANGED fires too often and causes blinking
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Only process content changes if we haven't detected factory reset recently
            if (now - lastFactoryResetDetectedTime < 10000) {
                return // Skip content changes for 10 seconds after detection
            }
        }
        
        // Get text from window
        val windowText = getWindowText().lowercase()
        
        // Check if this is a factory reset related page
        if (isFactoryResetRelated(className, windowText)) {
            isOnFactoryResetScreen = true
            lastFactoryResetDetectedTime = System.currentTimeMillis()
            Log.d(TAG, "🚨 Factory Reset page DETECTED!")
            
            // Reset the dismissed flag ONLY if user has navigated away and come back
            // (i.e., this is a new navigation to factory reset)
            // This ensures overlay shows every time user opens factory reset screen
            if (wasOverlayDismissedByUser) {
                // Check if enough time has passed since last dismissal (user likely navigated away and back)
                val timeSinceLastShow = now - lastOverlayShowTime
                if (timeSinceLastShow > 5000) {
                    Log.d(TAG, "Resetting dismissed flag - user navigated back to factory reset")
                    wasOverlayDismissedByUser = false
                }
            }
            
            if (!wasOverlayDismissedByUser) {
                showOverlayDirectly()
            }
        } else {
            // User is in settings but NOT on factory reset screen
            // Reset the dismissed flag so overlay will show again when they navigate to factory reset
            if (isOnFactoryResetScreen) {
                Log.d(TAG, "Left factory reset screen, resetting dismissed flag")
                isOnFactoryResetScreen = false
                wasOverlayDismissedByUser = false
            }
        }
    }
    
    /**
     * Get all text from the current window using rootInActiveWindow
     */
    private fun getWindowText(): String {
        val sb = StringBuilder()
        try {
            val rootNode = rootInActiveWindow
            if (rootNode != null) {
                extractTextFromNode(rootNode, sb)
                rootNode.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting window text: ${e.message}")
        }
        return sb.toString()
    }
    
    /**
     * Recursively extract text from accessibility node and its children
     */
    private fun extractTextFromNode(node: AccessibilityNodeInfo?, sb: StringBuilder, depth: Int = 0) {
        if (node == null || depth > 12) return
        
        try {
            node.text?.let {
                sb.append(it.toString()).append(" ")
            }
            node.contentDescription?.let {
                sb.append(it.toString()).append(" ")
            }
            
            for (i in 0 until node.childCount) {
                val child = node.getChild(i)
                if (child != null) {
                    extractTextFromNode(child, sb, depth + 1)
                    child.recycle()
                }
            }
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun isFactoryResetRelated(className: String, text: String): Boolean {
        val classNameLower = className.lowercase()
        val textLower = text.lowercase()
        
        // Check strong keywords first
        for (keyword in STRONG_FACTORY_RESET_KEYWORDS) {
            if (textLower.contains(keyword.lowercase())) {
                Log.d(TAG, "✅ Matched STRONG keyword: $keyword")
                return true
            }
        }
        
        // Check activity name
        for (activity in FACTORY_RESET_ACTIVITIES) {
            if (classNameLower.contains(activity.lowercase())) {
                Log.d(TAG, "✅ Matched activity: $activity")
                return true
            }
        }
        
        // Check regular keywords
        for (keyword in FACTORY_RESET_KEYWORDS) {
            if (textLower.contains(keyword.lowercase())) {
                Log.d(TAG, "✅ Matched keyword: $keyword")
                return true
            }
        }
        
        return false
    }

    /**
     * Show overlay DIRECTLY from accessibility service using TYPE_ACCESSIBILITY_OVERLAY
     * Only shows when factory reset is DISABLED (restricted).
     */
    private fun showOverlayDirectly() {
        if (!isWarningEnabled()) {
            Log.d(TAG, "Warning overlay is disabled in settings")
            return
        }
        
        // Check if factory reset is disabled - only show overlay when it's DISABLED
        // If factory reset is ENABLED, no need to show warning
        val isFactoryResetDisabled = dpmHelper?.isFactoryResetDisabled() ?: false
        if (!isFactoryResetDisabled) {
            Log.d(TAG, "Factory reset is ENABLED - not showing warning overlay")
            return
        }
        
        // Already showing or pending - don't show again
        if (isOverlayShowing || isOverlayPending || overlayView != null) {
            return
        }
        
        // Set pending flag immediately to prevent race conditions
        isOverlayPending = true
        lastOverlayShowTime = System.currentTimeMillis()
        
        Log.d(TAG, "🔴 SHOWING OVERLAY (Factory reset is DISABLED)")
        
        mainHandler.post {
            try {
                createAndShowOverlay()
            } catch (e: Exception) {
                Log.e(TAG, "Error showing overlay: ${e.message}")
                isOverlayPending = false  // Reset pending flag on error
            }
        }
    }
    
    private fun createAndShowOverlay() {
        // Double check to prevent race condition
        if (isOverlayShowing || overlayView != null) {
            isOverlayPending = false
            return
        }
        
        if (windowManager == null) {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        }
        
        // Get retailer info
        val flutterPrefs = getSharedPreferences(FLUTTER_PREFS_NAME, MODE_PRIVATE)
        val retailerName = flutterPrefs.getString(RETAILER_NAME_KEY, "") ?: ""
        val retailerNameUrdu = flutterPrefs.getString(RETAILER_NAME_URDU_KEY, "") ?: ""
        val retailerPhone = flutterPrefs.getString(RETAILER_PHONE_KEY, "") ?: ""
        
        val message = buildWarningMessage(retailerName, retailerNameUrdu, retailerPhone)
        val title = "⚠️ Legal Warning / قانونی انتباہ"
        
        // Create overlay UI
        val container = FrameLayout(this).apply {
            setBackgroundColor(Color.parseColor("#DD000000"))
            isClickable = true
            isFocusable = true
        }
        
        // Dialog card
        val dialogCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)
            val bg = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 24f
            }
            background = bg
        }
        
        // Close button
        val closeBtn = TextView(this).apply {
            text = "✕"
            textSize = 22f
            setTextColor(Color.parseColor("#666666"))
            gravity = Gravity.END
            setPadding(0, 0, 0, 8)
            setOnClickListener { dismissOverlayByUser() }
        }
        
        // Warning icon
        val icon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_dialog_alert)
            setColorFilter(Color.parseColor("#FF5722"))
            val size = (50 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            }
        }
        
        // Title
        val titleView = TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(Color.parseColor("#D32F2F"))
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * resources.displayMetrics.density).toInt()
            }
        }
        
        // ScrollView for message
        val scrollView = ScrollView(this).apply {
            val maxHeight = (resources.displayMetrics.heightPixels * 0.45).toInt()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                maxHeight
            ).apply {
                bottomMargin = (12 * resources.displayMetrics.density).toInt()
            }
        }
        
        val messageView = TextView(this).apply {
            text = message
            textSize = 13f
            setTextColor(Color.parseColor("#333333"))
            gravity = Gravity.START
            setLineSpacing(0f, 1.15f)
        }
        scrollView.addView(messageView)
        
        // OK Button
        val okButton = Button(this).apply {
            text = "I UNDERSTAND / میں سمجھتا ہوں"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            val btnBg = GradientDrawable().apply {
                setColor(Color.parseColor("#D32F2F"))
                cornerRadius = 20f
            }
            background = btnBg
            setPadding(24, 14, 24, 14)
            setOnClickListener { dismissOverlayByUser() }
        }
        
        // Build dialog
        dialogCard.addView(closeBtn)
        dialogCard.addView(icon)
        dialogCard.addView(titleView)
        dialogCard.addView(scrollView)
        dialogCard.addView(okButton)
        
        val dialogParams = FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        dialogCard.layoutParams = dialogParams
        
        container.addView(dialogCard)
        
        // Tap outside to close
        container.setOnClickListener { dismissOverlayByUser() }
        dialogCard.setOnClickListener { /* consume click */ }
        
        overlayView = container
        
        // Window params - TYPE_ACCESSIBILITY_OVERLAY
        // Using FLAG_LAYOUT_IN_SCREEN and FLAG_FULLSCREEN to cover the entire screen
        // Removed FLAG_NOT_FOCUSABLE to make the overlay capture all touch events
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.CENTER
        
        try {
            windowManager?.addView(container, params)
            isOverlayShowing = true
            isOverlayPending = false
            Log.d(TAG, "✅ Overlay shown successfully!")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to show overlay: ${e.message}")
            overlayView = null
            isOverlayPending = false
        }
    }
    
    private fun buildWarningMessage(retailerName: String, retailerNameUrdu: String, retailerPhone: String): String {
        val sb = StringBuilder()
        
        sb.append("You are attempting to make an unauthorized change to this device's security system.\n")
        sb.append("This device is on installments and remains the property of the company until full payment is made.\n")
        sb.append("Any tampering, removing the app, factory reset, or security bypass is a violation of the agreement.\n\n")
        sb.append("In such a case:\n")
        sb.append("• The device may be locked immediately\n")
        sb.append("• Legal action may be taken against you\n\n")
        sb.append("Please stop this action immediately.\n")
        
        sb.append("\n━━━━━━━━━━━━━━━━━━━━━\n\n")
        
        sb.append("آپ اس ڈیوائس کے سیکیورٹی سسٹم میں غیر مجاز تبدیلی کرنے کی کوشش کر رہے ہیں۔\n")
        sb.append("یہ ڈیوائس اقساط پر ہے اور مکمل ادائیگی تک کمپنی کی ملکیت ہے۔\n")
        sb.append("کسی بھی قسم کی چھیڑ چھاڑ، ایپ کو ہٹانے، فیکٹری ری سیٹ یا سیکیورٹی بائی پاس کرنا معاہدے کی خلاف ورزی ہے۔\n\n")
        sb.append("ایسی صورت میں:\n")
        sb.append("• ڈیوائس فوری طور پر لاک کی جا سکتی ہے\n")
        sb.append("• آپ کے خلاف قانونی کارروائی کی جا سکتی ہے\n\n")
        sb.append("براہ کرم فوراً یہ عمل روک دیں۔")
        
        if (retailerName.isNotEmpty() || retailerPhone.isNotEmpty()) {
            sb.append("\n\n━━━━━━━━━━━━━━━━━━━━━\n\n")
            sb.append("📞 Contact / رابطہ:\n")
            
            if (retailerName.isNotEmpty() && retailerName != "null") {
                if (retailerNameUrdu.isNotEmpty() && retailerNameUrdu != "null") {
                    sb.append("🏪 $retailerName / $retailerNameUrdu\n")
                } else {
                    sb.append("🏪 $retailerName\n")
                }
            }
            
            if (retailerPhone.isNotEmpty() && retailerPhone != "null") {
                sb.append("📱 $retailerPhone")
            }
        }
        
        return sb.toString()
    }
    
    /**
     * Called when user explicitly dismisses the overlay
     */
    private fun dismissOverlayByUser() {
        Log.d(TAG, "User dismissed overlay")
        wasOverlayDismissedByUser = true
        isOnFactoryResetScreen = false
        hideOverlayDirectly()
    }
    
    fun hideOverlayDirectly() {
        mainHandler.post {
            try {
                overlayView?.let {
                    windowManager?.removeView(it)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error hiding overlay: ${e.message}")
            }
            overlayView = null
            isOverlayShowing = false
            isOverlayPending = false
        }
    }

    private fun isWarningEnabled(): Boolean {
        return prefs?.getBoolean(KEY_WARNING_ENABLED, true) ?: true
    }

    fun setWarningEnabled(enabled: Boolean) {
        prefs?.edit()?.putBoolean(KEY_WARNING_ENABLED, enabled)?.apply()
    }

    override fun onInterrupt() {
        Log.d(TAG, "onInterrupt()")
    }
    
    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP && isOverlayShowing) {
            dismissOverlayByUser()
            return true
        }
        return super.onKeyEvent(event)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(TAG, "✅ Accessibility Service CONNECTED!")
        
        instance = this
        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // Initialize DevicePolicyManagerHelper to check factory reset status
        dpmHelper = DevicePolicyManagerHelper(this)
        
        // Reset all state flags on service connect/reconnect
        // This ensures overlay will show when factory reset is detected
        isOverlayShowing = false
        isOverlayPending = false
        wasOverlayDismissedByUser = false
        isOnFactoryResetScreen = false
        lastFactoryResetDetectedTime = 0
        lastOverlayShowTime = 0
        currentSettingsPackage = null
        overlayView = null
        
        val info = AccessibilityServiceInfo().apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                   AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                   AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                   AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS or
                   AccessibilityServiceInfo.DEFAULT
            notificationTimeout = 100
        }
        
        serviceInfo = info
        Log.d(TAG, "✅ Service configured with fresh state")
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        hideOverlayDirectly()
        instance = null
        super.onDestroy()
    }
}

