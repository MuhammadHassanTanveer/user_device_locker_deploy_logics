package com.deploy_logics.user_device_locker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.net.wifi.WifiManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import android.widget.*
import com.google.firebase.messaging.FirebaseMessaging

/**
 * Full-screen Lock Activity that uses Lock Task Mode (Kiosk Mode)
 * Design matches the Flutter OverlayLockScreen
 */
class LockActivity : Activity() {

    companion object {
        private const val TAG = "LockActivity"
        private const val PREFS_NAME = "FlutterSharedPreferences"
        private const val UNLOCK_CODE_KEY = "flutter.unlock_code"
        private const val LOCK_CODE_KEY = "flutter.lock_code"
        private const val LOCK_PIN_KEY = "flutter.lock_pin"
        private const val PIN_LENGTH = 6
        private const val DEVICE_LOCKED_KEY = "flutter.device_locked"
        private const val RETAILER_ID_KEY = "flutter.retailer_id"
        private const val RETAILER_NAME_KEY = "flutter.retailer_name"
        private const val RETAILER_NAME_URDU_KEY = "flutter.retailer_name_urdu"
        private const val RETAILER_PHONE_KEY = "flutter.retailer_phone"
        private const val RETAILER_COMPANY_CODE_KEY = "flutter.retailer_company_code"
        private const val USER_ID_KEY = "flutter.registered_device_id"
        private const val WIFI_SETTINGS_REQUEST_CODE = 1001
        private const val ACTION_UNLOCK = "com.deploy_logics.user_device_locker.ACTION_UNLOCK_ACTIVITY"

        // Static instance for external access
        private var currentInstance: LockActivity? = null

        fun start(context: Context) {
            Log.d(TAG, "Starting LockActivity...")
            val intent = Intent(context, LockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                addFlags(Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
            }
            context.startActivity(intent)
        }

        fun isLocked(context: Context): Boolean {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getBoolean(DEVICE_LOCKED_KEY, false)
        }

        fun setLocked(context: Context, locked: Boolean) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putBoolean(DEVICE_LOCKED_KEY, locked).commit()

            // If unlocking, also finish the current instance
            if (!locked) {
                Log.d(TAG, "setLocked(false) called - finishing LockActivity instance")
                finishLockActivity(context)
            }
        }

        /**
         * Finish the LockActivity if it's running.
         * This is called when an unlock command is received.
         */
        fun finishLockActivity(context: Context) {
            Log.d(TAG, "finishLockActivity() called - currentInstance: $currentInstance")

            // Method 1: Use static instance
            currentInstance?.let { activity ->
                try {
                    Log.d(TAG, "Finishing LockActivity via static instance...")
                    activity.unlockAndFinish()
                    Log.d(TAG, "✅ LockActivity finish requested via instance")
                } catch (e: Exception) {
                    Log.e(TAG, "Error finishing via instance: ${e.message}")
                }
            }

            // Method 2: Send broadcast as backup
            try {
                val intent = Intent(ACTION_UNLOCK)
                intent.setPackage(context.packageName)
                context.sendBroadcast(intent)
                Log.d(TAG, "✅ Unlock broadcast sent")
            } catch (e: Exception) {
                Log.e(TAG, "Error sending unlock broadcast: ${e.message}")
            }
        }
    }

    private lateinit var dpmHelper: DevicePolicyManagerHelper
    private lateinit var mainContainer: FrameLayout
    private lateinit var pinDialogContainer: FrameLayout
    private lateinit var fcmDialogContainer: FrameLayout
    private lateinit var fcmTokenTextView: TextView
    private lateinit var pinEditText: EditText
    private lateinit var errorTextView: TextView
    private var isPinDialogShowing = false
    private var isOpeningWifiSettings = false  // Track when WiFi settings is being opened
    private var isUnlocking = false  // Track when we're unlocking to prevent restart

