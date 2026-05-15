import 'dart:convert';
import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:flutter/foundation.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import '../models/mobile_api_models.dart';
import '../providers/register_device_provider.dart';
import '../util/api_response.dart';
import '../util/mobile_api_endpoints.dart';

/// Service to manage secret command codes for dialer-based device control.
///
/// [fetchAndStoreCodesFromApi] is invoked automatically after every
/// [RegisterDeviceProvider.getLockCodeApi] call (`finally` block), so `*#*#9009#*#*`
/// mappings stay in sync with the server whenever the unlock/lock metadata is refreshed.
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
  /// API endpoint: /mobile/get_codes?customer_id=
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
      
      final url = Uri.parse(
        MobileApiEndpoints.url(MobileApiEndpoints.getCodes(customerId)),
      );
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

      final api = ApiResponse.fromHttpBody(response.body);
      if (ApiResponse.isHttpOk(response.statusCode) &&
          api != null &&
          api.success &&
          api.data != null) {
          final secretCodes = SecretCodesData.fromApiData(api.data!);
          if (secretCodes.codeToCommand.isEmpty) {
            debugPrint(
              'SecretCodeService: API envelope OK but 0 dialer codes parsed — '
              'check data shape (nested objects / key names). Not overwriting stored map.',
            );
            final existing = await getCommandCodes();
            if (existing.isEmpty) {
              return await initializeWithDefaultCodes();
            }
            return true;
          }
          await storeCommandCodes(secretCodes.codeToCommand);
          debugPrint(
            'SecretCodeService: Fetched and stored ${secretCodes.codeToCommand.length} codes from API',
          );
          debugPrint('SecretCodeService: Codes mapping: ${secretCodes.codeToCommand}');
          return true;
      }

      debugPrint(
        'SecretCodeService: API response invalid (${api?.code ?? response.statusCode}), using default codes',
      );
      return await initializeWithDefaultCodes();
    } catch (e) {
      debugPrint('SecretCodeService: Error fetching codes from API: $e');
      // Fallback to default codes if API fails
      return await initializeWithDefaultCodes();
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
