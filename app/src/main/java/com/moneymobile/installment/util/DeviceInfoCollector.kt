package com.moneymobile.installment.util

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.telephony.TelephonyManager
import android.util.DisplayMetrics
import android.view.WindowManager
import com.moneymobile.installment.BuildConfig
import com.moneymobile.installment.model.DeviceInfo

@SuppressLint("MissingPermission", "HardwareIds")
object DeviceInfoCollector {

    fun collect(context: Context, enrollmentToken: String): DeviceInfo {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        return DeviceInfo(
            imei = safeGet { tm.imei },
            brand = Build.MANUFACTURER,
            model = Build.MODEL,
            androidVersion = Build.VERSION.RELEASE,
            serialNumber = safeGet { Build.getSerial() },
            phoneNumber = safeGet { tm.line1Number },
            simOperator = safeGet { tm.simOperatorName },
            simIccid = safeGet { tm.simSerialNumber },
            screenResolution = "${metrics.widthPixels}x${metrics.heightPixels}",
            storageGb = getTotalStorage(),
            ramMb = getTotalRam(context),
            enrollmentToken = enrollmentToken,
            appVersion = BuildConfig.VERSION_NAME,
            bootloaderState = bootloaderState()
        )
    }

    /**
     * Whether the phone's bootloader is locked. Reads Android's verified-boot properties
     * ("orange" / flash.locked=0 means unlocked). Returns "unknown" if the phone doesn't say.
     */
    fun bootloaderState(): String {
        fun prop(name: String): String = try {
            val p = Runtime.getRuntime().exec(arrayOf("getprop", name))
            p.inputStream.bufferedReader().use { it.readText() }.trim()
        } catch (e: Exception) { "" }
        val verified = prop("ro.boot.verifiedbootstate")
        val flashLocked = prop("ro.boot.flash.locked")
        val vbmeta = prop("ro.boot.vbmeta.device_state")
        return when {
            verified.equals("orange", true) || flashLocked == "0" || vbmeta.equals("unlocked", true) -> "unlocked"
            verified.equals("green", true) || flashLocked == "1" || vbmeta.equals("locked", true) -> "locked"
            else -> "unknown"
        }
    }

    fun getSimIccid(context: Context): String? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return safeGet { tm.simSerialNumber }
    }

    fun getSimOperator(context: Context): String? {
        val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
        return safeGet { tm.simOperatorName }
    }

    fun getBatteryLevel(context: Context): Int {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val intent = context.registerReceiver(null, filter) ?: return -1
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        return if (level >= 0 && scale > 0) (level * 100 / scale) else -1
    }

    fun getNetworkType(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(network) ?: return "unknown"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
            else -> "other"
        }
    }

    fun isNetworkAvailable(context: Context): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun getTotalStorage(): Long {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        return stat.totalBytes / (1024 * 1024 * 1024) // GB
    }

    private fun getTotalRam(context: Context): Long {
        val actManager = context.getSystemService(Context.ACTIVITY_SERVICE)
                as android.app.ActivityManager
        val memInfo = android.app.ActivityManager.MemoryInfo()
        actManager.getMemoryInfo(memInfo)
        return memInfo.totalMem / (1024 * 1024) // MB
    }

    private fun <T> safeGet(block: () -> T?): T? = try { block() } catch (e: Exception) { null }
}
