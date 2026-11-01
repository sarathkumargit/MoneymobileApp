package com.moneymobile.installment.api

import com.moneymobile.installment.model.*
import retrofit2.Response
import retrofit2.http.*

interface ApiService {

    /** Called once during USB enrollment */
    @POST("devices/enroll")
    suspend fun enrollDevice(
        @Body request: EnrollmentRequest
    ): Response<EnrollmentResponse>

    /** Token-free setup: register this phone to a shop by its IMEI + the shop secret */
    @POST("devices/auto-enroll")
    suspend fun autoEnroll(
        @Body request: AutoEnrollRequest
    ): Response<EnrollmentResponse>

    /** Periodic status ping from the device (every 4 hours) */
    @POST("devices/heartbeat")
    suspend fun sendHeartbeat(
        @Body request: HeartbeatRequest
    ): Response<HeartbeatResponse>

    /** Report security events (SIM change, admin disabled, tamper) */
    @POST("devices/security-event")
    suspend fun reportSecurityEvent(
        @Body request: SecurityEventRequest
    ): Response<ApiResponse<Unit>>

    /** Customer taps "Request unlock" — send device + customer details to the shop */
    @POST("devices/unlock-request")
    suspend fun requestUnlock(
        @Body request: UnlockRequestBody
    ): Response<ApiResponse<Unit>>

    /** Acknowledge a command received from backend */
    @POST("devices/command-ack")
    suspend fun acknowledgeCommand(
        @Body request: CommandAckRequest
    ): Response<ApiResponse<Unit>>

    /** Poll for any pending commands */
    @GET("devices/{deviceId}/commands")
    suspend fun getPendingCommands(
        @Path("deviceId") deviceId: String,
        @Header("Authorization") token: String
    ): Response<ApiResponse<DeviceCommand>>
}
