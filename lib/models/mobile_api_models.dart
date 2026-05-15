import 'dart:convert';

import '../util/api_response.dart';

/// `POST /mobile/registerdevice` → `data`
class RegisterDeviceData {
  final int customerId;
  final String customerName;
  final String customerCode;
  final String mobileModel;
  final int isActive;

  const RegisterDeviceData({
    required this.customerId,
    required this.customerName,
    required this.customerCode,
    required this.mobileModel,
    required this.isActive,
  });

  bool get isDeviceActive => isActive == 1;

  String get statusLabel => isDeviceActive ? 'Active' : 'Inactive';

  factory RegisterDeviceData.fromJson(Map<String, dynamic> json) {
    return RegisterDeviceData(
      customerId: ApiResponse.intField(json, ['customer_id', 'id']) ?? 0,
      customerName: ApiResponse.stringField(json, ['customer_name', 'name']),
      customerCode: ApiResponse.stringField(json, ['customer_code', 'code']),
      mobileModel: ApiResponse.stringField(json, ['mobile_model', 'model', 'device_model']),
      isActive: ApiResponse.intField(json, ['is_active', 'status']) ?? 0,
    );
  }

  Map<String, dynamic> toJson() => {
        'customer_id': customerId,
        'customer_name': customerName,
        'customer_code': customerCode,
        'mobile_model': mobileModel,
        'is_active': isActive,
      };
}

/// `POST /mobile/get_lock_code` → `data`
class UnlockCodeData {
  final int customerId;
  final String customerName;
  final String customerCode;
  final String unlockCode;
  final bool lockStatus;

  /// Retailer / company slice (shown on LockActivity from SharedPreferences).
  final String retailerId;
  final String retailerCode;
  final String retailerName;
  final String retailerNameUrdu;
  final String retailerPhone;

  const UnlockCodeData({
    required this.customerId,
    required this.customerName,
    required this.customerCode,
    required this.unlockCode,
    required this.lockStatus,
    required this.retailerId,
    required this.retailerCode,
    required this.retailerName,
    required this.retailerNameUrdu,
    required this.retailerPhone,
  });

  static String _numericIdField(Map<String, dynamic> json, List<String> keys) {
    for (final k in keys) {
      final value = json[k];
      if (value == null) continue;
      if (value is int || value is double) {
        return value == 0 ? '' : value.toString();
      }
      final s = value.toString().trim();
      if (s.isNotEmpty && s != 'null') return s;
    }
    return '';
  }

  factory UnlockCodeData.fromJson(Map<String, dynamic> json) {
    final lockStatusRaw = json['lock_status'];
    final lockStatus = lockStatusRaw == true ||
        lockStatusRaw == 1 ||
        lockStatusRaw?.toString() == '1';

    Map<String, dynamic>? createdByUser;
    final rawCreator = json['created_by_user'];
    if (rawCreator is Map) {
      createdByUser = Map<String, dynamic>.from(rawCreator);
    }

    var retailerId = ApiResponse.stringField(
      json,
      ['retailer_id', 'company_id'],
    );
    if (retailerId.isEmpty) {
      retailerId = _numericIdField(json, ['retailer_id', 'company_id']);
    }

    var retailerCode = ApiResponse.stringField(
      json,
      [
        'retailer_code',
        'company_code',
        'vendor_code',
        'retailer_company_code',
      ],
    );

    var retailerName = ApiResponse.stringField(
      json,
      [
        'company_name',
        'retailer_name',
        'retailer_company_name',
        'shop_name',
        'business_name',
      ],
    );

    var retailerNameUrdu = ApiResponse.stringField(
      json,
      [
        'company_name_urdu',
        'retailer_name_urdu',
        'company_name_ar',
      ],
    );

    var retailerPhone = ApiResponse.stringField(
      json,
      [
        'company_phone',
        'retailer_phone',
        'phone_no',
        'phone',
        'mobile',
        'contact_phone',
        'phone_number',
      ],
    );

    // Nested creator (retailer who registered the customer) — preferred when present.
    if (createdByUser != null) {
      final uid = _numericIdField(createdByUser, ['user_id']);
      retailerId = uid.isNotEmpty
          ? uid
          : ApiResponse.stringField(createdByUser, ['user_id']);

      retailerCode = ApiResponse.stringField(createdByUser, ['user_code']);

      retailerName = ApiResponse.stringField(createdByUser, ['company_name']);
      if (retailerName.isEmpty) {
        retailerName = ApiResponse.stringField(createdByUser, ['user_name']);
      }

      retailerNameUrdu =
          ApiResponse.stringField(createdByUser, ['company_name_urdu']);

      retailerPhone = ApiResponse.stringField(
        createdByUser,
        ['phone_number', 'phone_no', 'phone', 'mobile', 'company_phone'],
      );
    }

    return UnlockCodeData(
      customerId: ApiResponse.intField(json, ['customer_id', 'id']) ?? 0,
      customerName: ApiResponse.stringField(json, ['customer_name', 'buyer_name']),
      customerCode: ApiResponse.stringField(json, ['customer_code', 'code']),
      unlockCode: ApiResponse.stringField(
        json,
        ['unlock_code', 'lock_code', 'lockCode', 'lock_pin', 'pin'],
      ),
      lockStatus: lockStatus,
      retailerId: retailerId,
      retailerCode: retailerCode,
      retailerName: retailerName,
      retailerNameUrdu: retailerNameUrdu,
      retailerPhone: retailerPhone,
    );
  }
}

