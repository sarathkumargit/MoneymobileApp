package com.moneymobile.installment.monitor

import android.content.Context
import android.content.Intent
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.util.Log
import com.moneymobile.installment.admin.DevicePolicyController
import com.moneymobile.installment.service.ManagementService
import com.moneymobile.installment.ui.LockedActivity
import com.moneymobile.installment.util.AppPreferences
import com.moneymobile.installment.util.DeviceInfoCollector

/**
 * Detects a registered SIM being REMOVED or SWAPPED and locks the phone immediately.
 *
 * Dual-SIM aware: every SIM that is in the phone when it is registered is remembered (up to one per
 * slot). If ANY remembered SIM later goes missing — taken out, or replaced by a different card — the
 * phone locks. A SIM added afterwards into an empty slot is ignored, and moving a registered SIM to
 * the other slot is fine.
 *
 * Called from three places so it works on every Android version:
 *  - ManagementService checks every few seconds,
 *  - a SIM_STATE_CHANGED receiver registered at runtime by the service (instant),
 *  - the manifest SimChangeReceiver (older Android versions).
 *
 * Each missing-SIM state is reported only once ("alerted" state), so after staff unlock the phone it
 * is not re-locked in a loop. Putting the original SIM back clears the alert.
 */
object SimWatcher {
    private const val TAG = "SimWatcher"
    private const val SEP = "|"

    /** What is in the phone right now. unsure = a slot is still starting up / PIN-locked: don't judge yet. */
    private class Snapshot(val present: Set<String>, val unsure: Boolean)

    private fun snapshot(context: Context, tm: TelephonyManager): Snapshot {
        val slots = try { maxOf(1, tm.phoneCount) } catch (e: Exception) { 1 }
        val present = mutableSetOf<String>()
        var unsure = false
        for (slot in 0 until slots) {
            when (try { tm.getSimState(slot) } catch (e: Exception) { TelephonyManager.SIM_STATE_UNKNOWN }) {
                TelephonyManager.SIM_STATE_ABSENT -> {}
                TelephonyManager.SIM_STATE_READY -> {
                    val id = slotIdentity(context, tm, slot)
                    if (id == null) unsure = true else present += id
                }
                else -> unsure = true // UNKNOWN / NOT_READY / PIN_REQUIRED ... are transient — wait
            }
        }
        return Snapshot(present, unsure)
    }

    @Synchronized
    fun check(context: Context) {
        if (!AppPreferences.isEnrolled(context) || AppPreferences.isReleased(context)) return
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return
        val snap = snapshot(context, tm)

        val registered = registeredSims(context)
        if (registered.isEmpty()) {
            // First look since registration: remember every SIM that is in the phone now.
            if (!snap.unsure && snap.present.isNotEmpty()) {
                AppPreferences.setLastKnownIccid(context, snap.present.sorted().joinToString(SEP))
            }
            return
        }
        val missing = registered - snap.present
        if (missing.isEmpty()) {
            // Every registered SIM is in — clear any earlier alert.
            if (AppPreferences.getSimAlertState(context) != null) AppPreferences.setSimAlertState(context, null)
            return
        }
        if (snap.unsure) return // a slot is still initialising: wait before judging
        val state = "missing:" + missing.sorted().joinToString(SEP)
        if (AppPreferences.getSimAlertState(context) == state) return // already reported this state
        AppPreferences.setSimAlertState(context, state)

        val removed = snap.present.isEmpty()
        Log.w(TAG, "Registered SIM missing — locking ($state)")
        lockNow(context)
        val type = if (removed) "SIM_REMOVED" else "SIM_CHANGE"
        val details = "Registered SIM missing: ${missing.sorted().joinToString(", ")} (now in phone: ${snap.present.sorted().joinToString(", ").ifEmpty { "none" }})"
        // Stays pending (and the phone stays locked) until the server confirms it — see ManagementService.
        AppPreferences.setPendingSimEvent(context, "$type|$details")
        ManagementService.reportSecurityEvent(context, type, details)
    }

    private fun registeredSims(context: Context): Set<String> =
        AppPreferences.getLastKnownIccid(context)?.split(SEP)?.filter { it.isNotBlank() }?.toSet() ?: emptySet()

    /** "present" / "absent" for the heartbeat; null while a SIM is still initialising. */
    fun stateLabel(context: Context): String? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return null
        val snap = snapshot(context, tm)
        val registered = registeredSims(context)
        return when {
            registered.isNotEmpty() && (registered - snap.present).isNotEmpty() -> if (snap.unsure) null else "absent"
            snap.unsure -> null
            snap.present.isEmpty() -> "absent"
            else -> "present"
        }
    }

    /**
     * Identity of the SIM in a slot: its ICCID when readable (Device Owner + READ_PHONE_STATE),
     * otherwise the network operator code. null = cannot tell yet.
     */
    private fun slotIdentity(context: Context, tm: TelephonyManager, slot: Int): String? {
        var subTm: TelephonyManager? = null
        try {
            val sm = context.getSystemService(SubscriptionManager::class.java)
            val info = sm?.getActiveSubscriptionInfoForSimSlotIndex(slot)
            info?.iccId?.takeIf { it.isNotBlank() }?.let { return it }
            info?.subscriptionId?.let { subTm = tm.createForSubscriptionId(it) }
        } catch (e: Exception) { Log.w(TAG, "slot $slot subscription: ${e.message}") }
        try { subTm?.simSerialNumber?.takeIf { it.isNotBlank() }?.let { return it } } catch (e: Exception) { }
        if (slot == 0) DeviceInfoCollector.getSimIccid(context)?.takeIf { it.isNotBlank() }?.let { return it }
        val op = try { (subTm ?: tm).simOperator } catch (e: Exception) { null }
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
