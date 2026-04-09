#!/bin/bash

# =====================================================
# COMPLETE FIX SCRIPT FOR DEVICE LOCKER
# =====================================================
# Run this in a NEW Terminal window:
#   cd /Users/mac/StudioProjects/dcp_test
#   ./fix_and_install.sh
# =====================================================

set -e

ADB="/Users/mac/Library/Android/sdk/platform-tools/adb"
DEVICE="R9YRB05M9CN"
PACKAGE="com.deploy_logics.user_device_locker"
PROJECT_DIR="/Users/mac/StudioProjects/dcp_test"

echo "=============================================="
echo "  Device Locker - Fix & Install Script"
echo "=============================================="

cd "$PROJECT_DIR"

# Step 1: Build Release APK (with correct signing key)
echo ""
echo "Step 1: Building RELEASE APK with matching signature..."
flutter build apk --release

RELEASE_APK="$PROJECT_DIR/build/app/outputs/flutter-apk/app-release.apk"

if [ -f "$RELEASE_APK" ]; then
    echo "✅ Release APK built: $RELEASE_APK"
else
    echo "❌ Release APK not found!"
    echo "Trying alternative location..."
    RELEASE_APK=$(find "$PROJECT_DIR/build" -name "app-release.apk" -type f | head -1)
    if [ -z "$RELEASE_APK" ]; then
        echo "❌ No release APK found. Build failed?"
        exit 1
    fi
    echo "✅ Found APK: $RELEASE_APK"
fi

# Step 2: Install the release APK (should work because same signing key)
echo ""
echo "Step 2: Installing Release APK..."
$ADB -s $DEVICE install -r "$RELEASE_APK"

if [ $? -eq 0 ]; then
    echo "✅ APK installed successfully!"
else
    echo "❌ Installation failed!"
    echo ""
    echo "The signing keys might not match."
    echo "You may need to factory reset the device."
    echo ""
    echo "Try these commands manually:"
    echo "1. Enable factory reset via FCM (if the old app supports it)"
    echo "2. Or try: $ADB -s $DEVICE shell wipe data"
    exit 1
fi

# Step 3: Enable MainActivity (in case it was hidden)
echo ""
echo "Step 3: Enabling MainActivity..."
$ADB -s $DEVICE shell pm enable $PACKAGE/.MainActivity || true

# Step 4: Launch the app
echo ""
echo "Step 4: Launching app..."
$ADB -s $DEVICE shell am start -n $PACKAGE/.MainActivity

# Step 5: Start log monitoring
echo ""
echo "=============================================="
echo "  Installation Complete!"
echo "=============================================="
echo ""
echo "The app should now be running with the new code."
echo ""
echo "To test FCM notifications, send:"
echo '  {"device": "enable_factory_reset"}'
echo '  {"device": "show_app"}'
echo '  {"device": "lock"}'
echo ""
echo "Starting log monitor (Ctrl+C to stop)..."
echo ""

$ADB -s $DEVICE logcat -c
$ADB -s $DEVICE logcat | grep -iE "(MyFirebaseMsgService|LockOverlayService|DPMHelper)"