/// Customer profile stored locally after registration.
class CustomerProfile {
  final int customerId;
  final String customerName;
  final String customerCode;
  final String mobileModel;
  final int isActive;

  const CustomerProfile({
    required this.customerId,
    required this.customerName,
    required this.customerCode,
    required this.mobileModel,
    required this.isActive,
  });

  factory CustomerProfile.fromRegisterData(RegisterDeviceData data) {
    return CustomerProfile(
      customerId: data.customerId,
      customerName: data.customerName,
      customerCode: data.customerCode,
      mobileModel: data.mobileModel,
      isActive: data.isActive,
    );
  }

  bool get isDeviceActive => isActive == 1;

  String get statusLabel => isDeviceActive ? 'Active' : 'Inactive';
}

/// Dialer secret codes — `GET /mobile/get_codes` → `data`
class SecretCodesData {
  final Map<String, String> codeToCommand;

  const SecretCodesData(this.codeToCommand);

  /// Resolves nested / string-encoded payloads some backends return.
  static Map<String, dynamic> _resolvedDataMap(
    Map<String, dynamic> root, [
    int depth = 5,
  ]) {
    if (depth <= 0) return root;
    if (_looksLikeCodesPayload(root)) return root;

    const nestedKeys = [
      'device_codes',
      'deviceCodes',
      'codes',
      'secret_codes',
      'secretCodes',
      'dialer_codes',
      'data',
      'Data',
      'result',
      'payload',
    ];
    for (final nk in nestedKeys) {
      final inner = root[nk];
      if (inner is Map) {
        final m = Map<String, dynamic>.from(inner);
        if (_looksLikeCodesPayload(m)) return m;
        final deeper = _resolvedDataMap(m, depth - 1);
        if (_looksLikeCodesPayload(deeper)) return deeper;
      }
      if (inner is String) {
        try {
          final d = jsonDecode(inner.trim());
          if (d is Map) {
            final m = Map<String, dynamic>.from(d);
            if (_looksLikeCodesPayload(m)) return m;
            final deeper = _resolvedDataMap(m, depth - 1);
            if (_looksLikeCodesPayload(deeper)) return deeper;
          }
        } catch (_) {}
      }
    }
    return root;
  }

  static bool _looksLikeCodesPayload(Map<String, dynamic> m) {
    for (final k in m.keys) {
      final s = k.toString().toLowerCase();
      // Avoid mistaking profile fields (e.g. customer_code) for the dialer-codes map.
      if (s == 'customer_code' ||
          s == 'user_code' ||
          s == 'retailer_code' ||
          s == 'company_code') {
        continue;
      }
      if (s.endsWith('_code')) return true;
    }
    return false;
  }

  static Map<String, dynamic> _caseInsensitiveView(Map<String, dynamic> data) {
    final out = <String, dynamic>{};
    for (final e in data.entries) {
      out[e.key.toString().toLowerCase()] = e.value;
    }
    return out;
  }

  factory SecretCodesData.fromApiData(Map<String, dynamic> data) {
    final resolved = _resolvedDataMap(Map<String, dynamic>.from(data));
    final lower = _caseInsensitiveView(resolved);
    final codes = <String, String>{};
    void map(List<String> apiKeys, String command) {
      for (final apiKey in apiKeys) {
        final value = lower[apiKey.toLowerCase()];
        if (value == null) continue;
        final code = value.toString().trim();
        if (code.isNotEmpty && code != 'null') {
          codes[code] = command;
          return;
        }
      }
    }

    map(['lock_device_code'], 'lock_device');
    map(['unlock_device_code'], 'unlock_device');
    map(['enable_camera_code'], 'enable_camera');
    map(['disable_camera_code'], 'disable_camera');
    map(['show_social_app_code'], 'show_social_apps');
    map(['hide_social_app_code'], 'hide_social_apps');
    map(['show_all_apps_code'], 'show_all_apps');
    map(['hide_all_apps_code'], 'hide_all_apps');
    map(['change_screen_password_code'], 'change_screen_password');
    map(['remove_screen_password_code'], 'remove_screen_password');
    map(['reboot_device_code'], 'reboot');
    map(['send_message_to_customer_code'], 'send_message');
    map(['change_wallpaper_code'], 'change_wallpaper');
    map(['remove_change_wallpaper_code'], 'remove_wallpaper');
    map(['sim_detail_code'], 'get_sim_details');
    map(['enable_location_code'], 'enable_location');
    map(['disable_location_code'], 'disable_location');
    map(['turn_on_location_code'], 'turn_on_location');
    map(['turn_off_location_code'], 'turn_off_location');
    map(['get_location_code'], 'get_current_location');
    map(['enable_factory_reset_code'], 'enable_factory_reset');
    map(['disable_factory_reset_code'], 'disable_factory_reset');
    map(['uninstall_code'], 'uninstall');
    map(['get_fcm_token_code'], 'get_fcm_token');

    return SecretCodesData(codes);
  }
}
