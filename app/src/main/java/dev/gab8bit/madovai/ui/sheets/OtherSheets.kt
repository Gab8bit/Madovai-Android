package dev.gab8bit.madovai.ui.sheets

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.TouchApp
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Radar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import dev.gab8bit.madovai.AppContainer
import dev.gab8bit.madovai.model.AstralDeparture
import dev.gab8bit.madovai.model.AtacStop
import dev.gab8bit.madovai.model.AtacStopGroup
import dev.gab8bit.madovai.model.AtacStopPrediction
import dev.gab8bit.madovai.model.AtacTripStopTime
import dev.gab8bit.madovai.model.CotralTrainRoute
import dev.gab8bit.madovai.model.RomeTime
import dev.gab8bit.madovai.model.TransitOperator
import dev.gab8bit.madovai.model.TransitVehicle
import dev.gab8bit.madovai.model.TransitVehicleKind
import dev.gab8bit.madovai.model.upcoming
import dev.gab8bit.madovai.ui.SheetRoute
import dev.gab8bit.madovai.ui.common.DelayFormat
import dev.gab8bit.madovai.ui.common.EmptyState
import dev.gab8bit.madovai.ui.common.LoadingState
import dev.gab8bit.madovai.ui.common.Pill
import dev.gab8bit.madovai.ui.common.StatusDot
import dev.gab8bit.madovai.ui.common.kindIcon
import dev.gab8bit.madovai.ui.linee.CotralLineContent
import dev.gab8bit.madovai.ui.map.vehicleLineLabel
import dev.gab8bit.madovai.ui.theme.TransitColors
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.abs
import kotlin.math.max

/** Renders the top of the sheet stack as an M3 modal bottom sheet (with drag handle). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetHost(container: AppContainer) {
    val top = container.sheets.top ?: return
    key(top.key) {
        val state = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ModalBottomSheet(onDismissRequest = { container.sheets.pop() }, sheetState = state) {
            Box(Modifier.fillMaxWidth().heightIn(min = 320.dp)) {
                when (top) {
                    is SheetRoute.CotralPole -> PoleSheet(container, top.pole)
                    is SheetRoute.AtacStation -> AtacStationSheet(container, top.group, top.routeId)
                    is SheetRoute.Vehicle -> if (top.vehicle.transitOperator == TransitOperator.COTRAL) {
                        CotralVehicleSheet(container, top.vehicle)
                    } else {
                        AtacVehicleSheet(container, top.vehicle)
                    }
                    is SheetRoute.CotralLine -> Column {
                        Text(
                            top.route.routeShortName,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
                        )
                        CotralLineContent(container, top.route) { pole -> container.sheets.push(SheetRoute.CotralPole(pole)) }
                    }
                    SheetRoute.Info -> InfoSheet()
                }
            }
        }
    }
}

// region Atac station

/**
 * Predictions are realtime-derived; when the selected platform has none at all, a
 * best-effort static-timetable estimate is shown instead (clearly labelled).
 * `group.stops` may hold several platforms (one per direction) — never collapsed.
 */
@Composable
fun AtacStationSheet(container: AppContainer, group: AtacStopGroup, routeId: String?) {
    val stops = group.stops
    var selectedId by rememberSaveable(group.id) { mutableStateOf(stops[0].stopId) }
    val selected: AtacStop = stops.firstOrNull { it.stopId == selectedId } ?: stops[0]
    val allPredictions by container.atacRealtimeService.stopPredictions.collectAsState()
    val favoriteIds by container.favoritesStore.favoriteIds.collectAsState()
    val now = rememberMinuteTicker()
    val live = (allPredictions[selected.stopId] ?: emptyList()).filter { it.arrivalMillis - now > -120_000L }

    var fallback by remember(selected.stopId) { mutableStateOf<List<AtacStopPrediction>>(emptyList()) }
    var loadingFallback by remember(selected.stopId) { mutableStateOf(false) }
    LaunchedEffect(selected.stopId) {
        if (live.isNotEmpty()) return@LaunchedEffect
        loadingFallback = true
        fallback = try {
            container.atacGtfsStore.scheduledDepartures(selected.stopId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
        loadingFallback = false
    }

    fun headsign(stop: AtacStop): String? =
        if (routeId != null) container.atacGtfsStore.headsign(routeId, stop.stopId) else container.atacGtfsStore.anyHeadsign(stop.stopId)

    Column(Modifier.fillMaxWidth()) {
        SheetHeader(
            title = stops[0].stopName,
            subtitle = "Atac / Roma TPL · tempo reale",
            // The star reflects the currently selected platform (per-stop_id granularity).
            isFavorite = "atac:${selected.stopId}" in favoriteIds,
            onToggleFavorite = { container.favoritesStore.toggle(selected) },
        )
        if (stops.size > 1) {
            DirectionChips(stops, selected, { s -> headsign(s)?.let { "Verso $it" } ?: s.stopName }) { selectedId = it.stopId }
        }
        HorizontalDivider()
        when {
            live.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                items(live) { AtacPredictionRow(it, now) }
            }
            loadingFallback -> LoadingState("Cerco gli orari in programma…")
            fallback.isNotEmpty() -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Info, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Nessun mezzo in tempo reale al momento: questi sono orari stimati da programmazione.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                items(fallback) { AtacPredictionRow(it, now) }
            }
            else -> EmptyState(Icons.Outlined.Schedule, "Nessun transito in tempo reale", "Questa fermata potrebbe non avere corse attive in questo momento.")
        }
    }
}

