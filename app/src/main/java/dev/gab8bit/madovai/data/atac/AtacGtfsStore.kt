package dev.gab8bit.madovai.data.atac

import dev.gab8bit.madovai.Config
import dev.gab8bit.madovai.data.gtfs.ByteLineScanner
import dev.gab8bit.madovai.data.gtfs.Csv
import dev.gab8bit.madovai.data.gtfs.GtfsDownloader
import dev.gab8bit.madovai.data.gtfs.GtfsException
import dev.gab8bit.madovai.data.gtfs.GtfsTextUtils
import dev.gab8bit.madovai.data.gtfs.parseShapePoints
import dev.gab8bit.madovai.model.AtacRoute
import dev.gab8bit.madovai.model.AtacStop
import dev.gab8bit.madovai.model.AtacStopPrediction
import dev.gab8bit.madovai.model.GtfsLoadingState
import dev.gab8bit.madovai.model.LineShape
import dev.gab8bit.madovai.model.RomeTime
import dev.gab8bit.madovai.model.TransitOperator
import dev.gab8bit.madovai.model.TransitVehicleKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pure parsing for Rome's urban GTFS (Atac + Roma TPL combined), mirroring the iOS
 * `AtacGtfsParsing` including its fixed column positions.
 *
 * `stop_times.txt` (~240MB / ~5.1M rows) is parsed ONLY for exact route↔stop membership
 * (+ per route/stop headsigns), streamed at byte level — never loaded as one String.
 * Schedule times come from GTFS-Realtime; a per-stop static fallback re-scans the file
 * on demand ([scheduledDepartures]) instead of keeping 5.1M rows in memory.
 */
object AtacGtfsParsing {
    class ParsedStatic(
        val stops: List<AtacStop>,
        val routes: Map<String, AtacRoute>,
        val shapes: List<LineShape>,
        val stopToRouteIds: Map<String, Set<String>>,
        val tripToRoute: Map<String, String>,
        val tripToService: Map<String, String>,
        val tripToHeadsign: Map<String, String>,
        /** routeId → stopId → headsign of that route's trips at that platform. */
        val stopHeadsignByRoute: Map<String, Map<String, String>>,
        /** stopId → first headsign seen for that platform (any route). */
        val anyHeadsignByStop: Map<String, String>,
        /** "yyyyMMdd" → service_ids running that day (this feed has calendar_dates.txt only). */
        val activeServiceIdsByDate: Map<String, Set<String>>,
    )

    suspend fun parseAll(directory: File): ParsedStatic {
        val stops = parseStops(File(directory, "stops.txt"))
        val routes = parseRoutes(File(directory, "routes.txt"))
        val trips = parseTrips(File(directory, "trips.txt"))
        val shapePoints = parseShapePoints(File(directory, "shapes.txt"))
        val membership = parseStopTimesForRouteMembership(File(directory, "stop_times.txt"), trips.tripToRoute, trips.tripToHeadsign)
        val activeByDate = parseCalendarDates(File(directory, "calendar_dates.txt"))

        val shapes = ArrayList<LineShape>(shapePoints.size)
        for ((routeId, shapeIds) in trips.routeToShapeIds) {
            for (shapeId in shapeIds) {
                val builder = shapePoints[shapeId] ?: continue
                if (builder.size > 1) shapes.add(builder.build(shapeId, routeId))
            }
        }
        return ParsedStatic(
            stops = stops,
            routes = routes,
            shapes = shapes,
            stopToRouteIds = membership.stopToRouteIds,
            tripToRoute = trips.tripToRoute,
            tripToService = trips.tripToService,
            tripToHeadsign = trips.tripToHeadsign,
            stopHeadsignByRoute = membership.stopHeadsignByRoute,
            anyHeadsignByStop = membership.anyHeadsignByStop,
            activeServiceIdsByDate = activeByDate,
        )
    }

    /** "ANAGNINA (MA)" → "Anagnina": the "(MA)" suffix is the feed's internal line code. */
    private fun cleanHeadsign(raw: String): String? {
        val cleaned = GtfsTextUtils.capitalized(GtfsTextUtils.extractLocalityFromStopName(raw))
        return cleaned.ifEmpty { null }
    }

