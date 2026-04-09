#!/bin/bash

# ADB path
ADB="/Users/mac/Library/Android/sdk/platform-tools/adb"

echo "=========================================="
echo "       Device Locker ADB Test Script"
echo "=========================================="

# Check ADB
echo ""
echo "1. Checking ADB installation..."
if [ -f "$ADB" ]; then
    echo "   ✅ ADB found at: $ADB"
else
    echo "   ❌ ADB not found!"
    exit 1
fi

# List devices
echo ""
echo "2. Listing connected devices..."
$ADB devices -l

# Check if app is installed
echo ""
echo "3. Checking if app is installed..."
PACKAGE=$($ADB shell pm list packages | grep "com.deploy_logics.user_device_locker")
if [ -n "$PACKAGE" ]; then
    echo "   ✅ App is installed: $PACKAGE"
else
    echo "   ❌ App NOT installed. Installing now..."
    cd /Users/mac/StudioProjects/dcp_test
    flutter build apk --debug
    $ADB install -r build/app/outputs/flutter-apk/app-debug.apk
fi

# Check Device Owner status
echo ""
echo "4. Checking Device Owner status..."
$ADB shell dpm list-owners 2>&1 | head -10

# Check if MainActivity is enabled/disabled
echo ""
echo "5. Checking MainActivity state..."
$ADB shell cmd package dump com.deploy_logics.user_device_locker 2>&1 | grep -A2 "MainActivity" | head -5

# Start logging
echo ""
echo "6. Starting logcat filter..."
echo "   Press Ctrl+C to stop"
echo ""
echo "   Now send an FCM notification with:"
echo "   {\"device\": \"show_app\"}"
echo "   or"
echo "   {\"device\": \"lock\"}"
echo ""
echo "=========================================="

# Clear logs and start monitoring
$ADB logcat -c
$ADB logcat | grep -E "(MyFirebaseMsgService|LockOverlayService|BootCompletedReceiver|DPMHelper|FATAL|Exception)"

