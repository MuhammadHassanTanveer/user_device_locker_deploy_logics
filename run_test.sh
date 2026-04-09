#!/bin/bash

# =====================================================
# Device Locker ADB Test Script
# Run this in Terminal: ./run_test.sh
# =====================================================

ADB="/Users/mac/Library/Android/sdk/platform-tools/adb"
DEVICE="R9YRB05M9CN"
PACKAGE="com.deploy_logics.user_device_locker"

echo "=============================================="
echo "  Device Locker ADB Test"
echo "=============================================="
echo ""

# 1. List devices
echo "1. Connected devices:"
$ADB devices -l
echo ""

# 2. Check app installed
echo "2. Checking if app is installed..."
$ADB -s $DEVICE shell pm list packages | grep deploy
echo ""

# 3. Check device owner
echo "3. Device Owner status:"
$ADB -s $DEVICE shell dpm list-owners
echo ""

# 4. Enable MainActivity (if hidden)
echo "4. Enabling MainActivity..."
$ADB -s $DEVICE shell pm enable $PACKAGE/.MainActivity
echo ""

# 5. Launch app
echo "5. Launching app..."
$ADB -s $DEVICE shell am start -n $PACKAGE/.MainActivity
echo ""

# 6. Start log monitoring
echo "6. Starting log monitoring (Press Ctrl+C to stop)..."
echo "   Now send an FCM notification!"
echo ""
echo "   Example FCM payload:"
echo '   {"device": "show_app"}'
echo '   {"device": "lock"}'
echo '   {"device": "enable_factory_reset"}'
echo ""
echo "=============================================="
$ADB -s $DEVICE logcat -c
$ADB -s $DEVICE logcat | grep -iE "(MyFirebaseMsgService|LockOverlayService|DPMHelper|deploy_logics|FCM|firebase)"

