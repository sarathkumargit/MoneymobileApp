package com.moneymobile.installment.util

import android.content.Context
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Checks the shop's "30-min unlock code" ON the phone — no internet needed.
 *
 * The shop server and this phone share a secret without any extra setup: the phone holds its
 * device token, the server stores sha256(device token). The code is a 6-digit HMAC over this
 * phone's enrollment id and a 5-minute time step (like an authenticator app).
 *
 *  - Valid for codes made in the last 30 minutes (+5 min slack for a wrong clock).
 *  - A code (or any older one) can't be used twice.
 *  - 5 wrong tries → 10-minute wait, so the 6 digits can't be guessed.
 *
 * Keep in sync with src/lib/offline-unlock.ts in the web app.
 */
object OfflineUnlock {
    private const val STEP_SECONDS = 300L
    private const val PAST_STEPS = 6     // 30 minutes back
    private const val FUTURE_STEPS = 1   // 5 minutes forward (clock slack)
    private const val MAX_FAILS = 5
    private const val BLOCK_MS = 10 * 60 * 1000L

    sealed class Result {
        object Ok : Result()
        object Wrong : Result()
        object Used : Result()
        data class Blocked(val minutesLeft: Long) : Result()
        object NotEnrolled : Result()
    }

    fun verify(context: Context, input: String): Result {
        val deviceId = AppPreferences.getDeviceId(context) ?: return Result.NotEnrolled
        val token = AppPreferences.getDeviceToken(context) ?: return Result.NotEnrolled
        val now = System.currentTimeMillis()
        val blockedUntil = AppPreferences.getOfflineBlockedUntil(context)
        if (blockedUntil > now) return Result.Blocked((blockedUntil - now + 59_999) / 60_000)

        val code = input.filter { it.isDigit() }
        val key = sha256Hex(token)
        val nowStep = now / 1000 / STEP_SECONDS
        val usedStep = AppPreferences.getOfflineUsedStep(context)
        var matchedUsed = false
        for (step in (nowStep - PAST_STEPS)..(nowStep + FUTURE_STEPS)) {
            if (code.length == 6 && codeFor(key, deviceId, step) == code) {
                if (step <= usedStep) { matchedUsed = true; continue }
                AppPreferences.setOfflineUsedStep(context, step)
                AppPreferences.setOfflineFailures(context, 0, 0L)
                return Result.Ok
            }
        }
        if (matchedUsed) return Result.Used
        val fails = AppPreferences.getOfflineFailCount(context) + 1
        if (fails >= MAX_FAILS) {
            AppPreferences.setOfflineFailures(context, 0, now + BLOCK_MS)
            return Result.Blocked(BLOCK_MS / 60_000)
        }
        AppPreferences.setOfflineFailures(context, fails, 0L)
        return Result.Wrong
    }

    fun triesLeft(context: Context): Int = MAX_FAILS - AppPreferences.getOfflineFailCount(context)

    private fun codeFor(keyHex: String, deviceId: String, step: Long): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyHex.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val h = mac.doFinal("mm-unlock:$deviceId:$step".toByteArray(Charsets.UTF_8))
        val off = h[h.size - 1].toInt() and 0x0f
        val bin = ((h[off].toInt() and 0x7f) shl 24) or
            ((h[off + 1].toInt() and 0xff) shl 16) or
            ((h[off + 2].toInt() and 0xff) shl 8) or
            (h[off + 3].toInt() and 0xff)
        return (bin % 1_000_000).toString().padStart(6, '0')
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
