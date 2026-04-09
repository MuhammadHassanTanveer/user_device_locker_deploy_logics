import 'dart:convert';

import 'package:flutter/material.dart';

import 'package:connectivity_plus/connectivity_plus.dart';
import 'package:http/http.dart' as http;
import 'package:shared_preferences/shared_preferences.dart';

import '../util/app_constants.dart';
import '../widgets/snackbar_widget.dart';


class RegisterDeviceProvider with ChangeNotifier{

  static const String _userIdKey = 'registered_device_id';
  static const String _imei1Key = 'device_imei1';
  static const String _imei2Key = 'device_imei2';
  static const String _serialNoKey = 'device_serial_no';

  // Lock code related keys
  static const String _lockCodeKey = 'lock_code';
  static const String _retailerIdKey = 'retailer_id';
  static const String _retailerPhoneKey = 'retailer_phone';
  static const String _retailerNameKey = 'retailer_name';
  static const String _retailerNameUrduKey = 'retailer_name_urdu';

  // Pending sync key for offline support
  static const String _pendingDeviceStatusSyncKey = 'pending_device_status_sync';
  static const String _pendingUninstallStatusSyncKey = 'pending_uninstall_status_sync';

  bool isLoading = false;

  Future<bool> registerDeviceApi(context, {
    required String imei_no1,
    required String imei_no2,
    required String serial_no,
    required String fcm_token,
  })
  async {


    try {
      final url = Uri.parse('${AppConstants.baseUrl}/update_device_status_api');

      final Map<String, dynamic> body = {
        "imei_no1": imei_no1,
        "imei_no2": imei_no2,
        "serial_no": serial_no,
        "fcm_token": fcm_token,
      };
      final response = await http.post(
        url,
        headers: {
          'Content-Type': 'application/json',
          'Accept': 'application/json',
        },
        body: jsonEncode(body),
      );

      if (response.statusCode == 200) {
        debugPrint('updateUser api is working ${response.body}');
        final responseData = jsonDecode(response.body);
        if (responseData['success'] == true) {
          // Store device ID in SharedPreferences
          final userId = responseData['data']['id'];
          await _saveUserId(userId);
          debugPrint('Device ID saved: $userId');

          // Store IMEI numbers in SharedPreferences
          await _saveImeiNumbers(imei_no1, imei_no2, serial_no);
          debugPrint('IMEI numbers saved: IMEI1=$imei_no1, IMEI2=$imei_no2');

          showCustomSnackBar(context, "Updated successfully", isError: false);
          return true;
        } else {
          isLoading = false;
          showCustomSnackBar(context, responseData['message'] ?? "Failed to update user");
          return false;
        }
      } else {
        // Handle error responses (400, 401, etc.)
        debugPrint('updateUserEx ${response.statusCode} ${response.body}');
        try {
          final errorData = jsonDecode(response.body);
          final errorMessage = errorData['message'] ?? 'Failed to register device';
          showCustomSnackBar(context, errorMessage);
        } catch (e) {
          showCustomSnackBar(context, 'Failed to register device');
        }
        return false;
      }
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
  Future<void> _saveImeiNumbers(String imei1, String imei2, String serialNo) async {
    final prefs = await SharedPreferences.getInstance();

    // Save IMEI1 if not empty
    if (imei1.isNotEmpty) {
      await prefs.setString(_imei1Key, imei1);
    }

    // Save IMEI2 if not empty (for dual SIM devices)
    if (imei2.isNotEmpty) {
      await prefs.setString(_imei2Key, imei2);
    }

    // Save serial number
    if (serialNo.isNotEmpty) {
      await prefs.setString(_serialNoKey, serialNo);
    }
  }

  /// Save device ID to SharedPreferences
  Future<void> _saveUserId(int userId) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setInt(_userIdKey, userId);
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
  }

  /// Get lock code from API using stored IMEIs
  /// [context] can be null when called from background
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

      final url = Uri.parse('${AppConstants.baseUrl}/get_lock_code');
      debugPrint('🔑 API URL: $url');

      final Map<String, dynamic> body = {
        "imei_no1": imei1,
        "imei_no2": imei2,
      };
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

      if (response.statusCode == 200) {
        debugPrint('getLockCodeApi response: ${response.body}');
        final responseData = jsonDecode(response.body);

        if (responseData['success'] == true) {
          // API returns 'Data' with capital D
          final data = responseData['Data'];

          // Store lock code information in SharedPreferences
          // Mapping API fields: retailer_id, lock_code, phone_no, name, name_urdu
          // Note: Need to handle null values properly - data['field']?.toString() on null returns "null" string
          final retailerId = data['retailer_id'];
          final lockCode = data['lock_code'];
          final phoneNo = data['phone_no'];
          final name = data['name'];
          final nameUrdu = data['name_urdu'];

          await _saveLockCodeInfo(
            lockCode: lockCode != null ? lockCode.toString() : '',
            retailerId: retailerId != null ? retailerId.toString() : '',
            retailerPhone: phoneNo != null ? phoneNo.toString() : '',
            retailerName: name != null ? name.toString() : '',
            retailerNameUrdu: nameUrdu != null ? nameUrdu.toString() : '',
          );

          debugPrint('✅ Lock code info saved successfully');
          debugPrint('   Retailer ID: $retailerId');
          debugPrint('   Lock Code: $lockCode');
          debugPrint('   Phone: $phoneNo');
          debugPrint('   Name: $name');
          debugPrint('   Name Urdu: $nameUrdu');

          // Verify saved data
          final savedLockCode = await getLockCode();
          final savedRetailerId = await getRetailerId();
          debugPrint('   Verified saved lock code: $savedLockCode');
          debugPrint('   Verified saved retailer ID: $savedRetailerId');

          if (context != null) {
            showCustomSnackBar(context, "Lock code retrieved successfully", isError: false);
          }
          return true;
        } else {
          if (context != null) {
            showCustomSnackBar(context, responseData['message'] ?? "Failed to get lock code");
          }
          return false;
        }
      } else {
        debugPrint('getLockCodeApi error: ${response.statusCode} ${response.body}');
        try {
          final errorData = jsonDecode(response.body);
          final errorMessage = errorData['message'] ?? 'Failed to get lock code';
          if (context != null) {
            showCustomSnackBar(context, errorMessage);
          }
        } catch (e) {
          if (context != null) {
            showCustomSnackBar(context, 'Failed to get lock code');
          }
        }
        return false;
      }
    } catch (error) {
      debugPrint('Error getting lock code: $error');
      if (context != null) {
        showCustomSnackBar(context, 'Network error. Please try again.');
      }
      return false;
    } finally {
      isLoading = false;
      notifyListeners();
    }
  }

  /// Save lock code information to SharedPreferences
  /// This REPLACES all existing lock code info with fresh data from API
  Future<void> _saveLockCodeInfo({
    required String lockCode,
    required String retailerId,
    required String retailerPhone,
    required String retailerName,
    String retailerNameUrdu = '',
  }) async {
    final prefs = await SharedPreferences.getInstance();

    // IMPORTANT: Clear old values first to ensure we're using ONLY the latest data
    await prefs.remove(_lockCodeKey);
    await prefs.remove(_retailerIdKey);
    await prefs.remove(_retailerPhoneKey);
    await prefs.remove(_retailerNameKey);
    await prefs.remove(_retailerNameUrduKey);

    debugPrint('🔑 Cleared old lock code info from SharedPreferences');

    // Save new values (even if empty, we clear old values first)
    if (lockCode.isNotEmpty) {
      await prefs.setString(_lockCodeKey, lockCode);

      // IMPORTANT: Also sync the lock_pin in SharedPreferences
      // This ensures both 'lock_code' and 'lock_pin' are in sync
      // The native LockActivity can use either one and get the same (latest) value
      await prefs.setString('lock_pin', lockCode);
      debugPrint('🔑 Synced lock_pin with lock_code: $lockCode');
    }
    if (retailerId.isNotEmpty) {
      await prefs.setString(_retailerIdKey, retailerId);
    }
    if (retailerPhone.isNotEmpty) {
      await prefs.setString(_retailerPhoneKey, retailerPhone);
    }
    if (retailerName.isNotEmpty) {
      await prefs.setString(_retailerNameKey, retailerName);
    }
    if (retailerNameUrdu.isNotEmpty) {
      await prefs.setString(_retailerNameUrduKey, retailerNameUrdu);
    }
  }

  /// Get stored lock code from SharedPreferences
  static Future<String?> getLockCode() async {
    final prefs = await SharedPreferences.getInstance();
    return prefs.getString(_lockCodeKey);
  }

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
    // Reload to get fresh data (important for overlay isolate)
    await prefs.reload();
    return {
      'lock_code': prefs.getString(_lockCodeKey),
      'retailer_id': prefs.getString(_retailerIdKey),
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
      // Get customer_id (user_id) from SharedPreferences
      final customerId = await getUserId();

      if (customerId == null) {
        debugPrint('❌ Customer ID is null - cannot send location');
        return false;
      }

      final url = Uri.parse('${AppConstants.baseUrl}/update_location');
      debugPrint('📍 API URL: $url');

      final Map<String, dynamic> body = {
        "customer_id": customerId,
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

      if (response.statusCode == 200) {
        final responseData = jsonDecode(response.body);
        if (responseData['success'] == true) {
          debugPrint('✅ Location sent successfully');
          if (context != null) {
            showCustomSnackBar(context, "Location sent successfully", isError: false);
          }
          return true;
        } else {
          debugPrint('❌ Location API returned success: false');
          if (context != null) {
            showCustomSnackBar(context, responseData['message'] ?? "Failed to send location");
          }
          return false;
        }
      } else {
        debugPrint('❌ Location API error: ${response.statusCode}');
        return false;
      }
    } catch (error) {
      debugPrint('❌ Error sending location: $error');
      return false;
    }
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

  /// Update actual device status API
  /// This is called when the lock code changes after unlock
  /// If no internet, it stores the data locally and syncs when online
  static Future<bool> updateActualDeviceStatus({
    required String lockCode,
    String actualDeviceStatus = 'unlock',
  }) async {
    debugPrint('🔄🔄🔄 updateActualDeviceStatus CALLED 🔄🔄🔄');
    debugPrint('   Lock Code: $lockCode');
    debugPrint('   Status: $actualDeviceStatus');

    try {
      // Get user ID (customer_id) from SharedPreferences
      final userId = await getUserId();
      if (userId == null) {
        debugPrint('❌ User ID not found - cannot update device status');
        return false;
      }

      // Check internet connectivity
      final hasInternet = await hasInternetConnection();
      debugPrint('   Has Internet: $hasInternet');

      if (hasInternet) {
        // Online: Make API call directly
        final success = await _makeUpdateDeviceStatusApiCall(
          customerId: userId,
          lockCode: lockCode,
          actualDeviceStatus: actualDeviceStatus,
        );

        if (success) {
          // Clear any pending sync data since we successfully synced
          await _clearPendingDeviceStatusSync();
        }
        return success;
      } else {
        // Offline: Store data locally for later sync
        debugPrint('📴 No internet - storing for later sync');
        await _savePendingDeviceStatusSync(
          customerId: userId,
          lockCode: lockCode,
          actualDeviceStatus: actualDeviceStatus,
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
    required int customerId,
    required String lockCode,
    required String actualDeviceStatus,
  }) async {
    try {
      final url = Uri.parse('${AppConstants.baseUrl}/update_actual_device_status');
      debugPrint('🔄 API URL: $url');

      final Map<String, dynamic> body = {
        "customer_id": customerId,
        "lock_code": lockCode,
        "actual_device_status": actualDeviceStatus,
      };
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

      if (response.statusCode == 200) {
        final responseData = jsonDecode(response.body);
        if (responseData['success'] == true) {
          debugPrint('✅ Device status updated successfully');
          return true;
        } else {
          debugPrint('❌ Device status API returned success: false');
          debugPrint('   Message: ${responseData['message']}');
          return false;
        }
      } else {
        debugPrint('❌ Device status API error: ${response.statusCode}');
        return false;
      }
    } catch (error) {
      debugPrint('❌ Error in API call: $error');
      return false;
    }
  }

  /// Save pending device status sync data to SharedPreferences
  static Future<void> _savePendingDeviceStatusSync({
    required int customerId,
    required String lockCode,
    required String actualDeviceStatus,
  }) async {
    final prefs = await SharedPreferences.getInstance();
    final pendingData = jsonEncode({
      'customer_id': customerId,
      'lock_code': lockCode,
      'actual_device_status': actualDeviceStatus,
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

    // Make API call with pending data
    final success = await _makeUpdateDeviceStatusApiCall(
      customerId: pendingData['customer_id'] as int,
      lockCode: pendingData['lock_code'] as String,
      actualDeviceStatus: pendingData['actual_device_status'] as String,
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

  /// Update customer status to inactive (status=2) before uninstall
  /// This is called when the uninstall command is received
  /// If no internet, it saves the data for later sync
  static Future<bool> updateCustomerStatusForUninstall() async {
    debugPrint('🗑️🗑️🗑️ updateCustomerStatusForUninstall CALLED 🗑️🗑️🗑️');

    try {
      // Get user ID (customer_id) from SharedPreferences
      final userId = await getUserId();
      if (userId == null) {
        debugPrint('❌ User ID not found - cannot update customer status');
        return false;
      }

      debugPrint('   Customer ID: $userId');
      debugPrint('   Status: 2 (inactive)');

      // Check internet connectivity
      final hasInternet = await hasInternetConnection();
      debugPrint('   Has Internet: $hasInternet');

      if (hasInternet) {
        // Online: Make API call directly
        final success = await _makeUpdateCustomerStatusApiCall(customerId: userId);
        
        if (success) {
          debugPrint('✅ Customer status updated to inactive on server');
          await _clearPendingUninstallStatusSync();
          return true;
        } else {
          debugPrint('⚠️ API call failed - saving for later sync');
          await _savePendingUninstallStatusSync(customerId: userId);
          return false;
        }
      } else {
        // Offline: Save for later sync
        debugPrint('📴 No internet - saving customer status update for later sync');
        await _savePendingUninstallStatusSync(customerId: userId);
        return false;
      }
    } catch (error) {
      debugPrint('❌ Error in updateCustomerStatusForUninstall: $error');
      return false;
    }
  }

  /// Make API call to update customer is_active status
  static Future<bool> _makeUpdateCustomerStatusApiCall({
    required int customerId,
  }) async {
    try {
      final url = Uri.parse(
        '${AppConstants.baseUrl}/update_cutomer_is_active_status?customer_id=$customerId&status=2'
      );
      debugPrint('🌐 API URL: $url');

      final response = await http.get(
        url,
        headers: {
          'Accept': 'application/json',
        },
      );
      debugPrint('🌐 Response status code: ${response.statusCode}');
      debugPrint('🌐 Response body: ${response.body}');

      if (response.statusCode == 200) {
        try {
          final responseData = jsonDecode(response.body);
          return responseData['success'] == true;
        } catch (e) {
          // If response is not JSON, consider it successful if HTTP 200
          debugPrint('Response is not JSON, considering successful based on HTTP 200');
          return true;
        }
      } else {
        debugPrint('❌ Customer status API error: ${response.statusCode}');
        return false;
      }
    } catch (error) {
      debugPrint('❌ Error in API call: $error');
      return false;
    }
  }

  /// Save pending uninstall status sync data to SharedPreferences
  static Future<void> _savePendingUninstallStatusSync({
    required int customerId,
  }) async {
    final prefs = await SharedPreferences.getInstance();
    final pendingData = jsonEncode({
      'customer_id': customerId,
      'status': 2,
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

    // Make API call with pending data
    final success = await _makeUpdateCustomerStatusApiCall(
      customerId: pendingData['customer_id'] as int,
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
    required String imeiNo1,
    String? imeiNo2,
    required int simCount,
    String? sim1NetworkName,
    String? sim1Number,
    String? sim1CountryIso,
    String? sim1DisplayName,
    String? sim2NetworkName,
    String? sim2Number,
    String? sim2CountryIso,
    String? sim2DisplayName,
    String? networkType,
  }) async {
    debugPrint('📱📱📱 updateDeviceSimDetailsApi CALLED 📱📱📱');
    debugPrint('   IMEI1: $imeiNo1, IMEI2: $imeiNo2');
    debugPrint('   SIM Count: $simCount');

    try {
      final url = Uri.parse('${AppConstants.baseUrl}/update_device_sim_details');
      debugPrint('📱 API URL: $url');

      final Map<String, dynamic> body = {
        "imei_no1": imeiNo1,
        "imei_no2": imeiNo2 ?? '',
        "sim_count": simCount,
        "sim1_network_name": sim1NetworkName ?? '',
        "sim1_number": sim1Number ?? '',
        "sim1_country_iso": sim1CountryIso ?? '',
        "sim1_display_name": sim1DisplayName ?? '',
        "sim2_network_name": sim2NetworkName ?? '',
        "sim2_number": sim2Number ?? '',
        "sim2_country_iso": sim2CountryIso ?? '',
        "sim2_display_name": sim2DisplayName ?? '',
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

      if (response.statusCode == 200) {
        final responseData = jsonDecode(response.body);
        if (responseData['success'] == true) {
          debugPrint('✅ SIM details updated successfully');
          return true;
        } else {
          debugPrint('❌ SIM details API returned success: false');
          debugPrint('   Message: ${responseData['message']}');
          return false;
        }
      } else {
        debugPrint('❌ SIM details API error: ${response.statusCode}');
        return false;
      }
    } catch (error) {
      debugPrint('❌ Error updating SIM details: $error');
      return false;
    }
  }
}