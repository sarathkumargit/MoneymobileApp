package com.moneymobile.installment.admin

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.util.Log

class DevicePolicyController(private val context: Context) {

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    val adminComponent: ComponentName =
        ComponentName(context, DeviceAdminReceiver::class.java)

    val isDeviceOwner: Boolean
        get() = dpm.isDeviceOwnerApp(context.packageName)

    // ─────────────────────────────────────────────────────────
    // INITIAL SETUP — called once after provisioning
    // ─────────────────────────────────────────────────────────

    fun applyAllRestrictions() {
        if (!isDeviceOwner) {
            Log.e("DPC", "Not Device Owner — cannot apply restrictions")
            return
        }
        blockOwnUninstall()
        // We no longer block mobile-network config — the customer must be able to use their
        // SIM's data. SIM SWAPS are handled by detection + instant lock, not by blocking.
        clearDataRestrictions()
        blockFactoryReset()
        // Accounts are NOT blocked: the customer must be able to add their own Google account
        // so the Play Store works. (There is no shop account on the phone to protect.)
        allowAccountModification()
        blockSafeMode()
        lockClockSettings()
        blockTamperPaths()
        grantLocationPermissions()
        grantNotificationPermission()
        // Status bar is only disabled while LOCKED (see enterLockedMode) — disabling it
        // permanently would hide notifications from a customer who is paying on time.
        enableKioskAllowlist()
        Log.i("DPC", "All restrictions applied")
    }

    // ─────────────────────────────────────────────────────────
    // INDIVIDUAL POLICIES
    // ─────────────────────────────────────────────────────────

    /**
     * Close the "side doors": a second user / guest profile (which has no lock), installing
     * apps from unknown sources, and Clear data / Force stop on this app (which would wipe the
     * saved lock state and stop the service). Each call is isolated so one unsupported
     * restriction on an old phone never blocks the others.
     */
    fun blockTamperPaths() {
        if (!isDeviceOwner) return
        listOf(
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_USER_SWITCH,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES
        ).forEach { r ->
            try { dpm.addUserRestriction(adminComponent, r) } catch (e: Exception) { Log.w("DPC", "restrict $r: ${e.message}") }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try { dpm.setUserControlDisabledPackages(adminComponent, listOf(context.packageName)) }
            catch (e: Exception) { Log.w("DPC", "user-control lock: ${e.message}") }
        }
    }

    /** Prevent user from uninstalling this app */
    fun blockOwnUninstall() {
        if (!isDeviceOwner) return
        try {
            dpm.setUninstallBlocked(adminComponent, context.packageName, true)
            Log.i("DPC", "Uninstall blocked")
        } catch (e: Exception) {
            Log.e("DPC", "blockOwnUninstall failed: ${e.message}")
        }
    }

