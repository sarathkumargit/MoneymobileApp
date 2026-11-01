package com.moneymobile.installment.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.moneymobile.installment.service.ManagementService
import com.moneymobile.installment.util.AppPreferences

/** Fired by the deadline alarm: hand over to ManagementService, which applies the lock if still overdue. */
class OfflineLockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!AppPreferences.isEnrolled(context) || AppPreferences.isReleased(context)) return
        ManagementService.checkOfflineLock(context)
    }
}
