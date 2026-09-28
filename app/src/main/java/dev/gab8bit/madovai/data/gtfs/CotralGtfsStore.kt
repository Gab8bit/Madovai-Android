package dev.gab8bit.madovai.data.gtfs

import dev.gab8bit.madovai.Config
import dev.gab8bit.madovai.model.GtfsLoadingState
import dev.gab8bit.madovai.model.GtfsRoute
import dev.gab8bit.madovai.model.GtfsScheduledDeparture
import dev.gab8bit.madovai.model.GtfsStop
import dev.gab8bit.madovai.model.LineShape
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Calendar
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pure parsing for Cotral's GTFS feeds, mirroring the iOS `GTFSParsing` (itself a port of
 * the reference server's `gtfsService.ts`) field-for-field, including its fixed column
 * positions. Everything streams from disk — the bus `stop_times.txt` is ~175MB.
 */
object CotralGtfsParsing {
    class ParsedData(
        val stops: List<GtfsStop>,
        val stopById: Map<String, GtfsStop>,
        val routes: Map<String, GtfsRoute>,
        val stopToRouteIds: Map<String, Set<String>>,
        /** Rail feed only — Cotral bus shapes are never parsed (whole regional network). */
        val shapes: List<LineShape>,
    )

    class RailStopTime(val sequence: Int, val stopId: String, val departureSeconds: Int)

    /** calendar.txt pattern; `weekdays` is Monday-first (index 0 = Monday … 6 = Sunday). */
    class CalendarService(val weekdays: BooleanArray, val startDate: Int, val endDate: Int)

    class RailSchedule(
        val tripStops: Map<String, List<RailStopTime>>,
        val tripService: Map<String, String>,
        /** trip_id → "F:"-namespaced route_id. */
        val tripRoute: Map<String, String>,
        val calendarByService: Map<String, CalendarService>,
        /** serviceId → date(YYYYMMDD) → added(true)/removed(false). */
        val exceptionsByService: Map<String, Map<Int, Boolean>>,
    )

    /**
     * @param isRail tags stops/routes as rail and namespaces route ids ("F:") so merged
     *   bus + rail routes can never collide. Stop ids are intentionally NOT namespaced —
     *   they must stay the raw code PIV.do expects.
     */
    suspend fun parseAll(directory: File, isRail: Boolean): ParsedData {
        val prefix = if (isRail) "F:" else ""
        val (stops, stopById) = parseStops(File(directory, "stops.txt"), isRail)
        val routes = parseRoutes(File(directory, "routes.txt"), isRail, prefix)
        val tripToRoute = parseTrips(File(directory, "trips.txt"), prefix)
        val stopToRouteIds = parseStopTimes(File(directory, "stop_times.txt"), tripToRoute)
        val shapes = if (isRail) parseRailShapes(directory, prefix) else emptyList()
        return ParsedData(stops, stopById, routes, stopToRouteIds, shapes)
    }

    private fun parseStops(file: File, isRail: Boolean): Pair<List<GtfsStop>, Map<String, GtfsStop>> {
        val stops = ArrayList<GtfsStop>()
        val byId = HashMap<String, GtfsStop>()
        Csv.forEachDataLine(file) { line ->
            val trimmed = line.trim(' ', '\t')
            if (trimmed.isEmpty()) return@forEachDataLine
            val f = Csv.parseLine(trimmed)
            if (f.size <= 3) return@forEachDataLine
            val name = GtfsTextUtils.stripTrailingHashCode(f[1])
            val stop = GtfsStop(
                stopId = f[0],
                stopName = name,
                stopNameLower = name.lowercase(),
                stopLat = f[2].toDoubleOrNull() ?: 0.0,
                stopLon = f[3].toDoubleOrNull() ?: 0.0,
                isRail = isRail,
            )
            stops.add(stop)
            byId[stop.stopId] = stop
        }
        return stops to byId
    }

