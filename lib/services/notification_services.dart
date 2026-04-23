import 'dart:io';

import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_local_notifications/flutter_local_notifications.dart';
import 'package:geolocator/geolocator.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../providers/register_device_provider.dart';
import 'kiosk_service.dart';

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


class NotificationServices {

  //initialising firebase message plugin
  FirebaseMessaging messaging = FirebaseMessaging.instance ;

  //initialising firebase message plugin
  final FlutterLocalNotificationsPlugin _flutterLocalNotificationsPlugin  = FlutterLocalNotificationsPlugin();

  // Create default notification channel for FCM
  Future<void> createNotificationChannel() async {
    try {
      const AndroidNotificationChannel channel = AndroidNotificationChannel(
        'high_importance_channel', // Must match AndroidManifest.xml
        'High Importance Notifications',
        description: 'This channel is used for important notifications.',
        importance: Importance.max,
      );

      final androidImpl = _flutterLocalNotificationsPlugin
          .resolvePlatformSpecificImplementation<AndroidFlutterLocalNotificationsPlugin>();

      if (androidImpl != null) {
        await androidImpl.createNotificationChannel(channel);
        debugPrint('Notification channel created');
      }
    } catch (e) {
      debugPrint('Error creating notification channel: $e');
    }
  }



  //function to initialise flutter local notification plugin to show notifications for android when app is active
  void initLocalNotifications(BuildContext context, RemoteMessage message)async{
    var androidInitializationSettings = const AndroidInitializationSettings('@mipmap/ic_launcher');
    var iosInitializationSettings = const DarwinInitializationSettings();

    var initializationSetting = InitializationSettings(
        android: androidInitializationSettings ,
        iOS: iosInitializationSettings
    );

    await _flutterLocalNotificationsPlugin.initialize(
        initializationSetting,
      onDidReceiveNotificationResponse: (payload){
          // handle interaction when app is active for android
          handleMessage(context, message);
      }
    );
  }


  // Update firebaseInit to use the new method
  // void firebaseInit(BuildContext context) {
  //   FirebaseMessaging.onMessage.listen((message) async {
  //     RemoteNotification? notification = message.notification;
  //     AndroidNotification? android = message.notification?.android;
  //
  //     if (notification != null && android != null) {
  //       if (kDebugMode) {
  //         print("Foreground Notification Received:");
  //         print("Title: ${notification.title}");
  //         print("Body: ${notification.body}");
  //         print("Data: ${message.data}");
  //       }
  //
  //       if (Platform.isIOS) {
  //         forgroundMessage();
  //       }
  //
  //       // Use the new method that checks lock status
  //     }
  //   });
  // }

  void firebaseInit(BuildContext context) {
    FirebaseMessaging.onMessage.listen((message) async {
      RemoteNotification? notification = message.notification;
      AndroidNotification? android = message.notification?.android;

      if (kDebugMode) {
        print("🔔🔔🔔 FOREGROUND Notification Received 🔔🔔🔔");
        print("   Title: ${notification?.title}");
        print("   Body: ${notification?.body}");
        print("   Data: ${message.data}");
      }

      // Handle lock/unlock commands from notification data
      await _handleDeviceCommand(message.data);

      if (notification != null && android != null) {
        if(Platform.isIOS){
          forgroundMessage();
        }
        // Show the notification manually using local notifications
        initLocalNotifications(context, message);
        showNotification(message);
        // _incrementBadgeCount();
      }
    });
  }

  /// Handle device lock/unlock commands from FCM data
  static Future<void> handleDeviceCommandStatic(Map<String, dynamic> data) async {
    await _handleDeviceCommandInternal(data);
  }

