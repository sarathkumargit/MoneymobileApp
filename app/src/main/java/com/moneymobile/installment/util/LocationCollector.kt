package com.moneymobile.installment.util

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.util.Log
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

data class LocationFix(val lat: Double, val lng: Double, val accuracy: Int)

/**
 * Returns the phone's location for a heartbeat.
 *  1. A recent cached fix (< 5 min) — free, no battery.
 *  2. Otherwise actively requests ONE fresh fix (network first, then GPS) with a timeout.
 *  3. Otherwise any cached fix, however old.
 * Returns null only when location is off or no provider can produce a fix.
 */
object LocationCollector {

    private val PROVIDERS = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    )

    @SuppressLint("MissingPermission")
    fun collect(context: Context): LocationFix? {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            bestLastKnown(lm, 5 * 60 * 1000L)?.let { return it.toFix() }
            requestSingle(lm, 10_000L)?.let { return it.toFix() }
            bestLastKnown(lm, Long.MAX_VALUE)?.toFix()
        } catch (e: Exception) {
            Log.e("LocationCollector", "collect failed: ${e.message}")
            null
        }
    }

    /**
     * Non-blocking variant for the heartbeat: returns the best cached fix immediately and
     * reports whether it is stale, so the caller can refresh it in the background instead of
     * holding the heartbeat (and any pending Lock/Unlock command) for up to 10s.
     */
    @SuppressLint("MissingPermission")
    fun collectCached(context: Context): Pair<LocationFix?, Boolean> {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            bestLastKnown(lm, 5 * 60 * 1000L)?.let { return it.toFix() to false }
            bestLastKnown(lm, Long.MAX_VALUE)?.toFix() to true
        } catch (e: Exception) {
            Log.e("LocationCollector", "collectCached failed: ${e.message}")
            null to true
        }
    }

    /** Requests one fresh fix (blocking, up to timeoutMs) so the OS cache is warm for the next heartbeat. */
    @SuppressLint("MissingPermission")
    fun refresh(context: Context, timeoutMs: Long = 10_000L) {
        try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            requestSingle(lm, timeoutMs)
        } catch (e: Exception) {
            Log.w("LocationCollector", "refresh failed: ${e.message}")
        }
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(lm: LocationManager, maxAgeMs: Long): Location? {
        var best: Location? = null
        val now = System.currentTimeMillis()
        for (p in PROVIDERS) {
            try {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (now - l.time > maxAgeMs) continue
                if (best == null || l.time > best!!.time) best = l
            } catch (e: Exception) {
                Log.w("LocationCollector", "lastKnown $p: ${e.message}")
            }
        }
        return best
    }

    /** Blocks up to timeoutMs for one fresh fix. Runs the callback on the main looper. */
    @SuppressLint("MissingPermission")
    private fun requestSingle(lm: LocationManager, timeoutMs: Long): Location? {
        val enabled = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER).filter {
            try { lm.isProviderEnabled(it) } catch (e: Exception) { false }
        }
        if (enabled.isEmpty()) { Log.w("LocationCollector", "no location provider enabled (is Location on?)"); return null }
        val latch = CountDownLatch(1)
        val result = AtomicReference<Location?>()
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { result.set(location); latch.countDown() }
            override fun onProviderDisabled(provider: String) {}
            override fun onProviderEnabled(provider: String) {}
            @Deprecated("deprecated in API level 29") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        }
        try {
            enabled.forEach { lm.requestLocationUpdates(it, 0L, 0f, listener, Looper.getMainLooper()) }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w("LocationCollector", "active request failed: ${e.message}")
        } finally {
            try { lm.removeUpdates(listener) } catch (e: Exception) {}
        }
        return result.get()
    }

    private fun Location.toFix() = LocationFix(latitude, longitude, accuracy.toInt())
}
