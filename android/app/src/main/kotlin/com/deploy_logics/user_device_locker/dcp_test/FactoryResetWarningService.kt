package com.deploy_logics.user_device_locker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * Service that displays a warning overlay dialog when user attempts to access
 * factory reset settings. This helps prevent unauthorized factory resets.
 */
class FactoryResetWarningService : Service() {

    companion object {
        private const val TAG = "FactoryResetWarning"
        private const val NOTIFICATION_ID = 2001
        private const val CHANNEL_ID = "factory_reset_warning_channel"
        
        // SharedPreferences keys for retailer info
        private const val PREFS_NAME = "FlutterSharedPreferences"
        private const val RETAILER_NAME_KEY = "flutter.retailer_name"
        private const val RETAILER_NAME_URDU_KEY = "flutter.retailer_name_urdu"
        private const val RETAILER_PHONE_KEY = "flutter.retailer_phone"

        var isShowing = false
            private set
        var instance: FactoryResetWarningService? = null
            private set

        private var warningTitle: String = "⚠️ Legal Warning / قانونی انتباہ"
        private var warningMessage: String = ""

        fun show(context: Context, title: String? = null, message: String? = null) {
            Log.d(TAG, "show() called")

            // Check overlay permission first
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
                Log.e(TAG, "ERROR: Cannot draw overlays - permission not granted!")
                return
            }

            if (isShowing) {
                Log.d(TAG, "Warning dialog already showing, ignoring")
                return
            }
            
            // Mark as showing immediately to prevent duplicate calls
            isShowing = true

            // Get retailer info from SharedPreferences
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val retailerName = prefs.getString(RETAILER_NAME_KEY, "") ?: ""
            val retailerNameUrdu = prefs.getString(RETAILER_NAME_URDU_KEY, "") ?: ""
            val retailerPhone = prefs.getString(RETAILER_PHONE_KEY, "") ?: ""
            
            // Build the warning message
            warningTitle = title ?: "⚠️ Legal Warning / قانونی انتباہ"
            warningMessage = message ?: buildWarningMessage(retailerName, retailerNameUrdu, retailerPhone)

            val intent = Intent(context, FactoryResetWarningService::class.java)
            intent.putExtra("title", warningTitle)
            intent.putExtra("message", warningMessage)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                Log.d(TAG, "Service start requested successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Error starting service: ${e.message}")
                isShowing = false
                e.printStackTrace()
            }
        }
        
        private fun buildWarningMessage(retailerName: String, retailerNameUrdu: String, retailerPhone: String): String {
            val sb = StringBuilder()
            
            // English message
            sb.append("You are attempting to make an unauthorized change to this device's security system.\n")
            sb.append("This device is on installments and remains the property of the company until full payment is made.\n")
            sb.append("Any tampering, removing the app, factory reset, or security bypass is a violation of the agreement.\n\n")
            sb.append("In such a case:\n")
            sb.append("• The device may be locked immediately\n")
            sb.append("• Legal action may be taken against you\n\n")
            sb.append("Please stop this action immediately.\n")
            
            sb.append("\n━━━━━━━━━━━━━━━━━━━━━\n\n")
            
            // Urdu message
            sb.append("آپ اس ڈیوائس کے سیکیورٹی سسٹم میں غیر مجاز تبدیلی کرنے کی کوشش کر رہے ہیں۔\n")
            sb.append("یہ ڈیوائس اقساط پر ہے اور مکمل ادائیگی تک کمپنی کی ملکیت ہے۔\n")
            sb.append("کسی بھی قسم کی چھیڑ چھاڑ، ایپ کو ہٹانے، فیکٹری ری سیٹ یا سیکیورٹی بائی پاس کرنا معاہدے کی خلاف ورزی ہے۔\n\n")
            sb.append("ایسی صورت میں:\n")
            sb.append("• ڈیوائس فوری طور پر لاک کی جا سکتی ہے\n")
            sb.append("• آپ کے خلاف قانونی کارروائی کی جا سکتی ہے\n\n")
            sb.append("براہ کرم فوراً یہ عمل روک دیں۔")
            
            // Add retailer info if available
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

        fun hide(context: Context) {
            Log.d(TAG, "hide() called")

            try {
                instance?.removeOverlayView()
            } catch (e: Exception) {
                Log.e(TAG, "Error removing overlay view: ${e.message}")
            }

            isShowing = false
            instance = null

            try {
                val intent = Intent(context, FactoryResetWarningService::class.java)
                context.stopService(intent)
                Log.d(TAG, "Service stopped")
            } catch (e: Exception) {
                Log.e(TAG, "Error stopping service: ${e.message}")
            }
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate()")
        instance = this
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Factory Reset Warning",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows warning when factory reset is attempted"
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Security Alert")
            .setContentText("Factory reset protection active")
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand()")

        // Start as foreground service
        startForeground(NOTIFICATION_ID, createNotification())

        val title = intent?.getStringExtra("title") ?: warningTitle
        val message = intent?.getStringExtra("message") ?: warningMessage

        if (!isShowing) {
            showWarningDialog(title, message)
        }

        return START_STICKY
    }

    private fun showWarningDialog(title: String, message: String) {
        Log.d(TAG, "showWarningDialog()")

        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

        // Create the main container with semi-transparent background
        val container = object : FrameLayout(this) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                    Log.d(TAG, "Back button pressed, hiding overlay")
                    hide(this@FactoryResetWarningService)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply {
            setBackgroundColor(Color.parseColor("#CC000000")) // Darker semi-transparent black
            isFocusableInTouchMode = true
            isFocusable = true
        }

        // Create dialog card with reduced padding
        val dialogCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(36, 28, 36, 28)

            // White rounded card background
            val cardBackground = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 24f
            }
            background = cardBackground
        }

        // Close button (X) at the top right
        val closeButtonContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        val closeButton = TextView(this).apply {
            text = "✕"
            textSize = 22f
            setTextColor(Color.parseColor("#666666"))
            setPadding(12, 0, 0, 0)
            
            val closeParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.TOP
            }
            layoutParams = closeParams
            
            setOnClickListener {
                Log.d(TAG, "Close button clicked")
                hide(this@FactoryResetWarningService)
            }
        }
        closeButtonContainer.addView(closeButton)

        // Warning icon - smaller size
        val warningIcon = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_dialog_alert)
            setColorFilter(Color.parseColor("#FF5722")) // Orange/red color
            val iconSize = (50 * resources.displayMetrics.density).toInt()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = (8 * resources.displayMetrics.density).toInt()
            }
        }

        // Title - smaller margin
        val titleView = TextView(this).apply {
            text = title
            textSize = 18f
            setTextColor(Color.parseColor("#D32F2F")) // Red color
            gravity = Gravity.CENTER
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (12 * resources.displayMetrics.density).toInt()
            }
        }

        // Message in ScrollView - increased height, reduced line spacing
        val scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = true
            // Set max height for scroll area - 50% of screen
            val maxHeight = (resources.displayMetrics.heightPixels * 0.50).toInt()
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
            setLineSpacing(0f, 1.15f) // Reduced line spacing
            setPadding(
                (4 * resources.displayMetrics.density).toInt(),
                0,
                (4 * resources.displayMetrics.density).toInt(),
                0
            )
        }
        
        scrollView.addView(messageView)

        // OK Button - smaller padding
        val okButton = Button(this).apply {
            text = "I UNDERSTAND / میں سمجھتا ہوں"
            textSize = 13f
            setTextColor(Color.WHITE)
            setTypeface(typeface, android.graphics.Typeface.BOLD)

            val buttonBackground = GradientDrawable().apply {
                setColor(Color.parseColor("#D32F2F"))
                cornerRadius = 20f
            }
            background = buttonBackground

            setPadding(
                (24 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt(),
                (24 * resources.displayMetrics.density).toInt(),
                (12 * resources.displayMetrics.density).toInt()
            )

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            setOnClickListener {
                Log.d(TAG, "OK button clicked")
                hide(this@FactoryResetWarningService)
            }
        }

        // Add views to dialog card
        dialogCard.addView(closeButtonContainer)
        dialogCard.addView(warningIcon)
        dialogCard.addView(titleView)
        dialogCard.addView(scrollView)
        dialogCard.addView(okButton)

        // Set dialog card size and position - 90% width
        val dialogParams = FrameLayout.LayoutParams(
            (resources.displayMetrics.widthPixels * 0.92).toInt(),
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
        }
        dialogCard.layoutParams = dialogParams

        container.addView(dialogCard)

        // Handle click outside to dismiss the overlay
        container.setOnClickListener {
            Log.d(TAG, "Tapped outside dialog, hiding overlay")
            hide(this)
        }
        
        // Prevent clicks on the dialog from propagating to the container
        dialogCard.setOnClickListener {
            // Do nothing - consume click to prevent dismissing
        }

        overlayView = container

        // Window parameters - Use TYPE_ACCESSIBILITY_OVERLAY for highest z-order (above Settings)
        // This requires accessibility service to be enabled
        val layoutType = when {
            // API 22+ : TYPE_ACCESSIBILITY_OVERLAY has highest z-order (above everything)
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1 -> {
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.O -> {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            }
            else -> {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_ERROR // Highest priority for older APIs
            }
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            layoutType,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.CENTER

        // Use Handler to post the view addition to ensure it happens on main thread
        Handler(Looper.getMainLooper()).post {
            try {
                windowManager?.addView(container, params)
                isShowing = true
                Log.d(TAG, "Warning dialog shown successfully with TYPE: $layoutType")
            } catch (e: Exception) {
                Log.e(TAG, "Error showing warning dialog: ${e.message}")
                e.printStackTrace()
                
                // Fallback to TYPE_APPLICATION_OVERLAY if TYPE_ACCESSIBILITY_OVERLAY fails
                if (layoutType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) {
                    Log.d(TAG, "Retrying with TYPE_APPLICATION_OVERLAY...")
                    try {
                        val fallbackType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        } else {
                            @Suppress("DEPRECATION")
                            WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                        }
                        params.type = fallbackType
                        windowManager?.addView(container, params)
                        isShowing = true
                        Log.d(TAG, "Warning dialog shown with fallback TYPE: $fallbackType")
                    } catch (e2: Exception) {
                        Log.e(TAG, "Fallback also failed: ${e2.message}")
                    }
                }
            }
        }
    }

    fun removeOverlayView() {
        Log.d(TAG, "removeOverlayView()")
        try {
            overlayView?.let {
                windowManager?.removeView(it)
                overlayView = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error removing overlay view: ${e.message}")
        }
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy()")
        removeOverlayView()
        isShowing = false
        instance = null
        super.onDestroy()
    }
}



