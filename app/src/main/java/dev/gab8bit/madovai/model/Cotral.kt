package dev.gab8bit.madovai.model

import java.util.Calendar
import kotlin.math.abs

/**
 * A "palina" (bus/train stop pole). Nearly every field is optional depending on which
 * source (GTFS vs live Cotral API) produced it.
 *
 * IMPORTANT: once a pole's `codicePalina`/`codiceStop` is assigned (always GTFS-derived
 * in this app), it must never be overwritten by an id from another source — PIV.do,
 * GTFS and ASTRAL are three separate id spaces (see PoleDetailState.mergedPole).
 */
data class Pole(
    val codicePalina: String? = null,
    val codiceStop: String? = null,
    val nomePalina: String? = null,
    val nomeStop: String? = null,
    val localita: String? = null,
    val comune: String? = null,
    /** Latitude. */
    val coordX: Double? = null,
    /** Longitude. */
    val coordY: Double? = null,
    val zonaTariffaria: String? = null,
    val distanza: String? = null,
    val destinazioni: List<String>? = null,
    val isCotral: Int? = null,
    val isCapolinea: Int? = null,
    val isBanchinato: Int? = null,
    val preferita: Boolean? = null,
    /** Set locally from which GTFS feed (bus vs rail) this pole's stop came from. */
    val isTreno: Boolean = false,
) {
    /** Stable identity: codicePalina, then codiceStop, then coordinates. */
    val id: String
        get() = codicePalina ?: codiceStop ?: "${coordX ?: 0.0},${coordY ?: 0.0}"

    val coordinate: LatLon?
        get() {
            val lat = coordX ?: return null
            val lon = coordY ?: return null
            if (lat == 0.0 && lon == 0.0) return null
            return LatLon(lat, lon)
        }

    val displayName: String
        get() = nomePalina ?: nomeStop ?: localita ?: "Palina ${codicePalina ?: codiceStop ?: ""}"

    val subtitle: String?
        get() = listOfNotNull(comune, localita).firstOrNull { it.isNotEmpty() }
}

/** The vehicle assigned to a transit (`Transit.automezzo`). */
data class CotralVehicle(
    /** Vehicle code for Automezzi.do. Null when no vehicle is assigned to this run. */
    val codice: String?,
    /** Whether this specific vehicle is currently transmitting live position updates. */
    val isAlive: Boolean?,
)

enum class TransitTrackingStatus {
    /** `monitorata == "1"` and the assigned vehicle is currently transmitting. */
    REALTIME,
    /** `monitorata == "1"` but the assigned vehicle isn't transmitting right now. */
    MONITORED_OFFLINE,
    /** `monitorata != "1"` — schedule-only, no live tracking possible for this run. */
    SCHEDULED,
}

