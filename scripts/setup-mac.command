#!/bin/bash
# MoneyMobile — one-click phone setup (macOS). Double-click to run.
# Works for BOTH:  a new / factory-reset phone  -> installs, turns on management, registers it
#                  a phone that's already set up -> only updates the app (keeps customer + lock status)
set -e
cd "$(dirname "$0")"

echo ""
echo " ====================================================="
echo "   MoneyMobile — Phone Setup"
echo " ====================================================="
echo ""

# ── config ──────────────────────────────────────────────
if [ ! -f config.txt ]; then
  echo "[ERROR] config.txt is missing. Download it from the website (Settings > Device setup)"
  echo "        and keep it in this folder."
  read -n 1 -s -r -p "Press any key to close."; exit 1
fi
SHOP_ID=""; SHOP_SECRET=""; SERVER_URL=""
while IFS='=' read -r k v; do
  k="$(echo "$k" | tr -d ' \r')"; v="$(echo "$v" | tr -d ' \r')"
  [ "$k" = "SHOP_ID" ] && SHOP_ID="$v"
  [ "$k" = "SHOP_SECRET" ] && SHOP_SECRET="$v"
  [ "$k" = "SERVER_URL" ] && SERVER_URL="$v"
done < config.txt
if [ -z "$SHOP_ID" ] || [ -z "$SHOP_SECRET" ]; then
  echo "[ERROR] config.txt is missing SHOP_ID or SHOP_SECRET."
  read -n 1 -s -r -p "Press any key to close."; exit 1
fi

if [ ! -f MoneyMobile.apk ]; then
  echo "[ERROR] MoneyMobile.apk is missing from this folder."
  read -n 1 -s -r -p "Press any key to close."; exit 1
fi

# ── adb (download platform-tools if missing) ────────────
ADB="adb"
if ! command -v adb >/dev/null 2>&1; then
  if [ -x ./platform-tools/adb ]; then
    ADB="./platform-tools/adb"
  else
    echo "[1/5] Android tools not found — downloading (one time)..."
    curl -fsSL -o platform-tools.zip "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip"
    unzip -oq platform-tools.zip
    ADB="./platform-tools/adb"
    echo "[OK] Tools ready."
  fi
fi

echo ""
echo "[2/5] Waiting for the phone (plug in via USB, allow USB debugging)..."
"$ADB" wait-for-device
STATE=$("$ADB" get-state 2>/dev/null || echo unknown)
if [ "$STATE" != "device" ]; then
  echo "[ERROR] Phone not ready (state: $STATE). Enable USB Debugging and tap Allow on the phone."
  read -n 1 -s -r -p "Press any key to close."; exit 1
fi
BRAND=$("$ADB" shell getprop ro.product.manufacturer 2>/dev/null | tr -d '\r')
MODEL=$("$ADB" shell getprop ro.product.model 2>/dev/null | tr -d '\r')
echo "[OK] Connected: $BRAND $MODEL"

