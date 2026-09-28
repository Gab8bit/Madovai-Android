package dev.gab8bit.madovai.ui.map

import android.animation.ValueAnimator
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.view.animation.AccelerateDecelerateInterpolator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import dev.gab8bit.madovai.model.AtacStop
import dev.gab8bit.madovai.model.LatLon
import dev.gab8bit.madovai.model.LineShape
import dev.gab8bit.madovai.model.Pole
import dev.gab8bit.madovai.model.TransitVehicle
import dev.gab8bit.madovai.model.ViewportBounds
import dev.gab8bit.madovai.ui.CameraTarget
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline

/** The single Cotral vehicle the user explicitly chose to "Segui". */
data class FollowedVehicle(val coordinate: LatLon, val isTreno: Boolean, val lost: Boolean)

/**
 * osmdroid map (no Google Maps SDK / API key needed). Draws the simulated "public
 * transport layer": muted OSM tiles + line shapes + stops + live vehicles on top.
 */
@Composable
fun OsmMap(
    modifier: Modifier,
    initialCenter: LatLon,
    initialZoom: Double,
    darkTheme: Boolean,
    poles: List<Pole>,
    favoritePoleIds: Set<String>,
    atacStops: List<AtacStop>,
    shapes: List<LineShape>,
    shapeColor: (LineShape) -> Color,
    vehicles: List<TransitVehicle>,
    followed: FollowedVehicle?,
    userLocation: LatLon?,
    pendingCenter: CameraTarget?,
    onPendingCenterConsumed: (CameraTarget) -> Unit,
    onCameraChanged: (ViewportBounds, LatLon, Double) -> Unit,
    onPoleClick: (Pole) -> Unit,
    onAtacStopClick: (AtacStop) -> Unit,
    onVehicleClick: (TransitVehicle) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraCallback by rememberUpdatedState(onCameraChanged)
    val poleClick by rememberUpdatedState(onPoleClick)
    val stopClick by rememberUpdatedState(onAtacStopClick)
    val vehicleClick by rememberUpdatedState(onVehicleClick)

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            minZoomLevel = 7.0
            maxZoomLevel = 19.5
            isVerticalMapRepetitionEnabled = false
            controller.setZoom(initialZoom)
            controller.setCenter(GeoPoint(initialCenter.latitude, initialCenter.longitude))
        }
    }
    val sync = remember { OverlaySync(mapView, MarkerIcons(context)) }

    DisposableEffect(mapView) {
        fun emitCamera() {
            val bb = mapView.boundingBox
            val c = mapView.mapCenter
            cameraCallback(
                ViewportBounds(minLat = bb.latSouth, maxLat = bb.latNorth, minLon = bb.lonWest, maxLon = bb.lonEast),
                LatLon(c.latitude, c.longitude),
                mapView.zoomLevelDouble,
            )
        }
        val listener = object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { emitCamera(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { emitCamera(); return false }
        }
        mapView.addMapListener(listener)
        mapView.addOnFirstLayoutListener { _, _, _, _, _ -> emitCamera() }
        onDispose { mapView.removeMapListener(listener) }
    }

    DisposableEffect(lifecycleOwner, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onResume()
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            sync.cancelAnimations()
            mapView.onPause()
            mapView.onDetach()
        }
    }

    LaunchedEffect(darkTheme) {
        mapView.overlayManager.tilesOverlay.setColorFilter(tileFilter(darkTheme))
        mapView.invalidate()
    }

    LaunchedEffect(pendingCenter) {
        val target = pendingCenter ?: return@LaunchedEffect
        mapView.controller.animateTo(GeoPoint(target.center.latitude, target.center.longitude), target.zoom, 700L)
        onPendingCenterConsumed(target)
    }

    SideEffect {
        sync.setShapes(shapes, shapeColor)
        sync.setAtacStops(atacStops) { stopClick(it) }
        sync.setPoles(poles, favoritePoleIds) { poleClick(it) }
        sync.setVehicles(vehicles) { vehicleClick(it) }
        sync.setFollowed(followed)
        sync.setUser(userLocation)
        mapView.invalidate()
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/** Muted tiles so the drawn lines/stops stand out (iOS used MapKit's `.muted` style); dark = inverted greys. */
private fun tileFilter(dark: Boolean): ColorMatrixColorFilter {
    val m = ColorMatrix()
    if (dark) {
        val invert = ColorMatrix(
            floatArrayOf(
                -1f, 0f, 0f, 0f, 255f,
                0f, -1f, 0f, 0f, 255f,
                0f, 0f, -1f, 0f, 255f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        val sat = ColorMatrix().apply { setSaturation(0.2f) }
        val dim = ColorMatrix().apply { setScale(0.85f, 0.87f, 0.92f, 1f) }
        m.postConcat(invert); m.postConcat(sat); m.postConcat(dim)
    } else {
        m.setSaturation(0.45f)
        val lift = ColorMatrix(
            floatArrayOf(
                0.92f, 0f, 0f, 0f, 16f,
                0f, 0.92f, 0f, 0f, 16f,
                0f, 0f, 0.92f, 0f, 18f,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        m.postConcat(lift)
    }
    return ColorMatrixColorFilter(m)
}

/**
 * Diffs model lists into osmdroid overlays (keyed by id, like the iOS coordinator's
 * annotation dictionaries). Layering: shapes < Atac stops < Cotral poles < vehicles <
 * followed vehicle < user location < attribution.
 */
private class OverlaySync(private val map: MapView, private val icons: MarkerIcons) {
    private val shapeFolder = FolderOverlay()
    private val stopFolder = FolderOverlay()
    private val poleFolder = FolderOverlay()
    private val vehicleFolder = FolderOverlay()
    private val topFolder = FolderOverlay()

    private val shapeLines = HashMap<String, Polyline>()
    private val stopMarkers = HashMap<String, Marker>()
    private val poleMarkers = HashMap<String, Pair<Marker, Boolean>>()
    private val vehicleMarkers = HashMap<String, Marker>()
    private var followedMarker: Marker? = null
    private var userMarker: Marker? = null

    private var stopClick: (AtacStop) -> Unit = {}
    private var poleClick: (Pole) -> Unit = {}
    private var vehicleClick: (TransitVehicle) -> Unit = {}
    private val stopById = HashMap<String, AtacStop>()
    private val poleById = HashMap<String, Pole>()
    private val vehicleById = HashMap<String, TransitVehicle>()

    private var animator: ValueAnimator? = null

    init {
        map.overlays.add(shapeFolder)
        map.overlays.add(stopFolder)
        map.overlays.add(poleFolder)
        map.overlays.add(vehicleFolder)
        map.overlays.add(topFolder)
        map.overlays.add(CopyrightOverlay(map.context).apply { setAlignBottom(true); setAlignRight(false) })
    }

    fun cancelAnimations() {
        animator?.cancel()
        animator = null
    }

    fun setShapes(shapes: List<LineShape>, color: (LineShape) -> Color) {
        val ids = shapes.mapTo(HashSet()) { it.shapeId }
        shapeLines.keys.filter { it !in ids }.forEach { id -> shapeLines.remove(id)?.let { shapeFolder.remove(it) } }
        val density = map.context.resources.displayMetrics.density
        for (shape in shapes) {
            if (shapeLines.containsKey(shape.shapeId)) continue
            val line = Polyline(map).apply {
                setPoints(List(shape.size) { i -> GeoPoint(shape.lats[i], shape.lons[i]) })
                outlinePaint.color = color(shape).copy(alpha = 0.85f).toArgb()
                outlinePaint.strokeWidth = 3f * density
                outlinePaint.strokeCap = Paint.Cap.ROUND
                outlinePaint.strokeJoin = Paint.Join.ROUND
                infoWindow = null
                setOnClickListener { _, _, _ -> false }
            }
            shapeLines[shape.shapeId] = line
            shapeFolder.add(line)
        }
    }

    fun setAtacStops(stops: List<AtacStop>, onClick: (AtacStop) -> Unit) {
        stopClick = onClick
        val ids = stops.mapTo(HashSet()) { it.stopId }
        stopMarkers.keys.filter { it !in ids }.forEach { id ->
            stopMarkers.remove(id)?.let { stopFolder.remove(it) }
            stopById.remove(id)
        }
        for (stop in stops) {
            stopById[stop.stopId] = stop
            if (stopMarkers.containsKey(stop.stopId)) continue
            val m = marker(stop.coordinate, icons.atacStop(), stop.stopName) { stopById[stop.stopId]?.let { s -> stopClick(s) } }
            stopMarkers[stop.stopId] = m
            stopFolder.add(m)
        }
    }

    fun setPoles(poles: List<Pole>, favorites: Set<String>, onClick: (Pole) -> Unit) {
        poleClick = onClick
        val visible = poles.filter { it.coordinate != null }
        val ids = visible.mapTo(HashSet()) { it.id }
        poleMarkers.keys.filter { it !in ids }.forEach { id ->
            poleMarkers.remove(id)?.let { poleFolder.remove(it.first) }
            poleById.remove(id)
        }
        for (pole in visible) {
            poleById[pole.id] = pole
            val fav = pole.id in favorites
            val existing = poleMarkers[pole.id]
            if (existing != null) {
                if (existing.second != fav) {
                    existing.first.icon = icons.pole(pole.isTreno, fav)
                    poleMarkers[pole.id] = existing.first to fav
                }
                continue
            }
            val id = pole.id
            val m = marker(pole.coordinate!!, icons.pole(pole.isTreno, fav), pole.displayName) { poleById[id]?.let { p -> poleClick(p) } }
            poleMarkers[id] = m to fav
            poleFolder.add(m)
        }
    }

    fun setVehicles(vehicles: List<TransitVehicle>, onClick: (TransitVehicle) -> Unit) {
        vehicleClick = onClick
        val ids = vehicles.mapTo(HashSet()) { it.id }
        vehicleMarkers.keys.filter { it !in ids }.forEach { id ->
            vehicleMarkers.remove(id)?.let { vehicleFolder.remove(it) }
            vehicleById.remove(id)
        }
        val moves = ArrayList<Triple<Marker, GeoPoint, GeoPoint>>()
        for (v in vehicles) {
            vehicleById[v.id] = v
            val existing = vehicleMarkers[v.id]
            if (existing != null) {
                existing.icon = icons.vehicle(v)
                val from = existing.position
                val to = GeoPoint(v.coordinate.latitude, v.coordinate.longitude)
                if (!LatLon(from.latitude, from.longitude).isApproximately(v.coordinate)) moves.add(Triple(existing, GeoPoint(from), to))
                continue
            }
            val id = v.id
            val m = marker(v.coordinate, icons.vehicle(v), v.routeLabel?.let { "Linea $it" } ?: v.transitOperator.label) {
                vehicleById[id]?.let { x -> vehicleClick(x) }
            }
            vehicleMarkers[id] = m
            vehicleFolder.add(m)
        }
        animateMoves(moves)
    }

    /** One shared animator eases every moved marker to its new position (~1s), like the iOS map. */
    private fun animateMoves(moves: List<Triple<Marker, GeoPoint, GeoPoint>>) {
        if (moves.isEmpty()) return
        animator?.cancel()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { a ->
                val t = a.animatedFraction.toDouble()
                for ((m, from, to) in moves) {
                    m.position = GeoPoint(from.latitude + (to.latitude - from.latitude) * t, from.longitude + (to.longitude - from.longitude) * t)
                }
                map.invalidate()
            }
            start()
        }
    }

    fun setFollowed(followed: FollowedVehicle?) {
        if (followed == null) {
            followedMarker?.let { topFolder.remove(it) }
            followedMarker = null
            return
        }
        val m = followedMarker ?: marker(followed.coordinate, icons.followedVehicle(followed.isTreno, followed.lost), null) {}.also {
            followedMarker = it
            topFolder.add(it)
        }
        m.icon = icons.followedVehicle(followed.isTreno, followed.lost)
        m.alpha = if (followed.lost) 0.65f else 1f
        m.title = if (followed.isTreno) "Treno in tempo reale" else "Bus in tempo reale"
        m.position = GeoPoint(followed.coordinate.latitude, followed.coordinate.longitude)
    }

    fun setUser(location: LatLon?) {
        if (location == null) {
            userMarker?.let { topFolder.remove(it) }
            userMarker = null
            return
        }
        val m = userMarker ?: marker(location, icons.userLocation(), null) {}.also {
            it.setOnMarkerClickListener { _, _ -> false }
            userMarker = it
            topFolder.add(it)
        }
        m.position = GeoPoint(location.latitude, location.longitude)
    }

    private fun marker(at: LatLon, icon: android.graphics.drawable.Drawable, title: String?, onClick: () -> Unit): Marker =
        Marker(map).apply {
            position = GeoPoint(at.latitude, at.longitude)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            this.icon = icon
            this.title = title
            infoWindow = null
            setOnMarkerClickListener { _, _ ->
                onClick()
                true
            }
        }
}
