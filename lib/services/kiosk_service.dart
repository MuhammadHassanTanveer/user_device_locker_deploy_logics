import 'package:flutter/services.dart';

/// KioskService provides access to Device Policy Controller (DPC) functionality
/// Similar to Android TestDPC, this service allows comprehensive device management
class KioskService {
  static const MethodChannel _channel = MethodChannel('kiosk_channel');

  // ==================== Status Checks ====================

  /// Check if this app is set as Device Owner
  static Future<bool> isDeviceOwner() async =>
      await _channel.invokeMethod<bool>('isDeviceOwner') ?? false;

  /// Check if this app is set as Profile Owner
  static Future<bool> isProfileOwner() async =>
      await _channel.invokeMethod<bool>('isProfileOwner') ?? false;

  /// Check if this app is either Device Owner or Profile Owner
  static Future<bool> isDeviceOrProfileOwner() async =>
      await _channel.invokeMethod<bool>('isDeviceOrProfileOwner') ?? false;

  /// Check if this app is an active device admin
  static Future<bool> isAdminActive() async =>
      await _channel.invokeMethod<bool>('isAdminActive') ?? false;

  // ==================== Kiosk Mode ====================

  /// Start kiosk mode (lock task mode)
  static Future<bool> startKiosk() async =>
      await _channel.invokeMethod<bool>('startKiosk') ?? false;

  /// Stop kiosk mode
  static Future<bool> stopKiosk() async =>
      await _channel.invokeMethod<bool>('stopKiosk') ?? false;

  /// Set packages allowed in lock task mode
  static Future<bool> setLockTaskPackages(List<String> packages) async =>
      await _channel.invokeMethod<bool>('setLockTaskPackages', {'packages': packages}) ?? false;

  /// Set lock task features (flags for what's allowed in kiosk mode)
  static Future<bool> setLockTaskFeatures(int flags) async =>
      await _channel.invokeMethod<bool>('setLockTaskFeatures', {'flags': flags}) ?? false;

  // ==================== Camera Control ====================

  /// Disable the device camera
  static Future<bool> disableCamera() async =>
      await _channel.invokeMethod<bool>('disableCamera') ?? false;

  /// Enable the device camera
  static Future<bool> enableCamera() async =>
      await _channel.invokeMethod<bool>('enableCamera') ?? false;

  /// Check if camera is disabled
  static Future<bool> isCameraDisabled() async =>
      await _channel.invokeMethod<bool>('isCameraDisabled') ?? false;

  // ==================== Screen Capture Control ====================

  /// Disable screen capture/screenshots
  static Future<bool> disableScreenCapture() async =>
      await _channel.invokeMethod<bool>('disableScreenCapture') ?? false;

  /// Enable screen capture/screenshots
  static Future<bool> enableScreenCapture() async =>
      await _channel.invokeMethod<bool>('enableScreenCapture') ?? false;

  /// Check if screen capture is disabled
  static Future<bool> isScreenCaptureDisabled() async =>
      await _channel.invokeMethod<bool>('isScreenCaptureDisabled') ?? false;

  // ==================== Factory Reset Control ====================

  /// Disable factory reset
  static Future<bool> disableFactoryReset() async =>
      await _channel.invokeMethod<bool>('disableFactoryReset') ?? false;

  /// Enable factory reset
  static Future<bool> enableFactoryReset() async =>
      await _channel.invokeMethod<bool>('enableFactoryReset') ?? false;

  // ==================== Safe Mode Control ====================

  /// Disable safe mode boot
  static Future<bool> disableSafeMode() async =>
      await _channel.invokeMethod<bool>('disableSafeMode') ?? false;

  /// Enable safe mode boot
  static Future<bool> enableSafeMode() async =>
      await _channel.invokeMethod<bool>('enableSafeMode') ?? false;

  // ==================== USB File Transfer Control ====================

  /// Disable USB file transfer
  static Future<bool> disableUSBFileTransfer() async =>
      await _channel.invokeMethod<bool>('disableUSBFileTransfer') ?? false;

  /// Enable USB file transfer
  static Future<bool> enableUSBFileTransfer() async =>
      await _channel.invokeMethod<bool>('enableUSBFileTransfer') ?? false;

  // ==================== App Install/Uninstall Control ====================

  /// Disable installing apps
  static Future<bool> disableInstallApps() async =>
      await _channel.invokeMethod<bool>('disableInstallApps') ?? false;

  /// Enable installing apps
  static Future<bool> enableInstallApps() async =>
      await _channel.invokeMethod<bool>('enableInstallApps') ?? false;

  /// Disable uninstalling apps
  static Future<bool> disableUninstallApps() async =>
      await _channel.invokeMethod<bool>('disableUninstallApps') ?? false;

  /// Enable uninstalling apps
  static Future<bool> enableUninstallApps() async =>
      await _channel.invokeMethod<bool>('enableUninstallApps') ?? false;

  // ==================== Location Control ====================

  /// Turn ON location and prevent user from changing it (locks the toggle)
  static Future<bool> enableLocationAndLock() async =>
      await _channel.invokeMethod<bool>('enableLocationAndLock') ?? false;

