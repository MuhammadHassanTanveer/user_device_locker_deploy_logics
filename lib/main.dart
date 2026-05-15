import 'dart:ui' as ui;

import 'package:dcp_test/screens/splash_screen.dart';
import 'package:dcp_test/providers/register_device_provider.dart';
import 'package:dcp_test/services/kiosk_service.dart';
import 'package:firebase_core/firebase_core.dart';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';
import 'package:provider/provider.dart';
import 'package:shared_preferences/shared_preferences.dart';
import 'services/notification_services.dart';
import 'util/fcm_unlock_stale_guard.dart';
import 'firebase_options.dart';

// Background handler - MUST be a top-level function
// Social media app package names mapping
const Map<String, String> socialMediaPackages = {
  'facebook': 'com.facebook.katana',
  'whatsapp': 'com.whatsapp',
  'instagram': 'com.instagram.android',
  'twitter': 'com.twitter.android',
  'x': 'com.twitter.android',
  'tiktok': 'com.zhiliaoapp.musically',
  'snapchat': 'com.snapchat.android',
  'telegram': 'org.telegram.messenger',
  'linkedin': 'com.linkedin.android',
  'pinterest': 'com.pinterest',
  'reddit': 'com.reddit.frontpage',
  'youtube': 'com.google.android.youtube',
  'messenger': 'com.facebook.orca',
  'wechat': 'com.tencent.mm',
  'viber': 'com.viber.voip',
  'line': 'jp.naver.line.android',
  'discord': 'com.discord',
  'skype': 'com.skype.raider',
  'signal': 'org.thoughtcrime.securesms',
  'tumblr': 'com.tumblr',
  'threads': 'com.instagram.barcelona',
};