    private fun parseRoutes(file: File, isRail: Boolean, prefix: String): Map<String, GtfsRoute> {
        val routes = HashMap<String, GtfsRoute>()
        Csv.forEachDataLine(file) { line ->
            val trimmed = line.trim(' ', '\t')
            if (trimmed.isEmpty()) return@forEachDataLine
            val f = Csv.parseLine(trimmed)
            if (f.size <= 3) return@forEachDataLine
            val id = prefix + f[0]
            routes[id] = GtfsRoute(routeId = id, routeShortName = f[2], routeLongName = f[3], isRail = isRail)
        }
        return routes
    }

    /** trip_id → namespaced route_id (trips.txt: route_id, service_id, trip_id, …). */
    private fun parseTrips(file: File, prefix: String): Map<String, String> {
        val tripToRoute = HashMap<String, String>()
        val interned = HashMap<String, String>()
        Csv.forEachDataLine(file) { line ->
            val trimmed = line.trim(' ', '\t')
            if (trimmed.isEmpty()) return@forEachDataLine
            val f = Csv.parseLine(trimmed)
            if (f.size <= 2) return@forEachDataLine
            val routeId = prefix + f[0]
            tripToRoute[f[2]] = interned.getOrPut(routeId) { routeId }
        }
        return tripToRoute
    }

    /**
     * stop_id → route_ids, via a byte-level scan of columns 0 (trip_id) and 3 (stop_id)
     * only — the same fast path as the reference server/iOS, never the full CSV parser.
     */
    private suspend fun parseStopTimes(file: File, tripToRoute: Map<String, String>): Map<String, Set<String>> {
        val stopToRouteIds = HashMap<String, MutableSet<String>>()
        val scanner = ByteLineScanner(maxFields = 4)
        val lastTrip = ByteArray(256)
        var lastTripLen = -1
        var lastRoute: String? = null
        scanner.scan(file) { s ->
            if (s.fieldCount < 4) return@scan
            val routeId: String?
            if (s.length(0) <= lastTrip.size && s.fieldEquals(0, lastTrip, lastTripLen)) {
                routeId = lastRoute
            } else {
                val tripId = s.string(0).replace("\"", "")
                routeId = tripToRoute[tripId]
                if (s.length(0) <= lastTrip.size) {
                    lastTripLen = s.copyField(0, lastTrip)
                    lastRoute = routeId
                } else {
                    lastTripLen = -1
                }
            }
            if (routeId == null) return@scan
            val stopId = s.string(3).replace("\"", "").trim(' ', '\t')
            if (stopId.isEmpty()) return@scan
            stopToRouteIds.getOrPut(stopId) { HashSet(4) }.add(routeId)
        }
        return stopToRouteIds
    }

    /** Rail-only route↔shape association + shapes. Missing shapes.txt → no line drawn, not an error. */
    private fun parseRailShapes(directory: File, prefix: String): List<LineShape> {
        val tripsFile = File(directory, "trips.txt")
        val shapesFile = File(directory, "shapes.txt")
        if (!shapesFile.exists()) return emptyList()

        val routeToShapeIds = HashMap<String, MutableSet<String>>()
        Csv.forEachDataLine(tripsFile) { line ->
            val trimmed = line.trim(' ', '\t')
            if (trimmed.isEmpty()) return@forEachDataLine
            val f = Csv.parseLine(trimmed)
            if (f.size <= 4 || f[4].isEmpty()) return@forEachDataLine
            routeToShapeIds.getOrPut(prefix + f[0]) { HashSet() }.add(f[4])
        }
        val points = parseShapePoints(shapesFile)
        val shapes = ArrayList<LineShape>()
        for ((routeId, shapeIds) in routeToShapeIds) {
            for (shapeId in shapeIds) {
                val builder = points[shapeId] ?: continue
                if (builder.size > 1) shapes.add(builder.build(shapeId, routeId))
            }
        }
        return shapes
    }

