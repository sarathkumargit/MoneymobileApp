package com.moneymobile.installment.provisioning

import android.app.Activity
import android.os.Bundle

/**
 * Last step of QR setup (Android 10+): the app is now Device Owner. Save the shop details
 * (again, in case the earlier step didn't get them) and start background registration.
 */
class PolicyComplianceActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ProvisioningSetup.handle(this, ProvisioningSetup.extrasFrom(intent))
        setResult(RESULT_OK)
        finish()
    }
}
