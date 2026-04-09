package com.deploy_logics.user_device_locker

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout

/**
 * Activity that opens WiFi settings panel while keeping the lock overlay active.
 * When user presses back, it returns to the lock overlay screen.
 */
class WifiSettingsActivity : Activity() {

    companion object {
        private const val TAG = "WifiSettingsActivity"
        const val EXTRA_USER_ID = "userId"
        const val EXTRA_PIN = "pin"
        private const val WIFI_SETTINGS_REQUEST_CODE = 1001
        private const val MIN_WIFI_SETTINGS_TIME_MS = 2000L  // Minimum time user should be in WiFi settings

        /**
         * Launch WiFi settings activity from the lock overlay
         */
        fun launch(context: Context, userId: String = "", pin: String = "1234") {
            Log.d(TAG, "Launching WifiSettingsActivity")
            val intent = Intent(context, WifiSettingsActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                putExtra(EXTRA_USER_ID, userId)
                putExtra(EXTRA_PIN, pin)
            }
            context.startActivity(intent)
        }
    }

    private var userId: String = ""
    private var pin: String = "1234"
    private var wifiSettingsOpened = false
    private var hasLeftActivity = false  // Track if we actually left this activity
    private var wifiSettingsOpenedTime: Long = 0  // Track when WiFi settings was opened
    private var connectivityCallback: ConnectivityManager.NetworkCallback? = null
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var dpmHelper: DevicePolicyManagerHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate")

        dpmHelper = DevicePolicyManagerHelper(this)

        // Make activity appear over lock screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        // Get extras
        userId = intent.getStringExtra(EXTRA_USER_ID) ?: ""
        pin = intent.getStringExtra(EXTRA_PIN) ?: "1234"

        // Set a minimal transparent content view
        val container = FrameLayout(this)
        container.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        setContentView(container)

        // Register network callback to monitor WiFi connection
        registerNetworkCallback()

