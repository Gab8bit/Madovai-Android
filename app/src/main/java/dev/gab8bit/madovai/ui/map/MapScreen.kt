package dev.gab8bit.madovai.ui.map

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.gab8bit.madovai.AppContainer
import dev.gab8bit.madovai.model.AtacStop
import dev.gab8bit.madovai.model.AtacStopGroup
import dev.gab8bit.madovai.model.CotralTrainRoute
import dev.gab8bit.madovai.model.GtfsLoadingState
import dev.gab8bit.madovai.model.LineShape
import dev.gab8bit.madovai.model.NetworkState
import dev.gab8bit.madovai.model.SearchRouteResult
import dev.gab8bit.madovai.model.SearchStopResult
import dev.gab8bit.madovai.model.TransitOperator
import dev.gab8bit.madovai.model.TransitVehicleKind
import dev.gab8bit.madovai.model.VehicleTrackingState
import dev.gab8bit.madovai.model.groupedStopsByName
import dev.gab8bit.madovai.service.LocationTracker
import dev.gab8bit.madovai.ui.MapViewModel
import dev.gab8bit.madovai.ui.SheetRoute
import dev.gab8bit.madovai.ui.common.DataLoadingBanner
import dev.gab8bit.madovai.ui.common.ErrorBanner
import dev.gab8bit.madovai.ui.common.LoadingItem
import dev.gab8bit.madovai.ui.common.kindIcon
import dev.gab8bit.madovai.ui.theme.TransitColors

