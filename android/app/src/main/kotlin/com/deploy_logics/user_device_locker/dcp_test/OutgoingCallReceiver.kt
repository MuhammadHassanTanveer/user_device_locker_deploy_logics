package com.deploy_logics.user_device_locker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * BroadcastReceiver that listens for outgoing calls.
 * When user dials *#*#9009#*#* on the phone dialer, this receiver intercepts it
 * and shows the SecretCodeDialogActivity.
 * 
 * This approach works on ALL Android versions including Samsung devices
 * where SECRET_CODE broadcast is restricted.
 * 
 * Usage: User dials *#*#9009#*#* from the phone dialer
 */
class OutgoingCallReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "OutgoingCallReceiver"
        
        // Primary code to detect
        private const val SECRET_CODE = "9009"
        
        // All possible patterns that different dialers might send
        private val SECRET_CODE_PATTERNS = listOf(
            "*#*#9009#*#*",      // Standard Android format
            "#*#*9009#*#*",      // Some dialers reverse it
            "*#*#9009#*#",       // Without trailing *
            "*#*#9009",          // Partial
            "**9009**",          // Alternative format
            "*9009*",            // Simplified
            "#9009#",            // Hash format
            "9009"               // Just the code (some dialers strip symbols)
        )
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_NEW_OUTGOING_CALL) {
            return
        }

        val phoneNumber = resultData ?: intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER) ?: return
        
        Log.d(TAG, "Outgoing call detected: $phoneNumber")

        // Check if the dialed number matches any secret code pattern
        if (isSecretCode(phoneNumber)) {
            Log.d(TAG, "✅ Secret code detected! Blocking call and showing dialog.")
            
            // Cancel the outgoing call by setting result data to null
            resultData = null
            
            // Launch the dialog activity
            launchSecretCodeDialog(context)
        }
    }

    private fun isSecretCode(phoneNumber: String): Boolean {
        // Clean the number - remove spaces, dashes
        val cleanNumber = phoneNumber.replace(" ", "").replace("-", "")
        
        // Check exact matches first
        for (pattern in SECRET_CODE_PATTERNS) {
            if (cleanNumber.equals(pattern, ignoreCase = true)) {
                Log.d(TAG, "Matched pattern: $pattern")
                return true
            }
        }
        
        // Check if it contains the secret code surrounded by special characters
        // This handles cases like "*#*#9009#*#*" or variations
        if (cleanNumber.contains(SECRET_CODE)) {
            // Make sure it's not a regular phone number
            // Secret codes typically have * or # characters
            if (cleanNumber.contains("*") || cleanNumber.contains("#")) {
                Log.d(TAG, "Contains secret code with special chars: $cleanNumber")
                return true
            }
            
            // Also check if the entire string is just the code with symbols
            val digitsOnly = cleanNumber.filter { it.isDigit() }
            if (digitsOnly == SECRET_CODE) {
                Log.d(TAG, "Digits only match: $digitsOnly")
                return true
            }
        }
        
        return false
    }
    
    private fun launchSecretCodeDialog(context: Context) {
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