        // Temporarily allow WiFi settings access, then open WiFi settings
        handler.postDelayed({
            temporarilyAllowWifiSettingsAndOpen()
        }, 100)
    }

    private fun temporarilyAllowWifiSettingsAndOpen() {
        try {
            // Temporarily allow WiFi settings access (unhide settings app)
            if (dpmHelper.isDeviceOwner()) {
                dpmHelper.temporarilyAllowWifiSettings()
                Log.d(TAG, "Temporarily allowed WiFi settings access")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error allowing WiFi settings: ${e.message}")
        }

        // Small delay to ensure settings is unhidden before opening
        handler.postDelayed({
            openWifiSettings()
        }, 100)
    }

    private fun openWifiSettings() {
        try {
            Log.d(TAG, "Opening WiFi settings directly, SDK: ${Build.VERSION.SDK_INT}")

            // Always use direct WiFi settings (not panel) for a full settings experience
            Log.d(TAG, "Using Settings.ACTION_WIFI_SETTINGS")
            val wifiIntent = Intent(Settings.ACTION_WIFI_SETTINGS)
            // Don't use FLAG_ACTIVITY_NEW_TASK to avoid immediate onActivityResult callback
            startActivity(wifiIntent)
            wifiSettingsOpened = true
            wifiSettingsOpenedTime = System.currentTimeMillis()
            Log.d(TAG, "WiFi settings intent started")
        } catch (e: Exception) {
            Log.e(TAG, "Error opening WiFi settings: ${e.message}")
            e.printStackTrace()
            // Fallback to general wireless settings
            try {
                Log.d(TAG, "Trying fallback: Settings.ACTION_WIRELESS_SETTINGS")
                val fallbackIntent = Intent(Settings.ACTION_WIRELESS_SETTINGS)
                startActivity(fallbackIntent)
                wifiSettingsOpened = true
            } catch (e2: Exception) {
                Log.e(TAG, "Error opening wireless settings: ${e2.message}")
                e2.printStackTrace()
                returnToLockOverlay()
            }
        }
    }

    private fun registerNetworkCallback() {
        try {
            val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val networkRequest = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .build()

            connectivityCallback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    Log.d(TAG, "WiFi connected!")
                    runOnUiThread {
                        // Optional: Show a toast that WiFi is connected
                        android.widget.Toast.makeText(
                            this@WifiSettingsActivity,
                            "WiFi Connected",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                }

                override fun onLost(network: Network) {
                    Log.d(TAG, "WiFi disconnected")
                }
            }

            connectivityManager.registerNetworkCallback(networkRequest, connectivityCallback!!)
        } catch (e: Exception) {
            Log.e(TAG, "Error registering network callback: ${e.message}")
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            connectivityCallback?.let {
                val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                connectivityManager.unregisterNetworkCallback(it)
                connectivityCallback = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error unregistering network callback: ${e.message}")
        }
    }

    // Removed onActivityResult - we're using onResume timing instead

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        Log.d(TAG, "onBackPressed")
        returnToLockOverlay()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "onKeyDown - keyCode: $keyCode")
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            returnToLockOverlay()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    private fun returnToLockOverlay() {
        Log.d(TAG, "Returning to lock screen")

        // Re-block WiFi settings access (re-hide settings app)
        try {
            if (dpmHelper.isDeviceOwner()) {
                dpmHelper.reblockWifiSettings()
                Log.d(TAG, "Re-blocked WiFi settings access")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error re-blocking WiFi settings: ${e.message}")
        }

        // Check if LockActivity should be used (if device is locked via LockActivity)
        val prefs = getSharedPreferences("FlutterSharedPreferences", Context.MODE_PRIVATE)
        val isDeviceLocked = prefs.getBoolean("flutter.device_locked", false)

        if (isDeviceLocked) {
            // Return to LockActivity
            Log.d(TAG, "Device is locked, returning to LockActivity")
            LockActivity.start(this)
        } else if (LockOverlayService.isShowing) {
            // Restore the overlay that was temporarily hidden
            Log.d(TAG, "Restoring temporarily hidden overlay")
            LockOverlayService.instance?.restoreOverlay()
        } else {
            // Fallback to LockOverlayService if needed
            Log.d(TAG, "Lock overlay is not showing, re-showing it")
            LockOverlayService.show(this, userId, pin)
        }

        // Finish this activity
        finish()
    }

    override fun onResume() {
        super.onResume()
        val timeSinceOpened = System.currentTimeMillis() - wifiSettingsOpenedTime
        Log.d(TAG, "onResume - wifiSettingsOpened: $wifiSettingsOpened, hasLeftActivity: $hasLeftActivity, timeSinceOpened: ${timeSinceOpened}ms")

        // Only return to lock if:
        // 1. WiFi settings was opened
        // 2. We actually left this activity (WiFi settings was shown)
        // 3. Enough time has passed (user had time to interact with WiFi settings)
        if (wifiSettingsOpened && hasLeftActivity && timeSinceOpened > MIN_WIFI_SETTINGS_TIME_MS) {
            Log.d(TAG, "WiFi settings was closed after ${timeSinceOpened}ms, returning to lock screen")
            // Small delay to ensure settings is fully closed
            handler.postDelayed({
                if (!isFinishing) {
                    returnToLockOverlay()
                }
            }, 300)
        }
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause - marking hasLeftActivity = true")
        // When we pause, it means we're leaving this activity (WiFi settings is showing)
        if (wifiSettingsOpened) {
            hasLeftActivity = true
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
        // Clean up handler callbacks
        handler.removeCallbacksAndMessages(null)
        unregisterNetworkCallback()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        Log.d(TAG, "onUserLeaveHint - wifiSettingsOpened: $wifiSettingsOpened")
        // Only return to lock if WiFi settings wasn't opened yet
        // (meaning user pressed home before WiFi settings opened)
        if (!wifiSettingsOpened) {
            Log.d(TAG, "User left before WiFi settings opened, returning to lock")
            returnToLockOverlay()
        }
        // Otherwise, let onResume handle returning to lock after user finishes with WiFi settings
    }
}

