package com.moneymobile.installment.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.moneymobile.installment.service.ManagementService
import com.moneymobile.installment.util.OfflineUnlock
import com.moneymobile.installment.util.WifiHelper
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import androidx.lifecycle.lifecycleScope
import com.moneymobile.installment.admin.DevicePolicyController
import com.moneymobile.installment.api.ApiClient
import com.moneymobile.installment.databinding.ActivityLockedBinding
import com.moneymobile.installment.model.AgreementStatus
import com.moneymobile.installment.model.UnlockRequestBody
import com.moneymobile.installment.util.AppPreferences
import com.moneymobile.installment.util.DeviceInfoCollector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Full-screen restriction interface.
 *
 * This is a genuine kiosk: because the app is Device Owner, we allow-list the
 * package for Lock Task and call startLockTask(), which the user CANNOT exit via
 * Home, Recents or swipe. All figures come from the backend agreement snapshot
 * (never hardcoded). The screen exits itself automatically the moment the
 * backend reports the device is no longer restricted.
 */
class LockedActivity : AppCompatActivity() {

    private lateinit var binding: ActivityLockedBinding
    private val gson = Gson()
    private val handler = Handler(Looper.getMainLooper())
    private var fallbackPhone = "+94112345678"

    private val unlockWatcher = object : Runnable {
        override fun run() {
            if (!AppPreferences.isDeviceLocked(this@LockedActivity)) {
                exitKiosk()
                return
            }
            handler.postDelayed(this, 1_000L)
        }
    }

    // Connection line: refreshed every 10 s so the customer (and shop staff on the phone) can see
    // whether Wi-Fi is up and whether the shop server answers.
    private val netWatcher = object : Runnable {
        override fun run() {
            refreshNetStatus()
            handler.postDelayed(this, 10_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Show over the keyguard and wake the screen.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        }

        binding = ActivityLockedBinding.inflate(layoutInflater)
        setContentView(binding.root)

        enterKiosk()
        bindData()
        wireButtons()
    }

    override fun onResume() {
        super.onResume()
        // Re-assert kiosk (in case the activity was re-created) and refresh data.
        enterKiosk()
        bindData()
        handler.removeCallbacks(unlockWatcher)
        handler.post(unlockWatcher)
        handler.removeCallbacks(netWatcher)
        handler.post(netWatcher)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(unlockWatcher)
        handler.removeCallbacks(netWatcher)
    }

    // ───────────────────────── kiosk ─────────────────────────

    private fun enterKiosk() {
        try {
            val dpc = DevicePolicyController(this)
            dpc.enableKioskAllowlist()
            if (dpc.isKioskAllowed()) {
                startLockTask()
            }
        } catch (e: Exception) {
            // If lock task isn't permitted (e.g. not device owner in testing),
            // we still show the full-screen activity — just not un-exitable.
        }
    }

    private fun exitKiosk() {
        handler.removeCallbacks(unlockWatcher)
        handler.removeCallbacks(netWatcher)
        try {
            stopLockTask()
        } catch (e: Exception) {
            // ignore
        }
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        )
        finish()
    }

    // ───────────────────────── data ─────────────────────────

    private fun bindData() {
        val deviceId = AppPreferences.getDeviceId(this) ?: "N/A"
        binding.tvDeviceId.text = "Device ID: $deviceId"

        val a: AgreementStatus? = try {
            AppPreferences.getAgreementStatusJson(this)
                ?.let { gson.fromJson(it, AgreementStatus::class.java) }
        } catch (e: Exception) {
            null
        }

        if (a == null) {
            if (AppPreferences.getSimAlertState(this) != null) binding.tvSubReason.text = "A registered SIM card was removed or changed"
            binding.tvBalance.text = "—"
            binding.tvOverdue.text = "—"
            binding.tvNextDue.text = "—"
            binding.tvHotline.text = "Hotline: $fallbackPhone"
            binding.tvSyncInfo.text = "Waiting to sync with your account…"
            return
        }

        val shopPhone = a.shopPhone?.takeIf { it.isNotBlank() } ?: fallbackPhone
        fallbackPhone = shopPhone

        binding.tvShopName.text = a.shopName?.takeIf { it.isNotBlank() } ?: "MoneyMobile"
        binding.tvBalance.text = rs(a.outstandingBalance)
        binding.tvOverdue.text = rs(a.overdueAmount ?: 0.0)
        binding.tvNextDue.text = formatDate(a.nextDueDate)
        binding.tvSubReason.text = when {
            AppPreferences.getSimAlertState(this) != null -> "A registered SIM card was removed or changed"
            a.daysOverdue > 0 -> "Payment overdue by ${a.daysOverdue} day(s)"
            else -> "Installment payment required"
        }
        binding.tvHotline.text = "Hotline: $shopPhone"

        val last = AppPreferences.getLastHeartbeatTime(this)
        val syncedText = if (last > 0)
            "Last synced: ${SimpleDateFormat("dd MMM, HH:mm", Locale.US).format(last)}"
        else "Keep the phone on Wi-Fi to sync"
        val managed = try { DevicePolicyController(this).isDeviceOwner } catch (e: Exception) { false }
        binding.tvSyncInfo.text = if (managed) syncedText
            else "$syncedText · Management not active"
    }