  /// Turn OFF location and prevent user from changing it (locks the toggle)
  static Future<bool> disableLocationAndLock() async =>
      await _channel.invokeMethod<bool>('disableLocationAndLock') ?? false;

  /// Allow user to change location settings again (unlocks the toggle)
  static Future<bool> unlockLocationSettings() async =>
      await _channel.invokeMethod<bool>('unlockLocationSettings') ?? false;

  /// Enable location configuration - user CAN toggle location on/off in system settings
  static Future<bool> enableLocationConfig() async =>
      await _channel.invokeMethod<bool>('enableLocationConfig') ?? false;

  /// Disable location configuration - user CANNOT toggle location on/off in system settings
  static Future<bool> disableLocationConfig() async =>
      await _channel.invokeMethod<bool>('disableLocationConfig') ?? false;

  /// Just turn on location (without affecting the system toggle restriction)
  static Future<bool> turnOnLocation() async =>
      await _channel.invokeMethod<bool>('turnOnLocation') ?? false;

  /// Set location enabled/disabled (enables config so user can toggle)
  static Future<bool> setLocationEnabled(bool enabled) async =>
      await _channel.invokeMethod<bool>('setLocationEnabled', {'enabled': enabled}) ?? false;

  // ==================== WiFi Control ====================

  /// Disable WiFi configuration
  static Future<bool> disableConfigWifi() async =>
      await _channel.invokeMethod<bool>('disableConfigWifi') ?? false;

  /// Enable WiFi configuration
  static Future<bool> enableConfigWifi() async =>
      await _channel.invokeMethod<bool>('enableConfigWifi') ?? false;

  // ==================== Bluetooth Control ====================

  /// Disable Bluetooth configuration
  static Future<bool> disableConfigBluetooth() async =>
      await _channel.invokeMethod<bool>('disableConfigBluetooth') ?? false;

  /// Enable Bluetooth configuration
  static Future<bool> enableConfigBluetooth() async =>
      await _channel.invokeMethod<bool>('enableConfigBluetooth') ?? false;

  // ==================== Volume Control ====================

  /// Disable volume adjustment
  static Future<bool> disableAdjustVolume() async =>
      await _channel.invokeMethod<bool>('disableAdjustVolume') ?? false;

  /// Enable volume adjustment
  static Future<bool> enableAdjustVolume() async =>
      await _channel.invokeMethod<bool>('enableAdjustVolume') ?? false;

  // ==================== Calls/SMS Control ====================

  /// Disable outgoing calls
  static Future<bool> disableOutgoingCalls() async =>
      await _channel.invokeMethod<bool>('disableOutgoingCalls') ?? false;

  /// Enable outgoing calls
  static Future<bool> enableOutgoingCalls() async =>
      await _channel.invokeMethod<bool>('enableOutgoingCalls') ?? false;

  /// Disable SMS
  static Future<bool> disableSMS() async =>
      await _channel.invokeMethod<bool>('disableSMS') ?? false;

  /// Enable SMS
  static Future<bool> enableSMS() async =>
      await _channel.invokeMethod<bool>('enableSMS') ?? false;

  // ==================== App Management ====================

  /// Hide an app
  static Future<bool> hideApp(String packageName, {bool hidden = true}) async =>
      await _channel.invokeMethod<bool>('hideApp', {'packageName': packageName, 'hidden': hidden}) ?? false;

  /// Hide this app from the launcher
  /// The app will still be running but won't appear in app drawer
  static Future<bool> hideSelf() async =>
      await _channel.invokeMethod<bool>('hideSelf') ?? false;

  /// Show this app in the launcher again
  static Future<bool> showSelf() async =>
      await _channel.invokeMethod<bool>('showSelf') ?? false;

  /// Move app to background and close the current activity (after hiding from launcher).
  static Future<bool> exitApp() async =>
      await _channel.invokeMethod<bool>('exitApp') ?? false;

  /// Open this app's system settings page (for uninstall).
  static Future<bool> openAppSettings() async =>
      await _channel.invokeMethod<bool>('openAppSettings') ?? false;

  /// Full uninstall prep: server status, policy cleanup, show launcher icon, open UI.
  static Future<bool> prepareForUninstallAndShowUi() async =>
      await _channel.invokeMethod<bool>('prepareForUninstallAndShowUi') ?? false;

  static Future<bool> hasPendingUninstallUi() async =>
      await _channel.invokeMethod<bool>('hasPendingUninstallUi') ?? false;

  static Future<bool> clearPendingUninstallUi() async =>
      await _channel.invokeMethod<bool>('clearPendingUninstallUi') ?? false;

  /// Check if this app is hidden
  static Future<bool> isSelfHidden() async =>
      await _channel.invokeMethod<bool>('isSelfHidden') ?? false;

  /// Check if an app is hidden
  static Future<bool> isAppHidden(String packageName) async =>
      await _channel.invokeMethod<bool>('isAppHidden', {'packageName': packageName}) ?? false;

  /// Hide all user apps (excluding system apps and this app)
  /// Returns the number of apps hidden
  static Future<int> hideAllApps() async =>
      await _channel.invokeMethod<int>('hideAllApps') ?? 0;

  /// Show all currently hidden apps (restore visibility)
  /// Returns the number of apps shown
  static Future<int> showAllApps() async =>
      await _channel.invokeMethod<int>('showAllApps') ?? 0;

