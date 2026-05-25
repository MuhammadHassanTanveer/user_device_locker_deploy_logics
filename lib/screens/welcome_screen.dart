import 'package:flutter/material.dart';
import '../models/mobile_api_models.dart';
import '../providers/register_device_provider.dart';
import '../services/kiosk_service.dart';
import '../services/secret_code_service.dart';

class WelcomeScreen extends StatefulWidget {
  const WelcomeScreen({super.key});

  @override
  State<WelcomeScreen> createState() => _WelcomeScreenState();
}

class _WelcomeScreenState extends State<WelcomeScreen> {
  final ScrollController _scrollController = ScrollController();
  final RegisterDeviceProvider _provider = RegisterDeviceProvider();

  CustomerProfile? _customer;
  bool _isDeviceOwner = false;
  bool _isFactoryResetDisabled = false;
  bool _hasCalledLockCodeApi = false;
  bool _isFinishing = false;

  @override
  void initState() {
    super.initState();
    _loadDeviceInfo();
    _refreshSimWarningBanner();
  }

  Future<void> _refreshSimWarningBanner() async {
    try {
      await KioskService.refreshSimWarning();
    } catch (e) {
      debugPrint('refreshSimWarning: $e');
    }
  }

  @override
  void dispose() {
    _scrollController.dispose();
    super.dispose();
  }

  @override
  void didChangeDependencies() {
    super.didChangeDependencies();
    if (!_hasCalledLockCodeApi) {
      _hasCalledLockCodeApi = true;
      _fetchLockCode();
    }
  }

  Future<void> _loadDeviceInfo() async {
    final customer = await RegisterDeviceProvider.getCustomerProfile();
    final isDeviceOwner = await KioskService.isDeviceOwner();
    final isFactoryResetDisabled = isDeviceOwner
        ? await KioskService.isFactoryResetDisabled()
        : false;

    if (mounted) {
      setState(() {
        _customer = customer;
        _isDeviceOwner = isDeviceOwner;
        _isFactoryResetDisabled = isFactoryResetDisabled;
      });
    }
  }

  Future<void> _fetchLockCode() async {
    try {
      if (!mounted) return;

      final imei1 = await RegisterDeviceProvider.getImei1();
      // Without IMEI we cannot call get_lock_code; still refresh dialer codes when customer_id exists.
      if (imei1 == null || imei1.isEmpty) {
        await SecretCodeService.fetchAndStoreCodesFromApi();
        return;
      }

      await _provider.getLockCodeApi(context);
      if (mounted) {
        await _loadDeviceInfo();
      }
    } catch (e, stackTrace) {
      debugPrint('❌ Error fetching unlock code: $e');
      debugPrint('❌ Stack trace: $stackTrace');
    }
  }

