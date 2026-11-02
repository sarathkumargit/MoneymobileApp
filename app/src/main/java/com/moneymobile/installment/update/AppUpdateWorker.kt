package com.moneymobile.installment.update

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.moneymobile.installment.admin.DevicePolicyController
import com.moneymobile.installment.util.AppPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Background job for one app update (see AppUpdater). Runs only while the phone has a connection;
 * a dropped connection just means "try again later" (Result.retry) — the partly downloaded file is
 * kept and resumed. The running app is never touched until the checked file is handed to Android.
 */
class AppUpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private enum class Download { OK, RETRY, LINK_EXPIRED, TOO_BIG }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val t = AppUpdater.target(ctx) ?: return@withContext Result.success()
        if (AppPreferences.isReleased(ctx)) { AppUpdater.clear(ctx); return@withContext Result.success() }
        if (t.code <= 0 || t.size <= 0) { AppUpdater.fail(ctx, t.requestId, "The update details were incomplete."); return@withContext Result.success() }

        // Already running this build (e.g. this job was restarted after the update itself).
        if (AppUpdater.installedVersionCode(ctx) >= t.code) {
            AppUpdater.clear(ctx)
            AppUpdater.send(ctx, t.requestId, "installed")
            return@withContext Result.success()
        }
        if (!DevicePolicyController(ctx).isDeviceOwner) {
            AppUpdater.fail(ctx, t.requestId, "MoneyMobile is not Device Owner on this phone, so it can't install updates by itself. Use the USB setup file.")
            return@withContext Result.success()
        }
        if (runAttemptCount >= 30) {
            AppUpdater.fail(ctx, t.requestId, "Gave up after many tries. Press Update again when the phone has a steady connection.")
            return@withContext Result.success()
        }

        val dir = AppUpdater.updatesDir(ctx).apply { mkdirs() }
        // Remove files of other versions so storage never fills up.
        dir.listFiles()?.forEach { f -> if (!f.name.startsWith("update-${t.code}.")) f.delete() }
        val apk = File(dir, "update-${t.code}.apk")
        val part = File(dir, "update-${t.code}.part")

        val haveGoodFile = apk.exists() && apk.length() == t.size && AppUpdater.sha256(apk) == t.sha256
        if (!haveGoodFile) {
            apk.delete()
            val needed = t.size * 2 + 50L * 1024 * 1024 - (if (part.exists()) part.length() else 0L)
            if (dir.usableSpace < needed) {
                AppUpdater.fail(ctx, t.requestId, "Not enough free storage on the phone (needs about ${needed / (1024 * 1024)} MB free).")
                return@withContext Result.success()
            }
            // Tell the shop on the first try, and again when resuming after a pause ("waiting").
            if (AppUpdater.stage(ctx) != AppUpdater.STAGE_DOWNLOADING || runAttemptCount > 0) {
                AppUpdater.setStage(ctx, AppUpdater.STAGE_DOWNLOADING)
                AppUpdater.send(ctx, t.requestId, "downloading")
            }
            when (download(t.url, part, t.size)) {
                Download.OK -> {}
                Download.RETRY -> {
                    AppUpdater.send(ctx, t.requestId, "waiting", "connection dropped, continues automatically")
                    return@withContext Result.retry()
                }
                Download.LINK_EXPIRED -> {
                    // The next check-in brings a fresh link; try again after the back-off.
                    return@withContext Result.retry()
                }
                Download.TOO_BIG -> {
                    part.delete()
                    AppUpdater.fail(ctx, t.requestId, "The downloaded file was larger than expected.")
                    return@withContext Result.success()
                }
            }
            if (part.length() != t.size) return@withContext Result.retry()
            if (AppUpdater.sha256(part) != t.sha256) {
                part.delete()
                if (runAttemptCount >= 3) {
                    AppUpdater.fail(ctx, t.requestId, "The downloaded file was damaged (checksum mismatch).")
                    return@withContext Result.success()
                }
                return@withContext Result.retry()
            }
            if (!part.renameTo(apk)) return@withContext Result.retry()
        }

        // Is it really the MoneyMobile build we expect, newer, and signed with our key?
        AppUpdater.checkArchive(ctx, apk, t)?.let { problem ->
            AppUpdater.fail(ctx, t.requestId, problem)
            return@withContext Result.success()
        }

        if (AppUpdater.batteryTooLow(ctx)) {
            AppUpdater.send(ctx, t.requestId, "waiting", "battery below 20% — installs when charging or above 20%")
            return@withContext Result.retry()
        }

        // The update may have been cancelled on the website while we were downloading.
        if (AppUpdater.target(ctx)?.requestId != t.requestId) return@withContext Result.success()

        AppUpdater.send(ctx, t.requestId, "installing")
        AppUpdater.setStage(ctx, AppUpdater.STAGE_INSTALLING)
        val err = AppUpdater.install(ctx, apk)
        if (err != null) AppUpdater.fail(ctx, t.requestId, err)
        // On success Android replaces this app; the new version reports "installed" when it starts.
        Result.success()
    }

    /** Downloads [url] into [part], resuming a partial file when the server allows it. */
    private fun download(url: String, part: File, expected: Long): Download {
        val have = if (part.exists()) part.length() else 0L
        if (have > expected) part.delete()
        val start = if (part.exists()) part.length() else 0L
        if (start == expected) return Download.OK
        val req = Request.Builder().url(url).apply { if (start > 0) header("Range", "bytes=$start-") }.build()
        return try {
            client.newCall(req).execute().use { resp ->
                if (resp.code in 400..499) { Log.w(TAG, "download link refused (${resp.code})"); return Download.LINK_EXPIRED }
                if (!resp.isSuccessful) return Download.RETRY
                val body = resp.body ?: return Download.RETRY
                val append = resp.code == 206 && start > 0
                var written = if (append) start else 0L
                FileOutputStream(part, append).use { out ->
                    body.byteStream().use { input ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (isStopped) return Download.RETRY
                            val n = input.read(buf)
                            if (n <= 0) break
                            written += n
                            if (written > expected) return Download.TOO_BIG
                            out.write(buf, 0, n)
                        }
                    }
                }
                if (written == expected) Download.OK else Download.RETRY
            }
        } catch (e: IOException) {
            Log.w(TAG, "download interrupted: ${e.message}")
            Download.RETRY
        }
    }

    companion object {
        private const val TAG = "AppUpdateWorker"
        private val client by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build()
        }
    }
}