    private fun wireButtons() {
        binding.btnCallShop.setOnClickListener {
            open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$fallbackPhone")), "No phone app available")
        }
        binding.btnSmsShop.setOnClickListener {
            open(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$fallbackPhone")), "No messaging app available")
        }
        binding.btnRequestUnlock.setOnClickListener { requestUnlock() }
        binding.btnWifi.setOnClickListener { showWifiPicker() }
        binding.btnEnterCode.setOnClickListener { showCodeDialog() }
        binding.btnPayNow.setOnClickListener {
            Toast.makeText(
                this,
                "Please pay your installment at the shop or via the agreed method. " +
                    "The device unlocks automatically once payment is verified.",
                Toast.LENGTH_LONG
            ).show()
            open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$fallbackPhone")), "No phone app available")
        }
    }

    /** Dialer / SMS app are in the lock-task allowlist, so they open on top of the kiosk;
     *  Back returns here. */
    private fun open(intent: Intent, failMsg: String) {
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, failMsg, Toast.LENGTH_SHORT).show()
        } catch (e: SecurityException) {
            Toast.makeText(this, "Not allowed while locked — call $fallbackPhone", Toast.LENGTH_LONG).show()
        }
    }

    /** Customer-initiated unlock request straight from the locked screen. */
    private fun requestUnlock() {
        val deviceId = AppPreferences.getDeviceId(this)
        val token = AppPreferences.getDeviceToken(this)
        if (deviceId == null || token == null) {
            Toast.makeText(this, "This phone isn't enrolled.", Toast.LENGTH_LONG).show()
            return
        }
        Toast.makeText(this, "Sending request to the shop…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val msg = try {
                val info = withContext(Dispatchers.IO) { DeviceInfoCollector.collect(this@LockedActivity, "") }
                val resp = withContext(Dispatchers.IO) {
                    ApiClient.service.requestUnlock(
                        UnlockRequestBody(deviceId = deviceId, deviceToken = token, imei = info.imei, model = info.model, reason = null)
                    )
                }
                when {
                    resp.isSuccessful && resp.body()?.success == true -> "Request sent. The shop will unlock your phone shortly."
                    resp.code() == 401 || resp.code() == 409 -> "This phone is no longer managed by the shop."
                    else -> "The shop couldn't accept the request right now. Please try again."
                }
            } catch (e: Exception) {
                if (!WifiHelper.hasInternetLink(this@LockedActivity))
                    "No internet. Tap \"Connect to Wi-Fi\", or call the shop for an unlock code."
                else
                    "Connected, but the shop server isn't answering. Call the shop for an unlock code."
            }
            Toast.makeText(this@LockedActivity, msg, Toast.LENGTH_LONG).show()
        }
    }

    // ───────────────────────── connection ─────────────────────────

    private fun refreshNetStatus() {
        lifecycleScope.launch {
            val ssid = WifiHelper.connectedSsid(this@LockedActivity)
            val link = WifiHelper.hasInternetLink(this@LockedActivity)
            val wifiOn = WifiHelper.isWifiOn(this@LockedActivity)
            val reachable = if (link) withContext(Dispatchers.IO) { WifiHelper.serverReachable() } else false
            val net = when {
                ssid != null -> "Wi-Fi: $ssid"
                link -> "Mobile data"
                !wifiOn -> "Wi-Fi is off"
                else -> "Not connected to Wi-Fi"
            }
            binding.tvNetStatus.text = when {
                reachable -> "$net · shop connected"
                link -> "$net · shop server not reachable\n${ApiClient.baseUrl()}"
                else -> "$net. Connect to Wi-Fi so the shop can unlock this phone."
            }
            binding.btnWifi.text = if (ssid != null) "Change Wi-Fi" else "Connect to Wi-Fi"
        }
    }

    /** In-app Wi-Fi picker — Settings is hidden by the kiosk, so the app joins the network itself. */
    private fun showWifiPicker() {
        WifiHelper.ensureOn(this)
        WifiHelper.startScan(this)
        Toast.makeText(this, "Searching for Wi-Fi…", Toast.LENGTH_SHORT).show()
        handler.postDelayed({
            if (isFinishing) return@postDelayed
            val nets = WifiHelper.networks(this)
            val labels = nets.map { (if (it.secured) "Secured · " else "") + it.ssid + "  " + "▂▄▆█".take(it.level + 1) } + "Other network (type the name)…"
            AlertDialog.Builder(this)
                .setTitle(if (nets.isEmpty()) "No Wi-Fi found nearby" else "Choose Wi-Fi")
                .setItems(labels.toTypedArray()) { _, i ->
                    if (i < nets.size) {
                        val n = nets[i]
                        if (n.secured) askWifiPassword(n.ssid) else joinWifi(n.ssid, null)
                    } else askWifiName()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }, 2_500L)
    }

    private fun askWifiName() {
        val input = EditText(this).apply { hint = "Wi-Fi name"; inputType = InputType.TYPE_CLASS_TEXT }
        AlertDialog.Builder(this).setTitle("Wi-Fi name").setView(padded(input))
            .setPositiveButton("Next") { _, _ -> input.text.toString().trim().takeIf { it.isNotEmpty() }?.let { askWifiPassword(it) } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun askWifiPassword(ssid: String) {
        val input = EditText(this).apply {
            hint = "Password (leave empty if none)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        }
        AlertDialog.Builder(this).setTitle(ssid).setView(padded(input))
            .setPositiveButton("Connect") { _, _ -> joinWifi(ssid, input.text.toString()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun joinWifi(ssid: String, password: String?) {
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { WifiHelper.connect(this@LockedActivity, ssid, password) }
            if (!ok) {
                Toast.makeText(this@LockedActivity, "Couldn't join $ssid. Check the password and try again.", Toast.LENGTH_LONG).show()
                return@launch
            }
            Toast.makeText(this@LockedActivity, "Connecting to $ssid…", Toast.LENGTH_SHORT).show()
            // Give Android a few seconds to connect, then check in with the shop straight away.
            kotlinx.coroutines.delay(8_000L)
            refreshNetStatus()
            ManagementService.syncNow(this@LockedActivity)
            if (WifiHelper.connectedSsid(this@LockedActivity) == null)
                Toast.makeText(this@LockedActivity, "Not connected yet — if it doesn't connect, check the Wi-Fi password.", Toast.LENGTH_LONG).show()
        }
    }

    // ───────────────────────── offline unlock code ─────────────────────────

    private fun showCodeDialog() {
        val input = EditText(this).apply {
            hint = "6-digit code from the shop"
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            textSize = 22f
            letterSpacing = 0.3f
        }
        AlertDialog.Builder(this)
            .setTitle("Enter unlock code")
            .setMessage("Ask the shop for a code. It works without internet.")
            .setView(padded(input))
            .setPositiveButton("Unlock") { _, _ -> checkCode(input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun checkCode(code: String) {
        when (val r = OfflineUnlock.verify(this, code)) {
            is OfflineUnlock.Result.Ok -> {
                AppPreferences.setPendingOfflineUnlock(this, System.currentTimeMillis())
                // Open the phone for 30 minutes. If the payment is still due when that ends, it locks
                // again by itself (no internet needed). Set BEFORE unlocking so the 15-second watcher
                // can never see an unlocked phone without the window.
                val relockAt = com.moneymobile.installment.util.OfflineLockGuard.acknowledge(this)
                AppPreferences.setDeviceLocked(this, false)
                try { DevicePolicyController(this).exitLockedMode() } catch (e: Exception) { }
                val msg = if (relockAt != null)
                    "Unlocked until ${SimpleDateFormat("h:mm a", Locale.US).format(java.util.Date(relockAt))}. Your phone locks again then if the payment isn't made."
                else "Unlocked"
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                ManagementService.syncNow(this) // tell the shop now if there is internet
                exitKiosk()
            }
            is OfflineUnlock.Result.Wrong -> Toast.makeText(this,
                "Wrong or expired code. ${OfflineUnlock.triesLeft(this)} tries left.", Toast.LENGTH_LONG).show()
            is OfflineUnlock.Result.Used -> Toast.makeText(this,
                "That code was already used. Ask the shop for a new one.", Toast.LENGTH_LONG).show()
            is OfflineUnlock.Result.Blocked -> Toast.makeText(this,
                "Too many wrong codes. Try again in ${r.minutesLeft} min.", Toast.LENGTH_LONG).show()
            is OfflineUnlock.Result.NotEnrolled -> Toast.makeText(this,
                "This phone isn't registered with the shop.", Toast.LENGTH_LONG).show()
        }
    }

    private fun padded(v: android.view.View): android.view.View {
        val pad = (20 * resources.displayMetrics.density).toInt()
        return LinearLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(v, LinearLayout.LayoutParams(-1, -2)) }
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
            out.format(iso.parse(raw.substring(0, 10))!!)
        } catch (e: Exception) {
            raw
        }
    }

    // Block back — exit only happens via backend unlock.
    override fun onBackPressed() { /* no-op */ }
}
