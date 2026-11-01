package com.moneymobile.installment.provisioning

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.*
import com.moneymobile.installment.admin.DevicePolicyController
import com.moneymobile.installment.enrollment.EnrollmentRepository
import com.moneymobile.installment.enrollment.EnrollmentResult
import com.moneymobile.installment.service.ManagementService
import com.moneymobile.installment.util.AppPreferences
import java.util.concurrent.TimeUnit

/**
 * Registers the phone with the shop in the background using the saved setup details
 * (from the QR code, or the USB setup). Retries automatically until the server is reachable.
 */
class AutoEnrollWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        if (AppPreferences.isEnrolled(ctx)) return Result.success()
        val shopId = AppPreferences.getSetupShopId(ctx) ?: return Result.success()
        val secret = AppPreferences.getSetupSecret(ctx) ?: return Result.success()

        return when (val r = EnrollmentRepository(ctx).autoEnroll(shopId, secret)) {
            is EnrollmentResult.Success -> {
                Log.i(TAG, "RESULT=OK (background)")
                try {
                    val dpc = DevicePolicyController(ctx)
                    if (dpc.isDeviceOwner) dpc.applyAllRestrictions()
                } catch (e: Exception) { Log.w(TAG, "restrictions: ${e.message}") }
                try { ctx.startForegroundService(Intent(ctx, ManagementService::class.java)) } catch (e: Exception) { Log.w(TAG, "start service: ${e.message}") }
                Result.success()
            }
            is EnrollmentResult.NetworkError -> { Log.w(TAG, "RESULT=NETWORK ${r.message}"); Result.retry() }
            is EnrollmentResult.Failure -> { Log.w(TAG, "RESULT=FAIL ${r.message}"); Result.retry() }
        }
    }

    companion object {
        private const val TAG = "MMEnroll"
        private const val WORK = "mm_auto_enroll"

        fun enqueue(context: Context) {
            val req = OneTimeWorkRequestBuilder<AutoEnrollWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.LINEAR, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.REPLACE, req)
        }
    }
}