  Future<void> _onFinishedPressed() async {
    if (_isFinishing) return;

    setState(() => _isFinishing = true);

    try {
      final factoryResetDisabled =
          await RegisterDeviceProvider.ensureFactoryResetDisabled();

      if (!factoryResetDisabled) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text(
                'Could not disable factory reset in Settings. '
                'Ensure Device Owner is active, then try again.',
              ),
              backgroundColor: Colors.orange,
            ),
          );
          setState(() => _isFinishing = false);
        }
        return;
      }

      final accessibilityOn = await KioskService.isAccessibilityEnabled();
      if (!accessibilityOn && mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(
            content: Text(
              'Enable Accessibility for this app so factory reset stays blocked '
              'on Infinix/Tecno and other custom Android skins.',
            ),
            backgroundColor: Colors.orange,
            duration: Duration(seconds: 6),
          ),
        );
      }

      if (mounted) {
        setState(() => _isFactoryResetDisabled = true);
      }

      final hidden = await KioskService.hideSelf();
      if (!hidden) {
        if (mounted) {
          ScaffoldMessenger.of(context).showSnackBar(
            const SnackBar(
              content: Text(
                'Could not hide app from launcher. Ensure Device Owner is active.',
              ),
              backgroundColor: Colors.orange,
            ),
          );
        }
        if (mounted) setState(() => _isFinishing = false);
        return;
      }

      // Let the system apply launcher component / DPM hide before finishing the activity.
      await Future.delayed(const Duration(milliseconds: 400));

      await KioskService.exitApp();
    } catch (e) {
      debugPrint('❌ Error finishing setup: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('Error: $e'),
            backgroundColor: Colors.red,
          ),
        );
        setState(() => _isFinishing = false);
      }
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        child: Scrollbar(
          controller: _scrollController,
          child: SingleChildScrollView(
            controller: _scrollController,
            padding: const EdgeInsets.all(24),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.stretch,
              children: [
                const SizedBox(height: 16),
                Center(
                  child: Container(
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
                ),
                const SizedBox(height: 32),
                Text(
                  'Device Registered!',
                  textAlign: TextAlign.center,
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
                        label: 'Customer ID',
                        value: _customer?.customerId.toString() ?? 'Loading...',
                      ),
                      if (_customer?.customerName.isNotEmpty == true) ...[
                        const Divider(height: 16),
                        _buildInfoRow(
                          icon: Icons.person,
                          label: 'Customer Name',
                          value: _customer!.customerName,
                        ),
                      ],
                      if (_customer?.customerCode.isNotEmpty == true) ...[
                        const Divider(height: 16),
                        _buildInfoRow(
                          icon: Icons.qr_code_2,
                          label: 'Customer Code',
                          value: _customer!.customerCode,
                        ),
                      ],
                      if (_customer?.mobileModel.isNotEmpty == true) ...[
                        const Divider(height: 16),
                        _buildInfoRow(
                          icon: Icons.phone_android,
                          label: 'Device Model',
                          value: _customer!.mobileModel,
                        ),
                      ],
                      if (_customer != null) ...[
                        const Divider(height: 16),
                        _buildInfoRow(
                          icon: Icons.circle,
                          label: 'Account Status',
                          value: _customer!.statusLabel,
                          valueColor:
                              _customer!.isDeviceActive ? Colors.green : Colors.orange,
                        ),
                      ],
                      const Divider(height: 16),
                      _buildInfoRow(
                        icon: Icons.admin_panel_settings,
                        label: 'Device Owner',
                        value: _isDeviceOwner ? 'Active' : 'Not Active',
                        valueColor: _isDeviceOwner ? Colors.green : Colors.orange,
                      ),
                      const Divider(height: 16),
                      _buildInfoRow(
                        icon: Icons.lock,
                        label: 'Factory Reset',
                        value: !_isDeviceOwner
                            ? 'Not protected'
                            : (_isFactoryResetDisabled ? 'Disabled' : 'Enabled'),
                        valueColor: _isFactoryResetDisabled ? Colors.green : Colors.orange,
                      ),
                    ],
                  ),
                ),
                const SizedBox(height: 32),
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
                SizedBox(
                  width: double.infinity,
                  child: ElevatedButton(
                    onPressed: _isFinishing ? null : _onFinishedPressed,
                    style: ElevatedButton.styleFrom(
                      backgroundColor: Theme.of(context).primaryColor,
                      foregroundColor: Colors.white,
                      padding: const EdgeInsets.symmetric(vertical: 16),
                      shape: RoundedRectangleBorder(
                        borderRadius: BorderRadius.circular(12),
                      ),
                    ),
                    child: _isFinishing
                        ? const SizedBox(
                            height: 22,
                            width: 22,
                            child: CircularProgressIndicator(
                              strokeWidth: 2,
                              color: Colors.white,
                            ),
                          )
                        : const Text(
                            'Finished',
                            style: TextStyle(
                              fontSize: 16,
                              fontWeight: FontWeight.w600,
                            ),
                          ),
                  ),
                ),
                const SizedBox(height: 16),
                // const SizedBox(height: 32),
                // SizedBox(
                //   width: double.infinity,
                //   child: ElevatedButton(
                //     onPressed: () {
                //       Navigator.push(
                //         context,
                //         MaterialPageRoute(
                //           builder: (context) => const TestDeviceScreen(),
                //         ),
                //       );
                //     },
                //     style: ElevatedButton.styleFrom(
                //       backgroundColor: Theme.of(context).primaryColor,
                //       foregroundColor: Colors.white,
                //       padding: const EdgeInsets.symmetric(vertical: 16),
                //       shape: RoundedRectangleBorder(
                //         borderRadius: BorderRadius.circular(12),
                //       ),
                //     ),
                //     child: const Text(
                //       'Continue',
                //       style: TextStyle(
                //         fontSize: 16,
                //         fontWeight: FontWeight.w600,
                //       ),
                //     ),
                //   ),
                // ),
                // const SizedBox(height: 16),
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
        Flexible(
          child: Text(
            value,
            textAlign: TextAlign.end,
            style: TextStyle(
              fontSize: 13,
              fontWeight: FontWeight.w600,
              color: valueColor ?? Colors.black87,
            ),
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
