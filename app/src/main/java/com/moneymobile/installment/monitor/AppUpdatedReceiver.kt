package com.moneymobile.installment.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.moneymobile.installment.service.ManagementService
import com.moneymobile.installment.util.AppPreferences

/**
 * Android sends this to the NEW version right after MoneyMobile is updated (online update or USB).
 * The update stopped the old app, so start the management service at once: like after a reboot it
 * re-applies every restriction, puts a locked phone straight back into the lock screen, re-arms the
 * offline payment-deadline alarm, resumes SIM watching and reports the finished update.
 */
class AppUpdatedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        Log.i("AppUpdatedReceiver", "MoneyMobile was updated — restarting management")
        if (!AppPreferences.isEnrolled(context)) {
            if (AppPreferences.getSetupShopId(context) != null) com.moneymobile.installment.provisioning.AutoEnrollWorker.enqueue(context)
            return
        }
        if (AppPreferences.isReleased(context)) return
        try {
            context.startForegroundService(Intent(context, ManagementService::class.java).setAction(ManagementService.ACTION_BOOT))
        } catch (e: Exception) {
            Log.w("AppUpdatedReceiver", "could not start service: ${e.message}")
        }
    }
}
