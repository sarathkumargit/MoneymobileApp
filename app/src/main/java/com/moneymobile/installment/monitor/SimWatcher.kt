package com.moneymobile.installment.monitor

import android.content.Context
import android.content.Intent
import android.telephony.TelephonyManager
import android.util.Log
import com.moneymobile.installment.admin.DevicePolicyController
import com.moneymobile.installment.service.ManagementService
import com.moneymobile.installment.ui.LockedActivity
import com.moneymobile.installment.util.AppPreferences
import com.moneymobile.installment.util.DeviceInfoCollector

/**
 * Detects a SIM being REMOVED or SWAPPED and locks the phone immediately.
 *
 * Called from three places so it works on every Android version:
 *  - ManagementService checks every few seconds (Android 8+ rarely delivers SIM broadcasts
 *    to manifest receivers, so the old receiver alone often never fired),
 *  - a SIM_STATE_CHANGED receiver registered at runtime by the service (instant),
 *  - the manifest SimChangeReceiver (older Android versions).
 *
 * Each SIM state is reported only once ("alerted" state), so after staff unlock the phone it
 * is not re-locked in a loop. Putting the original SIM back clears the alert.
 */
object SimWatcher {
    private const val TAG = "SimWatcher"
    private const val ABSENT = "ABSENT"

    @Synchronized
    fun check(context: Context) {
        if (!AppPreferences.isEnrolled(context) || AppPreferences.isReleased(context)) return
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return

        val current: String = when (tm.simState) {
            TelephonyManager.SIM_STATE_ABSENT -> ABSENT
            TelephonyManager.SIM_STATE_READY -> identity(context, tm) ?: return
            else -> return // UNKNOWN / NOT_READY / PIN_REQUIRED ... are transient — wait
        }

        val approved = AppPreferences.getLastKnownIccid(context)
        if (approved == null) {
            // First SIM seen on this phone — remember it as the approved one.
            if (current != ABSENT) AppPreferences.setLastKnownIccid(context, current)
            return
        }
        if (current == approved) {
            // Original SIM is in — clear any earlier alert.
            if (AppPreferences.getSimAlertState(context) != null) AppPreferences.setSimAlertState(context, null)
            return
        }
        if (AppPreferences.getSimAlertState(context) == current) return // already reported this state
        AppPreferences.setSimAlertState(context, current)

        val removed = current == ABSENT
        Log.w(TAG, if (removed) "SIM REMOVED — locking" else "SIM CHANGED — locking")
        lockNow(context)
        val type = if (removed) "SIM_REMOVED" else "SIM_CHANGE"
        val details = if (removed) "SIM card removed (approved SIM: $approved)" else "SIM changed: $approved → $current"
        // Stays pending (and the phone stays locked) until the server confirms it — see ManagementService.
        AppPreferences.setPendingSimEvent(context, "$type|$details")
        ManagementService.reportSecurityEvent(context, type, details)
    }

    /** "present" / "absent" for the heartbeat; null while the SIM is still initialising. */
    fun stateLabel(context: Context): String? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        return when (tm.simState) {
            TelephonyManager.SIM_STATE_ABSENT -> "absent"
            TelephonyManager.SIM_STATE_READY -> "present"
            else -> null
        }
    }

    /** ICCID when readable (Device Owner + READ_PHONE_STATE); otherwise the network operator code. */
    private fun identity(context: Context, tm: TelephonyManager): String? {
        DeviceInfoCollector.getSimIccid(context)?.takeIf { it.isNotBlank() }?.let { return it }
        val op = try { tm.simOperator } catch (e: Exception) { null }
        return op?.takeIf { it.isNotBlank() }?.let { "op:$it" }
    }

    private fun lockNow(context: Context) {
        AppPreferences.setDeviceLocked(context, true)
        try { DevicePolicyController(context).enterLockedMode() } catch (e: Exception) { Log.e(TAG, "enterLockedMode: ${e.message}") }
        try {
            context.startActivity(
                Intent(context, LockedActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            )
        } catch (e: Exception) { Log.e(TAG, "show lock screen: ${e.message}") }
    }
}