    private fun parseStops(file: File): List<AtacStop> {
        val stops = ArrayList<AtacStop>()
        Csv.forEachDataLine(file) { line ->
            val trimmed = line.trim(' ', '\t')
            if (trimmed.isEmpty()) return@forEachDataLine
            val f = Csv.parseLine(trimmed)
            if (f.size <= 5) return@forEachDataLine
            val lat = f[4].toDoubleOrNull() ?: return@forEachDataLine
            val lon = f[5].toDoubleOrNull() ?: return@forEachDataLine
            stops.add(AtacStop(stopId = f[0], stopName = f[2], lat = lat, lon = lon))
        }
        return stops
    }

    private fun parseRoutes(file: File): Map<String, AtacRoute> {
        val routes = HashMap<String, AtacRoute>()
        Csv.forEachDataLine(file) { line ->
            val trimmed = line.trim(' ', '\t')
            if (trimmed.isEmpty()) return@forEachDataLine
            val f = Csv.parseLine(trimmed)
            if (f.size <= 6) return@forEachDataLine
            val routeType = f[4].toIntOrNull() ?: 3
            val color = f[6].trim()
            // Only agency_id "OP1" is Atac itself; every other agency in this combined feed
            // (TROIANI, TUSCIA, BIS, ATR Mobility) belongs to the Roma TPL consortium.
            val operator = if (f[1] == "OP1") TransitOperator.ATAC else TransitOperator.ROMA_TPL
            routes[f[0]] = AtacRoute(
                routeId = f[0],
                routeShortName = f[2],
                routeLongName = f[3],
                kind = TransitVehicleKind.fromGtfsRouteType(routeType),
                transitOperator = operator,
                colorHex = color.ifEmpty { null },
            )
        }
        return routes
    }

    private class Trips(
        val routeToShapeIds: Map<String, Set<String>>,
        val tripToRoute: Map<String, String>,
        val tripToService: Map<String, String>,
        val tripToHeadsign: Map<String, String>,
    )

    /** Quote-aware parser here: trip_headsign can contain quoted commas. shape_id = column 7. */
    private fun parseTrips(file: File): Trips {
        val routeToShapeIds = HashMap<String, MutableSet<String>>()
        val tripToRoute = HashMap<String, String>()
        val tripToService = HashMap<String, String>()
        val tripToHeadsign = HashMap<String, String>()
        val intern = HashMap<String, String>()
        fun i(s: String) = intern.getOrPut(s) { s }
        Csv.forEachDataLine(file) { line ->
            val trimmed = line.trim(' ', '\t')
            if (trimmed.isEmpty()) return@forEachDataLine
            val f = Csv.parseLine(trimmed)
            if (f.size <= 7) return@forEachDataLine
            val routeId = f[0]
            val serviceId = f[1]
            val tripId = f[2]
            val shapeId = f[7]
            if (routeId.isEmpty()) return@forEachDataLine
            if (tripId.isNotEmpty()) {
                tripToRoute[tripId] = i(routeId)
                if (serviceId.isNotEmpty()) tripToService[tripId] = i(serviceId)
                cleanHeadsign(f[3])?.let { tripToHeadsign[tripId] = i(it) }
            }
            if (shapeId.isEmpty()) return@forEachDataLine
            routeToShapeIds.getOrPut(i(routeId)) { HashSet() }.add(shapeId)
        }
        return Trips(routeToShapeIds, tripToRoute, tripToService, tripToHeadsign)
    }

    private class Membership(
        val stopToRouteIds: Map<String, Set<String>>,
        val stopHeadsignByRoute: Map<String, Map<String, String>>,
        val anyHeadsignByStop: Map<String, String>,
    )

