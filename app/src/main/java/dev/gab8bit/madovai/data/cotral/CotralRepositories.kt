package dev.gab8bit.madovai.data.cotral

import dev.gab8bit.madovai.Config
import dev.gab8bit.madovai.data.gtfs.CotralGtfsStore
import dev.gab8bit.madovai.data.gtfs.GtfsTextUtils
import dev.gab8bit.madovai.model.AstralDeparture
import dev.gab8bit.madovai.model.AstralScheduleDirection
import dev.gab8bit.madovai.model.AstralStation
import dev.gab8bit.madovai.model.CotralTrainRoute
import dev.gab8bit.madovai.model.CotralVehicle
import dev.gab8bit.madovai.model.GtfsStop
import dev.gab8bit.madovai.model.Pole
import dev.gab8bit.madovai.model.Transit
import dev.gab8bit.madovai.model.VehiclePosition
import dev.gab8bit.madovai.model.ViewportBounds
import dev.gab8bit.madovai.net.AstralTrainClient
import dev.gab8bit.madovai.net.CotralTimeUtils
import dev.gab8bit.madovai.net.CotralXmlClient
import dev.gab8bit.madovai.net.XmlNode
import java.util.concurrent.ConcurrentHashMap

/** Pole lookup, GTFS-only — the primary path of the reference server's `polesService.ts`. */
class PolesRepository(private val gtfsStore: CotralGtfsStore) {
    fun polesNear(latitude: Double, longitude: Double, range: Double = Config.NEARBY_POLES_RANGE_DEGREES): List<Pole> =
        gtfsStore.findStopsByPosition(latitude, longitude, range).map(::mapGtfsStopToPole)

    fun poles(bounds: ViewportBounds): List<Pole> =
        gtfsStore.findStopsInRegion(bounds.minLat, bounds.maxLat, bounds.minLon, bounds.maxLon).map(::mapGtfsStopToPole)

    fun poles(stops: List<GtfsStop>): List<Pole> = stops.map(::mapGtfsStopToPole)

    fun pole(stop: GtfsStop): Pole = mapGtfsStopToPole(stop)

    private fun mapGtfsStopToPole(stop: GtfsStop): Pole {
        val stopRoutes = gtfsStore.getRoutesForStop(stop.stopId)
        val destinations = gtfsStore.getDestinationsFromRoutes(stopRoutes)
        return Pole(
            codicePalina = stop.stopId,
            codiceStop = stop.stopId,
            nomePalina = stop.stopName,
            nomeStop = stop.stopName,
            localita = GtfsTextUtils.extractLocalityFromStopName(stop.stopName),
            coordX = stop.stopLat,
            coordY = stop.stopLon,
            destinazioni = destinations.ifEmpty { null },
            isCotral = if (stopRoutes.isEmpty()) null else 1,
            isTreno = stop.isRail,
        )
    }
}

/**
 * Live transits for a pole — always a direct PIV.do cmd=1 call, mirroring
 * `transitsService.ts.getTransitsByPoleCode` field-for-field.
 */
class TransitsRepository(private val client: CotralXmlClient) {
    data class Result(val pole: Pole, val transits: List<Transit>)

    suspend fun transits(poleCode: String): Result? {
        val root = client.fetchXml(
            "PIV.do",
            mapOf(
                "cmd" to "1",
                "userId" to Config.COTRAL_USER_ID,
                "pCodice" to poleCode,
                "pFormato" to "xml",
                "pDelta" to Config.COTRAL_DELTA,
            ),
        ) ?: return null
        val poleNode = root.firstChild("palina") ?: return null
        val corsaNodes = root.childList("corsa")
        if (corsaNodes.isEmpty()) return null

        val rawLat = poleNode.childText("latitudine").toDoubleOrNull() ?: 0.0
        val rawLon = poleNode.childText("longitudine").toDoubleOrNull() ?: 0.0
        val (lat, lon) = CotralTimeUtils.normalizeLatLon(rawLat, rawLon)

        // NOTE: this `codice` is PIV.do's own id space — callers must never let it replace a
        // GTFS-derived pole id (see PoleDetailState.mergedPole).
        val pole = Pole(
            codicePalina = poleNode.childText("codice"),
            nomePalina = poleNode.childText("nomePalina"),
            nomeStop = poleNode.childText("nomeStop"),
            localita = poleNode.childText("localita"),
            comune = poleNode.childText("comune"),
            coordX = lat,
            coordY = lon,
            preferita = poleNode.childText("preferita") == "1",
        )
        return Result(pole, corsaNodes.map(::makeTransit))
    }

