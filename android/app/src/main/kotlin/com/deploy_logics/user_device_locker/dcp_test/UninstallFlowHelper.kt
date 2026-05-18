package com.deploy_logics.user_device_locker

import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Shared uninstall preparation: update server status, restore device policies,
 * show this app in the launcher, and open the uninstall / re-register UI.
 */
object UninstallFlowHelper {

    private const val TAG = "UninstallFlowHelper"
    const val PREFS_NAME = "FlutterSharedPreferences"
    const val PENDING_UNINSTALL_UI_KEY = "flutter.pending_uninstall_ui"

    fun executeUninstallFlow(context: Context) {
        Log.d(TAG, "executeUninstallFlow started")
        val appContext = context.applicationContext
        val dpmHelper = DevicePolicyManagerHelper(appContext)

        CustomerStatusApi.updateCustomerStatusForUninstall(appContext) { success ->
            if (success) {
                Log.d(TAG, "Customer status updated to inactive on server")
            } else {
                Log.w(TAG, "Customer status API failed or queued for later sync")
            }
        }

        try {
            dpmHelper.showSelf()
            showAllHiddenSocialApps(dpmHelper)
            dpmHelper.prepareForUninstall()
        } catch (e: Exception) {
            Log.e(TAG, "Error during uninstall preparation: ${e.message}")
        }

        launchUninstallReadyUi(appContext)
    }

    private fun showAllHiddenSocialApps(dpmHelper: DevicePolicyManagerHelper) {
        val socialMediaPackages = mapOf(
            "facebook" to "com.facebook.katana",
            "whatsapp" to "com.whatsapp",
            "whatsappbusiness" to "com.whatsapp.w4b",
            "messenger" to "com.facebook.orca",
            "tiktok" to "com.zhiliaoapp.musically",
            "instagram" to "com.instagram.android",
            "snapchat" to "com.snapchat.android",
            "linkedin" to "com.linkedin.android",
            "youtube" to "com.google.android.youtube",
            "threads" to "com.instagram.barcelona",
            "x" to "com.twitter.android",
            "twitter" to "com.twitter.android",
            "discord" to "com.discord",
        )
        for ((_, packageName) in socialMediaPackages) {
            try {
                dpmHelper.hideApp(packageName, false)
            } catch (_: Exception) {
                // App may not be installed.
            }
        }
    }

    fun launchUninstallReadyUi(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(PENDING_UNINSTALL_UI_KEY, true).apply()

        val intent = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra("uninstall_mode", true)
        }
        context.startActivity(intent)
        Log.d(TAG, "Launched MainActivity for uninstall / re-register UI")
    }

    fun clearPendingUninstallUi(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(PENDING_UNINSTALL_UI_KEY)
            .apply()
    }

    fun hasPendingUninstallUi(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(PENDING_UNINSTALL_UI_KEY, false)
    }
}