  /// Get list of all user apps (package names)
  static Future<List<String>> getAllUserApps() async {
    final result = await _channel.invokeMethod<List<dynamic>>('getAllUserApps');
    return result?.cast<String>() ?? [];
  }

  /// Get list of currently hidden apps
  static Future<List<String>> getHiddenApps() async {
    final result = await _channel.invokeMethod<List<dynamic>>('getHiddenApps');
    return result?.cast<String>() ?? [];
  }

  /// Suspend an app
  static Future<bool> suspendApp(String packageName, {bool suspended = true}) async =>
      await _channel.invokeMethod<bool>('suspendApp', {'packageName': packageName, 'suspended': suspended}) ?? false;

  /// Block uninstall for an app
  static Future<bool> blockUninstall(String packageName, {bool blocked = true}) async =>
      await _channel.invokeMethod<bool>('blockUninstall', {'packageName': packageName, 'blocked': blocked}) ?? false;

  // ==================== Keyguard/Status Bar ====================

  /// Set keyguard (lock screen) disabled
  static Future<bool> setKeyguardDisabled(bool disabled) async =>
      await _channel.invokeMethod<bool>('setKeyguardDisabled', {'disabled': disabled}) ?? false;

  /// Set status bar disabled
  static Future<bool> setStatusBarDisabled(bool disabled) async =>
      await _channel.invokeMethod<bool>('setStatusBarDisabled', {'disabled': disabled}) ?? false;

  // ==================== Device Actions ====================

  /// Lock the device immediately
  static Future<bool> lockNow() async =>
      await _channel.invokeMethod<bool>('lockNow') ?? false;

  /// Reboot the device (Device Owner only)
  static Future<bool> reboot() async =>
      await _channel.invokeMethod<bool>('reboot') ?? false;

  /// Wipe device data (factory reset)
  static Future<bool> wipeData({int flags = 0}) async =>
      await _channel.invokeMethod<bool>('wipeData', {'flags': flags}) ?? false;

  // ==================== Time/Timezone ====================

  /// Set device time
  static Future<bool> setTime(int timeMillis) async =>
      await _channel.invokeMethod<bool>('setTime', {'timeMillis': timeMillis}) ?? false;

  /// Set device timezone
  static Future<bool> setTimeZone(String timeZone) async =>
      await _channel.invokeMethod<bool>('setTimeZone', {'timeZone': timeZone}) ?? false;

  // ==================== System Update Policy ====================

  /// Set automatic system updates
  static Future<bool> setAutomaticSystemUpdates() async =>
      await _channel.invokeMethod<bool>('setAutomaticSystemUpdates') ?? false;

  /// Postpone system updates
  static Future<bool> postponeSystemUpdates() async =>
      await _channel.invokeMethod<bool>('postponeSystemUpdates') ?? false;

  // ==================== Logging ====================

  /// Enable/disable network logging
  static Future<bool> setNetworkLoggingEnabled(bool enabled) async =>
      await _channel.invokeMethod<bool>('setNetworkLoggingEnabled', {'enabled': enabled}) ?? false;

  /// Enable/disable security logging
  static Future<bool> setSecurityLoggingEnabled(bool enabled) async =>
      await _channel.invokeMethod<bool>('setSecurityLoggingEnabled', {'enabled': enabled}) ?? false;

  // ==================== Launcher ====================

  /// Set this app as the default launcher
  static Future<bool> setAsDefaultLauncher() async =>
      await _channel.invokeMethod<bool>('setAsDefaultLauncher') ?? false;

  /// Clear default launcher setting
  static Future<bool> clearDefaultLauncher() async =>
      await _channel.invokeMethod<bool>('clearDefaultLauncher') ?? false;

  // ==================== IMEI ====================

  /// Get device IMEI (works on Android 9 and below for all apps,
  /// Android 10+ requires Device Owner)
  /// Returns primary IMEI (slot 0)
  static Future<String?> getIMEI() async =>
      await _channel.invokeMethod<String>('getIMEI');

  /// Get all IMEIs for dual SIM phones
  /// Returns a list of IMEIs (e.g., ["IMEI1", "IMEI2"] for dual SIM)
  static Future<List<String>> getAllIMEIs() async {
    final List<dynamic>? result = await _channel.invokeMethod<List<dynamic>>('getAllIMEIs');
    return result?.map((e) => e.toString()).toList() ?? [];
  }

  // ==================== User Restrictions ====================

  /// Add a user restriction
  static Future<bool> addUserRestriction(String restriction) async =>
      await _channel.invokeMethod<bool>('addUserRestriction', {'restriction': restriction}) ?? false;

  /// Clear a user restriction
  static Future<bool> clearUserRestriction(String restriction) async =>
      await _channel.invokeMethod<bool>('clearUserRestriction', {'restriction': restriction}) ?? false;

  // ==================== Accessibility ====================

  /// Check if accessibility service is enabled
  static Future<bool> isAccessibilityEnabled() async =>
      await _channel.invokeMethod<bool>('isAccessibilityEnabled') ?? false;

  /// Open accessibility settings
  static Future<bool> openAccessibilitySettings() async =>
      await _channel.invokeMethod<bool>('openAccessibilitySettings') ?? false;

