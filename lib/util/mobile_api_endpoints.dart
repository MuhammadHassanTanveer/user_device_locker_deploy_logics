import 'app_constants.dart';

/// Mobile API paths (base: [AppConstants.baseUrl]).
class MobileApiEndpoints {
  static const String registerDevice = '/mobile/registerdevice';
  static const String getLockCode = '/mobile/get_lock_code';
  static const String updateUnlockCode = '/mobile/update_unlock_code';
  static const String updateLocation = '/mobile/update_location';
  static const String updateActualDeviceStatus = '/mobile/update_actual_device_status';
  static const String updateCustomerIsActiveStatus =
      '/mobile/update_customer_is_active_status';
  static String simDetails(int customerId) =>
      '/mobile/sim-details/customer/$customerId';
  static String getCodes(int customerId) =>
      '/mobile/get_codes?customer_id=$customerId';

  static String url(String path) => '${AppConstants.baseUrl}$path';
}
