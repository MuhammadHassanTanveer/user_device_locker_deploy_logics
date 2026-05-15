package com.deploy_logics.user_device_locker

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject

/**
 * Activity that shows a dialog for entering secret command codes.
 * This is triggered when user dials *#*#9009#*#* on the phone dialer.
 * 
 * The command codes are fetched from API and stored in SharedPreferences.
 * Example codes mapping (stored in SharedPreferences):
 * {
 *   "101": "enable_camera",
 *   "102": "disable_camera",
 *   "103": "enable_location",
 *   "104": "disable_location",
 *   "105": "enable_wifi_config",
 *   "106": "disable_wifi_config",
 *   ... etc
 * }
 */
class SecretCodeDialogActivity : Activity() {

    companion object {
        private const val TAG = "SecretCodeDialog"
        private const val PREFS_NAME = "FlutterSharedPreferences"
        private const val SECRET_CODES_KEY = "flutter.secret_command_codes"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.d(TAG, "onCreate - showing secret code dialog")

        // Make the activity transparent
        window.setBackgroundDrawableResource(android.R.color.transparent)

        // Show dialog immediately
        showCodeInputDialog()
    }

    private fun showCodeInputDialog() {
        // Create EditText for code input
        val editText = EditText(this).apply {
            hint = "Enter command code"
            inputType = InputType.TYPE_CLASS_NUMBER
            setPadding(50, 30, 50, 30)
        }

        // Create container layout
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(50, 20, 50, 20)
            addView(editText)
        }

        // Build and show dialog
        val dialog = AlertDialog.Builder(this)
            .setTitle("🔐 Device Command")
            .setMessage("Enter the command code to execute")
            .setView(container)
            .setPositiveButton("Execute") { _, _ ->
                val raw = editText.text.toString().trim()
                val code = raw.filter { it.isDigit() }
                if (code.isNotEmpty()) {
                    val shouldFinish = executeCodeCommand(code)
                    if (shouldFinish) {
                        finish()
                    }
                } else {
                    showToast("Please enter a code")
                    finish()
                }
            }
            .setNegativeButton("Cancel") { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setOnCancelListener {
                finish()
            }
            .create()

        // Show dialog over lock screen if needed
        dialog.window?.apply {
            setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
            addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        try {
            dialog.show()
        } catch (e: Exception) {
            Log.e(TAG, "Error showing dialog with overlay type, trying without: ${e.message}")
            // Fallback: show without overlay permissions
            val fallbackDialog = AlertDialog.Builder(this)
                .setTitle("🔐 Device Command")
                .setMessage("Enter the command code to execute")
                .setView(LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(50, 20, 50, 20)
                    val newEditText = EditText(this@SecretCodeDialogActivity).apply {
                        hint = "Enter command code"
                        inputType = InputType.TYPE_CLASS_NUMBER
                        setPadding(50, 30, 50, 30)
                        tag = "codeInput"
                    }
                    addView(newEditText)
                })
                .setPositiveButton("Execute") { dialogInterface, _ ->
                    val alertDialog = dialogInterface as AlertDialog
                    val input = alertDialog.findViewById<EditText>(android.R.id.edit)
                        ?: alertDialog.window?.decorView?.findViewWithTag<EditText>("codeInput")
                    val code = input?.text?.toString()?.trim()?.filter { it.isDigit() } ?: ""
                    if (code.isNotEmpty()) {
                        val shouldFinish = executeCodeCommand(code)
                        if (shouldFinish) {
                            finish()
                        }
                    } else {
                        showToast("Please enter a code")
                        finish()
                    }
                }
                .setNegativeButton("Cancel") { d, _ ->
                    d.dismiss()
                    finish()
                }
                .setOnCancelListener {
                    finish()
                }
                .create()
            fallbackDialog.show()
        }
    }