    /** Rail-only full schedule (stop_times with times + calendar + calendar_dates). ~380KB. */
    fun parseRailSchedule(directory: File): RailSchedule? {
        val stopTimesFile = File(directory, "stop_times.txt")
        val tripsFile = File(directory, "trips.txt")
        if (!stopTimesFile.exists() || !tripsFile.exists()) return null

        val tripService = HashMap<String, String>()
        val tripRoute = HashMap<String, String>()
        Csv.forEachDataLine(tripsFile) { line ->
            val trimmed = line.trim(' ', '\t')
            if (trimmed.isEmpty()) return@forEachDataLine
            val f = Csv.parseLine(trimmed)
            if (f.size <= 2) return@forEachDataLine
            tripService[f[2]] = f[1]
            tripRoute[f[2]] = "F:" + f[0]
        }

        val tripStops = HashMap<String, MutableList<RailStopTime>>()
        Csv.forEachDataLine(stopTimesFile) { line ->
            val trimmed = line.trim(' ', '\t')
            if (trimmed.isEmpty()) return@forEachDataLine
            val f = Csv.parseLine(trimmed)
            if (f.size <= 4) return@forEachDataLine
            val departure = parseGtfsTime(f[2]) ?: return@forEachDataLine
            val sequence = f[4].toIntOrNull() ?: return@forEachDataLine
            tripStops.getOrPut(f[0]) { ArrayList() }.add(RailStopTime(sequence, f[3], departure))
        }
        tripStops.values.forEach { list -> list.sortBy { it.sequence } }

        val calendarByService = HashMap<String, CalendarService>()
        val calendarFile = File(directory, "calendar.txt")
        if (calendarFile.exists()) {
            Csv.forEachDataLine(calendarFile) { line ->
                val trimmed = line.trim(' ', '\t')
                if (trimmed.isEmpty()) return@forEachDataLine
                val f = Csv.parseLine(trimmed)
                if (f.size <= 9) return@forEachDataLine
                val weekdays = BooleanArray(7) { f[it + 1] == "1" }
                val start = f[8].toIntOrNull() ?: return@forEachDataLine
                val end = f[9].toIntOrNull() ?: return@forEachDataLine
                calendarByService[f[0]] = CalendarService(weekdays, start, end)
            }
        }

        val exceptions = HashMap<String, MutableMap<Int, Boolean>>()
        val exceptionsFile = File(directory, "calendar_dates.txt")
        if (exceptionsFile.exists()) {
            Csv.forEachDataLine(exceptionsFile) { line ->
                val trimmed = line.trim(' ', '\t')
                if (trimmed.isEmpty()) return@forEachDataLine
                val f = Csv.parseLine(trimmed)
                if (f.size <= 2) return@forEachDataLine
                val date = f[1].toIntOrNull() ?: return@forEachDataLine
                exceptions.getOrPut(f[0]) { HashMap() }[date] = f[2] == "1"
            }
        }
        return RailSchedule(tripStops, tripService, tripRoute, calendarByService, exceptions)
    }

    private fun parseGtfsTime(time: String): Int? {
        val p = time.split(":")
        if (p.size != 3) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        val s = p[2].toIntOrNull() ?: return null
        return h * 3600 + m * 60 + s
    }
}

/** Growable per-shape point buffer, sorted by shape_pt_sequence on build. */
class ShapeBuilder {
    private var seq = IntArray(16)
    private var lat = DoubleArray(16)
    private var lon = DoubleArray(16)
    var size = 0
        private set

    fun add(sequence: Int, latitude: Double, longitude: Double) {
        if (size == seq.size) {
            val n = size * 2
            seq = seq.copyOf(n); lat = lat.copyOf(n); lon = lon.copyOf(n)
        }
        seq[size] = sequence; lat[size] = latitude; lon[size] = longitude
        size++
    }

    fun build(shapeId: String, routeId: String): LineShape {
        val order = (0 until size).sortedBy { seq[it] }
        return LineShape(
            shapeId = shapeId,
            routeId = routeId,
            lats = DoubleArray(size) { lat[order[it]] },
            lons = DoubleArray(size) { lon[order[it]] },
        )
    }
}

