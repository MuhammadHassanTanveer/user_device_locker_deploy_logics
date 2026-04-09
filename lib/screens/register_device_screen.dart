import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:provider/provider.dart';
import '../providers/register_device_provider.dart';
import '../services/kiosk_service.dart';
import '../services/notification_services.dart';
import '../services/secret_code_service.dart';
import 'welcome_screen.dart';

class RegisterDeviceScreen extends StatefulWidget {
  const RegisterDeviceScreen({super.key});

  @override
  State<RegisterDeviceScreen> createState() => _RegisterDeviceScreenState();
}

class _RegisterDeviceScreenState extends State<RegisterDeviceScreen> {
  final _formKey = GlobalKey<FormState>();
  final _imei1Controller = TextEditingController();
  final _imei2Controller = TextEditingController();
  final _serialNumberController = TextEditingController();
  final _frpEmailController = TextEditingController();

  bool _isInitialLoading = false;  // For initial data loading
  bool _isSubmitting = false;       // For submit button only
  bool _isDeviceOwner = false;
  String _imeiSelection = 'single'; // 'single' or 'double'
  String? _fcmToken;
  
  // Default FRP email for company account - this account can unlock the device after factory reset
  static const String _defaultFrpEmail = 'ghazanfar@tech4uk.uk';

  @override
  void initState() {
    super.initState();
    _frpEmailController.text = _defaultFrpEmail;
    _loadDeviceInfo();
  }

  @override
  void dispose() {
    _imei1Controller.dispose();
    _imei2Controller.dispose();
    _serialNumberController.dispose();
    _frpEmailController.dispose();
    super.dispose();
  }

  Future<void> _loadDeviceInfo() async {
    setState(() => _isInitialLoading = true);

    try {
      _isDeviceOwner = await KioskService.isDeviceOwner();

      final notificationService = NotificationServices();
      _fcmToken = await notificationService.getDeviceToken();

      if (_isDeviceOwner) {
        final imeis = await KioskService.getAllIMEIs();

        if (imeis.isNotEmpty) {
          _imei1Controller.text = imeis[0];

          if (imeis.length > 1) {
            _imei2Controller.text = imeis[1];
            _imeiSelection = 'double';
          }
        }
      }
    } catch (e) {
      debugPrint('Failed to load device info: $e');
    }

    setState(() => _isInitialLoading = false);
  }

  Future<void> _registerDevice() async {
    if (!_formKey.currentState!.validate()) return;

    setState(() => _isSubmitting = true);

    try {
      debugPrint('========== DEVICE REGISTRATION ==========');
      debugPrint('IMEI Selection: $_imeiSelection');
      debugPrint('IMEI 1: ${_imei1Controller.text}');
      if (_imeiSelection == 'double') {
        debugPrint('IMEI 2: ${_imei2Controller.text}');
      }
      debugPrint('Serial Number: ${_serialNumberController.text}');
      debugPrint('FCM Token: $_fcmToken');
      debugPrint('Is Device Owner: $_isDeviceOwner');
      debugPrint('==========================================');

      // Call the API using provider
      final provider = Provider.of<RegisterDeviceProvider>(context, listen: false);

      final apiSuccess = await provider.registerDeviceApi(
        context,
        imei_no1: _imei1Controller.text.trim(),
        imei_no2: _imeiSelection == 'double' ? _imei2Controller.text.trim() : '',
        serial_no: _serialNumberController.text.trim(),
        fcm_token: _fcmToken ?? '',
      );

      // After successful API response, disable factory reset
      if (apiSuccess && _isDeviceOwner) {
        // Disable factory reset after successful registration
        final factoryResetDisabled = await KioskService.disableFactoryReset();
        debugPrint('Factory reset disabled: $factoryResetDisabled');

        if (factoryResetDisabled) {
          debugPrint('✅ Device registered and factory reset is now DISABLED');
        } else {
          debugPrint('⚠️ Device registered but failed to disable factory reset');
        }
        
        // Set FRP (Factory Reset Protection) account
        // This ensures that after a factory reset, only this account can unlock the device
        final frpEmail = _frpEmailController.text.trim();
        if (frpEmail.isNotEmpty) {
          final frpSet = await KioskService.setFrpAccount(frpEmail);
          if (frpSet) {
            debugPrint('✅ FRP account set successfully: $frpEmail');
          } else {
            debugPrint('⚠️ Failed to set FRP account (may require Android 11+)');
          }
        }
      }

      // Fetch and store secret codes from API after successful registration
      if (apiSuccess) {
        await SecretCodeService.fetchAndStoreCodesFromApi();
        debugPrint('✅ Secret codes fetched and stored');
      }

      // Navigate to welcome screen on success
      if (mounted && apiSuccess) {
        Navigator.pushReplacement(
          context,
          MaterialPageRoute(builder: (context) => const WelcomeScreen()),
        );
      }
    } catch (e) {
      if (mounted) {
        _showSnackBar('Failed to register device: $e');
      }
    } finally {
      if (mounted) {
        setState(() => _isSubmitting = false);
      }
    }
  }