    /** Block SIM configuration changes — user cannot swap SIM */
    fun blockSIMChange() {
        if (!isDeviceOwner) return
        try {
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_AIRPLANE_MODE)
            Log.i("DPC", "SIM change blocked")
        } catch (e: Exception) {
            Log.e("DPC", "blockSIMChange failed: ${e.message}")
        }
    }

    /** Undo any mobile-data / SIM / Wi-Fi restrictions so the customer can use their SIM normally. */
    fun clearDataRestrictions() {
        if (!isDeviceOwner) return
        listOf(
            UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
            UserManager.DISALLOW_AIRPLANE_MODE,
            UserManager.DISALLOW_CONFIG_WIFI
        ).forEach { r ->
            try { dpm.clearUserRestriction(adminComponent, r) } catch (e: Exception) { Log.w("DPC", "clear $r: ${e.message}") }
        }
        Log.i("DPC", "Mobile-data / SIM / Wi-Fi restrictions cleared")
    }

    /** Prevent user from factory resetting device */
    fun blockFactoryReset() {
        if (!isDeviceOwner) return
        try {
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_FACTORY_RESET)
            Log.i("DPC", "Factory reset blocked")
        } catch (e: Exception) {
            Log.e("DPC", "blockFactoryReset failed: ${e.message}")
        }
    }

    /** Allow adding/removing accounts so the customer can sign in to Google and use the Play Store. */
    fun allowAccountModification() {
        if (!isDeviceOwner) return
        try {
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_MODIFY_ACCOUNTS)
            Log.i("DPC", "Account modification allowed (Play Store sign-in enabled)")
        } catch (e: Exception) {
            Log.e("DPC", "allowAccountModification failed: ${e.message}")
        }
    }

    /** Block safe mode boot */
    fun blockSafeMode() {
        if (!isDeviceOwner) return
        try {
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_SAFE_BOOT)
        } catch (e: Exception) {
            Log.e("DPC", "blockSafeMode failed: ${e.message}")
        }
    }

    /**
     * Keep date & time automatic and un-editable. The offline overdue lock trusts the phone's clock
     * between check-ins, so the customer must not be able to wind the date back to dodge it.
     */
    fun lockClockSettings() {
        if (!isDeviceOwner) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.setAutoTimeEnabled(adminComponent, true)
            } else {
                @Suppress("DEPRECATION")
                dpm.setAutoTimeRequired(adminComponent, true)
            }
        } catch (e: Exception) { Log.w("DPC", "auto time: ${e.message}") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_DATE_TIME) } catch (e: Exception) { Log.w("DPC", "date/time lock: ${e.message}") }
        }
    }

    /** Disable status bar pull-down (hides quick settings panel) */
    fun disableStatusBarExpansion() {
        if (!isDeviceOwner) return
        try {
            dpm.setStatusBarDisabled(adminComponent, true)
        } catch (e: Exception) {
            Log.w("DPC", "setStatusBarDisabled not supported: ${e.message}")
        }
    }

    /** Disable ADB debugging after enrollment for security */
    fun disableADB() {
        if (!isDeviceOwner) return
        try {
            dpm.setGlobalSetting(adminComponent, Settings.Global.ADB_ENABLED, "0")
            Log.i("DPC", "ADB disabled")
        } catch (e: Exception) {
            Log.e("DPC", "disableADB failed: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────
    // PAYMENT OVERDUE — RESTRICT DEVICE
    // ─────────────────────────────────────────────────────────

    /**
     * Allow THIS app to run in Lock Task (kiosk) mode. Required before
     * LockedActivity can call startLockTask() to become un-exitable.
     * Safe to call repeatedly.
     */
    fun enableKioskAllowlist() {
        if (!isDeviceOwner) return
        try {
            // Our app + the phone's dialer / in-call screen / SMS app. Anything not in this
            // list cannot be opened while the kiosk is active, so without these the
            // "Call shop" / "SMS shop" buttons and incoming calls would not work.
            val packages = (listOf(context.packageName) + communicationPackages()).distinct()
            dpm.setLockTaskPackages(adminComponent, packages.toTypedArray())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // While LOCKED: no Home, no Recents, no notification shade and NO power menu —
                // holding the power key does nothing, so the customer can't Power off / Restart
                // from the screen. (Only applies inside the lock screen; an unlocked phone is normal.)
                // The hardware forced restart (hold Power + Volume Down ~10 s) is handled by the
                // phone's chip, not Android, so no app can block it — the phone simply comes
                // straight back to this lock screen after it (lock screen is forced as Home).
                dpm.setLockTaskFeatures(adminComponent, DevicePolicyManager.LOCK_TASK_FEATURE_NONE)
            }
            Log.i("DPC", "Lock task allowlist: $packages")
        } catch (e: Exception) {
            Log.e("DPC", "enableKioskAllowlist failed: ${e.message}")
        }
    }

    /** Grant location permissions to ourselves (Device Owner can, with no user prompt). */
    fun grantLocationPermissions() {
        if (!isDeviceOwner) return
        try {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION).forEach { perm ->
                dpm.setPermissionGrantState(adminComponent, context.packageName, perm, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
            Log.i("DPC", "Location permissions granted")
        } catch (e: Exception) {
            Log.w("DPC", "grantLocationPermissions failed: ${e.message}")
        }
    }

    /** Android 13+ asks the customer before showing notifications; as Device Owner we grant it silently
     *  so the "your phone will lock on ..." payment reminders always appear. */
    fun grantNotificationPermission() {
        if (!isDeviceOwner || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            dpm.setPermissionGrantState(adminComponent, context.packageName, Manifest.permission.POST_NOTIFICATIONS,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
        } catch (e: Exception) { Log.w("DPC", "grantNotificationPermission failed: ${e.message}") }
    }

    /** Packages needed so the customer can still phone / text while locked. */
    private fun communicationPackages(): List<String> {
        val pm = context.packageManager
        val found = mutableSetOf<String>()
        try {
            (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage?.let { found += it }
        } catch (e: Exception) {}
        try { Telephony.Sms.getDefaultSmsPackage(context)?.let { found += it } } catch (e: Exception) {}
        listOf(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:0")),
            Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:0"))
        ).forEach { intent ->
            try {
                pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
                    .forEach { found += it.activityInfo.packageName }
            } catch (e: Exception) {}
        }
        // In-call UI / telecom packages differ per OEM; include whichever are installed.
        listOf(
            "com.android.server.telecom", "com.android.phone", "com.android.incallui",
            "com.google.android.dialer", "com.android.dialer",
            "com.samsung.android.incallui", "com.samsung.android.dialer",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms"
        ).forEach { pkg ->
            try { pm.getPackageInfo(pkg, 0); found += pkg } catch (e: Exception) {}
        }
        return found.toList()
    }

    // ─────────────────────────────────────────────────────────
    // LOCK / UNLOCK — single place for both the command and the
    // heartbeat reconciliation, so they can never drift apart.
    // ─────────────────────────────────────────────────────────

    /**
     * Kiosk lock: only MoneyMobile (balance + payment info) plus the dialer and SMS app.
     * Calls are deliberately NOT blocked — the customer must be able to call the shop.
     */
    fun enterLockedMode() {
        if (!isDeviceOwner) {
            Log.w("DPC", "Not Device Owner — lock screen will show but the user can leave it")
            return
        }
        enableKioskAllowlist()
        disableKeyguard()
        disableStatusBarExpansion()
        lockUsbPort()
        try { dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_OUTGOING_CALLS) } catch (e: Exception) {}
        // Locking must NEVER cut the phone off from the shop: Wi-Fi is how the Unlock arrives
        // (especially after a SIM is removed). Keep Wi-Fi settings allowed + Wi-Fi switched on,
        // and stop airplane mode (reachable from the power menu) from switching it off.
        try { dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_WIFI) } catch (e: Exception) {}
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_AIRPLANE_MODE) } catch (e: Exception) {}
        }
        com.moneymobile.installment.util.WifiHelper.ensureOn(context)
        setLockedScreenAsHome(true)
    }

    /**
     * While the phone is locked, the USB port is charge-only: no file transfer, no USB debugging
     * (ADB) and, on Android 12+ phones that support it, no USB data at all. The customer can't use a
     * computer to reach the phone while it is restricted. Everything is lifted again on unlock, so
     * the shop can still update the app over USB afterwards.
     */
    private fun lockUsbPort() {
        if (!isDeviceOwner) return
        listOf(UserManager.DISALLOW_USB_FILE_TRANSFER, UserManager.DISALLOW_DEBUGGING_FEATURES).forEach { r ->
            try { dpm.addUserRestriction(adminComponent, r) } catch (e: Exception) { Log.w("DPC", "add $r: ${e.message}") }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { dpm.setUsbDataSignalingEnabled(false) } catch (e: Exception) { Log.w("DPC", "usb data off: ${e.message}") }
        }
    }

    private fun unlockUsbPort() {
        if (!isDeviceOwner) return
        listOf(UserManager.DISALLOW_USB_FILE_TRANSFER, UserManager.DISALLOW_DEBUGGING_FEATURES).forEach { r ->
            try { dpm.clearUserRestriction(adminComponent, r) } catch (e: Exception) { Log.w("DPC", "clear $r: ${e.message}") }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { dpm.setUsbDataSignalingEnabled(true) } catch (e: Exception) { Log.w("DPC", "usb data on: ${e.message}") }
        }
    }

    fun exitLockedMode() {
        unlockUsbPort()
        if (isDeviceOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            try { dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_AIRPLANE_MODE) } catch (e: Exception) {}
        }
        restoreDeviceAccess()
        enableStatusBar()
        enableKeyguard()
        setLockedScreenAsHome(false)
    }

    /**
     * While locked, the lock screen is forced as the phone's HOME app. Lock-task mode does not
     * survive a reboot; this makes Android open the lock screen straight after boot (and on any
     * Home press) instead of the normal launcher. The alias is disabled when unlocked so the
     * customer's normal launcher is untouched.
     */
    private fun setLockedScreenAsHome(locked: Boolean) {
        if (!isDeviceOwner) return
        val alias = ComponentName(context.packageName, "com.moneymobile.installment.ui.LockedHomeAlias")
        try {
            if (locked) {
                context.packageManager.setComponentEnabledSetting(
                    alias, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
                val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addCategory(Intent.CATEGORY_DEFAULT)
                }
                dpm.addPersistentPreferredActivity(adminComponent, filter, alias)
            } else {
                dpm.clearPackagePersistentPreferredActivities(adminComponent, context.packageName)
                context.packageManager.setComponentEnabledSetting(
                    alias, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
            }
        } catch (e: Exception) {
            Log.w("DPC", "setLockedScreenAsHome($locked) failed: ${e.message}")
        }
    }

    /** True if this package is allowed to enter lock task (kiosk) mode. */
    fun isKioskAllowed(): Boolean {
        if (!isDeviceOwner) return false
        return try {
            dpm.isLockTaskPermitted(context.packageName)
        } catch (e: Exception) {
            false
        }
    }

    /** Disable the PIN/pattern keyguard so the phone goes straight into the kiosk. */
    fun disableKeyguard() {
        if (!isDeviceOwner) return
        try {
            dpm.setKeyguardDisabled(adminComponent, true)
            Log.i("DPC", "Keyguard disabled")
        } catch (e: Exception) {
            Log.w("DPC", "disableKeyguard failed: ${e.message}")
        }
    }

    /** Restore the normal keyguard after the restriction is removed. */
    fun enableKeyguard() {
        if (!isDeviceOwner) return
        try {
            dpm.setKeyguardDisabled(adminComponent, false)
            Log.i("DPC", "Keyguard restored")
        } catch (e: Exception) {
            Log.w("DPC", "enableKeyguard failed: ${e.message}")
        }
    }

    /** Lock device screen immediately */
    fun lockDevice() {
        if (!isDeviceOwner) return
        try {
            dpm.lockNow()
            Log.i("DPC", "Device locked")
        } catch (e: Exception) {
            Log.e("DPC", "lockDevice failed: ${e.message}")
        }
    }

    /** Block outgoing calls (payment overdue restriction) */
    fun blockOutgoingCalls() {
        if (!isDeviceOwner) return
        try {
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_OUTGOING_CALLS)
            Log.i("DPC", "Outgoing calls blocked")
        } catch (e: Exception) {
            Log.e("DPC", "blockOutgoingCalls failed: ${e.message}")
        }
    }

    /** Block internet/data (payment overdue) */
    fun blockDataUsage() {
        if (!isDeviceOwner) return
        try {
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS)
            dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_WIFI)
            Log.i("DPC", "Data usage blocked")
        } catch (e: Exception) {
            Log.e("DPC", "blockDataUsage failed: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────
    // PAYMENT CONFIRMED — RESTORE DEVICE
    // ─────────────────────────────────────────────────────────

    /** Restore call and data access after payment confirmed */
    fun restoreDeviceAccess() {
        if (!isDeviceOwner) return
        try {
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_OUTGOING_CALLS)
            dpm.clearUserRestriction(adminComponent, UserManager.DISALLOW_CONFIG_WIFI)
            // Keep SIM change block permanently
            Log.i("DPC", "Device access restored")
        } catch (e: Exception) {
            Log.e("DPC", "restoreDeviceAccess failed: ${e.message}")
        }
    }

    /**
     * FINAL release once the phone is fully paid. Removes every restriction, hands back
     * normal control, and gives up Device Owner so the customer can uninstall MoneyMobile
     * and factory-reset the phone. After clearDeviceOwnerApp() this app can no longer manage
     * the device — this is intentional and irreversible without re-provisioning at the shop.
     */
    fun releaseFully() {
        if (!isDeviceOwner) {
            Log.w("DPC", "releaseFully: not Device Owner, nothing to tear down")
            return
        }
        // 1. Leave the kiosk and restore the normal launcher / keyguard / status bar.
        exitLockedMode()
        // 2. Drop every user restriction we ever set (SIM, factory reset, accounts, safe boot...).
        listOf(
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_MODIFY_ACCOUNTS,
            UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
            UserManager.DISALLOW_CONFIG_WIFI,
            UserManager.DISALLOW_AIRPLANE_MODE,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_OUTGOING_CALLS,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_USB_FILE_TRANSFER,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_ADD_USER,
            UserManager.DISALLOW_USER_SWITCH,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES
        ).forEach { r ->
            try { dpm.clearUserRestriction(adminComponent, r) } catch (e: Exception) { Log.w("DPC", "clear $r: ${e.message}") }
        }
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            try { dpm.setUserControlDisabledPackages(adminComponent, emptyList()) } catch (e: Exception) { Log.w("DPC", "user-control release: ${e.message}") }
        }
        try { @Suppress("DEPRECATION") dpm.setAutoTimeRequired(adminComponent, false) } catch (e: Exception) { Log.w("DPC", "auto time release: ${e.message}") }
        // 3. Allow uninstall of this app again.
        try { dpm.setUninstallBlocked(adminComponent, context.packageName, false) } catch (e: Exception) { Log.w("DPC", "unblock uninstall: ${e.message}") }
        // 4. Clear the kiosk allowlist.
        try { dpm.setLockTaskPackages(adminComponent, arrayOf()) } catch (e: Exception) { Log.w("DPC", "clear lock task: ${e.message}") }
        // 5. Finally give up Device Owner. MUST be last — after this, no privileged call works.
        try {
            dpm.clearDeviceOwnerApp(context.packageName)
            Log.i("DPC", "Device Owner relinquished — phone released to customer")
        } catch (e: Exception) {
            Log.e("DPC", "clearDeviceOwnerApp failed: ${e.message}")
        }
    }

    /** Re-enable status bar after restoration */
    fun enableStatusBar() {
        if (!isDeviceOwner) return
        try {
            dpm.setStatusBarDisabled(adminComponent, false)
        } catch (e: Exception) {
            Log.w("DPC", "enableStatusBar failed: ${e.message}")
        }
    }
}
