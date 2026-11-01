package com.moneymobile.installment.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.moneymobile.installment.service.ManagementService
import com.moneymobile.installment.util.AppPreferences

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.i("BootReceiver", "Boot event: $action")

        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == "android.intent.action.LOCKED_BOOT_COMPLETED") {

            // Set up (QR or USB) but not registered yet — keep trying in the background.
            if (!AppPreferences.isEnrolled(context) && AppPreferences.getSetupShopId(context) != null) {
                com.moneymobile.installment.provisioning.AutoEnrollWorker.enqueue(context)
                return
            }
            // Only act if device is enrolled
            if (!AppPreferences.isEnrolled(context)) {
                Log.d("BootReceiver", "Device not enrolled — skipping management service start")
                return
            }
            if (AppPreferences.isReleased(context)) {
                Log.d("BootReceiver", "Device released — not starting management service")
                return
            }

            // Start management foreground service
            val serviceIntent = Intent(context, ManagementService::class.java)
            serviceIntent.action = ManagementService.ACTION_BOOT
            context.startForegroundService(serviceIntent)

            Log.i("BootReceiver", "Management service started on boot")
        }
    }
}
