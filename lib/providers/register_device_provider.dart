import 'dart:convert';
import 'dart:math';

import 'package:flutter/material.dart';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import '../models/mobile_api_models.dart';
import '../services/secret_code_service.dart';
import '../util/api_response.dart';
import '../util/mobile_api_endpoints.dart';
import '../widgets/snackbar_widget.dart';


class RegisterDeviceProvider with ChangeNotifier{

  static const String _userIdKey = 'registered_device_id';
  static const String _imei1Key = 'device_imei1';
  static const String _imei2Key = 'device_imei2';
  static const String _serialNoKey = 'device_serial_no';
  static const String _customerNameKey = 'customer_name';
  static const String _customerCodeKey = 'customer_code';
  static const String _mobileModelKey = 'mobile_model';
  static const String _isActiveKey = 'customer_is_active';

  // Unlock code related keys (lock_code / lock_pin kept for native compatibility)
  static const String _unlockCodeKey = 'unlock_code';
  static const String _lockCodeKey = 'lock_code';
  static const String _lockPinKey = 'lock_pin';
  static const String _lockStatusKey = 'lock_status';
  static const String _retailerIdKey = 'retailer_id';
  static const String _retailerCompanyCodeKey = 'retailer_company_code';
  static const String _retailerPhoneKey = 'retailer_phone';
  static const String _retailerNameKey = 'retailer_name';
  static const String _retailerNameUrduKey = 'retailer_name_urdu';

  // Pending sync key for offline support
  static const String _pendingDeviceStatusSyncKey = 'pending_device_status_sync';
  static const String _pendingUninstallStatusSyncKey = 'pending_uninstall_status_sync';

  bool isLoading = false;

  static ApiResponse? _readApiResponse(http.Response response, {String? logLabel}) {
    if (logLabel != null) {
      debugPrint('$logLabel status=${response.statusCode} body=${response.body}');
    }
    return ApiResponse.fromHttpBody(response.body);
  }

  static Future<Map<String, dynamic>?> _buildImeiRequestBody() async {
    final imei1 = await getImei1() ?? '';
    final imei2 = await getImei2() ?? '';
    if (imei1.isEmpty) return null;

    final body = <String, dynamic>{'imei_1': imei1};
    if (imei2.isNotEmpty) {
      body['imei_2'] = imei2;
    }
    return body;
  }

