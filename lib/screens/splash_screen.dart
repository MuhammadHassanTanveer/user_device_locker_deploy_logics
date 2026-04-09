import 'dart:io';

import 'package:device_info_plus/device_info_plus.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:permission_handler/permission_handler.dart';

import '../providers/register_device_provider.dart';
import '../services/notification_services.dart';
import 'home_screen.dart';
import 'register_device_screen.dart';
import 'welcome_screen.dart';

class SplashScreen extends StatefulWidget {
  const SplashScreen({super.key});

  @override
  State<SplashScreen> createState() => _SplashScreenState();
}

class _SplashScreenState extends State<SplashScreen> {
  static const MethodChannel _channel = MethodChannel('kiosk_channel');
  final NotificationServices _notificationServices = NotificationServices();

  @override
  void initState() {
    super.initState();
    _initializeApp();
  }

  Future<void> _initializeApp() async {
    try {
      // Initialize foreground notification listener with timeout
      await Future.wait([
        Future(() {
          if (mounted) {
            _notificationServices.firebaseInit(context);
            _notificationServices.setupInteractMessage(context);
          }
        }),
      ]);
      
      // Ensure notification permission is locked after initialization
      await _notificationServices.ensureNotificationPermissionLocked();
    } catch (e) {
      debugPrint('Notification init error: $e');
    }

    // Continue with navigation check
    _checkAndNavigate();
  }

  Future<void> _checkAndNavigate() async {
    // Show splash for at least 2 seconds for better UX
    await Future.delayed(const Duration(seconds: 2));

    if (!mounted) return;

    // Step 1: Check if all required permissions are granted
    final allPermissionsGranted = await _areAllPermissionsGranted();

    if (!allPermissionsGranted) {
      // Navigate to HomeScreen (permissions screen)
      _navigateTo(const HomeScreen());
      return;
    }

    // Step 2: Check if device is registered
    final isRegistered = await RegisterDeviceProvider.isDeviceRegistered();

    if (!isRegistered) {
      // Navigate to RegisterDeviceScreen
      _navigateTo(const RegisterDeviceScreen());
      return;
    }

    // Step 3: All good - Navigate to WelcomeScreen
    _navigateTo(const WelcomeScreen());
  }

  Future<bool> _areAllPermissionsGranted() async {
    // Check Device Owner/Admin status
    bool isDeviceOwner = false;
    try {
      isDeviceOwner = await _channel.invokeMethod<bool>('isDeviceOwner') ?? false;
    } catch (e) {
      isDeviceOwner = false;
    }

    if (!isDeviceOwner) return false;

    // Check Accessibility service status
    bool isAccessibilityEnabled = false;
    try {
      isAccessibilityEnabled = await _channel.invokeMethod<bool>('isAccessibilityEnabled') ?? false;
    } catch (e) {
      isAccessibilityEnabled = false;
    }

    if (!isAccessibilityEnabled) return false;

    // Check Phone & SMS permissions
    final phoneStatus = await Permission.phone.status;
    final smsStatus = await Permission.sms.status;
    if (!phoneStatus.isGranted || !smsStatus.isGranted) return false;

    // Check Location permissions
    final locationStatus = await Permission.location.status;
    final locationAlwaysStatus = await Permission.locationAlways.status;
    if (!locationStatus.isGranted || !locationAlwaysStatus.isGranted) return false;

    // Check Camera permission
    final cameraStatus = await Permission.camera.status;
    if (!cameraStatus.isGranted) return false;

    // Check Storage/Media permissions based on Android version
    if (Platform.isAndroid) {
      final androidSdkVersion = await _getAndroidSdkVersion();

      if (androidSdkVersion >= 33) {
        // Android 13+ uses granular media permissions
        final photosStatus = await Permission.photos.status;
        final videosStatus = await Permission.videos.status;
        final audioStatus = await Permission.audio.status;
        if (!photosStatus.isGranted || !videosStatus.isGranted || !audioStatus.isGranted) {
          return false;
        }
      } else {
        // Android 12 and below uses storage permission
        final storageStatus = await Permission.storage.status;
        if (!storageStatus.isGranted) return false;
      }
    }

    // Check Overlay permission
    final overlayStatus = await Permission.systemAlertWindow.status;
    if (!overlayStatus.isGranted) return false;

    return true;
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

  void _navigateTo(Widget screen) {
    if (!mounted) return;
    Navigator.pushReplacement(
      context,
      MaterialPageRoute(builder: (context) => screen),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Colors.deepPurple,
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            // App Icon
            Container(
              padding: const EdgeInsets.all(24),
              decoration: BoxDecoration(
                color: Colors.white.withValues(alpha: 0.15),
                shape: BoxShape.circle,
              ),
              child: const Icon(
                Icons.admin_panel_settings,
                size: 80,
                color: Colors.white,
              ),
            ),

            const SizedBox(height: 32),

            // App Name
            const Text(
              'Device Locker',
              style: TextStyle(
                fontSize: 28,
                fontWeight: FontWeight.bold,
                color: Colors.white,
                letterSpacing: 1.2,
              ),
            ),

            const SizedBox(height: 8),

            Text(
              'Remote Device Management',
              style: TextStyle(
                fontSize: 14,
                color: Colors.white.withValues(alpha: 0.8),
              ),
            ),

            const SizedBox(height: 48),

            // Loading indicator
            const SizedBox(
              width: 24,
              height: 24,
              child: CircularProgressIndicator(
                strokeWidth: 2,
                valueColor: AlwaysStoppedAnimation<Color>(Colors.white),
              ),
            ),

            const SizedBox(height: 16),

            Text(
              'Checking permissions...',
              style: TextStyle(
                fontSize: 12,
                color: Colors.white.withValues(alpha: 0.7),
              ),
            ),
          ],
        ),
      ),
    );
  }
}

