import 'package:flutter/material.dart';
import '../../widgets/custom_toast.dart';

Future<void> showCustomSnackBar(BuildContext context, String? message, {bool isError = true}) async {
  if (message != null && message.isNotEmpty) {
    // Close any existing snack bars
    ScaffoldMessenger.of(context).clearSnackBars();

    // Show the custom snack bar
    ScaffoldMessenger.of(context).showSnackBar(
      SnackBar(
        content: CustomToast(
          text: message,
          isError: isError,
        ),
        backgroundColor: Colors.transparent,
        behavior: SnackBarBehavior.floating,
        margin: const EdgeInsets.only(bottom: 40, left: 20, right: 20),
        padding: const EdgeInsets.all(0),
        shape: RoundedRectangleBorder(borderRadius: BorderRadius.circular(10)),
        duration: const Duration(seconds: 2),
        dismissDirection: DismissDirection.horizontal,
        elevation: 0,
      ),
    );
  }
}
