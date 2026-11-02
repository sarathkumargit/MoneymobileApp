package com.moneymobile.installment.enrollment

import android.content.Context
import com.moneymobile.installment.api.ApiClient
import com.moneymobile.installment.model.AutoEnrollRequest
import com.moneymobile.installment.model.EnrollmentRequest
import com.moneymobile.installment.model.EnrollmentResponse
import com.moneymobile.installment.util.AppPreferences
import com.moneymobile.installment.util.DeviceInfoCollector

sealed class EnrollmentResult {
    data class Success(val response: EnrollmentResponse) : EnrollmentResult()
    data class Failure(val message: String) : EnrollmentResult()
    data class NetworkError(val message: String) : EnrollmentResult()
}

class EnrollmentRepository(private val context: Context) {

    suspend fun enroll(
        enrollmentToken: String,
        shopId: String,
        agreementId: String?
    ): EnrollmentResult {
        return try {
            val deviceInfo = DeviceInfoCollector.collect(context, enrollmentToken)

            val request = EnrollmentRequest(
                deviceInfo = deviceInfo,
                shopId = shopId,
                agreementId = agreementId,
                enrollmentToken = enrollmentToken
            )

            val response = ApiClient.service.enrollDevice(request)

            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.success) {
                    // Save credentials securely
                    body.deviceId?.let { AppPreferences.setDeviceId(context, it) }
                    body.deviceToken?.let { AppPreferences.setDeviceToken(context, it) }
                    body.commandKey?.let { AppPreferences.setCommandKey(context, it) }
                    AppPreferences.setEnrolled(context, true)

                    // Save the agreement snapshot so the dashboard shows real data
                    body.agreement?.let {
                        AppPreferences.setAgreementStatusJson(context, com.google.gson.Gson().toJson(it))
                    }

                    EnrollmentResult.Success(body)
                } else {
                    EnrollmentResult.Failure(body?.message ?: "Enrollment rejected by server")
                }
            } else {
                val errorBody = response.errorBody()?.string()
                EnrollmentResult.Failure("Server error ${response.code()}: $errorBody")
            }
        } catch (e: Exception) {
            EnrollmentResult.NetworkError("Network error: ${e.message}")
        }
    }

    /** Token-free enrollment used by the shop setup file: identifies the phone by IMEI. */
    suspend fun autoEnroll(shopId: String, enrollSecret: String): EnrollmentResult {
        return try {
            val deviceInfo = DeviceInfoCollector.collect(context, "")
            val response = ApiClient.service.autoEnroll(AutoEnrollRequest(shopId, enrollSecret, deviceInfo))
            if (response.isSuccessful) {
                val body = response.body()
                if (body != null && body.success) {
                    body.deviceId?.let { AppPreferences.setDeviceId(context, it) }
                    body.deviceToken?.let { AppPreferences.setDeviceToken(context, it) }
                    body.commandKey?.let { AppPreferences.setCommandKey(context, it) }
                    AppPreferences.setEnrolled(context, true)
                    body.agreement?.let {
                        AppPreferences.setAgreementStatusJson(context, com.google.gson.Gson().toJson(it))
                    }
                    EnrollmentResult.Success(body)
                } else {
                    EnrollmentResult.Failure(body?.message ?: "Registration rejected by server")
                }
            } else {
                EnrollmentResult.Failure("Server error ${response.code()}: ${response.errorBody()?.string()}")
            }
        } catch (e: Exception) {
            EnrollmentResult.NetworkError("Network error: ${e.message}")
        }
    }
}