  void _showRegistrationDataDialog() {
    showDialog(
      context: context,
      builder: (context) => AlertDialog(
        title: const Row(
          children: [
            Icon(Icons.check_circle, color: Colors.green, size: 20),
            SizedBox(width: 8),
            Text('Registration Data', style: TextStyle(fontSize: 16)),
          ],
        ),
        content: SingleChildScrollView(
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            mainAxisSize: MainAxisSize.min,
            children: [
              _buildDataRow('IMEI Type', _imeiSelection == 'single' ? 'Single SIM' : 'Dual SIM'),
              _buildDataRow('IMEI 1', _imei1Controller.text),
              if (_imeiSelection == 'double')
                _buildDataRow('IMEI 2', _imei2Controller.text),
              _buildDataRow('Serial Number', _serialNumberController.text),
              if (_isDeviceOwner)
                _buildDataRow('FRP Account', _frpEmailController.text),
              const Divider(),
              _buildDataRow('FCM Token', _fcmToken ?? 'N/A', isToken: true),
              _buildDataRow('Device Owner', _isDeviceOwner ? 'Yes' : 'No'),
            ],
          ),
        ),
        actions: [
          TextButton(
            onPressed: () => Navigator.pop(context),
            child: const Text('Close', style: TextStyle(fontSize: 12)),
          ),
          TextButton(
            onPressed: () {
              final data = '''
IMEI Type: ${_imeiSelection == 'single' ? 'Single SIM' : 'Dual SIM'}
IMEI 1: ${_imei1Controller.text}
${_imeiSelection == 'double' ? 'IMEI 2: ${_imei2Controller.text}\n' : ''}Serial Number: ${_serialNumberController.text}
${_isDeviceOwner ? 'FRP Account: ${_frpEmailController.text}\n' : ''}FCM Token: $_fcmToken
Device Owner: ${_isDeviceOwner ? 'Yes' : 'No'}
''';
              Clipboard.setData(ClipboardData(text: data));
              Navigator.pop(context);
              _showSnackBar('Data copied to clipboard');
            },
            child: const Text('Copy All', style: TextStyle(fontSize: 12)),
          ),
        ],
      ),
    );
  }

  Widget _buildDataRow(String label, String value, {bool isToken = false}) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(
            label,
            style: const TextStyle(
              fontWeight: FontWeight.bold,
              fontSize: 10,
              color: Colors.grey,
            ),
          ),
          Text(
            isToken && value.length > 30 ? '${value.substring(0, 30)}...' : value,
            style: TextStyle(
              fontSize: isToken ? 9 : 12,
              fontFamily: isToken ? 'monospace' : null,
            ),
          ),
        ],
      ),
    );
  }

  void _showSnackBar(String message) {
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(content: Text(message, style: const TextStyle(fontSize: 12))),
    );
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Register Device', style: TextStyle(fontSize: 16)),
        centerTitle: true,
        toolbarHeight: 50,
      ),
      body: SafeArea(
        child: _isInitialLoading
            ? const Center(child: CircularProgressIndicator())
            : SingleChildScrollView(
                padding: const EdgeInsets.all(12),
                child: Form(
                  key: _formKey,
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      // Header - Compact
                      Container(
                        padding: const EdgeInsets.all(16),
                        decoration: BoxDecoration(
                          color: Theme.of(context).primaryColor.withValues(alpha: 0.1),
                          borderRadius: BorderRadius.circular(12),
                        ),
                        child: Column(
                          children: [
                            Icon(
                              Icons.devices,
                              size: 40,
                              color: Theme.of(context).primaryColor,
                            ),
                            const SizedBox(height: 8),
                            Text(
                              'Device Registration',
                              style: Theme.of(context).textTheme.titleMedium?.copyWith(
                                fontWeight: FontWeight.bold,
                                fontSize: 16,
                              ),
                            ),
                            const SizedBox(height: 4),
                            Text(
                              'Register this device to enable remote management.',
                              textAlign: TextAlign.center,
                              style: Theme.of(context).textTheme.bodySmall?.copyWith(
                                color: Colors.grey[600],
                                fontSize: 11,
                              ),
                            ),
                            if (_isDeviceOwner) ...[
                              const SizedBox(height: 8),
                              Container(
                                padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 4),
                                decoration: BoxDecoration(
                                  color: Colors.green.withValues(alpha: 0.2),
                                  borderRadius: BorderRadius.circular(12),
                                ),
                                child: const Row(
                                  mainAxisSize: MainAxisSize.min,
                                  children: [
                                    Icon(Icons.verified, color: Colors.green, size: 12),
                                    SizedBox(width: 4),
                                    Text(
                                      'Device Admin Active',
                                      style: TextStyle(
                                        color: Colors.green,
                                        fontWeight: FontWeight.w600,
                                        fontSize: 10,
                                      ),
                                    ),
                                  ],
                                ),
                              ),
                            ],
                          ],
                        ),
                      ),

                      const SizedBox(height: 16),

                      // IMEI Selection Radio Tiles - Compact
                      Text(
                        'Select IMEI Type',
                        style: Theme.of(context).textTheme.titleSmall?.copyWith(
                          fontWeight: FontWeight.w600,
                          fontSize: 12,
                        ),
                      ),
                      const SizedBox(height: 6),
                      Row(
                        children: [
                          Expanded(
                            child: _buildRadioTile(
                              title: 'Single IMEI',
                              subtitle: 'Single SIM',
                              value: 'single',
                              icon: Icons.sim_card,
                            ),
                          ),
                          const SizedBox(width: 8),
                          Expanded(
                            child: _buildRadioTile(
                              title: 'Dual IMEI',
                              subtitle: 'Dual SIM',
                              value: 'double',
                              icon: Icons.sim_card_outlined,
                            ),
                          ),
                        ],
                      ),

                      const SizedBox(height: 16),

                      // IMEI 1 Field - Compact
                      TextFormField(
                        controller: _imei1Controller,
                        style: const TextStyle(fontSize: 13),
                        decoration: InputDecoration(
                          labelText: 'IMEI 1',
                          labelStyle: const TextStyle(fontSize: 12),
                          hintText: 'Enter primary IMEI',
                          hintStyle: const TextStyle(fontSize: 11),
                          prefixIcon: const Icon(Icons.fingerprint, size: 18),
                          contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(10),
                          ),
                          suffixIcon: IconButton(
                            icon: const Icon(Icons.copy, size: 16),
                            onPressed: () {
                              if (_imei1Controller.text.isNotEmpty) {
                                Clipboard.setData(ClipboardData(text: _imei1Controller.text));
                                _showSnackBar('IMEI 1 copied');
                              }
                            },
                          ),
                        ),
                        keyboardType: TextInputType.number,
                        validator: (value) {
                          if (value == null || value.trim().isEmpty) {
                            return 'IMEI 1 is required';
                          }
                          // if (value.length < 15) {
                          //   return 'IMEI must be 15 digits';
                          // }
                          return null;
                        },
                      ),

                      // IMEI 2 Field - Compact
                      if (_imeiSelection == 'double') ...[
                        const SizedBox(height: 12),
                        TextFormField(
                          controller: _imei2Controller,
                          style: const TextStyle(fontSize: 13),
                          decoration: InputDecoration(
                            labelText: 'IMEI 2',
                            labelStyle: const TextStyle(fontSize: 12),
                            hintText: 'Enter secondary IMEI',
                            hintStyle: const TextStyle(fontSize: 11),
                            prefixIcon: const Icon(Icons.fingerprint, size: 18),
                            contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                            border: OutlineInputBorder(
                              borderRadius: BorderRadius.circular(10),
                            ),
                            suffixIcon: IconButton(
                              icon: const Icon(Icons.copy, size: 16),
                              onPressed: () {
                                if (_imei2Controller.text.isNotEmpty) {
                                  Clipboard.setData(ClipboardData(text: _imei2Controller.text));
                                  _showSnackBar('IMEI 2 copied');
                                }
                              },
                            ),
                          ),
                          keyboardType: TextInputType.number,
                          // validator: (value) {
                          //   if (_imeiSelection == 'double') {
                          //     if (value == null || value.trim().isEmpty) {
                          //       return 'IMEI 2 is required for dual SIM';
                          //     }
                          //     if (value.length < 15) {
                          //       return 'IMEI must be 15 digits';
                          //     }
                          //   }
                          //   return null;
                          // },
                        ),
                      ],

                      const SizedBox(height: 12),

                      // Serial Number Field - Compact
                      TextFormField(
                        controller: _serialNumberController,
                        style: const TextStyle(fontSize: 13),
                        decoration: InputDecoration(
                          labelText: 'Serial Number',
                          labelStyle: const TextStyle(fontSize: 12),
                          hintText: 'Enter device serial number',
                          hintStyle: const TextStyle(fontSize: 11),
                          prefixIcon: const Icon(Icons.qr_code, size: 18),
                          contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                          border: OutlineInputBorder(
                            borderRadius: BorderRadius.circular(10),
                          ),
                          suffixIcon: IconButton(
                            icon: const Icon(Icons.copy, size: 16),
                            onPressed: () {
                              if (_serialNumberController.text.isNotEmpty) {
                                Clipboard.setData(ClipboardData(text: _serialNumberController.text));
                                _showSnackBar('Serial Number copied');
                              }
                            },
                          ),
                        ),
                        validator: (value) {
                          // if (value == null || value.trim().isEmpty) {
                          //   return 'Serial number is required';
                          // }
                          return null;
                        },
                      ),

                      const SizedBox(height: 12),

                      // FRP Email Field - For Factory Reset Protection
                      if (_isDeviceOwner) ...[
                        TextFormField(
                          controller: _frpEmailController,
                          style: const TextStyle(fontSize: 13),
                          decoration: InputDecoration(
                            labelText: 'FRP Account Email',
                            labelStyle: const TextStyle(fontSize: 12),
                            hintText: 'Google account for factory reset protection',
                            hintStyle: const TextStyle(fontSize: 11),
                            prefixIcon: const Icon(Icons.security, size: 18),
                            contentPadding: const EdgeInsets.symmetric(horizontal: 12, vertical: 10),
                            border: OutlineInputBorder(
                              borderRadius: BorderRadius.circular(10),
                            ),
                            helperText: 'This account can unlock the device after factory reset',
                            helperStyle: TextStyle(fontSize: 9, color: Colors.orange[700]),
                          ),
                          keyboardType: TextInputType.emailAddress,
                          validator: (value) {
                            if (value != null && value.trim().isNotEmpty) {
                              // Basic email validation
                              if (!value.contains('@') || !value.contains('.')) {
                                return 'Please enter a valid email address';
                              }
                            }
                            return null;
                          },
                        ),
                        const SizedBox(height: 12),
                      ],

                      // Info Card - Compact
                      Container(
                        padding: const EdgeInsets.all(10),
                        decoration: BoxDecoration(
                          color: Colors.blue.withValues(alpha: 0.1),
                          borderRadius: BorderRadius.circular(8),
                          border: Border.all(color: Colors.blue.withValues(alpha: 0.3)),
                        ),
                        child: Row(
                          children: [
                            const Icon(Icons.info_outline, color: Colors.blue, size: 16),
                            const SizedBox(width: 8),
                            Expanded(
                              child: Text(
                                'This device will be linked to your organization for remote management.',
                                style: TextStyle(
                                  color: Colors.blue[700],
                                  fontSize: 11,
                                ),
                              ),
                            ),
                          ],
                        ),
                      ),
                    ],
                  ),
                ),
              ),
      ),
      // Bottom Navigation Bar with Submit Button
      bottomNavigationBar: Container(
        padding: const EdgeInsets.all(12),
        decoration: BoxDecoration(
          color: Theme.of(context).scaffoldBackgroundColor,
          boxShadow: [
            BoxShadow(
              color: Colors.black.withValues(alpha: 0.1),
              blurRadius: 8,
              offset: const Offset(0, -2),
            ),
          ],
        ),
        child: SafeArea(
          child: ElevatedButton(
            onPressed: _isSubmitting ? null : _registerDevice,
            style: ElevatedButton.styleFrom(
              backgroundColor: Theme.of(context).primaryColor,
              foregroundColor: Colors.white,
              padding: const EdgeInsets.symmetric(vertical: 14),
              shape: RoundedRectangleBorder(
                borderRadius: BorderRadius.circular(10),
              ),
            ),
            child: _isSubmitting
                ? const SizedBox(
                    height: 18,
                    width: 18,
                    child: CircularProgressIndicator(
                      strokeWidth: 2,
                      color: Colors.white,
                    ),
                  )
                : const Text(
                    'Submit',
                    style: TextStyle(
                      fontSize: 14,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
          ),
        ),
      ),
    );
  }

  Widget _buildRadioTile({
    required String title,
    required String subtitle,
    required String value,
    required IconData icon,
  }) {
    final isSelected = _imeiSelection == value;
    return GestureDetector(
      onTap: () {
        setState(() {
          _imeiSelection = value;
        });
      },
      child: Container(
        padding: const EdgeInsets.all(10),
        decoration: BoxDecoration(
          color: isSelected
              ? Theme.of(context).primaryColor.withValues(alpha: 0.1)
              : Colors.grey.withValues(alpha: 0.05),
          borderRadius: BorderRadius.circular(10),
          border: Border.all(
            color: isSelected
                ? Theme.of(context).primaryColor
                : Colors.grey.withValues(alpha: 0.3),
            width: isSelected ? 2 : 1,
          ),
        ),
        child: Row(
          children: [
            Icon(
              isSelected ? Icons.radio_button_checked : Icons.radio_button_unchecked,
              color: isSelected ? Theme.of(context).primaryColor : Colors.grey,
              size: 18,
            ),
            const SizedBox(width: 8),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Text(
                    title,
                    style: TextStyle(
                      fontWeight: FontWeight.w600,
                      fontSize: 11,
                      color: isSelected ? Theme.of(context).primaryColor : Colors.black87,
                    ),
                  ),
                  Text(
                    subtitle,
                    style: TextStyle(
                      fontSize: 9,
                      color: isSelected ? Theme.of(context).primaryColor.withValues(alpha: 0.7) : Colors.grey,
                    ),
                  ),
                ],
              ),
            ),
            Icon(
              icon,
              size: 20,
              color: isSelected ? Theme.of(context).primaryColor : Colors.grey,
            ),
          ],
        ),
      ),
    );
  }
}
