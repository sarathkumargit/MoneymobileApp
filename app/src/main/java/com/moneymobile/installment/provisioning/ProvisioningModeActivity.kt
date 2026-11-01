package com.moneymobile.installment.provisioning

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.os.Build
import android.os.Bundle

/**
 * Android 10+ asks the app which mode to provision (QR setup). We always answer "fully managed
 * device" (Device Owner) and save the shop details from the QR.
 */
class ProvisioningModeActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProvisioningSetup.handle(this, ProvisioningSetup.extrasFrom(intent))
        val result = Intent()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            result.putExtra(DevicePolicyManager.EXTRA_PROVISIONING_MODE, DevicePolicyManager.PROVISIONING_MODE_FULLY_MANAGED_DEVICE)
        }
        setResult(RESULT_OK, result)
        finish()
    }
}
