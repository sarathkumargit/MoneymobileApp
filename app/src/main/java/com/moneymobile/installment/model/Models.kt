package com.moneymobile.installment.model

import com.google.gson.annotations.SerializedName

// ─── Device Info ────────────────────────────────────────────

data class DeviceInfo(
    @SerializedName("imei") val imei: String?,
    @SerializedName("brand") val brand: String,
    @SerializedName("model") val model: String,
    @SerializedName("android_version") val androidVersion: String,
    @SerializedName("serial_number") val serialNumber: String?,
    @SerializedName("phone_number") val phoneNumber: String?,
    @SerializedName("sim_operator") val simOperator: String?,
    @SerializedName("sim_iccid") val simIccid: String?,
    @SerializedName("screen_resolution") val screenResolution: String,
    @SerializedName("storage_gb") val storageGb: Long,
    @SerializedName("ram_mb") val ramMb: Long,
    @SerializedName("enrollment_token") val enrollmentToken: String,
    @SerializedName("app_version") val appVersion: String,
    // "locked" | "unlocked" | "unknown" — an unlocked bootloader lets the phone be erased from a computer
    @SerializedName("bootloader_state") val bootloaderState: String? = null
)

// ─── Enrollment ──────────────────────────────────────────────

data class EnrollmentRequest(
    @SerializedName("device_info") val deviceInfo: DeviceInfo,
    @SerializedName("shop_id") val shopId: String,
    @SerializedName("agreement_id") val agreementId: String?,
    @SerializedName("enrollment_token") val enrollmentToken: String
)

data class AutoEnrollRequest(
    @SerializedName("shop_id") val shopId: String,
    @SerializedName("enroll_secret") val enrollSecret: String,
    @SerializedName("device_info") val deviceInfo: DeviceInfo
)

data class EnrollmentResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("device_id") val deviceId: String?,
    @SerializedName("device_token") val deviceToken: String?,
    @SerializedName("command_key") val commandKey: String? = null,
    @SerializedName("agreement") val agreement: AgreementStatus?,
    @SerializedName("message") val message: String?
)

// ─── Agreement ───────────────────────────────────────────────

data class AgreementStatus(
    @SerializedName("id") val id: String,
    @SerializedName("status") val status: String,       // active, overdue, restricted, paid
    @SerializedName("customer_name") val customerName: String,
    @SerializedName("next_due_date") val nextDueDate: String?,
    @SerializedName("next_due_amount") val nextDueAmount: Double?,
    @SerializedName("outstanding_balance") val outstandingBalance: Double,
    @SerializedName("grace_period_days") val gracePeriodDays: Int,
    @SerializedName("days_overdue") val daysOverdue: Int,
    @SerializedName("total_price") val totalPrice: Double? = null,
    @SerializedName("deposit") val deposit: Double? = null,
    @SerializedName("total_paid") val totalPaid: Double? = null,
    @SerializedName("locked") val locked: Boolean? = null,
    @SerializedName("shop_name") val shopName: String? = null,
    @SerializedName("shop_phone") val shopPhone: String? = null,
    @SerializedName("overdue_amount") val overdueAmount: Double? = null,
    // Offline lock: the shop server's clock, and the moment (UTC epoch ms) the first unpaid
    // installment goes overdue. The phone keeps both so it can lock itself with no internet.
    @SerializedName("server_time") val serverTime: Long? = null,
    @SerializedName("overdue_lock_at") val overdueLockAt: Long? = null,
    // The deadlines of the following unpaid months, and their own signature (see CommandSig.laterLockAt).
    @SerializedName("later_lock_at") val laterLockAt: List<Long>? = null,
    @SerializedName("later_sig") val laterSig: String? = null,
    // Server signature over the lock fields (see CommandSig).
    @SerializedName("sig") val sig: String? = null
)

// ─── Heartbeat ───────────────────────────────────────────────