@pragma('vm:entry-point')
Future<void> _firebaseMessagingBackgroundHandler(RemoteMessage message) async {
  print('📨📨📨 BACKGROUND HANDLER START 📨📨📨');
  print('Message data: ${message.data}');

  try {
    // Initialize Firebase first
    await Firebase.initializeApp();
    print('Firebase initialized');

    // Initialize plugin registrant
    ui.DartPluginRegistrant.ensureInitialized();
    print('DartPluginRegistrant initialized');

    // Small delay to ensure channels are ready
    await Future.delayed(const Duration(milliseconds: 500));

    final deviceCommand = message.data['device']?.toString().toLowerCase();
    print('Device command: $deviceCommand');

    if (deviceCommand == null || deviceCommand.isEmpty) {
      print('No device command found');
      return;
    }

    bool result = false;

    switch (deviceCommand) {
      // ==================== Device Lock/Unlock ====================
      case 'lock':
        print('🔒 LOCK command received in background');
        if (await FcmUnlockStaleGuard.shouldIgnoreStaleLock(message)) {
          print(
            '⚠️ Stale FCM lock ignored (unlock completed after this message was sent)',
          );
          return;
        }

        // Save lock state using SharedPreferences directly
        final prefs = await SharedPreferences.getInstance();
        await prefs.setBool('device_locked', true);

        // Get lock PIN: use from notification data, OR existing lock_code from SharedPreferences
        // Only default to '1234' if no lock code exists at all
        String lockPin;
        if (message.data['pin'] != null && message.data['pin'].toString().isNotEmpty) {
          lockPin = message.data['pin'].toString();
          print('Using PIN from notification: $lockPin');
        } else {
          lockPin = await RegisterDeviceProvider.getUnlockCode() ?? '';
          print('Using unlock code from SharedPreferences: $lockPin');
        }
        if (lockPin.isNotEmpty) {
          await prefs.setString('lock_pin', lockPin);
        }
        print('Lock state saved to SharedPreferences');

        // POST /mobile/get_lock_code (also runs GET /mobile/get_codes in provider `finally`).
        try {
          final provider = RegisterDeviceProvider();
          await provider.getLockCodeApi(null);
          print('✅ Lock code + dialer codes refresh finished (getLockCodeApi)');

          final updatedUnlockCode = await RegisterDeviceProvider.getUnlockCode();
          if (updatedUnlockCode != null && updatedUnlockCode.isNotEmpty) {
            lockPin = updatedUnlockCode;
            await prefs.setString('lock_pin', lockPin);
            print('✅ Unlock code updated from API: $lockPin');
          }
        } catch (e) {
          print('⚠️ Could not fetch lock code info: $e');
        }

        // Call API to update actual device status (1 = locked). Queues offline / on failure.
        try {
          print('📤 Calling updateActualDeviceStatus (actual_lock_status=1), lockCode: $lockPin');
          await RegisterDeviceProvider.updateActualDeviceStatus(
            lockCode: lockPin,
            actualLockStatus: 1,
          );
          print('✅ Device status lock sync requested');
        } catch (e) {
          print('⚠️ Could not update device status: $e');
        }

        // NOTE: Native LockActivity with Lock Task Mode is started by MyFirebaseMessagingService
        // This is more reliable than Flutter overlay because:
        // 1. Doesn't require overlay permission
        // 2. Uses Lock Task Mode to block home/recent buttons
        // 3. Status bar is disabled via DevicePolicyManager
        // 4. User cannot exit without correct PIN

        // The status bar and settings are locked by the native service
        // We just need to ensure lock state is saved
        print('✅ Lock command processed - Native LockActivity handles the lock screen');
        result = true;
        break;

      case 'unlock':
        print('🔓 UNLOCK command received in background');
        await FcmUnlockStaleGuard.recordUnlock(message);
        // Save unlock state
        final prefs = await SharedPreferences.getInstance();
        await prefs.setBool('device_locked', false);
        print('Unlock state saved');

        // Get the current lock code to send to API
        final currentLockCode = await RegisterDeviceProvider.getUnlockCode() ?? '';

        // Hide all overlays (LockOverlay, MessageOverlay, LockActivity)
        try {
          await KioskService.hideAllOverlays();
          print('✅ All overlays hidden');
        } catch (e) {
          print('⚠️ Could not hide overlays: $e');
        }

        // NOTE: Native MyFirebaseMessagingService handles stopping LockActivity
        // and enabling status bar. We just need to save the unlock state.

        // POST /mobile/get_lock_code (also runs GET /mobile/get_codes in provider `finally`).
        try {
          final provider = RegisterDeviceProvider();
          await provider.getLockCodeApi(null);
          print('✅ Lock code + dialer codes refresh finished (getLockCodeApi)');
        } catch (e) {
          print('⚠️ Could not fetch lock code info: $e');
        }

        // Call API (actual_lock_status=0 = unlocked). Queues offline / on failure.
        try {
          print('📤 Calling updateActualDeviceStatus (actual_lock_status=0)');
          await RegisterDeviceProvider.updateActualDeviceStatus(
            lockCode: currentLockCode,
            actualLockStatus: 0,
          );
          print('✅ Device status unlock sync requested');
        } catch (e) {
          print('⚠️ Could not update device status: $e');
        }

        print('✅ Unlock command processed - Native handler stops LockActivity');
        result = true;
        break;

      // ==================== Camera Control ====================
      case 'enable_camera':
        result = await KioskService.enableCamera();
        break;
      case 'disable_camera':
        result = await KioskService.disableCamera();
        break;

      // ==================== Location Control ====================
      case 'enable_location':
      case 'enable_location_config':
        result = await KioskService.enableLocationConfig();
        break;
      case 'disable_location':
      case 'disable_location_config':
        result = await KioskService.disableLocationConfig();
        break;
      case 'enable_location_and_lock':
      case 'turn_on_location_lock':
        result = await KioskService.enableLocationAndLock();
        break;
      case 'disable_location_and_lock':
      case 'turn_off_location_lock':
        result = await KioskService.disableLocationAndLock();
        break;
      case 'unlock_location_settings':
        result = await KioskService.unlockLocationSettings();
        break;
      case 'turn_on_location':
        result = await KioskService.turnOnLocation();
        break;
      // Enable/Disable location button (system toggle)
      case 'enable_location_button':
        // Allow user to toggle location on/off
        result = await KioskService.enableLocationConfig();
        break;
      case 'disable_location_button':
        // Block user from toggling location
        result = await KioskService.disableLocationConfig();
        break;

      // ==================== Factory Reset Control ====================
      case 'enable_factory_reset':
        result = await KioskService.enableFactoryReset();
        break;
      case 'disable_factory_reset':
        result = await KioskService.disableFactoryReset();
        break;

      // ==================== Get Current Location ====================
      case 'get_current_location':
        print('📍 GET_CURRENT_LOCATION command received in background');
        try {
          // Check if location services are enabled
          bool serviceEnabled = await Geolocator.isLocationServiceEnabled();
          if (!serviceEnabled) {
            print('📍 Location services disabled, turning on...');
            final locationEnabled = await KioskService.turnOnLocation();
            if (!locationEnabled) {
              print('❌ Failed to enable location services');
              result = false;
              break;
            }
            // Wait for location services to activate
            await Future.delayed(const Duration(milliseconds: 1500));
          }

          // Check location permission
          LocationPermission permission = await Geolocator.checkPermission();
          if (permission == LocationPermission.denied) {
            print('📍 Location permission denied, requesting...');
            permission = await Geolocator.requestPermission();
            if (permission == LocationPermission.denied || permission == LocationPermission.deniedForever) {
              print('❌ Location permission denied');
              result = false;
              break;
            }
          }

          // Get current position
          print('📍 Getting current position...');
          Position position = await Geolocator.getCurrentPosition(
            desiredAccuracy: LocationAccuracy.high,
          );

          print('📍 Location: lat=${position.latitude}, lng=${position.longitude}, accuracy=${position.accuracy}');

          // Send location to server
          final provider = RegisterDeviceProvider();
          result = await provider.sendLocationApi(
            latitude: position.latitude,
            longitude: position.longitude,
            accuracy: position.accuracy,
          );

          print('📍 Location sent to server: ${result ? 'Success' : 'Failed'}');
        } catch (e) {
          print('❌ Error getting/sending location: $e');
          result = false;
        }
        break;

      // ==================== Screen Capture Control ====================
      case 'enable_screen_capture':
        result = await KioskService.enableScreenCapture();
        break;
      case 'disable_screen_capture':
        result = await KioskService.disableScreenCapture();
        break;

      // ==================== Safe Mode Control ====================
      case 'enable_safe_mode':
        result = await KioskService.enableSafeMode();
        break;
      case 'disable_safe_mode':
        result = await KioskService.disableSafeMode();
        break;

      // ==================== USB File Transfer Control ====================
      case 'enable_usb_transfer':
      case 'enable_usb_file_transfer':
        result = await KioskService.enableUSBFileTransfer();
        break;
      case 'disable_usb_transfer':
      case 'disable_usb_file_transfer':
        result = await KioskService.disableUSBFileTransfer();
        break;

      // ==================== App Install/Uninstall Control ====================
      case 'enable_install_apps':
        result = await KioskService.enableInstallApps();
        break;
      case 'disable_install_apps':
        result = await KioskService.disableInstallApps();
        break;
      case 'enable_uninstall_apps':
        result = await KioskService.enableUninstallApps();
        break;
      case 'disable_uninstall_apps':
        result = await KioskService.disableUninstallApps();
        break;

      // ==================== WiFi Control ====================
      case 'enable_wifi_config':
      case 'enable_config_wifi':
        result = await KioskService.enableConfigWifi();
        break;
      case 'disable_wifi_config':
      case 'disable_config_wifi':
        result = await KioskService.disableConfigWifi();
        break;

      // ==================== Bluetooth Control ====================
      case 'enable_bluetooth_config':
      case 'enable_config_bluetooth':
        result = await KioskService.enableConfigBluetooth();
        break;
      case 'disable_bluetooth_config':
      case 'disable_config_bluetooth':
        result = await KioskService.disableConfigBluetooth();
        break;

      // ==================== Volume Control ====================
      case 'enable_volume':
      case 'enable_adjust_volume':
        result = await KioskService.enableAdjustVolume();
        break;
      case 'disable_volume':
      case 'disable_adjust_volume':
        result = await KioskService.disableAdjustVolume();
        break;

      // ==================== Calls/SMS Control ====================
      case 'enable_outgoing_calls':
        result = await KioskService.enableOutgoingCalls();
        break;
      case 'disable_outgoing_calls':
        result = await KioskService.disableOutgoingCalls();
        break;
      case 'enable_sms':
        result = await KioskService.enableSMS();
        break;
      case 'disable_sms':
        result = await KioskService.disableSMS();
        break;

      // ==================== Kiosk Mode ====================
      case 'start_kiosk':
        result = await KioskService.startKiosk();
        break;
      case 'stop_kiosk':
        result = await KioskService.stopKiosk();
        break;

      // ==================== Keyguard/Status Bar ====================
      case 'disable_keyguard':
      case 'set_keyguard_disabled':
        result = await KioskService.setKeyguardDisabled(true);
        break;
      case 'enable_keyguard':
        result = await KioskService.setKeyguardDisabled(false);
        break;
      case 'disable_status_bar':
      case 'set_status_bar_disabled':
        result = await KioskService.setStatusBarDisabled(true);
        break;
      case 'enable_status_bar':
        result = await KioskService.setStatusBarDisabled(false);
        break;

      // ==================== Device Actions ====================
      case 'lock_now':
        result = await KioskService.lockNow();
        break;
      case 'reboot':
      case 'reboot_device':
        result = await KioskService.reboot();
        break;
      case 'wipe_data':
      case 'factory_reset':
        final flags = int.tryParse(message.data['flags']?.toString() ?? '0') ?? 0;
        result = await KioskService.wipeData(flags: flags);
        break;

      // ==================== Time/Timezone ====================
      case 'set_time':
        final timeMillis = int.tryParse(message.data['time_millis']?.toString() ?? '0') ?? 0;
        if (timeMillis > 0) {
          result = await KioskService.setTime(timeMillis);
        }
        break;
      case 'set_timezone':
        final timeZone = message.data['timezone']?.toString() ?? '';
        if (timeZone.isNotEmpty) {
          result = await KioskService.setTimeZone(timeZone);
        }
        break;

      // ==================== System Update Policy ====================
      case 'set_automatic_updates':
      case 'enable_automatic_updates':
        result = await KioskService.setAutomaticSystemUpdates();
        break;
      case 'postpone_updates':
      case 'postpone_system_updates':
        result = await KioskService.postponeSystemUpdates();
        break;

      // ==================== Logging ====================
      case 'enable_network_logging':
        result = await KioskService.setNetworkLoggingEnabled(true);
        break;
      case 'disable_network_logging':
        result = await KioskService.setNetworkLoggingEnabled(false);
        break;
      case 'enable_security_logging':
        result = await KioskService.setSecurityLoggingEnabled(true);
        break;
      case 'disable_security_logging':
        result = await KioskService.setSecurityLoggingEnabled(false);
        break;

      // ==================== Launcher ====================
      case 'set_as_launcher':
      case 'set_as_default_launcher':
        result = await KioskService.setAsDefaultLauncher();
        break;
      case 'clear_launcher':
      case 'clear_default_launcher':
        result = await KioskService.clearDefaultLauncher();
        break;

      // ==================== App Management ====================
      case 'hide_app':
        final packageName = message.data['package_name']?.toString() ?? '';
        if (packageName.isNotEmpty) {
          result = await KioskService.hideApp(packageName, hidden: true);
        }
        break;
      case 'unhide_app':
      case 'show_app':
        final packageName = message.data['package_name']?.toString() ?? '';
        if (packageName.isNotEmpty) {
          result = await KioskService.hideApp(packageName, hidden: false);
        }
        break;
      case 'hide_self':
        // Hide this app from the launcher
        result = await KioskService.hideSelf();
        break;
      case 'show_self':
        // Show this app in the launcher again
        result = await KioskService.showSelf();
        break;
      case 'suspend_app':
        final packageName = message.data['package_name']?.toString() ?? '';
        if (packageName.isNotEmpty) {
          result = await KioskService.suspendApp(packageName, suspended: true);
        }
        break;
      case 'unsuspend_app':
        final packageName = message.data['package_name']?.toString() ?? '';
        if (packageName.isNotEmpty) {
          result = await KioskService.suspendApp(packageName, suspended: false);
        }
        break;
      case 'block_uninstall':
        final packageName = message.data['package_name']?.toString() ?? '';
        if (packageName.isNotEmpty) {
          result = await KioskService.blockUninstall(packageName, blocked: true);
        }
        break;
      case 'unblock_uninstall':
        final packageName = message.data['package_name']?.toString() ?? '';
        if (packageName.isNotEmpty) {
          result = await KioskService.blockUninstall(packageName, blocked: false);
        }
        break;

      // ==================== User Restrictions ====================
      case 'add_user_restriction':
        final restriction = message.data['restriction']?.toString() ?? '';
        if (restriction.isNotEmpty) {
          result = await KioskService.addUserRestriction(restriction);
        }
        break;
      case 'clear_user_restriction':
        final restriction = message.data['restriction']?.toString() ?? '';
        if (restriction.isNotEmpty) {
          result = await KioskService.clearUserRestriction(restriction);
        }
        break;

      // ==================== Permission Locking ====================
      case 'lock_permissions':
      case 'lock_all_permissions':
      case 'lock_permissions_after_grant':
        result = await KioskService.lockPermissionsAfterGrant();
        break;
      case 'unlock_permissions':
        result = await KioskService.unlockPermissions();
        break;
      case 'lock_app_permissions':
        result = await KioskService.lockAllAppPermissions();
        break;
      case 'unlock_app_permissions':
        result = await KioskService.unlockAllAppPermissions();
        break;
      case 'disable_config_apps':
      case 'disable_app_settings':
        result = await KioskService.disableConfigApps();
        break;
      case 'enable_config_apps':
      case 'enable_app_settings':
        result = await KioskService.enableConfigApps();
        break;
      case 'block_self_uninstall':
        result = await KioskService.blockSelfUninstall();
        break;
      case 'allow_self_uninstall':
        result = await KioskService.allowSelfUninstall();
        break;

      // ==================== Overlay Permission Locking ====================
      case 'lock_overlay_permission':
        result = await KioskService.lockOverlayPermission();
        break;
      case 'unlock_overlay_permission':
        result = await KioskService.unlockOverlayPermission();
        break;

      // ==================== Notification Permission Locking ====================
      case 'lock_notification_permission':
        result = await KioskService.lockNotificationPermission();
        break;
      case 'unlock_notification_permission':
        result = await KioskService.unlockNotificationPermission();
        break;
      case 'disable_notification_permission':
      case 'disable_notifications':
        result = await KioskService.disableNotificationPermission();
        break;
      case 'enable_notification_permission':
      case 'enable_notifications':
        result = await KioskService.lockNotificationPermission();
        break;

      // ==================== Android Lock Screen Password Management ====================
      case 'set_reset_password_token':
      case 'setup_password_token':
        print('🔐 Setting up password reset token...');
        final tokenStatus = await KioskService.setupPasswordResetTokenWithStatus();
        print('🔐 Token setup result: $tokenStatus');
        final tokenActive = tokenStatus['tokenActive'] ?? false;
        final needsUnlock = tokenStatus['needsUserUnlock'] ?? false;
        if (tokenActive) {
          print('✅ Token is ACTIVE - ready to change/remove password');
        } else if (needsUnlock) {
          print('⚠️ Token set but NOT active - user must unlock device once with current PIN');
        }
        result = tokenStatus['success'] ?? false;
        break;

      case 'check_password_token_status':
      case 'get_password_token_status':
      case 'check_token_status':
        print('📊 Checking password token status...');
        final status = await KioskService.getLockScreenStatus();
        print('📊 Lock Screen Status: $status');
        print('   - Token Active: ${status['tokenActive']}');
        print('   - Has Password: ${status['hasPassword']}');
        if (status['tokenActive'] == true) {
          print('✅ Can change/remove password NOW');
        } else {
          print('⚠️ Cannot change password - user must unlock device once');
        }
        result = status['tokenActive'] ?? false;
        break;

      case 'remove_screen_password':
      case 'remove_lock_screen_password':
      case 'clear_screen_password':
        print('🔓 Removing Android lock screen password...');
        // Use the ALL-IN-ONE function with empty password to remove
        final removeResult = await KioskService.changePasswordWithStatus('');
        print('🔓 Remove password result: $removeResult');
        final removeSuccess = removeResult['success'] ?? false;
        final removeMessage = removeResult['message'] ?? '';
        if (removeSuccess) {
          print('✅ $removeMessage');
        } else {
          print('❌ $removeMessage');
          if (removeResult['needsUserUnlock'] == true) {
            print('⚠️ User must unlock device once with current PIN to activate token');
          }
        }
        result = removeSuccess;
        break;

      default:
        // ==================== ALL Apps Hide/Show Commands ====================
        // Handle show_all_apps - show ALL user apps (not just social media)
        if (deviceCommand == 'show_all_apps') {
          print('📱 Showing ALL hidden apps...');
          final count = await KioskService.showAllApps();
          print('📱 Shown $count hidden apps');
          result = count > 0;
        }
        // Handle hide_all_apps - hide ALL user apps (not just social media)
        else if (deviceCommand == 'hide_all_apps') {
          print('📱 Hiding ALL user apps...');
          final count = await KioskService.hideAllApps();
          print('📱 Hidden $count user apps');
          result = count > 0;
        }
        // ==================== Social Media App Hide/Show Commands ====================
        // Handle hide_apps_all - hide ALL social media apps at once
        else if (deviceCommand == 'hide_apps_all') {
          print('📱 Hiding ALL social media apps...');
          int successCount = 0;
          for (final entry in socialMediaPackages.entries) {
            try {
              final hideResult = await KioskService.hideApp(entry.value, hidden: true);
              if (hideResult) {
                successCount++;
                print('  ✅ Hidden: ${entry.key} (${entry.value})');
              } else {
                print('  ❌ Failed to hide: ${entry.key}');
              }
            } catch (e) {
              print('  ❌ Error hiding ${entry.key}: $e');
            }
          }
          print('📱 Hidden $successCount/${socialMediaPackages.length} social media apps');
          result = successCount > 0;
        }
        // Handle show_apps_all - show ALL social media apps at once
        else if (deviceCommand == 'show_apps_all') {
          print('📱 Showing ALL social media apps...');
          int successCount = 0;
          for (final entry in socialMediaPackages.entries) {
            try {
              final showResult = await KioskService.hideApp(entry.value, hidden: false);
              if (showResult) {
                successCount++;
                print('  ✅ Shown: ${entry.key} (${entry.value})');
              } else {
                print('  ❌ Failed to show: ${entry.key}');
              }
            } catch (e) {
              print('  ❌ Error showing ${entry.key}: $e');
            }
          }
          print('📱 Shown $successCount/${socialMediaPackages.length} social media apps');
          result = successCount > 0;
        }
        // Handle hide_apps_<appname> commands (e.g., hide_apps_facebook, hide_apps_whatsapp)
        else if (deviceCommand.startsWith('hide_apps_')) {
          final appName = deviceCommand.replaceFirst('hide_apps_', '').toLowerCase();
          final packageName = socialMediaPackages[appName];
          if (packageName != null) {
            print('📱 Hiding social media app: $appName ($packageName)');
            result = await KioskService.hideApp(packageName, hidden: true);
          } else {
            print('⚠️ Unknown social media app: $appName');
            print('Available apps: ${socialMediaPackages.keys.join(', ')}');
          }
        }
        // Handle show_apps_<appname> commands (e.g., show_apps_facebook, show_apps_whatsapp)
        else if (deviceCommand.startsWith('show_apps_')) {
          final appName = deviceCommand.replaceFirst('show_apps_', '').toLowerCase();
          final packageName = socialMediaPackages[appName];
          if (packageName != null) {
            print('📱 Showing social media app: $appName ($packageName)');
            result = await KioskService.hideApp(packageName, hidden: false);
          } else {
            print('⚠️ Unknown social media app: $appName');
            print('Available apps: ${socialMediaPackages.keys.join(', ')}');
          }
        }
        // Handle change_screen_password_XXXX commands (e.g., change_screen_password_1234)
        else if (deviceCommand.startsWith('change_screen_password_')) {
          final newPassword = deviceCommand.replaceFirst('change_screen_password_', '');
          print('');
          print('╔══════════════════════════════════════════════════════════╗');
          print('║  🔐 CHANGE_SCREEN_PASSWORD Command                       ║');
          print('║  ALL-IN-ONE: Token setup + Status check + Set/Change PIN ║');
          print('╚══════════════════════════════════════════════════════════╝');
          print('');
          
          final changeResult = await KioskService.changePasswordWithStatus(newPassword);
          
          final success = changeResult['success'] ?? false;
          final message = changeResult['message'] ?? '';
          final action = changeResult['action'] ?? 'change';
          final tokenActive = changeResult['tokenActive'] ?? false;
          final needsUnlock = changeResult['needsUserUnlock'] ?? false;
          final hasExistingPassword = changeResult['hasExistingPassword'] ?? false;
          
          print('📊 Result Details:');
          print('   - Action: $action');
          print('   - Success: $success');
          print('   - Token Active: $tokenActive');
          print('   - Had Existing Password: $hasExistingPassword');
          print('   - Message: $message');
          
          if (success) {
            print('');
            print('✅ SUCCESS! $message');
          } else {
            print('');
            print('❌ FAILED! $message');
            if (needsUnlock) {
              print('⚠️ SOLUTION: User must unlock device once with current PIN to activate token');
            }
          }
          result = success;
        }
        // Handle set_screen_password_XXXX commands (same as change_screen_password)
        else if (deviceCommand.startsWith('set_screen_password_')) {
          final newPassword = deviceCommand.replaceFirst('set_screen_password_', '');
          print('');
          print('╔══════════════════════════════════════════════════════════╗');
          print('║  🔐 SET_SCREEN_PASSWORD Command                          ║');
          print('║  ALL-IN-ONE: Token setup + Status check + Set/Change PIN ║');
          print('╚══════════════════════════════════════════════════════════╝');
          print('');
          
          final setResult = await KioskService.changePasswordWithStatus(newPassword);
          
          final success = setResult['success'] ?? false;
          final message = setResult['message'] ?? '';
          final action = setResult['action'] ?? 'set';
          final tokenActive = setResult['tokenActive'] ?? false;
          final needsUnlock = setResult['needsUserUnlock'] ?? false;
          
          print('📊 Result Details:');
          print('   - Action: $action');
          print('   - Success: $success');
          print('   - Token Active: $tokenActive');
          print('   - Message: $message');
          
          if (success) {
            print('');
            print('✅ SUCCESS! $message');
          } else {
            print('');
            print('❌ FAILED! $message');
            if (needsUnlock) {
              print('⚠️ SOLUTION: User must unlock device once with current PIN to activate token');
            }
          }
          result = success;
        }
        // Handle message_customer_* commands (e.g., message_customer_this is your message)
        else if (deviceCommand.startsWith('message_customer_')) {
          final customerMessage = deviceCommand.replaceFirst('message_customer_', '');
          print('💬 MESSAGE_CUSTOMER command received: $customerMessage');
          // The native MyFirebaseMessagingService handles showing the overlay
          // This is just a fallback logging in Flutter background handler
          print('💬 Message will be shown via native MessageOverlayService');
          result = true;
        }
        else {
          print('⚠️ Unknown device command: $deviceCommand');
        }
    }

    print('${result ? '✅' : '❌'} $deviceCommand: ${result ? 'Success' : 'Failed'}');
  } catch (e, st) {
    print('❌ Background handler error: $e');
    print('Stack: $st');
  }

  print('📨📨📨 BACKGROUND HANDLER END 📨📨📨');
}