# Already managed by MoneyMobile? Then this run is just an app UPDATE — registering again would
# give the phone a new identity and break its link to the customer.
if "$ADB" shell "dumpsys device_policy | grep -A3 'Device Owner'" 2>/dev/null | tr -d '\r' | grep -q "com.moneymobile.installment"; then
  OLDV=$("$ADB" shell dumpsys package com.moneymobile.installment 2>/dev/null | tr -d '\r' | grep -m1 versionName | sed 's/.*=//')
  echo ""
  echo "[3/3] This phone is already set up (app v${OLDV:-?}) — updating the app only..."
  OUT=$("$ADB" install -r -g MoneyMobile.apk 2>&1 | tr -d '\r'); echo "$OUT"
  echo ""
  echo " ====================================================="
  if echo "$OUT" | grep -qi "Success"; then
    # Refresh the phone's server address in case this computer's Wi-Fi IP changed since setup
    # (a stale address is the usual reason an updated phone still shows "offline").
    IFACE=$(route -n get default 2>/dev/null | awk '/interface:/{print $2}')
    MAC_IP=$(ipconfig getifaddr "${IFACE:-en0}" 2>/dev/null || ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)
    URL="$SERVER_URL"; [ -z "$URL" ] && [ -n "$MAC_IP" ] && URL="http://$MAC_IP:3000/api"
    if [ -n "$URL" ]; then
      "$ADB" shell am start -n com.moneymobile.installment/.ui.EnrollmentActivity --es action "UPDATE_SERVER_URL" --es server_url "$URL" >/dev/null 2>&1 || true
      echo "   Server address refreshed: $URL"
    else
      "$ADB" shell am start -n com.moneymobile.installment/.ui.SplashActivity >/dev/null 2>&1 || true
    fi
    NEWV=$("$ADB" shell dumpsys package com.moneymobile.installment 2>/dev/null | tr -d '\r' | grep -m1 versionName | sed 's/.*=//')
    echo "   DONE — $BRAND $MODEL updated to v${NEWV:-new}."
    echo "   Customer, registration and lock status are unchanged."
    echo ""
    echo "   Phone still shows Pending, or you deleted it on the website?"
    read -t 10 -n 1 -s -r -p "   Press R within 10 seconds to register it again (or wait to skip): " REREG || true
    echo ""
    if [ "$REREG" = "r" ] || [ "$REREG" = "R" ]; then
      "$ADB" shell am start -n com.moneymobile.installment/.ui.EnrollmentActivity \
        --es action "REGISTER_DEVICE" --es shop_id "$SHOP_ID" --es enroll_secret "$SHOP_SECRET" \
        ${URL:+--es server_url "$URL"} >/dev/null 2>&1 || true
      echo "   Registration sent. The phone appears in Devices on the website in about a minute"
      echo "   (it must have internet). Then assign it to the customer again."
    fi
  elif echo "$OUT" | grep -qi "DOWNGRADE"; then
    echo "   Already up to date — the phone has this version or newer."
  else
    echo "   [ERROR] Update failed (see the message above)."
  fi
  echo " ====================================================="
  echo ""
  read -n 1 -s -r -p "Press any key to close."; exit 0
fi

echo ""
echo "[3/5] New phone — installing the app..."
"$ADB" install -r -g MoneyMobile.apk
echo "[OK] Installed."

echo ""
echo "[4/5] Enabling full management (Device Owner)..."
PKG="com.moneymobile.installment/.admin.DeviceAdminReceiver"
if "$ADB" shell "dumpsys device_policy | grep -A3 'Device Owner'" 2>/dev/null | tr -d '\r' | grep -q "com.moneymobile.installment"; then
  echo "[OK] Already set."
else
  OUT=$("$ADB" shell dpm set-device-owner "$PKG" 2>&1 | tr -d '\r'); echo "$OUT"
  if ! echo "$OUT" | grep -qi "Success"; then
    echo ""
    echo "[ERROR] Could not enable management. Usually an account is still signed in."
    if echo "$OUT" | grep -qi "account"; then
      echo " Accounts on the phone (remove these in Settings > Passwords & accounts, WhatsApp can stay):"
      "$ADB" shell dumpsys account 2>/dev/null | tr -d '\r' | grep "Account {" | grep -v "Session:" | sort -u | sed 's/^ */   - /'
    fi
    read -n 1 -s -r -p "Press any key to close."; exit 1
  fi
fi

echo ""
echo "[5/5] Registering the phone with the shop..."
# Server address for the phone. config.txt may set SERVER_URL (e.g. http://192.168.1.20:3000/api).
# Otherwise, if the MoneyMobile server is running on THIS Mac, use this Mac's current Wi-Fi IP —
# so a changed IP address no longer breaks the phone.
# 2-second connect test (macOS nc); falls back to bash /dev/tcp.
port_open() {
  if command -v nc >/dev/null 2>&1; then nc -z -G 2 "$1" "$2" >/dev/null 2>&1
  else (exec 3<>"/dev/tcp/$1/$2") >/dev/null 2>&1; fi
}
if [ -z "$SERVER_URL" ]; then
  # Always hand the phone THIS Mac's current Wi-Fi IP (the server normally runs here).
  IFACE=$(route -n get default 2>/dev/null | awk '/interface:/{print $2}')
  MAC_IP=$(ipconfig getifaddr "${IFACE:-en0}" 2>/dev/null || ipconfig getifaddr en0 2>/dev/null || ipconfig getifaddr en1 2>/dev/null || true)
  [ -n "$MAC_IP" ] && SERVER_URL="http://$MAC_IP:3000/api"
  # Port check only (instant) — an HTTP request can take 10s+ while "npm run dev" compiles a page.
  if ! port_open 127.0.0.1 3000 && ! port_open localhost 3000; then
    echo "      [!] Nothing is running on port 3000 on this Mac. Start the website"
    echo "          (npm run dev or npm start) — the phone will connect as soon as it is up."
  fi
