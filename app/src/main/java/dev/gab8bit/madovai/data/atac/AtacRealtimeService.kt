package dev.gab8bit.madovai.data.atac

import com.google.transit.realtime.GtfsRealtime
import dev.gab8bit.madovai.Config
import dev.gab8bit.madovai.model.AtacStopPrediction
import dev.gab8bit.madovai.model.AtacTripStopTime
import dev.gab8bit.madovai.model.LatLon
import dev.gab8bit.madovai.model.TransitOperator
import dev.gab8bit.madovai.model.TransitVehicle
import dev.gab8bit.madovai.model.TransitVehicleKind
import dev.gab8bit.madovai.net.HttpClients
import dev.gab8bit.madovai.net.getBytes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/**
 * Polls Rome's two GTFS-Realtime (protobuf) feeds. Unlike Cotral, a single
 * `vehicle_positions` call returns every active vehicle in the network at once.
 */
class AtacRealtimeService(private val gtfsStore: AtacGtfsStore, private val scope: CoroutineScope) {
    private val _vehicles = MutableStateFlow<List<TransitVehicle>>(emptyList())
    val vehicles: StateFlow<List<TransitVehicle>> = _vehicles.asStateFlow()

    /** stopId → upcoming predictions, soonest first (built entirely from trip_updates). */
    private val _stopPredictions = MutableStateFlow<Map<String, List<AtacStopPrediction>>>(emptyMap())
    val stopPredictions: StateFlow<Map<String, List<AtacStopPrediction>>> = _stopPredictions.asStateFlow()

    /** tripId → full stop-by-stop itinerary, sorted by stop sequence. */
    private val _tripStopTimes = MutableStateFlow<Map<String, List<AtacTripStopTime>>>(emptyMap())
    val tripStopTimes: StateFlow<Map<String, List<AtacTripStopTime>>> = _tripStopTimes.asStateFlow()

    private val _lastUpdated = MutableStateFlow<Long?>(null)
    val lastUpdated: StateFlow<Long?> = _lastUpdated.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private var pollJob: Job? = null

    fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                refresh()
                delay(Config.ATAC_REALTIME_POLL_INTERVAL_MS)
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** One fetch+decode of both feeds (also used directly by the JVM smoke test). */
    suspend fun refresh() {
        try {
            val (vehicles, updates) = coroutineScope {
                val v = async(Dispatchers.IO) { HttpClients.api.getBytes(Config.ATAC_VEHICLE_POSITIONS_URL) }
                val u = async(Dispatchers.IO) { HttpClients.api.getBytes(Config.ATAC_TRIP_UPDATES_URL) }
                v.await() to u.await()
            }
            val decoded = withContext(Dispatchers.Default) {
                val vehicleFeed = GtfsRealtime.FeedMessage.parseFrom(vehicles)
                val updatesFeed = GtfsRealtime.FeedMessage.parseFrom(updates)
                decodeVehicles(vehicleFeed) to decodeTripUpdates(updatesFeed)
            }
            _vehicles.value = decoded.first
            _stopPredictions.value = decoded.second.first
            _tripStopTimes.value = decoded.second.second
            _lastUpdated.value = System.currentTimeMillis()
            _lastError.value = null
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _lastError.value = e.message ?: e.toString()
        }
    }

    private fun decodeVehicles(feed: GtfsRealtime.FeedMessage): List<TransitVehicle> {
        val result = ArrayList<TransitVehicle>(feed.entityCount)
        for (entity in feed.entityList) {
            if (!entity.hasVehicle() || !entity.vehicle.hasPosition()) continue
            val vp = entity.vehicle
            val routeId = if (vp.hasTrip()) vp.trip.routeId else ""
            val route = if (routeId.isEmpty()) null else gtfsStore.route(routeId)
            val vehicleId = if (vp.hasVehicle() && vp.vehicle.hasId()) vp.vehicle.id else entity.id
            val tripId = if (vp.hasTrip() && vp.trip.hasTripId()) vp.trip.tripId else null
            result.add(
                TransitVehicle(
                    id = "atac-$vehicleId",
                    coordinate = LatLon(vp.position.latitude.toDouble(), vp.position.longitude.toDouble()),
                    bearing = if (vp.position.hasBearing()) vp.position.bearing.toDouble() else null,
                    routeId = routeId.ifEmpty { null },
                    routeLabel = route?.routeShortName ?: routeId.ifEmpty { null },
                    kind = route?.kind ?: TransitVehicleKind.BUS,
                    transitOperator = route?.transitOperator ?: TransitOperator.ATAC,
                    scope = TransitVehicle.VisibilityScope.FULL_FLEET,
                    delaySeconds = null,
                    tripId = tripId,
                )
            )
        }
        return result
    }

    /** One pass builds both the per-stop and the per-trip indices. */
    private fun decodeTripUpdates(feed: GtfsRealtime.FeedMessage): Pair<Map<String, List<AtacStopPrediction>>, Map<String, List<AtacTripStopTime>>> {
        val byStop = HashMap<String, MutableList<AtacStopPrediction>>()
        val byTrip = HashMap<String, MutableList<AtacTripStopTime>>()
        val now = System.currentTimeMillis()

        for (entity in feed.entityList) {
            if (!entity.hasTripUpdate()) continue
            val tu = entity.tripUpdate
            val routeId = if (tu.hasTrip()) tu.trip.routeId else ""
            val route = if (routeId.isEmpty()) null else gtfsStore.route(routeId)
            val tripId = if (tu.hasTrip() && tu.trip.hasTripId()) tu.trip.tripId else entity.id
            val headsign = gtfsStore.headsignForTrip(tripId)

            for (stu in tu.stopTimeUpdateList) {
                if (!stu.hasStopId()) continue
                val event = when {
                    stu.hasArrival() -> stu.arrival
                    stu.hasDeparture() -> stu.departure
                    else -> null
                } ?: continue
                if (!event.hasTime() || event.time <= 0) continue

                val arrivalMillis = event.time * 1000L
                val delaySeconds = if (event.hasDelay()) event.delay else null

                // Per-stop "coming next" only makes sense for the future (2 min grace);
                // the per-trip schedule keeps every stop, past and future.
                if (arrivalMillis - now > -120_000L) {
                    byStop.getOrPut(stu.stopId) { ArrayList() }.add(
                        AtacStopPrediction(
                            tripId = tripId,
                            routeId = routeId,
                            routeLabel = route?.routeShortName ?: routeId,
                            kind = route?.kind ?: TransitVehicleKind.BUS,
                            arrivalMillis = arrivalMillis,
                            delaySeconds = delaySeconds,
                            headsign = headsign,
                        )
                    )
                }

                val list = byTrip.getOrPut(tripId) { ArrayList() }
                list.add(
                    AtacTripStopTime(
                        stopId = stu.stopId,
                        stopName = gtfsStore.stop(stu.stopId)?.stopName ?: stu.stopId,
                        sequence = if (stu.hasStopSequence()) stu.stopSequence else list.size,
                        arrivalMillis = arrivalMillis,
                        delaySeconds = delaySeconds,
                    )
                )
            }
        }
        byStop.values.forEach { it.sortBy { p -> p.arrivalMillis } }
        byTrip.values.forEach { it.sortBy { t -> t.sequence } }
        return byStop to byTrip
    }
}
