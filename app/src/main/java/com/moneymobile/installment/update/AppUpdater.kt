package com.moneymobile.installment.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.util.Log
import androidx.core.content.pm.PackageInfoCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.moneymobile.installment.BuildConfig
import com.moneymobile.installment.admin.DevicePolicyController
import com.moneymobile.installment.api.ApiClient
import com.moneymobile.installment.model.AppUpdateInfo
import com.moneymobile.installment.model.AppUpdateStatusRequest
import com.moneymobile.installment.util.AppPreferences
import com.moneymobile.installment.util.CommandSig
import com.moneymobile.installment.util.DeviceInfoCollector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Online app update (app 1.10.0+). Shop staff press "Update" on the website; the phone's next check-in
 * carries a signed offer (which file, its SHA-256 and size). This phone then, in the background:
 *   1. downloads the file (resumes after a dropped connection, retries by itself when back online),
 *   2. checks it: size + SHA-256 from the server, same package, the expected version, newer than now,
 *      and signed with the same key as the installed app,
 *   3. installs it silently (Device Owner — no prompt to the customer).
 * Until step 3 finishes the current app keeps running exactly as before. Android installs atomically:
 * if anything fails, the phone simply keeps the current version. After the update Android starts the
 * new version (monitor/AppUpdatedReceiver), which puts the lock, alarms and restrictions straight back.
 *
 * Nothing here ever runs unless the server sent a signed offer, so a phone nobody pressed Update for
 * behaves exactly like before.
 */
object AppUpdater {
    private const val TAG = "AppUpdater"
    private const val PREFS = "mm_app_update"
    const val WORK_NAME = "mm_app_update"

    const val STAGE_QUEUED = "queued"
    const val STAGE_DOWNLOADING = "downloading"
    const val STAGE_INSTALLING = "installing"

    /** If an install was started this long ago and the version did not change, it did not happen. */
    private const val INSTALL_TIMEOUT_MS = 10 * 60 * 1000L

    data class Target(val requestId: String, val code: Int, val name: String, val url: String, val sha256: String, val size: Long)

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun target(ctx: Context): Target? {
        val p = prefs(ctx)
        val id = p.getString("req", null) ?: return null
        val url = p.getString("url", null) ?: return null
        val sha = p.getString("sha", null) ?: return null
        return Target(id, p.getInt("code", -1), p.getString("name", "") ?: "", url, sha, p.getLong("size", -1L))
    }

    private fun saveTarget(ctx: Context, u: AppUpdateInfo) {
        prefs(ctx).edit()
            .putString("req", u.requestId).putInt("code", u.versionCode ?: -1).putString("name", u.versionName ?: "")
            .putString("url", u.url).putString("sha", u.sha256?.lowercase()).putLong("size", u.size ?: -1L)
            .commit()
    }

    fun stage(ctx: Context): String = prefs(ctx).getString("stage", "") ?: ""
    private fun stageAt(ctx: Context): Long = prefs(ctx).getLong("stage_at", 0L)
    fun setStage(ctx: Context, stage: String) {
        prefs(ctx).edit().putString("stage", stage).putLong("stage_at", System.currentTimeMillis()).commit()
    }

    fun clear(ctx: Context) {
        prefs(ctx).edit().clear().commit()
        deleteFiles(ctx)
    }

    fun updatesDir(ctx: Context) = File(ctx.filesDir, "updates")

    private fun deleteFiles(ctx: Context) {
        try { updatesDir(ctx).listFiles()?.forEach { it.delete() } } catch (e: Exception) { }
    }

    fun installedVersionCode(ctx: Context): Long =
        try { PackageInfoCompat.getLongVersionCode(ctx.packageManager.getPackageInfo(ctx.packageName, 0)) }
        catch (e: Exception) { BuildConfig.VERSION_CODE.toLong() }

    // ─────────────────────────────────────────────────────────
    // Hooks called by ManagementService
    // ─────────────────────────────────────────────────────────