/** A scheduled/real-time transit at a pole, from PIV.do cmd=1. */
data class Transit(
    val idCorsa: String,
    val percorso: String,
    val partenzaCorsa: String,
    val orarioPartenzaCorsa: String,
    val arrivoCorsa: String,
    val orarioArrivoCorsa: String,
    val soppressa: String,
    val numeroOrdine: String,
    val tempoTransito: String,
    /**
     * Raw seconds (negative = ahead of schedule), deliberately NOT an "HH:MM" string —
     * round-tripping e.g. -15s through "HH:MM" produced "-00:00". Only meaningful when
     * `trackingStatus == REALTIME`; Cotral fills it with a fictitious 0 otherwise.
     */
    val ritardoSeconds: Int,
    val passato: String,
    val automezzo: CotralVehicle,
    val testoFermata: String,
    /** Not a usable timestamp — never use for "updated N seconds ago". */
    val dataModifica: String,
    val instradamento: String,
    val banchina: String,
    /** "1" when this run supports real-time tracking. */
    val monitorata: String,
    val accessibile: String,
) {
    val id: String
        get() = idCorsa.ifEmpty { "$partenzaCorsa-$arrivoCorsa-$orarioPartenzaCorsa" }

    val trackingStatus: TransitTrackingStatus
        get() {
            if (monitorata != "1") return TransitTrackingStatus.SCHEDULED
            return if (automezzo.isAlive == true) TransitTrackingStatus.REALTIME else TransitTrackingStatus.MONITORED_OFFLINE
        }

    /** `ritardo` is only a real punctuality reading in the realtime state. */
    val isDelayReliable: Boolean
        get() = trackingStatus == TransitTrackingStatus.REALTIME

    val displayTime: String
        get() = tempoTransito.ifEmpty { orarioPartenzaCorsa }

    /**
     * `displayTime` corrected for a reliable delay (≥ 60s) — the time the vehicle will
     * really get there. Under a minute isn't worth adjusting for.
     */
    val adjustedDisplayTime: String
        get() {
            if (!isDelayReliable || abs(ritardoSeconds) < 60) return displayTime
            return applyingDelay(displayTime, ritardoSeconds)
        }

    val canTrackVehicle: Boolean
        get() = monitorata == "1" && !automezzo.codice.isNullOrEmpty()

    /**
     * Minutes from now until the delay-aware arrival. Backs the "tra N min" label, the
     * sort order AND the "already passed" filter — deliberately delay-aware so a run a
     * couple of minutes late doesn't look "passed" just because its original schedule
     * time ticked by.
     */
    val minutesFromNow: Int?
        get() = minutesFromNow(adjustedDisplayTime)

    companion object {
        private fun applyingDelay(time: String, ritardoSeconds: Int): String {
            val parts = time.split(":")
            if (parts.size != 2) return time
            val h = parts[0].toIntOrNull() ?: return time
            val m = parts[1].toIntOrNull() ?: return time
            val total = h * 3600 + m * 60 + ritardoSeconds
            val wrapped = ((total % 86400) + 86400) % 86400
            return "%02d:%02d".format(java.util.Locale.ROOT, wrapped / 3600, (wrapped % 3600) / 60)
        }

        /** Handles the schedule's post-midnight convention (e.g. "25:10") with a single day wrap. */
        fun minutesFromNow(time: String, now: Calendar = Calendar.getInstance()): Int? {
            val parts = time.split(":")
            if (parts.size != 2) return null
            val h = parts[0].toIntOrNull() ?: return null
            val m = parts[1].toIntOrNull() ?: return null
            val target = h * 60 + m
            val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
            var diff = target - nowMinutes
            if (diff > 12 * 60) diff -= 24 * 60
            if (diff < -12 * 60) diff += 24 * 60
            return diff
        }
    }
}

/**
 * One position record from Automezzi.do (cmd=loc). `coordX`/`coordY` are parallel
 * arrays (a short trail of recent pings); the latest reading is the last element.
 */
data class VehiclePosition(val coordX: List<String>, val coordY: List<String>, val time: String) {
    val latestCoordinate: LatLon?
        get() {
            val lat = coordX.lastOrNull()?.toDoubleOrNull() ?: return null
            val lon = coordY.lastOrNull()?.toDoubleOrNull() ?: return null
            if (lat == 0.0 && lon == 0.0) return null
            return LatLon(lat, lon)
        }
}

/** A stop from Cotral's static GTFS. `stopId` is never namespaced (it's what PIV.do expects). */
data class GtfsStop(
    val stopId: String,
    val stopName: String,
    val stopNameLower: String,
    val stopLat: Double,
    val stopLon: Double,
    /** From the rail feed (GTFS_FERRO) rather than the bus one — display only. */
    val isRail: Boolean,
)

/** A Cotral GTFS route. `routeId` is namespaced ("F:" prefix for rail) so bus/rail never collide. */
data class GtfsRoute(
    val routeId: String,
    val routeShortName: String,
    val routeLongName: String,
    val isRail: Boolean,
)

/** A static-GTFS rail departure — last-resort tier of the rail schedule fallback. */
data class GtfsScheduledDeparture(val tripId: String, val destinationStopName: String, val departureSeconds: Int) {
    val departureTime: String
        get() = "%02d:%02d".format(java.util.Locale.ROOT, (departureSeconds / 3600) % 24, (departureSeconds / 60) % 60)
}

/** One scheduled passage at a Cotral rail station, from ASTRAL `/api/transit`. */
data class AstralDeparture(
    /** ASTRAL's own "corsa" id. */
    val id: String,
    /** "HH:mm" arrival at this stop. */
    val time: String,
    val sortSeconds: Int,
    /** Minutes of delay; null when not computed yet or from the static-GTFS fallback tier. */
    val delayMinutes: Int?,
    val isCancelled: Boolean,
    val isReplacementBus: Boolean,
)

data class AstralScheduleDirection(val destination: String, val entries: List<AstralDeparture>)

/**
 * ASTRAL returns a station's entire day — drop everything already past and re-sort,
 * otherwise `.first()` is the day's very first run, not the next one.
 */
fun List<AstralDeparture>.upcoming(now: Calendar = Calendar.getInstance()): List<AstralDeparture> {
    val nowSeconds = now.get(Calendar.HOUR_OF_DAY) * 3600 + now.get(Calendar.MINUTE) * 60 + now.get(Calendar.SECOND)
    return filter { it.sortSeconds >= nowSeconds }.sortedBy { it.sortSeconds }
}

