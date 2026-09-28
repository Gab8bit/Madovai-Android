package dev.gab8bit.madovai.ui.linee

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gab8bit.madovai.AppContainer
import dev.gab8bit.madovai.model.AtacRoute
import dev.gab8bit.madovai.model.AtacStopGroup
import dev.gab8bit.madovai.model.GtfsLoadingState
import dev.gab8bit.madovai.model.GtfsRoute
import dev.gab8bit.madovai.model.Pole
import dev.gab8bit.madovai.model.SearchRouteResult
import dev.gab8bit.madovai.model.TransitVehicleKind
import dev.gab8bit.madovai.model.groupedStopsByName
import dev.gab8bit.madovai.ui.SheetRoute
import dev.gab8bit.madovai.ui.common.StatusDot
import dev.gab8bit.madovai.ui.common.kindIcon
import dev.gab8bit.madovai.ui.sheets.rememberMinuteTicker
import dev.gab8bit.madovai.ui.theme.TransitColors

/**
 * Standalone line browser (no map). Only lines where browsing is meaningful: Atac/Roma
 * TPL (live fleet + stop predictions) and Cotral's rail lines. Cotral's ~4000 bus routes
 * are deliberately left out (no per-line live data; they'd dominate the list).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineeListScreen(container: AppContainer, onOpen: (SearchRouteResult) -> Unit) {
    val atacState by container.atacGtfsStore.state.collectAsState()
    val cotralState by container.cotralGtfsStore.state.collectAsState()
    var query by rememberSaveable { mutableStateOf("") }

    val all = remember(atacState, cotralState) {
        val atac = container.atacGtfsStore.allRoutes()
        val trains = atac.filter { it.kind == TransitVehicleKind.METRO }.map { SearchRouteResult.Atac(it) } +
            container.cotralGtfsStore.allRailRoutes().map { SearchRouteResult.Cotral(it) }
        val buses = atac.filter { it.kind == TransitVehicleKind.BUS || it.kind == TransitVehicleKind.TRAM }.map { SearchRouteResult.Atac(it) }
        trains to buses
    }
    fun filtered(list: List<SearchRouteResult>): List<SearchRouteResult> {
        val q = query.trim().lowercase()
        val base = if (q.isEmpty()) list else list.filter { it.shortName.lowercase().contains(q) || it.longName.lowercase().contains(q) }
        return base.sortedBy { it.shortName }
    }
    val trains = filtered(all.first)
    val buses = filtered(all.second)

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = { TopAppBar(title = { Text("Linee") }) },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Cerca una linea") },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Filled.Close, "Cancella") } },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                )
            }
            if (trains.isNotEmpty()) {
                sectionHeader("Treni")
                items(trains, key = { r -> r.id }) { r -> LineCard(r) { onOpen(r) } }
            }
            if (buses.isNotEmpty()) {
                sectionHeader("Bus")
                items(buses, key = { r -> r.id }) { r -> LineCard(r) { onOpen(r) } }
            }
            if (trains.isEmpty() && buses.isEmpty()) {
                item {
                    Text(
                        if (atacState == GtfsLoadingState.Ready) "Nessuna linea trovata." else "Carico le linee…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }
}

private fun LazyListScope.sectionHeader(title: String) {
    item(key = "header-$title") {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp, start = 4.dp),
        )
    }
}

@Composable
private fun LineCard(route: SearchRouteResult, onClick: () -> Unit) {
    ElevatedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        ListItem(
            colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
            leadingContent = { Icon(kindIcon(route.kind), null, tint = MaterialTheme.colorScheme.primary) },
            headlineContent = { Text(route.displayName, fontWeight = FontWeight.SemiBold) },
            supportingContent = if (route.longName.isNotEmpty()) {
                { Text(route.longName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            } else null,
            trailingContent = { Text(route.subtitle, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LineeDetailScreen(container: AppContainer, kind: String, routeId: String, onBack: () -> Unit) {
    val atacState by container.atacGtfsStore.state.collectAsState()
    val cotralState by container.cotralGtfsStore.state.collectAsState()
    val atacRoute = remember(routeId, atacState) { if (kind == "atac") container.atacGtfsStore.route(routeId) else null }
    val cotralRoute = remember(routeId, cotralState) { if (kind == "cotral") container.cotralGtfsStore.route(routeId) else null }
    val title = atacRoute?.friendlyName ?: cotralRoute?.routeShortName ?: routeId

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            when {
                atacRoute != null -> AtacLineContent(container, atacRoute)
                cotralRoute != null -> CotralLineContent(container, cotralRoute) { pole -> container.sheets.push(SheetRoute.CotralPole(pole)) }
                else -> Text("Carico la linea…", modifier = Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Atac/Roma TPL: live vehicles + one row per station (platforms grouped by name via the
 * shared [groupedStopsByName] — never deduplicated, the sheet offers a direction picker).
 */
