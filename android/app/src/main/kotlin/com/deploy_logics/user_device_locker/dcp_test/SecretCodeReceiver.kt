package com.deploy_logics.user_device_locker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver that listens for secret code dialed from phone dialer.
 * When user dials *#*#9009#*#* on the phone dialer, this receiver is triggered.
 * It then opens the SecretCodeDialogActivity to show an input dialog.
 */
class SecretCodeReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SecretCodeReceiver"
        const val SECRET_CODE = "9009" // User dials *#*#9009#*#*
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.d(TAG, "Secret code received! Action: ${intent.action}")
        Log.d(TAG, "Data: ${intent.data}")

        // Launch the dialog activity
        val dialogIntent = Intent(context, SecretCodeDialogActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }

        try {
            context.startActivity(dialogIntent)
            Log.d(TAG, "SecretCodeDialogActivity started successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Error starting SecretCodeDialogActivity: ${e.message}")
            e.printStackTrace()
        }
    }
}

