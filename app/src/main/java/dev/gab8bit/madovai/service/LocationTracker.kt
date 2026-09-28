package dev.gab8bit.madovai.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.gab8bit.madovai.model.LatLon
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plain framework LocationManager (GPS + network providers) — no Google Play Services
 * dependency, so it works on de-Googled devices too.
 */
class LocationTracker(context: Context) {
    enum class Permission { NOT_DETERMINED, GRANTED, DENIED }

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val _permission = MutableStateFlow(if (hasPermission()) Permission.GRANTED else Permission.NOT_DETERMINED)
    val permission: StateFlow<Permission> = _permission.asStateFlow()
    private val _currentLocation = MutableStateFlow<LatLon?>(null)
    val currentLocation: StateFlow<LatLon?> = _currentLocation.asStateFlow()

    private var updating = false
    private var bestAccuracy: Float = Float.MAX_VALUE
    private var bestTime = 0L

    private val listener = LocationListener { location -> onLocation(location) }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Result of the runtime permission request (granted = any of fine/coarse). */
    fun onPermissionResult(granted: Boolean) {
        _permission.value = if (granted || hasPermission()) Permission.GRANTED else Permission.DENIED
        if (_permission.value == Permission.GRANTED) startUpdating()
    }

    fun refreshPermission() {
        if (hasPermission()) _permission.value = Permission.GRANTED
    }

    @SuppressLint("MissingPermission")
    fun startUpdating() {
        if (updating || !hasPermission()) return
        updating = true
        _permission.value = Permission.GRANTED
        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        for (provider in providers) {
            runCatching { manager.getLastKnownLocation(provider) }.getOrNull()?.let { onLocation(it) }
            runCatching { manager.requestLocationUpdates(provider, 5_000L, 25f, listener, Looper.getMainLooper()) }
        }
    }

    fun stopUpdating() {
        if (!updating) return
        updating = false
        runCatching { manager.removeUpdates(listener) }
    }

    private fun onLocation(location: Location) {
        // Prefer fresh fixes; accept a less accurate one only if the last good fix is stale.
        val now = System.currentTimeMillis()
        val accuracy = if (location.hasAccuracy()) location.accuracy else 500f
        val stale = now - bestTime > 30_000L
        if (!stale && accuracy > bestAccuracy * 2 && _currentLocation.value != null) return
        bestAccuracy = accuracy
        bestTime = now
        _currentLocation.value = LatLon(location.latitude, location.longitude)
    }
}