  // ==================== Lock Overlay ====================

  /// Show full-screen lock overlay
  static Future<bool> showLockOverlay({String userId = '', String pin = '1234'}) async =>
      await _channel.invokeMethod<bool>('showLockOverlay', {'userId': userId, 'pin': pin}) ?? false;

  /// Hide lock overlay
  static Future<bool> hideLockOverlay() async =>
      await _channel.invokeMethod<bool>('hideLockOverlay') ?? false;

  /// Check if lock overlay is currently showing
  static Future<bool> isLockOverlayShowing() async =>
      await _channel.invokeMethod<bool>('isLockOverlayShowing') ?? false;

  // ==================== Permission Locking ====================

  /// Lock all runtime permissions for this app.
  /// This prevents the user from revoking any granted permissions.
  static Future<bool> lockAllAppPermissions() async =>
      await _channel.invokeMethod<bool>('lockAllAppPermissions') ?? false;

  /// Unlock all runtime permissions (allow user to change them again)
  static Future<bool> unlockAllAppPermissions() async =>
      await _channel.invokeMethod<bool>('unlockAllAppPermissions') ?? false;

  /// Set permission grant state for a specific permission
  /// grantState: 0 = default (user-controllable), 1 = granted (locked), 2 = denied (locked)
  static Future<bool> setPermissionGrantState(String permission, {String? packageName, int grantState = 1}) async =>
      await _channel.invokeMethod<bool>('setPermissionGrantState', {
        'packageName': packageName,
        'permission': permission,
        'grantState': grantState,
      }) ?? false;

  /// Get permission grant state for a specific permission
  /// Returns: 0 = default, 1 = granted (locked), 2 = denied (locked)
  static Future<int> getPermissionGrantState(String permission, {String? packageName}) async =>
      await _channel.invokeMethod<int>('getPermissionGrantState', {
        'packageName': packageName,
        'permission': permission,
      }) ?? 0;

  /// Disable access to app settings (prevents user from going to app info screen)
  static Future<bool> disableConfigApps() async =>
      await _channel.invokeMethod<bool>('disableConfigApps') ?? false;

  /// Enable access to app settings
  static Future<bool> enableConfigApps() async =>
      await _channel.invokeMethod<bool>('enableConfigApps') ?? false;

  /// Block uninstall for this app itself
  static Future<bool> blockSelfUninstall() async =>
      await _channel.invokeMethod<bool>('blockSelfUninstall') ?? false;

  /// Allow uninstall for this app
  static Future<bool> allowSelfUninstall() async =>
      await _channel.invokeMethod<bool>('allowSelfUninstall') ?? false;

  /// Lock all permissions after they are granted.
  /// Also blocks access to app settings to prevent user from changing permissions.
  static Future<bool> lockPermissionsAfterGrant() async =>
      await _channel.invokeMethod<bool>('lockPermissionsAfterGrant') ?? false;

  /// Unlock permissions and allow user to change them again.
  static Future<bool> unlockPermissions() async =>
      await _channel.invokeMethod<bool>('unlockPermissions') ?? false;

  // ==================== Overlay Permission Locking ====================

  /// Lock the overlay (SYSTEM_ALERT_WINDOW) permission after it's granted.
  /// This prevents the user from disabling "Display over other apps" permission.
  /// The permission must already be granted before calling this.
  /// NOTE: This uses minimal restrictions - does NOT block accessibility settings.
  static Future<bool> lockOverlayPermission() async =>
      await _channel.invokeMethod<bool>('lockOverlayPermission') ?? false;

  /// Unlock the overlay permission (allow user to change it again)
  static Future<bool> unlockOverlayPermission() async =>
      await _channel.invokeMethod<bool>('unlockOverlayPermission') ?? false;

  /// Check if overlay permission is locked
  static Future<bool> isOverlayPermissionLocked() async =>
      await _channel.invokeMethod<bool>('isOverlayPermissionLocked') ?? false;

  /// Apply comprehensive kiosk-mode restrictions when overlay is SHOWN (device is locked).
  ///
  /// Implements the typical kiosk app solution:
  /// 1. Enable Lock Task Mode (user cannot leave this app)
  /// 2. Hide Settings app (and vendor-specific settings)
  /// 3. Block access to app settings (DISALLOW_APPS_CONTROL, no_config_apps)
  /// 4. Block debugging features (ADB access)
  /// 5. Block safe boot (prevents bypass via safe mode)
  /// 6. Block factory reset
  /// 7. Intercept settings intents (redirect to app)
  ///
  /// This prevents the user from accessing settings to disable overlay permission.
  /// Should be called when showing the lock overlay.
  ///
  /// NOTE: This is also called automatically by LockOverlayService for redundancy.
  static Future<bool> lockSettingsWhenOverlayShown() async =>
      await _channel.invokeMethod<bool>('lockSettingsWhenOverlayShown') ?? false;