/** Resolves a tapped/searched Atac platform back to its whole station (every platform sharing the name). */
fun AppContainer.atacStationGroup(stop: AtacStop): AtacStopGroup =
    AtacStopGroup(stop.stopName.trim(), atacGtfsStore.stopsSharingName(stop))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(container: AppContainer, onRequestLocationPermission: () -> Unit) {
    val vm = container.mapViewModel
    val cotralState by container.cotralGtfsStore.state.collectAsState()
    val cotralProgress by container.cotralGtfsStore.downloadProgress.collectAsState()
    val atacState by container.atacGtfsStore.state.collectAsState()
    val atacProgress by container.atacGtfsStore.downloadProgress.collectAsState()

    val poles by vm.poles.collectAsState()
    val mapState by vm.state.collectAsState()
    val focusedRouteId by vm.focusedRouteId.collectAsState()
    val atacVisibleStops by vm.atacVisibleStops.collectAsState()
    val atacVisibleShapes by vm.atacVisibleShapes.collectAsState()
    val linesVisible by vm.linesVisible.collectAsState()
    val settledBounds by vm.settledBounds.collectAsState()
    val pendingCenter by vm.pendingCenter.collectAsState()

    val atacVehicles by container.atacRealtimeService.vehicles.collectAsState()
    val cotralVehicles by container.cotralViewportVehicles.vehicles.collectAsState()
    val favoriteIds by container.favoritesStore.favoriteIds.collectAsState()
    val trackerCoordinate by container.vehicleTracker.coordinate.collectAsState()
    val trackerIsTreno by container.vehicleTracker.isTreno.collectAsState()
    val trackerState by container.vehicleTracker.state.collectAsState()
    val userLocation by container.locationTracker.currentLocation.collectAsState()
    val permission by container.locationTracker.permission.collectAsState()

    val atacReady = atacState == GtfsLoadingState.Ready
    val cotralReady = cotralState == GtfsLoadingState.Ready

    // True when the focused line is an Atac/Roma TPL one (vs a Cotral `percorso` code —
    // a different id namespace entirely).
    val isAtacFocus = focusedRouteId?.let { container.atacGtfsStore.route(it) != null } ?: false

    // While focused: only that line's own shape(s), across the whole line.
    val focusedShapes: List<LineShape> = remember(focusedRouteId, atacVisibleShapes, atacReady, cotralReady) {
        val routeId = focusedRouteId
        when {
            routeId == null -> atacVisibleShapes
            container.atacGtfsStore.route(routeId) != null -> container.atacGtfsStore.shapes(routeId)
            else -> {
                // A focused Cotral vehicle's routeId is its `percorso` (ASTRAL code for a train):
                // translate via route_short_name, never by string-building a GTFS id.
                val train = CotralTrainRoute.fromRaw(routeId)
                val rail = train?.let { t -> container.cotralGtfsStore.allRailRoutes().firstOrNull { it.routeShortName.uppercase() == t.gtfsRouteShortName } }
                rail?.let { container.cotralGtfsStore.shapes(it.routeId) } ?: emptyList()
            }
        }
    }
    // While focused: only stops served by that line. One pin per physical station (grouped
    // by name) — tapping resolves every platform again.
    val focusedAtacStops: List<AtacStop> = remember(focusedRouteId, atacVisibleStops, atacReady) {
        val routeId = focusedRouteId
        val stops = when {
            routeId == null -> atacVisibleStops
            container.atacGtfsStore.route(routeId) != null -> container.atacGtfsStore.stopsForRoute(routeId)
            else -> emptyList()
        }
        groupedStopsByName(stops).map { it.anchor }
    }
    // Merge point of the two pipelines: one combined vehicle list for presentation only.
    val allVehicles = remember(atacVehicles, cotralVehicles) { atacVehicles + cotralVehicles }
    val focusedVehicles = remember(allVehicles, focusedRouteId, settledBounds) {
        val routeId = focusedRouteId
        val filtered = if (routeId == null) allVehicles else allVehicles.filter { it.routeId == routeId }
        // Render only what's around the viewport (the full Atac fleet is 1000+ markers).
        val bounds = settledBounds?.padded
        if (bounds == null || routeId != null) filtered else filtered.filter { bounds.contains(it.coordinate) }
    }
    // While focused, hide Cotral poles entirely (no reliable "poles of this line" mapping).
    val focusedPoles = if (focusedRouteId == null) poles else emptyList()

    val favoritePoleIds = remember(favoriteIds) {
        favoriteIds.filter { it.startsWith("cotral:") }.mapTo(HashSet()) { it.removePrefix("cotral:") }
    }

    val loadingItems = buildList {
        if (!cotralReady) add(LoadingItem("cotral", "Dati Cotral", cotralState, cotralProgress) { container.retryCotral() })
        if (!atacReady) add(LoadingItem("atac", "Dati Atac/Roma TPL", atacState, atacProgress) { container.retryAtac() })
    }

    val darkTheme = isSystemInDarkTheme()
    var searchExpanded by rememberSaveable { mutableStateOf(false) }
    val followed = trackerCoordinate?.let { FollowedVehicle(it, trackerIsTreno, trackerState is VehicleTrackingState.Lost) }

    Box(Modifier.fillMaxSize()) {
        OsmMap(
            modifier = Modifier.fillMaxSize(),
            initialCenter = vm.savedCenter,
            initialZoom = vm.savedZoom,
            darkTheme = darkTheme,
            poles = focusedPoles,
            favoritePoleIds = favoritePoleIds,
            atacStops = focusedAtacStops,
            shapes = focusedShapes,
            shapeColor = { shape -> shapeColor(container, shape) },
            vehicles = focusedVehicles,
            followed = followed,
            userLocation = userLocation,
            pendingCenter = pendingCenter,
            onPendingCenterConsumed = vm::consumePendingCenter,
            onCameraChanged = vm::onCameraChanged,
            onPoleClick = { container.sheets.push(SheetRoute.CotralPole(it)) },
            onAtacStopClick = { stop ->
                container.sheets.push(SheetRoute.AtacStation(container.atacStationGroup(stop), if (isAtacFocus) focusedRouteId else null))
            },
            onVehicleClick = { vehicle ->
                container.sheets.push(SheetRoute.Vehicle(vehicle))
                vm.setFocusedRoute(vehicle.routeId)
            },
        )

        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MapSearchBar(container, vm, searchExpanded) { searchExpanded = it }

            if (!searchExpanded) Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (loadingItems.isNotEmpty()) DataLoadingBanner(loadingItems)
                if (focusedRouteId != null) FocusedLineBanner(onReset = vm::resetFocus)
                (mapState as? NetworkState.Error)?.let { err ->
                    ErrorBanner(
                        message = err.message,
                        onRetry = { userLocation?.let { vm.centerOn(it, MapViewModel.USER_ZOOM) }; vm.dismissError() },
                        onDismiss = vm::dismissError,
                    )
                }
                if (permission == LocationTracker.Permission.DENIED) PermissionDeniedBanner()
            }
        }

        if (!searchExpanded) Column(
            Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.End,
        ) {
            SmallFloatingActionButton(onClick = { container.sheets.push(SheetRoute.Info) }) {
                Icon(Icons.Outlined.Info, contentDescription = "Info")
            }
            SmallFloatingActionButton(onClick = vm::toggleLinesVisible) {
                Icon(if (linesVisible) Icons.Filled.Layers else Icons.Filled.LayersClear, contentDescription = if (linesVisible) "Nascondi linee" else "Mostra linee")
            }
            FloatingActionButton(onClick = {
                val loc = userLocation
                if (loc != null) vm.centerOn(loc, MapViewModel.USER_ZOOM) else onRequestLocationPermission()
            }) {
                Icon(Icons.Filled.MyLocation, contentDescription = "La mia posizione")
            }
        }
    }
}

