import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:geolocator/geolocator.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../providers/register_device_provider.dart';
import '../services/kiosk_service.dart';

class TestDeviceScreen extends StatefulWidget {
  const TestDeviceScreen({super.key});

  @override
  State<TestDeviceScreen> createState() => _TestDeviceScreenState();
}

class _TestDeviceScreenState extends State<TestDeviceScreen> with WidgetsBindingObserver {
  bool _isLoading = false;
  String _statusMessage = '';
  String _currentLocation = '';
  String _currentLockCode = '';
  String _previousLockCode = ''; // Track previous lock code to detect changes

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _initUnlockCode();
  }

  Future<void> _initUnlockCode() async {
    await _loadCurrentLockCode();
    final unlockCode = await RegisterDeviceProvider.getUnlockCode();
    if (unlockCode == null || unlockCode.isEmpty) {
      setState(() => _statusMessage = 'Fetching unlock code...');
      final provider = RegisterDeviceProvider();
      await provider.getLockCodeApi(null);
      await _loadCurrentLockCode();
      if (mounted) {
        setState(() => _statusMessage = '');
      }
    }
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    super.dispose();
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    // Refresh lock code when app resumes (e.g., after unlocking from LockActivity)
    if (state == AppLifecycleState.resumed) {
      _loadCurrentLockCodeAndCheckForChanges();
    }
  }

  Future<void> _loadCurrentLockCode() async {
    final unlockCode = await RegisterDeviceProvider.getUnlockCode() ?? '';
    setState(() {
      _currentLockCode = unlockCode;
      if (_previousLockCode.isEmpty) {
        _previousLockCode = unlockCode;
      }
    });
  }

  Future<void> _loadCurrentLockCodeAndCheckForChanges() async {
    final unlockCode = await RegisterDeviceProvider.getUnlockCode() ?? '';

    if (_previousLockCode.isNotEmpty &&
        unlockCode.isNotEmpty &&
        unlockCode != _previousLockCode) {
      debugPrint('🔑 Unlock code changed from $_previousLockCode to $unlockCode');
      await _updateUnlockCodeOnServer(unlockCode);
    }

    setState(() {
      _previousLockCode = _currentLockCode;
      _currentLockCode = unlockCode;
    });
  }

  Future<void> _updateUnlockCodeOnServer(String newUnlockCode) async {
    debugPrint('📤 Updating unlock code on server: $newUnlockCode');
    final success = await RegisterDeviceProvider.updateUnlockCodeApi(newUnlockCode);
    if (success) {
      debugPrint('✅ Unlock code updated on server');
    } else {
      debugPrint('⚠️ Unlock code update failed');
    }
  }

  Future<void> _executeAction(String actionName, Future<bool> Function() action) async {
    setState(() {
      _isLoading = true;
      _statusMessage = 'Executing $actionName...';
    });

    try {
      final result = await action();
      setState(() {
        _statusMessage = result
            ? '$actionName: Success ✓'
            : '$actionName: Failed ✗';
      });
    } catch (e) {
      setState(() {
        _statusMessage = '$actionName: Error - $e';
      });
    } finally {
      setState(() {
        _isLoading = false;
      });
    }
  }

