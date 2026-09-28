package dev.gab8bit.madovai.ui

import androidx.compose.runtime.mutableStateListOf
import dev.gab8bit.madovai.Config
import dev.gab8bit.madovai.data.atac.AtacGtfsStore
import dev.gab8bit.madovai.data.cotral.PolesRepository
import dev.gab8bit.madovai.data.gtfs.CotralGtfsStore
import dev.gab8bit.madovai.model.AtacRoute
import dev.gab8bit.madovai.model.AtacStop
import dev.gab8bit.madovai.model.AtacStopGroup
import dev.gab8bit.madovai.model.GtfsLoadingState
import dev.gab8bit.madovai.model.GtfsRoute
import dev.gab8bit.madovai.model.GtfsStop
import dev.gab8bit.madovai.model.LatLon
import dev.gab8bit.madovai.model.LineShape
import dev.gab8bit.madovai.model.NetworkState
import dev.gab8bit.madovai.model.Pole
import dev.gab8bit.madovai.model.SearchRouteResult
import dev.gab8bit.madovai.model.SearchStopResult
import dev.gab8bit.madovai.model.TransitVehicle
import dev.gab8bit.madovai.model.ViewportBounds
import dev.gab8bit.madovai.service.CotralViewportVehicleService
import dev.gab8bit.madovai.service.LocationTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** A request for the map to move. `nonce` lets the same target be requested twice (locate button). */
data class CameraTarget(val center: LatLon, val zoom: Double, val nonce: Long = System.nanoTime())

/** Every bottom sheet the app can show. Kept in a stack so a sheet can open another one. */
sealed interface SheetRoute {
    val key: String

    data class CotralPole(val pole: Pole) : SheetRoute { override val key get() = "pole:${pole.id}" }
    data class AtacStation(val group: AtacStopGroup, val routeId: String?) : SheetRoute { override val key get() = "atac:${group.id}:$routeId" }
    data class Vehicle(val vehicle: TransitVehicle) : SheetRoute { override val key get() = "vehicle:${vehicle.id}" }
    data class CotralLine(val route: GtfsRoute) : SheetRoute { override val key get() = "line:${route.routeId}" }
    data object Info : SheetRoute { override val key get() = "info" }
}

class SheetHostState {
    val stack = mutableStateListOf<SheetRoute>()
    val top: SheetRoute? get() = stack.lastOrNull()

    fun push(route: SheetRoute) {
        if (stack.lastOrNull()?.key == route.key) return
        stack.add(route)
    }

    fun pop() {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex)
    }
}

/**
 * Map-screen state. Process-scoped (lives in AppContainer), so camera, focused line and
 * the "already centered on the user" flag survive rotations and tab switches — exactly
 * like the iOS ContentView's @State/@StateObject for the life of the app.
 */
