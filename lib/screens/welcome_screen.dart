import 'package:flutter/material.dart';
import '../providers/register_device_provider.dart';
import '../services/kiosk_service.dart';
import 'test_device_screen.dart';

class WelcomeScreen extends StatefulWidget {
  const WelcomeScreen({super.key});

  @override
  State<WelcomeScreen> createState() => _WelcomeScreenState();
}

class _WelcomeScreenState extends State<WelcomeScreen> {
  int? _userId;
  bool _isDeviceOwner = false;
  bool _isTokenActive = false;
  bool _isSettingUpToken = false;
  final RegisterDeviceProvider _provider = RegisterDeviceProvider();
  bool _hasCalledLockCodeApi = false;

  @override
  void initState() {
    super.initState();
    _loadDeviceInfo();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    // Call _fetchLockCode only once when dependencies are ready
    if (!_hasCalledLockCodeApi) {
      _hasCalledLockCodeApi = true;
      _fetchLockCode();
    }
  }

  Future<void> _loadDeviceInfo() async {
    final userId = await RegisterDeviceProvider.getUserId();
    final isDeviceOwner = await KioskService.isDeviceOwner();
    
    // Check if password token is active
    bool tokenActive = false;
    if (isDeviceOwner) {
      tokenActive = await KioskService.isResetPasswordTokenActive();
    }

    if (mounted) {
      setState(() {
        _userId = userId;
        _isDeviceOwner = isDeviceOwner;
        _isTokenActive = tokenActive;
      });
    }
  }