@Composable
private fun AtacLineContent(container: AppContainer, route: AtacRoute) {
    val allVehicles by container.atacRealtimeService.vehicles.collectAsState()
    val predictions by container.atacRealtimeService.stopPredictions.collectAsState()
    val now = rememberMinuteTicker()
    val vehicles = allVehicles.filter { it.routeId == route.routeId }
    val stations: List<AtacStopGroup> = remember(route.routeId) { groupedStopsByName(container.atacGtfsStore.stopsForRoute(route.routeId)) }

    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(kindIcon(route.kind), null)
                Spacer(Modifier.width(8.dp))
                Text("${route.friendlyName} · ${route.transitOperator.label}", style = MaterialTheme.typography.bodyLarge)
            }
        }
        sectionHeader("Veicoli in tempo reale (${vehicles.size})")
        if (vehicles.isEmpty()) {
            item { Text("Nessun veicolo attivo al momento su questa linea.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(vehicles) { v ->
                Card(onClick = { container.sheets.push(SheetRoute.Vehicle(v)) }, modifier = Modifier.fillMaxWidth()) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        leadingContent = { Icon(kindIcon(v.kind), null, tint = TransitColors.AtacTeal) },
                        headlineContent = { Text("Veicolo ${v.id.removePrefix("atac-")}") },
                        supportingContent = v.tripId?.let { container.atacGtfsStore.headsignForTrip(it) }?.let { h -> { Text("verso $h") } },
                        trailingContent = { StatusDot(TransitColors.Live, pulsing = true) },
                    )
                }
            }
        }
        sectionHeader("Fermate (${stations.size})")
        if (stations.isEmpty()) {
            item { Text("Nessuna fermata trovata per questa linea.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        } else {
            items(stations, key = { it.id }) { station ->
                // Soonest prediction across every platform of this station for this route.
                val next = station.stops.flatMap { predictions[it.stopId] ?: emptyList() }
                    .filter { it.routeId == route.routeId && it.arrivalMillis - now > -120_000L }
                    .minByOrNull { it.arrivalMillis }
                Card(
                    onClick = { container.sheets.push(SheetRoute.AtacStation(station, route.routeId)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                ) {
                    ListItem(
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        headlineContent = { Text(station.name) },
                        supportingContent = if (station.stops.size > 1) {
                            val directions = station.stops.mapNotNull { container.atacGtfsStore.headsign(route.routeId, it.stopId) }
                            if (directions.isNotEmpty()) ({ Text(directions.joinToString(" · "), style = MaterialTheme.typography.labelSmall) }) else null
                        } else null,
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (next != null) {
                                    val m = next.minutesFromNow(now)
                                    Text(if (m <= 0) "in arrivo" else "$m min", style = MaterialTheme.typography.labelLarge, color = TransitColors.Live, fontWeight = FontWeight.SemiBold)
                                } else {
                                    Text("—", color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        },
                    )
                }
            }
        }
    }
}

/**
 * Cotral line: stations in physical order (rail) — tapping one opens the same schedule
 * sheet as tapping its pole on the map (ASTRAL, falling back to static GTFS). A flat list
 * can't show platform distinctions, so repeated names are shown once here.
 */
@Composable
fun CotralLineContent(container: AppContainer, route: GtfsRoute, onStation: (Pole) -> Unit) {
    val stops = remember(route.routeId) {
        val seen = HashSet<String>()
        container.cotralGtfsStore.stopsForRouteInOrder(route.routeId).filter { s ->
            val k = s.stopName.trim().lowercase()
            k.isNotEmpty() && seen.add(k)
        }
    }
    LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Info, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (route.isRail) "Gli orari di ogni stazione arrivano da ASTRAL con i ritardi reali quando disponibili, altrimenti dall'orario programmato — tocca una stazione per vederli."
                        else "Tocca una fermata per vedere i transiti in tempo reale.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
        item {
            Text(
                "${if (route.isRail) "Stazioni" else "Fermate"} (${stops.size})",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp, start = 4.dp),
            )
        }
        items(stops) { stop ->
            Card(
                onClick = { onStation(container.polesRepository.pole(stop)) },
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
            ) {
                ListItem(
                    colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                    leadingContent = { Icon(kindIcon(if (stop.isRail) TransitVehicleKind.TRENO else TransitVehicleKind.BUS), null, tint = TransitColors.CotralPoleRail) },
                    headlineContent = { Text(stop.stopName) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                )
            }
        }
    }
}
