package com.moneymobile.installment.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object AppPreferences {

    private const val PREF_FILE = "mm_secure_prefs"

    // Performance: building the MasterKey (Android Keystore) and EncryptedSharedPreferences
    // (Tink keyset decrypt) costs tens to hundreds of ms. It used to happen on EVERY get/set —
    // including the lock screen's main-thread poll — so the instance is now built once per
    // process and reused. A failed build is not cached, so it is retried next call as before.
    @Volatile private var cached: SharedPreferences? = null

    private fun getPrefs(context: Context): SharedPreferences {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            return try {
                val app = context.applicationContext
                val masterKey = MasterKey.Builder(app)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build()
                EncryptedSharedPreferences.create(
                    app,
                    PREF_FILE,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
                ).also { cached = it }
            } catch (e: Exception) {
                // Fallback to regular prefs if encryption unavailable
                context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            }
        }
    }

    // ─── Enrollment State ────────────────────────────────────

    fun isEnrolled(context: Context): Boolean =
        getPrefs(context).getBoolean("enrolled", false)

    fun setEnrolled(context: Context, enrolled: Boolean) =
        getPrefs(context).edit().putBoolean("enrolled", enrolled).apply()

    fun getDeviceId(context: Context): String? =
        getPrefs(context).getString("device_id", null)

    fun setDeviceId(context: Context, id: String) =
        getPrefs(context).edit().putString("device_id", id).apply()

    fun getDeviceToken(context: Context): String? =
        getPrefs(context).getString("device_token", null)

    fun setDeviceToken(context: Context, token: String) =
        getPrefs(context).edit().putString("device_token", token).apply()

    fun getShopId(context: Context): String? =
        getPrefs(context).getString("shop_id", null)

    fun setShopId(context: Context, shopId: String) =
        getPrefs(context).edit().putString("shop_id", shopId).apply()

    fun getAgreementId(context: Context): String? =
        getPrefs(context).getString("agreement_id", null)

    fun setAgreementId(context: Context, agreementId: String) =
        getPrefs(context).edit().putString("agreement_id", agreementId).apply()

    // ─── Shop setup (from the setup file on the computer) ────
    // Saved the moment the setup file hands them over, so registration can retry on its own
    // (no Wi-Fi yet, server offline, app reopened) without anyone typing the shop ID.
    fun getSetupShopId(context: Context): String? =
        getPrefs(context).getString("setup_shop_id", null)

    fun getSetupSecret(context: Context): String? =
        getPrefs(context).getString("setup_enroll_secret", null)

    /** Server address handed over by the setup file (e.g. http://192.168.1.20:3000/api). */
    fun getServerUrl(context: Context): String? =
        getPrefs(context).getString("server_url", null)

    fun setServerUrl(context: Context, url: String) =
        getPrefs(context).edit().putString("server_url", url).commit()

    /**
     * Running the setup file on a phone always means "register this phone now" — including a
     * phone that was released earlier and is being sold again without a factory reset. Clear the
     * old registration so the app registers fresh instead of thinking it is already done.
     */
    fun resetRegistrationForSetup(context: Context) =
        getPrefs(context).edit()
            .putBoolean("enrolled", false)
            .putBoolean("released", false)
            .remove("device_id")
            .remove("device_token")
            .remove("agreement_status")
            // New owner/registration: forget the previous approved SIM and any SIM alert.
            // The SIM present at registration becomes the approved one.
            .remove("last_sim_iccid")
            .remove("sim_alert_state")
            .remove("pending_sim_event")
            .remove("offline_lock_at")
            .remove("offline_lock_ack_at")
            .remove("lock_warn_state")
            .remove("clock_ms").remove("clock_elapsed").remove("clock_boot").remove("clock_last_est")
            .commit()

    fun setSetupConfig(context: Context, shopId: String, secret: String) =
        getPrefs(context).edit()
            .putString("setup_shop_id", shopId)
            .putString("setup_enroll_secret", secret)
            .commit() // synchronous: must be on disk even if the app is killed right after

    // ─── Device Status ───────────────────────────────────────

    fun isAdminEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean("admin_enabled", false)

    fun setAdminEnabled(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean("admin_enabled", enabled).apply()

    fun isDeviceLocked(context: Context): Boolean =
        getPrefs(context).getBoolean("device_locked", false)

    fun setDeviceLocked(context: Context, locked: Boolean) =
        getPrefs(context).edit().putBoolean("device_locked", locked).apply()

    // Set once the phone is fully paid and released — management stops permanently.
    fun isReleased(context: Context): Boolean =
        getPrefs(context).getBoolean("released", false)

    fun setReleased(context: Context, released: Boolean) =
        getPrefs(context).edit().putBoolean("released", released).apply()

    // ─── SIM Tracking ────────────────────────────────────────

    fun getLastKnownIccid(context: Context): String? =
        getPrefs(context).getString("last_sim_iccid", null)

    fun setLastKnownIccid(context: Context, iccid: String) =
        getPrefs(context).edit().putString("last_sim_iccid", iccid).apply()

    /** The SIM state already reported to the shop (so one removal/swap = one alert, not a loop). */
    fun getSimAlertState(context: Context): String? =
        getPrefs(context).getString("sim_alert_state", null)

    fun setSimAlertState(context: Context, state: String?) =
        getPrefs(context).edit().apply { if (state == null) remove("sim_alert_state") else putString("sim_alert_state", state) }.apply()

    /** A SIM alert the server has not confirmed yet: "TYPE|details". Kept until it is delivered. */
    fun getPendingSimEvent(context: Context): String? =
        getPrefs(context).getString("pending_sim_event", null)

    fun setPendingSimEvent(context: Context, value: String?) =
        getPrefs(context).edit().apply {
            if (value == null) { remove("pending_sim_event"); remove("pending_sim_event_at") }
            else { putString("pending_sim_event", value); putLong("pending_sim_event_at", System.currentTimeMillis()) }
        }.commit()

    /**
     * When the pending SIM alert was detected. Alerts saved by older builds have no time: the SIM
     * was still there at the last successful check-in, so that is the earliest it can have happened.
     */
    fun getPendingSimEventAt(context: Context): Long {
        val p = getPrefs(context)
        val at = p.getLong("pending_sim_event_at", 0L)
        return if (at > 0) at else p.getLong("last_heartbeat", 0L)
    }

    // ─── Offline unlock code ─────────────────────────────────
    /** Highest time-step already used — a code can never be used twice. */
    fun getOfflineUsedStep(context: Context): Long =
        getPrefs(context).getLong("offline_used_step", 0L)

    fun setOfflineUsedStep(context: Context, step: Long) =
        getPrefs(context).edit().putLong("offline_used_step", step).commit()

    fun getOfflineFailCount(context: Context): Int =
        getPrefs(context).getInt("offline_fail_count", 0)

    fun getOfflineBlockedUntil(context: Context): Long =
        getPrefs(context).getLong("offline_blocked_until", 0L)

    fun setOfflineFailures(context: Context, count: Int, blockedUntil: Long) =
        getPrefs(context).edit().putInt("offline_fail_count", count).putLong("offline_blocked_until", blockedUntil).commit()

    /** An offline unlock the shop has not been told about yet (reported on the next check-in). */
    fun getPendingOfflineUnlock(context: Context): Long =
        getPrefs(context).getLong("pending_offline_unlock", 0L)

    fun setPendingOfflineUnlock(context: Context, at: Long) =
        getPrefs(context).edit().putLong("pending_offline_unlock", at).commit()

    // ─── Offline overdue lock ────────────────────────────────
    /** When the phone must lock itself for an overdue payment (UTC epoch ms). 0 = nothing to enforce. */
    fun getOfflineLockAt(context: Context): Long =
        getPrefs(context).getLong("offline_lock_at", 0L)

    fun setOfflineLockAt(context: Context, at: Long) =
        getPrefs(context).edit().putLong("offline_lock_at", at).commit()

    /** The deadline already enforced / unlocked with a shop code — never lock again for that same one. */
    fun getOfflineLockAckAt(context: Context): Long =
        getPrefs(context).getLong("offline_lock_ack_at", 0L)

    fun setOfflineLockAckAt(context: Context, at: Long) =
        getPrefs(context).edit().putLong("offline_lock_ack_at", at).commit()

    /** Which "phone will lock soon" reminders were already shown, as "<lockAt>|<bitmask>". */
    fun getLockWarnState(context: Context): String? =
        getPrefs(context).getString("lock_warn_state", null)

    fun setLockWarnState(context: Context, state: String?) =
        getPrefs(context).edit().apply { if (state == null) remove("lock_warn_state") else putString("lock_warn_state", state) }.commit()

    /** Trusted time: [ms] was the real time when SystemClock.elapsedRealtime() read [elapsed], in boot number [boot]. */
    data class ClockAnchor(val ms: Long, val elapsed: Long, val boot: Int)

    fun getClockAnchor(context: Context): ClockAnchor? {
        val p = getPrefs(context)
        val ms = p.getLong("clock_ms", 0L)
        if (ms <= 0L) return null
        return ClockAnchor(ms, p.getLong("clock_elapsed", 0L), p.getInt("clock_boot", -1))
    }

    fun setClockAnchor(context: Context, a: ClockAnchor) =
        getPrefs(context).edit()
            .putLong("clock_ms", a.ms).putLong("clock_elapsed", a.elapsed).putInt("clock_boot", a.boot)
            .putLong("clock_last_est", a.ms)
            .commit()

    /** Latest trusted time we computed — the floor after a reboot, so the clock can never go backwards. */
    fun getClockLastEst(context: Context): Long =
        getPrefs(context).getLong("clock_last_est", 0L)

    fun setClockLastEst(context: Context, ms: Long) =
        getPrefs(context).edit().putLong("clock_last_est", ms).apply()

    // ─── Last Heartbeat ──────────────────────────────────────

    fun getLastHeartbeatTime(context: Context): Long =
        getPrefs(context).getLong("last_heartbeat", 0L)

    fun setLastHeartbeatTime(context: Context, time: Long) =
        getPrefs(context).edit().putLong("last_heartbeat", time).apply()

    // ─── Firebase Cloud Messaging ────────────────────────────
    fun getFcmToken(context: Context): String? =
        getPrefs(context).getString("fcm_token", null)

    fun setFcmToken(context: Context, token: String) =
        getPrefs(context).edit().putString("fcm_token", token).commit()

    /** The last FCM command id we acted on — so a re-delivered push can't lock/unlock twice. */
    fun getLastFcmCmdId(context: Context): String? =
        getPrefs(context).getString("last_fcm_cmd_id", null)

    fun setLastFcmCmdId(context: Context, id: String) =
        getPrefs(context).edit().putString("last_fcm_cmd_id", id).commit()

    // ─── Latest agreement snapshot (from backend) ───────────
    fun getAgreementStatusJson(context: Context): String? =
        getPrefs(context).getString("agreement_status", null)

    fun setAgreementStatusJson(context: Context, json: String) =
        getPrefs(context).edit().putString("agreement_status", json).apply()
}
