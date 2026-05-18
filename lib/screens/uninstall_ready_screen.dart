import 'package:flutter/material.dart';

import '../providers/register_device_provider.dart';
import '../services/kiosk_service.dart';
import 'register_device_screen.dart';

/// Shown after an uninstall FCM command: app is visible again so the user can
/// uninstall from system settings or re-register the device.
class UninstallReadyScreen extends StatefulWidget {
  const UninstallReadyScreen({super.key});

  @override
  State<UninstallReadyScreen> createState() => _UninstallReadyScreenState();
}

class _UninstallReadyScreenState extends State<UninstallReadyScreen> {
  bool _isLoading = false;

  @override
  void initState() {
    super.initState();
    _ensureVisible();
  }

  Future<void> _ensureVisible() async {
    await KioskService.showSelf();
  }

  Future<void> _openUninstallSettings() async {
    setState(() => _isLoading = true);
    try {
      final opened = await KioskService.openAppSettings();
      if (mounted && !opened) {
        _showMessage('Could not open app settings', isError: true);
      }
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  Future<void> _reRegisterDevice() async {
    setState(() => _isLoading = true);
    try {
      await RegisterDeviceProvider.clearDeviceId();
      await KioskService.clearPendingUninstallUi();
      if (!mounted) return;
      Navigator.of(context).pushAndRemoveUntil(
        MaterialPageRoute(builder: (_) => const RegisterDeviceScreen()),
        (_) => false,
      );
    } catch (e) {
      if (mounted) {
        _showMessage('Could not reset registration: $e', isError: true);
      }
    } finally {
      if (mounted) setState(() => _isLoading = false);
    }
  }

  void _showMessage(String text, {bool isError = false}) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: Text(text),
        backgroundColor: isError ? Colors.red : Colors.green,
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Padding(
          padding: const EdgeInsets.all(24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              const SizedBox(height: 24),
              Center(
                child: Container(
                  padding: const EdgeInsets.all(24),
                  decoration: BoxDecoration(
                    color: Colors.orange.withValues(alpha: 0.12),
                    shape: BoxShape.circle,
                  ),
                  child: const Icon(
                    Icons.phonelink_erase,
                    size: 72,
                    color: Colors.orange,
                  ),
                ),
              ),
              const SizedBox(height: 28),
              Text(
                'Remove or re-register device',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.headlineSmall?.copyWith(
                      fontWeight: FontWeight.bold,
                    ),
              ),
              const SizedBox(height: 12),
              Text(
                'Device Guardian is visible again. You can uninstall the app from system settings, or register this device with a new customer.',
                textAlign: TextAlign.center,
                style: Theme.of(context).textTheme.bodyMedium?.copyWith(
                      color: Colors.grey[600],
                    ),
              ),
              const Spacer(),
              SizedBox(
                width: double.infinity,
                child: ElevatedButton.icon(
                  onPressed: _isLoading ? null : _openUninstallSettings,
                  icon: const Icon(Icons.settings),
                  label: const Text('Open app settings to uninstall'),
                  style: ElevatedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(vertical: 16),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                ),
              ),
              const SizedBox(height: 12),
              SizedBox(
                width: double.infinity,
                child: OutlinedButton.icon(
                  onPressed: _isLoading ? null : _reRegisterDevice,
                  icon: const Icon(Icons.app_registration),
                  label: const Text('Re-register device'),
                  style: OutlinedButton.styleFrom(
                    padding: const EdgeInsets.symmetric(vertical: 16),
                    shape: RoundedRectangleBorder(
                      borderRadius: BorderRadius.circular(12),
                    ),
                  ),
                ),
              ),
              if (_isLoading) ...[
                const SizedBox(height: 24),
                const Center(child: CircularProgressIndicator()),
              ],
              const SizedBox(height: 16),
            ],
          ),
        ),
      ),
    );
  }
}