    /**
     * Execute a code command.
     * @return true if the activity should finish immediately, false if it should stay open (e.g., for showing another dialog)
     */
    private fun executeCodeCommand(code: String): Boolean {
        Log.d(TAG, "Executing code: $code")

        // Get command codes from SharedPreferences
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val codesJson = prefs.getString(SECRET_CODES_KEY, null)

        if (codesJson.isNullOrEmpty()) {
            Log.e(TAG, "No command codes found in SharedPreferences")
            showToast("❌ No command codes configured")
            
            // For testing: show default codes that can be used
            Log.d(TAG, "Tip: Store codes in SharedPreferences key '$SECRET_CODES_KEY' as JSON")
            Log.d(TAG, "Example: {\"101\":\"enable_camera\",\"102\":\"disable_camera\"}")
            return true
        }

        try {
            val codesMap = JSONObject(codesJson)
            
            if (codesMap.has(code)) {
                val command = codesMap.getString(code)
                Log.d(TAG, "Code $code matched! Executing command: $command")
                
                // Handle special commands locally
                when (command) {
                    "get_fcm_token" -> {
                        showFcmTokenDialog()
                        return false // Don't finish, we'll show another dialog
                    }
                }
                
                showToast("✅ Executing: $command")

                // Same execution entry as push: DeviceCommandService uses FCM-equivalent routing
                // (immediate path for lock / unlock / message_customer_*; FG service otherwise).
                DeviceCommandService.executeCommand(this, command)
                return true
                
            } else {
                Log.w(TAG, "Code $code not found in commands mapping (open app once online so get_codes syncs)")
                showToast("Invalid code — app may still have fallback codes")

                // Log available codes for debugging
                Log.d(TAG, "Available codes: ${codesMap.keys().asSequence().toList()}")
                return true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing command codes: ${e.message}")
            showToast("❌ Error: ${e.message}")
            return true
        }
    }

    private fun showFcmTokenDialog() {
        // Show loading dialog first
        val loadingDialog = AlertDialog.Builder(this)
            .setTitle("🔄 Loading...")
            .setMessage("Fetching FCM Token...")
            .setCancelable(false)
            .create()
        
        runOnUiThread { loadingDialog.show() }
        
        FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
            runOnUiThread { loadingDialog.dismiss() }
            
            val token = if (task.isSuccessful) {
                task.result ?: "Unable to get token"
            } else {
                "Error: ${task.exception?.message}"
            }
            
            Log.d(TAG, "FCM Token: $token")
            
            // Cache the token in SharedPreferences
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString("flutter.cached_fcm_token", token).apply()
            
            // Show the FCM Token UI
            runOnUiThread {
                showFcmTokenUI(token)
            }
        }
    }
    
    private fun showFcmTokenUI(token: String) {
        // Create main container
        val mainLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 30, 40, 20)
        }
        
        // Title icon and text
        val titleLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, 20)
        }
        
        val titleIcon = TextView(this).apply {
            text = "🔑"
            textSize = 24f
            setPadding(0, 0, 16, 0)
        }
        
        val titleText = TextView(this).apply {
            text = "Firebase Cloud Messaging Token"
            textSize = 16f
            setTypeface(null, android.graphics.Typeface.BOLD)
        }
        
        titleLayout.addView(titleIcon)
        titleLayout.addView(titleText)
        mainLayout.addView(titleLayout)
        
        // Info text
        val infoText = TextView(this).apply {
            text = "This token is used to send push notifications to this device."
            textSize = 12f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 0, 0, 20)
        }
        mainLayout.addView(infoText)
        
        // Token card container
        val cardLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(android.graphics.Color.parseColor("#F5F5F5"))
            setPadding(30, 30, 30, 30)
        }
        
        // Token label
        val tokenLabel = TextView(this).apply {
            text = "FCM Token:"
            textSize = 12f
            setTextColor(android.graphics.Color.parseColor("#666666"))
            setPadding(0, 0, 0, 10)
        }
        cardLayout.addView(tokenLabel)
        
        // Scrollable token text
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                300 // Fixed height for scroll
            )
        }
        
        val tokenTextView = TextView(this).apply {
            text = token
            textSize = 13f
            setTextColor(android.graphics.Color.parseColor("#333333"))
            setTextIsSelectable(true)
            setTypeface(android.graphics.Typeface.MONOSPACE)
            setPadding(0, 0, 0, 0)
        }
        
        scrollView.addView(tokenTextView)
        cardLayout.addView(scrollView)
        
        // Token length info
        val lengthInfo = TextView(this).apply {
            text = "Length: ${token.length} characters"
            textSize = 11f
            setTextColor(android.graphics.Color.GRAY)
            setPadding(0, 15, 0, 0)
        }
        cardLayout.addView(lengthInfo)
        
        mainLayout.addView(cardLayout)
        
        // Copy button (large, prominent)
        val copyButton = android.widget.Button(this).apply {
            text = "📋 Copy Token to Clipboard"
            textSize = 14f
            setBackgroundColor(android.graphics.Color.parseColor("#4CAF50"))
            setTextColor(android.graphics.Color.WHITE)
            setPadding(20, 25, 20, 25)
            val params = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            params.topMargin = 30
            layoutParams = params
            
            setOnClickListener {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("FCM Token", token)
                clipboard.setPrimaryClip(clip)
                showToast("✅ Token copied to clipboard!")
                text = "✅ Copied!"
                postDelayed({
                    text = "📋 Copy Token to Clipboard"
                }, 2000)
            }
        }
        mainLayout.addView(copyButton)
        
        // Show dialog
        AlertDialog.Builder(this)
            .setView(mainLayout)
            .setPositiveButton("Close") { dialog, _ ->
                dialog.dismiss()
                finish()
            }
            .setOnCancelListener {
                finish()
            }
            .show()
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "onDestroy")
    }
}