  /// Remove all kiosk-mode restrictions when overlay is HIDDEN (device is unlocked).
  ///
  /// This reverses all restrictions applied by lockSettingsWhenOverlayShown:
  /// - Clears Lock Task Mode
  /// - Unhides Settings apps
  /// - Clears all user restrictions
  /// - Clears intercepted intents
  ///
  /// Should be called when closing the lock overlay.
  ///
  /// NOTE: This is also called automatically by LockOverlayService for redundancy.
  static Future<bool> unlockSettingsWhenOverlayHidden() async =>
      await _channel.invokeMethod<bool>('unlockSettingsWhenOverlayHidden') ?? false;

  // ==================== Notification Permission Locking ====================

  /// Lock the notification (POST_NOTIFICATIONS) permission after it's granted.
  /// This prevents the user from disabling notifications.
  /// The permission must already be granted before calling this.
  /// Only works on Android 13+ (API 33+)
  static Future<bool> lockNotificationPermission() async =>
      await _channel.invokeMethod<bool>('lockNotificationPermission') ?? false;

  /// Unlock the notification permission (allow user to change it again)
  static Future<bool> unlockNotificationPermission() async =>
      await _channel.invokeMethod<bool>('unlockNotificationPermission') ?? false;

  /// Disable/Deny the notification permission.
  /// This revokes the notification permission and locks it in denied state.
  /// The user will not be able to re-enable notifications from settings.
  /// Only works on Android 13+ (API 33+)
  static Future<bool> disableNotificationPermission() async =>
      await _channel.invokeMethod<bool>('disableNotificationPermission') ?? false;

  /// Check if notification permission is locked
  static Future<bool> isNotificationPermissionLocked() async =>
      await _channel.invokeMethod<bool>('isNotificationPermissionLocked') ?? false;

  /// Check if notification permission is disabled (denied and locked)
  static Future<bool> isNotificationPermissionDisabled() async =>
      await _channel.invokeMethod<bool>('isNotificationPermissionDisabled') ?? false;

  /// Force re-lock notification permission.
  /// Use this for Realme/OPPO/Xiaomi devices where the lock might not stick.
  /// This will re-grant and re-lock the notification permission.
  static Future<bool> forceRelockNotificationPermission() async =>
      await _channel.invokeMethod<bool>('forceRelockNotificationPermission') ?? false;

  /// Start the notification permission monitor service.
  /// This service monitors notification permission and re-enables it if user disables it.
  /// Specifically designed for Realme/OPPO/Xiaomi devices where setPermissionGrantState may not fully work.
  static Future<bool> startNotificationMonitor() async =>
      await _channel.invokeMethod<bool>('startNotificationMonitor') ?? false;

  /// Stop the notification permission monitor service.
  static Future<bool> stopNotificationMonitor() async =>
      await _channel.invokeMethod<bool>('stopNotificationMonitor') ?? false;

  /// Ultra-aggressive notification permission lock specifically for Realme/OPPO/ColorOS devices.
  /// Uses ALL available techniques to prevent user from disabling notifications.
  /// Call this after granting notification permission on Realme devices.
  static Future<bool> lockNotificationPermissionForRealme() async =>
      await _channel.invokeMethod<bool>('lockNotificationPermissionForRealme') ?? false;

  // ==================== Status Bar Broadcast ====================

  /// Enable status bar via broadcast (works from any isolate)
  /// This sends a broadcast to native side which handles status bar enable
  static Future<bool> enableStatusBarViaBroadcast() async =>
      await _channel.invokeMethod<bool>('sendEnableStatusBarBroadcast') ?? false;

  /// Disable status bar via broadcast (works from any isolate)
  /// This sends a broadcast to native side which handles status bar disable
  static Future<bool> disableStatusBarViaBroadcast() async =>
      await _channel.invokeMethod<bool>('sendDisableStatusBarBroadcast') ?? false;

  // ==================== Lock Activity (Lock Task Mode) ====================

  /// Start the native Lock Activity with Lock Task Mode (Kiosk Mode)
  /// This is more reliable than overlay because:
  /// 1. Doesn't require overlay permission
  /// 2. Blocks home/recent buttons via Lock Task Mode
  /// 3. Status bar is disabled via DevicePolicyManager
  /// 4. User cannot exit without correct PIN
  static Future<bool> startLockActivity() async =>
      await _channel.invokeMethod<bool>('startLockActivity') ?? false;

  /// Stop the Lock Activity and exit Lock Task Mode
  static Future<bool> stopLockActivity() async =>
      await _channel.invokeMethod<bool>('stopLockActivity') ?? false;

  /// Check if Lock Activity is currently showing
  static Future<bool> isLockActivityShowing() async =>
      await _channel.invokeMethod<bool>('isLockActivityShowing') ?? false;

  // ==================== FRP (Factory Reset Protection) Control ====================

  /// Set a single FRP (Factory Reset Protection) account.
  ///
  /// After factory reset, the device will require this Google account to unlock.
  ///
  /// IMPORTANT: This is a "hidden" FRP account - it is NOT added to Settings > Accounts.
  /// It is only set in the FRP policy, so the user cannot see or remove it.
  ///
  /// After factory reset:
  /// 1. Device starts setup wizard
  /// 2. Google verifies FRP
  /// 3. User must sign in with the specified account
  /// 4. If user doesn't know the account, device is locked
  ///
  /// Requires Android 11 (API 30) or higher and Device Owner.
  ///
  /// [accountEmail] - The Google account email for FRP (e.g., "ghazanfar@tech4uk.uk")
  static Future<bool> setFrpAccount(String accountEmail) async =>
      await _channel.invokeMethod<bool>('setFrpAccount', {'accountEmail': accountEmail}) ?? false;

