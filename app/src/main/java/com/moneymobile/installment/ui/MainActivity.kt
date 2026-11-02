package com.moneymobile.installment.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import com.moneymobile.installment.R
import com.moneymobile.installment.admin.DevicePolicyController
import com.moneymobile.installment.databinding.ActivityMainBinding
import com.moneymobile.installment.api.ApiClient
import com.moneymobile.installment.model.AgreementStatus
import com.moneymobile.installment.model.UnlockRequestBody
import com.moneymobile.installment.util.AppPreferences
import com.moneymobile.installment.util.DeviceInfoCollector
import com.moneymobile.installment.util.LockWarnings
import com.moneymobile.installment.util.OfflineLockGuard
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Customer dashboard.
 *
 * All figures shown here are the REAL agreement snapshot delivered by the
 * backend (via the enrollment response and each heartbeat) and cached locally
 * by [AppPreferences]. Nothing on this screen is hard-coded sample data.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val gson = Gson()
    private var shopPhone = "+94112345678" // replaced by the shop's phone from the agreement
    private val handler = Handler(Looper.getMainLooper())
    // Keeps the "locks in 4 days 3 h" countdown fresh while the screen is open.
    private val lockTimeTicker = object : Runnable {
        override fun run() { bindLockTime(); handler.postDelayed(this, 30_000L) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Follow the phone's Light / Dark setting, like iOS.
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        super.onCreate(savedInstanceState)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        wireActions()
        bindAgreement()
    }

    override fun onResume() {
        super.onResume()
        // Refresh whenever the user returns — the background service updates the
        // cached snapshot every ~60s, so this keeps the dashboard current.
        bindAgreement()
        handler.post(lockTimeTicker)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(lockTimeTicker)
    }

    // ───────────────────────── data binding ─────────────────────────

    /** "Phone will lock on <date> — in 4 days 3 h". Read from the deadline stored on the phone, so it works offline. */
    private fun bindLockTime() {
        val lockAt = if (AppPreferences.isDeviceLocked(this) || AppPreferences.isReleased(this)) null
                     else OfflineLockGuard.upcomingLockAt(this)
        if (lockAt == null) { binding.cardLockTime.visibility = View.GONE; return }
        val left = lockAt - OfflineLockGuard.trustedNow(this)
        binding.cardLockTime.visibility = View.VISIBLE
        binding.tvLockTime.text = LockWarnings.formatLockTime(lockAt)
        binding.tvLockCountdown.text = if (left <= 0L) "Locking now…" else "Locks in ${LockWarnings.remainingText(left)}"
    }

    private fun bindAgreement() {
        bindLockTime()
        binding.tvDeviceModel.text = "${Build.MANUFACTURER} ${Build.MODEL}"

        val json = AppPreferences.getAgreementStatusJson(this)
        if (json.isNullOrBlank()) {
            showEmptyState(
                if (AppPreferences.isEnrolled(this)) "Waiting for account sync…"
                else "Device not activated yet"
            )
            return
        }

        val a: AgreementStatus? = try {
            gson.fromJson(json, AgreementStatus::class.java)
        } catch (e: Exception) {
            null
        }
        if (a == null) {
            showEmptyState("Waiting for account sync…")
            return
        }

        // Remaining is the authoritative outstanding balance (never below zero).
        val remaining = (a.outstandingBalance).coerceAtLeast(0.0)
        val paid = (a.totalPaid ?: 0.0).coerceAtLeast(0.0)
        // Total payable over the plan = paid so far + still owed. (total_price is only the phone's
        // MRP and excludes interest/charges, so it can't be the denominator — payments would
        // otherwise appear to exceed it and the remainder could go negative.)
        val payable = paid + remaining

        a.shopPhone?.takeIf { it.isNotBlank() }?.let { shopPhone = it }
        binding.tvCustomerName.text = a.customerName.ifBlank { "Customer" }
        binding.tvRemainingBalance.text = rs(remaining)
        binding.tvTotalPrice.text = rs(if ((a.totalPrice ?: 0.0) > 0.0) a.totalPrice else payable)
        binding.tvDeposit.text = rs(a.deposit)
        binding.tvTotalPaid.text = rs(paid)
        binding.tvPaidOfTotal.text = "${rs(paid)} of ${rs(payable)} paid"
        binding.tvNextDue.text = formatDate(a.nextDueDate)

        val pct = if (payable > 0) ((paid / payable) * 100).toInt().coerceIn(0, 100) else 0
        binding.progressPaid.setProgressCompat(pct, true)

        val locked = AppPreferences.isDeviceLocked(this)
        // Asking the shop to unlock only makes sense while the phone is actually locked.
        binding.btnUnlock.visibility = if (locked) View.VISIBLE else View.GONE
        binding.tvStatusChip.text = when {
            locked -> "Locked"
            else -> a.status.replaceFirstChar { it.uppercase() }
        }
        when {
            locked || a.status.equals("defaulted", true) -> styleChip(R.color.danger, R.color.tint_danger)
            a.status.equals("overdue", true) -> styleChip(R.color.warning, R.color.tint_warning)
            a.status.equals("active", true) || a.status.equals("paid", true) || a.status.equals("completed", true) -> styleChip(R.color.success, R.color.tint_success)
            else -> styleChip(R.color.text_secondary, R.color.tint_neutral)
        }
        binding.tvAgreementLine.setTextColor(getColor(when {
            locked -> R.color.danger
            a.status.equals("overdue", true) -> R.color.warning
            else -> R.color.success
        }))
        binding.tvAgreementLine.text = when {
            locked -> "Device locked. Please contact the shop."
            a.status.equals("overdue", true) ->
                "Payment overdue${if (a.daysOverdue > 0) " by ${a.daysOverdue} day(s)" else ""}"
            a.status.equals("paid", true) -> "Fully paid"
            else -> "Agreement active"
        }
        // Honest connection status: the REAL time of the last successful sync with the shop, so the
        // screen never claims "synced" when the phone has actually been offline for hours.
        val last = AppPreferences.getLastHeartbeatTime(this)
        val syncText = if (last <= 0) "Not synced yet — connect to the internet"
            else {
                val mins = (System.currentTimeMillis() - last) / 60000
                when {
                    mins < 2    -> "Synced just now"
                    mins < 60   -> "Synced $mins min ago"
                    mins < 1440 -> "Last synced ${mins / 60} h ago. Connect to the internet."
                    else        -> "Last synced ${SimpleDateFormat("dd MMM, HH:mm", Locale.US).format(last)}. Connect to the internet."
                }
            }
        binding.tvFooter.text = when {
            AppPreferences.isReleased(this) -> "Fully paid and released. This phone is yours; management has been removed."
            else -> syncText + (if (!isManaged()) " · Management not active" else "")
        }
    }

    /** Status pill: tinted background + matching text color (Apple-style "tinted" capsule). */
    private fun styleChip(textColorRes: Int, tintRes: Int) {
        binding.tvStatusChip.setTextColor(getColor(textColorRes))
        binding.tvStatusChip.backgroundTintList = android.content.res.ColorStateList.valueOf(getColor(tintRes))
    }

    private fun isManaged(): Boolean = try {
        DevicePolicyController(this).isDeviceOwner
    } catch (e: Exception) { false }

    private fun showEmptyState(message: String) {
        binding.tvCustomerName.text = "—"
        binding.tvRemainingBalance.text = "—"
        binding.tvTotalPrice.text = "—"
        binding.tvDeposit.text = "—"
        binding.tvTotalPaid.text = "—"
        binding.tvPaidOfTotal.text = "—"
        binding.tvNextDue.text = "—"
        binding.progressPaid.setProgressCompat(0, false)
        binding.btnUnlock.visibility = if (AppPreferences.isDeviceLocked(this)) View.VISIBLE else View.GONE
        binding.tvStatusChip.text = "Pending"
        styleChip(R.color.text_secondary, R.color.tint_neutral)
        binding.tvAgreementLine.text = message
        binding.tvFooter.text = "Keep the phone on Wi-Fi to sync"
    }

    private fun rs(value: Double?): String {
        val n = value ?: 0.0
        return "Rs " + NumberFormat.getIntegerInstance(Locale.US).format(Math.round(n))
    }

    private fun formatDate(raw: String?): String {
        if (raw.isNullOrBlank()) return "—"
        return try {
            val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US)
            val out = SimpleDateFormat("dd MMM yyyy", Locale.US)
            // handle both "2026-11-05" and "2026-11-05T..." forms
            out.format(iso.parse(raw.substring(0, 10))!!)
        } catch (e: Exception) {
            raw
        }
    }

    // ───────────────────────── actions ─────────────────────────

    private fun wireActions() {
        binding.btnUnlock.setOnClickListener { showUnlockRequestDialog() }

        binding.btnPayment.setOnClickListener {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$shopPhone")))
        }

        binding.btnCall.setOnClickListener {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$shopPhone")))
        }

        binding.btnMessage.setOnClickListener {
            startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$shopPhone")))
        }
    }

    /**
     * Customer asks the shop to unlock the phone. Sends IMEI + device + customer details to the
     * backend, which lists it on the shop's Unlock Requests page. Staff approve → the phone
     * unlocks on its next check-in. An optional note lets the customer explain (e.g. "paid today").
     */
    private fun showUnlockRequestDialog() {
        val input = EditText(this).apply {
            hint = "Reason (optional) — e.g. paid at shop today"
            setTextColor(getColor(R.color.text_primary))
            setHintTextColor(getColor(R.color.text_muted))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val container = FrameLayout(this).apply { setPadding(56, 24, 56, 0); addView(input) }

        MaterialAlertDialogBuilder(this)
            .setTitle("Request unlock")
            .setMessage("This sends an unlock request to the shop with your device details. They will review it and unlock your phone remotely.")
            .setView(container)
            .setPositiveButton("Send request") { _, _ -> sendUnlockRequest(input.text.toString().trim().ifEmpty { null }) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun sendUnlockRequest(reason: String?) {
        val deviceId = AppPreferences.getDeviceId(this)
        val token = AppPreferences.getDeviceToken(this)
        if (deviceId == null || token == null) {
            Toast.makeText(this, "This phone isn't enrolled, so it can't send a request.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Sending request…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val msg = try {
                val info = withContext(Dispatchers.IO) { DeviceInfoCollector.collect(this@MainActivity, "") }
                val resp = withContext(Dispatchers.IO) {
                    ApiClient.service.requestUnlock(
                        UnlockRequestBody(deviceId = deviceId, deviceToken = token, imei = info.imei, model = info.model, reason = reason)
                    )
                }
                when {
                    resp.isSuccessful && resp.body()?.success == true -> "Request sent to the shop. They'll unlock your phone shortly."
                    resp.code() == 401 || resp.code() == 409 -> "This phone is no longer managed by the shop, so there's nothing to request."
                    else -> "The shop couldn't accept the request right now. Please try again in a moment."
                }
            } catch (e: Exception) { "Couldn't reach the shop. Check your internet connection and try again." }
            Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

}
