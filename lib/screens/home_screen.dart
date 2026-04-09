import 'dart:async';
import 'dart:io';

import 'package:device_info_plus/device_info_plus.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../providers/register_device_provider.dart';
import '../services/kiosk_service.dart';
import 'register_device_screen.dart';
import 'welcome_screen.dart';

class PermissionItem {
  final String name;
  final String description;
  final Permission? permission; // null for special permissions like Device Admin
  final List<Permission>? multiplePermissions; // For combined permissions
  final IconData icon;
  final bool isDeviceAdmin; // Flag for Device Admin permission
  final bool isAccessibility; // Flag for Accessibility permission
  PermissionStatus status;

  PermissionItem({
    required this.name,
    required this.description,
    this.permission,
    this.multiplePermissions,
    required this.icon,
    this.isDeviceAdmin = false,
    this.isAccessibility = false,
    this.status = PermissionStatus.denied,
  });
}

class HomeScreen extends StatefulWidget {
  const HomeScreen({super.key});

  @override
  State<HomeScreen> createState() => _HomeScreenState();
}

class _HomeScreenState extends State<HomeScreen> with WidgetsBindingObserver {
  List<PermissionItem> permissions = [];
  bool isLoading = true;
  bool isDeviceOwner = false;
  bool _permissionsLocked = false;
  final bool _autoLockEnabled = true; // Auto-lock permissions when all granted

