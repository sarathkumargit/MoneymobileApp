package com.moneymobile.installment

import android.app.Application
import com.moneymobile.installment.api.ApiClient

/** Runs before any activity, service or receiver in the app process. */
class MoneyMobileApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ApiClient.init(this)
    }
}