/** shape_id → points. Fast manual split (every field numeric), like the iOS parser. */
fun parseShapePoints(file: File): Map<String, ShapeBuilder> {
    val result = HashMap<String, ShapeBuilder>()
    Csv.forEachDataLine(file) { line ->
        if (line.isEmpty()) return@forEachDataLine
        val parts = line.split(',')
        if (parts.size < 4) return@forEachDataLine
        val lat = parts[1].toDoubleOrNull() ?: return@forEachDataLine
        val lon = parts[2].toDoubleOrNull() ?: return@forEachDataLine
        val sequence = parts[3].toIntOrNull() ?: return@forEachDataLine
        result.getOrPut(parts[0]) { ShapeBuilder() }.add(sequence, lat, lon)
    }
    return result
}

/**
 * Owns Cotral's static GTFS feeds — bus (GTFS_COTRAL) and rail (GTFS_FERRO) — on device.
 * Downloads + caches them once (a cached copy is ALWAYS reused, never re-downloaded), but
 * re-processes them into in-memory models on every app start.
 */
class CotralGtfsStore(baseDir: File) {
    private val _state = MutableStateFlow<GtfsLoadingState>(GtfsLoadingState.Idle)
    val state: StateFlow<GtfsLoadingState> = _state.asStateFlow()
    private val _downloadProgress = MutableStateFlow<Double?>(null)
    val downloadProgress: StateFlow<Double?> = _downloadProgress.asStateFlow()

    private class Data(
        val stops: List<GtfsStop> = emptyList(),
        val stopById: Map<String, GtfsStop> = emptyMap(),
        val routes: Map<String, GtfsRoute> = emptyMap(),
        val stopToRouteIds: Map<String, Set<String>> = emptyMap(),
        val routeToStopIds: Map<String, Set<String>> = emptyMap(),
        val railSchedule: CotralGtfsParsing.RailSchedule? = null,
        val shapesByRouteId: Map<String, List<LineShape>> = emptyMap(),
    )

    @Volatile private var data = Data()
    private val loadMutex = Mutex()

    private val cacheDir = File(baseDir, "GTFS")
    private val requiredFiles = listOf("stops.txt", "routes.txt", "trips.txt", "stop_times.txt")
    /** Grabbed opportunistically; calendar and shapes files are rail-only features. */
    private val optionalFiles = listOf("calendar.txt", "calendar_dates.txt", "shapes.txt")

    val isReady: Boolean get() = _state.value == GtfsLoadingState.Ready

    suspend fun ensureLoaded() {
        if (_state.value == GtfsLoadingState.Ready) return
        load()
    }

    suspend fun retry() {
        _state.value = GtfsLoadingState.Idle
        load()
    }

    private suspend fun load(): Unit = loadMutex.withLock {
        if (_state.value == GtfsLoadingState.Ready) return@withLock
        try {
            withContext(Dispatchers.IO) {
                val busDir = File(cacheDir, "bus").apply { mkdirs() }
                val railDir = File(cacheDir, "rail").apply { mkdirs() }

                if (!requiredFilesExist(busDir)) {
                    downloadAndExtract(Config.GTFS_BUS_ZIP_URL, busDir)
                }

                // Rail is a nice-to-have: degrade to bus-only if missing/malformed. Also
                // self-heal a rail cache missing calendar.txt/shapes.txt (not gating on the
                // genuinely optional calendar_dates.txt, to avoid a redownload loop).
                var railAvailable = requiredFilesExist(railDir) &&
                    File(railDir, "calendar.txt").exists() && File(railDir, "shapes.txt").exists()
                if (!railAvailable) {
                    railAvailable = try {
                        downloadAndExtract(Config.GTFS_RAIL_ZIP_URL, railDir)
                        requiredFilesExist(railDir)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        false
                    }
                }

                _downloadProgress.value = null
                _state.value = GtfsLoadingState.Parsing
                coroutineScope {
                    val bus = async(Dispatchers.Default) { CotralGtfsParsing.parseAll(busDir, isRail = false) }
                    val rail = async(Dispatchers.Default) {
                        if (railAvailable) runCatching { CotralGtfsParsing.parseAll(railDir, isRail = true) }.getOrNull() else null
                    }
                    val schedule = async(Dispatchers.Default) {
                        if (railAvailable) runCatching { CotralGtfsParsing.parseRailSchedule(railDir) }.getOrNull() else null
                    }
                    data = merge(bus.await(), rail.await(), schedule.await())
                }
            }
            _state.value = GtfsLoadingState.Ready
        } catch (e: CancellationException) {
            _downloadProgress.value = null
            if (_state.value != GtfsLoadingState.Ready) _state.value = GtfsLoadingState.Idle
            throw e
        } catch (e: Throwable) {
            _downloadProgress.value = null
            e.printStackTrace()
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            _state.value = GtfsLoadingState.Failed(if (e is GtfsException) detail else "Errore nel caricamento dei dati GTFS. ($detail)")
        }
    }