    /**
     * stop_id → route_ids (+ each (route, stop) pair's headsign), from a byte-level scan of
     * columns 0 (trip_id) and 3 (stop_id). Consecutive rows of the same trip reuse the
     * previous lookup, so only the stop id String is allocated per row.
     */
    private suspend fun parseStopTimesForRouteMembership(
        file: File,
        tripToRoute: Map<String, String>,
        tripToHeadsign: Map<String, String>,
    ): Membership {
        val stopToRouteIds = HashMap<String, MutableSet<String>>(16_384)
        val headsignByRoute = HashMap<String, HashMap<String, String>>()
        val anyHeadsign = HashMap<String, String>(16_384)
        val stopIntern = HashMap<String, String>(16_384)

        val scanner = ByteLineScanner(maxFields = 4)
        val lastTrip = ByteArray(256)
        var lastTripLen = -1
        var lastRoute: String? = null
        var lastHeadsign: String? = null
        var lastRouteHeadsigns: HashMap<String, String>? = null

        scanner.scan(file) { s ->
            if (s.fieldCount < 4) return@scan
            if (!(s.length(0) <= lastTrip.size && s.fieldEquals(0, lastTrip, lastTripLen))) {
                val tripId = s.string(0)
                lastRoute = tripToRoute[tripId]
                lastHeadsign = tripToHeadsign[tripId]
                lastRouteHeadsigns = lastRoute?.let { headsignByRoute.getOrPut(it) { HashMap() } }
                lastTripLen = if (s.length(0) <= lastTrip.size) s.copyField(0, lastTrip) else -1
            }
            val routeId = lastRoute ?: return@scan
            val rawStop = s.string(3)
            val stopId = stopIntern.getOrPut(rawStop) { rawStop }
            stopToRouteIds.getOrPut(stopId) { HashSet(4) }.add(routeId)
            val headsign = lastHeadsign
            if (headsign != null) {
                lastRouteHeadsigns?.putIfAbsent(stopId, headsign)
                anyHeadsign.putIfAbsent(stopId, headsign)
            }
        }
        return Membership(stopToRouteIds, headsignByRoute, anyHeadsign)
    }

    private fun parseCalendarDates(file: File): Map<String, Set<String>> {
        val byDate = HashMap<String, MutableSet<String>>()
        Csv.forEachDataLine(file) { line ->
            if (line.isEmpty()) return@forEachDataLine
            val f = line.split(',')
            if (f.size < 3 || f[2] != "1") return@forEachDataLine
            byDate.getOrPut(f[1]) { HashSet() }.add(f[0])
        }
        return byDate
    }

    class ScheduledDeparture(val tripId: String, val routeId: String, val departureSeconds: Int)

    /** On-demand single-stop rescan of the cached stop_times.txt (departure_time = column 2). */
    suspend fun scheduledDepartures(
        stopId: String,
        file: File,
        tripToRoute: Map<String, String>,
        activeServiceIds: Set<String>,
        tripToService: Map<String, String>,
    ): List<ScheduledDeparture> {
        val target = stopId.toByteArray(Charsets.UTF_8)
        val results = ArrayList<ScheduledDeparture>()
        val scanner = ByteLineScanner(maxFields = 4)
        scanner.scan(file) { s ->
            if (s.fieldCount < 4 || !s.fieldEquals(3, target)) return@scan
            val tripId = s.string(0)
            val routeId = tripToRoute[tripId] ?: return@scan
            val serviceId = tripToService[tripId] ?: return@scan
            if (serviceId !in activeServiceIds) return@scan
            val parts = s.string(2).split(':')
            if (parts.size != 3) return@scan
            val h = parts[0].toIntOrNull() ?: return@scan
            val m = parts[1].toIntOrNull() ?: return@scan
            val sec = parts[2].toIntOrNull() ?: return@scan
            results.add(ScheduledDeparture(tripId, routeId, h * 3600 + m * 60 + sec))
        }
        return results
    }
}

/**
 * Owns Rome's urban static GTFS. Loads independently of Cotral's store — if Rome's feed is
 * unreachable, the app still works for Cotral (separate failure domains).
 */
class AtacGtfsStore(baseDir: File) {
    private val _state = MutableStateFlow<GtfsLoadingState>(GtfsLoadingState.Idle)
    val state: StateFlow<GtfsLoadingState> = _state.asStateFlow()
    private val _downloadProgress = MutableStateFlow<Double?>(null)
    val downloadProgress: StateFlow<Double?> = _downloadProgress.asStateFlow()

