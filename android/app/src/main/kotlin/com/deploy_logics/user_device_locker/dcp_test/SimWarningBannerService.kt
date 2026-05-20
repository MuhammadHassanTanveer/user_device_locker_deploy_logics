package com.deploy_logics.user_device_locker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * Thin top-of-screen banner (loan-warning style) when SIM is missing.
 */
class SimWarningBannerService : Service() {

    companion object {
        private const val TAG = "SimWarningBanner"
        private const val NOTIFICATION_ID = 4103
        private const val CHANNEL_ID = "sim_warning_banner_channel"

        @Volatile
        var isShowing = false
            private set

        private var instance: SimWarningBannerService? = null

        fun show(context: Context, message: String) {
            SimWarningOverlay.show(context, message)
        }

        fun hide(context: Context) {
            SimWarningOverlay.hide(context)
        }
    }

    private var windowManager: WindowManager? = null
    private var bannerView: View? = null
    private var message: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildForegroundNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        message = intent?.getStringExtra("message")
            ?: SimWarningCoordinator.buildNoSimWarningMessage(this)
        showBanner()
        return START_STICKY
    }

    override fun onDestroy() {
        removeBanner()
        isShowing = false
        instance = null
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "SIM warning banner",
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildForegroundNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle("SIM monitoring")
            .setContentText("Watching SIM slots")
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun showBanner() {
        if (bannerView != null) {
            val container = bannerView as? LinearLayout
            val textView = container?.getChildAt(0) as? TextView
            textView?.text = message
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        val statusBarHeight = getStatusBarHeight()

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#CC1A0000"))
            setPadding(24, statusBarHeight + 12, 24, 12)
        }

        val textView = TextView(this).apply {
            text = message
            setTextColor(Color.parseColor("#FF3B30"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
        }
        container.addView(textView)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            y = 0
        }

        try {
            windowManager?.addView(container, params)
            bannerView = container
            isShowing = true
            Log.d(TAG, "SIM warning banner shown")
        } catch (e: Exception) {
            Log.e(TAG, "Error showing banner: ${e.message}")
        }
    }

    private fun removeBanner() {
        try {
            bannerView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.w(TAG, "removeBanner: ${e.message}")
        }
        bannerView = null
    }

    private fun getStatusBarHeight(): Int {
        val resourceId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) resources.getDimensionPixelSize(resourceId) else 0
    }
}