    private suspend fun downloadAndExtract(url: String, dir: File) {
        _state.value = GtfsLoadingState.Downloading
        val zip = File(dir, "download.zip")
        GtfsDownloader.download(url, zip, "Download dei dati GTFS fallito") { _downloadProgress.value = it }
        _downloadProgress.value = null
        _state.value = GtfsLoadingState.Extracting
        GtfsDownloader.extract(zip, dir, (requiredFiles + optionalFiles).toSet(), "Archivio GTFS non valido o corrotto.")
        if (!requiredFilesExist(dir)) throw GtfsException("File GTFS mancanti nell'archivio scaricato.")
    }

    private fun requiredFilesExist(dir: File) = requiredFiles.all { File(dir, it).exists() }

    private fun merge(
        bus: CotralGtfsParsing.ParsedData,
        rail: CotralGtfsParsing.ParsedData?,
        schedule: CotralGtfsParsing.RailSchedule?,
    ): Data {
        val stops = ArrayList(bus.stops)
        val stopById = HashMap(bus.stopById)
        val routes = HashMap(bus.routes)
        val stopToRouteIds = HashMap<String, Set<String>>(bus.stopToRouteIds)
        if (rail != null) {
            stops.addAll(rail.stops)
            stopById.putAll(rail.stopById)
            routes.putAll(rail.routes)
            for ((stopId, ids) in rail.stopToRouteIds) {
                stopToRouteIds[stopId] = (stopToRouteIds[stopId] ?: emptySet()) + ids
            }
        }
        val inverted = HashMap<String, MutableSet<String>>()
        for ((stopId, ids) in stopToRouteIds) for (routeId in ids) inverted.getOrPut(routeId) { HashSet() }.add(stopId)
        return Data(
            stops = stops,
            stopById = stopById,
            routes = routes,
            stopToRouteIds = stopToRouteIds,
            routeToStopIds = inverted,
            railSchedule = schedule,
            shapesByRouteId = (rail?.shapes ?: emptyList()).groupBy { it.routeId },
        )
    }

    // region Queries (mirroring gtfsService.ts, extended with stop/line search)

    fun findStopsByPosition(latitude: Double, longitude: Double, range: Double, limit: Int = 40): List<GtfsStop> {
        val results = ArrayList<GtfsStop>()
        for (stop in data.stops) {
            if (stop.stopLat >= latitude - range && stop.stopLat <= latitude + range &&
                stop.stopLon >= longitude - range && stop.stopLon <= longitude + range
            ) {
                results.add(stop)
                if (results.size >= limit) break
            }
        }
        return results
    }

    fun findStopsInRegion(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, limit: Int = 60): List<GtfsStop> {
        val results = ArrayList<GtfsStop>()
        for (stop in data.stops) {
            if (stop.stopLat in minLat..maxLat && stop.stopLon in minLon..maxLon) {
                results.add(stop)
                if (results.size >= limit) break
            }
        }
        return results
    }

    fun findStopById(stopId: String): GtfsStop? = data.stopById[stopId]