    // Broadcast receiver for unlock command
    private val unlockReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_UNLOCK) {
                Log.d(TAG, "📻 Unlock broadcast received!")
                unlockAndFinish()
            }
        }
    }

    // Colors matching Flutter design
    private val colorBackground1 = Color.parseColor("#1a1a2e")
    private val colorBackground2 = Color.parseColor("#16213e")
    private val colorAccent = Color.parseColor("#e94560")
    private val colorCardBg = Color.parseColor("#2a2a4a")
    private val colorCardBorder = Color.parseColor("#444466")
    private val colorTextPrimary = Color.WHITE
    private val colorTextSecondary = Color.parseColor("#888888")
    private val colorTextMuted = Color.parseColor("#666666")
    private val colorError = Color.parseColor("#ff5555")
    private val colorSuccess = Color.parseColor("#4CAF50")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate() called")

        // Set current instance for external access
        currentInstance = this

        // Register unlock broadcast receiver
        try {
            val filter = android.content.IntentFilter(ACTION_UNLOCK)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(unlockReceiver, filter, RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(unlockReceiver, filter)
            }
            Log.d(TAG, "✅ Unlock receiver registered")
        } catch (e: Exception) {
            Log.e(TAG, "Error registering unlock receiver: ${e.message}")
        }

        dpmHelper = DevicePolicyManagerHelper(this)
        setupFullscreenMode()
        createUI()
        startLockTaskModeIfOwner()
        disableStatusBar()
    }

    private fun setupFullscreenMode() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.addFlags(WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            window.statusBarColor = colorBackground1
            window.navigationBarColor = colorBackground1
        }
    }

    private fun dp(value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            resources.displayMetrics
        ).toInt()
    }

    private fun createUI() {
        // Main FrameLayout to hold content and PIN dialog overlay
        mainContainer = FrameLayout(this)

        // Gradient background
        val gradientDrawable = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(colorBackground1, colorBackground2)
        )
        mainContainer.background = gradientDrawable

        // ScrollView for main content
        val scrollView = ScrollView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            isFillViewport = true
            isVerticalScrollBarEnabled = true
        }

        // Main content layout - must have WRAP_CONTENT height for scrolling
        val contentLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            setPadding(dp(32), dp(48), dp(32), dp(48))
        }

        // Lock Icon Container (Red circle with lock icon)
        val lockIconContainer = FrameLayout(this).apply {
            val size = dp(120)
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            val circleDrawable = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(colorAccent)
            }
            background = circleDrawable
        }

        val lockIcon = TextView(this).apply {
            text = "🔒"
            textSize = 48f
            gravity = Gravity.CENTER
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        lockIconContainer.addView(lockIcon)
        contentLayout.addView(lockIconContainer)

        // Spacer
        contentLayout.addView(createSpacer(40))

        // Title
        contentLayout.addView(TextView(this).apply {
            text = "Device Locked"
            setTextColor(colorTextPrimary)
            textSize = 32f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            letterSpacing = 0.05f
        })

        contentLayout.addView(createSpacer(16))

        // Subtitle English
        contentLayout.addView(TextView(this).apply {
            text = "This device is locked by admin"
            setTextColor(Color.argb(178, 255, 255, 255)) // 70% white
            textSize = 16f
            gravity = Gravity.CENTER
        })

        contentLayout.addView(createSpacer(8))

        // Subtitle Urdu
        contentLayout.addView(TextView(this).apply {
            text = "یہ ڈیوائس ایڈمن کی طرف سے لاک ہے"
            setTextColor(Color.argb(153, 255, 255, 255)) // 60% white
            textSize = 14f
            gravity = Gravity.CENTER
        })

        contentLayout.addView(createSpacer(32))

        // User ID Card
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val userId = try {
            prefs.getLong(USER_ID_KEY, 0L).toString()
        } catch (e: Exception) {
            prefs.getString(USER_ID_KEY, "") ?: ""
        }

        if (userId.isNotEmpty() && userId != "0") {
            contentLayout.addView(createUserIdCard(userId))
            contentLayout.addView(createSpacer(24))
        }

        // Retailer Info Card
        val retailerId = prefs.getString(RETAILER_ID_KEY, "") ?: ""
        val retailerCompanyCode = prefs.getString(RETAILER_COMPANY_CODE_KEY, "") ?: ""
        val retailerName = prefs.getString(RETAILER_NAME_KEY, "") ?: ""
        val retailerNameUrdu = prefs.getString(RETAILER_NAME_URDU_KEY, "") ?: ""
        val retailerPhone = prefs.getString(RETAILER_PHONE_KEY, "") ?: ""

        if (retailerId.isNotEmpty() || retailerCompanyCode.isNotEmpty() || retailerName.isNotEmpty() || retailerPhone.isNotEmpty()) {
            contentLayout.addView(
                createRetailerInfoCard(
                    retailerId,
                    retailerCompanyCode,
                    retailerName,
                    retailerNameUrdu,
                    retailerPhone
                )
            )
            contentLayout.addView(createSpacer(32))
        }

        // Unlock Button
        contentLayout.addView(createUnlockButton())

        // WiFi and FCM Buttons - Same row
        contentLayout.addView(createSpacer(24))
        contentLayout.addView(createWifiAndFcmButtonsRow())

        // Footer
        contentLayout.addView(createSpacer(32))
        contentLayout.addView(createFooter())

        // Add extra space at the bottom for scrolling
        contentLayout.addView(createSpacer(24))

        scrollView.addView(contentLayout)
        mainContainer.addView(scrollView)

        // PIN Dialog Container (initially hidden)
        pinDialogContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.argb(178, 0, 0, 0)) // 70% black overlay
            visibility = View.GONE
            isClickable = true
        }
        pinDialogContainer.addView(createPinDialog())
        mainContainer.addView(pinDialogContainer)

        // FCM Dialog Container (initially hidden)
        fcmDialogContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.argb(178, 0, 0, 0)) // 70% black overlay
            visibility = View.GONE
            isClickable = true
            setOnClickListener { hideFcmDialog() } // Dismiss when clicking outside
        }
        fcmDialogContainer.addView(createFcmDialog())
        mainContainer.addView(fcmDialogContainer)

        setContentView(mainContainer)
    }

    private fun createSpacer(heightDp: Int): View {
        return View(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(heightDp)
            )
        }
    }

    /**
     * Creates the footer with "Powered by Deploy Logics" and "deploylogics.com"
     */
    private fun createFooter(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // "Powered by Deploy Logics" text
            addView(TextView(this@LockActivity).apply {
                text = "Powered by Deploy Logics"
                setTextColor(Color.argb(153, 255, 255, 255)) // 60% white
                textSize = 12f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.ITALIC)
            })

            // Small spacer
            addView(View(this@LockActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(4)
                )
            })

            // "deploylogics.com" text
            addView(TextView(this@LockActivity).apply {
                text = "deploylogics.com"
                setTextColor(Color.argb(153, 255, 255, 255)) // 60% white
                textSize = 12f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.ITALIC)
            })
        }
    }

    private fun createUserIdCard(userId: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(12), dp(24), dp(12))

            val cardDrawable = GradientDrawable().apply {
                setColor(colorCardBg)
                cornerRadius = dp(24).toFloat()
            }
            background = cardDrawable

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }

            // Person icon
            addView(TextView(this@LockActivity).apply {
                text = "👤"
                textSize = 16f
            })

            addView(View(this@LockActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), 0)
            })

            addView(TextView(this@LockActivity).apply {
                text = "User ID: "
                setTextColor(Color.argb(153, 255, 255, 255))
                textSize = 14f
            })

            addView(TextView(this@LockActivity).apply {
                text = userId
                setTextColor(colorTextPrimary)
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
            })
        }
    }

    private fun createRetailerInfoCard(
        retailerId: String,
        retailerCompanyCode: String,
        retailerName: String,
        retailerNameUrdu: String,
        retailerPhone: String
    ): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))

            val cardDrawable = GradientDrawable().apply {
                setColor(colorCardBg)
                cornerRadius = dp(16).toFloat()
                setStroke(dp(1), colorCardBorder)
            }
            background = cardDrawable

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // Header
            addView(LinearLayout(this@LockActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL

                addView(TextView(this@LockActivity).apply {
                    text = "🏪"
                    textSize = 16f
                })

                addView(View(this@LockActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(8), 0)
                })

                addView(TextView(this@LockActivity).apply {
                    text = "Retailer Information"
                    setTextColor(Color.argb(230, 255, 255, 255))
                    textSize = 14f
                    typeface = Typeface.DEFAULT_BOLD
                })

                addView(View(this@LockActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(6), 0)
                })

                addView(TextView(this@LockActivity).apply {
                    text = "|"
                    setTextColor(Color.argb(102, 255, 255, 255))
                    textSize = 14f
                })

                addView(View(this@LockActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(6), 0)
                })

                addView(TextView(this@LockActivity).apply {
                    text = "ریٹیلر کی معلومات"
                    setTextColor(Color.argb(178, 255, 255, 255))
                    textSize = 13f
                    typeface = Typeface.DEFAULT_BOLD
                })
            })

            addView(createSpacer(12))

            // Divider
            addView(View(this@LockActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(1)
                )
                setBackgroundColor(colorCardBorder)
            })

            addView(createSpacer(12))

            // Retailer ID
            if (retailerId.isNotEmpty() && retailerId != "null") {
                addView(createInfoRow("🪪", "Retailer ID", retailerId, "ریٹیلر آئی ڈی"))
            }

            // Retailer / company code (CU… style)
            if (retailerCompanyCode.isNotEmpty() && retailerCompanyCode != "null") {
                addView(createInfoRow("🏷", "Company Code", retailerCompanyCode, "کمپنی کوڈ"))
            }

            // Retailer Name
            val nameValue = when {
                retailerName.isNotEmpty() && retailerName != "null" &&
                retailerNameUrdu.isNotEmpty() && retailerNameUrdu != "null" ->
                    "$retailerName  $retailerNameUrdu"
                retailerName.isNotEmpty() && retailerName != "null" -> retailerName
                retailerNameUrdu.isNotEmpty() && retailerNameUrdu != "null" -> retailerNameUrdu
                else -> ""
            }
            if (nameValue.isNotEmpty()) {
                addView(createInfoRow("👤", "Name", nameValue, "نام"))
            }

            // Retailer Phone
            if (retailerPhone.isNotEmpty() && retailerPhone != "null") {
                addView(createInfoRow("📞", "Phone", retailerPhone, "فون"))
            }
        }
    }

    private fun createInfoRow(icon: String, label: String, value: String, labelUrdu: String): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, dp(6))

            addView(TextView(this@LockActivity).apply {
                text = icon
                textSize = 14f
            })

            addView(View(this@LockActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(10), 0)
            })

            addView(TextView(this@LockActivity).apply {
                text = "$label: "
                setTextColor(Color.argb(153, 255, 255, 255))
                textSize = 13f
            })

            addView(TextView(this@LockActivity).apply {
                text = value
                setTextColor(colorTextPrimary)
                textSize = 13f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })

            addView(View(this@LockActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), 0)
            })

            addView(TextView(this@LockActivity).apply {
                text = labelUrdu
                setTextColor(Color.argb(128, 255, 255, 255))
                textSize = 12f
            })
        }
    }

    private fun createUnlockButton(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(24), dp(18), dp(24), dp(18))

            val buttonDrawable = GradientDrawable().apply {
                setColor(colorAccent)
                cornerRadius = dp(30).toFloat()
            }
            background = buttonDrawable

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            isClickable = true
            isFocusable = true
            setOnClickListener { showPinDialog() }

            addView(TextView(this@LockActivity).apply {
                text = "🔓"
                textSize = 18f
            })

            addView(View(this@LockActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(12), 0)
            })

            addView(TextView(this@LockActivity).apply {
                text = "Enter PIN to Unlock"
                setTextColor(colorTextPrimary)
                textSize = 16f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            })
        }
    }

    /**
     * Creates a row with WiFi and FCM buttons side by side
     */
    private fun createWifiAndFcmButtonsRow(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // Buttons Row
            addView(LinearLayout(this@LockActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                )

                // WiFi Button
                addView(createWifiButton())

                // Spacer between buttons
                addView(View(this@LockActivity).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(12), 0)
                })

                // FCM Button
                addView(createFcmButton())
            })

            // Spacer
            addView(View(this@LockActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(8)
                )
            })

            // WiFi Status Text
            addView(TextView(this@LockActivity).apply {
                text = getWifiStatusText()
                setTextColor(colorTextSecondary)
                textSize = 12f
                gravity = Gravity.CENTER
            })
        }
    }

    /**
     * Creates the WiFi section with button and status text
     */
    private fun createWifiSection(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // WiFi Button
            addView(createWifiButton())

            // Spacer
            addView(View(this@LockActivity).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp(8)
                )
            })

            // WiFi Status Text
            addView(TextView(this@LockActivity).apply {
                text = getWifiStatusText()
                setTextColor(colorTextSecondary)
                textSize = 12f
                gravity = Gravity.CENTER
            })
        }
    }

    /**
     * Creates the WiFi settings button
     */
    private fun createWifiButton(): LinearLayout {
        val colorWifi = Color.parseColor("#3498db") // Blue color for WiFi

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(14), dp(20), dp(14))

            val buttonDrawable = GradientDrawable().apply {
                setColor(colorWifi)
                cornerRadius = dp(30).toFloat()
            }
            background = buttonDrawable

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }

            isClickable = true
            isFocusable = true
            setOnClickListener { openWifiSettings() }

            addView(TextView(this@LockActivity).apply {
                text = "📶"
                textSize = 16f
            })

            addView(View(this@LockActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), 0)
            })

            addView(TextView(this@LockActivity).apply {
                text = "WiFi Settings"
                setTextColor(colorTextPrimary)
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            })
        }
    }

    /**
     * Gets the current WiFi connection status text
     */
    private fun getWifiStatusText(): String {
        return try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = connectivityManager.activeNetwork
                val capabilities = connectivityManager.getNetworkCapabilities(network)

                if (capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                    @Suppress("DEPRECATION")
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
     * Creates the FCM section with button
     */
    private fun createFcmSection(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            // FCM Button
            addView(createFcmButton())
        }
    }

    /**
     * Creates the FCM token button
     */
    private fun createFcmButton(): LinearLayout {
        val colorFcm = Color.parseColor("#FF9800") // Orange color for FCM

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(20), dp(14), dp(20), dp(14))

            val buttonDrawable = GradientDrawable().apply {
                setColor(colorFcm)
                cornerRadius = dp(30).toFloat()
            }
            background = buttonDrawable

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }

            isClickable = true
            isFocusable = true
            setOnClickListener { showFcmTokenDialog() }

            addView(TextView(this@LockActivity).apply {
                text = "🔔"
                textSize = 16f
            })

            addView(View(this@LockActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), 0)
            })

            addView(TextView(this@LockActivity).apply {
                text = "FCM"
                setTextColor(colorTextPrimary)
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            })
        }
    }

    /**
     * Creates the FCM token dialog
     */
    private fun createFcmDialog(): FrameLayout {
        val dialogContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                setMargins(dp(24), 0, dp(24), 0)
            }
            // Stop click propagation to prevent dismissing when clicking dialog content
            setOnClickListener { /* consume click */ }
        }

        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))

            val dialogDrawable = GradientDrawable().apply {
                setColor(colorCardBg)
                cornerRadius = dp(20).toFloat()
            }
            background = dialogDrawable

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Dialog Title
        dialogContent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER

            addView(TextView(this@LockActivity).apply {
                text = "🔔"
                textSize = 20f
            })

            addView(View(this@LockActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), 0)
            })

            addView(TextView(this@LockActivity).apply {
                text = "FCM Token"
                setTextColor(colorTextPrimary)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
            })
        })

        dialogContent.addView(createSpacer(8))

        // Subtitle
        dialogContent.addView(TextView(this).apply {
            text = "Your Firebase Cloud Messaging Token"
            setTextColor(colorTextSecondary)
            textSize = 12f
            gravity = Gravity.CENTER
        })

        dialogContent.addView(createSpacer(16))

        // FCM Token container with background
        val tokenContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(12))

            val tokenBgDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#1a1a2e"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), colorCardBorder)
            }
            background = tokenBgDrawable

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // FCM Token Text
        fcmTokenTextView = TextView(this).apply {
            text = "Loading..."
            setTextColor(colorTextPrimary)
            textSize = 11f
            gravity = Gravity.START
            setTextIsSelectable(true) // Allow text selection for copying
            maxLines = 8
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        tokenContainer.addView(fcmTokenTextView)

        dialogContent.addView(tokenContainer)

        dialogContent.addView(createSpacer(16))

        // Buttons Row
        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        // Copy Button
        buttonsRow.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))

            val copyDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#3498db"))
                cornerRadius = dp(10).toFloat()
            }
            background = copyDrawable

            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(6), 0)
            }

            isClickable = true
            isFocusable = true
            setOnClickListener { copyFcmTokenToClipboard() }

            addView(TextView(this@LockActivity).apply {
                text = "📋 Copy"
                setTextColor(colorTextPrimary)
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            })
        })

        // Close Button
        buttonsRow.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))

            val closeDrawable = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), colorTextSecondary)
            }
            background = closeDrawable

            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(6), 0, 0, 0)
            }

            isClickable = true
            isFocusable = true
            setOnClickListener { hideFcmDialog() }

            addView(TextView(this@LockActivity).apply {
                text = "Close"
                setTextColor(colorTextSecondary)
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            })
        })

        dialogContent.addView(buttonsRow)
        dialogContainer.addView(dialogContent)

        return dialogContainer
    }

    /**
     * Shows the FCM token dialog and fetches the token
     */
    private fun showFcmTokenDialog() {
        Log.d(TAG, "Showing FCM token dialog")
        fcmDialogContainer.visibility = View.VISIBLE
        fcmTokenTextView.text = "Loading..."

        // Get FCM token
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                Log.d(TAG, "FCM Token: $token")
                runOnUiThread {
                    fcmTokenTextView.text = token ?: "Token not available"
                }
            } else {
                Log.e(TAG, "Failed to get FCM token: ${task.exception?.message}")
                runOnUiThread {
                    fcmTokenTextView.text = "Failed to get token: ${task.exception?.message}"
                }
            }
        }
    }

    /**
     * Hides the FCM token dialog
     */
    private fun hideFcmDialog() {
        fcmDialogContainer.visibility = View.GONE
    }

    /**
     * Copies the FCM token to clipboard
     */
    private fun copyFcmTokenToClipboard() {
        val token = fcmTokenTextView.text.toString()
        if (token.isNotEmpty() && token != "Loading..." && !token.startsWith("Failed")) {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            val clip = android.content.ClipData.newPlainText("FCM Token", token)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Token copied to clipboard", Toast.LENGTH_SHORT).show()
            Log.d(TAG, "FCM token copied to clipboard")
        } else {
            Toast.makeText(this, "No valid token to copy", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * Opens WiFi settings directly from LockActivity
     * Since we're in Lock Task Mode, we need to:
     * 1. Stop lock task mode
     * 2. Unhide settings app
     * 3. Launch WifiSettingsActivity which opens WiFi settings
     * 4. Finish this activity so WiFi settings is visible
     * 5. WifiSettingsActivity restarts LockActivity when user is done
     */
    private fun openWifiSettings() {
        Log.d(TAG, "Opening WiFi settings from LockActivity")
        isOpeningWifiSettings = true  // Set flag to prevent onPause from restarting lock

        try {
            if (dpmHelper.isDeviceOwner()) {
                // Step 1: Stop lock task mode temporarily
                try {
                    stopLockTask()
                    Log.d(TAG, "Stopped lock task mode temporarily for WiFi settings")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop lock task: ${e.message}")
                }

                // Step 2: Temporarily unhide settings app to allow WiFi settings access
                try {
                    dpmHelper.temporarilyAllowWifiSettings()
                    Log.d(TAG, "Temporarily allowed WiFi settings access")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to allow WiFi settings: ${e.message}")
                }
            }

            // Get the current PIN from SharedPreferences
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val pin = UnlockCodeApi.getStoredUnlockCode(prefs).ifEmpty { "000000" }

            // Step 3: Launch WifiSettingsActivity with proper flags to bring it to front
            Log.d(TAG, "Launching WifiSettingsActivity")
            val wifiActivityIntent = Intent(this, WifiSettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(WifiSettingsActivity.EXTRA_USER_ID, "")
                putExtra(WifiSettingsActivity.EXTRA_PIN, pin)
            }
            startActivity(wifiActivityIntent)

            // Step 4: Finish this activity so WiFi settings is fully visible
            Log.d(TAG, "Finishing LockActivity for WiFi settings")
            finish()

        } catch (e: Exception) {
            Log.e(TAG, "Error opening WiFi settings: ${e.message}")
            e.printStackTrace()
            isOpeningWifiSettings = false
            Toast.makeText(this, "Unable to open WiFi settings", Toast.LENGTH_SHORT).show()
            // Restart lock task mode if failed
            restartLockTaskMode()
        }
    }

    private fun openRegularWifiSettings() {
        Log.d(TAG, "Opening regular WiFi settings")
        try {
            val wifiIntent = Intent(Settings.ACTION_WIFI_SETTINGS)
            wifiIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivityForResult(wifiIntent, WIFI_SETTINGS_REQUEST_CODE)
            Log.d(TAG, "Regular WiFi settings started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open regular WiFi settings: ${e.message}")
            // Last resort - try wireless settings
            try {
                val wirelessIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                startActivityForResult(wirelessIntent, WIFI_SETTINGS_REQUEST_CODE)
                Log.d(TAG, "Wireless settings started as fallback")
            } catch (e2: Exception) {
                Log.e(TAG, "All WiFi settings methods failed: ${e2.message}")
                Toast.makeText(this, "Cannot open WiFi settings", Toast.LENGTH_SHORT).show()
                // Restart lock task mode if all fails
                restartLockTaskMode()
            }
        }
    }

    private fun restartLockTaskMode() {
        if (dpmHelper.isDeviceOwner()) {
            // Re-block settings access (re-hide settings app)
            try {
                dpmHelper.reblockWifiSettings()
                Log.d(TAG, "Re-blocked WiFi settings access")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to re-block WiFi settings: ${e.message}")
            }

            try {
                startLockTask()
                Log.d(TAG, "Lock task mode restarted")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restart lock task mode: ${e.message}")
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        Log.d(TAG, "onActivityResult - requestCode: $requestCode, resultCode: $resultCode")

        if (requestCode == WIFI_SETTINGS_REQUEST_CODE) {
            Log.d(TAG, "Returned from WiFi settings, restarting lock task mode")
            isOpeningWifiSettings = false  // Reset flag
            // Restart lock task mode after returning from WiFi settings
            restartLockTaskMode()
        }
    }

    private fun createPinDialog(): FrameLayout {
        val dialogContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                setMargins(dp(32), 0, dp(32), 0)
            }
        }

        val dialogContent = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))

            val dialogDrawable = GradientDrawable().apply {
                setColor(colorCardBg)
                cornerRadius = dp(20).toFloat()
            }
            background = dialogDrawable

            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }

        // Dialog Title
        dialogContent.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER

            addView(TextView(this@LockActivity).apply {
                text = "🔢"
                textSize = 20f
            })

            addView(View(this@LockActivity).apply {
                layoutParams = LinearLayout.LayoutParams(dp(8), 0)
            })

            addView(TextView(this@LockActivity).apply {
                text = "Enter PIN"
                setTextColor(colorTextPrimary)
                textSize = 20f
                typeface = Typeface.DEFAULT_BOLD
            })
        })

        dialogContent.addView(createSpacer(8))

        // Subtitle
        dialogContent.addView(TextView(this).apply {
            text = "Enter your PIN code to unlock"
            setTextColor(colorTextSecondary)
            textSize = 14f
            gravity = Gravity.CENTER
        })

        dialogContent.addView(createSpacer(24))

        // PIN Input (6 digits)
        pinEditText = EditText(this).apply {
            hint = "Enter 6-digit code"
            setHintTextColor(colorTextMuted)
            setTextColor(colorTextPrimary)
            textSize = 24f
            gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(PIN_LENGTH))
            setPadding(dp(16), dp(16), dp(16), dp(16))

            val inputDrawable = GradientDrawable().apply {
                setColor(Color.parseColor("#1a1a2e"))
                cornerRadius = dp(12).toFloat()
                setStroke(dp(1), colorCardBorder)
            }
            background = inputDrawable

            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )

            imeOptions = EditorInfo.IME_ACTION_DONE
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    verifyPin()
                    true
                } else false
            }
        }
        dialogContent.addView(pinEditText)

        dialogContent.addView(createSpacer(12))

        // Error Text
        errorTextView = TextView(this).apply {
            text = ""
            setTextColor(colorError)
            textSize = 14f
            gravity = Gravity.CENTER
        }
        dialogContent.addView(errorTextView)

        dialogContent.addView(createSpacer(20))

        // Buttons Row
        val buttonsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        // Cancel Button
        buttonsRow.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))

            val cancelDrawable = GradientDrawable().apply {
                setColor(Color.TRANSPARENT)
                cornerRadius = dp(10).toFloat()
                setStroke(dp(1), colorTextSecondary)
            }
            background = cancelDrawable

            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, dp(6), 0)
            }

            isClickable = true
            isFocusable = true
            setOnClickListener { hidePinDialog() }

            addView(TextView(this@LockActivity).apply {
                text = "Cancel"
                setTextColor(colorTextSecondary)
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            })
        })

        // Unlock Button
        buttonsRow.addView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))

            val unlockDrawable = GradientDrawable().apply {
                setColor(colorSuccess)
                cornerRadius = dp(10).toFloat()
            }
            background = unlockDrawable

            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(6), 0, 0, 0)
            }

            isClickable = true
            isFocusable = true
            setOnClickListener { verifyPin() }

            addView(TextView(this@LockActivity).apply {
                text = "Unlock"
                setTextColor(colorTextPrimary)
                textSize = 14f
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            })
        })

        dialogContent.addView(buttonsRow)
        dialogContainer.addView(dialogContent)

        return dialogContainer
    }

    private fun showPinDialog() {
        isPinDialogShowing = true
        pinDialogContainer.visibility = View.VISIBLE
        pinEditText.text.clear()
        errorTextView.text = ""
        pinEditText.requestFocus()
    }

    private fun hidePinDialog() {
        isPinDialogShowing = false
        pinDialogContainer.visibility = View.GONE
        pinEditText.text.clear()
        errorTextView.text = ""
    }

    private fun startLockTaskModeIfOwner() {
        if (dpmHelper.isDeviceOwner()) {
            try {
                dpmHelper.setLockTaskPackages(arrayOf(packageName))
                Log.d(TAG, "✅ Set lock task packages")

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpmHelper.setLockTaskFeatures(0)
                    Log.d(TAG, "✅ Set lock task features to 0")
                }

                startLockTask()
                Log.d(TAG, "✅ Started Lock Task Mode")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error starting lock task mode: ${e.message}")
            }
        }
    }

    private fun disableStatusBar() {
        if (dpmHelper.isDeviceOwner()) {
            try {
                dpmHelper.setStatusBarDisabled(true)
                Log.d(TAG, "✅ Status bar disabled")
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error disabling status bar: ${e.message}")
            }
        }
    }

    private fun generateNewPin(): String = UnlockCodeApi.generateUnlockCode()

    private fun updatePinInSharedPreferences(newPin: String) {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        UnlockCodeApi.saveUnlockCodeLocally(prefs, newPin)
        Log.d(TAG, "🔑 Updated unlock code in SharedPreferences: $newPin")
    }

    private fun verifyPin() {
        val enteredPin = pinEditText.text.toString().trim()
        Log.d(TAG, "Verifying PIN...")

        if (enteredPin.length != PIN_LENGTH) {
            errorTextView.text = "Please enter a $PIN_LENGTH-digit code"
            return
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedPin = UnlockCodeApi.getStoredUnlockCode(prefs)

        Log.d(TAG, "Entered: $enteredPin, Stored: $storedPin")

        if (enteredPin == storedPin) {
            Log.d(TAG, "✅ Unlock code matches!")
            val newPin = generateNewPin()
            updatePinInSharedPreferences(newPin)

            Log.d(TAG, "📤 Calling update_unlock_code API with new code")
            UnlockCodeApi.updateUnlockCode(applicationContext, newPin)

            unlockDevice()
        } else {
            Log.d(TAG, "❌ Wrong unlock code")
            errorTextView.text = "Wrong code. Please try again."
            pinEditText.text.clear()
        }
    }

    private fun unlockDevice() {
        Log.d(TAG, "📤 update_actual_device_status (0) — correct PIN unlock")

        LockCommandActions.onUnlockCompleted(applicationContext)

        DeviceStatusApi.updateActualDeviceStatus(applicationContext, "", "unlock")

        setLocked(this, false)

        if (dpmHelper.isDeviceOwner()) {
            try {
                dpmHelper.setStatusBarDisabled(false)
                Log.d(TAG, "✅ Status bar enabled")
                dpmHelper.unlockSettingsWhenOverlayHidden()
                Log.d(TAG, "✅ Settings unlocked")
            } catch (e: Exception) {
                Log.e(TAG, "Error: ${e.message}")
            }
        }

        try {
            stopLockTask()
            Log.d(TAG, "✅ Stopped Lock Task Mode")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping lock task: ${e.message}")
        }

        finish()
        Log.d(TAG, "✅ LockActivity finished")
    }

    /**
     * Called when an unlock command is received from outside (FCM notification).
     * This properly unlocks the device and finishes the activity.
     */
    fun unlockAndFinish() {
        Log.d(TAG, "unlockAndFinish() called")

        LockCommandActions.onUnlockCompleted(applicationContext)

        // Set unlocking flag to prevent restart in onPause/onDestroy
        isUnlocking = true

        // Set locked state to false directly (don't call setLocked to avoid recursion)
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putBoolean(DEVICE_LOCKED_KEY, false).commit()
        Log.d(TAG, "✅ Lock state set to false")

        // Enable status bar and unlock settings
        if (dpmHelper.isDeviceOwner()) {
            try {
                dpmHelper.setStatusBarDisabled(false)
                Log.d(TAG, "✅ Status bar enabled")
                dpmHelper.unlockSettingsWhenOverlayHidden()
                Log.d(TAG, "✅ Settings unlocked")
            } catch (e: Exception) {
                Log.e(TAG, "Error unlocking settings: ${e.message}")
            }
        }

        // Stop lock task mode
        try {
            stopLockTask()
            Log.d(TAG, "✅ Stopped Lock Task Mode")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping lock task: ${e.message}")
        }

        // Finish the activity
        try {
            finishAndRemoveTask()
            Log.d(TAG, "✅ LockActivity finishAndRemoveTask() called")
        } catch (e: Exception) {
            Log.e(TAG, "Error in finishAndRemoveTask: ${e.message}")
            try {
                finish()
                Log.d(TAG, "✅ LockActivity finish() called as fallback")
            } catch (e2: Exception) {
                Log.e(TAG, "Error in finish(): ${e2.message}")
            }
        }
    }

    override fun onBackPressed() {
        if (isPinDialogShowing) {
            hidePinDialog()
        }
        // Don't call super - block back button
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        return when (keyCode) {
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_BACK,
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_APP_SWITCH -> true
            else -> super.onKeyDown(keyCode, event)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !isUnlocking) setupFullscreenMode()
    }

    override fun onResume() {
        super.onResume()
        if (!isUnlocking) {
            setupFullscreenMode()
        }
    }

    override fun onPause() {
        super.onPause()
        // Don't restart lock if we're unlocking or opening WiFi settings
        if (isLocked(this) && !isOpeningWifiSettings && !isUnlocking) {
            val intent = Intent(this, LockActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            }
            startActivity(intent)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy() - isUnlocking: $isUnlocking, isOpeningWifiSettings: $isOpeningWifiSettings")

        // Clear current instance
        if (currentInstance == this) {
            currentInstance = null
        }

        // Unregister unlock receiver
        try {
            unregisterReceiver(unlockReceiver)
            Log.d(TAG, "✅ Unlock receiver unregistered")
        } catch (e: Exception) {
            // Ignore - receiver might not be registered
        }

        // Don't restart lock if we're unlocking or opening WiFi settings
        if (isLocked(this) && !isOpeningWifiSettings && !isUnlocking) {
            Log.d(TAG, "Restarting LockActivity from onDestroy...")
            start(applicationContext)
        }
    }
}




