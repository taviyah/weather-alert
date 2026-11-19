package com.weatheralert.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.weatheralert.model.WatchedLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import kotlin.coroutines.resume

/**
 * One-shot coarse fix for the "use my location" button.
 *
 * Uses the platform [LocationManager] rather than Play Services so the app keeps working on
 * devices without Google services — which includes a good share of the e-paper phones this is
 * being built for. Coarse accuracy is plenty: the forecast grid is ~11 km wide.
 */
object DeviceLocation {

    private const val TAG = "WeatherAlert"

    class LocationException(message: String) : Exception(message)

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

    fun locationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(LocationManager::class.java) ?: return false
        return lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
    }

    /**
     * [maxCachedAgeMs] bounds how old a cached fix may be before a fresh one is waited for.
     * Adding a place is happy with any fix; "what's the weather where I am" is not, since the
     * last cached fix can be from yesterday in another city. A stale fix is still used if no
     * fresh one arrives, because a forecast for roughly-here beats none.
     */
    @SuppressLint("MissingPermission")
    suspend fun current(context: Context, maxCachedAgeMs: Long = Long.MAX_VALUE): WatchedLocation {
        if (!hasPermission(context)) throw LocationException("Location permission not granted")
        if (!locationEnabled(context)) throw LocationException("Location is turned off on this device")

        val lm = context.getSystemService(LocationManager::class.java)
            ?: throw LocationException("No location service")

        // A cached fix is instant and, at forecast-grid resolution, almost always good enough.
        val cached = listOf(
            LocationManager.NETWORK_PROVIDER,
            LocationManager.GPS_PROVIDER,
            LocationManager.PASSIVE_PROVIDER,
        ).mapNotNull { provider ->
            runCatching { lm.getLastKnownLocation(provider) }.getOrNull()
        }.maxByOrNull { it.elapsedRealtimeNanos }

        val ageMs = cached?.let {
            (SystemClock.elapsedRealtimeNanos() - it.elapsedRealtimeNanos) / 1_000_000
        }
        val fresh = cached != null && ageMs != null && ageMs <= maxCachedAgeMs
        val fix = (if (fresh) cached else withTimeoutOrNull(20_000) { awaitFix(lm) } ?: cached)
            ?: throw LocationException("Could not get a location fix — try again outdoors")

        return describe(context, fix)
    }

    @SuppressLint("MissingPermission")
    private suspend fun awaitFix(lm: LocationManager): Location? =
        suspendCancellableCoroutine { cont ->
            val provider = when {
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                else -> null
            }
            if (provider == null) {
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val signal = CancellationSignal()
                cont.invokeOnCancellation { signal.cancel() }
                lm.getCurrentLocation(provider, signal, Runnable::run) { loc ->
                    if (cont.isActive) cont.resume(loc)
                }
            } else {
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(location: Location) {
                        lm.removeUpdates(this)
                        if (cont.isActive) cont.resume(location)
                    }

                    @Deprecated("Required on API < 29")
                    override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) = Unit
                    override fun onProviderEnabled(p: String) = Unit
                    override fun onProviderDisabled(p: String) = Unit
                }
                cont.invokeOnCancellation { lm.removeUpdates(listener) }
                lm.requestSingleUpdate(provider, listener, null)
            }
        }

    /**
     * Names the fix. Reverse geocoding needs a backend that plenty of devices do not have, so a
     * failure here degrades to coordinates rather than blocking the add.
     */
    private suspend fun describe(context: Context, fix: Location): WatchedLocation =
        withContext(Dispatchers.IO) {
            val fallbackName = String.format(Locale.US, "%.3f, %.3f", fix.latitude, fix.longitude)
            var name = fallbackName
            var admin: String? = null
            var country: String? = null

            if (Geocoder.isPresent()) {
                runCatching {
                    @Suppress("DEPRECATION")
                    val results = Geocoder(context, Locale.getDefault())
                        .getFromLocation(fix.latitude, fix.longitude, 1)
                    results?.firstOrNull()?.let { a ->
                        name = a.locality ?: a.subAdminArea ?: a.adminArea ?: fallbackName
                        admin = a.adminArea?.takeIf { it != name }
                        country = a.countryName
                    }
                }.onFailure { Log.w(TAG, "reverse geocode failed: ${it.message}") }
            }

            WatchedLocation(
                id = WatchedLocation.idFor(fix.latitude, fix.longitude),
                name = name,
                admin = admin,
                country = country,
                latitude = fix.latitude,
                longitude = fix.longitude,
            )
        }
}