private fun shapeColor(container: AppContainer, shape: LineShape): Color {
    val atacRoute = container.atacGtfsStore.route(shape.routeId)
    atacRoute?.colorHex?.let { hex -> parseHex(hex)?.let { return it } }
    // A focused Cotral rail line is drawn through the same pipeline → treno purple.
    val kind = atacRoute?.kind ?: if (shape.routeId.startsWith("F:")) TransitVehicleKind.TRENO else TransitVehicleKind.BUS
    return when (kind) {
        TransitVehicleKind.METRO -> TransitColors.Metro
        TransitVehicleKind.TRAM -> TransitColors.Tram
        TransitVehicleKind.TRENO -> TransitColors.Treno
        TransitVehicleKind.BUS -> TransitColors.BusLine
    }
}

private fun parseHex(hex: String): Color? {
    val s = hex.trim().removePrefix("#")
    if (s.length != 6) return null
    val v = s.toLongOrNull(16) ?: return null
    return Color(0xFF000000 or v)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapSearchBar(container: AppContainer, vm: MapViewModel, expanded: Boolean, setExpanded: (Boolean) -> Unit) {
    val query by vm.searchText.collectAsState()
    val stopResults by vm.stopResults.collectAsState()
    val routeResults by vm.routeResults.collectAsState()
    fun onStop(result: SearchStopResult) {
        setExpanded(false)
        when (result) {
            is SearchStopResult.Cotral -> container.sheets.push(SheetRoute.CotralPole(vm.jumpTo(result.stop)))
            is SearchStopResult.Atac -> {
                vm.centerOnAtacStop(result.stop)
                container.sheets.push(SheetRoute.AtacStation(container.atacStationGroup(result.stop), null))
            }
        }
    }

    fun onRoute(result: SearchRouteResult) {
        setExpanded(false)
        when (result) {
            is SearchRouteResult.Cotral -> {
                vm.jumpTo(result.route)
                vm.resetFocus()
                // Open the line's own station list (with tappable schedules) — otherwise a tap
                // would land on whichever pole happens to be nearest.
                container.sheets.push(SheetRoute.CotralLine(result.route))
            }
            is SearchRouteResult.Atac -> if (vm.jumpTo(result.route)) vm.setFocusedRoute(result.route.routeId)
        }
    }

    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        SearchBar(
            inputField = {
                SearchBarDefaults.InputField(
                    query = query,
                    onQueryChange = vm::onSearchTextChange,
                    onSearch = { },
                    expanded = expanded,
                    onExpandedChange = setExpanded,
                    placeholder = { Text("Cerca una fermata o una linea…") },
                    leadingIcon = {
                        if (expanded) {
                            IconButton(onClick = { setExpanded(false) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Indietro") }
                        } else {
                            Icon(Icons.Filled.Search, null)
                        }
                    },
                    trailingIcon = {
                        if (query.isNotEmpty()) {
                            IconButton(onClick = { vm.clearSearch() }) { Icon(Icons.Filled.Close, "Cancella") }
                        }
                    },
                )
            },
            expanded = expanded,
            onExpandedChange = setExpanded,
            modifier = if (expanded) Modifier else Modifier.padding(horizontal = 16.dp),
        ) {
            SearchResults(routeResults, stopResults, query, ::onRoute, ::onStop)
        }
    }
}

@Composable
private fun SearchResults(
    routes: List<SearchRouteResult>,
    stops: List<SearchStopResult>,
    query: String,
    onRoute: (SearchRouteResult) -> Unit,
    onStop: (SearchStopResult) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        if (routes.isNotEmpty()) {
            item { SectionHeader("Linee") }
            items(routes) { r ->
                ListItem(
                    headlineContent = { Text(r.displayName, fontWeight = FontWeight.SemiBold) },
                    supportingContent = {
                        Text(listOf(r.subtitle, r.longName).filter { it.isNotEmpty() }.joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { Icon(kindIcon(r.kind), null) },
                    modifier = Modifier.clickable { onRoute(r) },
                )
            }
        }
        if (stops.isNotEmpty()) {
            item { SectionHeader("Fermate") }
            items(stops) { s ->
                ListItem(
                    headlineContent = { Text(s.name) },
                    supportingContent = { Text(s.subtitle) },
                    leadingContent = {
                        Icon(
                            when (s) {
                                is SearchStopResult.Cotral -> kindIcon(if (s.isRail) TransitVehicleKind.TRENO else TransitVehicleKind.BUS)
                                is SearchStopResult.Atac -> Icons.Filled.Place
                            },
                            null,
                        )
                    },
                    modifier = Modifier.clickable { onStop(s) },
                )
            }
        }
        if (routes.isEmpty() && stops.isEmpty() && query.isNotBlank()) {
            item {
                Text(
                    "Nessun risultato per \"$query\".",
                    modifier = Modifier.padding(24.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column {
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
        )
        HorizontalDivider()
    }
}

@Composable
private fun FocusedLineBanner(onReset: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.FilterAlt, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(8.dp))
            Text(
                "Mostro solo questa linea",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onReset) {
                Icon(Icons.Filled.Close, null)
                Spacer(Modifier.width(4.dp))
                Text("Reset")
            }
        }
    }
}

@Composable
private fun PermissionDeniedBanner() {
    val context = LocalContext.current
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.LocationOff, null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Posizione non disponibile", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Attiva la posizione per vedere le paline vicine, oppure cerca una fermata.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }) { Text("Impostazioni") }
        }
    }
}

/** Display label for a vehicle's line (Cotral trains show the real line name, not the ASTRAL code). */
fun vehicleLineLabel(v: dev.gab8bit.madovai.model.TransitVehicle): String {
    if (v.transitOperator == TransitOperator.COTRAL) {
        CotralTrainRoute.fromRaw(v.routeId)?.let { return it.lineName }
        return v.routeLabel ?: "—"
    }
    return dev.gab8bit.madovai.model.AtacMetroLine.fromShortName(v.routeLabel ?: "")?.friendlyName ?: (v.routeLabel ?: "—")
}