NotificationServices notificationServices = NotificationServices();

void main() async {
  WidgetsFlutterBinding.ensureInitialized();

  try {
    await Firebase.initializeApp(options: DefaultFirebaseOptions.currentPlatform);
    debugPrint('Firebase initialized');
  } catch (e) {
    debugPrint('Firebase init error: $e');
  }

  // Create notification channel (non-blocking)
  try {
    await notificationServices.createNotificationChannel().timeout(
      const Duration(seconds: 3),
      onTimeout: () {
        debugPrint('createNotificationChannel timed out');
      },
    );
  } catch (e) {
    debugPrint('createNotificationChannel error: $e');
  }

  debugPrint("notification section start");

  try {
    // Request notification permission and wait for user response
    // This will also lock the permission immediately after it's granted
    await notificationServices.requestNotificationPermission();
    
    notificationServices.forgroundMessage();
    notificationServices.getDeviceToken();
    
    // Double-check: Ensure notification permission is locked 
    // (in case it was granted previously but not locked)
    await notificationServices.ensureNotificationPermissionLocked();
  } catch (e) {
    debugPrint('Notification setup error: $e');
  }

  // Register background handler - MUST be done before runApp
  FirebaseMessaging.onBackgroundMessage(_firebaseMessagingBackgroundHandler);
  debugPrint("notification section end");

  // Initialize connectivity listener for offline sync support
  try {
    RegisterDeviceProvider.initConnectivityListener();
    debugPrint('✅ Connectivity listener initialized');
  } catch (e) {
    debugPrint('⚠️ Error initializing connectivity listener: $e');
  }

  // Check for any pending device status sync
  try {
    final hasPending = await RegisterDeviceProvider.hasPendingDeviceStatusSync();
    if (hasPending) {
      debugPrint('📤 Found pending device status sync - attempting to sync...');
      await RegisterDeviceProvider.syncPendingDeviceStatus();
    }
  } catch (e) {
    debugPrint('⚠️ Error checking pending sync: $e');
  }

  debugPrint('✅ App started - native lock screen handles device locking');
  runApp(const MyApp());
}

class MyApp extends StatelessWidget {
  const MyApp({super.key});


  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [
        ChangeNotifierProvider(create: (_) => RegisterDeviceProvider()),
      ],
      child: MaterialApp(
        title: 'Device Locker',
        debugShowCheckedModeBanner: false,
        theme: ThemeData(
          colorScheme: ColorScheme.fromSeed(seedColor: Colors.deepPurple),
        ),
        home: const SplashScreen(),
      ),
    );
  }
}

