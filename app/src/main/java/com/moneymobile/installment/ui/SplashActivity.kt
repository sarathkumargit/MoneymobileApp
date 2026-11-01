package com.moneymobile.installment.ui

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.moneymobile.installment.admin.DevicePolicyController
import com.moneymobile.installment.service.ManagementService
import com.moneymobile.installment.util.AppPreferences

class SplashActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // No artificial delay: routing only reads local prefs, so go straight to the right screen.
        route()
    }

    private fun route() {
        val dpc = DevicePolicyController(this)

        when {
            // Not enrolled yet — show enrollment screen
            !AppPreferences.isEnrolled(this) -> {
                startActivity(Intent(this, EnrollmentActivity::class.java))
            }

            // Released to the customer — no management, just show the (paid) dashboard
            AppPreferences.isReleased(this) -> {
                startActivity(Intent(this, MainActivity::class.java))
            }

            // Device is locked (overdue) — show locked screen
            AppPreferences.isDeviceLocked(this) -> {
                // Service must run so the Unlock command can arrive
                startForegroundService(Intent(this, ManagementService::class.java))
                startActivity(Intent(this, LockedActivity::class.java))
            }

            // Normal operation — show status dashboard
            else -> {
                // Ensure management service is running
                startForegroundService(Intent(this, ManagementService::class.java))
                startActivity(Intent(this, MainActivity::class.java))
            }
        }
        finish()
    }
}
