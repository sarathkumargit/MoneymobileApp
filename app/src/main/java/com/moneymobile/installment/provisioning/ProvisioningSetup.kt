package com.moneymobile.installment.provisioning

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import com.moneymobile.installment.util.AppPreferences

/**
 * QR-code provisioning: the website's QR contains an "admin extras bundle" with the shop id,
 * shop secret and server address. Android hands that bundle to the app during setup; we save it
 * exactly like the USB setup file does and let [AutoEnrollWorker] register the phone as soon as
 * it has network — no typing, no computer.
 */
object ProvisioningSetup {
    private const val TAG = "MMProvision"

    fun extrasFrom(intent: Intent?): PersistableBundle? {
        intent ?: return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE, PersistableBundle::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra<PersistableBundle>(DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE)
        }
    }

    /** Saves the shop details from the QR (if present) and schedules registration. Safe to call repeatedly. */
    fun handle(context: Context, extras: PersistableBundle?) {
        val shopId = extras?.getString("shop_id")?.trim()
        val secret = extras?.getString("enroll_secret")?.trim()
        val server = extras?.getString("server_url")?.trim()
        if (shopId.isNullOrBlank() || secret.isNullOrBlank()) {
            Log.w(TAG, "No shop details in provisioning extras")
            return
        }
        val alreadySame = AppPreferences.getSetupShopId(context) == shopId && AppPreferences.isEnrolled(context)
        if (!server.isNullOrBlank() && server.startsWith("http")) AppPreferences.setServerUrl(context, server)
        if (!alreadySame) {
            AppPreferences.setSetupConfig(context, shopId, secret)
            AppPreferences.resetRegistrationForSetup(context)
        }
        Log.i(TAG, "Provisioned for shop=$shopId server=$server")
        AutoEnrollWorker.enqueue(context)
    }
}