    private class Data(
        val stops: List<AtacStop> = emptyList(),
        val stopById: Map<String, AtacStop> = emptyMap(),
        val stopsByName: Map<String, List<AtacStop>> = emptyMap(),
        val routes: Map<String, AtacRoute> = emptyMap(),
        val shapes: List<LineShape> = emptyList(),
        val shapesByRouteId: Map<String, List<LineShape>> = emptyMap(),
        val routeToStopIds: Map<String, Set<String>> = emptyMap(),
        val tripToRoute: Map<String, String> = emptyMap(),
        val tripToService: Map<String, String> = emptyMap(),
        val tripToHeadsign: Map<String, String> = emptyMap(),
        val stopHeadsignByRoute: Map<String, Map<String, String>> = emptyMap(),
        val anyHeadsignByStop: Map<String, String> = emptyMap(),
        val activeServiceIdsByDate: Map<String, Set<String>> = emptyMap(),
        val sortedRoutes: List<AtacRoute> = emptyList(),
    )

    @Volatile private var data = Data()
    private val loadMutex = Mutex()
    private val cacheDir = File(baseDir, "AtacGTFS")
    private val requiredFiles = listOf("stops.txt", "routes.txt", "trips.txt", "shapes.txt", "stop_times.txt", "calendar_dates.txt")

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
                cacheDir.mkdirs()
                if (!requiredFilesExist()) {
                    _state.value = GtfsLoadingState.Downloading
                    val zip = File(cacheDir, "download.zip")
                    GtfsDownloader.download(Config.ATAC_GTFS_ZIP_URL, zip, "Download dei dati Atac/Roma TPL fallito") {
                        _downloadProgress.value = it
                    }
                    _downloadProgress.value = null
                    _state.value = GtfsLoadingState.Extracting
                    GtfsDownloader.extract(zip, cacheDir, requiredFiles.toSet(), "Archivio GTFS Atac/Roma TPL non valido o corrotto.")
                    if (!requiredFilesExist()) throw GtfsException("File GTFS mancanti nell'archivio Atac/Roma TPL scaricato.")
                }
                _state.value = GtfsLoadingState.Parsing
                val parsed = withContext(Dispatchers.Default) { AtacGtfsParsing.parseAll(cacheDir) }

