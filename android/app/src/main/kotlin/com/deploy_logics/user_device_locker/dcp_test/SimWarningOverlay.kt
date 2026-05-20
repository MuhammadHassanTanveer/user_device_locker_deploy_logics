package com.deploy_logics.user_device_locker

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView

/**
 * Non-blocking top text strip over all apps (loan-warning / search-bar style).
 * Touches pass through — user can open and use apps normally.
 */
object SimWarningOverlay {

    private const val TAG = "SimWarningOverlay"

    private val mainHandler = Handler(Looper.getMainLooper())
    private var bannerView: View? = null
    private var windowManager: WindowManager? = null

    @Volatile
    var isShowing = false
        private set

    fun show(context: Context, message: String) {
        mainHandler.post { showOnMainThread(context.applicationContext, message) }
    }

    fun hide(context: Context) {
        mainHandler.post { hideOnMainThread() }
    }

    private fun showOnMainThread(context: Context, message: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(context)
        ) {
            Log.e(TAG, "Cannot show overlay — SYSTEM_ALERT_WINDOW not granted")
            return
        }

        if (bannerView != null) {
            (bannerView as? TextView)?.text = message
            isShowing = true
            return
        }

        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val statusBarHeight = getStatusBarHeight(context)
            val horizontalPad = dp(context, 16)
            val verticalPad = dp(context, 8)

            val stripBackground = GradientDrawable().apply {
                setColor(Color.parseColor("#B3000000"))
                cornerRadius = dp(context, 6).toFloat()
            }

            val textView = TextView(context).apply {
                text = message
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                gravity = Gravity.CENTER
                setPadding(horizontalPad, verticalPad, horizontalPad, verticalPad)
                background = stripBackground
                isClickable = false
                isFocusable = false
                isFocusableInTouchMode = false
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }

            val wrapper = FrameLayout(context).apply {
                setPadding(horizontalPad, statusBarHeight + dp(context, 4), horizontalPad, 0)
                addView(
                    textView,
                    FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.WRAP_CONTENT,
                    ),
                )
                isClickable = false
                isFocusable = false
            }

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
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 0
            }

            windowManager?.addView(wrapper, params)
            bannerView = wrapper
            isShowing = true
            Log.d(TAG, "Text overlay shown (touch-through)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to show overlay: ${e.message}", e)
            isShowing = false
        }
    }

    private fun hideOnMainThread() {
        try {
            bannerView?.let { windowManager?.removeView(it) }
        } catch (e: Exception) {
            Log.w(TAG, "hide: ${e.message}")
        }
        bannerView = null
        isShowing = false
    }

    private fun getStatusBarHeight(context: Context): Int {
        val resourceId = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resourceId > 0) context.resources.getDimensionPixelSize(resourceId) else 0
    }

    private fun dp(context: Context, value: Int): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            value.toFloat(),
            context.resources.displayMetrics,
        ).toInt()
    }
}