fi
if [ -n "$SERVER_URL" ]; then
  echo "      Server for the phone: $SERVER_URL"
  HOSTPORT=$(echo "$SERVER_URL" | sed -E 's#^https?://([^/]+).*#\1#')
  H=${HOSTPORT%%:*}; P=${HOSTPORT##*:}; [ "$P" = "$HOSTPORT" ] && P=80
  if ! port_open "$H" "$P"; then
    echo "      [!] $H:$P is not reachable on the network. If the server runs on this Mac:"
    echo "          System Settings > Network > Firewall: allow incoming connections for 'node'."
  fi
else
  echo "      [!] Could not find this Mac's Wi-Fi IP. Is the Mac connected to Wi-Fi?"
fi
"$ADB" logcat -c >/dev/null 2>&1 || true
START_OUT=$("$ADB" shell am start -W -n com.moneymobile.installment/.ui.EnrollmentActivity \
  --es action "REGISTER_DEVICE" --es shop_id "$SHOP_ID" --es enroll_secret "$SHOP_SECRET" \
  ${SERVER_URL:+--es server_url "$SERVER_URL"} 2>&1 | tr -d '\r')
if echo "$START_OUT" | grep -qi "error"; then
  echo "[ERROR] Could not open the MoneyMobile app on the phone:"
  echo "$START_OUT"
  read -n 1 -s -r -p "Press any key to close."; exit 1
fi

# The app saves the shop details immediately and reports the result in the log (tag MMEnroll).
RESULT=""
SAID_WAITING=0
for i in $(seq 1 25); do
  LOG=$("$ADB" logcat -d -s MMEnroll:I MMEnroll:W 2>/dev/null | tr -d '\r')
  if echo "$LOG" | grep -q "RESULT=OK"; then RESULT="OK"; break; fi
  if echo "$LOG" | grep -q "RESULT=FAIL"; then RESULT="FAIL"; break; fi
  if echo "$LOG" | grep -q "RESULT=NETWORK" && [ $SAID_WAITING -eq 0 ]; then
    echo "      Phone can't reach the server yet — it keeps retrying, waiting..."
    SAID_WAITING=1
  fi
  sleep 2
done

echo ""
echo " ====================================================="
if [ "$RESULT" = "OK" ]; then
  echo "   DONE — $BRAND $MODEL is registered to your shop."
  echo "   Find it by its IMEI on the website and assign it to"
  echo "   a customer. You can unplug the phone now."
elif [ "$RESULT" = "FAIL" ]; then
  echo "   [ERROR] The server refused the registration:"
  echo "$LOG" | grep -o "RESULT=FAIL.*" | tail -1 | sed 's/RESULT=FAIL /   /'
  echo "   Download a fresh config.txt from the website"
  echo "   (Settings > Device setup) and run this again."
else
  echo "   ALMOST DONE — the app is installed and the shop ID is"
  echo "   SAVED on the phone, but it can't reach the server yet."
  LASTERR=$(echo "$LOG" | grep -o "RESULT=NETWORK.*" | tail -1 | sed 's/RESULT=NETWORK //')
  [ -n "$LASTERR" ] && echo "   Phone says: $LASTERR"
  echo "   Connect the phone to the same Wi-Fi as the server and"
  echo "   make sure the server is running. The phone registers"
  echo "   by itself — no need to type anything or run this again."
fi
echo " ====================================================="
echo ""
read -n 1 -s -r -p "Press any key to close."