class MapViewModel(
    private val gtfsStore: CotralGtfsStore,
    private val atacGtfsStore: AtacGtfsStore,
    private val polesRepository: PolesRepository,
    private val cotralViewportVehicles: CotralViewportVehicleService,
    locationTracker: LocationTracker,
    private val scope: CoroutineScope,
) {
    private val _poles = MutableStateFlow<List<Pole>>(emptyList())
    val poles: StateFlow<List<Pole>> = _poles.asStateFlow()
    private val _state = MutableStateFlow<NetworkState>(NetworkState.Idle)
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val _searchText = MutableStateFlow("")
    val searchText: StateFlow<String> = _searchText.asStateFlow()
    private val _stopResults = MutableStateFlow<List<SearchStopResult>>(emptyList())
    val stopResults: StateFlow<List<SearchStopResult>> = _stopResults.asStateFlow()
    private val _routeResults = MutableStateFlow<List<SearchRouteResult>>(emptyList())
    val routeResults: StateFlow<List<SearchRouteResult>> = _routeResults.asStateFlow()

    private val _pendingCenter = MutableStateFlow<CameraTarget?>(null)
    val pendingCenter: StateFlow<CameraTarget?> = _pendingCenter.asStateFlow()

    /**
     * "trip_selected": the line isolated on the map. Persists until the explicit Reset —
     * closing a sheet must NOT clear it.
     */
    private val _focusedRouteId = MutableStateFlow<String?>(null)
    val focusedRouteId: StateFlow<String?> = _focusedRouteId.asStateFlow()

    // Atac layer (AtacViewModel on iOS)
    private val _atacVisibleStops = MutableStateFlow<List<AtacStop>>(emptyList())
    val atacVisibleStops: StateFlow<List<AtacStop>> = _atacVisibleStops.asStateFlow()
    private val _atacVisibleShapes = MutableStateFlow<List<LineShape>>(emptyList())
    val atacVisibleShapes: StateFlow<List<LineShape>> = _atacVisibleShapes.asStateFlow()
    private val _linesVisible = MutableStateFlow(true)
    val linesVisible: StateFlow<Boolean> = _linesVisible.asStateFlow()

    private val _settledBounds = MutableStateFlow<ViewportBounds?>(null)
    val settledBounds: StateFlow<ViewportBounds?> = _settledBounds.asStateFlow()

    /** Last camera, restored when the map is recreated (tab switch / rotation). Default: Rome. */
    var savedCenter: LatLon = LatLon(41.9028, 12.4964)
        private set
    var savedZoom: Double = 12.0
        private set

    /**
     * Guards the ONE-TIME recenter on the very first GPS fix after launch. A dedicated flag,
     * never a recurring condition like "no visible poles" (that bug kept yanking the map
     * back to the user whenever they panned somewhere without poles).
     */
    private var hasCenteredOnUserOnce = false
    private var debounceJob: Job? = null

    init {
        scope.launch {
            val first = locationTracker.currentLocation.filterNotNull().first()
            if (!hasCenteredOnUserOnce) {
                hasCenteredOnUserOnce = true
                _pendingCenter.value = CameraTarget(first, USER_ZOOM)
            }
        }
        // A store finishing its load re-runs the viewport pipeline once, so poles/stops
        // appear without requiring the user to pan.
        scope.launch {
            gtfsStore.state.filter { it == GtfsLoadingState.Ready }.first()
            _settledBounds.value?.let { applyViewport(it) }
            search() // results typed while loading refresh by themselves
        }
        scope.launch {
            atacGtfsStore.state.filter { it == GtfsLoadingState.Ready }.first()
            _settledBounds.value?.let { applyViewport(it) }
            search() // results typed while loading refresh by themselves
        }
    }

    // region Viewport

    fun onCameraChanged(bounds: ViewportBounds, center: LatLon, zoom: Double) {
        savedCenter = center
        savedZoom = zoom
        debounceJob?.cancel()
        debounceJob = scope.launch {
            delay(Config.VIEWPORT_SETTLE_DELAY_MS)
            _settledBounds.value = bounds
            applyViewport(bounds)
        }
    }

    private fun applyViewport(bounds: ViewportBounds) {
        val result = polesRepository.poles(bounds)
        _poles.value = result
        _state.value = if (result.isEmpty()) NetworkState.Empty else NetworkState.Loaded
        updateAtacViewport(bounds)
        cotralViewportVehicles.updateVisiblePoles(result)
    }

    private fun updateAtacViewport(bounds: ViewportBounds) {
        if (!atacGtfsStore.isReady) return
        val p = bounds.padded
        _atacVisibleStops.value = atacGtfsStore.stopsInRegion(p.minLat, p.maxLat, p.minLon, p.maxLon)
        _atacVisibleShapes.value = if (_linesVisible.value) atacGtfsStore.shapesInRegion(p.minLat, p.maxLat, p.minLon, p.maxLon) else emptyList()
    }

    fun toggleLinesVisible() {
        _linesVisible.value = !_linesVisible.value
        _settledBounds.value?.let { updateAtacViewport(it) }
    }

    fun consumePendingCenter(target: CameraTarget) {
        if (_pendingCenter.value == target) _pendingCenter.value = null
    }

    fun centerOn(location: LatLon, zoom: Double = POLE_ZOOM) {
        _pendingCenter.value = CameraTarget(location, zoom)
    }

    // endregion

    // region Focus

    fun setFocusedRoute(routeId: String?) {
        _focusedRouteId.value = routeId
    }

    fun resetFocus() {
        _focusedRouteId.value = null
    }

    fun dismissError() {
        if (_state.value is NetworkState.Error) _state.value = NetworkState.Loaded
    }

    // endregion

    // region Search (both sources together)

    fun onSearchTextChange(text: String) {
        _searchText.value = text
        search()
    }

    private fun search() {
        val query = _searchText.value.trim()
        if (query.isEmpty()) {
            _stopResults.value = emptyList()
            _routeResults.value = emptyList()
            return
        }
        _stopResults.value = gtfsStore.searchStops(query).map { SearchStopResult.Cotral(it) } +
            atacGtfsStore.searchStops(query).map { SearchStopResult.Atac(it) }
        _routeResults.value = gtfsStore.searchRoutes(query).map { SearchRouteResult.Cotral(it) } +
            atacGtfsStore.searchRoutes(query).map { SearchRouteResult.Atac(it) }
    }

    fun clearSearch() {
        _searchText.value = ""
        _stopResults.value = emptyList()
        _routeResults.value = emptyList()
    }

    /** Jumps to a searched Cotral stop; returns its pole so the caller can open the sheet. */
    fun jumpTo(stop: GtfsStop): Pole {
        clearSearch()
        val pole = polesRepository.pole(stop)
        pole.coordinate?.let { _pendingCenter.value = CameraTarget(it, POLE_ZOOM) }
        return pole
    }

    fun centerOnAtacStop(stop: AtacStop) {
        clearSearch()
        _pendingCenter.value = CameraTarget(stop.coordinate, POLE_ZOOM)
    }

    /** Shows a searched Cotral line's stops immediately, centered on their centroid. */
    fun jumpTo(route: GtfsRoute) {
        clearSearch()
        val stops = gtfsStore.stopsForRoute(route.routeId)
        if (stops.isEmpty()) {
            _state.value = NetworkState.Error("Nessuna fermata trovata per la linea \"${route.routeShortName}\".")
            return
        }
        _poles.value = polesRepository.poles(stops)
        _state.value = NetworkState.Loaded
        _pendingCenter.value = CameraTarget(LatLon(stops.map { it.stopLat }.average(), stops.map { it.stopLon }.average()), LINE_ZOOM)
    }

    /** Centers on a searched Atac line's shape centroid; the caller then focuses the line. */
    fun jumpTo(route: AtacRoute): Boolean {
        clearSearch()
        val shapes = atacGtfsStore.shapes(route.routeId)
        var n = 0
        var sumLat = 0.0
        var sumLon = 0.0
        for (s in shapes) for (i in 0 until s.size) {
            sumLat += s.lats[i]; sumLon += s.lons[i]; n++
        }
        if (n == 0) {
            _state.value = NetworkState.Error("Nessun percorso trovato per la linea \"${route.routeShortName}\".")
            return false
        }
        _pendingCenter.value = CameraTarget(LatLon(sumLat / n, sumLon / n), LINE_ZOOM)
        return true
    }

    // endregion

    companion object {
        const val USER_ZOOM = 15.5
        const val POLE_ZOOM = 16.0
        const val LINE_ZOOM = 13.0
    }
}
