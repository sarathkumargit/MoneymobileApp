#!/bin/bash
# Builds the MoneyMobile app and publishes it to the website's "Install App" downloads.
# Double-click after changing the Android app. (Expects "Moneymobile WEB" next to this folder.)
set -e
cd "$(dirname "$0")"
WEB="../Moneymobile WEB/setup-kit"
echo "Building the app..."
./gradlew assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
cp "$APK" scripts/MoneyMobile.apk
mkdir -p "$WEB"
cp "$APK" "$WEB/MoneyMobile.apk"
cp scripts/setup-mac.command scripts/setup-windows.bat scripts/README.txt "$WEB/"
# Version shown on the website's Install App page (read from the app's build file).
VERSION=$(grep -E 'versionName *=' app/build.gradle.kts | head -1 | sed -E 's/.*"(.*)".*/\1/')
echo "$VERSION" > "$WEB/version.txt"
echo "Version: $VERSION"
echo ""
echo "Published. Shop admins and employees now download this build from Install App."
read -n 1 -s -r -p "Press any key to close."