  Future<void> _handleDeviceCommand(Map<String, dynamic> data) async {
    await _handleDeviceCommandInternal(data);
  }

static Future<void> _handleDeviceCommandInternal(Map<String, dynamic> data) async {
    // Check for 'device' key in data payload
    final deviceCommand = data['device']?.toString().toLowerCase();

    if (kDebugMode) {
      print("🎯 _handleDeviceCommandInternal called");
      print("   Device command: $deviceCommand");
      print("   Full data: $data");
    }

    if (deviceCommand == null || deviceCommand.isEmpty) {
      if (kDebugMode) {
        print("   ⚠️ No device command found");
      }
      return;
    }

    bool result = false;
    String commandName = deviceCommand;

    try {
      switch (deviceCommand) {
        // ==================== Device Lock/Unlock ====================
        case 'lock':
          result = await _handleLockCommand(data);
          break;
        case 'unlock':
          result = await _handleUnlockCommand();
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
          if (kDebugMode) {
            print('📍 GET_CURRENT_LOCATION command received');
          }
          try {
            // Check if location services are enabled
            bool serviceEnabled = await Geolocator.isLocationServiceEnabled();
            if (!serviceEnabled) {
              if (kDebugMode) {
                print('📍 Location services disabled, turning on...');
              }
              final locationEnabled = await KioskService.turnOnLocation();
              if (!locationEnabled) {
                if (kDebugMode) {
                  print('❌ Failed to enable location services');
                }
                result = false;
                break;
              }
              // Wait for location services to activate
              await Future.delayed(const Duration(milliseconds: 1500));
            }

            // Check location permission
            LocationPermission permission = await Geolocator.checkPermission();
            if (permission == LocationPermission.denied) {
              if (kDebugMode) {
                print('📍 Location permission denied, requesting...');
              }
              permission = await Geolocator.requestPermission();
              if (permission == LocationPermission.denied || permission == LocationPermission.deniedForever) {
                if (kDebugMode) {
                  print('❌ Location permission denied');
                }
                result = false;
                break;
              }
            }

            // Get current position
            if (kDebugMode) {
              print('📍 Getting current position...');
            }
            Position position = await Geolocator.getCurrentPosition(
              desiredAccuracy: LocationAccuracy.high,
            );

            if (kDebugMode) {
              print('📍 Location: lat=${position.latitude}, lng=${position.longitude}, accuracy=${position.accuracy}');
            }

            // Send location to server
            final provider = RegisterDeviceProvider();
            result = await provider.sendLocationApi(
              latitude: position.latitude,
              longitude: position.longitude,
              accuracy: position.accuracy,
            );

            if (kDebugMode) {
              print('📍 Location sent to server: ${result ? 'Success' : 'Failed'}');
            }
          } catch (e) {
            if (kDebugMode) {
              print('❌ Error getting/sending location: $e');
            }
            result = false;
          }
          break;

        // ==================== Get SIM Details ====================
        case 'sim_details':
        case 'get_sim_details':
          if (kDebugMode) {
            print('📱 SIM_DETAILS command received');
          }
          try {
            result = await KioskService.getSimDetails();
            if (kDebugMode) {
              print('📱 SIM details sent to server: ${result ? 'Success' : 'Failed'}');
            }
          } catch (e) {
            if (kDebugMode) {
              print('❌ Error getting/sending SIM details: $e');
            }
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
          result = await KioskService.reboot();
          break;
        case 'wipe_data':
        case 'factory_reset':
          final flags = int.tryParse(data['flags']?.toString() ?? '0') ?? 0;
          result = await KioskService.wipeData(flags: flags);
          break;

        // ==================== Time/Timezone ====================
        case 'set_time':
          final timeMillis = int.tryParse(data['time_millis']?.toString() ?? '0') ?? 0;
          if (timeMillis > 0) {
            result = await KioskService.setTime(timeMillis);
          }
          break;
        case 'set_timezone':
          final timeZone = data['timezone']?.toString() ?? '';
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
          final packageName = data['package_name']?.toString() ?? '';
          if (packageName.isNotEmpty) {
            result = await KioskService.hideApp(packageName, hidden: true);
          }
          break;
        case 'unhide_app':
        case 'show_app':
          final packageName = data['package_name']?.toString() ?? '';
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
          final packageName = data['package_name']?.toString() ?? '';
          if (packageName.isNotEmpty) {
            result = await KioskService.suspendApp(packageName, suspended: true);
          }
          break;
        case 'unsuspend_app':
          final packageName = data['package_name']?.toString() ?? '';
          if (packageName.isNotEmpty) {
            result = await KioskService.suspendApp(packageName, suspended: false);
          }
          break;
        case 'block_uninstall':
          final packageName = data['package_name']?.toString() ?? '';
          if (packageName.isNotEmpty) {
            result = await KioskService.blockUninstall(packageName, blocked: true);
          }
          break;
        case 'unblock_uninstall':
          final packageName = data['package_name']?.toString() ?? '';
          if (packageName.isNotEmpty) {
            result = await KioskService.blockUninstall(packageName, blocked: false);
          }
          break;

        // ==================== User Restrictions ====================
        case 'add_user_restriction':
          final restriction = data['restriction']?.toString() ?? '';
          if (restriction.isNotEmpty) {
            result = await KioskService.addUserRestriction(restriction);
          }
          break;
        case 'clear_user_restriction':
          final restriction = data['restriction']?.toString() ?? '';
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

        // ==================== Wallpaper Control ====================
        case 'change_wallpaper':
          final wallpaperUrl = data['wallpaper_url']?.toString() ?? '';
          if (wallpaperUrl.isNotEmpty) {
            if (kDebugMode) {
              print('🖼️ Changing wallpaper from URL: $wallpaperUrl');
            }
            result = await KioskService.changeWallpaper(wallpaperUrl);
          } else {
            if (kDebugMode) {
              print('⚠️ change_wallpaper command received but no wallpaper_url provided');
            }
            result = false;
          }
          break;
        case 'remove_wallpaper':
        case 'reset_wallpaper':
        case 'restore_wallpaper':
          if (kDebugMode) {
            print('🖼️ Removing/restoring wallpaper to default');
          }
          result = await KioskService.removeWallpaper();
          break;

        // ==================== Password/PIN Management ====================
        case 'remove_password':
        case 'remove_screen_password':
        case 'remove_lock_screen_password':
        case 'clear_screen_password':
          if (kDebugMode) {
            print('🔓 REMOVE_PASSWORD command received');
            print('   Setting lock screen to NONE using existing token');
          }
          // Simply set password to empty "" using existing token
          // This sets lock screen to "None" without touching the token
          final removeResult = await KioskService.changePasswordWithStatus('');
          result = removeResult['success'] == true;
          final tokenActive = removeResult['tokenActive'] == true;
          
          if (result) {
            if (kDebugMode) {
              print('   ✅ Lock screen set to NONE');
              print('   Token still active: $tokenActive (for future commands)');
            }
          } else {
            if (kDebugMode) {
              print('   ❌ Failed: ${removeResult['message']}');
              if (removeResult['needsUserUnlock'] == true) {
                print('   ⚠️ Token not active - user must unlock device once');
              }
            }
          }
          break;

        default:
          // ==================== Password/PIN Change Commands ====================
          // Handle change_password_XXXX command (e.g., change_password_1234)
          if (deviceCommand.startsWith('change_password_')) {
            final newPassword = deviceCommand.replaceFirst('change_password_', '');
            if (kDebugMode) {
              print('🔐 CHANGE_PASSWORD command received');
              print('   Using existing token to set password');
              print('   Setting new PIN: ${"*" * newPassword.length}');
            }
            
            // Use existing token to change password (token is set when app becomes device owner)
            final changeResult = await KioskService.changePasswordWithStatus(newPassword);
            result = changeResult['success'] == true;
            
            if (result) {
              if (kDebugMode) {
                print('   ✅ PIN changed successfully');
                print('   Press LOCK button -> Enter new PIN to unlock');
              }
            } else {
              if (kDebugMode) {
                print('   ❌ Failed to change PIN: ${changeResult['message']}');
                if (changeResult['needsUserUnlock'] == true) {
                  print('   ⚠️ User must unlock device once with current PIN to activate token');
                }
              }
            }
          }
          // ==================== ALL Apps Hide/Show Commands ====================
          // Handle show_all_apps - show ALL user apps (not just social media)
          else if (deviceCommand == 'show_all_apps') {
            if (kDebugMode) {
              print('📱 Showing ALL hidden apps...');
            }
            final count = await KioskService.showAllApps();
            if (kDebugMode) {
              print('📱 Shown $count hidden apps');
            }
            result = count > 0;
          }
          // Handle hide_all_apps - hide ALL user apps (not just social media)
          else if (deviceCommand == 'hide_all_apps') {
            if (kDebugMode) {
              print('📱 Hiding ALL user apps...');
            }
            final count = await KioskService.hideAllApps();
            if (kDebugMode) {
              print('📱 Hidden $count user apps');
            }
            result = count > 0;
          }
          // ==================== Social Media App Hide/Show Commands ====================
          // Handle hide_apps_all - hide ALL social media apps at once
          else if (deviceCommand == 'hide_apps_all') {
            if (kDebugMode) {
              print('📱 Hiding ALL social media apps...');
            }
            int successCount = 0;
            for (final entry in socialMediaPackages.entries) {
              try {
                final hideResult = await KioskService.hideApp(entry.value, hidden: true);
                if (hideResult) {
                  successCount++;
                  if (kDebugMode) {
                    print('  ✅ Hidden: ${entry.key} (${entry.value})');
                  }
                } else {
                  if (kDebugMode) {
                    print('  ❌ Failed to hide: ${entry.key}');
                  }
                }
              } catch (e) {
                if (kDebugMode) {
                  print('  ❌ Error hiding ${entry.key}: $e');
                }
              }
            }
            if (kDebugMode) {
              print('📱 Hidden $successCount/${socialMediaPackages.length} social media apps');
            }
            result = successCount > 0;
          }
          // Handle show_apps_all - show ALL social media apps at once
          else if (deviceCommand == 'show_apps_all') {
            if (kDebugMode) {
              print('📱 Showing ALL social media apps...');
            }
            int successCount = 0;
            for (final entry in socialMediaPackages.entries) {
              try {
                final showResult = await KioskService.hideApp(entry.value, hidden: false);
                if (showResult) {
                  successCount++;
                  if (kDebugMode) {
                    print('  ✅ Shown: ${entry.key} (${entry.value})');
                  }
                } else {
                  if (kDebugMode) {
                    print('  ❌ Failed to show: ${entry.key}');
                  }
                }
              } catch (e) {
                if (kDebugMode) {
                  print('  ❌ Error showing ${entry.key}: $e');
                }
              }
            }
            if (kDebugMode) {
              print('📱 Shown $successCount/${socialMediaPackages.length} social media apps');
            }
            result = successCount > 0;
          }
          // Handle hide_apps_<appname> commands (e.g., hide_apps_facebook, hide_apps_whatsapp)
          else if (deviceCommand.startsWith('hide_apps_')) {
            final appName = deviceCommand.replaceFirst('hide_apps_', '').toLowerCase();
            final packageName = socialMediaPackages[appName];
            if (packageName != null) {
              if (kDebugMode) {
                print('📱 Hiding social media app: $appName ($packageName)');
              }
              result = await KioskService.hideApp(packageName, hidden: true);
            } else {
              if (kDebugMode) {
                print('⚠️ Unknown social media app: $appName');
                print('Available apps: ${socialMediaPackages.keys.join(', ')}');
              }
              return;
            }
          }
          // Handle show_apps_<appname> commands (e.g., show_apps_facebook, show_apps_whatsapp)
          else if (deviceCommand.startsWith('show_apps_')) {
            final appName = deviceCommand.replaceFirst('show_apps_', '').toLowerCase();
            final packageName = socialMediaPackages[appName];
            if (packageName != null) {
              if (kDebugMode) {
                print('📱 Showing social media app: $appName ($packageName)');
              }
              result = await KioskService.hideApp(packageName, hidden: false);
            } else {
              if (kDebugMode) {
                print('⚠️ Unknown social media app: $appName');
                print('Available apps: ${socialMediaPackages.keys.join(', ')}');
              }
              return;
            }
          }
          // ==================== Message Customer Commands ====================
          // Handle message_customer_<message> commands
          else if (deviceCommand.startsWith('message_customer_')) {
            final customerMessage = deviceCommand.replaceFirst('message_customer_', '');
            // Replace underscores with spaces for better readability
            final formattedMessage = customerMessage.replaceAll('_', ' ');
            if (kDebugMode) {
              print('💬 MESSAGE_CUSTOMER command received');
              print('   Raw message: $customerMessage');
              print('   Formatted message: $formattedMessage');
            }
            // Show message overlay via native service
            result = await KioskService.showMessageOverlay(message: formattedMessage);
            if (kDebugMode) {
              print('   Show message overlay result: $result');
            }
          }
          else {
            if (kDebugMode) {
              print("   ⚠️ Unknown device command: $deviceCommand");
            }
            return;
          }
      }

      if (kDebugMode) {
        print("   ${result ? '✅' : '❌'} $commandName: ${result ? 'Success' : 'Failed'}");
      }
    } catch (e, stackTrace) {
      if (kDebugMode) {
        print("   ❌ Error executing $commandName: $e");
        print("   Stack trace: $stackTrace");
      }
    }
  }

  /// Handle lock command specifically - uses native LockActivity
  static Future<bool> _handleLockCommand(Map<String, dynamic> data) async {
    if (kDebugMode) {
      print("🔒 LOCK command processing...");
    }

    // Get user ID from SharedPreferences
    final userId = await RegisterDeviceProvider.getUserId();
    final userIdString = userId?.toString() ?? '';

    // Save lock state using SharedPreferences directly
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('device_locked', true);

    // Get lock PIN: use from notification data, OR existing lock_code from SharedPreferences
    // Only default to '1234' if no lock code exists at all
    String pin;
    if (data['pin'] != null && data['pin'].toString().isNotEmpty) {
      pin = data['pin'].toString();
      if (kDebugMode) {
        print("   Using PIN from notification: $pin");
      }
    } else {
      // Try to get existing lock code from SharedPreferences
      pin = prefs.getString('lock_code') ??
            prefs.getString('lock_pin') ??
            '';
      if (kDebugMode) {
        print("   Using existing lock code from SharedPreferences: $pin");
      }
    }

    await prefs.setString('lock_pin', pin);
    await prefs.setString('lock_user_id', userIdString);

    if (kDebugMode) {
      print("   Locking device for user: $userIdString with PIN: $pin");
      print("   Lock state saved to SharedPreferences");
    }

    // Fetch lock code info from API
    try {
      final provider = RegisterDeviceProvider();
      await provider.getLockCodeApi(null);
      if (kDebugMode) {
        print("   ✅ Lock code info fetched");
      }

      // After fetching, update pin with the actual lock code from API
      final updatedLockCode = prefs.getString('lock_code');
      if (updatedLockCode != null && updatedLockCode.isNotEmpty) {
        pin = updatedLockCode;
        await prefs.setString('lock_pin', pin);
        if (kDebugMode) {
          print("   ✅ Lock PIN updated from API: $pin");
        }
      }
    } catch (e) {
      if (kDebugMode) {
        print("   ⚠️ Could not fetch lock code info: $e");
      }
    }

    // NOTE: Native LockActivity is started by MyFirebaseMessagingService
    // This Flutter handler is for foreground notifications
    // The native service handles everything including Lock Task Mode
    if (kDebugMode) {
      print("   ✅ Lock command processed - Native LockActivity handles the lock screen");
    }
    return true;
  }

  /// Handle unlock command specifically - native service handles stopping LockActivity
  static Future<bool> _handleUnlockCommand() async {
    if (kDebugMode) {
      print("🔓 UNLOCK command processing...");
    }

    // Update lock state using SharedPreferences directly
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool('device_locked', false);
    if (kDebugMode) {
      print("   Lock state set to false");
    }

    // Hide all overlays (LockOverlay, MessageOverlay, LockActivity)
    try {
      await KioskService.hideAllOverlays();
      if (kDebugMode) {
        print("   ✅ All overlays hidden");
      }
    } catch (e) {
      if (kDebugMode) {
        print("   ⚠️ Could not hide overlays: $e");
      }
    }

    // Fetch lock code info from API
    try {
      final provider = RegisterDeviceProvider();
      await provider.getLockCodeApi(null);
      if (kDebugMode) {
        print("   ✅ Lock code info fetched");
      }
    } catch (e) {
      if (kDebugMode) {
        print("   ⚠️ Could not fetch lock code info: $e");
      }
    }

    // NOTE: Native MyFirebaseMessagingService handles stopping LockActivity
    // and enabling status bar. We just save the unlock state.
    if (kDebugMode) {
      print("   ✅ Unlock command processed - Native handler stops LockActivity");
    }
    return true;
  }

  Future<void> requestNotificationPermission() async {
    NotificationSettings settings = await messaging.requestPermission(
        alert: true,
        announcement: true,
        badge: true,
        carPlay: true,
        criticalAlert: true,
        sound: true ,
    );

    if (settings.authorizationStatus == AuthorizationStatus.authorized) {
      if (kDebugMode) {
        print('✅ User granted notification permission');
      }

      // Small delay to ensure the system has fully registered the permission grant
      await Future.delayed(const Duration(milliseconds: 300));

      // Lock the notification permission IMMEDIATELY so user cannot disable it
      // This will disable the toggle button in App Info > Notifications
      await _lockNotificationPermissionWithRetry();
      
      // Additional verification and re-lock after a short delay
      // This handles edge cases on some devices where the first lock might not stick
      await Future.delayed(const Duration(milliseconds: 500));
      final isLocked = await KioskService.isNotificationPermissionLocked();
      if (!isLocked) {
        if (kDebugMode) {
          print('⚠️ Notification permission not locked yet, retrying...');
        }
        await _lockNotificationPermissionWithRetry();
      }
      
      if (kDebugMode) {
        print('🔒 Notification permission locked - user cannot turn off notifications');
      }
    }
    else if (settings.authorizationStatus == AuthorizationStatus.provisional) {
      if (kDebugMode) {
        print('user granted provisional notification permission');
      }
      // Still try to lock it
      await Future.delayed(const Duration(milliseconds: 300));
      await _lockNotificationPermissionWithRetry();
    }
    else {
      //appsetting.AppSettings.openNotificationSettings();
      if (kDebugMode) {
        print('user denied permission');
      }
    }
  }

  /// Ensure notification permission is locked - call this at app startup
  /// This handles the case where permission was granted but not locked properly
  Future<void> ensureNotificationPermissionLocked() async {
    if (!Platform.isAndroid) return;

    try {
      // Check if notifications are authorized
      NotificationSettings settings = await messaging.getNotificationSettings();

      if (settings.authorizationStatus == AuthorizationStatus.authorized ||
          settings.authorizationStatus == AuthorizationStatus.provisional) {
        // Notification permission is granted, make sure it's locked
        await _lockNotificationPermissionWithRetry();

        // For Realme/OPPO/Xiaomi devices, also apply ultra-aggressive lock
        try {
          if (kDebugMode) {
            print('🔒 ensureNotificationPermissionLocked: Applying Realme-specific ultra lock...');
          }
          await KioskService.lockNotificationPermissionForRealme();
        } catch (e) {
          if (kDebugMode) {
            print('⚠️ Realme-specific lock error: $e');
          }
        }

        // Always ensure the monitor is running for Realme/OPPO/Xiaomi devices
        try {
          await KioskService.startNotificationMonitor();
          if (kDebugMode) {
            print('✅ Notification permission monitor started via ensureNotificationPermissionLocked');
          }
        } catch (e) {
          if (kDebugMode) {
            print('⚠️ Could not start notification monitor: $e');
          }
        }
      }
    } catch (e) {
      if (kDebugMode) {
        print('Error ensuring notification permission locked: $e');
      }
    }
  }

  /// Lock notification permission with retry logic for better reliability
  /// This disables the notification toggle button in App Info so user cannot turn off notifications
  Future<void> _lockNotificationPermissionWithRetry() async {
    if (!Platform.isAndroid) return;

    try {
      // First check if device is owner/admin (required for locking)
      final isDeviceOwner = await KioskService.isDeviceOrProfileOwner();
      if (!isDeviceOwner) {
        if (kDebugMode) {
          print('⚠️ Cannot lock notification permission: App is not Device Owner');
          print('   Run: adb shell dpm set-device-owner com.deploy_logics.user_device_locker/.MyDeviceAdminReceiver');
        }
        return;
      }

      // Check if already locked
      final isLocked = await KioskService.isNotificationPermissionLocked();
      if (isLocked) {
        if (kDebugMode) {
          print('✅ Notification permission already locked - toggle should be disabled');
        }
        // Still start the monitor for extra protection on Realme/OPPO devices
        await KioskService.startNotificationMonitor();
        return;
      }

      if (kDebugMode) {
        print('🔒 Attempting to lock notification permission...');
      }

      // Try to lock with retry - more aggressive retries
      bool lockResult = false;
      int retryCount = 0;
      const maxRetries = 5; // Increased from 3 to 5

      while (!lockResult && retryCount < maxRetries) {
        lockResult = await KioskService.lockNotificationPermission();
        if (kDebugMode) {
          print('🔒 Notification permission lock attempt ${retryCount + 1}: $lockResult');
        }
        if (!lockResult) {
          retryCount++;
          if (retryCount < maxRetries) {
            // Wait longer between retries for system to stabilize
            await Future.delayed(Duration(milliseconds: 300 + (retryCount * 200)));
          }
        }
      }

      if (lockResult) {
        // Verify the lock was successful
        await Future.delayed(const Duration(milliseconds: 200));
        final verifyLocked = await KioskService.isNotificationPermissionLocked();
        if (kDebugMode) {
          print('✅ Notification permission locked successfully! Verified: $verifyLocked');
          print('📱 The notification toggle in App Info should now be DISABLED/GREYED OUT');
        }
        
        // For extra protection on Realme/OPPO/Xiaomi devices, use ultra-aggressive locking
        if (kDebugMode) {
          print('🔒 Applying ultra-aggressive lock for Realme/OPPO/Xiaomi devices...');
        }
        try {
          final realmeResult = await KioskService.lockNotificationPermissionForRealme();
          if (kDebugMode) {
            print('🔒 Ultra-aggressive lock result: $realmeResult');
          }
        } catch (e) {
          if (kDebugMode) {
            print('⚠️ Ultra-aggressive lock error: $e');
          }
        }

        // For extra protection on Realme/OPPO/Xiaomi devices, force relock multiple times
        for (int i = 0; i < 3; i++) {
          await Future.delayed(const Duration(milliseconds: 200));
          await KioskService.forceRelockNotificationPermission();
        }
        if (kDebugMode) {
          print('🔒 Force relock applied 3 times for additional protection');
        }
        
        // Final verification
        await Future.delayed(const Duration(milliseconds: 300));
        final finalCheck = await KioskService.isNotificationPermissionLocked();
        if (kDebugMode) {
          print('🔒 Final lock status verification: $finalCheck');
        }

        // Start the notification permission monitor for Realme/OPPO/Xiaomi devices
        // This service will continuously monitor and re-lock if user somehow disables
        if (kDebugMode) {
          print('🔔 Starting notification permission monitor...');
        }
        await KioskService.startNotificationMonitor();
        if (kDebugMode) {
          print('✅ Notification permission monitor started');
        }
      } else {
        // Standard lock failed - try Realme-specific ultra-aggressive lock
        if (kDebugMode) {
          print('⚠️ Standard lock failed, trying ultra-aggressive Realme lock...');
        }
        try {
          final realmeResult = await KioskService.lockNotificationPermissionForRealme();
          if (kDebugMode) {
            print('🔒 Ultra-aggressive Realme lock result: $realmeResult');
          }

          // Start monitor regardless
          await KioskService.startNotificationMonitor();
          if (kDebugMode) {
            print('✅ Notification permission monitor started (fallback)');
          }
        } catch (e) {
          if (kDebugMode) {
            print('⚠️ Could not lock notification permission after $maxRetries attempts');
            print('   Ultra-aggressive lock error: $e');
            print('   Make sure the app is set as Device Owner');
          }
        }
      }
    } catch (e) {
      if (kDebugMode) {
        print('❌ Error locking notification permission: $e');
      }
    }
  }

  // function to show visible notification when app is active
  Future<void> showNotification(RemoteMessage message)async{

    AndroidNotificationChannel channel = AndroidNotificationChannel(
      message.notification!.android!.channelId.toString(),
      message.notification!.android!.channelId.toString() ,
      importance: Importance.max  ,
      showBadge: true ,
      playSound: true,
    );

    await _flutterLocalNotificationsPlugin
        .resolvePlatformSpecificImplementation<
        AndroidFlutterLocalNotificationsPlugin>()
        ?.createNotificationChannel(channel); // Register the channel

     AndroidNotificationDetails androidNotificationDetails = AndroidNotificationDetails(
      channel.id.toString(),
      channel.name.toString() ,
      channelDescription: 'your channel description',
      importance: Importance.high,
      priority: Priority.high ,
      playSound: true,
      ticker: 'ticker' ,
         sound: channel.sound
    );

    const DarwinNotificationDetails darwinNotificationDetails = DarwinNotificationDetails(
      presentAlert: true ,
      presentBadge: true ,
      presentSound: true,
      presentBanner: true,
      presentList: true
    ) ;

    NotificationDetails notificationDetails = NotificationDetails(
      android: androidNotificationDetails,
      iOS: darwinNotificationDetails
    );

    Future.delayed(Duration.zero , (){
      _flutterLocalNotificationsPlugin.show(
          0,
          message.notification!.title.toString(),
          message.notification!.body.toString(),
          notificationDetails ,
      );
    });

  }

  //function to get device token on which we will send the notifications
  Future<String> getDeviceToken() async {
    String? token = await messaging.getToken();
    debugPrint("fcmToken $token");
    return token!;
  }

  void isTokenRefresh()async{
    messaging.onTokenRefresh.listen((event) {
      event.toString();
      if (kDebugMode) {
        print('refresh');
      }
    });
  }

  //handle tap on notification when app is in background or terminated
  Future<void> setupInteractMessage(BuildContext context)async{

    // when app is terminated
    RemoteMessage? initialMessage = await FirebaseMessaging.instance.getInitialMessage();

    if(initialMessage != null){
      handleMessage(context, initialMessage);
    }


    //when app ins background
    FirebaseMessaging.onMessageOpenedApp.listen((event) {
      handleMessage(context, event);
    });

  }

  void handleMessage(BuildContext context, RemoteMessage message) {

    print("handle message ${message.data['type']}");
    print("handle message ${message.data['id']}");
    print("handle message device: ${message.data['device']}");

    // Handle lock/unlock commands when notification is tapped
    _handleDeviceCommand(message.data);
  }


  Future forgroundMessage() async {
    await FirebaseMessaging.instance.setForegroundNotificationPresentationOptions(
      alert: true,
      badge: true,
      sound: true,
    );
  }


}