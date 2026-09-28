package dev.gab8bit.madovai.service

import dev.gab8bit.madovai.Config
import dev.gab8bit.madovai.data.cotral.VehicleRepository
import dev.gab8bit.madovai.model.LatLon
import dev.gab8bit.madovai.model.VehicleTrackingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Tracks at most one live Cotral vehicle ("Segui"), independent of which pole sheet is
 * open — owned app-wide so the map marker and loss banner survive the sheet closing.
 * All state is touched on the main thread (scope uses Dispatchers.Main).
 */
class VehicleTracker(private val repository: VehicleRepository, private val scope: CoroutineScope) {
    private val _vehicleCode = MutableStateFlow<String?>(null)
    val vehicleCode: StateFlow<String?> = _vehicleCode.asStateFlow()
    private val _isTreno = MutableStateFlow(false)
    val isTreno: StateFlow<Boolean> = _isTreno.asStateFlow()
    private val _coordinate = MutableStateFlow<LatLon?>(null)
    val coordinate: StateFlow<LatLon?> = _coordinate.asStateFlow()
    private val _state = MutableStateFlow<VehicleTrackingState>(VehicleTrackingState.Idle)
    val state: StateFlow<VehicleTrackingState> = _state.asStateFlow()
    private var lastUpdated: Long? = null

    private var pollJob: Job? = null
    private var consecutiveMisses = 0
    /** Paused while the app is in background; resumed with the same vehicle. */
    private var paused = false

    fun isFollowing(code: String): Boolean = _vehicleCode.value == code

    fun toggleFollowing(code: String, isTreno: Boolean = false) {
        if (isFollowing(code)) stopFollowing() else startFollowing(code, isTreno)
    }

    fun startFollowing(code: String, isTreno: Boolean = false) {
        pollJob?.cancel()
        _vehicleCode.value = code
        _isTreno.value = isTreno
        _coordinate.value = null
        _state.value = VehicleTrackingState.Tracking
        consecutiveMisses = 0
        if (!paused) launchPolling(code)
    }

    fun stopFollowing() {
        pollJob?.cancel()
        pollJob = null
        _vehicleCode.value = null
        _isTreno.value = false
        _coordinate.value = null
        _state.value = VehicleTrackingState.Idle
        consecutiveMisses = 0
    }

    fun pause() {
        paused = true
        pollJob?.cancel()
        pollJob = null
    }

    fun resume() {
        paused = false
        val code = _vehicleCode.value ?: return
        if (pollJob?.isActive != true) launchPolling(code)
    }

    /** Called by a pole sheet's transits poll when it sees `isAlive == false` for the followed vehicle. */
    fun reportOffline(code: String) {
        if (_vehicleCode.value != code || _state.value == VehicleTrackingState.Idle) return
        markLost()
    }

    private fun launchPolling(code: String) {
        pollJob = scope.launch {
            while (isActive) {
                refresh(code)
                delay(Config.VEHICLE_POSITION_POLL_INTERVAL_MS)
            }
        }
    }

    private suspend fun refresh(code: String) {
        try {
            val newCoordinate = repository.positions(code).firstOrNull()?.latestCoordinate
            if (newCoordinate != null) {
                _coordinate.value = newCoordinate
                _state.value = VehicleTrackingState.Tracking
                lastUpdated = System.currentTimeMillis()
                consecutiveMisses = 0
            } else {
                registerMiss()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            registerMiss()
        }
    }

    private fun registerMiss() {
        consecutiveMisses++
        if (consecutiveMisses >= Config.VEHICLE_TRACKING_LOSS_THRESHOLD) markLost()
    }

    private fun markLost() {
        if (_state.value == VehicleTrackingState.Idle) return
        _state.value = VehicleTrackingState.Lost(lastUpdated)
    }
}
