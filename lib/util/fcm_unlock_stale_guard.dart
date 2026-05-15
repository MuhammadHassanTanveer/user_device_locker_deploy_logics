import 'package:firebase_messaging/firebase_messaging.dart';
import 'package:shared_preferences/shared_preferences.dart';

/// Matches native [LockCommandActions] — stored under `FlutterSharedPreferences`
/// as `flutter._native_last_unlock_wall_ms` (string epoch ms).
const _unlockMarkerPrefsKey = '_native_last_unlock_wall_ms';

/// Prevents a queued FCM `lock` (older [RemoteMessage.sentTime]) from re-locking
/// after an unlock (native + Dart both read this marker).
class FcmUnlockStaleGuard {
  FcmUnlockStaleGuard._();

  static int _computeMarker(RemoteMessage? message) {
    final now = DateTime.now().millisecondsSinceEpoch;
    final sent = message?.sentTime?.millisecondsSinceEpoch;
    if (sent != null && sent > 0) {
      return now > sent ? now : sent;
    }
    return now;
  }

  static Future<void> recordUnlock(RemoteMessage? message) async {
    final prefs = await SharedPreferences.getInstance();
    final marker = _computeMarker(message);
    await prefs.setString(_unlockMarkerPrefsKey, marker.toString());
  }

  static Future<bool> shouldIgnoreStaleLock(RemoteMessage message) async {
    final sent = message.sentTime?.millisecondsSinceEpoch;
    if (sent == null || sent <= 0) return false;
    final prefs = await SharedPreferences.getInstance();
    final raw = prefs.getString(_unlockMarkerPrefsKey);
    final last = int.tryParse(raw ?? '') ?? 0;
    if (last <= 0) return false;
    return sent < last;
  }
}