                val stopById = HashMap<String, AtacStop>(parsed.stops.size * 2)
                parsed.stops.forEach { stopById[it.stopId] = it }
                val inverted = HashMap<String, MutableSet<String>>()
                for ((stopId, ids) in parsed.stopToRouteIds) for (r in ids) inverted.getOrPut(r) { HashSet() }.add(stopId)
                data = Data(
                    stops = parsed.stops,
                    stopById = stopById,
                    stopsByName = parsed.stops.groupBy { it.stopName.trim() },
                    routes = parsed.routes,
                    shapes = parsed.shapes,
                    shapesByRouteId = parsed.shapes.groupBy { it.routeId },
                    routeToStopIds = inverted,
                    tripToRoute = parsed.tripToRoute,
                    tripToService = parsed.tripToService,
                    tripToHeadsign = parsed.tripToHeadsign,
                    stopHeadsignByRoute = parsed.stopHeadsignByRoute,
                    anyHeadsignByStop = parsed.anyHeadsignByStop,
                    activeServiceIdsByDate = parsed.activeServiceIdsByDate,
                    sortedRoutes = parsed.routes.values.sortedBy { it.routeShortName },
                )
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
            _state.value = GtfsLoadingState.Failed(if (e is GtfsException) detail else "Errore nel caricamento dei dati Atac/Roma TPL. ($detail)")
        }
    }

    private fun requiredFilesExist() = requiredFiles.all { File(cacheDir, it).exists() }

    // region Viewport-scoped queries

    fun stopsInRegion(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, limit: Int = 300): List<AtacStop> {
        val results = ArrayList<AtacStop>()
        for (stop in data.stops) {
            if (stop.lat in minLat..maxLat && stop.lon in minLon..maxLon) {
                results.add(stop)
                if (results.size >= limit) break
            }
        }
        return results
    }

    fun shapesInRegion(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double, limit: Int = 150): List<LineShape> {
        val results = ArrayList<LineShape>()
        for (shape in data.shapes) {
            if (shape.intersects(minLat, maxLat, minLon, maxLon)) {
                results.add(shape)
                if (results.size >= limit) break
            }
        }
        return results
    }

    fun route(routeId: String): AtacRoute? = data.routes[routeId]

    fun stop(stopId: String): AtacStop? = data.stopById[stopId]

    /** All shapes of a line regardless of viewport (focused-line view). */
    fun shapes(routeId: String): List<LineShape> = data.shapesByRouteId[routeId] ?: emptyList()

    /** Exact stops served by a line (real stop_times membership), sorted by name. */
    fun stopsForRoute(routeId: String): List<AtacStop> {
        val d = data
        val ids = d.routeToStopIds[routeId] ?: return emptyList()
        return ids.mapNotNull { d.stopById[it] }.sortedBy { it.stopName }
    }

    fun headsignForTrip(tripId: String): String? = data.tripToHeadsign[tripId]

    fun headsign(routeId: String, stopId: String): String? = data.stopHeadsignByRoute[routeId]?.get(stopId)

    /** Any known headsign for this platform, regardless of route (bare map-pin tap / search). */
    fun anyHeadsign(stopId: String): String? = data.anyHeadsignByStop[stopId]

    /** Every platform sharing this stop's name, across the whole feed (not viewport-scoped). */
    fun stopsSharingName(stop: AtacStop): List<AtacStop> = data.stopsByName[stop.stopName.trim()] ?: listOf(stop)

    /**
     * Best-effort static-timetable fallback for a stop with no live prediction at all.
     * Doesn't handle a night run still under yesterday's service_id (acceptable gap for a
     * last-resort estimate, same as iOS).
     */
    suspend fun scheduledDepartures(stopId: String, limit: Int = 6): List<AtacStopPrediction> {
        if (!isReady) return emptyList()
        val d = data
        val now = System.currentTimeMillis()
        val active = d.activeServiceIdsByDate[RomeTime.dateKey(now)] ?: emptySet()
        if (active.isEmpty()) return emptyList()
        val midnight = RomeTime.startOfDay(now)
        val nowSeconds = ((now - midnight) / 1000).toInt()
        val raw = withContext(Dispatchers.IO) {
            AtacGtfsParsing.scheduledDepartures(stopId, File(cacheDir, "stop_times.txt"), d.tripToRoute, active, d.tripToService)
        }
        return raw.asSequence()
            .filter { it.departureSeconds >= nowSeconds }
            .sortedBy { it.departureSeconds }
            .take(limit)
            .map { dep ->
                val route = d.routes[dep.routeId]
                AtacStopPrediction(
                    tripId = dep.tripId,
                    routeId = dep.routeId,
                    routeLabel = route?.routeShortName ?: dep.routeId,
                    kind = route?.kind ?: TransitVehicleKind.BUS,
                    arrivalMillis = midnight + dep.departureSeconds * 1000L,
                    delaySeconds = null,
                    isScheduled = true,
                    headsign = headsign(dep.routeId, stopId),
                )
            }
            .toList()
    }

    // endregion

    // region Search

    fun searchStops(query: String, limit: Int = 15): List<AtacStop> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        val results = ArrayList<AtacStop>()
        for (stop in data.stops) {
            if (stop.stopName.lowercase().contains(q)) {
                results.add(stop)
                if (results.size >= limit) break
            }
        }
        return results
    }

    fun searchRoutes(query: String, limit: Int = 10): List<AtacRoute> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return data.sortedRoutes
            .filter { it.routeShortName.lowercase().contains(q) || it.routeLongName.lowercase().contains(q) }
            .take(limit)
    }

    fun allRoutes(): List<AtacRoute> = data.sortedRoutes

    // endregion
}