    /** After every successful check-in. [info] is null when no update is requested (or it was cancelled). */
    fun onHeartbeat(ctx: Context, info: AppUpdateInfo?) {
        if (AppPreferences.isReleased(ctx)) return
        val current = target(ctx)
        if (info == null) {
            // Cancelled or withdrawn on the website: stop a queued / downloading update. An install that
            // already started is never interrupted (Android finishes or rolls it back by itself).
            if (current != null && stage(ctx) != STAGE_INSTALLING) {
                Log.i(TAG, "Update no longer requested — stopping")
                WorkManager.getInstance(ctx).cancelUniqueWork(WORK_NAME)
                clear(ctx)
            }
            return
        }
        val code = info.versionCode ?: return
        val requestId = info.requestId?.takeIf { it.isNotBlank() } ?: return
        val url = info.url?.takeIf { it.isNotBlank() } ?: return
        if (info.sha256.isNullOrBlank() || (info.size ?: 0L) <= 0L) return
        if (!CommandSig.updateOk(ctx, info)) { Log.w(TAG, "Ignoring update offer: bad signature"); return }
        if (!url.startsWith("https://") && !BuildConfig.DEBUG) { Log.w(TAG, "Ignoring update offer: not https"); return }
        if (code <= installedVersionCode(ctx)) return // already on it; the server sees our version on the next check-in

        val stage = stage(ctx)
        if (stage == STAGE_INSTALLING && System.currentTimeMillis() - stageAt(ctx) < INSTALL_TIMEOUT_MS) return
        val same = current != null && current.requestId == requestId && current.code == code
        saveTarget(ctx, info) // always keep the newest download link (links expire)
        if (!same) setStage(ctx, STAGE_QUEUED)
        enqueue(ctx, replace = !same)
    }

    /** Once per service start (boot, app updated, service restarted). Settles an install that was in progress. */
    fun onServiceStart(ctx: Context) {
        val t = target(ctx) ?: run { deleteFiles(ctx); return }
        if (installedVersionCode(ctx) >= t.code) {
            Log.i(TAG, "Now running build ${BuildConfig.VERSION_CODE} — update ${t.requestId} done")
            clear(ctx)
            report(ctx, t.requestId, "installed", "now v${BuildConfig.VERSION_NAME}")
            return
        }
        if (stage(ctx) == STAGE_INSTALLING && System.currentTimeMillis() - stageAt(ctx) > INSTALL_TIMEOUT_MS) {
            Log.w(TAG, "Install of ${t.code} never completed — keeping the current app")
            clear(ctx)
            report(ctx, t.requestId, "failed", "The install did not finish. The phone kept its current app.")
            return
        }
        // Queued or downloading: make sure the background job exists (it survives restarts, this is a safety net).
        if (stage(ctx) != STAGE_INSTALLING) enqueue(ctx, replace = false)
    }

