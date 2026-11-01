package com.moneymobile.installment.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.moneymobile.installment.monitor.OfflineLockReceiver

/**
 * Locks the phone for an overdue payment WITHOUT needing the shop server or internet.
 *
 * How it works
 *  1. Every check-in, the server sends `overdue_lock_at` (the moment the first unpaid installment
 *     goes overdue, grace days included) and `server_time`. Both are stored here ([onSynced]).
 *  2. A deadline alarm, a 15-second watcher and every reboot call [isDue]. When it is true,
 *     ManagementService applies the same kiosk lock the Lock button uses.
 *  3. The next check-in is still the source of truth: a payment, a changed due date or a staff
 *     unlock sends a new deadline (or none) and the phone follows it.
 *
 * Trusted clock — the customer can't beat the lock by moving the date back
 *  Time is measured as "server time at the last check-in + SystemClock.elapsedRealtime() since",
 *  which the user cannot change. The wall clock is only used after a reboot, and even then never
 *  earlier than the last trusted time. DevicePolicyController also forces automatic date & time.
 */
object OfflineLockGuard {
    private const val TAG = "OfflineLockGuard"
    const val ACTION_ALARM = "com.moneymobile.ACTION_OFFLINE_LOCK_ALARM"

    /** Wait this long past the deadline so the phone never beats the server by a few seconds (no lock/unlock flapping). */
    private const val MARGIN_MS = 60_000L
    private const val PERSIST_EVERY_MS = 60_000L
    @Volatile private var lastPersistElapsed = 0L

    /** Best estimate of the real time now (UTC epoch ms) that the customer cannot move backwards. */
    @Synchronized
    fun trustedNow(context: Context): Long {
        val app = context.applicationContext
        val elapsed = SystemClock.elapsedRealtime()
        val boot = bootCount(app)
        val anchor = AppPreferences.getClockAnchor(app)

        // Same boot as the anchor: uptime is the reliable measure — ignore whatever the wall clock says.
        if (anchor != null && anchor.boot == boot && elapsed >= anchor.elapsed) {
            val est = anchor.ms + (elapsed - anchor.elapsed)
            if (elapsed - lastPersistElapsed > PERSIST_EVERY_MS) {
                AppPreferences.setClockLastEst(app, est); lastPersistElapsed = elapsed
            }
            return est
        }
        // First look since a reboot (or before any check-in): use the wall clock, but never earlier
        // than the last trusted time, then anchor to it for the rest of this boot.
        val floor = maxOf(AppPreferences.getClockLastEst(app), anchor?.ms ?: 0L)
        val now = maxOf(System.currentTimeMillis(), floor)
        AppPreferences.setClockAnchor(app, AppPreferences.ClockAnchor(now, elapsed, boot))
        return now
    }

    /** Called with every successful check-in that carries agreement data. */
    fun onSynced(context: Context, serverNowMs: Long?, lockAtMs: Long?) {
        val app = context.applicationContext
        if (serverNowMs != null && serverNowMs > 0L) {
            AppPreferences.setClockAnchor(app, AppPreferences.ClockAnchor(serverNowMs, SystemClock.elapsedRealtime(), bootCount(app)))
        }
        val lockAt = lockAtMs ?: 0L
        if (AppPreferences.getOfflineLockAt(app) != lockAt) {
            AppPreferences.setOfflineLockAt(app, lockAt)
            LockWarnings.cancel(app) // deadline changed (paid / extended): drop the old reminder
        }
        schedule(app)
    }

    /** True when the payment deadline has passed and it has not already been enforced / unlocked by a shop code. */
    fun isDue(context: Context): Boolean {
        val lockAt = AppPreferences.getOfflineLockAt(context)
        if (lockAt <= 0L || lockAt == AppPreferences.getOfflineLockAckAt(context)) return false
        return trustedNow(context) >= lockAt + MARGIN_MS
    }

    /** The pending payment deadline (UTC epoch ms), or null when there is nothing to enforce / it was already handled. */
    fun upcomingLockAt(context: Context): Long? {
        val lockAt = AppPreferences.getOfflineLockAt(context)
        return if (lockAt <= 0L || lockAt == AppPreferences.getOfflineLockAckAt(context)) null else lockAt
    }

    /** The shop unlocked this deadline with an offline code: don't lock for it again. */
    fun acknowledge(context: Context) {
        val app = context.applicationContext
        AppPreferences.setOfflineLockAckAt(app, AppPreferences.getOfflineLockAt(app))
        cancel(app)
        LockWarnings.cancel(app)
    }

    /** (Re)arms the one-shot deadline alarm. Safe to call any time; lost on reboot, so BOOT calls it. */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(app)
        val lockAt = AppPreferences.getOfflineLockAt(app)
        if (lockAt <= 0L || lockAt == AppPreferences.getOfflineLockAckAt(app)) { am.cancel(pi); LockWarnings.schedule(app); return }
        LockWarnings.schedule(app)

        val wait = (lockAt + MARGIN_MS + 1_000L - trustedNow(app)).coerceAtLeast(1_000L)
        // ELAPSED_REALTIME alarms are measured in uptime, so changing the date can't move them.
        val triggerAt = SystemClock.elapsedRealtime() + wait
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
            Log.i(TAG, "Deadline alarm set for ${wait / 1000}s from now")
        } catch (e: SecurityException) {
            // Exact alarms not permitted: an approximate one is fine — the 15-second watcher is the backstop.
            try { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi) } catch (e2: Exception) { Log.w(TAG, "alarm: ${e2.message}") }
        }
    }

    fun cancel(context: Context) {
        val app = context.applicationContext
        try { app.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(app)) } catch (e: Exception) { }
    }

    private fun alarmIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app, 7101,
        Intent(app, OfflineLockReceiver::class.java).setAction(ACTION_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /** Increases on every reboot — tells us the uptime clock restarted and the anchor is stale. */
    private fun bootCount(context: Context): Int =
        try { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1) } catch (e: Exception) { -1 }
}
