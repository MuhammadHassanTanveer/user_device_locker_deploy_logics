import 'dart:convert';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import '../providers/register_device_provider.dart';
import '../util/app_constants.dart';

/// Service to manage secret command codes for dialer-based device control.
/// 
/// Users can dial *#*#9009#*#* on the phone dialer to open a command dialog.
/// The codes entered in the dialog are matched against stored codes from API.
/// 
/// Example usage:
/// ```dart
/// // Fetch and store codes from API
/// await SecretCodeService.fetchAndStoreCodesFromApi();
/// 
/// // Get stored codes
/// final codes = await SecretCodeService.getCommandCodes();
/// ```
class SecretCodeService {
  static const String _secretCodesKey = 'secret_command_codes';
  static const String _fcmTokenKey = 'cached_fcm_token';

  /// Fetch command codes from API and store them
  /// API endpoint: https://locker.deploylogics.com/api/get_codes?customer_id=
  /// 
  /// Response format:
  /// {
  ///   "success": true,
  ///   "data": {
  ///     "lock_device_code": "136331",
  ///     "unlock_device_code": "224382",
  ///     "enable_camera_code": "193949",
  ///     ...
  ///   }
  /// }
  static Future<bool> fetchAndStoreCodesFromApi() async {
    try {
      // Get customer_id (user_id) from SharedPreferences
      final customerId = await RegisterDeviceProvider.getUserId();
      
      if (customerId == null) {
        debugPrint('SecretCodeService: No customer_id found, using default codes');
        return await initializeWithDefaultCodes();
      }
      
      final url = Uri.parse('${AppConstants.baseUrl}/get_codes?customer_id=$customerId');
      debugPrint('SecretCodeService: Fetching codes from: $url');
      
      final response = await http.get(
        url,
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
        },
      );

      debugPrint('SecretCodeService: Response status: ${response.statusCode}');
      debugPrint('SecretCodeService: Response body: ${response.body}');

      if (response.statusCode == 200) {
        final responseData = jsonDecode(response.body);
        
        if (responseData['success'] == true && responseData['data'] != null) {
          final data = responseData['data'];
          
          // Map API response codes to commands
          // The API returns: "enable_camera_code": "193949"
          // We need to store: "193949" -> "enable_camera"
          final Map<String, String> codes = {};
          
          // Map each code from API to its command
          _mapCodeToCommand(codes, data, 'lock_device_code', 'lock_device');
          _mapCodeToCommand(codes, data, 'unlock_device_code', 'unlock_device');
          _mapCodeToCommand(codes, data, 'enable_camera_code', 'enable_camera');
          _mapCodeToCommand(codes, data, 'disable_camera_code', 'disable_camera');
          _mapCodeToCommand(codes, data, 'show_social_app_code', 'show_social_apps');
          _mapCodeToCommand(codes, data, 'hide_social_app_code', 'hide_social_apps');
          _mapCodeToCommand(codes, data, 'show_all_apps_code', 'show_all_apps');
          _mapCodeToCommand(codes, data, 'hide_all_apps_code', 'hide_all_apps');
          _mapCodeToCommand(codes, data, 'change_screen_password_code', 'change_screen_password');
          _mapCodeToCommand(codes, data, 'remove_screen_password_code', 'remove_screen_password');
          _mapCodeToCommand(codes, data, 'reboot_device_code', 'reboot');
          _mapCodeToCommand(codes, data, 'send_message_to_customer_code', 'send_message');
          _mapCodeToCommand(codes, data, 'change_wallpaper_code', 'change_wallpaper');
          _mapCodeToCommand(codes, data, 'remove_change_wallpaper_code', 'remove_wallpaper');
          _mapCodeToCommand(codes, data, 'sim_detail_code', 'get_sim_details');
          _mapCodeToCommand(codes, data, 'enable_location_code', 'enable_location');
          _mapCodeToCommand(codes, data, 'disable_location_code', 'disable_location');
          _mapCodeToCommand(codes, data, 'turn_on_location_code', 'turn_on_location');
          _mapCodeToCommand(codes, data, 'turn_off_location_code', 'turn_off_location');
          _mapCodeToCommand(codes, data, 'get_location_code', 'get_current_location');
          _mapCodeToCommand(codes, data, 'enable_factory_reset_code', 'enable_factory_reset');
          _mapCodeToCommand(codes, data, 'disable_factory_reset_code', 'disable_factory_reset');
          _mapCodeToCommand(codes, data, 'uninstall_code', 'uninstall');
          _mapCodeToCommand(codes, data, 'get_fcm_token_code', 'get_fcm_token');
          
          await storeCommandCodes(codes);
          debugPrint('SecretCodeService: Fetched and stored ${codes.length} codes from API');
          debugPrint('SecretCodeService: Codes mapping: $codes');
          return true;
        }
        
        debugPrint('SecretCodeService: API response invalid, using default codes');
        return await initializeWithDefaultCodes();
      } else {
        debugPrint('SecretCodeService: API error ${response.statusCode}, using default codes');
        return await initializeWithDefaultCodes();
      }
    } catch (e) {
      debugPrint('SecretCodeService: Error fetching codes from API: $e');
      // Fallback to default codes if API fails
      return await initializeWithDefaultCodes();
    }
  }

  /// Helper method to map API code to command
  static void _mapCodeToCommand(Map<String, String> codes, Map<String, dynamic> data, String apiKey, String command) {
    if (data.containsKey(apiKey) && data[apiKey] != null) {
      final code = data[apiKey].toString();
      if (code.isNotEmpty) {
        codes[code] = command;
      }
    }
  }

  /// Store command codes from API response
  /// 
  /// [codes] is a Map where:
  /// - Key: The numeric code user will enter (e.g., "193949")
  /// - Value: The command to execute (e.g., "enable_camera")
  static Future<bool> storeCommandCodes(Map<String, String> codes) async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final jsonString = jsonEncode(codes);
      await prefs.setString(_secretCodesKey, jsonString);
      debugPrint('SecretCodeService: Stored ${codes.length} command codes');
      return true;
    } catch (e) {
      debugPrint('SecretCodeService: Error storing codes: $e');
      return false;
    }
  }

  /// Get stored command codes
  static Future<Map<String, String>> getCommandCodes() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      final jsonString = prefs.getString(_secretCodesKey);
      if (jsonString == null || jsonString.isEmpty) {
        return {};
      }
      final Map<String, dynamic> decoded = jsonDecode(jsonString);
      return decoded.map((key, value) => MapEntry(key, value.toString()));
    } catch (e) {
      debugPrint('SecretCodeService: Error getting codes: $e');
      return {};
    }
  }

  /// Add or update a single command code
  static Future<bool> addCommandCode(String code, String command) async {
    final codes = await getCommandCodes();
    codes[code] = command;
    return storeCommandCodes(codes);
  }

  /// Remove a command code
  static Future<bool> removeCommandCode(String code) async {
    final codes = await getCommandCodes();
    codes.remove(code);
    return storeCommandCodes(codes);
  }

  /// Clear all command codes
  static Future<bool> clearCommandCodes() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      await prefs.remove(_secretCodesKey);
      return true;
    } catch (e) {
      debugPrint('SecretCodeService: Error clearing codes: $e');
      return false;
    }
  }

  /// Get FCM Token
  static Future<String> getFcmToken() async {
    try {
      final token = await FirebaseMessaging.instance.getToken();
      if (token != null) {
        // Cache the token
        final prefs = await SharedPreferences.getInstance();
        await prefs.setString(_fcmTokenKey, token);
        debugPrint('SecretCodeService: FCM Token: $token');
        return token;
      }
      return 'Unable to get FCM token';
    } catch (e) {
      debugPrint('SecretCodeService: Error getting FCM token: $e');
      return 'Error: $e';
    }
  }

  /// Get cached FCM Token (if available)
  static Future<String?> getCachedFcmToken() async {
    try {
      final prefs = await SharedPreferences.getInstance();
      return prefs.getString(_fcmTokenKey);
    } catch (e) {
      return null;
    }
  }

  /// Get default command codes (fallback if API fails)
  static Map<String, String> getDefaultCodes() {
    return {
      // These are placeholder codes - actual codes come from API
      "101": "enable_camera",
      "102": "disable_camera",
      "103": "enable_location",
      "104": "disable_location",
      "105": "turn_on_location",
      "106": "enable_factory_reset",
      "107": "disable_factory_reset",
      "108": "lock_device",
      "109": "unlock_device",
      "110": "reboot",
      "111": "get_sim_details",
      "112": "get_current_location",
      "113": "show_social_apps",
      "114": "hide_social_apps",
      "115": "change_wallpaper",
      "116": "remove_wallpaper",
      
      // Show/Hide ALL apps (not just social media)
      "117": "show_all_apps",
      "118": "hide_all_apps",
      
      // FCM Token - Display FCM token in dialog
      "136": "get_fcm_token",
      
      // Prepare for uninstall
      "999": "uninstall",
    };
  }

  /// Initialize with default codes (call this when API fails)
  static Future<bool> initializeWithDefaultCodes() async {
    final existingCodes = await getCommandCodes();
    if (existingCodes.isEmpty) {
      return storeCommandCodes(getDefaultCodes());
    }
    return true;
  }
}
