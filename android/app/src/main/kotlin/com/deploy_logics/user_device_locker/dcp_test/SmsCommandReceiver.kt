package com.deploy_logics.user_device_locker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.telephony.SmsMessage
import android.util.Log

/**
 * SMS Command Receiver - Listens for incoming SMS and executes device commands
 *
 * This receiver checks if an SMS is from the registered retailer phone number
 * and executes commands like lock, unlock, etc. - same as FCM notifications.
 *
 * Supported commands (case-insensitive):
 * - lock / Lock / LOCK
 * - unlock / Unlock / UNLOCK
 * - enable_camera / disable_camera
 * - enable_location / disable_location
 * - And all other commands supported by FCM
 */
class SmsCommandReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "SmsCommandReceiver"
        private const val PREFS_NAME = "FlutterSharedPreferences"
        private const val RETAILER_PHONE_KEY = "flutter.retailer_phone"
        private const val LOCK_CODE_KEY = "flutter.lock_code"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SMS_RECEIVED") {
            return
        }

        Log.d(TAG, "===========================================")
        Log.d(TAG, "📱📱📱 SMS RECEIVED 📱📱📱")
        Log.d(TAG, "===========================================")

        try {
            val bundle: Bundle? = intent.extras
            if (bundle == null) {
                Log.d(TAG, "No bundle in intent")
                return
            }

            val pdus = bundle.get("pdus") as? Array<*>
            if (pdus == null || pdus.isEmpty()) {
                Log.d(TAG, "No PDUs in bundle")
                return
            }

            // Get the format for Android M and above
            val format = bundle.getString("format")

            // Parse SMS messages
            for (pdu in pdus) {
                val smsMessage: SmsMessage = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    SmsMessage.createFromPdu(pdu as ByteArray, format)
                } else {
                    @Suppress("DEPRECATION")
                    SmsMessage.createFromPdu(pdu as ByteArray)
                }

                val sender = smsMessage.displayOriginatingAddress ?: smsMessage.originatingAddress
                val messageBody = smsMessage.messageBody

                Log.d(TAG, "📱 SMS from: $sender")
                Log.d(TAG, "📱 Message: $messageBody")

                // Check if this SMS is from the registered retailer phone number
                if (isFromRegisteredRetailer(context, sender)) {
                    Log.d(TAG, "✅ SMS is from registered retailer - processing command")
                    processCommand(context, messageBody, sender)
                } else {
                    Log.d(TAG, "❌ SMS is NOT from registered retailer - ignoring")
                    Log.d(TAG, "   Expected retailer phone from SharedPreferences")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing SMS: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Check if the SMS sender matches the registered retailer phone number
     */
    private fun isFromRegisteredRetailer(context: Context, sender: String?): Boolean {
        if (sender.isNullOrEmpty()) {
            Log.d(TAG, "Sender is null or empty")
            return false
        }

        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val retailerPhone = prefs.getString(RETAILER_PHONE_KEY, null)

        Log.d(TAG, "📱 Checking sender: $sender")
        Log.d(TAG, "📱 Registered retailer phone: $retailerPhone")

        if (retailerPhone.isNullOrEmpty()) {
            Log.d(TAG, "❌ No retailer phone number registered")
            return false
        }

        // Normalize phone numbers for comparison (remove spaces, dashes, +, etc.)
        val normalizedSender = normalizePhoneNumber(sender)
        val normalizedRetailer = normalizePhoneNumber(retailerPhone)

        Log.d(TAG, "📱 Normalized sender: $normalizedSender")
        Log.d(TAG, "📱 Normalized retailer: $normalizedRetailer")

        // Check if they match (either exact match or one contains the other)
        val isMatch = normalizedSender == normalizedRetailer ||
                      normalizedSender.endsWith(normalizedRetailer) ||
                      normalizedRetailer.endsWith(normalizedSender) ||
                      normalizedSender.contains(normalizedRetailer) ||
                      normalizedRetailer.contains(normalizedSender)

        Log.d(TAG, "📱 Phone match result: $isMatch")
        return isMatch
    }

    /**
     * Normalize phone number by removing non-digit characters
     * Keep only the last 10 digits for comparison
     */
    private fun normalizePhoneNumber(phone: String): String {
        // Remove all non-digit characters
        val digitsOnly = phone.replace(Regex("[^0-9]"), "")

        // Return last 10 digits (or full number if less than 10 digits)
        return if (digitsOnly.length > 10) {
            digitsOnly.takeLast(10)
        } else {
            digitsOnly
        }
    }

    /**
     * Process the SMS message body as a command
     */
    private fun processCommand(context: Context, messageBody: String?, sender: String?) {
        if (messageBody.isNullOrEmpty()) {
            Log.d(TAG, "Message body is empty")
            return
        }

        // Clean and normalize the command
        val command = messageBody.trim().lowercase()
        Log.d(TAG, "🚀 Processing SMS command: '$command'")

        val dpmHelper = DevicePolicyManagerHelper(context)
        Log.d(TAG, "📱 Is Device Owner: ${dpmHelper.isDeviceOwner()}")

        when {
            // ==================== Device Lock/Unlock ====================
            command == "lock" || command.startsWith("lock ") -> {
                Log.d(TAG, ">>> LOCK command from SMS")
                handleLockCommand(context, dpmHelper)
            }
            command == "unlock" || command.startsWith("unlock ") -> {
                Log.d(TAG, ">>> UNLOCK command from SMS")
                handleUnlockCommand(context, dpmHelper)
            }

            // ==================== Camera Control ====================
            command == "enable_camera" || command == "enable camera" -> {
                Log.d(TAG, ">>> ENABLE_CAMERA command from SMS")
                val result = dpmHelper.setCameraDisabled(false)
                Log.d(TAG, "✅ Camera enabled: $result")
            }
            command == "disable_camera" || command == "disable camera" -> {
                Log.d(TAG, ">>> DISABLE_CAMERA command from SMS")
                val result = dpmHelper.setCameraDisabled(true)
                Log.d(TAG, "✅ Camera disabled: $result")
            }

            // ==================== Location Control ====================
            command == "enable_location" || command == "enable location" -> {
                Log.d(TAG, ">>> ENABLE_LOCATION command from SMS")
                val result = dpmHelper.enableConfigLocation()
                Log.d(TAG, "✅ Location config enabled: $result")
            }
            command == "disable_location" || command == "disable location" -> {
                Log.d(TAG, ">>> DISABLE_LOCATION command from SMS")
                val result = dpmHelper.disableConfigLocation()
                Log.d(TAG, "✅ Location config disabled: $result")
            }

            // ==================== Factory Reset Control ====================
            command == "enable_factory_reset" || command == "enable factory reset" -> {
                Log.d(TAG, ">>> ENABLE_FACTORY_RESET command from SMS")
                val result = dpmHelper.enableFactoryReset()
                Log.d(TAG, "✅ Factory reset enabled: $result")
            }
            command == "disable_factory_reset" || command == "disable factory reset" -> {
                Log.d(TAG, ">>> DISABLE_FACTORY_RESET command from SMS")
                val result = dpmHelper.disableFactoryReset()
                Log.d(TAG, "✅ Factory reset disabled: $result")
            }

            // ==================== Screen Capture Control ====================
            command == "enable_screen_capture" || command == "enable screen capture" -> {
                Log.d(TAG, ">>> ENABLE_SCREEN_CAPTURE command from SMS")
                val result = dpmHelper.setScreenCaptureDisabled(false)
                Log.d(TAG, "✅ Screen capture enabled: $result")
            }
            command == "disable_screen_capture" || command == "disable screen capture" -> {
                Log.d(TAG, ">>> DISABLE_SCREEN_CAPTURE command from SMS")
                val result = dpmHelper.setScreenCaptureDisabled(true)
                Log.d(TAG, "✅ Screen capture disabled: $result")
            }

            // ==================== WiFi Control ====================
            command == "enable_wifi" || command == "enable wifi" -> {
                Log.d(TAG, ">>> ENABLE_WIFI command from SMS")
                val result = dpmHelper.enableConfigWifi()
                Log.d(TAG, "✅ WiFi config enabled: $result")
            }
            command == "disable_wifi" || command == "disable wifi" -> {
                Log.d(TAG, ">>> DISABLE_WIFI command from SMS")
                val result = dpmHelper.disableConfigWifi()
                Log.d(TAG, "✅ WiFi config disabled: $result")
            }

            // ==================== Bluetooth Control ====================
            command == "enable_bluetooth" || command == "enable bluetooth" -> {
                Log.d(TAG, ">>> ENABLE_BLUETOOTH command from SMS")
                val result = dpmHelper.enableConfigBluetooth()
                Log.d(TAG, "✅ Bluetooth config enabled: $result")
            }
            command == "disable_bluetooth" || command == "disable bluetooth" -> {
                Log.d(TAG, ">>> DISABLE_BLUETOOTH command from SMS")
                val result = dpmHelper.disableConfigBluetooth()
                Log.d(TAG, "✅ Bluetooth config disabled: $result")
            }

            // ==================== USB Control ====================
            command == "enable_usb" || command == "enable usb" -> {
                Log.d(TAG, ">>> ENABLE_USB command from SMS")
                val result = dpmHelper.enableUSBFileTransfer()
                Log.d(TAG, "✅ USB transfer enabled: $result")
            }
            command == "disable_usb" || command == "disable usb" -> {
                Log.d(TAG, ">>> DISABLE_USB command from SMS")
                val result = dpmHelper.disableUSBFileTransfer()
                Log.d(TAG, "✅ USB transfer disabled: $result")
            }

            // ==================== Volume Control ====================
            command == "enable_volume" || command == "enable volume" -> {
                Log.d(TAG, ">>> ENABLE_VOLUME command from SMS")
                val result = dpmHelper.enableAdjustVolume()
                Log.d(TAG, "✅ Volume adjust enabled: $result")
            }
            command == "disable_volume" || command == "disable volume" -> {
                Log.d(TAG, ">>> DISABLE_VOLUME command from SMS")
                val result = dpmHelper.disableAdjustVolume()
                Log.d(TAG, "✅ Volume adjust disabled: $result")
            }

            // ==================== Calls/SMS Control ====================
            command == "enable_calls" || command == "enable calls" -> {
                Log.d(TAG, ">>> ENABLE_CALLS command from SMS")
                val result = dpmHelper.enableOutgoingCalls()
                Log.d(TAG, "✅ Outgoing calls enabled: $result")
            }
            command == "disable_calls" || command == "disable calls" -> {
                Log.d(TAG, ">>> DISABLE_CALLS command from SMS")
                val result = dpmHelper.disableOutgoingCalls()
                Log.d(TAG, "✅ Outgoing calls disabled: $result")
            }
            command == "enable_sms" || command == "enable sms" -> {
                Log.d(TAG, ">>> ENABLE_SMS command from SMS")
                val result = dpmHelper.enableSMS()
                Log.d(TAG, "✅ SMS enabled: $result")
            }
            command == "disable_sms" || command == "disable sms" -> {
                Log.d(TAG, ">>> DISABLE_SMS command from SMS")
                val result = dpmHelper.disableSMS()
                Log.d(TAG, "✅ SMS disabled: $result")
            }

            // ==================== Status Bar Control ====================
            command == "enable_status_bar" || command == "enable status bar" -> {
                Log.d(TAG, ">>> ENABLE_STATUS_BAR command from SMS")
                val result = dpmHelper.setStatusBarDisabled(false)
                Log.d(TAG, "✅ Status bar enabled: $result")
            }
            command == "disable_status_bar" || command == "disable status bar" -> {
                Log.d(TAG, ">>> DISABLE_STATUS_BAR command from SMS")
                val result = dpmHelper.setStatusBarDisabled(true)
                Log.d(TAG, "✅ Status bar disabled: $result")
            }

            // ==================== Device Actions ====================
            command == "reboot" || command == "restart" -> {
                Log.d(TAG, ">>> REBOOT command from SMS")
                val result = dpmHelper.reboot()
                Log.d(TAG, "✅ Reboot initiated: $result")
            }
            command == "lock_now" || command == "lock now" -> {
                Log.d(TAG, ">>> LOCK_NOW command from SMS")
                val result = dpmHelper.lockNow()
                Log.d(TAG, "✅ Device locked: $result")
            }

            // ==================== App Install/Uninstall Control ====================
            command == "enable_install_apps" || command == "enable install apps" -> {
                Log.d(TAG, ">>> ENABLE_INSTALL_APPS command from SMS")
                val result = dpmHelper.enableInstallApps()
                Log.d(TAG, "✅ Install apps enabled: $result")
            }
            command == "disable_install_apps" || command == "disable install apps" -> {
                Log.d(TAG, ">>> DISABLE_INSTALL_APPS command from SMS")
                val result = dpmHelper.disableInstallApps()
                Log.d(TAG, "✅ Install apps disabled: $result")
            }
            command == "enable_uninstall_apps" || command == "enable uninstall apps" -> {
                Log.d(TAG, ">>> ENABLE_UNINSTALL_APPS command from SMS")
                val result = dpmHelper.enableUninstallApps()
                Log.d(TAG, "✅ Uninstall apps enabled: $result")
            }
            command == "disable_uninstall_apps" || command == "disable uninstall apps" -> {
                Log.d(TAG, ">>> DISABLE_UNINSTALL_APPS command from SMS")
                val result = dpmHelper.disableUninstallApps()
                Log.d(TAG, "✅ Uninstall apps disabled: $result")
            }

            // ==================== Safe Mode Control ====================
            command == "enable_safe_mode" || command == "enable safe mode" -> {
                Log.d(TAG, ">>> ENABLE_SAFE_MODE command from SMS")
                val result = dpmHelper.enableSafeMode()
                Log.d(TAG, "✅ Safe mode enabled: $result")
            }
            command == "disable_safe_mode" || command == "disable safe mode" -> {
                Log.d(TAG, ">>> DISABLE_SAFE_MODE command from SMS")
                val result = dpmHelper.disableSafeMode()
                Log.d(TAG, "✅ Safe mode disabled: $result")
            }

            // ==================== Message Customer ====================
            command.startsWith("message_customer_") || command.startsWith("message ") -> {
                val customerMessage = when {
                    command.startsWith("message_customer_") -> command.removePrefix("message_customer_")
                    command.startsWith("message ") -> command.removePrefix("message ")
                    else -> command
                }
                Log.d(TAG, ">>> MESSAGE_CUSTOMER command from SMS: $customerMessage")
                handleMessageCustomerCommand(context, customerMessage)
            }

            else -> {
                Log.d(TAG, "⚠️ Unknown SMS command: '$command'")
            }
        }
    }

    /**
     * Handle lock command - same path as FCM / dial codes ([LockCommandActions])
     */
    private fun handleLockCommand(context: Context, dpmHelper: DevicePolicyManagerHelper) {
        Log.d(TAG, "handleLockCommand from SMS -> LockCommandActions.lock")
        LockCommandActions.lock(context.applicationContext, dpmHelper, emptyMap())
        Log.d(TAG, "Lock command from SMS processed")
    }

    /**
     * Handle unlock command - same path as FCM / dial codes ([LockCommandActions])
     */
    private fun handleUnlockCommand(context: Context, dpmHelper: DevicePolicyManagerHelper) {
        Log.d(TAG, "handleUnlockCommand from SMS -> LockCommandActions.unlock")
        LockCommandActions.unlock(context.applicationContext, dpmHelper)
        Log.d(TAG, "Unlock command from SMS processed")
    }

    /**
     * Handle message_customer command - show message overlay
     */
    private fun handleMessageCustomerCommand(context: Context, message: String) {
        Log.d(TAG, "handleMessageCustomerCommand: message = $message")

        try {
            // Replace underscores with spaces for better readability
            val formattedMessage = message.replace("_", " ")
            Log.d(TAG, "Formatted message: $formattedMessage")

            // Show message overlay using MessageOverlayService
            MessageOverlayService.show(context, formattedMessage)
            Log.d(TAG, "✅ MessageOverlayService started")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error showing message overlay: ${e.message}")
            e.printStackTrace()
        }
    }
}

