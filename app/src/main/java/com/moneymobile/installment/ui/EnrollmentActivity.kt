package com.moneymobile.installment.ui

import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.moneymobile.installment.admin.DevicePolicyController
import com.moneymobile.installment.api.ApiClient
import com.moneymobile.installment.databinding.ActivityEnrollmentBinding
import com.moneymobile.installment.enrollment.EnrollmentRepository
import com.moneymobile.installment.enrollment.EnrollmentResult
import com.moneymobile.installment.service.ManagementService
import com.moneymobile.installment.util.AppPreferences
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class EnrollmentActivity : AppCompatActivity() {

    private lateinit var binding: ActivityEnrollmentBinding
    private val repo by lazy { EnrollmentRepository(this) }
    private val dpc by lazy { DevicePolicyController(this) }
    private var autoEnrolling = false
    private var retryJob: Job? = null

    companion object {
        // The setup file on the computer reads this tag from logcat to report success/failure.
        const val LOG_TAG = "MMEnroll"
        private const val RETRY_MS = 10_000L
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityEnrollmentBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupUI()
        displayDeviceOwnerStatus()
        handleSetupIntent(intent)
        startAutoEnrollIfConfigured()
    }

    // This activity is singleTop: when the setup file runs while the screen is already open,
    // Android delivers the shop id here instead of onCreate. It used to be ignored, which is
    // why the shop ID sometimes never reached the app.
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleSetupIntent(intent)
        startAutoEnrollIfConfigured()
    }

    override fun onDestroy() {
        retryJob?.cancel()
        super.onDestroy()
    }

    /** Saves the shop id + secret passed by the setup file so they survive retries and restarts. */
    private fun isSameOrLocalHost(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host }.getOrNull()?.lowercase() ?: return false
        val current = runCatching { java.net.URI(ApiClient.baseUrl()).host }.getOrNull()?.lowercase()
        if (host == current) return true
        return host == "localhost" || host.endsWith(".local") ||
            Regex("^(10\\.|192\\.168\\.|127\\.|172\\.(1[6-9]|2\\d|3[01])\\.)").containsMatchIn(host)
    }

    private fun handleSetupIntent(intent: Intent?) {
        // Lightweight: an already-set-up phone just needs its server address refreshed (e.g. the
        // shop computer's Wi-Fi IP changed). No re-registration, no reset — keeps the same device.
        if (intent?.getStringExtra("action") == "UPDATE_SERVER_URL") {
            intent.getStringExtra("server_url")?.trim()?.takeIf { it.startsWith("http") }?.let {
                // Once registered, a different server is only accepted when it is on the shop's own
                // network (private/local address). Otherwise another app could repoint a locked
                // phone at a fake server that always answers "unlocked".
                if (AppPreferences.isEnrolled(this) && !isSameOrLocalHost(it)) {
                    Log.w(LOG_TAG, "SERVER_URL change ignored (not the current or a local host)")
                    finish(); return
                }
                AppPreferences.setServerUrl(this, it)
                Log.i(LOG_TAG, "SERVER_URL refreshed -> $it")
                if (AppPreferences.isEnrolled(this) && !AppPreferences.isReleased(this)) ManagementService.syncNow(this)
            }
            finish()
            return
        }
        if (intent?.getStringExtra("action") != "REGISTER_DEVICE") return
        // An already-registered phone only accepts a new registration signed with the SAME shop id
        // and secret it was set up with (the setup kit re-register option). Anything else — another
        // app, or someone with a cable — could wipe the phone's deadline and repoint it at a fake
        // server, so it is ignored.
        if (AppPreferences.isEnrolled(this) && !AppPreferences.isReleased(this)) {
            val savedShop = AppPreferences.getSetupShopId(this)
            val savedSecret = AppPreferences.getSetupSecret(this)
            val newShop = intent.getStringExtra("shop_id")?.trim()
            val newSecret = intent.getStringExtra("enroll_secret")?.trim()
            val url = intent.getStringExtra("server_url")?.trim()
            // Phones set up by an older build have nothing saved to compare with: let them re-register once
            // (that saves the details, so they are protected from then on).
            val sameSetup = savedShop.isNullOrBlank() || (savedShop == newShop && savedSecret == newSecret)
            val urlOk = url.isNullOrBlank() || isSameOrLocalHost(url)
            if (!sameSetup || !urlOk) {
                Log.w(LOG_TAG, "REGISTER_DEVICE ignored on a registered phone (setup details do not match)")
                finish(); return
            }
        }
        intent.getStringExtra("token")?.let { binding.etToken.setText(it) }
        intent.getStringExtra("agreement_id")?.let { binding.etAgreementId.setText(it) }
        val shopId = intent.getStringExtra("shop_id")?.trim()
        val secret = intent.getStringExtra("enroll_secret")?.trim()
        intent.getStringExtra("server_url")?.trim()?.takeIf { it.startsWith("http") }?.let {
            AppPreferences.setServerUrl(this, it)
        }
        if (!shopId.isNullOrBlank() && !secret.isNullOrBlank()) {
            AppPreferences.setSetupConfig(this, shopId, secret)
            // The setup file was run on this phone: always register fresh (covers resold phones).
            AppPreferences.resetRegistrationForSetup(this)
            Log.i(LOG_TAG, "SETUP_RECEIVED shop=$shopId server=${ApiClient.baseUrl()}")
        } else if (!shopId.isNullOrBlank()) {
            binding.etShopId.setText(shopId)
        }
    }

    /** Registers automatically whenever this phone has a saved setup config and isn't enrolled. */
    private fun startAutoEnrollIfConfigured() {
        if (AppPreferences.isEnrolled(this)) return
        val shopId = AppPreferences.getSetupShopId(this)
        val secret = AppPreferences.getSetupSecret(this)
        if (shopId.isNullOrBlank() || secret.isNullOrBlank()) return
        binding.etShopId.setText(shopId)
        performAutoEnroll(shopId, secret)
    }

    private fun setupUI() {
        binding.btnEnroll.setOnClickListener {
            val setupShop = AppPreferences.getSetupShopId(this)
            val setupSecret = AppPreferences.getSetupSecret(this)
            if (!setupShop.isNullOrBlank() && !setupSecret.isNullOrBlank()) {
                performAutoEnroll(setupShop, setupSecret)
                return@setOnClickListener
            }
            val token = binding.etToken.text.toString().trim()
            val shopId = binding.etShopId.text.toString().trim()
            val agreementId = binding.etAgreementId.text.toString().trim().ifEmpty { null }

            if (token.isEmpty() || shopId.isEmpty()) {
                Toast.makeText(this, "Enrollment token and Shop ID are required", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (!dpc.isDeviceOwner) {
                Toast.makeText(this, "Warning: Device Owner not set. Contact technical staff.", Toast.LENGTH_LONG).show()
            }

            performEnrollment(token, shopId, agreementId)
        }
    }

    private fun displayDeviceOwnerStatus() {
        if (dpc.isDeviceOwner) {
            binding.tvDeviceOwnerStatus.text = "Device owner active. Full management is enabled."
            binding.tvDeviceOwnerStatus.setTextColor(getColor(com.moneymobile.installment.R.color.success))
        } else {
            binding.tvDeviceOwnerStatus.text = "Device owner is not set. Run the enrollment script over ADB."
            binding.tvDeviceOwnerStatus.setTextColor(getColor(com.moneymobile.installment.R.color.danger))
        }
    }

    private fun performEnrollment(token: String, shopId: String, agreementId: String?) {
        binding.progressBar.visibility = View.VISIBLE
        binding.btnEnroll.isEnabled = false
        binding.tvStatus.text = "Connecting to server..."

        lifecycleScope.launch {
            val result = repo.enroll(token, shopId, agreementId)

            runOnUiThread {
                binding.progressBar.visibility = View.GONE
                binding.btnEnroll.isEnabled = true

                when (result) {
                    is EnrollmentResult.Success -> {
                        binding.tvStatus.text = "Enrollment successful."

                        // Apply all device restrictions now
                        dpc.applyAllRestrictions()

                        // Start management service
                        startForegroundService(Intent(this@EnrollmentActivity, ManagementService::class.java))

                        // Navigate to main dashboard
                        startActivity(Intent(this@EnrollmentActivity, MainActivity::class.java))
                        finish()
                    }
                    is EnrollmentResult.Failure -> {
                        binding.tvStatus.text = "✗ ${result.message}"
                    }
                    is EnrollmentResult.NetworkError -> {
                        binding.tvStatus.text = "✗ Network error: ${result.message}"
                    }
                }
            }
        }
    }

    private fun performAutoEnroll(shopId: String, secret: String) {
        if (autoEnrolling) return
        autoEnrolling = true
        retryJob?.cancel()
        binding.progressBar.visibility = View.VISIBLE
        binding.btnEnroll.isEnabled = false
        binding.tvStatus.text = "Registering this phone with the shop…"

        lifecycleScope.launch {
            val result = repo.autoEnroll(shopId, secret)
            autoEnrolling = false
            binding.progressBar.visibility = View.GONE
            binding.btnEnroll.isEnabled = true
            when (result) {
                is EnrollmentResult.Success -> {
                    Log.i(LOG_TAG, "RESULT=OK")
                    binding.tvStatus.text = "Registered. This phone is now managed."
                    dpc.applyAllRestrictions()
                    startForegroundService(Intent(this@EnrollmentActivity, ManagementService::class.java))
                    startActivity(Intent(this@EnrollmentActivity, MainActivity::class.java))
                    finish()
                }
                is EnrollmentResult.Failure -> {
                    // Server said no (e.g. wrong/old setup file) — retrying won't help.
                    Log.w(LOG_TAG, "RESULT=FAIL ${result.message}")
                    binding.tvStatus.text = "✗ ${result.message}"
                    binding.btnEnroll.text = "Try again"
                }
                is EnrollmentResult.NetworkError -> {
                    // Usually no Wi-Fi yet or the server is off: keep trying by itself.
                    Log.w(LOG_TAG, "RESULT=NETWORK ${ApiClient.baseUrl()} ${result.message}")
                    binding.tvStatus.text = "✗ Can't reach the server at ${ApiClient.baseUrl()} — retrying automatically…\n${result.message}"
                    binding.btnEnroll.text = "Try again now"
                    retryJob = lifecycleScope.launch {
                        delay(RETRY_MS)
                        if (!isFinishing && !AppPreferences.isEnrolled(this@EnrollmentActivity)) performAutoEnroll(shopId, secret)
                    }
                }
            }
        }
    }
}
