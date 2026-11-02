package com.moneymobile.installment.update

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.moneymobile.installment.admin.DevicePolicyController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Android's answer to the install started by AppUpdater.install. On success Android has usually
 * already replaced (and restarted) the app; the new version also reports "installed" when it starts.
 * The customer is never shown an install prompt: if Android asks for one, the install is dropped.
 */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        val ctx = context.applicationContext
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val t = AppUpdater.target(ctx)
                Log.i(TAG, "Install result $status ${message.orEmpty()}")
                when (status) {
                    PackageInstaller.STATUS_SUCCESS -> {
                        DevicePolicyController(ctx).allowSelfUpdateInstall(false)
                        if (t != null && AppUpdater.installedVersionCode(ctx) >= t.code) {
                            AppUpdater.clear(ctx)
                            AppUpdater.send(ctx, t.requestId, "installed")
                        }
                    }
                    PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                        if (sessionId >= 0) runCatching { ctx.packageManager.packageInstaller.abandonSession(sessionId) }
                        t?.let { AppUpdater.fail(ctx, it.requestId, "The phone asked for an on-screen confirmation, so it was not installed. Update this phone with the USB setup file.") }
                            ?: DevicePolicyController(ctx).allowSelfUpdateInstall(false)
                    }
                    else -> {
                        val reason = AppUpdater.describe(status, message)
                        t?.let { AppUpdater.fail(ctx, it.requestId, "$reason The phone kept its current app.") }
                            ?: DevicePolicyController(ctx).allowSelfUpdateInstall(false)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "result handling: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    companion object { private const val TAG = "InstallResult" }
}