@Composable
private fun AtacPredictionRow(p: AtacStopPrediction, now: Long) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    val minutes = p.minutesFromNow(now)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(72.dp)) {
            Text(p.arrivalTimeLabel, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            val rel = when {
                p.isScheduled -> "tra ${max(minutes, 0)} min"
                minutes <= 0 -> "in arrivo"
                minutes == 1 -> "tra 1 min"
                else -> "tra $minutes min"
            }
            Text(rel, style = MaterialTheme.typography.labelMedium, color = neutral)
        }
        Column(Modifier.width(52.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(kindIcon(p.kind), null, Modifier.size(20.dp))
            Text(p.routeLabel, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, maxLines = 1)
        }
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            if (!p.headsign.isNullOrEmpty()) Text("verso ${p.headsign}", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val d = p.delaySeconds
            if (p.isScheduled) {
                Text("stimato", style = MaterialTheme.typography.labelMedium, color = neutral)
            } else if (d != null && abs(d) >= 60) {
                Text(DelayFormat.secondsText(d), style = MaterialTheme.typography.labelMedium, color = DelayFormat.secondsColor(d))
            }
        }
        StatusDot(if (p.isScheduled) TransitColors.Scheduled else TransitColors.Live, pulsing = !p.isScheduled)
    }
}

// endregion

// region Vehicles

@Composable
private fun VehicleHeader(icon: ImageVector, title: String, subtitle: String, delaySeconds: Int?) {
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null)
            Spacer(Modifier.width(8.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // Delay only exists here when it's genuinely reliable; <60s reads as on time.
        if (delaySeconds != null && abs(delaySeconds) >= 60) {
            Text(DelayFormat.secondsText(delaySeconds), style = MaterialTheme.typography.labelLarge, color = DelayFormat.secondsColor(delaySeconds))
        }
    }
}