  /// Set multiple FRP accounts.
  /// Any of these accounts can be used to unlock the device after factory reset.
  ///
  /// Requires Android 11 (API 30) or higher and Device Owner.
  ///
  /// [accountEmails] - List of Google account emails for FRP
  static Future<bool> setFrpAccounts(List<String> accountEmails) async =>
      await _channel.invokeMethod<bool>('setFrpAccounts', {'accountEmails': accountEmails}) ?? false;

  /// Clear FRP policy (remove FRP protection).
  /// After this, factory reset will not require any specific account.
  ///
  /// Requires Android 11 (API 30) or higher and Device Owner.
  static Future<bool> clearFrpPolicy() async =>
      await _channel.invokeMethod<bool>('clearFrpPolicy') ?? false;

  /// Get current FRP accounts.
  ///
  /// Returns a list of FRP account emails, or empty list if not set.
  ///
  /// Requires Android 11 (API 30) or higher and Device Owner.
  static Future<List<String>> getFrpAccounts() async {
    final List<dynamic>? result = await _channel.invokeMethod<List<dynamic>>('getFrpAccounts');
    return result?.map((e) => e.toString()).toList() ?? [];
  }

  /// Check if FRP is enabled (has accounts set).
  ///
  /// Returns true if FRP is enabled with at least one account.
  ///
  /// Requires Android 11 (API 30) or higher and Device Owner.
  static Future<bool> isFrpEnabled() async =>
      await _channel.invokeMethod<bool>('isFrpEnabled') ?? false;

  /// Enable FRP protection with a company/locker account.
  /// This is a convenience method that sets a single FRP account.
  ///
  /// The account is HIDDEN from the user - they cannot see it in Settings > Accounts.
  /// After factory reset, the user must sign in with this account to unlock the device.
  ///
  /// Requires Android 11 (API 30) or higher and Device Owner.
  ///
  /// [accountEmail] - The Google account email for FRP
  static Future<bool> enableFrpProtection(String accountEmail) async =>
      await _channel.invokeMethod<bool>('enableFrpProtection', {'accountEmail': accountEmail}) ?? false;

  /// Disable FRP protection (clear FRP policy).
  /// After this, factory reset will not require any specific account.
  ///
  /// Requires Android 11 (API 30) or higher and Device Owner.
  static Future<bool> disableFrpProtection() async =>
      await _channel.invokeMethod<bool>('disableFrpProtection') ?? false;

  // ==================== Android Lock Screen Password Management ====================

  /// Set the password reset token. Must be called once before password can be changed.
  /// This should be called when the device has no password set or when the user
  /// authenticates with their current credentials.
  ///
  /// Requires Android 8.0 (API 26) or higher and Device Owner.
  static Future<bool> setResetPasswordToken() async =>
      await _channel.invokeMethod<bool>('setResetPasswordToken') ?? false;

  /// Check if the password reset token is active.
  /// Token becomes active after user confirms their credentials (if password was set).
  ///
  /// Requires Android 8.0 (API 26) or higher and Device Owner.
  static Future<bool> isResetPasswordTokenActive() async =>
      await _channel.invokeMethod<bool>('isResetPasswordTokenActive') ?? false;

  /// Clear the password reset token.
  ///
  /// Requires Android 8.0 (API 26) or higher and Device Owner.
  static Future<bool> clearResetPasswordToken() async =>
      await _channel.invokeMethod<bool>('clearResetPasswordToken') ?? false;

  /// Set/Change the Android lock screen password or PIN.
  ///
  /// [password] - The new password/PIN. Use empty string "" to remove password.
  ///
  /// Requirements:
  /// - Device Owner permission
  /// - For Android 8.0+: Password reset token must be set and active
  /// - For Android 7.x and below: Uses deprecated resetPassword API
  ///
  /// Note: If token is not active, user may need to confirm current credentials first.
  static Future<bool> setLockScreenPassword(String password) async =>
      await _channel.invokeMethod<bool>('setLockScreenPassword', {'password': password}) ?? false;

  /// Remove the Android lock screen password/PIN.
  /// After this, the device will unlock directly without any PIN/password.
  ///
  /// Requirements:
  /// - Device Owner permission
  /// - For Android 8.0+: Password reset token must be set and active
  static Future<bool> removeLockScreenPassword() async =>
      await _channel.invokeMethod<bool>('removeLockScreenPassword') ?? false;

  /// Set the minimum password quality requirement.
  ///
  /// Common quality values:
  /// - 0: PASSWORD_QUALITY_UNSPECIFIED (allows no password)
  /// - 65536: PASSWORD_QUALITY_SOMETHING (any lock)
  /// - 131072: PASSWORD_QUALITY_NUMERIC (PIN)
  /// - 262144: PASSWORD_QUALITY_ALPHABETIC
  /// - 327680: PASSWORD_QUALITY_ALPHANUMERIC
  /// - 393216: PASSWORD_QUALITY_COMPLEX
  ///
  /// Requires Device Owner.
  static Future<bool> setPasswordQuality(int quality) async =>
      await _channel.invokeMethod<bool>('setPasswordQuality', {'quality': quality}) ?? false;

