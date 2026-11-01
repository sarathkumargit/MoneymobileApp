package com.moneymobile.installment.monitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** SIM broadcasts (delivered on older Android; the service also checks on newer ones). */
class SimChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        Log.d("SimChangeReceiver", "Received: $action")
        if (action == "android.intent.action.SIM_STATE_CHANGED" ||
            action == "android.telephony.action.SIM_APPLICATION_STATE_CHANGED" ||
            action == "android.telephony.action.SIM_CARD_STATE_CHANGED") {
            SimWatcher.check(context)
        }
    }
}
