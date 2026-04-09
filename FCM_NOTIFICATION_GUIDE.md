# How to Send FCM Notifications for Device Commands

## IMPORTANT: Data-Only Messages

For your device commands to work when the app is killed/swiped away, you MUST send **DATA-ONLY** FCM messages (no notification payload).

### ✅ CORRECT - Data-Only Message (Works when app is killed)

```json
{
  "to": "<device_fcm_token>",
  "priority": "high",
  "data": {
    "device": "disable_camera"
  }
}
```

### ❌ WRONG - Message with Notification Payload (Does NOT work when app is killed)

```json
{
  "to": "<device_fcm_token>",
  "notification": {
    "title": "Command",
    "body": "Disabling camera"
  },
  "data": {
    "device": "disable_camera"
  }
}
```

When you include a `notification` payload:
- **App in foreground**: `onMessageReceived()` is called ✅
- **App killed/background**: Android displays the notification, `onMessageReceived()` is NOT called ❌
  - Data is only accessible when user taps the notification

## Using Firebase Admin SDK (Node.js Example)

```javascript
const admin = require('firebase-admin');

// Initialize Firebase Admin
admin.initializeApp({
  credential: admin.credential.cert(serviceAccount)
});

// Send data-only message
const message = {
  data: {
    device: 'disable_camera'  // or 'enable_camera', 'disable_location', etc.
  },
  android: {
    priority: 'high',  // REQUIRED for reliability when app is killed
    ttl: 0  // Don't cache, deliver immediately
  },
  token: deviceFCMToken
};

admin.messaging().send(message)
  .then((response) => {
    console.log('Successfully sent message:', response);
  })
  .catch((error) => {
    console.log('Error sending message:', error);
  });
```

## Using HTTP v1 API (cURL Example)

```bash
curl -X POST \
  'https://fcm.googleapis.com/v1/projects/YOUR_PROJECT_ID/messages:send' \
  -H 'Authorization: Bearer YOUR_ACCESS_TOKEN' \
  -H 'Content-Type: application/json' \
  -d '{
    "message": {
      "token": "DEVICE_FCM_TOKEN",
      "data": {
        "device": "disable_camera"
      },
      "android": {
        "priority": "high",
        "ttl": "0s"
      }
    }
  }'
```

## Using Legacy HTTP API (cURL Example)

```bash
curl -X POST \
  'https://fcm.googleapis.com/fcm/send' \
  -H 'Authorization: key=YOUR_SERVER_KEY' \
  -H 'Content-Type: application/json' \
  -d '{
    "to": "DEVICE_FCM_TOKEN",
    "priority": "high",
    "data": {
      "device": "disable_camera"
    }
  }'
```

## Available Commands

| Command | Description |
|---------|-------------|
| `lock` | Show lock overlay (with optional `pin` field) |
| `unlock` | Hide lock overlay |
| `enable_camera` | Enable device camera |
| `disable_camera` | Disable device camera |
| `enable_location` | Enable location config |
| `disable_location` | Disable location config |
| `turn_on_location_lock` | Turn ON location + lock setting |
| `turn_off_location_lock` | Turn OFF location + lock setting |
| `enable_factory_reset` | Enable factory reset |
| `disable_factory_reset` | Disable factory reset |
| `get_current_location` | Get and send current GPS location |
| `enable_screen_capture` | Enable screen capture |
| `disable_screen_capture` | Disable screen capture |
| `enable_usb_transfer` | Enable USB file transfer |
| `disable_usb_transfer` | Disable USB file transfer |
| `enable_wifi_config` | Enable WiFi configuration |
| `disable_wifi_config` | Disable WiFi configuration |
| `enable_bluetooth_config` | Enable Bluetooth configuration |
| `disable_bluetooth_config` | Disable Bluetooth configuration |
| `enable_notifications` | Enable notification permission (lock in granted state) |
| `disable_notifications` | Disable notification permission (lock in denied state) |
| `lock_notification_permission` | Lock notification permission in granted state |
| `unlock_notification_permission` | Unlock notification permission (user can change) |
| `reboot` | Reboot device |
| `lock_now` | Lock screen immediately |

## Debugging

To verify notifications are being received correctly, run:

```bash
adb logcat -c && adb logcat | grep -E "MyFirebaseMsgService|DeviceCommandService|DPMHelper|PersistentFCMService"
```

You should see logs like:
```
🔥🔥🔥 FCM Message Received in NATIVE Service! 🔥🔥🔥
Device command extracted: 'disable_camera'
🚀 Processing command: disable_camera
📤 Starting foreground service for command: disable_camera
>>> Executing DISABLE_CAMERA
✅ Camera disabled: true
```

If you DON'T see these logs when app is killed, check:
1. Is the notification DATA-ONLY (no `notification` payload)?
2. Is `priority: high` set?
3. Is the device excluded from battery optimization?