  /// Allow no password on the device.
  /// This sets password quality to UNSPECIFIED, allowing the device to have no lock screen.
  ///
  /// Requires Device Owner.
  static Future<bool> allowNoPassword() async =>
      await _channel.invokeMethod<bool>('allowNoPassword') ?? false;

  /// Get the current lock screen password status including token info.
  ///
  /// Returns a map with:
  /// - isDeviceOwner: Whether this app is device owner
  /// - androidVersion: Android SDK version
  /// - tokenActive: Whether password reset token is active
  /// - hasStoredToken: Whether token is stored
  /// - hasPassword: Whether device has a lock screen password set
  /// - legacyMode: True if using pre-Android 8 legacy API
  static Future<Map<String, dynamic>> getLockScreenStatus() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>('getLockScreenStatus');
    return result?.map((k, v) => MapEntry(k.toString(), v)) ?? {};
  }

  /// Setup password reset token and get detailed status.
  /// This is the recommended method to prepare for remote password management.
  ///
  /// Returns a map with:
  /// - success: Whether token was set
  /// - tokenActive: Whether token is active (can change password now)
  /// - needsUserUnlock: Whether user needs to unlock to activate token
  /// - message: Human readable status
  ///
  /// If [needsUserUnlock] is true, the user must unlock the device once
  /// with their current PIN/password. After that, the token will be active
  /// and you can change/remove the password remotely.
  static Future<Map<String, dynamic>> setupPasswordResetTokenWithStatus() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>('setupPasswordResetTokenWithStatus');
    return result?.map((k, v) => MapEntry(k.toString(), v)) ?? {};
  }

  /// Force remove ALL device unlock methods - PIN, password, pattern, fingerprint, face unlock.
  /// This aggressively removes all security by:
  /// 1. Deleting existing token
  /// 2. Creating new token
  /// 3. Disabling keyguard features (fingerprint, face, etc.)
  /// 4. Removing password via multiple methods
  ///
  /// Returns a map with:
  /// - success: Whether all unlock methods were removed
  /// - method: Which method succeeded ('password_removed', 'keyguard_disabled', 'security_cleared')
  /// - needsUserUnlock: If true, user must unlock device with current PIN first
  /// - message: Human readable status
  static Future<Map<String, dynamic>> forceRemovePassword() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>('forceRemovePassword');
    return result?.map((k, v) => MapEntry(k.toString(), v)) ?? {};
  }

  /// Change or remove password with detailed status response.
  ///
  /// [password] - The new password. Use empty string "" to remove password.
  ///
  /// Returns a map with:
  /// - success: Whether password was changed
  /// - tokenActive: Whether token is active
  /// - needsUserUnlock: Whether user needs to unlock to activate token
  /// - message: Human readable status
  ///
  /// If [needsUserUnlock] is true in the response, the password was NOT changed
  /// and the user must unlock the device once with their current PIN/password first.
  static Future<Map<String, dynamic>> changePasswordWithStatus(String password) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>('changePasswordWithStatus', {'password': password});
    return result?.map((k, v) => MapEntry(k.toString(), v)) ?? {};
  }

  /// Change password with forced token reset.
  /// 
  /// This method:
  /// 1. Deletes existing token if any exists
  /// 2. Sets a new token
  /// 3. Sets the new password
  /// 
  /// This is useful when you want to ensure a fresh token is used.
  ///
  /// [password] - The new password/PIN to set.
  ///
  /// Returns a map with:
  /// - success: Whether password was changed
  /// - tokenActive: Whether token is active
  /// - needsUserUnlock: Whether user needs to unlock to activate token
  /// - message: Human readable status
  static Future<Map<String, dynamic>> changePasswordWithForcedTokenReset(String password) async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>('changePasswordWithForcedTokenReset', {'password': password});
    return result?.map((k, v) => MapEntry(k.toString(), v)) ?? {};
  }

  /// Remove password using the "0000 then empty" approach.
  /// 
  /// This method:
  /// 1. First changes the password to "0000"
  /// 2. Then uses "0000" password context to set no password (empty)
  /// 3. Keeps the token active for future commands
  /// 
  /// This is more reliable than directly removing the password because
  /// some devices don't allow direct removal.
  ///
  /// Returns a map with:
  /// - success: Whether password was removed
  /// - tokenActive: Whether token is active (should remain active)
  /// - needsUserUnlock: Whether user needs to unlock to activate token
  /// - message: Human readable status
  static Future<Map<String, dynamic>> removePasswordViaTemporaryPin() async {
    final result = await _channel.invokeMethod<Map<dynamic, dynamic>>('removePasswordViaTemporaryPin');
    return result?.map((k, v) => MapEntry(k.toString(), v)) ?? {};
  }

  /// Check if the device has a secure lock screen (PIN/password/pattern)
  static Future<bool> isDeviceSecure() async =>
      await _channel.invokeMethod<bool>('isDeviceSecure') ?? false;

  // ==================== Wallpaper Control ====================

  /// Change the device wallpaper from a URL.
  /// Downloads the image from the URL and sets it as both home and lock screen wallpaper.
  ///
  /// [wallpaperUrl] - The URL of the image to set as wallpaper.
  ///
  /// The current wallpaper is backed up before changing, allowing restore later.
  /// Requires SET_WALLPAPER permission.
  static Future<bool> changeWallpaper(String wallpaperUrl) async =>
      await _channel.invokeMethod<bool>('changeWallpaper', {'wallpaperUrl': wallpaperUrl}) ?? false;

  /// Remove the custom wallpaper and restore the original/system default.
  ///
  /// If the original wallpaper was backed up when changing, it will be restored.
  /// Otherwise, the system default wallpaper will be applied.
  /// Requires SET_WALLPAPER permission.
  static Future<bool> removeWallpaper() async =>
      await _channel.invokeMethod<bool>('removeWallpaper') ?? false;

  // ==================== Message Overlay Control ====================

  /// Show a message overlay dialog to the customer.
  ///
  /// The overlay displays a dialog with the company name (from SharedPreferences)
  /// as the title, and the provided message. The user can dismiss it by clicking
  /// the close icon or OK button.
  ///
  /// @param message The message to display to the customer
  /// @param companyName Optional company name (if not provided, reads from SharedPreferences)
  static Future<bool> showMessageOverlay({required String message, String? companyName}) async =>
      await _channel.invokeMethod<bool>('showMessageOverlay', {
        'message': message,
        if (companyName != null) 'companyName': companyName,
      }) ?? false;

  /// Hide the message overlay service if it's currently showing.
  ///
  /// This is used to dismiss the customer message dialog overlay,
  /// typically when an unlock command is received.
  static Future<bool> hideMessageOverlay() async =>
      await _channel.invokeMethod<bool>('hideMessageOverlay') ?? false;

  /// Hide all overlays (LockOverlay, MessageOverlay, LockActivity).
  ///
  /// This is a convenience method to ensure all lock/overlay screens are hidden.
  static Future<bool> hideAllOverlays() async =>
      await _channel.invokeMethod<bool>('hideAllOverlays') ?? false;

  // ==================== SIM Details ====================

  /// Get SIM details and send them to the server.
  ///
  /// This method collects information about all active SIM cards including:
  /// - Carrier name, display name, country ISO
  /// - Phone number (if available)
  /// - Network type (4G LTE, 5G, 3G, etc.)
  ///
  /// The collected data is automatically sent to the server API.
  /// Collect SIM data and POST to sim-details API (native).
  /// Requires READ_PHONE_STATE permission.
  static Future<bool> getSimDetails() async =>
      await _channel.invokeMethod<bool>('getSimDetails') ?? false;

  /// Start native SIM slot monitoring (banner + notification when SIM missing).
  static Future<bool> startSimMonitoring() async =>
      await _channel.invokeMethod<bool>('startSimMonitoring') ?? false;

  /// Re-check SIM slots and show/hide the top warning banner.
  static Future<bool> refreshSimWarning() async =>
      await _channel.invokeMethod<bool>('refreshSimWarning') ?? false;

  /// Collect SIM data for [RegisterDeviceProvider.updateDeviceSimDetailsApi].
  /// sim1_* = physical slot 0, sim2_* = physical slot 1.
  static Future<Map<String, dynamic>?> getSimDetailsForApi() async {
    final raw = await _channel.invokeMethod<Map<Object?, Object?>>('getSimDetailsForApi');
    if (raw == null) return null;
    return raw.map((key, value) => MapEntry(key.toString(), value));
  }

  // ==================== Factory Reset Warning ====================

  /// Show a warning overlay when user attempts to access factory reset settings.
  ///
  /// This overlay displays a warning dialog preventing unauthorized factory resets.
  /// The overlay is shown over the Settings app when the accessibility service
  /// detects navigation to factory reset related pages.
  ///
  /// @param title Optional custom title for the warning dialog
  /// @param message Optional custom message for the warning dialog
  ///
  /// Requires SYSTEM_ALERT_WINDOW permission and Accessibility Service enabled.
  static Future<bool> showFactoryResetWarning({String? title, String? message}) async =>
      await _channel.invokeMethod<bool>('showFactoryResetWarning', {
        if (title != null) 'title': title,
        if (message != null) 'message': message,
      }) ?? false;

  /// Hide the factory reset warning overlay if it's currently showing.
  static Future<bool> hideFactoryResetWarning() async =>
      await _channel.invokeMethod<bool>('hideFactoryResetWarning') ?? false;

  /// Check if the factory reset warning overlay is currently showing.
  static Future<bool> isFactoryResetWarningShowing() async =>
      await _channel.invokeMethod<bool>('isFactoryResetWarningShowing') ?? false;

  /// Enable or disable the automatic factory reset warning feature.
  ///
  /// When enabled, the accessibility service will monitor for factory reset
  /// settings pages and automatically show a warning overlay.
  ///
  /// @param enabled true to enable, false to disable the feature
  ///
  /// Note: Requires Accessibility Service to be enabled in system settings.
  static Future<bool> setFactoryResetWarningEnabled(bool enabled) async =>
      await _channel.invokeMethod<bool>('setFactoryResetWarningEnabled', {'enabled': enabled}) ?? false;
}
