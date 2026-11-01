package com.moneymobile.installment.util

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import com.moneymobile.installment.R
import com.moneymobile.installment.model.AgreementStatus
import com.moneymobile.installment.monitor.OfflineLockReceiver
import com.moneymobile.installment.ui.MainActivity
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "Your phone will lock on <date>" reminders, shown ON the phone — no internet needed.
 *
 * The lock time is already stored by [OfflineLockGuard]. Two reminders are scheduled from it:
 * 48 hours and 24 hours before the lock. Each one is shown once per deadline (a new deadline after
 * a payment starts again), and never after the lock time — from then on the lock takes over.
 * An alarm wakes the phone for each one; [showDue] is also called by the 15-second watcher and on
 * every service start, so a reminder missed while the phone was off appears as soon as it is on.
 */
object LockWarnings {
    private const val TAG = "LockWarnings"
    const val ACTION_WARN_ALARM = "com.moneymobile.ACTION_LOCK_WARNING_ALARM"
    private const val CHANNEL_ID = "mm_payment_reminders"
    private const val NOTIFICATION_ID = 2001
    private const val HOUR = 3_600_000L

    /** Hours before the lock, largest first. Bit i of the "shown" mask = reminder i already shown. */
    private val BEFORE_LOCK_MS = longArrayOf(48 * HOUR, 24 * HOUR)

    /** Shows the most urgent reminder that is due and not shown yet (older ones are marked shown too). */
    fun showDue(context: Context) {
        val app = context.applicationContext
        val lockAt = OfflineLockGuard.upcomingLockAt(app) ?: return
        val now = OfflineLockGuard.trustedNow(app)
        if (now >= lockAt) return // the lock itself takes over

        val shown = shownMask(app, lockAt)
        var newMask = shown
        var due = false
        BEFORE_LOCK_MS.forEachIndexed { i, before ->
            if (now >= lockAt - before && (shown and (1 shl i)) == 0) { newMask = newMask or (1 shl i); due = true }
        }
        if (!due) return
        AppPreferences.setLockWarnState(app, "$lockAt|$newMask")
        notify(app, lockAt, lockAt - now)
    }

    /** (Re)arms the alarm for the next reminder that has not been shown. */
    fun schedule(context: Context) {
        val app = context.applicationContext
        val am = app.getSystemService(AlarmManager::class.java) ?: return
        val pi = alarmIntent(app)
        val lockAt = OfflineLockGuard.upcomingLockAt(app)
        if (lockAt == null) { am.cancel(pi); return }

        val now = OfflineLockGuard.trustedNow(app)
        val shown = shownMask(app, lockAt)
        val nextAt = BEFORE_LOCK_MS.indices
            .filter { (shown and (1 shl it)) == 0 && lockAt - BEFORE_LOCK_MS[it] > now }
            .minOfOrNull { lockAt - BEFORE_LOCK_MS[it] }
        if (nextAt == null || nextAt >= lockAt) { am.cancel(pi); return }

        val triggerAt = SystemClock.elapsedRealtime() + (nextAt - now + 1_000L).coerceAtLeast(1_000L)
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi)
            }
        } catch (e: SecurityException) {
            try { am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerAt, pi) } catch (e2: Exception) { Log.w(TAG, "alarm: ${e2.message}") }
        }
    }

    /** Removes the alarm and any reminder still on screen (deadline changed, paid, or phone released). */
    fun cancel(context: Context) {
        val app = context.applicationContext
        try { app.getSystemService(AlarmManager::class.java)?.cancel(alarmIntent(app)) } catch (e: Exception) { }
        try { app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID) } catch (e: Exception) { }
    }

    /** "4 days 3 h", "5 h 20 min", "12 min" — used by the reminder and the dashboard. */
    fun remainingText(ms: Long): String {
        if (ms <= 0L) return "now"
        val totalMin = ms / 60_000L
        val d = totalMin / 1440
        val h = (totalMin % 1440) / 60
        val m = totalMin % 60
        return when {
            d >= 1 -> "$d day${if (d > 1) "s" else ""}" + (if (h > 0) " $h h" else "")
            h >= 1 -> "$h h" + (if (m > 0) " $m min" else "")
            else -> "${maxOf(m, 1)} min"
        }
    }

    fun formatLockTime(lockAt: Long): String =
        SimpleDateFormat("EEE, d MMM yyyy 'at' h:mm a", Locale.US).format(Date(lockAt))

    private fun shownMask(app: Context, lockAt: Long): Int {
        val st = AppPreferences.getLockWarnState(app) ?: return 0
        val parts = st.split('|')
        return if (parts.size == 2 && parts[0].toLongOrNull() == lockAt) parts[1].toIntOrNull() ?: 0 else 0
    }

    private fun notify(app: Context, lockAt: Long, remainingMs: Long) {
        try {
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Payment reminders", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Tells you when your phone will lock if a payment is not made"
                }
            )
            val a: AgreementStatus? = try {
                AppPreferences.getAgreementStatusJson(app)?.let { Gson().fromJson(it, AgreementStatus::class.java) }
            } catch (e: Exception) { null }
            val amount = a?.nextDueAmount?.takeIf { it > 0.0 }
                ?.let { " Amount due: Rs ${NumberFormat.getIntegerInstance(Locale.US).format(Math.round(it))}." } ?: ""
            val shop = a?.shopName?.takeIf { it.isNotBlank() }?.let { " Pay $it to keep your phone unlocked." } ?: ""

            val text = "Your phone will lock on ${formatLockTime(lockAt)} (in ${remainingText(remainingMs)}) " +
                "if your payment is not made.$amount$shop Already paid? Connect to the internet once so your phone updates."
            val open = PendingIntent.getActivity(
                app, 7103,
                Intent(app, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val n = NotificationCompat.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("Payment reminder — phone locks in ${remainingText(remainingMs)}")
                .setContentText("Locks on ${formatLockTime(lockAt)}")
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFICATION_ID, n)
            Log.i(TAG, "Reminder shown: locks in ${remainingText(remainingMs)}")
        } catch (e: Exception) { Log.w(TAG, "notify failed: ${e.message}") }
    }

    private fun alarmIntent(app: Context): PendingIntent = PendingIntent.getBroadcast(
        app, 7102,
        Intent(app, OfflineLockReceiver::class.java).setAction(ACTION_WARN_ALARM),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}
