package com.moneymobile.installment.admin

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.moneymobile.installment.service.ManagementService
import com.moneymobile.installment.util.AppPreferences

class DeviceAdminReceiver : DeviceAdminReceiver() {

    companion object {
        private const val TAG = "DeviceAdminReceiver"
    }

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Log.i(TAG, "Device Admin enabled")
        AppPreferences.setAdminEnabled(context, true)
        // `adb shell dpm set-device-owner` triggers onEnabled (onProfileProvisioningComplete
        // only fires for QR/NFC provisioning), so apply the baseline protections here too.
        val controller = DevicePolicyController(context)
        if (controller.isDeviceOwner) controller.applyAllRestrictions()
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Log.w(TAG, "Device Admin disabled — reporting security event")
        AppPreferences.setAdminEnabled(context, false)
        // Report tamper to backend even if we lose control
        ManagementService.reportSecurityEvent(context, "ADMIN_DISABLED")
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        super.onProfileProvisioningComplete(context, intent)
        Log.i(TAG, "Device Owner provisioning complete")
        // QR setup: save the shop details from the QR and register in the background.
        com.moneymobile.installment.provisioning.ProvisioningSetup.handle(
            context, com.moneymobile.installment.provisioning.ProvisioningSetup.extrasFrom(intent)
        )

        // Apply all restrictions immediately after provisioning
        val controller = DevicePolicyController(context)
        controller.applyAllRestrictions()

        // Start management service
        val serviceIntent = Intent(context, ManagementService::class.java)
        context.startForegroundService(serviceIntent)
    }

    override fun onPasswordFailed(context: Context, intent: Intent) {
        super.onPasswordFailed(context, intent)
        Log.w(TAG, "Password attempt failed")
        ManagementService.reportSecurityEvent(context, "PASSWORD_FAILED")
    }
}