data class HeartbeatRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_token") val deviceToken: String,
    @SerializedName("sim_iccid") val simIccid: String?,
    @SerializedName("sim_operator") val simOperator: String?,
    @SerializedName("battery_level") val batteryLevel: Int,
    @SerializedName("is_connected") val isConnected: Boolean,
    @SerializedName("network_type") val networkType: String,
    @SerializedName("device_owner") val deviceOwner: Boolean,
    @SerializedName("latitude") val latitude: Double? = null,
    @SerializedName("longitude") val longitude: Double? = null,
    @SerializedName("location_accuracy") val locationAccuracy: Int? = null,
    @SerializedName("sim_state") val simState: String? = null,      // "present" | "absent" | null (unknown)
    @SerializedName("app_version") val appVersion: String? = null,
    // This build detects SIM changes itself (with the time it saw them), so the server skips its
    // own backup check — that backup would re-lock a phone staff unlocked while it was offline.
    @SerializedName("sim_watch") val simWatch: Boolean = true,
    @SerializedName("fcm_token") val fcmToken: String? = null,   // Firebase push token (for reaching the phone)
    @SerializedName("has_command_key") val hasCommandKey: Boolean = false, // the server only sends the signing key to phones without one
    @SerializedName("timestamp") val timestamp: Long,
    // App build number (versionCode). Lets the shop see which phones can take an online update.
    @SerializedName("app_version_code") val appVersionCode: Int? = null,
    // Reset protection as the phone really holds it: on | off | unsupported | failed, and the account IDs.
    @SerializedName("frp_state") val frpState: String? = null,
    @SerializedName("frp_accounts") val frpAccounts: String? = null
)

data class HeartbeatResponse(
    @SerializedName("success") val success: Boolean,
    @SerializedName("command") val command: DeviceCommand?,
    @SerializedName("agreement") val agreement: AgreementStatus?,
    @SerializedName("command_key") val commandKey: String? = null,
    @SerializedName("frp_account_ids") val frpAccountIds: List<String>? = null,
    @SerializedName("frp_sig") val frpSig: String? = null,      // server signature over the account list (CommandSig.frpOk)
    // Present only when shop staff pressed "Update" for this phone (see update/AppUpdater).
    @SerializedName("app_update") val appUpdate: AppUpdateInfo? = null
)

// ─── Online app update ───────────────────────────────────────

/** Which app file to install. Signed by the server (CommandSig.updateOk); the file itself is also checked. */
data class AppUpdateInfo(
    @SerializedName("request_id") val requestId: String? = null,
    @SerializedName("version_code") val versionCode: Int? = null,
    @SerializedName("version_name") val versionName: String? = null,
    @SerializedName("url") val url: String? = null,
    @SerializedName("sha256") val sha256: String? = null,
    @SerializedName("size") val size: Long? = null,
    @SerializedName("sig") val sig: String? = null
)

/** Progress report for an app update: downloading | waiting | installing | installed | failed. */
data class AppUpdateStatusRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_token") val deviceToken: String,
    @SerializedName("request_id") val requestId: String,
    @SerializedName("status") val status: String,
    @SerializedName("detail") val detail: String?,
    @SerializedName("version_code") val versionCode: Int
)

// ─── Commands ────────────────────────────────────────────────

data class DeviceCommand(
    @SerializedName("id") val id: String,
    @SerializedName("type") val type: CommandType,
    @SerializedName("reason") val reason: String?,
    @SerializedName("issued_at") val issuedAt: String,
    @SerializedName("sig") val sig: String? = null
)

enum class CommandType {
    LOCK_DEVICE,
    UNLOCK_DEVICE,
    RELEASE_DEVICE,
    BLOCK_CALLS,
    RESTORE_CALLS,
    BLOCK_DATA,
    RESTORE_DATA,
    COLLECT_INFO,
    NONE
}

// ─── Security Event ──────────────────────────────────────────

data class SecurityEventRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_token") val deviceToken: String,
    @SerializedName("event_type") val eventType: String,
    @SerializedName("details") val details: String?,
    @SerializedName("timestamp") val timestamp: Long,              // when it HAPPENED on the phone
    @SerializedName("sent_at") val sentAt: Long? = null            // phone clock when sent (server corrects clock drift)
)

// ─── Unlock Request (customer → shop) ────────────────────────

data class UnlockRequestBody(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_token") val deviceToken: String,
    @SerializedName("imei") val imei: String?,
    @SerializedName("model") val model: String?,
    @SerializedName("reason") val reason: String?
)

// ─── Command Acknowledgement ──────────────────────────────────

data class CommandAckRequest(
    @SerializedName("device_id") val deviceId: String,
    @SerializedName("device_token") val deviceToken: String,
    @SerializedName("command_id") val commandId: String,
    @SerializedName("executed") val executed: Boolean,
    @SerializedName("result") val result: String
)

// ─── API Wrapper ─────────────────────────────────────────────

data class ApiResponse<T>(
    @SerializedName("success") val success: Boolean,
    @SerializedName("data") val data: T?,
    @SerializedName("error") val error: String?,
    @SerializedName("message") val message: String?
)