  static const MethodChannel _channel = MethodChannel('kiosk_channel');
  static const String _permissionsLockedKey = 'permissions_locked';

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _loadLockedState();
    _initPermissions();
  }

  Future<void> _loadLockedState() async {
    final prefs = await SharedPreferences.getInstance();
    setState(() {
      _permissionsLocked = prefs.getBool(_permissionsLockedKey) ?? false;
    });
  }

  Future<void> _saveLockedState(bool locked) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setBool(_permissionsLockedKey, locked);
    setState(() {
      _permissionsLocked = locked;
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Refresh permissions when app comes back to foreground
    if (state == AppLifecycleState.resumed) {
      _checkAllPermissions();
      // Re-verify and lock notification permission when app resumes
      // This catches cases where user might have changed it while app was in background
      _ensureNotificationPermissionLocked();
    }
  }

  /// Ensure notification permission stays locked when app resumes
  Future<void> _ensureNotificationPermissionLocked() async {
    if (!isDeviceOwner) return;
    
    try {
      final isLocked = await KioskService.isNotificationPermissionLocked();
      if (!isLocked) {
        debugPrint('⚠️ Notification permission not locked - re-locking...');
        await KioskService.lockNotificationPermission();
        await Future.delayed(const Duration(milliseconds: 200));
        await KioskService.forceRelockNotificationPermission();
      }
    } catch (e) {
      debugPrint('Error ensuring notification permission locked: $e');
    }
  }

  Future<void> _initPermissions() async {
    permissions = [
      // Device Admin - Special permission (must be set via ADB or device settings)
      PermissionItem(
        name: 'Device Admin',
        description: 'Required for camera control, factory reset protection',
        permission: null,
        icon: Icons.admin_panel_settings,
        isDeviceAdmin: true,
      ),
      PermissionItem(
        name: 'Phone & SMS',
        description: 'Required to get device IMEI and read SMS',
        permission: null,
        multiplePermissions: [Permission.phone, Permission.sms],
        icon: Icons.phone_android,
      ),
      // Accessibility permission - Required for accessibility service (after Phone & SMS)
      PermissionItem(
        name: 'Accessibility',
        description: 'Required for accessibility service to control device',
        permission: null,
        icon: Icons.accessibility_new,
        isAccessibility: true,
      ),
      // Combined Location + Background Location
      PermissionItem(
        name: 'Location',
        description: 'Required to access device location (foreground & background)',
        permission: null,
        multiplePermissions: [Permission.location, Permission.locationAlways],
        icon: Icons.location_on,
      ),
      PermissionItem(
        name: 'Camera',
        description: 'Required to use device camera',
        permission: Permission.camera,
        icon: Icons.camera_alt,
      ),
    ];

    // Add storage permissions based on Android version
    if (Platform.isAndroid) {
      // Get Android SDK version
      final androidInfo = await _getAndroidSdkVersion();

      if (androidInfo >= 33) {
        // Android 13+ uses granular media permissions - combined into single tile
        permissions.add(
          PermissionItem(
            name: 'Media',
            description: 'Required to access photos, videos, and audio files',
            permission: null,
            multiplePermissions: [Permission.photos, Permission.videos, Permission.audio],
            icon: Icons.perm_media,
          ),
        );
      } else {
        // Android 12 and below uses storage permission
        permissions.add(
          PermissionItem(
            name: 'Storage',
            description: 'Required to access files and media',
            permission: Permission.storage,
            icon: Icons.folder,
          ),
        );
      }
    }
    permissions.add(
      // Overlay permission - Required to show lock screen over all apps
      PermissionItem(
        name: 'Display Over Apps',
        description: 'Required to show lock screen overlay over all apps',
        permission: Permission.systemAlertWindow,
        icon: Icons.layers,
      ),
    );

    _checkAllPermissions();
  }

  Future<int> _getAndroidSdkVersion() async {
    if (!Platform.isAndroid) return 0;

    try {
      final deviceInfo = DeviceInfoPlugin();
      final androidInfo = await deviceInfo.androidInfo;
      return androidInfo.version.sdkInt;
    } catch (e) {
      return 32; // Default to Android 12 if detection fails
    }
  }

  Future<void> _checkAllPermissions() async {
    setState(() => isLoading = true);

    // Check Device Owner status
    try {
      isDeviceOwner = await _channel.invokeMethod<bool>('isDeviceOwner') ?? false;
    } catch (e) {
      isDeviceOwner = false;
    }

    for (var item in permissions) {
      if (item.isDeviceAdmin) {
        // Set status based on device owner check
        item.status = isDeviceOwner ? PermissionStatus.granted : PermissionStatus.denied;
      } else if (item.isAccessibility) {
        // Check Accessibility service status via platform channel
        try {
          final isEnabled = await _channel.invokeMethod<bool>('isAccessibilityEnabled') ?? false;
          item.status = isEnabled ? PermissionStatus.granted : PermissionStatus.denied;
        } catch (e) {
          item.status = PermissionStatus.denied;
        }
      } else if (item.multiplePermissions != null) {
        // Check all permissions in the list - all must be granted
        bool allGranted = true;
        bool anyPermanentlyDenied = false;

        for (var perm in item.multiplePermissions!) {
          final status = await perm.status;
          if (!status.isGranted) {
            allGranted = false;
          }
          if (status.isPermanentlyDenied) {
            anyPermanentlyDenied = true;
          }
        }

        if (allGranted) {
          item.status = PermissionStatus.granted;
        } else if (anyPermanentlyDenied) {
          item.status = PermissionStatus.permanentlyDenied;
        } else {
          item.status = PermissionStatus.denied;
        }
      } else if (item.permission != null) {
        item.status = await item.permission!.status;

        // If overlay permission is granted, lock it immediately
        if (item.permission == Permission.systemAlertWindow &&
            item.status.isGranted &&
            isDeviceOwner) {
          // Check if not already locked
          final isLocked = await KioskService.isOverlayPermissionLocked();
          if (!isLocked) {
            debugPrint('🔒 Overlay permission granted - locking it...');
            await KioskService.lockOverlayPermission();
          }
        }
      }
    }

    // Check if all permissions are granted
    final allGranted = permissions.every((p) => p.status.isGranted);

    // Auto-lock permissions when all are granted and device is owner
    if (allGranted && isDeviceOwner && _autoLockEnabled && !_permissionsLocked) {
      debugPrint('🔒 All permissions granted - auto-locking permissions...');
      await _autoLockPermissions();
    }

    setState(() => isLoading = false);
  }

  /// Automatically lock permissions after all are granted
  Future<void> _autoLockPermissions() async {
    try {
      // Lock all app permissions (prevents user from revoking them)
      final lockResult = await KioskService.lockPermissionsAfterGrant();

      if (lockResult) {
        await _saveLockedState(true);
        debugPrint('✅ Permissions locked successfully');

        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('All permissions granted and locked'),
              backgroundColor: Colors.green,
              duration: Duration(seconds: 3),
            ),
          );
        }
      } else {
        debugPrint('❌ Failed to lock permissions');
      }
    } catch (e) {
      debugPrint('❌ Error locking permissions: $e');
    }
  }

  /// Manually unlock permissions (for admin use)
  Future<void> _unlockPermissions() async {
    try {
      final unlockResult = await KioskService.unlockPermissions();

      if (unlockResult) {
        await _saveLockedState(false);
        debugPrint('✅ Permissions unlocked successfully');

        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('Permissions unlocked'),
              backgroundColor: Colors.orange,
              duration: Duration(seconds: 2),
            ),
          );
        }
      }
    } catch (e) {
      debugPrint('❌ Error unlocking permissions: $e');
    }
  }

  Future<void> _requestPermission(PermissionItem item) async {
    // Handle Device Admin separately
    if (item.isDeviceAdmin) {
      _showDeviceAdminDialog();
      return;
    }

    // Handle Accessibility separately
    if (item.isAccessibility) {
      _showAccessibilityDialog();
      return;
    }

    // Handle multiple permissions (Location + Background, Media, etc.)
    if (item.multiplePermissions != null) {
      await _requestMultiplePermissions(item);
      return;
    }

    if (item.permission == null) return;

    // Special handling for System Alert Window (Overlay) permission
    if (item.permission == Permission.systemAlertWindow) {
      final status = await Permission.systemAlertWindow.status;
      if (!status.isGranted) {
        _showOverlayPermissionDialog();
        return;
      }
    }

    PermissionStatus status = await item.permission!.request();

    // If permanently denied, open app settings
    if (status.isPermanentlyDenied) {
      final opened = await openAppSettings();
      if (!opened) {
        _showSnackBar('Could not open settings');
      }
      return;
    }

    setState(() {
      item.status = status;
    });

    // Refresh all permissions
    await _checkAllPermissions();
  }

  void _showOverlayPermissionDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Row(
          children: [
            Icon(Icons.layers, color: Colors.blue),
            SizedBox(width: 8),
            Text('Display Over Apps'),
          ],
        ),
        content: const Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'This permission is required to show the lock screen overlay over all apps.',
              style: TextStyle(fontWeight: FontWeight.w500),
            ),
            SizedBox(height: 16),
            Text(
              'You will be redirected to Settings. Please find this app and enable "Allow display over other apps".',
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () async {
              Navigator.pop(context);
              final status = await Permission.systemAlertWindow.request();
              if (status.isGranted) {
                // Lock the overlay permission so user cannot disable it
                await _lockOverlayPermission();
              } else {
                _showSnackBar('Please enable "Display over other apps" in Settings');
              }
              await _checkAllPermissions();
            },
            child: const Text('Open Settings'),
          ),
        ],
      ),
    );
  }

  /// Lock overlay permission after it's granted
  Future<void> _lockOverlayPermission() async {
    if (!isDeviceOwner) {
      debugPrint('⚠️ Cannot lock overlay permission - not device owner');
      return;
    }

    try {
      final result = await KioskService.lockOverlayPermission();
      if (result) {
        debugPrint('✅ Overlay permission locked');
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('Overlay permission granted and locked'),
              backgroundColor: Colors.green,
              duration: Duration(seconds: 2),
            ),
          );
        }
      } else {
        debugPrint('❌ Failed to lock overlay permission');
      }
    } catch (e) {
      debugPrint('❌ Error locking overlay permission: $e');
    }
  }

  Future<void> _requestMultiplePermissions(PermissionItem item) async {
    if (item.multiplePermissions == null) return;

    // Check if this is the Location + Background Location combo
    bool isLocationCombo = item.multiplePermissions!.contains(Permission.location) &&
        item.multiplePermissions!.contains(Permission.locationAlways);

    if (isLocationCombo) {
      // Step 1: Request foreground location first
      final locationStatus = await Permission.location.request();

      if (locationStatus.isPermanentlyDenied) {
        final opened = await openAppSettings();
        if (!opened) {
          _showSnackBar('Could not open settings');
        }
        await _checkAllPermissions();
        return;
      }

      if (!locationStatus.isGranted) {
        _showSnackBar('Location permission is required');
        await _checkAllPermissions();
        return;
      }

      // Step 2: Request background location
      final bgStatus = await Permission.locationAlways.request();

      if (bgStatus.isPermanentlyDenied) {
        _showSnackBar('Please enable "Allow all the time" in Settings');
        final opened = await openAppSettings();
        if (!opened) {
          _showSnackBar('Could not open settings');
        }
      } else if (!bgStatus.isGranted) {
        _showSnackBar('Background location is required for full functionality');
      }
    } else {
      // For other combined permissions (Media: Photos, Videos, Audio)
      for (var perm in item.multiplePermissions!) {
        final status = await perm.request();

        if (status.isPermanentlyDenied) {
          final opened = await openAppSettings();
          if (!opened) {
            _showSnackBar('Could not open settings');
          }
          await _checkAllPermissions();
          return;
        }
      }
    }

    // Refresh all permissions
    await _checkAllPermissions();
  }

  void _showDeviceAdminDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Row(
          children: [
            Icon(Icons.admin_panel_settings, color: Colors.blue),
            SizedBox(width: 8),
            Text('Device Admin'),
          ],
        ),
        content: const Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Device Admin permission requires setup via ADB command.',
              style: TextStyle(
                  fontWeight: FontWeight.w500),
            ),
            SizedBox(height: 16),
            Text('Run this command on your computer:'),
            SizedBox(height: 8),
            SelectableText(
              'adb shell dpm set-device-owner com.deploy_logics.user_device_locker/.MyDeviceAdminReceiver',
              style: TextStyle(
                fontFamily: 'monospace',
                fontSize: 12,
                color: Colors.blue,
              ),
            ),
            SizedBox(height: 16),
            Text(
              'Note: Make sure no accounts are signed in on the device before running this command.',
              style: TextStyle(
                fontSize: 12,
                color: Colors.orange,
                fontStyle: FontStyle.italic,
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('OK'),
          ),
          TextButton(
            onPressed: () {
              Clipboard.setData(const ClipboardData(
                text: 'adb shell dpm set-device-owner com.deploy_logics.user_device_locker/.MyDeviceAdminReceiver',
              ));
              Navigator.pop(context);
              _showSnackBar('ADB command copied to clipboard');
            },
            child: const Text('Copy Command'),
          ),
        ],
      ),
    );
  }

  void _showAccessibilityDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Row(
          children: [
            Icon(Icons.accessibility_new, color: Colors.blue),
            SizedBox(width: 8),
            Text('Accessibility'),
          ],
        ),
        content: const Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Accessibility service is required to control the device.',
              style: TextStyle(fontWeight: FontWeight.w500),
            ),
            SizedBox(height: 16),
            Text(
              'You will be redirected to Accessibility Settings. Please find this app and enable the accessibility service.',
            ),
            SizedBox(height: 16),
            Text(
              'Note: This permission allows the app to observe your actions and control the device.',
              style: TextStyle(
                fontSize: 12,
                color: Colors.orange,
                fontStyle: FontStyle.italic,
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () async {
              Navigator.pop(context);
              try {
                await _channel.invokeMethod('openAccessibilitySettings');
              } catch (e) {
                _showSnackBar('Could not open Accessibility Settings');
              }
            },
            child: const Text('Open Settings'),
          ),
        ],
      ),
    );
  }

  void _showSnackBar(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message)),
    );
  }

  Widget _buildStatusIcon(PermissionStatus status) {
    switch (status) {
      case PermissionStatus.granted:
      case PermissionStatus.limited:
        return const Icon(Icons.check_circle, color: Colors.green, size: 20);
      case PermissionStatus.denied:
        return const Icon(Icons.cancel, color: Colors.orange, size: 20);
      case PermissionStatus.permanentlyDenied:
        return const Icon(Icons.block, color: Colors.red, size: 20);
      case PermissionStatus.restricted:
        return const Icon(Icons.lock, color: Colors.grey, size: 20);
      default:
        return const Icon(Icons.help_outline, color: Colors.grey, size: 20);
    }
  }

  String _getStatusText(PermissionStatus status) {
    switch (status) {
      case PermissionStatus.granted:
        return 'Granted';
      case PermissionStatus.limited:
        return 'Limited';
      case PermissionStatus.denied:
        return 'Denied';
      case PermissionStatus.permanentlyDenied:
        return 'Blocked';
      case PermissionStatus.restricted:
        return 'Restricted';
      default:
        return 'Unknown';
    }
  }

  Color _getStatusColor(PermissionStatus status) {
    switch (status) {
      case PermissionStatus.granted:
      case PermissionStatus.limited:
        return Colors.green;
      case PermissionStatus.denied:
        return Colors.orange;
      case PermissionStatus.permanentlyDenied:
        return Colors.red;
      default:
        return Colors.grey;
    }
  }

  Future<void> _requestAllPermissions() async {
    for (var item in permissions) {
      // Skip Device Admin - requires ADB setup
      if (item.isDeviceAdmin) continue;

      if (!item.status.isGranted) {
        // Request permission and wait for it to be granted
        await _requestPermissionAndWait(item);
        
        // Check if permission was granted
        await _checkAllPermissions();
        
        // Find the updated item status
        final updatedItem = permissions.firstWhere(
          (p) => p.name == item.name,
          orElse: () => item,
        );
        
        // If permission is still not granted, stop the sequence
        if (!updatedItem.status.isGranted) {
          _showSnackBar('Please grant ${item.name} permission to continue');
          return;
        }
      }
    }
  }

  Future<void> _requestPermissionAndWait(PermissionItem item) async {
    // Handle Device Admin separately
    if (item.isDeviceAdmin) {
      await _showDeviceAdminDialogAndWait();
      return;
    }

    // Handle Accessibility separately
    if (item.isAccessibility) {
      await _showAccessibilityDialogAndWait();
      return;
    }

    // Handle multiple permissions (Location + Background, Media, etc.)
    if (item.multiplePermissions != null) {
      await _requestMultiplePermissions(item);
      return;
    }

    if (item.permission == null) return;

    // Special handling for System Alert Window (Overlay) permission
    if (item.permission == Permission.systemAlertWindow) {
      final status = await Permission.systemAlertWindow.status;
      if (!status.isGranted) {
        await _showOverlayPermissionDialogAndWait();
        return;
      }
    }

    PermissionStatus status = await item.permission!.request();

    // If permanently denied, open app settings
    if (status.isPermanentlyDenied) {
      final opened = await openAppSettings();
      if (!opened) {
        _showSnackBar('Could not open settings');
      }
      return;
    }

    setState(() {
      item.status = status;
    });
  }

  Future<void> _showAccessibilityDialogAndWait() async {
    final completer = Completer<void>();
    
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => AlertDialog(
        title: const Row(
          children: [
            Icon(Icons.accessibility_new, color: Colors.blue),
            SizedBox(width: 8),
            Text('Accessibility'),
          ],
        ),
        content: const Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Accessibility service is required to control the device.',
              style: TextStyle(fontWeight: FontWeight.w500),
            ),
            SizedBox(height: 16),
            Text(
              'You will be redirected to Accessibility Settings. Please find this app and enable the accessibility service.',
            ),
            SizedBox(height: 16),
            Text(
              'Note: This permission allows the app to observe your actions and control the device.',
              style: TextStyle(
                fontSize: 12,
                color: Colors.orange,
                fontStyle: FontStyle.italic,
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () {
              Navigator.pop(context);
              completer.complete();
            },
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () async {
              Navigator.pop(context);
              try {
                await _channel.invokeMethod('openAccessibilitySettings');
              } catch (e) {
                _showSnackBar('Could not open Accessibility Settings');
              }
              completer.complete();
            },
            child: const Text('Open Settings'),
          ),
        ],
      ),
    );
    
    return completer.future;
  }

  Future<void> _showDeviceAdminDialogAndWait() async {
    final completer = Completer<void>();
    
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => AlertDialog(
        title: const Row(
          children: [
            Icon(Icons.admin_panel_settings, color: Colors.blue),
            SizedBox(width: 8),
            Text('Device Admin'),
          ],
        ),
        content: const Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'Device Admin permission requires setup via ADB command.',
              style: TextStyle(fontWeight: FontWeight.w500),
            ),
            SizedBox(height: 16),
            Text('Run this command on your computer:'),
            SizedBox(height: 8),
            SelectableText(
              'adb shell dpm set-device-owner com.deploy_logics.user_device_locker/.MyDeviceAdminReceiver',
              style: TextStyle(
                fontFamily: 'monospace',
                fontSize: 12,
                color: Colors.blue,
              ),
            ),
            SizedBox(height: 16),
            Text(
              'Note: Make sure no accounts are signed in on the device before running this command.',
              style: TextStyle(
                fontSize: 12,
                color: Colors.orange,
                fontStyle: FontStyle.italic,
              ),
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () {
              Navigator.pop(context);
              completer.complete();
            },
            child: const Text('OK'),
          ),
          TextButton(
            onPressed: () {
              Clipboard.setData(const ClipboardData(
                text: 'adb shell dpm set-device-owner com.deploy_logics.user_device_locker/.MyDeviceAdminReceiver',
              ));
              Navigator.pop(context);
              _showSnackBar('ADB command copied to clipboard');
              completer.complete();
            },
            child: const Text('Copy Command'),
          ),
        ],
      ),
    );
    
    return completer.future;
  }

  Future<void> _showOverlayPermissionDialogAndWait() async {
    final completer = Completer<void>();
    
    showDialog(
      context: context,
      barrierDismissible: false,
      builder: (context) => AlertDialog(
        title: const Row(
          children: [
            Icon(Icons.layers, color: Colors.blue),
            SizedBox(width: 8),
            Text('Display Over Apps'),
          ],
        ),
        content: const Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(
              'This permission is required to show the lock screen overlay over all apps.',
              style: TextStyle(fontWeight: FontWeight.w500),
            ),
            SizedBox(height: 16),
            Text(
              'You will be redirected to Settings. Please find this app and enable "Allow display over other apps".',
            ),
          ],
        ),
        actions: [
          TextButton(
            onPressed: () {
              Navigator.pop(context);
              completer.complete();
            },
            child: const Text('Cancel'),
          ),
          TextButton(
            onPressed: () async {
              Navigator.pop(context);
              final status = await Permission.systemAlertWindow.request();
              if (status.isGranted) {
                // Lock the overlay permission so user cannot disable it
                await _lockOverlayPermission();
              } else {
                _showSnackBar('Please enable "Display over other apps" in Settings');
              }
              completer.complete();
            },
            child: const Text('Open Settings'),
          ),
        ],
      ),
    );
    
    return completer.future;
  }

  int get _grantedCount => permissions.where((p) => p.status.isGranted).length;

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('App Permissions'),
        centerTitle: true,
        actions: [
          IconButton(
            icon: const Icon(Icons.refresh),
            onPressed: _checkAllPermissions,
            tooltip: 'Refresh',
          ),
        ],
      ),
      body: SafeArea(
        child: isLoading
            ? const Center(child: CircularProgressIndicator())
            : Column(
                children: [
                  // Header with progress
                  Container(
                    padding: const EdgeInsets.symmetric(vertical: 06, horizontal: 12),
                    color: Theme.of(context).primaryColor.withValues(alpha: 0.1),
                    child: Row(
                      children: [
                        Expanded(
                          child: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                'Permission Status',
                                style: Theme.of(context).textTheme.titleMedium!.copyWith(fontSize: 14),
                              ),
                              const SizedBox(height: 2),
                              Text(
                                '$_grantedCount of ${permissions.length} permissions granted',
                                style: Theme.of(context).textTheme.bodySmall!.copyWith(fontSize: 10),
                              ),
                            ],
                          ),
                        ),
                        ElevatedButton.icon(
                          onPressed: _requestAllPermissions,
                          icon: const Icon(Icons.security, size: 18),
                          label: const Text('Grant All'),
                          style: ElevatedButton.styleFrom(
                            backgroundColor: Colors.green,
                            foregroundColor: Colors.white,
                          ),
                        ),
                      ],
                    ),
                  ),

                  // Permissions Locked Status Card
                  if (_permissionsLocked)
                    Container(
                      padding: const EdgeInsets.symmetric(vertical: 8, horizontal: 12),
                      margin: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                      decoration: BoxDecoration(
                        color: Colors.green.shade50,
                        borderRadius: BorderRadius.circular(8),
                        border: Border.all(color: Colors.green.shade200),
                      ),
                      child: Row(
                        children: [
                          Icon(Icons.lock, color: Colors.green.shade700, size: 20),
                          const SizedBox(width: 8),
                          Expanded(
                            child: Text(
                              'Permissions are locked and cannot be changed by user',
                              style: TextStyle(
                                color: Colors.green.shade700,
                                fontSize: 12,
                                fontWeight: FontWeight.w500,
                              ),
                            ),
                          ),
                          if (isDeviceOwner)
                            TextButton(
                              onPressed: _unlockPermissions,
                              child: const Text('Unlock', style: TextStyle(fontSize: 12)),
                            ),
                        ],
                      ),
                    ),

                  // Permission List
                  Expanded(
                    child: ListView.separated(
                      padding: const EdgeInsets.all(8),
                      itemCount: permissions.length,
                      separatorBuilder: (context, index) => const Divider(height: 1),
                      itemBuilder: (context, index) {
                        final item = permissions[index];
                        return ListTile(
                          leading: Container(
                            padding: const EdgeInsets.all(8),
                            decoration: BoxDecoration(
                              color: Theme.of(context).primaryColor.withValues(alpha: 0.1),
                              borderRadius: BorderRadius.circular(8),
                            ),
                            child: Icon(item.icon, color: Theme.of(context).primaryColor),
                          ),
                          title: Text(
                            item.name,
                            style: const TextStyle(
                              fontSize: 12,
                                fontWeight: FontWeight.w600),
                          ),
                          subtitle: Column(
                            crossAxisAlignment: CrossAxisAlignment.start,
                            children: [
                              Text(
                                item.description,
                                style: Theme.of(context).textTheme.bodySmall!.copyWith(fontSize: 08),
                              ),
                              const SizedBox(height: 4),
                              Row(
                                children: [
                                  Container(
                                    padding: const EdgeInsets.symmetric(
                                      horizontal: 8,
                                      vertical: 2,
                                    ),
                                    decoration: BoxDecoration(
                                      color: _getStatusColor(item.status).withValues(alpha: 0.1),
                                      borderRadius: BorderRadius.circular(12),
                                    ),
                                    child: Text(
                                      _getStatusText(item.status),
                                      style: TextStyle(
                                        color: _getStatusColor(item.status),
                                        fontSize: 08,
                                        fontWeight: FontWeight.w500,
                                      ),
                                    ),
                                  ),
                                ],
                              ),
                            ],
                          ),
                          trailing: Row(
                            mainAxisSize: MainAxisSize.min,
                            children: [
                              _buildStatusIcon(item.status),
                              if (!item.status.isGranted) ...[
                                const SizedBox(width: 8),
                                IconButton(
                                  icon: const Icon(Icons.arrow_forward_ios, size: 16),
                                  onPressed: () => _requestPermission(item),
                                  tooltip: 'Grant Permission',
                                ),
                              ],
                            ],
                          ),
                          onTap: item.status.isGranted
                              ? null
                              : () => _requestPermission(item),
                        );
                      },
                    ),
                  ),

                  // Legend
                  Container(
                    padding: const EdgeInsets.all(12),
                    color: Colors.grey.shade100,
                    child: Row(
                      mainAxisAlignment: MainAxisAlignment.spaceEvenly,
                      children: [
                        _buildLegendItem(Icons.check_circle, Colors.green, 'Granted'),
                        _buildLegendItem(Icons.cancel, Colors.orange, 'Denied'),
                        _buildLegendItem(Icons.block, Colors.red, 'Blocked'),
                      ],
                    ),
                  ),

                  // Continue Button
                  Container(
                    padding: const EdgeInsets.all(16),
                    child: SizedBox(
                      width: double.infinity,
                      child: ElevatedButton(
                        onPressed: () async {
                          // Check if device is registered
                          final isRegistered = await RegisterDeviceProvider.isDeviceRegistered();

                          if (!mounted) return;

                          if (isRegistered) {
                            // Device is registered, go to Welcome screen
                            Navigator.pushReplacement(
                              context,
                              MaterialPageRoute(
                                builder: (context) => const WelcomeScreen(),
                              ),
                            );
                          } else {
                            // Device not registered, go to Register Device screen
                            Navigator.pushReplacement(
                              context,
                              MaterialPageRoute(
                                builder: (context) => const RegisterDeviceScreen(),
                              ),
                            );
                          }
                        },
                        style: ElevatedButton.styleFrom(
                          backgroundColor: Theme.of(context).primaryColor,
                          foregroundColor: Colors.white,
                          padding: const EdgeInsets.symmetric(vertical: 16),
                          shape: RoundedRectangleBorder(
                            borderRadius: BorderRadius.circular(12),
                          ),
                        ),
                        child: const Text(
                          'Continue',
                          style: TextStyle(
                            fontSize: 16,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                      ),
                    ),
                  ),
                ],
              ),
      ),
    );
  }

  Widget _buildLegendItem(IconData icon, Color color, String label) {
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(icon, color: color, size: 14),
        const SizedBox(width: 4),
        Text(label, style: const TextStyle(fontSize: 10)),
      ],
    );
  }
}