  /// Setup password reset token for remote password management
  Future<void> _setupPasswordToken() async {
    if (!_isDeviceOwner) {
      ScaffoldMessenger.of(context).showSnackBar(
        const SnackBar(
          content: Text('Device Owner permission required'),
          backgroundColor: Colors.red,
        ),
      );
      return;
    }

    setState(() => _isSettingUpToken = true);

    try {
      final result = await KioskService.setupPasswordResetTokenWithStatus();
      final success = result['success'] == true;
      final tokenActive = result['tokenActive'] == true;
      final needsUnlock = result['needsUserUnlock'] == true;
      final message = result['message'] ?? '';

      if (mounted) {
        setState(() {
          _isTokenActive = tokenActive;
          _isSettingUpToken = false;
        });

        if (tokenActive) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text('✅ Password token active! Remote password management ready.'),
              backgroundColor: Colors.green,
              duration: Duration(seconds: 3),
            ),
          );
        } else if (needsUnlock) {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: const Text('⚠️ Token set! Lock and unlock your device once to activate.'),
              backgroundColor: Colors.orange,
              duration: const Duration(seconds: 5),
              action: SnackBarAction(
                label: 'OK',
                textColor: Colors.white,
                onPressed: () {},
              ),
            ),
          );
        } else {
          ScaffoldMessenger.of(context).showSnackBar(
            SnackBar(
              content: Text(message.isNotEmpty ? message : 'Token setup completed'),
              backgroundColor: success ? Colors.green : Colors.red,
            ),
          );
        }
      }
    } catch (e) {
      if (mounted) {
        setState(() => _isSettingUpToken = false);
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Error: $e'),
            backgroundColor: Colors.red,
          ),
        );
      }
    }
  }

  Future<void> _fetchLockCode() async {
    try {
      debugPrint('🔑🔑🔑 _fetchLockCode() CALLED 🔑🔑🔑');

      // Check if IMEI is available
      final imei1 = await RegisterDeviceProvider.getImei1();
      debugPrint('🔑 IMEI1 from SharedPreferences: "$imei1"');

      if (imei1 == null || imei1.isEmpty) {
        debugPrint('⚠️ IMEI1 is empty or null - API call will be skipped');
        debugPrint('⚠️ Make sure device is registered before opening WelcomeScreen');
        return;
      }

      // Ensure widget is still mounted before using context
      if (!mounted) {
        debugPrint('⚠️ Widget not mounted - skipping API call');
        return;
      }

      debugPrint('🔑 Calling getLockCodeApi...');
      // Call getLockCodeApi to fetch and store lock code info
      final result = await _provider.getLockCodeApi(context);
      debugPrint('🔑 getLockCodeApi result: $result');
    } catch (e, stackTrace) {
      debugPrint('❌ Error fetching lock code: $e');
      debugPrint('❌ Stack trace: $stackTrace');
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(24.0),
            child: Column(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                // Success Icon
                Container(
                  padding: const EdgeInsets.all(24),
                  decoration: BoxDecoration(
                    color: Colors.green.withValues(alpha: 0.1),
                    shape: BoxShape.circle,
                  ),
                  child: const Icon(
                    Icons.check_circle,
                    size: 80,
                    color: Colors.green,
                  ),
                ),

                const SizedBox(height: 32),

                // Welcome Text
                Text(
                  'Device Registered!',
                  style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                    fontWeight: FontWeight.bold,
                    color: Colors.green,
                  ),
                ),

                const SizedBox(height: 12),

                Text(
                  'Your device has been successfully registered and is now ready for remote management.',
                  textAlign: TextAlign.center,
                  style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                    color: Colors.grey[600],
                  ),
                ),

                const SizedBox(height: 32),

                // Device Info Card
                Container(
                  padding: const EdgeInsets.all(16),
                  decoration: BoxDecoration(
                    color: Theme.of(context).primaryColor.withValues(alpha: 0.05),
                    borderRadius: BorderRadius.circular(12),
                    border: Border.all(
                      color: Theme.of(context).primaryColor.withValues(alpha: 0.2),
                    ),
                  ),
                  child: Column(
                    children: [
                      _buildInfoRow(
                        icon: Icons.fingerprint,
                        label: 'User ID',
                        value: _userId?.toString() ?? 'Loading...',
                      ),
                      const Divider(height: 16),
                      _buildInfoRow(
                        icon: Icons.admin_panel_settings,
                        label: 'Device Owner',
                        value: _isDeviceOwner ? 'Active' : 'Not Active',
                        valueColor: _isDeviceOwner ? Colors.green : Colors.orange,
                      ),
                      const Divider(height: 16),
                      _buildInfoRow(
                        icon: Icons.vpn_key,
                        label: 'Password Token',
                        value: _isTokenActive ? 'Active' : 'Not Active',
                        valueColor: _isTokenActive ? Colors.green : Colors.orange,
                      ),
                      const Divider(height: 16),
                      _buildInfoRow(
                        icon: Icons.lock,
                        label: 'Factory Reset',
                        value: _isDeviceOwner ? 'Disabled' : 'Enabled',
                        valueColor: _isDeviceOwner ? Colors.green : Colors.orange,
                      ),
                    ],
                  ),
                ),

                // Password Token Setup Button (only show if device owner and token not active)
                if (_isDeviceOwner && !_isTokenActive) ...[
                  const SizedBox(height: 16),
                  Container(
                    padding: const EdgeInsets.all(12),
                    decoration: BoxDecoration(
                      color: Colors.orange.withValues(alpha: 0.1),
                      borderRadius: BorderRadius.circular(12),
                      border: Border.all(color: Colors.orange.withValues(alpha: 0.3)),
                    ),
                    child: Column(
                      children: [
                        const Row(
                          children: [
                            Icon(Icons.warning_amber, color: Colors.orange, size: 20),
                            SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                'Setup required for remote password management',
                                style: TextStyle(
                                  fontSize: 12,
                                  color: Colors.orange,
                                  fontWeight: FontWeight.w500,
                                ),
                              ),
                            ),
                          ],
                        ),
                        const SizedBox(height: 8),
                        SizedBox(
                          width: double.infinity,
                          child: ElevatedButton.icon(
                            onPressed: _isSettingUpToken ? null : _setupPasswordToken,
                            icon: _isSettingUpToken
                                ? const SizedBox(
                                    width: 16,
                                    height: 16,
                                    child: CircularProgressIndicator(strokeWidth: 2),
                                  )
                                : const Icon(Icons.vpn_key, size: 18),
                            label: Text(_isSettingUpToken ? 'Setting up...' : 'Setup Password Token'),
                            style: ElevatedButton.styleFrom(
                              backgroundColor: Colors.orange,
                              foregroundColor: Colors.white,
                              padding: const EdgeInsets.symmetric(vertical: 10),
                            ),
                          ),
                        ),
                        const SizedBox(height: 4),
                        const Text(
                          'After setup, lock and unlock your device once to activate',
                          style: TextStyle(fontSize: 10, color: Colors.grey),
                          textAlign: TextAlign.center,
                        ),
                      ],
                    ),
                  ),
                ],

                const SizedBox(height: 32),

                // Status indicators
                Row(
                  mainAxisAlignment: MainAxisAlignment.center,
                  children: [
                    _buildStatusChip(
                      icon: Icons.cloud_done,
                      label: 'Synced',
                      color: Colors.green,
                    ),
                    const SizedBox(width: 12),
                    _buildStatusChip(
                      icon: Icons.security,
                      label: 'Protected',
                      color: Colors.blue,
                    ),
                  ],
                ),

                const SizedBox(height: 32),

                // Continue Button
                SizedBox(
                  width: double.infinity,
                  child: ElevatedButton(
                    onPressed: () {
                      Navigator.push(
                        context,
                        MaterialPageRoute(
                          builder: (context) => const TestDeviceScreen(),
                        ),
                      );
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
              ],
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildInfoRow({
    required IconData icon,
    required String label,
    required String value,
    Color? valueColor,
  }) {
    return Row(
      children: [
        Icon(icon, size: 20, color: Theme.of(context).primaryColor),
        const SizedBox(width: 12),
        Expanded(
          child: Text(
            label,
            style: const TextStyle(
              fontSize: 13,
              color: Colors.grey,
            ),
          ),
        ),
        Text(
          value,
          style: TextStyle(
            fontSize: 13,
            fontWeight: FontWeight.w600,
            color: valueColor ?? Colors.black87,
          ),
        ),
      ],
    );
  }

  Widget _buildStatusChip({
    required IconData icon,
    required String label,
    required Color color,
  }) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
      decoration: BoxDecoration(
        color: color.withValues(alpha: 0.1),
        borderRadius: BorderRadius.circular(20),
      ),
      child: Row(
        mainAxisSize: MainAxisSize.min,
        children: [
          Icon(icon, size: 16, color: color),
          const SizedBox(width: 6),
          Text(
            label,
            style: TextStyle(
              fontSize: 12,
              fontWeight: FontWeight.w600,
              color: color,
            ),
          ),
        ],
      ),
    );
  }
}

