import 'dart:convert';

/// Standard mobile API envelope:
/// `{ "success": true, "code": "...", "message": "...", "data": { ... } }`
class ApiResponse {
  final bool success;
  final String? code;
  final String? message;
  final Map<String, dynamic>? data;

  const ApiResponse({
    required this.success,
    this.code,
    this.message,
    this.data,
  });

  static ApiResponse? tryParse(dynamic json) {
    if (json is! Map) return null;
    final map = Map<String, dynamic>.from(json);
    return ApiResponse(
      success: map['success'] == true,
      code: map['code']?.toString(),
      message: map['message']?.toString(),
      data: extractData(map),
    );
  }

  static ApiResponse? fromHttpBody(String body) {
    try {
      return tryParse(jsonDecode(body));
    } catch (_) {
      return null;
    }
  }

  static bool isHttpOk(int statusCode) =>
      statusCode >= 200 && statusCode < 300;

  static Map<String, dynamic>? extractData(Map<String, dynamic> json) {
    final raw = json['data'] ?? json['Data'];
    if (raw is Map) {
      return Map<String, dynamic>.from(raw);
    }
    if (raw is String) {
      try {
        final decoded = jsonDecode(raw.trim());
        if (decoded is Map) {
          return Map<String, dynamic>.from(decoded);
        }
      } catch (_) {}
    }
    return null;
  }

  static String? messageFrom(dynamic json, {String? fallback}) {
    if (json is Map) {
      final message = json['message']?.toString();
      if (message != null && message.isNotEmpty) return message;
    }
    return fallback;
  }

  /// First non-empty string value for any of [keys] inside [data].
  static String stringField(
    Map<String, dynamic>? data,
    List<String> keys, {
    String fallback = '',
  }) {
    if (data == null) return fallback;
    for (final key in keys) {
      final value = data[key];
      if (value == null) continue;
      final text = value.toString().trim();
      if (text.isNotEmpty && text != 'null') return text;
    }
    return fallback;
  }

  static int? intField(Map<String, dynamic>? data, List<String> keys) {
    if (data == null) return null;
    for (final key in keys) {
      final value = data[key];
      if (value is int) return value;
      if (value is double) return value.toInt();
      if (value is String) {
        final parsed = int.tryParse(value);
        if (parsed != null) return parsed;
      }
    }
    return null;
  }
}
