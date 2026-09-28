package dev.gab8bit.madovai.model

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

data class AtacStop(val stopId: String, val stopName: String, val lat: Double, val lon: Double) {
    val coordinate: LatLon get() = LatLon(lat, lon)
}

data class AtacRoute(
    val routeId: String,
    val routeShortName: String,
    val routeLongName: String,
    val kind: TransitVehicleKind,
    val transitOperator: TransitOperator,
    /** Hex string (e.g. "FF6600") without "#", or null if the feed left it blank. */
    val colorHex: String?,
) {
    /** "Metro A" for a metro line, otherwise long name with short-name fallback. */
    val friendlyName: String
        get() = AtacMetroLine.fromShortName(routeShortName)?.friendlyName
            ?: routeLongName.ifEmpty { routeShortName }

    fun displayName(headsign: String?): String =
        if (headsign.isNullOrEmpty()) friendlyName else "$friendlyName verso $headsign"
}

/**
 * Rome's 4 metro lines — the feed leaves `route_long_name` blank for all 4, so there's
 * no name to fall back on. routeId == routeShortName for these ("MEA", "MEB", …).
 */
enum class AtacMetroLine(val rawValue: String, val friendlyName: String) {
    A("MEA", "Metro A"),
    B("MEB", "Metro B"),
    B1("MEB1", "Metro B1"),
    C("MEC", "Metro C");

    companion object {
        fun fromShortName(shortName: String): AtacMetroLine? = entries.firstOrNull { it.rawValue == shortName.uppercase() }
    }
}

/**
 * One drawable polyline (one branch/direction of a route), with a precomputed bounding
 * box for fast viewport intersection. Also reused for Cotral's rail shapes.
 */
class LineShape(val shapeId: String, val routeId: String, val lats: DoubleArray, val lons: DoubleArray) {
    val minLat: Double = lats.minOrNull() ?: 0.0
    val maxLat: Double = lats.maxOrNull() ?: 0.0
    val minLon: Double = lons.minOrNull() ?: 0.0
    val maxLon: Double = lons.maxOrNull() ?: 0.0
    val size: Int get() = lats.size

    fun intersects(minLat: Double, maxLat: Double, minLon: Double, maxLon: Double): Boolean =
        this.minLat <= maxLat && this.maxLat >= minLat && this.minLon <= maxLon && this.maxLon >= minLon
}

/** One upcoming arrival at an Atac/Roma TPL stop (live from trip_updates, or a scheduled estimate). */
data class AtacStopPrediction(
    val tripId: String,
    val routeId: String,
    val routeLabel: String,
    val kind: TransitVehicleKind,
    val arrivalMillis: Long,
    /** Seconds of delay (positive = late). Always null for a scheduled-fallback estimate. */
    val delaySeconds: Int?,
    val isScheduled: Boolean = false,
    val headsign: String? = null,
) {
    val id: String get() = tripId

    fun minutesFromNow(nowMillis: Long = System.currentTimeMillis()): Int =
        ((arrivalMillis - nowMillis) / 60_000L).toInt()

    /** "HH:mm" in Rome's own timezone, not the device's. */
    val arrivalTimeLabel: String get() = RomeTime.hhmm(arrivalMillis)
}

/** One stop along a specific Atac trip's realtime-predicted itinerary. */
data class AtacTripStopTime(
    val stopId: String,
    val stopName: String,
    val sequence: Int,
    val arrivalMillis: Long,
    val delaySeconds: Int?,
) {
    val id: String get() = "$stopId-$sequence"
}

/** One physical station's platforms, grouped by name (never deduplicated — see [groupedStopsByName]). */
data class AtacStopGroup(val name: String, val stops: List<AtacStop>) {
    val id: String get() = name
    /** Deterministic representative platform (stops are pre-sorted by stopId). */
    val anchor: AtacStop get() = stops[0]
}

/**
 * Groups stops by name, KEEPING every stop_id. A real station is frequently split across
 * several GTFS stop_ids, one physical platform per direction — each with its own genuine
 * real-time predictions. An earlier version discarded "duplicates" and lost a whole
 * direction's arrivals; grouping keeps all of it and lets the UI offer a direction picker.
 */
fun groupedStopsByName(stops: List<AtacStop>): List<AtacStopGroup> {
    val order = ArrayList<String>()
    val groups = HashMap<String, MutableList<AtacStop>>()
    for (stop in stops) {
        val key = stop.stopName.trim()
        if (key.isEmpty()) continue
        val list = groups.getOrPut(key) { order.add(key); ArrayList() }
        list.add(stop)
    }
    return order.map { name -> AtacStopGroup(name, groups.getValue(name).sortedBy { it.stopId }) }
}

object RomeTime {
    val zone: TimeZone = TimeZone.getTimeZone("Europe/Rome")

    fun hhmm(millis: Long): String {
        val f = SimpleDateFormat("HH:mm", Locale.ROOT)
        f.timeZone = zone
        return f.format(Date(millis))
    }

    fun dateKey(millis: Long): String {
        val f = SimpleDateFormat("yyyyMMdd", Locale.ROOT)
        f.timeZone = zone
        return f.format(Date(millis))
    }

    /** Start of the current day in Rome, as epoch millis. */
    fun startOfDay(millis: Long): Long {
        val c = java.util.Calendar.getInstance(zone)
        c.timeInMillis = millis
        c.set(java.util.Calendar.HOUR_OF_DAY, 0)
        c.set(java.util.Calendar.MINUTE, 0)
        c.set(java.util.Calendar.SECOND, 0)
        c.set(java.util.Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }
}