/** Full stop-by-stop schedule of the tapped Atac vehicle's current run (live trip_updates). */
@Composable
fun AtacVehicleSheet(container: AppContainer, vehicle: TransitVehicle) {
    val vehicles by container.atacRealtimeService.vehicles.collectAsState()
    val tripStopTimes by container.atacRealtimeService.tripStopTimes.collectAsState()
    val live = vehicles.firstOrNull { it.id == vehicle.id } ?: vehicle
    val stops: List<AtacTripStopTime> = vehicle.tripId?.let { tripStopTimes[it] } ?: emptyList()
    val headsign = vehicle.tripId?.let { container.atacGtfsStore.headsignForTrip(it) }
    val line = vehicleLineLabel(live)
    val now = rememberMinuteTicker()

    Column(Modifier.fillMaxWidth()) {
        VehicleHeader(
            kindIcon(live.kind),
            if (headsign != null) "Linea $line verso $headsign" else "Linea $line",
            "${live.transitOperator.label} · tempo reale",
            null,
        )
        HorizontalDivider()
        if (stops.isEmpty()) {
            EmptyState(Icons.Outlined.Schedule, "Orario non disponibile", "Non ho ancora un aggiornamento in tempo reale per questa corsa.")
        } else {
            val listState = rememberLazyListState()
            LaunchedEffect(vehicle.tripId) {
                val firstUpcoming = stops.indexOfFirst { it.arrivalMillis >= System.currentTimeMillis() }
                if (firstUpcoming > 1) listState.scrollToItem(firstUpcoming - 1)
            }
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                items(stops) { s ->
                    val past = s.arrivalMillis < now
                    Row(
                        Modifier.fillMaxWidth().alpha(if (past) 0.5f else 1f).padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(10.dp).background(if (past) TransitColors.Scheduled else TransitColors.AtacTeal, CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(s.stopName, style = MaterialTheme.typography.titleSmall)
                            val d = s.delaySeconds
                            if (d != null && abs(d) >= 60) Text(DelayFormat.secondsText(d), style = MaterialTheme.typography.labelMedium, color = DelayFormat.secondsColor(d))
                        }
                        Text(RomeTime.hhmm(s.arrivalMillis), style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

private data class StationNextPassage(val stationName: String, val next: AstralDeparture?)

/**
 * Cotral has no per-run itinerary endpoint (verified), so for a TRAIN this shows ASTRAL's
 * timetable for the vehicle's own line+direction — each station's next passage — honestly
 * labelled as the line's schedule, not this specific run's. For a bus it says so plainly.
 */
@Composable
fun CotralVehicleSheet(container: AppContainer, vehicle: TransitVehicle) {
    val trainRoute = CotralTrainRoute.fromRaw(vehicle.routeId)
    var stations by remember(vehicle.routeId) { mutableStateOf<List<StationNextPassage>>(emptyList()) }
    var loading by remember(vehicle.routeId) { mutableStateOf(trainRoute != null) }
    var failed by remember(vehicle.routeId) { mutableStateOf(false) }

    LaunchedEffect(vehicle.routeId) {
        val route = trainRoute ?: return@LaunchedEffect
        loading = true
        failed = false
        try {
            val list = container.astralTrainRepository.stations(route)
            // One /api/transit call per station, concurrently.
            val next = coroutineScope {
                list.map { st ->
                    async {
                        val dir = try {
                            container.astralTrainRepository.departures(route, st.nomeFermata)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            null
                        }
                        st.nomeFermata to dir?.entries?.upcoming()?.firstOrNull()
                    }
                }.awaitAll().toMap()
            }
            // Sorted by each station's own next passage; stations done for today go last.
            stations = list.map { StationNextPassage(it.nomeFermata, next[it.nomeFermata]) }
                .sortedBy { it.next?.sortSeconds ?: Int.MAX_VALUE }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            stations = emptyList()
            failed = true
        }
        loading = false
    }

    Column(Modifier.fillMaxWidth()) {
        VehicleHeader(kindIcon(vehicle.kind), "Linea ${vehicleLineLabel(vehicle)}", "Cotral · posizione in tempo reale", vehicle.delaySeconds)
        HorizontalDivider()
        when {
            trainRoute == null -> EmptyState(
                Icons.Outlined.Info,
                "Itinerario del veicolo non disponibile",
                "Cotral non espone l'elenco delle fermate di una singola corsa bus: sono disponibili solo linea, posizione e ritardo.",
            )
            loading -> LoadingState("Carico l'orario della linea…")
            stations.isEmpty() -> EmptyState(
                Icons.Outlined.Info,
                if (failed) "Orario non disponibile" else "Itinerario del veicolo non disponibile",
                if (failed) "Non è stato possibile recuperare l'orario di questa linea al momento."
                else "Non risultano corse programmate per questa linea per il resto di oggi.",
            )
            else -> LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
                item {
                    Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(Modifier.width(6.dp))
                            Text("Orario della linea, non di questa specifica corsa", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text(trainRoute.directionLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(stations) { StationNextPassageRow(it) }
            }
        }
    }
}

@Composable
private fun StationNextPassageRow(entry: StationNextPassage) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(entry.stationName, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        val next = entry.next
        if (next == null) {
            Text("—", color = neutral.copy(alpha = 0.6f))
        } else {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (next.isReplacementBus) {
                        Icon(Icons.Filled.DirectionsBus, "Bus sostitutivo", Modifier.size(16.dp), tint = neutral)
                        Spacer(Modifier.width(4.dp))
                    }
                    Text(next.time, style = MaterialTheme.typography.titleSmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                }
                val d = next.delayMinutes
                when {
                    next.isCancelled -> Pill("Soppressa", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError)
                    d != null -> Text(DelayFormat.minutesText(d), style = MaterialTheme.typography.labelMedium, color = DelayFormat.minutesColor(d, neutral))
                }
            }
        }
    }
}

// endregion

// region Info

@Composable
fun InfoSheet() {
    LazyColumn(Modifier.fillMaxWidth().navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
        item { Text("Info", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 20.dp, bottom = 8.dp)) }
        item { InfoSection("Come funziona") }
        item {
            InfoRow(Icons.Outlined.Map, "Due fonti, una mappa", "Cotral copre l'extraurbano del Lazio, Atac e Roma TPL coprono Roma urbana (bus, tram, metro). Sono fonti indipendenti, mostrate insieme.")
        }
        item {
            InfoRow(Icons.Outlined.Radar, "Copertura dei veicoli in tempo reale", "Atac/Roma TPL: un'unica chiamata restituisce sempre tutta la flotta attiva. Cotral non ha un endpoint simile — i suoi veicoli compaiono solo interrogando le paline attualmente visibili sulla mappa, quindi la copertura è parziale per costruzione, non un difetto.")
        }
        item {
            InfoRow(Icons.Outlined.TouchApp, "Tocca un veicolo", "Filtra la mappa al solo percorso e ai mezzi di quella linea, e apre l'orario. Per Atac/Roma TPL è l'orario completo della corsa; per Cotral solo le info disponibili. Il pulsante \"Reset\" nel banner in alto riporta alla vista normale.")
        }
        item { InfoSection("Colori degli orari") }
        item { LegendDotRow(TransitColors.Live, "Verde: in tempo reale (veicolo tracciato)") }
        item { LegendDotRow(TransitColors.TrackedOffline, "Giallo: corsa tracciata, veicolo non in trasmissione") }
        item { LegendDotRow(TransitColors.Scheduled, "Grigio: solo orario programmato") }
        item { InfoSection("Simboli sulla mappa") }
        item { LegendRow(TransitColors.CotralPoleBus, kindIcon(TransitVehicleKind.BUS), "Palina Cotral (bus)") }
        item { LegendRow(TransitColors.CotralPoleRail, kindIcon(TransitVehicleKind.TRENO), "Stazione Cotral (treno)") }
        item { LegendRow(TransitColors.Favorite, Icons.Filled.Star, "Palina preferita") }
        item { LegendRow(TransitColors.AtacStop, null, "Fermata Atac / Roma TPL") }
        item { LegendRow(TransitColors.AtacTeal, kindIcon(TransitVehicleKind.BUS), "Bus Atac in movimento") }
        item { LegendRow(TransitColors.Metro, kindIcon(TransitVehicleKind.METRO), "Metro Atac") }
        item { LegendRow(TransitColors.Tram, kindIcon(TransitVehicleKind.TRAM), "Tram Atac in movimento") }
        item { LegendRow(TransitColors.RomaTpl, kindIcon(TransitVehicleKind.BUS), "Mezzo Roma TPL in movimento") }
        item { LegendRow(TransitColors.CotralOrange, kindIcon(TransitVehicleKind.BUS), "Veicolo Cotral in movimento") }
        item { InfoSection("Crediti") }
        item { CreditRow("Dati trasporto Roma", "Roma Servizi per la Mobilità — romamobilita.it, licenza CC-BY 3.0 Italia.") }
        item { CreditRow("Dati Cotral", "Endpoint e feed GTFS pubblici di Cotral S.p.A.; orari treni da ASTRAL S.p.A.") }
        item { CreditRow("Mappa", "© OpenStreetMap contributors (ODbL), tiles da tile.openstreetmap.org via osmdroid.") }
    }
}

@Composable
private fun InfoSection(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp))
}

@Composable
private fun InfoRow(icon: ImageVector, title: String, text: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LegendRow(color: Color, icon: ImageVector?, label: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).background(color, CircleShape), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, null, Modifier.size(14.dp), tint = Color.White)
        }
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun LegendDotRow(color: Color, label: String) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { StatusDot(color, size = 10) }
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CreditRow(title: String, text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// endregion
