package com.moneymobile.installment.util

import android.content.Context
import android.util.Log
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Verifies a lock/unlock command that arrived by Firebase push, so the phone can act on it WITHOUT
 * contacting the server (useful when the phone can reach Google but not the shop's website).
 *
 * Same shared secret as the offline unlock code: HMAC key = SHA-256(device token) in hex, which the
 * server stores as device_token_hash and the phone derives from its own token. A push that isn't
 * signed with that secret is ignored, so a fake FCM message can't lock or unlock a phone.
 *
 * Keep in sync with src/lib/fcm.ts on the server.
 */
object FcmCommand {
    private const val TAG = "FcmCommand"
    private const val MAX_AGE_SECONDS = 600L // reject a push older than 10 minutes (replay guard)

    /** Returns the command ("LOCK"/"UNLOCK") if the push is authentic and fresh, else null. */
    fun verified(context: Context, data: Map<String, String>): String? {
        val deviceId = AppPreferences.getDeviceId(context) ?: return null
        val token = AppPreferences.getDeviceToken(context) ?: return null
        val command = data["command"] ?: return null
        val ts = data["ts"]?.toLongOrNull() ?: return null
        val cmdId = data["cmd_id"] ?: return null
        val sig = data["sig"] ?: return null

        if (command != "LOCK" && command != "UNLOCK") return null
        val ageSec = Math.abs(System.currentTimeMillis() / 1000 - ts)
        if (ageSec > MAX_AGE_SECONDS) { Log.w(TAG, "stale push ($ageSec s)"); return null }
        if (cmdId == AppPreferences.getLastFcmCmdId(context)) { Log.i(TAG, "duplicate push ignored"); return null }

        val key = sha256Hex(token)
        val expected = hmac(key, "fcm:$deviceId:$command:$ts:$cmdId")
        if (!constantTimeEquals(expected, sig)) { Log.w(TAG, "bad signature"); return null }

        AppPreferences.setLastFcmCmdId(context, cmdId)
        return command
    }

    private fun hmac(keyHex: String, message: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyHex.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(message.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun sha256Hex(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }
}