    private fun enqueue(ctx: Context, replace: Boolean) {
        val request = OneTimeWorkRequestBuilder<AppUpdateWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .addTag(WORK_NAME)
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(
            WORK_NAME, if (replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP, request
        )
    }

    // ─────────────────────────────────────────────────────────
    // Reporting
    // ─────────────────────────────────────────────────────────

    /** Fire-and-forget report (never blocks, never throws). */
    fun report(ctx: Context, requestId: String, status: String, detail: String? = null) {
        val app = ctx.applicationContext
        CoroutineScope(Dispatchers.IO).launch { send(app, requestId, status, detail) }
    }

    /** Sends one progress report. Returns true when the server got it. */
    suspend fun send(ctx: Context, requestId: String, status: String, detail: String? = null): Boolean {
        val deviceId = AppPreferences.getDeviceId(ctx) ?: return false
        val token = AppPreferences.getDeviceToken(ctx) ?: return false
        return try {
            val r = ApiClient.service.reportAppUpdate(
                AppUpdateStatusRequest(deviceId, token, requestId, status, detail?.take(300), BuildConfig.VERSION_CODE)
            )
            r.isSuccessful
        } catch (e: Exception) {
            Log.w(TAG, "report $status failed: ${e.message}")
            false
        }
    }

    /** The update could not be done: tell the shop, forget it, put the install restriction back. */
    suspend fun fail(ctx: Context, requestId: String, reason: String) {
        Log.w(TAG, "Update failed: $reason")
        clear(ctx)
        DevicePolicyController(ctx).allowSelfUpdateInstall(false)
        send(ctx, requestId, "failed", reason)
    }

    // ─────────────────────────────────────────────────────────
    // Checks + install
    // ─────────────────────────────────────────────────────────

    fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n <= 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** True when the battery is too low to start an install (and the phone is not charging). */
    fun batteryTooLow(ctx: Context): Boolean {
        val level = DeviceInfoCollector.getBatteryLevel(ctx)
        val charging = try { (ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager).isCharging } catch (e: Exception) { false }
        return level in 0 until 20 && !charging
    }

    /** Null when [apk] is the right MoneyMobile build for [t]; otherwise the reason, in plain words. */
    fun checkArchive(ctx: Context, apk: File, t: Target): String? {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val info = pm.getPackageArchiveInfo(apk.path, flags) ?: return "The downloaded file is not a valid app."
        if (info.packageName != ctx.packageName) return "The file is a different app (${info.packageName})."
        val code = PackageInfoCompat.getLongVersionCode(info)
        if (code != t.code.toLong()) return "The file is build $code, expected build ${t.code}."
        if (code <= installedVersionCode(ctx)) return "The phone already has this version or newer."
        val newCerts = certs(info)
        val ourCerts = try { certs(pm.getPackageInfo(ctx.packageName, flags)) } catch (e: Exception) { emptySet() }
        // When Android can't read the file's certificates here it still refuses a wrong key at install time.
        if (newCerts.isNotEmpty() && ourCerts.isNotEmpty() && newCerts != ourCerts) {
            return "The update is signed with a different key than the app on the phone, so the phone can't install it."
        }
        return null
    }

    @Suppress("DEPRECATION")
    private fun certs(info: PackageInfo): Set<String> {
        val sigs = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.signingInfo?.apkContentsSigners else info.signatures
        return sigs?.map { s -> MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) } }?.toSet() ?: emptySet()
    }

    /**
     * Hands the checked file to Android's installer. Returns null when the install was started (the
     * result arrives in InstallResultReceiver), or the reason it could not start.
     */
    fun install(ctx: Context, apk: File): String? {
        val installer = ctx.packageManager.packageInstaller
        // Leftover sessions from an earlier, interrupted attempt.
        try { installer.mySessions.forEach { runCatching { installer.abandonSession(it.sessionId) } } } catch (e: Exception) { }
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val dpc = DevicePolicyController(ctx)
        var sessionId = -1
        return try {
            sessionId = installer.createSession(params)
            installer.openSession(sessionId).use { session ->
                apk.inputStream().use { input ->
                    session.openWrite("base.apk", 0, apk.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                dpc.allowSelfUpdateInstall(true)
                val intent = Intent(ctx, InstallResultReceiver::class.java).setPackage(ctx.packageName)
                val piFlags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                val pending = PendingIntent.getBroadcast(ctx, sessionId, intent, piFlags)
                session.commit(pending.intentSender)
            }
            Log.i(TAG, "Install started (session $sessionId)")
            null
        } catch (e: Exception) {
            if (sessionId >= 0) runCatching { installer.abandonSession(sessionId) }
            dpc.allowSelfUpdateInstall(false)
            "Could not start the install: ${e.message}"
        }
    }

    /** Plain-language reason for an installer failure code. */
    fun describe(status: Int, message: String?): String {
        val m = message.orEmpty()
        return when {
            status == PackageInstaller.STATUS_FAILURE_STORAGE -> "Not enough free storage on the phone."
            m.contains("UPDATE_INCOMPATIBLE", true) || m.contains("signature", true) ->
                "The update is signed with a different key than the app on the phone."
            m.contains("VERSION_DOWNGRADE", true) -> "The phone already has a newer version."
            status == PackageInstaller.STATUS_FAILURE_BLOCKED -> "The phone blocked the install${if (m.isNotBlank()) " ($m)" else ""}."
            status == PackageInstaller.STATUS_FAILURE_ABORTED -> "The install was stopped${if (m.isNotBlank()) " ($m)" else ""}."
            else -> "Install failed${if (m.isNotBlank()) " ($m)" else ""}."
        }
    }
}
