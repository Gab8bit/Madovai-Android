package dev.gab8bit.madovai.ui.sheets

import dev.gab8bit.madovai.Config
import dev.gab8bit.madovai.data.cotral.AstralTrainRepository
import dev.gab8bit.madovai.data.cotral.TransitsRepository
import dev.gab8bit.madovai.data.gtfs.CotralGtfsStore
import dev.gab8bit.madovai.model.AstralDeparture
import dev.gab8bit.madovai.model.AstralScheduleDirection
import dev.gab8bit.madovai.model.CotralTrainRoute
import dev.gab8bit.madovai.model.NetworkState
import dev.gab8bit.madovai.model.Pole
import dev.gab8bit.madovai.model.Transit
import dev.gab8bit.madovai.model.upcoming
import dev.gab8bit.madovai.net.ApiException
import dev.gab8bit.madovai.service.VehicleTracker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.coroutines.cancellation.CancellationException

/**
 * Port of the iOS `PoleDetailViewModel`: live PIV.do transits for a bus pole, or the
 * ASTRAL → static-GTFS schedule fallback for a rail station. Polling runs only while the
 * sheet is visible (the caller runs [runPolling] inside a lifecycle-aware effect).
 */
class PoleDetailState(
    initialPole: Pole,
    private val vehicleTracker: VehicleTracker,
    private val transitsRepository: TransitsRepository,
    private val gtfsStore: CotralGtfsStore,
    private val astralTrainRepository: AstralTrainRepository,
) {
    private val _pole = MutableStateFlow(initialPole)
    val pole: StateFlow<Pole> = _pole.asStateFlow()
    private val _transits = MutableStateFlow<List<Transit>>(emptyList())
    val transits: StateFlow<List<Transit>> = _transits.asStateFlow()
    private val _state = MutableStateFlow<NetworkState>(NetworkState.Loading)
    val state: StateFlow<NetworkState> = _state.asStateFlow()
    private val _lastUpdated = MutableStateFlow<Long?>(null)
    val lastUpdated: StateFlow<Long?> = _lastUpdated.asStateFlow()

    /** Raw per-direction rail departures — "upcoming" filtering is recomputed at render time. */
    private val _railDirections = MutableStateFlow<List<AstralScheduleDirection>>(emptyList())
    val railDirections: StateFlow<List<AstralScheduleDirection>> = _railDirections.asStateFlow()

    private val poleCode: String = initialPole.codicePalina ?: initialPole.codiceStop ?: ""

    suspend fun runPolling() {
        while (true) {
            refresh()
            delay(Config.TRANSITS_POLL_INTERVAL_MS)
        }
    }

    private suspend fun refresh() {
        if (_pole.value.isTreno) {
            refreshRailDepartures()
            return
        }
        if (poleCode.isEmpty()) {
            _state.value = NetworkState.Error("Codice palina mancante.")
            return
        }
        if (_transits.value.isEmpty()) _state.value = NetworkState.Loading
        try {
            val result = transitsRepository.transits(poleCode)
            if (result == null) {
                _transits.value = emptyList()
                _state.value = NetworkState.Empty
                return
            }
            _pole.value = mergedPole(_pole.value, result.pole)
            // Drop runs whose DELAY-ADJUSTED arrival passed more than ~2 min ago (Cotral can
            // get stuck reporting a run that never arrives). `minutesFromNow` is delay-aware:
            // never filter on the bare scheduled time.
            _transits.value = result.transits
                .filter { (it.minutesFromNow ?: 0) >= -2 }
                .sortedBy { it.minutesFromNow ?: Int.MAX_VALUE }
            _state.value = NetworkState.Loaded
            _lastUpdated.value = System.currentTimeMillis()

            // Followed vehicle no longer alive → tell the tracker right away.
            val followed = vehicleTracker.vehicleCode.value
            if (followed != null) {
                val match = _transits.value.firstOrNull { it.automezzo.codice == followed }
                if (match != null && match.automezzo.isAlive == false) vehicleTracker.reportOffline(followed)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.value = NetworkState.Error((e as? ApiException)?.message ?: e.message ?: "Errore")
        }
    }

    /**
     * Rail poles never touch PIV.do: ASTRAL resolves every candidate direction of this
     * pole's line BY NAME, keeping whichever match; the static GTFS timetable is used only
     * when ASTRAL resolves nothing at all.
     */
    private suspend fun refreshRailDepartures() {
        if (_railDirections.value.isEmpty()) _state.value = NetworkState.Loading
        val p = _pole.value
        val stopId = p.codiceStop
        val railRoute = stopId?.let { id -> gtfsStore.getRoutesForStop(id).firstOrNull { it.routeId.startsWith("F:") } }
        val candidates = railRoute?.let { CotralTrainRoute.directionsForGtfsRouteShortName(it.routeShortName) }
        if (stopId == null || candidates == null) {
            _state.value = NetworkState.Empty
            return
        }
        val stationName = p.nomeStop ?: p.nomePalina ?: ""
        if (stationName.isEmpty()) {
            _state.value = NetworkState.Empty
            return
        }

        val resolved = ArrayList<AstralScheduleDirection>()
        for (route in candidates) {
            try {
                astralTrainRepository.departures(route, stationName)?.let { resolved.add(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                android.util.Log.w("Madovai", "ASTRAL ${route.rawValue} @ '$stationName' failed, trying next candidate", e)
            }
        }
        val directions = if (resolved.isEmpty()) gtfsScheduledDirections(stopId) ?: emptyList() else resolved
        _railDirections.value = mergeSameDestination(directions)
        _state.value = if (directions.isEmpty()) NetworkState.Empty else NetworkState.Loaded
        _lastUpdated.value = System.currentTimeMillis()
    }

    private fun gtfsScheduledDirections(stopId: String): List<AstralScheduleDirection>? {
        val departures = gtfsStore.scheduledDepartures(stopId) ?: return null
        return departures.groupBy { it.destinationStopName }.map { (destination, deps) ->
            AstralScheduleDirection(
                destination = destination,
                entries = deps.map {
                    AstralDeparture(it.tripId, it.departureTime, it.departureSeconds, null, isCancelled = false, isReplacementBus = false)
                },
            )
        }
    }

    /**
     * RVEXT has two direction pairs (Viterbo↔Catalano and Morlupo↔Catalano) that can BOTH
     * head to "Catalano" at a shared station — merged into one tab (same destination for
     * the rider) instead of two identically-labelled ones.
     */
    private fun mergeSameDestination(directions: List<AstralScheduleDirection>): List<AstralScheduleDirection> =
        directions.groupBy { it.destination }.map { (destination, group) ->
            if (group.size == 1) group[0]
            else AstralScheduleDirection(destination, group.flatMap { it.entries }.distinctBy { it.id to it.time })
        }

    companion object {
        /** Upcoming-only, per direction, sorted by destination — recomputed at every render. */
        fun upcomingDirections(raw: List<AstralScheduleDirection>): List<AstralScheduleDirection> =
            raw.map { AstralScheduleDirection(it.destination, it.entries.upcoming()) }.sortedBy { it.destination }

        /**
         * PIV.do's `<codice>` is a SEPARATE id space from GTFS — `base` (always GTFS-derived)
         * keeps its ids. Letting the update win once silently swapped a pole's id mid-session
         * and produced un-removable duplicate favorites. Other fields: prefer fresh values.
         */
        fun mergedPole(base: Pole, update: Pole): Pole = Pole(
            codicePalina = base.codicePalina ?: update.codicePalina.nz(),
            codiceStop = base.codiceStop ?: update.codiceStop.nz(),
            // PIV.do's XML yields "" (not absent) for a missing field — treat as missing so
            // it can't blank out a good GTFS value; same for a 0.0 "no fix" coordinate.
            nomePalina = update.nomePalina.nz() ?: base.nomePalina,
            nomeStop = update.nomeStop.nz() ?: base.nomeStop,
            localita = update.localita.nz() ?: base.localita,
            comune = update.comune.nz() ?: base.comune,
            coordX = if (update.coordinate != null) update.coordX else base.coordX,
            coordY = if (update.coordinate != null) update.coordY else base.coordY,
            zonaTariffaria = update.zonaTariffaria ?: base.zonaTariffaria,
            distanza = update.distanza ?: base.distanza,
            destinazioni = base.destinazioni ?: update.destinazioni,
            isCotral = base.isCotral ?: update.isCotral,
            isCapolinea = update.isCapolinea ?: base.isCapolinea,
            isBanchinato = update.isBanchinato ?: base.isBanchinato,
            preferita = base.preferita ?: update.preferita,
            isTreno = base.isTreno,
        )

        private fun String?.nz(): String? = this?.takeIf { it.isNotEmpty() }
    }
}
