package com.moneymobile.installment.util

import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.moneymobile.installment.admin.DeviceAdminReceiver
import com.moneymobile.installment.api.ApiClient
import java.net.HttpURLConnection
import java.net.URL

/**
 * Wi-Fi for a LOCKED phone. The kiosk hides Settings and the quick-settings panel, so without
 * this a customer whose Wi-Fi dropped (or who took the SIM out) could never get back online to
 * receive the shop's Unlock. As Device Owner the app may turn Wi-Fi on and join networks itself
 * (Android exempts Device Owners from the Android 10 restrictions on this).
 */
object WifiHelper {
    private const val TAG = "WifiHelper"

    data class Network(val ssid: String, val secured: Boolean, val level: Int)

    private fun wifi(ctx: Context) = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /** Make sure Wi-Fi (and location, needed to list networks) is switched on. */
    @Suppress("DEPRECATION")
    fun ensureOn(ctx: Context) {
        try {
            val wm = wifi(ctx)
            if (!wm.isWifiEnabled) Log.i(TAG, "Wi-Fi was off — turning on: ${wm.setWifiEnabled(true)}")
        } catch (e: Exception) { Log.w(TAG, "ensureOn: ${e.message}") }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                if (dpm.isDeviceOwnerApp(ctx.packageName)) {
                    dpm.setLocationEnabled(ComponentName(ctx, DeviceAdminReceiver::class.java), true)
                }
            } catch (e: Exception) { Log.w(TAG, "location on: ${e.message}") }
        }
    }

    fun isWifiOn(ctx: Context): Boolean = try { wifi(ctx).isWifiEnabled } catch (e: Exception) { false }

    /** Name of the connected Wi-Fi, or null when not on Wi-Fi. */
    @Suppress("DEPRECATION")
    fun connectedSsid(ctx: Context): String? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
        if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null
        val raw = try { wifi(ctx).connectionInfo?.ssid } catch (e: Exception) { null }
        val name = raw?.trim('"')
        return if (name.isNullOrBlank() || name == "<unknown ssid>") "Wi-Fi" else name
    }

    fun hasInternetLink(ctx: Context): Boolean {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getNetworkCapabilities(cm.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }

    /** Ask Android for a fresh scan; results arrive a few seconds later. */
    @Suppress("DEPRECATION")
    fun startScan(ctx: Context) { try { wifi(ctx).startScan() } catch (e: Exception) { Log.w(TAG, "scan: ${e.message}") } }

    @SuppressLint("MissingPermission")
    fun networks(ctx: Context): List<Network> {
        val results: List<ScanResult> = try { wifi(ctx).scanResults ?: emptyList() } catch (e: Exception) { emptyList() }
        return results
            .filter { !it.SSID.isNullOrBlank() }
            .groupBy { it.SSID }
            .map { (ssid, list) ->
                val best = list.maxByOrNull { it.level }!!
                val caps = best.capabilities ?: ""
                Network(ssid, caps.contains("WPA") || caps.contains("WEP") || caps.contains("SAE") || caps.contains("PSK"),
                    WifiManager.calculateSignalLevel(best.level, 4))
            }
            .sortedByDescending { it.level }
    }

    /** Join a network. Returns false if Android refused (wrong setup / not Device Owner). */
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    fun connect(ctx: Context, ssid: String, password: String?): Boolean {
        return try {
            val wm = wifi(ctx)
            ensureOn(ctx)
            val quoted = "\"$ssid\""
            // Forget an older saved copy of this network (e.g. with a wrong password).
            try { wm.configuredNetworks?.filter { it.SSID == quoted }?.forEach { wm.removeNetwork(it.networkId) } } catch (e: Exception) {}
            val conf = WifiConfiguration().apply {
                SSID = quoted
                if (password.isNullOrEmpty()) {
                    allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE)
                } else {
                    preSharedKey = "\"$password\""
                }
            }
            val id = wm.addNetwork(conf)
            if (id == -1) { Log.w(TAG, "addNetwork refused for $ssid"); return false }
            wm.disconnect()
            val ok = wm.enableNetwork(id, true)
            wm.reconnect()
            Log.i(TAG, "connect $ssid -> id=$id enabled=$ok")
            ok
        } catch (e: Exception) {
            Log.e(TAG, "connect failed: ${e.message}")
            false
        }
    }

    /** True if the shop server answers at all (any HTTP status). Call off the main thread. */
    fun serverReachable(): Boolean = try {
        val c = URL(ApiClient.baseUrl()).openConnection() as HttpURLConnection
        c.connectTimeout = 5000; c.readTimeout = 5000; c.requestMethod = "GET"
        c.responseCode; c.disconnect(); true
    } catch (e: Exception) { false }
}
