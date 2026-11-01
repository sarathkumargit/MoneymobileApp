package com.moneymobile.installment.service

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.moneymobile.installment.util.AppPreferences
import com.moneymobile.installment.util.FcmCommand

/**
 * Receives Firebase pushes from the shop. Two kinds:
 *  - a signed LOCK / UNLOCK the phone applies immediately (works even if the website is unreachable),
 *  - anything else = a nudge to check in now (so a phone that only had a push channel still syncs).
 * Either way we also ask ManagementService to check in, so the server state and dashboard stay in step.
 */
class FcmService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Log.i(TAG, "New FCM token")
        AppPreferences.setFcmToken(this, token)
        // Push it up to the server on the next check-in.
        if (AppPreferences.isEnrolled(this) && !AppPreferences.isReleased(this)) ManagementService.syncNow(this)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (AppPreferences.isReleased(this)) return
        val command = FcmCommand.verified(this, message.data)
        when (command) {
            "LOCK"   -> ManagementService.applyFromPush(this, lock = true)
            "UNLOCK" -> ManagementService.applyFromPush(this, lock = false)
            else     -> ManagementService.syncNow(this) // unsigned/sync push: just check in
        }
    }

    companion object { private const val TAG = "FcmService" }
}