    fun searchStops(query: String, limit: Int = 15): List<GtfsStop> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val results = ArrayList<GtfsStop>()
        for (stop in data.stops) {
            if (stop.stopNameLower.contains(q)) {
                results.add(stop)
                if (results.size >= limit) break
            }
        }
        return results
    }

    fun searchRoutes(query: String, limit: Int = 10): List<GtfsRoute> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return data.routes.values
            .filter { it.routeShortName.lowercase().contains(q) || it.routeLongName.lowercase().contains(q) }
            .sortedBy { it.routeShortName }
            .take(limit)
    }

    fun stopsForRoute(routeId: String): List<GtfsStop> {
        val d = data
        val ids = d.routeToStopIds[routeId] ?: return emptyList()
        return ids.mapNotNull { d.stopById[it] }
    }

    /**
     * Stops in physical line order for a rail route — reuses the longest trip of that
     * route (ties broken by trip id for determinism). Falls back to [stopsForRoute]
     * (sorted by name here, for a stable list) when no sequence data exists (bus routes).
     */
    fun stopsForRouteInOrder(routeId: String): List<GtfsStop> {
        val d = data
        val schedule = d.railSchedule ?: return stopsForRoute(routeId).sortedBy { it.stopName }
        val longest = schedule.tripStops.entries
            .filter { schedule.tripRoute[it.key] == routeId }
            .sortedBy { it.key }
            .maxByOrNull { it.value.size }
            ?: return stopsForRoute(routeId).sortedBy { it.stopName }
        return longest.value.mapNotNull { d.stopById[it.stopId] }
    }

    /** Rail lines only — the "Linee" browser's Cotral bucket (the ~4000 bus routes are left out on purpose). */
    fun allRailRoutes(): List<GtfsRoute> =
        data.routes.values.filter { it.routeId.startsWith("F:") }.sortedBy { it.routeShortName }

    fun getRoutesForStop(stopId: String): List<GtfsRoute> {
        val d = data
        val ids = d.stopToRouteIds[stopId] ?: return emptyList()
        return ids.mapNotNull { d.routes[it] }
    }

    fun route(routeId: String): GtfsRoute? = data.routes[routeId]

    fun shapes(routeId: String): List<LineShape> = data.shapesByRouteId[routeId] ?: emptyList()

    fun getDestinationsFromRoutes(stopRoutes: List<GtfsRoute>): List<String> {
        val set = HashSet<String>()
        for (route in stopRoutes) {
            val name = GtfsTextUtils.stripTrailingHashCode(route.routeLongName)
            val parts = name.split(" - ")
            if (parts.size >= 2) {
                val last = parts.last().trim()
                if (last.isNotEmpty()) set.add(last)
            }
        }
        return set.sorted()
    }

    /**
     * Today's scheduled (static) departures from a Cotral rail stop. Null only when no rail
     * schedule loaded at all; empty when nothing departs from this stop today.
     */
    fun scheduledDepartures(railStopId: String, now: Calendar = Calendar.getInstance()): List<GtfsScheduledDeparture>? {
        val d = data
        val schedule = d.railSchedule ?: return null
        val today = now.get(Calendar.YEAR) * 10_000 + (now.get(Calendar.MONTH) + 1) * 100 + now.get(Calendar.DAY_OF_MONTH)
        // Calendar.DAY_OF_WEEK: 1 = Sunday … 7 = Saturday → Monday-first index.
        val mondayFirst = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7

        fun isActive(serviceId: String): Boolean {
            schedule.exceptionsByService[serviceId]?.get(today)?.let { return it }
            val service = schedule.calendarByService[serviceId] ?: return false
            if (today < service.startDate || today > service.endDate) return false
            return service.weekdays[mondayFirst]
        }

        val results = ArrayList<GtfsScheduledDeparture>()
        for ((tripId, stopTimes) in schedule.tripStops) {
            val serviceId = schedule.tripService[tripId] ?: continue
            if (!isActive(serviceId)) continue
            val departure = stopTimes.firstOrNull { it.stopId == railStopId } ?: continue
            val destinationId = stopTimes.lastOrNull()?.stopId ?: continue
            val destination = d.stopById[destinationId] ?: continue
            results.add(GtfsScheduledDeparture(tripId, destination.stopName, departure.departureSeconds))
        }
        return results.sortedBy { it.departureSeconds }
    }

    // endregion
}
