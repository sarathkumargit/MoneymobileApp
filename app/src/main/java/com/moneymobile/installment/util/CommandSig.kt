package com.moneymobile.installment.util

import android.content.Context
import android.util.Log
import com.moneymobile.installment.model.AgreementStatus
import com.moneymobile.installment.model.AppUpdateInfo
import com.moneymobile.installment.model.DeviceCommand
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Checks that a command / lock state really came from the shop server.
 *
 * The server signs them with a per-phone key that it handed to this phone once (never sent back to
 * the server, unlike the device token). A reply that is forged or altered on the way fails here and
 * is ignored — it can no longer unlock or release the phone.
 *
 * A phone that has no key yet (registered by an older build) keeps working: the server hands it the
 * key on its next check-in. Keep in sync with src/lib/devices/command-sign.ts on the server.
 */
object CommandSig {
    private const val TAG = "CommandSig"
    /** A signed lock state older/newer than this (vs the phone's trusted clock) is a replay. */
    private const val MAX_SKEW_MS = 2 * 60 * 60 * 1000L

    fun hasKey(context: Context): Boolean = !AppPreferences.getCommandKey(context).isNullOrBlank()

    /** Command is acceptable: no key yet (legacy phone), or the signature matches. */
    fun commandOk(context: Context, cmd: DeviceCommand): Boolean {
        val key = AppPreferences.getCommandKey(context)?.takeIf { it.isNotBlank() } ?: return true
        val enrId = AppPreferences.getDeviceId(context) ?: return false
        val sig = cmd.sig ?: return false
        return equal(hmac(key, "cmd|$enrId|${cmd.id}|${cmd.type.name}|${cmd.issuedAt}"), sig)
    }

    /** Lock state is acceptable: no key yet (legacy phone), or the signature matches and is fresh. */
    fun agreementOk(context: Context, a: AgreementStatus): Boolean {
        val key = AppPreferences.getCommandKey(context)?.takeIf { it.isNotBlank() } ?: return true
        val enrId = AppPreferences.getDeviceId(context) ?: return false
        val sig = a.sig ?: return false
        val serverTime = a.serverTime ?: return false
        val expected = hmac(key, "agr|$enrId|${a.id}|${if (a.locked == true) 1 else 0}|${a.overdueLockAt ?: 0L}|$serverTime")
        if (!equal(expected, sig)) { Log.w(TAG, "agreement signature mismatch"); return false }
        val skew = Math.abs(serverTime - OfflineLockGuard.trustedNow(context))
        if (skew > MAX_SKEW_MS) { Log.w(TAG, "agreement is stale/replayed (skew ${skew / 1000}s)"); return false }
        return true
    }

    /** The following months' payment deadlines — only if the server signed them (or the phone has no key yet). */
    fun laterLockAt(context: Context, a: AgreementStatus): List<Long> {
        val list = a.laterLockAt ?: return emptyList()
        val key = AppPreferences.getCommandKey(context)?.takeIf { it.isNotBlank() } ?: return list
        val enrId = AppPreferences.getDeviceId(context) ?: return emptyList()
        val serverTime = a.serverTime ?: return emptyList()
        val sig = a.laterSig ?: return emptyList()
        val expected = hmac(key, "dl|$enrId|${a.id}|${list.joinToString(",")}|$serverTime")
        return if (equal(expected, sig)) list else emptyList()
    }

    /**
     * An app-update offer is acceptable only when the server signed it. Unlike commands, a phone without
     * a key never installs anything (it gets its key on the next check-in anyway).
     */
    fun updateOk(context: Context, u: AppUpdateInfo): Boolean {
        val key = AppPreferences.getCommandKey(context)?.takeIf { it.isNotBlank() } ?: return false
        val enrId = AppPreferences.getDeviceId(context) ?: return false
        val sig = u.sig ?: return false
        return equal(hmac(key, "upd|$enrId|${u.requestId}|${u.versionCode}|${u.sha256}|${u.size}"), sig)
    }

    /**
     * Reset-protection account list is acceptable: no key yet (legacy phone), or the server signed exactly
     * this list. A forged reply can then never remove or change a phone's reset protection.
     */
    fun frpOk(context: Context, ids: List<String>, sig: String?): Boolean {
        val key = AppPreferences.getCommandKey(context)?.takeIf { it.isNotBlank() } ?: return true
        val enrId = AppPreferences.getDeviceId(context) ?: return false
        if (sig.isNullOrBlank()) return false
        return equal(hmac(key, "frp|$enrId|${ids.joinToString(",")}"), sig)
    }

    private fun hmac(keyHex: String, msg: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(keyHex.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(msg.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    private fun equal(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var r = 0
        for (i in a.indices) r = r or (a[i].code xor b[i].code)
        return r == 0
    }
}