/**
 * One station along a rail line+direction, as ASTRAL `/api/fermate/<percorso>` lists it.
 * `codice` is ASTRAL's own code — confirmed NOT the same numbering as PIV.do's pole codes
 * despite both looking like "ff900xx". Matching to GTFS is by name only.
 */
data class AstralStation(val nomeFermata: String, val codice: String, val ordine: Int)

/**
 * ASTRAL's `codicePercorso` values — one per direction of each rail line. A separate id
 * space from PIV.do/Automezzi.do and from GTFS `route_id`s.
 */
enum class CotralTrainRoute(
    val rawValue: String,
    val lineName: String,
    val directionLabel: String,
    val destinationName: String,
    val gtfsRouteShortName: String,
) {
    METROMARE_COLOMBO_TO_PSP("RL_CC-PSP", "Metromare", "Cristoforo Colombo → Porta San Paolo", "Porta San Paolo", "ROMALIDO"),
    METROMARE_PSP_TO_COLOMBO("RL_PSP-CC", "Metromare", "Porta San Paolo → Cristoforo Colombo", "Cristoforo Colombo", "ROMALIDO"),
    VITERBO_URBANA_FLAMINIO_TO_MONTEBELLO("RN_RMMON", "Roma-Viterbo Urbana", "Flaminio → Montebello", "Montebello", "RVURB"),
    VITERBO_URBANA_MONTEBELLO_TO_FLAMINIO("RN_MONRM", "Roma-Viterbo Urbana", "Montebello → Flaminio", "Flaminio", "RVURB"),
    VITERBO_EXTRA_CATALANO_TO_VITERBO("RV_CATVIT", "Roma-Viterbo Extraurbana", "Catalano → Viterbo", "Viterbo", "RVEXT"),
    VITERBO_EXTRA_VITERBO_TO_CATALANO("RV_VITCAT", "Roma-Viterbo Extraurbana", "Viterbo → Catalano", "Catalano", "RVEXT"),
    MORLUPO_TO_CATALANO("RV_MORCAT", "Roma-Viterbo Extraurbana", "Morlupo → Catalano", "Catalano", "RVEXT"),
    CATALANO_TO_MORLUPO("RV_CATMOR", "Roma-Viterbo Extraurbana", "Catalano → Morlupo", "Morlupo", "RVEXT");

    val reversed: CotralTrainRoute
        get() = when (this) {
            METROMARE_COLOMBO_TO_PSP -> METROMARE_PSP_TO_COLOMBO
            METROMARE_PSP_TO_COLOMBO -> METROMARE_COLOMBO_TO_PSP
            VITERBO_URBANA_FLAMINIO_TO_MONTEBELLO -> VITERBO_URBANA_MONTEBELLO_TO_FLAMINIO
            VITERBO_URBANA_MONTEBELLO_TO_FLAMINIO -> VITERBO_URBANA_FLAMINIO_TO_MONTEBELLO
            VITERBO_EXTRA_CATALANO_TO_VITERBO -> VITERBO_EXTRA_VITERBO_TO_CATALANO
            VITERBO_EXTRA_VITERBO_TO_CATALANO -> VITERBO_EXTRA_CATALANO_TO_VITERBO
            MORLUPO_TO_CATALANO -> CATALANO_TO_MORLUPO
            CATALANO_TO_MORLUPO -> MORLUPO_TO_CATALANO
        }

    companion object {
        fun fromRaw(raw: String?): CotralTrainRoute? = if (raw == null) null else entries.firstOrNull { it.rawValue == raw }

        /**
         * Every direction of the line a GTFS rail route (by `route_short_name`) belongs to.
         * RVEXT covers two direction pairs — callers must try every candidate.
         */
        fun directionsForGtfsRouteShortName(shortName: String): List<CotralTrainRoute>? = when (shortName.uppercase()) {
            "ROMALIDO" -> listOf(METROMARE_COLOMBO_TO_PSP, METROMARE_PSP_TO_COLOMBO)
            "RVURB" -> listOf(VITERBO_URBANA_FLAMINIO_TO_MONTEBELLO, VITERBO_URBANA_MONTEBELLO_TO_FLAMINIO)
            "RVEXT" -> listOf(VITERBO_EXTRA_CATALANO_TO_VITERBO, VITERBO_EXTRA_VITERBO_TO_CATALANO, MORLUPO_TO_CATALANO, CATALANO_TO_MORLUPO)
            else -> null
        }
    }
}
