package dev.gab8bit.madovai.service

import dev.gab8bit.madovai.Config
import dev.gab8bit.madovai.data.cotral.TransitsRepository
import dev.gab8bit.madovai.data.cotral.VehicleRepository
import dev.gab8bit.madovai.model.CotralTrainRoute
import dev.gab8bit.madovai.model.Pole
import dev.gab8bit.madovai.model.Transit
import dev.gab8bit.madovai.model.TransitOperator
import dev.gab8bit.madovai.model.TransitVehicle
import dev.gab8bit.madovai.model.TransitVehicleKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * Opportunistically shows Cotral vehicles for the poles currently visible on screen.
 * There is no "all Cotral vehicles" endpoint — a vehicle only becomes visible after
 * querying a pole's transits and finding a monitored run with a vehicle code, so this
 * is partial coverage by construction (VisibilityScope.VISIBLE_AREA_ONLY).
 * State is only touched on the main thread (scope uses Dispatchers.Main).
 */
class CotralViewportVehicleService(
    private val transitsRepository: TransitsRepository,
    private val vehicleRepository: VehicleRepository,
    private val scope: CoroutineScope,
) {
    private val _vehicles = MutableStateFlow<List<TransitVehicle>>(emptyList())
    val vehicles: StateFlow<List<TransitVehicle>> = _vehicles.asStateFlow()

    private var currentPoleCodes: List<String> = emptyList()
    private var pollJob: Job? = null
    private var vehiclesById: Map<String, TransitVehicle> = emptyMap()
    private var missesById: Map<String, Int> = emptyMap()
    private var isScanning = false
    private var needsRescan = false

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                delay(Config.COTRAL_VIEWPORT_VEHICLE_POLL_INTERVAL_MS)
                requestScan()
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Called whenever the visible Cotral poles change (viewport settle) — scans immediately. */
    fun updateVisiblePoles(poles: List<Pole>) {
        currentPoleCodes = poles.mapNotNull { it.codicePalina }.take(Config.COTRAL_VIEWPORT_POLE_QUERY_LIMIT)
        scope.launch { requestScan() }
    }

    /**
     * Serializes every trigger (periodic tick, viewport settle): a request arriving
     * mid-scan is coalesced into exactly ONE follow-up scan instead of running
     * concurrently — overlapping scans finishing out of order made vehicles blink.
     */
    private suspend fun requestScan() {
        if (isScanning) {
            needsRescan = true
            return
        }
        scan()
        while (needsRescan) {
            needsRescan = false
            scan()
        }
    }

    private suspend fun scan() {
        isScanning = true
        try {
            val poleCodes = currentPoleCodes
            if (poleCodes.isEmpty()) {
                // Genuinely no Cotral poles in view — nothing to keep a grace period for.
                vehiclesById = emptyMap()
                missesById = emptyMap()
                _vehicles.value = emptyList()
                return
            }

            val transitsByVehicle: Map<String, Transit> = coroutineScope {
                poleCodes.map { code ->
                    async {
                        val result = try {
                            transitsRepository.transits(code)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        result?.transits
                            ?.filter { it.canTrackVehicle }
                            ?.mapNotNull { t -> t.automezzo.codice?.let { it to t } }
                            ?: emptyList()
                    }
                }.awaitAll().flatten().toMap()
            }

            val fresh: List<TransitVehicle> = coroutineScope {
                transitsByVehicle.map { (code, transit) ->
                    async {
                        val coordinate = try {
                            vehicleRepository.positions(code).firstOrNull()?.latestCoordinate
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        } ?: return@async null
                        val routeId = transit.percorso.ifEmpty { null }
                        // PIV.do's `percorso` doubles as ASTRAL's codicePercorso for a rail run.
                        val kind = if (CotralTrainRoute.fromRaw(routeId) != null) TransitVehicleKind.TRENO else TransitVehicleKind.BUS
                        TransitVehicle(
                            id = "cotral-$code",
                            coordinate = coordinate,
                            bearing = null,
                            routeId = routeId,
                            routeLabel = routeId,
                            kind = kind,
                            transitOperator = TransitOperator.COTRAL,
                            scope = TransitVehicle.VisibilityScope.VISIBLE_AREA_ONLY,
                            // Delay only when genuinely live-tracked.
                            delaySeconds = if (transit.isDelayReliable) transit.ritardoSeconds else null,
                            tripId = transit.idCorsa.ifEmpty { null },
                        )
                    }
                }.awaitAll().filterNotNull()
            }

            // A vehicle missing from one cycle (any transient hiccup) keeps its last known
            // position for a couple of misses instead of blinking off the map.
            val merged = HashMap<String, TransitVehicle>()
            val mergedMisses = HashMap<String, Int>()
            for (v in fresh) {
                merged[v.id] = v
                mergedMisses[v.id] = 0
            }
            val freshIds = fresh.map { it.id }.toSet()
            for ((id, v) in vehiclesById) {
                if (id in freshIds) continue
                val misses = (missesById[id] ?: 0) + 1
                if (misses > Config.VEHICLE_TRACKING_LOSS_THRESHOLD) continue
                merged[id] = v
                mergedMisses[id] = misses
            }
            vehiclesById = merged
            missesById = mergedMisses
            _vehicles.value = merged.values.toList()
        } finally {
            isScanning = false
        }
    }
}
