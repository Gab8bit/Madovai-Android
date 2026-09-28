package dev.gab8bit.madovai

import dev.gab8bit.madovai.data.atac.AtacGtfsStore
import dev.gab8bit.madovai.data.atac.AtacRealtimeService
import dev.gab8bit.madovai.data.cotral.AstralTrainRepository
import dev.gab8bit.madovai.data.cotral.PolesRepository
import dev.gab8bit.madovai.data.cotral.TransitsRepository
import dev.gab8bit.madovai.data.cotral.VehicleRepository
import dev.gab8bit.madovai.data.gtfs.CotralGtfsStore
import dev.gab8bit.madovai.model.CotralTrainRoute
import dev.gab8bit.madovai.model.GtfsLoadingState
import dev.gab8bit.madovai.model.groupedStopsByName
import dev.gab8bit.madovai.model.upcoming
import dev.gab8bit.madovai.net.AstralTrainClient
import dev.gab8bit.madovai.net.CotralXmlClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Opt-in end-to-end check of the real data layer against the LIVE feeds (no emulator
 * needed): `./gradlew testDebugUnitTest -PliveTests`. Downloads ~60MB of GTFS into
 * build/live-gtfs (reused on later runs, exactly like the app's own cache).
 */
class LiveFeedsSmokeTest {
    /**
     * JVM equivalent of res/xml/network_security_config.xml's ASTRAL domain-config:
     * system roots + the bundled Sectigo intermediate as an extra anchor.
     */
    private fun astralClientLikeAndroid(): okhttp3.OkHttpClient {
        val cf = java.security.cert.CertificateFactory.getInstance("X.509")
        val ks = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType()).apply { load(null, null) }
        File("src/main/res/raw/astral_sectigo_ov_r36.pem").inputStream().use { ks.setCertificateEntry("astral-intermediate", cf.generateCertificate(it)) }
        val defaultTm = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as java.security.KeyStore?) }.trustManagers.first() as javax.net.ssl.X509TrustManager
        val extraTm = javax.net.ssl.TrustManagerFactory.getInstance(javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(ks) }.trustManagers.first() as javax.net.ssl.X509TrustManager
        val tm = object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = defaultTm.checkClientTrusted(chain, authType)
            override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {
                try { defaultTm.checkServerTrusted(chain, authType) } catch (e: java.security.cert.CertificateException) { extraTm.checkServerTrusted(chain, authType) }
            }
            override fun getAcceptedIssuers() = defaultTm.acceptedIssuers + extraTm.acceptedIssuers
        }
        val ctx = javax.net.ssl.SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return dev.gab8bit.madovai.net.HttpClients.api.newBuilder().sslSocketFactory(ctx.socketFactory, tm).build()
    }

    private val base = File("build/live-gtfs").apply { mkdirs() }

    private fun log(msg: String) = println("[smoke] $msg")

    private inline fun <T> timed(label: String, block: () -> T): T {
        val t0 = System.currentTimeMillis()
        val r = block()
        log("$label took ${System.currentTimeMillis() - t0} ms")
        return r
    }

    @Test
    fun cotralPipeline() = runBlocking {
        val store = CotralGtfsStore(base)
        timed("Cotral GTFS load") { store.ensureLoaded() }
        assertEquals(GtfsLoadingState.Ready, store.state.value)

        val rail = store.allRailRoutes()
        log("rail routes: ${rail.map { "${it.routeId}=${it.routeShortName}" }}")
        assertTrue(rail.isNotEmpty())

        val poles = PolesRepository(store)
        val near = poles.polesNear(41.8925, 12.4853, 0.02)
        log("poles near Rome centre: ${near.size}; first=${near.firstOrNull()}")

        val search = store.searchStops("acilia")
        log("search 'acilia': ${search.map { "${it.stopId}:${it.stopName}:${it.isRail}" }}")
        val lines = store.searchRoutes("frosinone").take(3)
        log("search 'frosinone' routes: ${lines.map { it.routeShortName + " " + it.routeLongName }}")

        // Rail line order + static schedule tier.
        val lido = rail.firstOrNull { it.routeShortName.uppercase() == "ROMALIDO" }
        if (lido != null) {
            val ordered = store.stopsForRouteInOrder(lido.routeId)
            log("ROMALIDO ordered stations (${ordered.size}): ${ordered.map { it.stopName }}")
            val stop = ordered.firstOrNull { it.stopName.contains("Acilia", ignoreCase = true) } ?: ordered.first()
            val gtfsDeps = store.scheduledDepartures(stop.stopId)
            log("GTFS static departures at ${stop.stopId} ${stop.stopName}: ${gtfsDeps?.size} (first: ${gtfsDeps?.firstOrNull()})")

            // ASTRAL tier (name-matched, all candidate directions).
            val astral = AstralTrainRepository(AstralTrainClient(astralClientLikeAndroid()))
            for (dir in CotralTrainRoute.directionsForGtfsRouteShortName(lido.routeShortName).orEmpty()) {
                val d = runCatching { astral.departures(dir, stop.stopName) }
                (d.exceptionOrNull() as? dev.gab8bit.madovai.net.ApiException.Network)?.let { log("  network detail: ${it.detail}") }
                log("ASTRAL ${dir.rawValue} @ '${stop.stopName}': ${d.exceptionOrNull() ?: "${d.getOrNull()?.entries?.size} entries, next=${d.getOrNull()?.entries?.upcoming()?.firstOrNull()}"}")
            }
            // RVEXT spans two direction pairs: verify candidates resolve independently.
            rail.firstOrNull { it.routeShortName.uppercase() == "RVEXT" }?.let { rvext ->
                val st = store.stopsForRouteInOrder(rvext.routeId)
                log("RVEXT stations (${st.size}): ${st.take(6).map { it.stopName }} …")
                val station = st.getOrNull(st.size / 2)
                if (station != null) {
                    for (dir in CotralTrainRoute.directionsForGtfsRouteShortName("RVEXT").orEmpty()) {
                        val d = runCatching { astral.departures(dir, station.stopName) }
                        log("ASTRAL ${dir.rawValue} @ '${station.stopName}': ${d.exceptionOrNull() ?: d.getOrNull()?.let { "${it.entries.size} entries → ${it.destination}" } ?: "no station match"}")
                    }
                }
            }
        }

        // Live PIV.do: try poles until one answers with transits.
        val transitsRepo = TransitsRepository(CotralXmlClient())
        val candidates = poles.polesNear(41.842, 12.586, 0.02).take(12) // around Anagnina, a Cotral hub
        var found = false
        for (p in candidates) {
            val code = p.codicePalina ?: continue
            val result = runCatching { transitsRepo.transits(code) }
            val r = result.getOrNull()
            log("PIV.do $code (${p.displayName}): ${result.exceptionOrNull()?.toString() ?: "${r?.transits?.size ?: 0} transits"}")
            if (r != null && r.transits.isNotEmpty()) {
                log("  PIV pole codice=${r.pole.codicePalina} (GTFS id $code) coords=${r.pole.coordX},${r.pole.coordY}")
                r.transits.take(5).forEach { t ->
                    log("  ${t.percorso} → ${t.arrivoCorsa} at ${t.displayTime} adj=${t.adjustedDisplayTime} status=${t.trackingStatus} ritardo=${t.ritardoSeconds}s min=${t.minutesFromNow} vehicle=${t.automezzo}")
                }
                val tracked = r.transits.firstOrNull { it.canTrackVehicle }
                if (tracked != null) {
                    val pos = VehicleRepository(CotralXmlClient()).positions(tracked.automezzo.codice!!)
                    log("  Automezzi.do ${tracked.automezzo.codice}: ${pos.size} records, latest=${pos.firstOrNull()?.latestCoordinate}")
                }
                found = true
                break
            }
        }
        log("PIV.do returned live data: $found")
    }

    @Test
    fun atacPipeline() = runBlocking {
        val store = AtacGtfsStore(base)
        timed("Atac GTFS load") { store.ensureLoaded() }
        val state = store.state.value
        log("Atac state: $state")
        assertEquals(GtfsLoadingState.Ready, state)
        val rt = Runtime.getRuntime()
        log("heap used after Atac load: ${(rt.totalMemory() - rt.freeMemory()) / 1_000_000} MB")

        val routes = store.allRoutes()
        log("routes: ${routes.size}; metro: ${routes.filter { it.kind.name == "METRO" }.map { it.routeId + "/" + it.friendlyName }}")
        val metroA = store.route("MEA")
        log("MEA route: $metroA")
        val stopsA = store.stopsForRoute("MEA")
        val groups = groupedStopsByName(stopsA)
        log("Metro A: ${stopsA.size} platforms in ${groups.size} stations")
        groups.take(4).forEach { g ->
            log("  ${g.name}: ${g.stops.map { "${it.stopId}→${store.headsign("MEA", it.stopId)}" }}")
        }
        log("MEA shapes: ${store.shapes("MEA").size}")
        val searchStops = store.searchStops("termini").take(5)
        log("search 'termini': ${searchStops.map { it.stopId + ":" + it.stopName }}")
        val shared = searchStops.firstOrNull()?.let { store.stopsSharingName(it) }
        log("platforms sharing name with first: ${shared?.map { it.stopId + "(" + store.anyHeadsign(it.stopId) + ")" }}")

        val realtime = AtacRealtimeService(store, CoroutineScope(Dispatchers.Default))
        timed("Atac realtime refresh") { realtime.refresh() }
        log("realtime error: ${realtime.lastError.value}")
        val vehicles = realtime.vehicles.value
        log("vehicles: ${vehicles.size}; sample=${vehicles.take(3)}")
        val preds = realtime.stopPredictions.value
        log("stops with predictions: ${preds.size}; trips: ${realtime.tripStopTimes.value.size}")
        val sample = preds.entries.firstOrNull()
        if (sample != null) {
            log("  stop ${sample.key} (${store.stop(sample.key)?.stopName}): ${sample.value.take(3).map { "${it.routeLabel} ${it.arrivalTimeLabel} delay=${it.delaySeconds} → ${it.headsign}" }}")
            val fallback = timed("Atac scheduled fallback scan") { store.scheduledDepartures(sample.key) }
            log("  scheduled fallback for ${sample.key}: ${fallback.map { "${it.routeLabel} ${it.arrivalTimeLabel} → ${it.headsign}" }}")
        }
        assertTrue(vehicles.isNotEmpty())
    }

    @Test
    fun memoryFootprintBothStores() = runBlocking {
        val cotral = CotralGtfsStore(base)
        val atac = AtacGtfsStore(base)
        cotral.ensureLoaded(); atac.ensureLoaded()
        val rt = Runtime.getRuntime()
        repeat(3) { System.gc(); Thread.sleep(300) }
        log("retained heap with BOTH stores loaded: ${(rt.totalMemory() - rt.freeMemory()) / 1_000_000} MB")
        assertTrue(cotral.isReady && atac.isReady)
    }
}
