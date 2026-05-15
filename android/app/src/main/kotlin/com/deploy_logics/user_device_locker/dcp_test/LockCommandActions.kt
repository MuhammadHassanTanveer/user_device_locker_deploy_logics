package com.deploy_logics.user_device_locker

import android.content.Context
import android.util.Log

/**
 * Single implementation for remote/dialer lock & unlock so FCM and *#*#9009#*#* behave the same
 * (LockActivity + policy hooks), not LockOverlay-only.
 */
object LockCommandActions {
    private const val TAG = "LockCommandActions"
    private const val PREFS_NAME = "FlutterSharedPreferences"
    private const val PIN_KEY = "flutter.lock_pin"
    /** String epoch-ms when unlock completed so FCM/Dart can drop stale queued `lock`. */
    private const val LAST_UNLOCK_WALL_CLOCK_MS_KEY = "flutter._native_last_unlock_wall_ms"

    private fun readLastUnlockWallClockMarker(prefs: android.content.SharedPreferences): Long =
        prefs.getString(LAST_UNLOCK_WALL_CLOCK_MS_KEY, null)?.toLongOrNull() ?: 0L

    private fun persistUnlockWallClockMarker(prefs: android.content.SharedPreferences, fcmSentTimeMillis: Long) {
        val now = System.currentTimeMillis()
        val marker =
            if (fcmSentTimeMillis > 0L) maxOf(now, fcmSentTimeMillis) else now
        prefs.edit().putString(LAST_UNLOCK_WALL_CLOCK_MS_KEY, marker.toString()).commit()
        Log.d(TAG, "Unlock wall-clock marker saved: $marker")
    }

    /** PIN unlock / any unlock path not covered by [unlock]. Keeps queued stale FCM `lock` from showing UI again. */
    fun onUnlockCompleted(context: Context, fcmSentTimeMillis: Long = 0L) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        persistUnlockWallClockMarker(prefs, fcmSentTimeMillis)
    }

    /**
     * @param data optional map (e.g. FCM payload); uses `pin` when non-empty, else SharedPreferences unlock code.
     * @param fcmSentTimeMillis from [com.google.firebase.messaging.RemoteMessage.getSentTime]; omit for dial/SMS (always apply).
     */
    fun lock(
        context: Context,
        dpmHelper: DevicePolicyManagerHelper,
        data: Map<String, String> = emptyMap(),
        fcmSentTimeMillis: Long? = null,
    ) {
        Log.d(TAG, "lock(fcmSentTimeMillis=$fcmSentTimeMillis)")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

        val msgSent = fcmSentTimeMillis
        if (msgSent != null && msgSent > 0L) {
            val lastUnlock = readLastUnlockWallClockMarker(prefs)
            if (lastUnlock > 0L && msgSent < lastUnlock) {
                Log.w(
                    TAG,
                    "Ignoring stale FCM lock (msg sent $msgSent < lastUnlockWallClock $lastUnlock)",
                )
                return
            }
        }

        val pin: String = if (!data["pin"].isNullOrEmpty()) {
            Log.d(TAG, "Using PIN from payload: ${data["pin"]}")
            data["pin"]!!
        } else {
            val existingCode = UnlockCodeApi.getStoredUnlockCode(prefs)
            Log.d(TAG, "Using unlock code from SharedPreferences: $existingCode")
            existingCode
        }

        prefs.edit().putString(PIN_KEY, pin).commit()
        Log.d(TAG, "Lock PIN saved to SharedPreferences")

        try {
            if (dpmHelper.isDeviceOwner()) {
                dpmHelper.setStatusBarDisabled(true)
                Log.d(TAG, "Status bar disabled")
                dpmHelper.lockSettingsWhenOverlayShown()
                Log.d(TAG, "Settings locked")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error applying device-owner lock UI policies: ${e.message}")
        }

        try {
            LockActivity.setLocked(context.applicationContext, true)
            LockActivity.start(context.applicationContext)
            Log.d(TAG, "LockActivity started with Lock Task Mode")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting LockActivity: ${e.message}")
        }

        Log.d(TAG, "📤 update_actual_device_status (1) after lock UI shown")
        DeviceStatusApi.updateActualDeviceStatus(
            context = context.applicationContext,
            lockCode = pin,
            actualDeviceStatus = "lock"
        )

        Log.d(TAG, "lock() done")
    }

    fun unlock(context: Context, dpmHelper: DevicePolicyManagerHelper, fcmSentTimeMillis: Long = 0L) {
        Log.d(TAG, "unlock(fcmSentTimeMillis=$fcmSentTimeMillis)")

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        persistUnlockWallClockMarker(prefs, fcmSentTimeMillis)

        try {
            LockActivity.setLocked(context.applicationContext, false)
            Log.d(TAG, "LockActivity stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping LockActivity: ${e.message}")
        }

        try {
            if (LockOverlayService.isShowing) {
                LockOverlayService.hide(context.applicationContext)
                Log.d(TAG, "LockOverlayService hidden")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding LockOverlayService: ${e.message}")
        }

        try {
            if (MessageOverlayService.isShowing) {
                MessageOverlayService.hide(context.applicationContext)
                Log.d(TAG, "MessageOverlayService hidden")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error hiding MessageOverlayService: ${e.message}")
        }

        try {
            if (dpmHelper.isDeviceOwner()) {
                dpmHelper.setStatusBarDisabled(false)
                dpmHelper.unlockSettingsWhenOverlayHidden()
                Log.d(TAG, "Status bar restored, settings unlocked")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring status bar/settings: ${e.message}")
        }

        Log.d(TAG, "📤 update_actual_device_status (0) after unlock")
        DeviceStatusApi.updateActualDeviceStatus(
            context = context.applicationContext,
            lockCode = "",
            actualDeviceStatus = "unlock"
        )

        Log.d(TAG, "unlock() done")
    }
}