    private fun makeTransit(node: XmlNode): Transit {
        val automezzo = node.firstChild("automezzo")
        val vehicleCode = automezzo?.text?.trim()
        return Transit(
            idCorsa = node.childText("idCorsa"),
            percorso = node.childText("percorso"),
            partenzaCorsa = node.childText("partenzaCorsa"),
            orarioPartenzaCorsa = CotralTimeUtils.readableTime(node.childText("orarioPartenzaCorsa")),
            arrivoCorsa = node.childText("arrivoCorsa"),
            orarioArrivoCorsa = CotralTimeUtils.readableTime(node.childText("orarioArrivoCorsa")),
            soppressa = node.childText("soppressa"),
            numeroOrdine = node.childText("numeroOrdine"),
            tempoTransito = CotralTimeUtils.readableTime(node.childText("tempoTransito")),
            ritardoSeconds = node.childText("ritardo").trim().toIntOrNull() ?: 0,
            passato = node.childText("passato"),
            automezzo = CotralVehicle(
                codice = if (vehicleCode.isNullOrEmpty()) null else vehicleCode,
                isAlive = automezzo?.attributes?.get("isAlive") == "1",
            ),
            testoFermata = node.childText("testoFermata"),
            dataModifica = node.childText("dataModifica"),
            instradamento = node.childText("instradamento"),
            banchina = node.childText("banchina"),
            monitorata = node.childText("monitorata"),
            accessibile = node.childText("accessibile"),
        )
    }
}

/** Live GPS positions via Automezzi.do cmd=loc. */
class VehicleRepository(private val client: CotralXmlClient) {
    suspend fun positions(vehicleCode: String): List<VehiclePosition> {
        val root = client.fetchXml(
            "Automezzi.do",
            mapOf(
                "cmd" to "loc",
                "userId" to Config.COTRAL_USER_ID,
                "pAutomezzo" to vehicleCode,
                "pFormato" to "xml",
            ),
        ) ?: return emptyList()
        return root.childList("posizione").map { node ->
            VehiclePosition(
                coordX = (node.attributes["pX"] ?: "").split(' ').filter { it.isNotEmpty() },
                coordY = (node.attributes["pY"] ?: "").split(' ').filter { it.isNotEmpty() },
                time = node.text,
            )
        }
    }
}

/**
 * Primary source for Cotral rail schedules/delays. Resolves a GTFS rail station to
 * ASTRAL's own stop code BY NAME — there is no shared id space (ASTRAL's codes differ
 * from PIV.do's even where they look alike). Station lists are cached per direction.
 */
class AstralTrainRepository(private val client: AstralTrainClient) {
    private val stationsCache = ConcurrentHashMap<String, List<AstralStation>>()

    suspend fun stations(route: CotralTrainRoute): List<AstralStation> {
        stationsCache[route.rawValue]?.let { return it }
        val stations = client.fetchStations(route.rawValue).sortedBy { it.ordine }
        stationsCache[route.rawValue] = stations
        return stations
    }

    /** Today's departures for `stationName` in `route`'s direction; null when the station can't be matched. */
    suspend fun departures(route: CotralTrainRoute, stationName: String): AstralScheduleDirection? {
        val fermata = fermataCode(route, stationName) ?: return null
        val transits = client.fetchTransits(route.rawValue, fermata)
        val entries = transits.mapNotNull { dto ->
            val seconds = secondsFromHHMM(dto.orario) ?: return@mapNotNull null
            AstralDeparture(
                id = dto.corsa,
                time = dto.orario,
                sortSeconds = seconds,
                delayMinutes = dto.ritardo.trim().toIntOrNull(),
                isCancelled = dto.soppressa.trim().uppercase() == "Y",
                isReplacementBus = dto.busSostitutivo.trim().uppercase() == "Y",
            )
        }
        return AstralScheduleDirection(destination = route.destinationName, entries = entries)
    }

    /** Exact name match first, then a substring match in either direction (e.g. "Acilia Sud" vs "Acilia Sud - Dragona"). */
    private suspend fun fermataCode(route: CotralTrainRoute, stationName: String): String? {
        val target = stationName.trim().lowercase()
        if (target.isEmpty()) return null
        val all = stations(route)
        all.firstOrNull { it.nomeFermata.trim().lowercase() == target }?.let { return it.codice }
        return all.firstOrNull { station ->
            val name = station.nomeFermata.trim().lowercase()
            name.isNotEmpty() && (name.contains(target) || target.contains(name))
        }?.codice
    }

    private fun secondsFromHHMM(time: String): Int? {
        val p = time.split(":")
        if (p.size != 2) return null
        val h = p[0].toIntOrNull() ?: return null
        val m = p[1].toIntOrNull() ?: return null
        return h * 3600 + m * 60
    }
}