  Future<bool> registerDeviceApi(context, {
    required String imei_1,
    required String imei_2,
    required String fcm_token,
    required bool isDualImei,
  })
  async {


    try {
      final url = Uri.parse(MobileApiEndpoints.url(MobileApiEndpoints.registerDevice));

      final Map<String, dynamic> body = {
        "imei_1": imei_1,
        "fcm_token": fcm_token,
      };
      if (isDualImei) {
        body["imei_2"] = imei_2;
      }
      final response = await http.post(
        url,
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
        },
        body: jsonEncode(body),
      );

      final api = _readApiResponse(response, logLabel: 'registerdevice');
      if (!ApiResponse.isHttpOk(response.statusCode) || api == null) {
        showCustomSnackBar(
          context,
          api?.message ?? 'Failed to register device',
        );
        return false;
      }

      if (api.success) {
        final data = api.data;
        if (data == null) {
          showCustomSnackBar(context, 'Invalid server response: missing data');
          return false;
        }

        final registration = RegisterDeviceData.fromJson(data);
        if (registration.customerId == 0) {
          debugPrint('❌ Registration response missing customer_id: $data');
          showCustomSnackBar(
            context,
            'Invalid server response: missing customer ID',
          );
          return false;
        }

        await _saveRegistrationData(registration);
        debugPrint(
          'Registration saved: id=${registration.customerId}, '
          'name=${registration.customerName}, code=${registration.customerCode}',
        );

        await _saveImeiNumbers(imei_1, isDualImei ? imei_2 : '');
        debugPrint('IMEI numbers saved: IMEI1=$imei_1, IMEI2=${isDualImei ? imei_2 : ''}');

        showCustomSnackBar(
          context,
          api.message?.isNotEmpty == true
              ? api.message!
              : 'Device registered successfully',
          isError: false,
        );
        return true;
      }

      showCustomSnackBar(context, api.message ?? 'Failed to register device');
      return false;
    } catch (error) {
      debugPrint('Error updating user $error');
      showCustomSnackBar(context, 'Network error. Please try again.');
      return false;
    } finally {
      isLoading = false;
      notifyListeners();
    }
  }

  /// Save IMEI numbers to SharedPreferences
  Future<void> _saveImeiNumbers(String imei1, String imei2) async {
    final prefs = await SharedPreferences.getInstance();

    // Save IMEI1 if not empty
    if (imei1.isNotEmpty) {
      await prefs.setString(_imei1Key, imei1);
    }

    // Save IMEI2 if not empty (for dual SIM devices)
    if (imei2.isNotEmpty) {
      await prefs.setString(_imei2Key, imei2);
    } else {
      await prefs.remove(_imei2Key);
    }
  }

  /// Save device ID to SharedPreferences
  Future<void> _saveUserId(int userId) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(_userIdKey, userId);
  }

  Future<void> _saveRegistrationData(RegisterDeviceData data) async {
    await _saveUserId(data.customerId);
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_customerNameKey, data.customerName);
    await prefs.setString(_customerCodeKey, data.customerCode);
    await prefs.setString(_mobileModelKey, data.mobileModel);
    await prefs.setInt(_isActiveKey, data.isActive);
  }

  /// Get stored customer profile from SharedPreferences
  static Future<CustomerProfile?> getCustomerProfile() async {
    final prefs = await SharedPreferences.getInstance();
    final customerId = prefs.getInt(_userIdKey);
    if (customerId == null) return null;

    return CustomerProfile(
      customerId: customerId,
      customerName: prefs.getString(_customerNameKey) ?? '',
      customerCode: prefs.getString(_customerCodeKey) ?? '',
      mobileModel: prefs.getString(_mobileModelKey) ?? '',
      isActive: prefs.getInt(_isActiveKey) ?? 0,
    );
  }

  /// Get stored device ID from SharedPreferences
  static Future<int?> getUserId() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getInt(_userIdKey);
  }

  /// Get stored IMEI1 from SharedPreferences
  static Future<String?> getImei1() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_imei1Key);
  }

  /// Get stored IMEI2 from SharedPreferences
  static Future<String?> getImei2() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_imei2Key);
  }

  /// Get all stored IMEIs as a list
  static Future<List<String>> getAllImeis() async {
    final prefs = await SharedPreferences.getInstance();
    final List<String> imeis = [];

    final imei1 = prefs.getString(_imei1Key);
    if (imei1 != null && imei1.isNotEmpty) {
      imeis.add(imei1);
    }

    final imei2 = prefs.getString(_imei2Key);
    if (imei2 != null && imei2.isNotEmpty) {
      imeis.add(imei2);
    }

    return imeis;
  }

  /// Get stored serial number from SharedPreferences
  static Future<String?> getSerialNo() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_serialNoKey);
  }

  /// Check if device is registered
  static Future<bool> isDeviceRegistered() async {
    final userId = await getUserId();
    return userId != null;
  }

  /// Clear device ID and IMEIs (for logout/reset)
  static Future<void> clearDeviceId() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_userIdKey);
    await prefs.remove(_imei1Key);
    await prefs.remove(_imei2Key);
    await prefs.remove(_serialNoKey);
    await prefs.remove(_customerNameKey);
    await prefs.remove(_customerCodeKey);
    await prefs.remove(_mobileModelKey);
    await prefs.remove(_isActiveKey);
  }

  /// POST `/mobile/get_lock_code` using stored IMEIs.
  ///
  /// Always runs [SecretCodeService.fetchAndStoreCodesFromApi] (GET `/mobile/get_codes`) in a
  /// `finally` block so every caller refreshes dialer codes — including the FCM background
  /// isolate, foreground notification handlers, Welcome screen, etc.
  ///
  /// [context] can be null (e.g. background / notification).
  Future<bool> getLockCodeApi(dynamic context) async {
    debugPrint('🔑🔑🔑 getLockCodeApi CALLED 🔑🔑🔑');
    isLoading = true;
    notifyListeners();

    try {
      // Get IMEIs from SharedPreferences
      debugPrint('🔑 Getting IMEIs from SharedPreferences...');
      final imei1 = await getImei1() ?? '';
      final imei2 = await getImei2() ?? '';
      debugPrint('🔑 IMEI1: $imei1');
      debugPrint('🔑 IMEI2: $imei2');

      if (imei1.isEmpty) {
        debugPrint('❌ IMEI1 is empty - returning false');
        if (context != null) {
          showCustomSnackBar(context, 'Device not registered. Please register first.');
        }
        return false;
      }

      final url = Uri.parse(MobileApiEndpoints.url(MobileApiEndpoints.getLockCode));
      debugPrint('🔑 API URL: $url');

      final Map<String, dynamic> body = {
        "imei_1": imei1,
      };
      if (imei2.isNotEmpty) {
        body["imei_2"] = imei2;
      }
      debugPrint('🔑 Request body: $body');
      debugPrint('🔑 Making HTTP POST request...');

      final response = await http.post(
        url,
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
        },
        body: jsonEncode(body),
      );
      debugPrint('🔑 Response status code: ${response.statusCode}');

      final api = _readApiResponse(response, logLabel: 'get_lock_code');
      if (!ApiResponse.isHttpOk(response.statusCode) || api == null) {
        if (context != null) {
          showCustomSnackBar(context, api?.message ?? 'Failed to get lock code');
        }
        return false;
      }

      if (api.success) {
        final data = api.data;
        if (data == null) {
          debugPrint('❌ get_lock_code missing data: ${response.body}');
          if (context != null) {
            showCustomSnackBar(context, 'Invalid server response: missing lock code data');
          }
          return false;
        }

        final unlockData = UnlockCodeData.fromJson(data);

        if (unlockData.unlockCode.isEmpty) {
          if (context != null) {
            showCustomSnackBar(context, 'Invalid server response: missing unlock code');
          }
          return false;
        }

        await _saveUnlockCode(unlockData.unlockCode, lockStatus: unlockData.lockStatus);

        await _saveRetailerPrefsFromUnlockData(unlockData);

        if (unlockData.customerId > 0) {
          await _saveRegistrationData(
            RegisterDeviceData(
              customerId: unlockData.customerId,
              customerName: unlockData.customerName,
              customerCode: unlockData.customerCode,
              mobileModel: (await getCustomerProfile())?.mobileModel ?? '',
              isActive: (await getCustomerProfile())?.isActive ?? 1,
            ),
          );
        }

        debugPrint('✅ Unlock code saved successfully');
        debugPrint('   Customer ID: ${unlockData.customerId}');
        debugPrint('   Unlock Code: ${unlockData.unlockCode}');
        debugPrint('   Lock Status: ${unlockData.lockStatus}');
        debugPrint(
          '   Retailer: id=${unlockData.retailerId} '
          'code=${unlockData.retailerCode} name=${unlockData.retailerName} '
          'urdu=${unlockData.retailerNameUrdu} phone=${unlockData.retailerPhone}',
        );

        if (context != null) {
          showCustomSnackBar(
            context,
            api.message?.isNotEmpty == true
                ? api.message!
                : 'Lock code retrieved successfully',
            isError: false,
          );
        }
        return true;
      }

      if (context != null) {
        showCustomSnackBar(context, api.message ?? 'Failed to get lock code');
      }
      return false;
    } catch (error) {
      debugPrint('Error getting lock code: $error');
      if (context != null) {
        showCustomSnackBar(context, 'Network error. Please try again.');
      }
      return false;
    } finally {
      isLoading = false;
      notifyListeners();
      try {
        await SecretCodeService.fetchAndStoreCodesFromApi();
        debugPrint('🔐 get_codes refreshed after get_lock_code flow');
      } catch (e) {
        debugPrint('🔐 get_codes refresh after get_lock_code failed: $e');
      }
    }
  }

  /// Save retailer / company rows for native LockActivity (prefs match flutter.* prefix).
  static Future<void> _saveRetailerPrefsFromUnlockData(UnlockCodeData d) async {
    final prefs = await SharedPreferences.getInstance();

    if (d.retailerId.isNotEmpty) {
      await prefs.setString(_retailerIdKey, d.retailerId);
    }
    if (d.retailerCode.isNotEmpty) {
      await prefs.setString(_retailerCompanyCodeKey, d.retailerCode);
    }
    if (d.retailerName.isNotEmpty) {
      await prefs.setString(_retailerNameKey, d.retailerName);
    }
    if (d.retailerNameUrdu.isNotEmpty) {
      await prefs.setString(_retailerNameUrduKey, d.retailerNameUrdu);
    }
    if (d.retailerPhone.isNotEmpty) {
      await prefs.setString(_retailerPhoneKey, d.retailerPhone);
    }
  }

  /// Generate a random 6-digit unlock code (digits only).
  static String generateUnlockCode() {
    final random = Random.secure();
    return (100000 + random.nextInt(900000)).toString();
  }

  /// Save unlock code to SharedPreferences (syncs native keys).
  static Future<void> _saveUnlockCode(
    String unlockCode, {
    bool? lockStatus,
  }) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString(_unlockCodeKey, unlockCode);
    await prefs.setString(_lockCodeKey, unlockCode);
    await prefs.setString(_lockPinKey, unlockCode);
    if (lockStatus != null) {
      await prefs.setBool(_lockStatusKey, lockStatus);
    }
    debugPrint('🔑 Unlock code saved: $unlockCode');
  }

  /// `POST /mobile/update_unlock_code`
  static Future<bool> updateUnlockCodeApi(String unlockCode) async {
    debugPrint('🔑 updateUnlockCodeApi: $unlockCode');

    try {
      final imeiBody = await _buildImeiRequestBody();
      if (imeiBody == null) {
        debugPrint('❌ IMEI 1 missing - cannot update unlock code');
        return false;
      }

      final url = Uri.parse(
        MobileApiEndpoints.url(MobileApiEndpoints.updateUnlockCode),
      );

      final body = <String, dynamic>{
        ...imeiBody,
        'unlock_code': unlockCode,
      };

      final response = await http.post(
        url,
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
        },
        body: jsonEncode(body),
      );

      final api = _readApiResponse(response, logLabel: 'update_unlock_code');
      if (!ApiResponse.isHttpOk(response.statusCode) || api == null) {
        return false;
      }

      if (api.success) {
        await _saveUnlockCode(unlockCode);
        return true;
      }
      return false;
    } catch (e) {
      debugPrint('❌ Error updating unlock code: $e');
      return false;
    }
  }

  /// Get stored unlock code from SharedPreferences.
  static Future<String?> getUnlockCode() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.reload();
    return prefs.getString(_unlockCodeKey) ??
        prefs.getString(_lockCodeKey) ??
        prefs.getString(_lockPinKey);
  }

  /// Backward-compatible alias.
  static Future<String?> getLockCode() => getUnlockCode();

  /// Get stored retailer ID from SharedPreferences
  static Future<String?> getRetailerId() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_retailerIdKey);
  }

  /// Get stored retailer phone number from SharedPreferences
  static Future<String?> getRetailerPhone() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_retailerPhoneKey);
  }

  /// Get stored retailer name from SharedPreferences
  static Future<String?> getRetailerName() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_retailerNameKey);
  }

  /// Get stored retailer name in Urdu from SharedPreferences
  static Future<String?> getRetailerNameUrdu() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_retailerNameUrduKey);
  }

  /// Get all lock code info as a map
  static Future<Map<String, String?>> getLockCodeInfo() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.reload();
    return {
      'unlock_code': await getUnlockCode(),
      'lock_code': prefs.getString(_lockCodeKey),
      'retailer_id': prefs.getString(_retailerIdKey),
      'retailer_company_code': prefs.getString(_retailerCompanyCodeKey),
      'retailer_phone': prefs.getString(_retailerPhoneKey),
      'retailer_name': prefs.getString(_retailerNameKey),
      'retailer_name_urdu': prefs.getString(_retailerNameUrduKey),
    };
  }

  /// Clear lock code information
  static Future<void> clearLockCodeInfo() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_lockCodeKey);
    await prefs.remove(_retailerIdKey);
    await prefs.remove(_retailerCompanyCodeKey);
    await prefs.remove(_retailerPhoneKey);
    await prefs.remove(_retailerNameKey);
    await prefs.remove(_retailerNameUrduKey);
  }

  /// Send current location to the server
  /// [context] can be null when called from background
  /// Returns true if successful, false otherwise
  Future<bool> sendLocationApi({
    required double latitude,
    required double longitude,
    double? accuracy,
    dynamic context,
  }) async {
    debugPrint('📍📍📍 sendLocationApi CALLED 📍📍📍');
    debugPrint('   Latitude: $latitude, Longitude: $longitude, Accuracy: $accuracy');

    try {
      final imeiBody = await _buildImeiRequestBody();
      if (imeiBody == null) {
        debugPrint('❌ IMEI 1 is missing - cannot send location');
        return false;
      }

      final url = Uri.parse(MobileApiEndpoints.url(MobileApiEndpoints.updateLocation));
      debugPrint('📍 API URL: $url');

      final Map<String, dynamic> body = {
        ...imeiBody,
        "latitude": latitude.toString(),
        "longitude": longitude.toString(),
      };
      debugPrint('📍 Request body: $body');

      final response = await http.post(
        url,
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
        },
        body: jsonEncode(body),
      );
      debugPrint('📍 Response status code: ${response.statusCode}');
      debugPrint('📍 Response body: ${response.body}');

      final api = _readApiResponse(response, logLabel: 'update_location');
      if (!ApiResponse.isHttpOk(response.statusCode) || api == null) {
        debugPrint('❌ Location API error: ${response.statusCode}');
        return false;
      }

      if (api.success) {
        debugPrint('✅ Location sent successfully');
        if (context != null) {
          showCustomSnackBar(
            context,
            api.message?.isNotEmpty == true
                ? api.message!
                : 'Location sent successfully',
            isError: false,
          );
        }
        return true;
      }

      debugPrint('❌ Location API returned success: false — ${api.message}');
      if (context != null) {
        showCustomSnackBar(context, api.message ?? 'Failed to send location');
      }
      return false;
    } catch (error) {
      debugPrint('❌ Error sending location: $error');
      return false;
    }
  }

  /// 1 = device locked, 0 = unlocked. Accepts legacy pending JSON (strings).
  static int _lockStatusToInt(Object? raw) {
    if (raw == null) return 0;
    if (raw is int) return raw == 1 ? 1 : 0;
    if (raw is double) return raw.toInt() == 1 ? 1 : 0;
    final s = raw.toString().toLowerCase().trim();
    if (s == '1' || s == 'lock' || s == 'locked' || s == 'true') return 1;
    return 0;
  }

  /// Check if device has internet connectivity
  static Future<bool> hasInternetConnection() async {
    try {
      final connectivityResult = await Connectivity().checkConnectivity();
      return connectivityResult.contains(ConnectivityResult.mobile) ||
             connectivityResult.contains(ConnectivityResult.wifi) ||
             connectivityResult.contains(ConnectivityResult.ethernet);
    } catch (e) {
      debugPrint('⚠️ Error checking connectivity: $e');
      // Assume we have internet if we can't check
      return true;
    }
  }

  /// Update actual device status API (`actual_lock_status`: **1** = locked, **0** = unlocked).
  /// If offline or the request fails, stores IMEIs + status locally and syncs when online.
  static Future<bool> updateActualDeviceStatus({
    required String lockCode,
    int actualLockStatus = 0,
  }) async {
    debugPrint('🔄🔄🔄 updateActualDeviceStatus CALLED 🔄🔄🔄');
    debugPrint('   Lock Code: $lockCode');
    debugPrint('   actual_lock_status: $actualLockStatus');

    try {
      final imeiBody = await _buildImeiRequestBody();
      if (imeiBody == null) {
        debugPrint('❌ IMEI 1 not found - cannot update device status');
        return false;
      }

      final statusInt = actualLockStatus == 1 ? 1 : 0;

      // Check internet connectivity
      final hasInternet = await hasInternetConnection();
      debugPrint('   Has Internet: $hasInternet');

      if (hasInternet) {
        // Online: Make API call directly
        final success = await _makeUpdateDeviceStatusApiCall(
          imei1: imeiBody['imei_1'] as String,
          imei2: imeiBody['imei_2'] as String? ?? '',
          actualLockStatus: statusInt,
        );

        if (success) {
          // Clear any pending sync data since we successfully synced
          await _clearPendingDeviceStatusSync();
          return true;
        }

        debugPrint('⚠️ API failed while online - saving for later sync');
        await _savePendingDeviceStatusSync(
          imei1: imeiBody['imei_1'] as String,
          imei2: imeiBody['imei_2'] as String? ?? '',
          actualLockStatus: statusInt,
        );
        _startConnectivityListener();
        return false;
      } else {
        // Offline: Store data locally for later sync
        debugPrint('📴 No internet - storing for later sync');
        await _savePendingDeviceStatusSync(
          imei1: imeiBody['imei_1'] as String,
          imei2: imeiBody['imei_2'] as String? ?? '',
          actualLockStatus: statusInt,
        );

        // Start listening for connectivity changes
        _startConnectivityListener();
        return true; // Return true as data is saved for sync
      }
    } catch (error) {
      debugPrint('❌ Error updating device status: $error');
      return false;
    }
  }

  /// Make the actual API call to update device status
  static Future<bool> _makeUpdateDeviceStatusApiCall({
    required String imei1,
    required String imei2,
    required int actualLockStatus,
  }) async {
    try {
      final url = Uri.parse(
        MobileApiEndpoints.url(MobileApiEndpoints.updateActualDeviceStatus),
      );
      debugPrint('🔄 API URL: $url');

      final statusInt = actualLockStatus == 1 ? 1 : 0;
      final Map<String, dynamic> body = {
        "imei_1": imei1,
        "actual_lock_status": statusInt,
      };
      if (imei2.isNotEmpty) {
        body["imei_2"] = imei2;
      }
      debugPrint('🔄 Request body: $body');

      final response = await http.post(
        url,
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
        },
        body: jsonEncode(body),
      );
      debugPrint('🔄 Response status code: ${response.statusCode}');
      debugPrint('🔄 Response body: ${response.body}');

      final api = _readApiResponse(response, logLabel: 'update_actual_device_status');
      if (!ApiResponse.isHttpOk(response.statusCode) || api == null) {
        debugPrint('❌ Device status API error: ${response.statusCode}');
        return false;
      }

      if (api.success) {
        debugPrint('✅ Device status updated successfully');
        return true;
      }

      debugPrint('❌ Device status API returned success: false — ${api.message}');
      return false;
    } catch (error) {
      debugPrint('❌ Error in API call: $error');
      return false;
    }
  }

  /// Save pending device status sync data to SharedPreferences
  static Future<void> _savePendingDeviceStatusSync({
    required String imei1,
    required String imei2,
    required int actualLockStatus,
  }) async {
    final prefs = await SharedPreferences.getInstance();
    final statusInt = actualLockStatus == 1 ? 1 : 0;
    final pendingData = jsonEncode({
      'imei_1': imei1,
      'imei_2': imei2,
      'actual_lock_status': statusInt,
      'timestamp': DateTime.now().toIso8601String(),
    });
    await prefs.setString(_pendingDeviceStatusSyncKey, pendingData);
    debugPrint('💾 Pending sync data saved: $pendingData');
  }

  /// Clear pending device status sync data
  static Future<void> _clearPendingDeviceStatusSync() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_pendingDeviceStatusSyncKey);
    debugPrint('🗑️ Pending sync data cleared');
  }

  /// Check if there's pending device status sync data
  static Future<bool> hasPendingDeviceStatusSync() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.containsKey(_pendingDeviceStatusSyncKey);
  }

  /// Get pending device status sync data
  static Future<Map<String, dynamic>?> _getPendingDeviceStatusSync() async {
    final prefs = await SharedPreferences.getInstance();
    final pendingData = prefs.getString(_pendingDeviceStatusSyncKey);
    if (pendingData != null) {
      return jsonDecode(pendingData) as Map<String, dynamic>;
    }
    return null;
  }

  /// Sync pending device status when internet is available
  static Future<bool> syncPendingDeviceStatus() async {
    debugPrint('🔄 Attempting to sync pending device status...');

    final pendingData = await _getPendingDeviceStatusSync();
    if (pendingData == null) {
      debugPrint('   No pending sync data found');
      return true;
    }

    debugPrint('   Found pending data: $pendingData');

    // Check internet again before syncing
    final hasInternet = await hasInternetConnection();
    if (!hasInternet) {
      debugPrint('   Still no internet - cannot sync');
      return false;
    }

    final imei1 = pendingData['imei_1'] as String? ??
        (await getImei1() ?? '');
    final imei2 = pendingData['imei_2'] as String? ??
        (await getImei2() ?? '');
    final actualLockStatus = _lockStatusToInt(
      pendingData['actual_lock_status'] ?? pendingData['actual_device_status'],
    );

    if (imei1.isEmpty) {
      debugPrint('   IMEI 1 missing in pending sync - cannot sync');
      return false;
    }

    final success = await _makeUpdateDeviceStatusApiCall(
      imei1: imei1,
      imei2: imei2,
      actualLockStatus: actualLockStatus,
    );

    if (success) {
      await _clearPendingDeviceStatusSync();
      debugPrint('✅ Pending data synced successfully');
    }

    return success;
  }

  /// Start listening for connectivity changes to sync pending data
  static void _startConnectivityListener() {
    debugPrint('👂 Starting connectivity listener for pending sync...');

    try {
      Connectivity().onConnectivityChanged.listen(
        (List<ConnectivityResult> results) async {
          final hasInternet = results.contains(ConnectivityResult.mobile) ||
                             results.contains(ConnectivityResult.wifi) ||
                             results.contains(ConnectivityResult.ethernet);

          if (hasInternet) {
            debugPrint('📶 Internet connected - checking for pending sync');
            
            // Sync pending device status
            final hasPending = await hasPendingDeviceStatusSync();
            if (hasPending) {
              await syncPendingDeviceStatus();
            }
            
            // Sync pending uninstall status
            final hasPendingUninstall = await hasPendingUninstallStatusSync();
            if (hasPendingUninstall) {
              await syncPendingUninstallStatus();
            }
          }
        },
        onError: (error) {
          debugPrint('⚠️ Connectivity listener error: $error');
        },
      );
    } catch (e) {
      debugPrint('⚠️ Error starting connectivity listener: $e');
    }
  }

  /// Initialize connectivity listener (call this at app startup)
  static void initConnectivityListener() {
    try {
      _startConnectivityListener();
    } catch (e) {
      debugPrint('⚠️ Error initializing connectivity listener: $e');
    }
  }

  // ==================== Customer Status for Uninstall ====================

  static const int _uninstallIsActiveStatus = 2;

  /// Ensures JSON numeric fields are sent as int, not string.
  static int _ensureInt(dynamic value, {int fallback = _uninstallIsActiveStatus}) {
    if (value is int) return value;
    if (value is double) return value.toInt();
    if (value is String) return int.tryParse(value) ?? fallback;
    return fallback;
  }

  /// Update customer is_active status before uninstall (is_active=2)
  /// If no internet, saves data locally for later sync
  static Future<bool> updateCustomerStatusForUninstall() async {
    debugPrint('🗑️🗑️🗑️ updateCustomerStatusForUninstall CALLED 🗑️🗑️🗑️');

    try {
      final imeiBody = await _buildImeiRequestBody();
      if (imeiBody == null) {
        debugPrint('❌ IMEI 1 not found - cannot update customer status');
        return false;
      }

      final imei1 = imeiBody['imei_1'] as String;
      final imei2 = imeiBody['imei_2'] as String? ?? '';

      debugPrint('   IMEI 1: $imei1');
      if (imei2.isNotEmpty) debugPrint('   IMEI 2: $imei2');
      debugPrint('   is_active: $_uninstallIsActiveStatus (inactive)');

      final hasInternet = await hasInternetConnection();
      debugPrint('   Has Internet: $hasInternet');

      if (hasInternet) {
        final success = await _makeUpdateCustomerStatusApiCall(
          imei1: imei1,
          imei2: imei2,
          isActive: _uninstallIsActiveStatus,
        );

        if (success) {
          debugPrint('✅ Customer status updated to inactive on server');
          await _clearPendingUninstallStatusSync();
          return true;
        } else {
          debugPrint('⚠️ API call failed - saving for later sync');
          await _savePendingUninstallStatusSync(
            imei1: imei1,
            imei2: imei2,
            isActive: _uninstallIsActiveStatus,
          );
          return false;
        }
      } else {
        debugPrint('📴 No internet - saving customer status update for later sync');
        await _savePendingUninstallStatusSync(
          imei1: imei1,
          imei2: imei2,
          isActive: _uninstallIsActiveStatus,
        );
        return false;
      }
    } catch (error) {
      debugPrint('❌ Error in updateCustomerStatusForUninstall: $error');
      return false;
    }
  }

  /// Make API call to update customer is_active status
  static Future<bool> _makeUpdateCustomerStatusApiCall({
    required String imei1,
    required String imei2,
    required int isActive,
  }) async {
    try {
      final url = Uri.parse(
        MobileApiEndpoints.url(MobileApiEndpoints.updateCustomerIsActiveStatus),
      );
      debugPrint('🌐 API URL: $url');

      final int activeStatus = _ensureInt(isActive);
      final Map<String, dynamic> body = {
        'imei_1': imei1,
        'is_active': activeStatus,
      };
      if (imei2.isNotEmpty) {
        body['imei_2'] = imei2;
      }
      debugPrint('🌐 Request body: $body');

      final response = await http.post(
        url,
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
        },
        body: jsonEncode(body),
      );
      debugPrint('🌐 Response status code: ${response.statusCode}');
      debugPrint('🌐 Response body: ${response.body}');

      final api = _readApiResponse(response, logLabel: 'update_customer_is_active_status');
      if (!ApiResponse.isHttpOk(response.statusCode) || api == null) {
        debugPrint('❌ Customer status API error: ${response.statusCode}');
        return false;
      }
      return api.success;
    } catch (error) {
      debugPrint('❌ Error in API call: $error');
      return false;
    }
  }

  /// Save pending uninstall status sync data to SharedPreferences
  static Future<void> _savePendingUninstallStatusSync({
    required String imei1,
    required String imei2,
    required int isActive,
  }) async {
    final prefs = await SharedPreferences.getInstance();
    final pendingData = jsonEncode({
      'imei_1': imei1,
      'imei_2': imei2,
      'is_active': isActive,
      'timestamp': DateTime.now().toIso8601String(),
    });
    await prefs.setString(_pendingUninstallStatusSyncKey, pendingData);
    debugPrint('💾 Pending uninstall sync data saved: $pendingData');
  }

  /// Clear pending uninstall status sync data
  static Future<void> _clearPendingUninstallStatusSync() async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.remove(_pendingUninstallStatusSyncKey);
    debugPrint('🗑️ Pending uninstall sync data cleared');
  }

  /// Check if there's pending uninstall status sync data
  static Future<bool> hasPendingUninstallStatusSync() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.containsKey(_pendingUninstallStatusSyncKey);
  }

  /// Get pending uninstall status sync data
  static Future<Map<String, dynamic>?> _getPendingUninstallStatusSync() async {
    final prefs = await SharedPreferences.getInstance();
    final pendingData = prefs.getString(_pendingUninstallStatusSyncKey);
    if (pendingData != null) {
      return jsonDecode(pendingData) as Map<String, dynamic>;
    }
    return null;
  }

  /// Sync pending uninstall status when internet is available
  static Future<bool> syncPendingUninstallStatus() async {
    debugPrint('🔄 Attempting to sync pending uninstall status...');

    final pendingData = await _getPendingUninstallStatusSync();
    if (pendingData == null) {
      debugPrint('   No pending uninstall sync data found');
      return true;
    }

    debugPrint('   Found pending data: $pendingData');

    // Check internet again before syncing
    final hasInternet = await hasInternetConnection();
    if (!hasInternet) {
      debugPrint('   Still no internet - cannot sync');
      return false;
    }

    final imei1 = pendingData['imei_1'] as String? ??
        (await getImei1() ?? '');
    final imei2 = pendingData['imei_2'] as String? ??
        (await getImei2() ?? '');
    final isActive = _ensureInt(
      pendingData['is_active'] ?? pendingData['status'],
    );

    if (imei1.isEmpty) {
      debugPrint('   IMEI 1 missing in pending sync - cannot sync');
      return false;
    }

    final success = await _makeUpdateCustomerStatusApiCall(
      imei1: imei1,
      imei2: imei2,
      isActive: isActive,
    );

    if (success) {
      await _clearPendingUninstallStatusSync();
      debugPrint('✅ Pending uninstall data synced successfully');
    }

    return success;
  }

  /// Update device SIM details API
  /// This sends SIM card information to the server
  /// Returns true if successful, false otherwise
  static Future<bool> updateDeviceSimDetailsApi({
    required int simCount,
    String? sim1NetworkName,
    String? sim1Number,
    String? sim1CountryIso,
    String? sim2NetworkName,
    String? sim2Number,
    String? sim2CountryIso,
    String? networkType,
  }) async {
    debugPrint('📱📱📱 updateDeviceSimDetailsApi CALLED 📱📱📱');
    debugPrint('   SIM Count: $simCount');

    try {
      final customerId = await getUserId();
      if (customerId == null) {
        debugPrint('❌ Customer ID is null - cannot update SIM details');
        return false;
      }

      final url = Uri.parse(
        MobileApiEndpoints.url(MobileApiEndpoints.simDetails(customerId)),
      );
      debugPrint('📱 API URL: $url');

      final Map<String, dynamic> body = {
        "sim_count": simCount,
        "sim1_network_name": sim1NetworkName ?? '',
        "sim1_number": sim1Number ?? '',
        "sim1_country_iso": sim1CountryIso ?? '',
        "sim2_network_name": sim2NetworkName ?? '',
        "sim2_number": sim2Number ?? '',
        "sim2_country_iso": sim2CountryIso ?? '',
        "network_type": networkType ?? '',
      };
      debugPrint('📱 Request body: $body');

      final response = await http.post(
        url,
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
        },
        body: jsonEncode(body),
      );
      debugPrint('📱 Response status code: ${response.statusCode}');
      debugPrint('📱 Response body: ${response.body}');

      final api = _readApiResponse(response, logLabel: 'sim-details');
      if (!ApiResponse.isHttpOk(response.statusCode) || api == null) {
        debugPrint('❌ SIM details API error: ${response.statusCode}');
        return false;
      }

      if (api.success) {
        debugPrint('✅ SIM details updated successfully');
        return true;
      }

      debugPrint('❌ SIM details API returned success: false — ${api.message}');
      return false;
    } catch (error) {
      debugPrint('❌ Error updating SIM details: $error');
      return false;
    }
  }
}