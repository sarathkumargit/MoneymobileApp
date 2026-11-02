# MoneyMobile — Android Device Management App

Part of the Android Installment Management & Device Control System.

---

## What This App Does

- Installs as **Device Owner** via ADB during the sales process
- **Cannot be uninstalled** by the customer (enforced by Android OS)
- **Blocks SIM changes** — customer cannot swap their SIM card
- Sends **heartbeat every 4 hours** to the backend with device status
- Detects **SIM change attempts** and reports them to the server
- **Locks the device** when a payment becomes overdue (on command from backend)
- **Restores access** automatically after payment is confirmed

---

## Project Structure

```
app/src/main/java/com/moneymobile/installment/
├── admin/
│   ├── DeviceAdminReceiver.kt      ← Android Device Admin entry point
│   └── DevicePolicyController.kt  ← All restriction/unlock methods
├── api/
│   ├── ApiClient.kt               ← Retrofit HTTP client
│   └── ApiService.kt              ← API endpoint definitions
├── enrollment/
│   └── EnrollmentRepository.kt    ← Handles device registration with backend
├── model/
│   └── Models.kt                  ← All data classes
├── monitor/
│   ├── BootReceiver.kt            ← Starts service on device boot
│   └── SimChangeReceiver.kt       ← Detects SIM card changes
├── service/
│   ├── ManagementService.kt       ← Foreground service (always running)
│   └── HeartbeatWorker.kt         ← WorkManager job (4-hour heartbeat)
├── ui/
│   ├── SplashActivity.kt          ← Entry point / routing
│   ├── EnrollmentActivity.kt      ← Staff enrollment screen
│   ├── MainActivity.kt            ← Device status dashboard
│   └── LockedActivity.kt          ← Shown when device is restricted
└── util/
    ├── AppPreferences.kt          ← Encrypted local storage
    └── DeviceInfoCollector.kt     ← Reads IMEI, SIM, battery, etc.

scripts/
├── enroll_device.bat              ← Windows enrollment script
└── enroll_device.sh               ← Mac/Linux enrollment script
```

---

## Setup Instructions

### 1. Configure Backend URL

Edit `app/build.gradle.kts`:
```kotlin
buildConfigField("String", "BASE_URL", "\"https://your-backend.com/api\"")
```

### 2. Build the APK

```bash
./gradlew assembleRelease
```

Copy the output APK to `scripts/MoneyMobile.apk`.

### 3. Enroll a Device (Sales Workflow)

**Before running the script:**
- Factory reset the device
- Enable USB Debugging (Settings → Developer Options → USB Debugging)
- Do NOT sign into any Google account
- Connect device via USB

**Windows:**
```
Double-click scripts/enroll_device.bat
```

**Mac/Linux:**
```bash
cd scripts
./enroll_device.sh
```

The script will:
1. Install the APK
2. Set the app as Device Owner
3. Open the enrollment screen on the device
4. Staff enters the enrollment token from the web dashboard

### 4. What Happens After Enrollment

- App runs silently in the background (foreground service, low-priority notification)
- Sends heartbeat every 4 hours
- Backend can send `LOCK_DEVICE` or `UNLOCK_DEVICE` commands
- If device goes offline, commands are queued and executed on reconnect
- **Offline overdue lock:** the server sends the payment deadline (`overdue_lock_at`) and its clock
  (`server_time`) with every heartbeat. `util/OfflineLockGuard.kt` keeps them, and a deadline alarm +
  15-second watcher + boot check lock the phone at the deadline with NO internet. Time is measured as
  server time + uptime, and date/time settings are locked (Device Owner), so moving the date back does
  not help. A shop offline-unlock code is respected (not undone) until the next payment changes the deadline.
- **Lock-time display + reminders:** the dashboard shows "Phone will lock on <date> — in 4 days 3 h"
  (`ui/MainActivity.kt`, read from the stored deadline, so it works offline). `util/LockWarnings.kt`
  shows a notification 48 h and 24 h before the lock (once each per deadline, never after the lock).
  Notification permission is granted silently by Device Owner (`DevicePolicyController.grantNotificationPermission`).

---

## Backend API Expected

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/devices/enroll` | POST | Register device |
| `/api/devices/heartbeat` | POST | Status ping + receive commands |
| `/api/devices/security-event` | POST | SIM change / tamper reports |
| `/api/devices/command-ack` | POST | Confirm command executed |
| `/api/devices/:id/commands` | GET | Poll for pending commands |

---

## Requirements

- Android 8.0+ (API 26)
- Device Owner provisioning via ADB (requires no existing Google account)
- Backend API running at configured `BASE_URL`

---

## Notes

- SIM lock (`DISALLOW_SIM_GLOBALLY`) requires Android 9+ on most devices
- Factory reset block requires Device Owner privileges
- Device Owner can only be set ONCE. Remove it only with full factory reset.

## v1.7.0 — Apple-style interface
Screens follow Apple's Human Interface Guidelines: system Light/Dark (follows the phone), inset grouped
lists, system blue/green/red/orange, flat cards (no gradients, no emoji), large titles, sentence-case labels.
Colors live in `res/values/colors.xml` (light) and `res/values-night/colors.xml` (dark) as semantic names
(`bg_dark` = grouped background, `surface` = card, `text_primary/secondary/muted` = label levels).
Also: the phone registers itself again if the shop deleted its record (heartbeat 401 -> auto re-register).
Original screens are backed up in `.backup-2026-10-31-apple-ui/`.

## v1.10.0 — Online app updates
The app can now update itself over the internet when shop staff press **Update** on the website (see the web
project's ARCHITECTURE.md, "Online app updates"). Code: `update/AppUpdater.kt`, `update/AppUpdateWorker.kt`,
`update/InstallResultReceiver.kt`, `monitor/AppUpdatedReceiver.kt`. Phones on 1.9.x need one USB update to get this.
**Every release must raise `versionCode` and be signed with the same key** (release builds use the debug keystore at
`~/.android/debug.keystore` on the build Mac — losing it means no phone can ever be updated again without a reset).
Originals of changed files: `.backup-2026-11-02-app-updates/`.

## v1.10.1 — Per-shop reset protection status
Applies the shop's reset-protection Google account(s) only when the server signed the list (`CommandSig.frpOk`) and reports
what Android really holds (`DevicePolicyController.frpStatus`) on every check-in. Originals: `.backup-2026-11-03-reset-protection/`.
