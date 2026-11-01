#!/bin/bash
# MoneyMobile — collect phone diagnostics into diagnose-output.txt (next to this file).
cd "$(dirname "$0")"
OUT="$(pwd)/diagnose-output.txt"

ADB="adb"
if ! command -v adb >/dev/null 2>&1; then
  if [ -x ./platform-tools/adb ]; then ADB="./platform-tools/adb"
  else
    echo "Downloading Android tools (one time)..."
    curl -fsSL -o platform-tools.zip "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip" && unzip -oq platform-tools.zip
    ADB="./platform-tools/adb"
  fi
fi

echo "Waiting for the phone..."
"$ADB" wait-for-device
PKG=com.moneymobile.installment
IP=$(ipconfig getifaddr "$(route -n get default 2>/dev/null | awk '/interface:/{print $2}')" 2>/dev/null)
{
  echo "=== $(date)"
  echo "Mac IP: $IP"
  echo "Mac port 3000 open: $(nc -z -G 2 127.0.0.1 3000 >/dev/null 2>&1 && echo yes || echo NO)"
  echo; echo "=== Phone"
  "$ADB" shell getprop ro.product.model; "$ADB" shell getprop ro.build.version.release
  echo; echo "=== App install"
  "$ADB" shell dumpsys package $PKG | grep -E "versionName|firstInstallTime|lastUpdateTime" | head -5
  echo "App process: $("$ADB" shell pidof $PKG 2>/dev/null || echo 'NOT RUNNING')"
  echo; echo "=== Device owner"
  "$ADB" shell dumpsys device_policy | grep -A2 "Device Owner" | head -4
  echo; echo "=== SIM"
  "$ADB" shell getprop gsm.sim.state
  echo; echo "=== Phone -> Mac network test"
  [ -n "$IP" ] && "$ADB" shell "ping -c 2 -W 2 $IP" 2>&1 | tail -3
  echo; echo "=== App log (last 400 lines)"
  "$ADB" logcat -d -v time 2>/dev/null | grep -E "MMEnroll|ManagementService|SimWatcher|SimChange|EnrollmentActivity|AndroidRuntime|FATAL|moneymobile|okhttp|DPC|LocationCollector" | tail -400
} > "$OUT" 2>&1

echo ""
echo "Saved: $OUT"
echo "Tell Claude it's ready (it can read this file)."
read -n 1 -s -r -p "Press any key to close."