Future<void> _lockDevice() async {
    await _executeAction('Lock Device', () async {
      debugPrint('🔒 _lockDevice() called');

      // Get user ID from SharedPreferences
      final userId = await RegisterDeviceProvider.getUserId();
      final userIdString = userId?.toString() ?? '';
      debugPrint('   User ID: $userIdString');

      // Save lock state using SharedPreferences directly
      final prefs = await SharedPreferences.getInstance();
      await prefs.setBool('device_locked', true);
      // Use existing lock_pin if available, otherwise default to '1234'
      final existingPin = await RegisterDeviceProvider.getUnlockCode() ?? '';
      await prefs.setString('lock_pin', existingPin);
      await prefs.setString('lock_user_id', userIdString);
      debugPrint('   Lock state saved to SharedPreferences with PIN: $existingPin');

      // Always use native LockActivity with Lock Task Mode
      // This is the same as what happens when lock notification is received
      try {
        // Try to disable status bar (will work if device owner)
        try {
          await KioskService.setStatusBarDisabled(true);
          debugPrint('   🔒 Status bar disabled');
        } catch (e) {
          debugPrint('   ⚠️ Could not disable status bar: $e');
        }

        // Try to lock settings (will work if device owner)
        try {
          await KioskService.lockSettingsWhenOverlayShown();
          debugPrint('   🔒 Settings locked');
        } catch (e) {
          debugPrint('   ⚠️ Could not lock settings: $e');
        }

        // Start native LockActivity with Lock Task Mode
        final result = await KioskService.startLockActivity();
        debugPrint('   🔒 LockActivity started: $result');

        // Server actual_lock_status=1 when lock UI is shown (same as FCM / dial). Offline-safe.
        try {
          await RegisterDeviceProvider.updateActualDeviceStatus(
            lockCode: existingPin,
            actualLockStatus: 1,
          );
        } catch (e) {
          debugPrint('   ⚠️ updateActualDeviceStatus after test lock: $e');
        }

        return result;
      } catch (e) {
        debugPrint('   ❌ Error starting LockActivity: $e');
        return false;
      }
    });
  }

  Future<void> _enableCamera() async {
    await _executeAction('Enable Camera', () async {
      return await KioskService.enableCamera();
    });
  }

  Future<void> _disableCamera() async {
    await _executeAction('Disable Camera', () async {
      return await KioskService.disableCamera();
    });
  }

  Future<void> _enableLocation() async {
    await _executeAction('Enable Location Config', () async {
      // Allow user to toggle location on/off in system settings
      final result = await KioskService.enableLocationConfig();
      return result;
    });
  }

  Future<void> _disableLocation() async {
    await _executeAction('Disable Location Config', () async {
      // Block user from toggling location in system settings
      final result = await KioskService.disableLocationConfig();
      return result;
    });
  }

  Future<void> _turnOnLocationAndLock() async {
    await _executeAction('Turn On Location (Locked)', () async {
      // Turn ON location AND block the system toggle
      final result = await KioskService.enableLocationAndLock();
      return result;
    });
  }

  Future<void> _turnOffLocationAndLock() async {
    await _executeAction('Turn Off Location (Locked)', () async {
      // Turn OFF location AND block the system toggle
      final result = await KioskService.disableLocationAndLock();
      return result;
    });
  }

  Future<void> _enableLocationButton() async {
    await _executeAction('Enable Location Button', () async {
      // Allow user to toggle location on/off in system settings
      return await KioskService.enableLocationConfig();
    });
  }

  Future<void> _disableLocationButton() async {
    await _executeAction('Disable Location Button', () async {
      // Block user from toggling location in system settings
      return await KioskService.disableLocationConfig();
    });
  }

  Future<void> _getCurrentLocation() async {
    setState(() {
      _isLoading = true;
      _statusMessage = 'Getting current location...';
      _currentLocation = '';
    });

    try {
      // Check if location services are enabled
      bool serviceEnabled = await Geolocator.isLocationServiceEnabled();
      if (!serviceEnabled) {
        // If location is disabled, turn it on instantly via KioskService
        setState(() {
          _statusMessage = 'Enabling location services...';
        });

        // Turn on location via KioskService (this works even if toggle is locked)
        final locationEnabled = await KioskService.turnOnLocation();
        if (!locationEnabled) {
          setState(() {
            _statusMessage = 'Failed to enable location services';
            _isLoading = false;
          });
          return;
        }

        // Wait a moment for location services to activate
        await Future.delayed(const Duration(milliseconds: 1500));

        // Recheck location services
        serviceEnabled = await Geolocator.isLocationServiceEnabled();
        if (!serviceEnabled) {
          setState(() {
            _statusMessage = 'Location services still disabled';
            _isLoading = false;
          });
          return;
        }

        setState(() {
          _statusMessage = 'Location enabled, getting position...';
        });
      }

      // Check location permission
      LocationPermission permission = await Geolocator.checkPermission();
      if (permission == LocationPermission.denied) {
        permission = await Geolocator.requestPermission();
        if (permission == LocationPermission.denied) {
          setState(() {
            _statusMessage = 'Location permission denied';
            _isLoading = false;
          });
          return;
        }
      }

      if (permission == LocationPermission.deniedForever) {
        setState(() {
          _statusMessage = 'Location permission permanently denied';
          _isLoading = false;
        });
        return;
      }

      // Get current position
      Position position = await Geolocator.getCurrentPosition(
        desiredAccuracy: LocationAccuracy.high,
      );

      setState(() {
        _currentLocation = 'Lat: ${position.latitude.toStringAsFixed(6)}\n'
            'Lng: ${position.longitude.toStringAsFixed(6)}\n'
            'Accuracy: ${position.accuracy.toStringAsFixed(2)}m';
        _statusMessage = 'Location retrieved successfully ✓';
      });
    } catch (e) {
      setState(() {
        _statusMessage = 'Error getting location: $e';
      });
    } finally {
      setState(() {
        _isLoading = false;
      });
    }
  }

  Future<void> _enableFactoryReset() async {
    await _executeAction('Enable Factory Reset', () async {
      return await KioskService.enableFactoryReset();
    });
  }

  Future<void> _disableFactoryReset() async {
    await _executeAction('Disable Factory Reset', () async {
      return await KioskService.disableFactoryReset();
    });
  }

  // Permission Locking Methods
  Future<void> _lockPermissionsAfterGrant() async {
    await _executeAction('Lock Permissions After Grant', () async {
      return await KioskService.lockPermissionsAfterGrant();
    });
  }

  Future<void> _lockAppPermissions() async {
    await _executeAction('Lock App Permissions', () async {
      return await KioskService.lockAllAppPermissions();
    });
  }

  Future<void> _unlockPermissions() async {
    await _executeAction('Unlock Permissions', () async {
      return await KioskService.unlockPermissions();
    });
  }

  Future<void> _disableConfigApps() async {
    await _executeAction('Disable App Settings', () async {
      return await KioskService.disableConfigApps();
    });
  }

  Future<void> _enableConfigApps() async {
    await _executeAction('Enable App Settings', () async {
      return await KioskService.enableConfigApps();
    });
  }

  Future<void> _blockSelfUninstall() async {
    await _executeAction('Block Self Uninstall', () async {
      return await KioskService.blockSelfUninstall();
    });
  }

  Future<void> _allowSelfUninstall() async {
    await _executeAction('Allow Self Uninstall', () async {
      return await KioskService.allowSelfUninstall();
    });
  }

  Future<void> _hideApp() async {
    await _executeAction('Hide App', () async {
      return await KioskService.hideSelf();
    });
  }

  Future<void> _showApp() async {
    await _executeAction('Show App', () async {
      return await KioskService.showSelf();
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Test Device Controls'),
        backgroundColor: Theme.of(context).primaryColor,
        foregroundColor: Colors.white,
      ),
      body: SingleChildScrollView(
        padding: const EdgeInsets.all(16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            // Status Card
            Card(
              color: _statusMessage.contains('Success') || _statusMessage.contains('✓')
                  ? Colors.green.shade50
                  : _statusMessage.contains('Failed') || _statusMessage.contains('Error')
                      ? Colors.red.shade50
                      : Colors.blue.shade50,
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Column(
                  children: [
                    if (_isLoading)
                      const SizedBox(
                        height: 20,
                        width: 20,
                        child: CircularProgressIndicator(strokeWidth: 2),
                      )
                    else
                      Icon(
                        _statusMessage.contains('Success') || _statusMessage.contains('✓')
                            ? Icons.check_circle
                            : _statusMessage.contains('Failed') || _statusMessage.contains('Error')
                                ? Icons.error
                                : Icons.info,
                        color: _statusMessage.contains('Success') || _statusMessage.contains('✓')
                            ? Colors.green
                            : _statusMessage.contains('Failed') || _statusMessage.contains('Error')
                                ? Colors.red
                                : Colors.blue,
                      ),
                    const SizedBox(height: 8),
                    Text(
                      _statusMessage.isEmpty ? 'Ready to test' : _statusMessage,
                      textAlign: TextAlign.center,
                      style: const TextStyle(fontSize: 14),
                    ),
                  ],
                ),
              ),
            ),

            const SizedBox(height: 24),

            // Lock Device Section
            _buildSectionTitle('Device Lock'),

            // Current Lock Code Card
            Card(
              color: Colors.amber.shade50,
              child: Padding(
                padding: const EdgeInsets.all(16),
                child: Row(
                  children: [
                    const Icon(Icons.vpn_key, color: Colors.amber, size: 24),
                    const SizedBox(width: 12),
                    Column(
                      crossAxisAlignment: CrossAxisAlignment.start,
                      children: [
                        const Text(
                          'Current Unlock Code',
                          style: TextStyle(
                            fontSize: 12,
                            color: Colors.black54,
                          ),
                        ),
                        Text(
                          _currentLockCode.isEmpty ? 'Loading...' : _currentLockCode,
                          style: const TextStyle(
                            fontSize: 24,
                            fontWeight: FontWeight.bold,
                            fontFamily: 'monospace',
                            letterSpacing: 4,
                            color: Colors.black87,
                          ),
                        ),
                      ],
                    ),
                    const Spacer(),
                    IconButton(
                      icon: const Icon(Icons.refresh, color: Colors.amber),
                      onPressed: _loadCurrentLockCode,
                      tooltip: 'Refresh lock code',
                    ),
                  ],
                ),
              ),
            ),
            const SizedBox(height: 12),
            _buildActionButton(
              icon: Icons.lock,
              label: 'Lock Device',
              color: Colors.red,
              onPressed: _isLoading ? null : _lockDevice,
            ),

            const SizedBox(height: 24),

            // Camera Section
            _buildSectionTitle('Camera Control'),
            Row(
              children: [
                Expanded(
                  child: _buildActionButton(
                    icon: Icons.camera_alt,
                    label: 'Enable Camera',
                    color: Colors.green,
                    onPressed: _isLoading ? null : _enableCamera,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: _buildActionButton(
                    icon: Icons.no_photography,
                    label: 'Disable Camera',
                    color: Colors.orange,
                    onPressed: _isLoading ? null : _disableCamera,
                  ),
                ),
              ],
            ),

            const SizedBox(height: 24),

            // Location Section
            _buildSectionTitle('Location Control'),
            Row(
              children: [
                Expanded(
                  child: _buildActionButton(
                    icon: Icons.location_on,
                    label: 'Enable Location',
                    color: Colors.green,
                    onPressed: _isLoading ? null : _enableLocation,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: _buildActionButton(
                    icon: Icons.location_off,
                    label: 'Disable Location',
                    color: Colors.orange,
                    onPressed: _isLoading ? null : _disableLocation,
                  ),
                ),
              ],
            ),

            const SizedBox(height: 12),

            Row(
              children: [
                Expanded(
                  child: _buildActionButton(
                    icon: Icons.location_on,
                    label: 'Turn On (Lock)',
                    color: Colors.blue,
                    onPressed: _isLoading ? null : _turnOnLocationAndLock,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: _buildActionButton(
                    icon: Icons.location_off,
                    label: 'Turn Off (Lock)',
                    color: Colors.purple,
                    onPressed: _isLoading ? null : _turnOffLocationAndLock,
                  ),
                ),
              ],
            ),

            const SizedBox(height: 12),

            // Location Button Control (System Toggle)
            _buildSectionTitle('Location Button (System Toggle)'),
            Row(
              children: [
                Expanded(
                  child: _buildActionButton(
                    icon: Icons.toggle_on,
                    label: 'Enable Button',
                    color: Colors.green,
                    onPressed: _isLoading ? null : _enableLocationButton,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: _buildActionButton(
                    icon: Icons.toggle_off,
                    label: 'Disable Button',
                    color: Colors.red,
                    onPressed: _isLoading ? null : _disableLocationButton,
                  ),
                ),
              ],
            ),

            const SizedBox(height: 24),

            // Get Current Location Section
            _buildSectionTitle('Current Location'),
            _buildActionButton(
              icon: Icons.my_location,
              label: 'Get Current Location',
              color: Colors.teal,
              onPressed: _isLoading ? null : _getCurrentLocation,
            ),

            if (_currentLocation.isNotEmpty) ...[
              const SizedBox(height: 12),
              Card(
                color: Colors.teal.shade50,
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.start,
                    children: [
                      const Row(
                        children: [
                          Icon(Icons.location_pin, color: Colors.teal, size: 20),
                          SizedBox(width: 8),
                          Text(
                            'Current Position',
                            style: TextStyle(
                              fontWeight: FontWeight.bold,
                              color: Colors.teal,
                            ),
                          ),
                        ],
                      ),
                      const SizedBox(height: 8),
                      Text(
                        _currentLocation,
                        style: const TextStyle(
                          fontFamily: 'monospace',
                          fontSize: 13,
                        ),
                      ),
                    ],
                  ),
                ),
              ),
            ],

            const SizedBox(height: 24),

            // Factory Reset Section
            _buildSectionTitle('Factory Reset Control'),
            Row(
              children: [
                Expanded(
                  child: _buildActionButton(
                    icon: Icons.restore,
                    label: 'Enable Factory Reset',
                    color: Colors.green,
                    onPressed: _isLoading ? null : _enableFactoryReset,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: _buildActionButton(
                    icon: Icons.block,
                    label: 'Disable Factory Reset',
                    color: Colors.red,
                    onPressed: _isLoading ? null : _disableFactoryReset,
                  ),
                ),
              ],
            ),

            const SizedBox(height: 24),

            // Permission Locking Section
            _buildSectionTitle('Permission Locking'),
            _buildActionButton(
              icon: Icons.lock,
              label: 'Lock All Permissions After Grant',
              color: Colors.deepPurple,
              onPressed: _isLoading ? null : _lockPermissionsAfterGrant,
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: _buildActionButton(
                    icon: Icons.lock_outline,
                    label: 'Lock App Permissions',
                    color: Colors.purple,
                    onPressed: _isLoading ? null : _lockAppPermissions,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: _buildActionButton(
                    icon: Icons.lock_open,
                    label: 'Unlock Permissions',
                    color: Colors.teal,
                    onPressed: _isLoading ? null : _unlockPermissions,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: _buildActionButton(
                    icon: Icons.app_settings_alt,
                    label: 'Disable App Settings',
                    color: Colors.red,
                    onPressed: _isLoading ? null : _disableConfigApps,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: _buildActionButton(
                    icon: Icons.settings_applications,
                    label: 'Enable App Settings',
                    color: Colors.green,
                    onPressed: _isLoading ? null : _enableConfigApps,
                  ),
                ),
              ],
            ),
            const SizedBox(height: 12),
            Row(
              children: [
                Expanded(
                  child: _buildActionButton(
                    icon: Icons.delete_forever,
                    label: 'Block Uninstall',
                    color: Colors.red,
                    onPressed: _isLoading ? null : _blockSelfUninstall,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: _buildActionButton(
                    icon: Icons.delete_outline,
                    label: 'Allow Uninstall',
                    color: Colors.green,
                    onPressed: _isLoading ? null : _allowSelfUninstall,
                  ),
                ),
              ],
            ),

            const SizedBox(height: 24),

            // Hide/Show App Section
            _buildSectionTitle('App Visibility'),
            Row(
              children: [
                Expanded(
                  child: _buildActionButton(
                    icon: Icons.visibility_off,
                    label: 'Hide App',
                    color: Colors.red,
                    onPressed: _isLoading ? null : _hideApp,
                  ),
                ),
                const SizedBox(width: 12),
                Expanded(
                  child: _buildActionButton(
                    icon: Icons.visibility,
                    label: 'Show App',
                    color: Colors.green,
                    onPressed: _isLoading ? null : _showApp,
                  ),
                ),
              ],
            ),

            const SizedBox(height: 32),
          ],
        ),
      ),
    );
  }

  Widget _buildSectionTitle(String title) {
    return Padding(
      padding: const EdgeInsets.only(bottom: 12),
      child: Text(
        title,
        style: const TextStyle(
          fontSize: 16,
          fontWeight: FontWeight.bold,
          color: Colors.black87,
        ),
      ),
    );
  }

  Widget _buildActionButton({
    required IconData icon,
    required String label,
    required Color color,
    VoidCallback? onPressed,
  }) {
    return ElevatedButton.icon(
      onPressed: onPressed,
      icon: Icon(icon, size: 20),
      label: Text(
        label,
        style: const TextStyle(fontSize: 12),
      ),
      style: ElevatedButton.styleFrom(
        backgroundColor: color,
        foregroundColor: Colors.white,
        padding: const EdgeInsets.symmetric(vertical: 14, horizontal: 12),
        shape: RoundedRectangleBorder(
          borderRadius: BorderRadius.circular(10),
        ),
      ),
    );
  }
}


