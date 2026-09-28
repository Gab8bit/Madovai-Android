package dev.gab8bit.madovai.model

import dev.gab8bit.madovai.Config
import kotlin.math.abs

data class LatLon(val latitude: Double, val longitude: Double) {
    fun isApproximately(other: LatLon?, tolerance: Double = 0.0000005): Boolean {
        if (other == null) return false
        return abs(latitude - other.latitude) < tolerance && abs(longitude - other.longitude) < tolerance
    }
}

/**
 * Which agency a piece of data belongs to. Cotral covers extra-urban Lazio; Atac and
 * Roma TPL together cover urban Rome — shown together on one map but never conflated.
 */
enum class TransitOperator(val label: String) {
    COTRAL("Cotral"),
    ATAC("Atac"),
    ROMA_TPL("Roma TPL"),
}

/** GTFS `route_type`: 0 = tram, 1 = subway/metro, 2 = rail, 3 = bus. */
enum class TransitVehicleKind(val gtfsRouteType: Int) {
    TRAM(0),
    METRO(1),
    TRENO(2),
    BUS(3);

    companion object {
        fun fromGtfsRouteType(type: Int): TransitVehicleKind = entries.firstOrNull { it.gtfsRouteType == type } ?: BUS
    }
}

/** A simple lat/lon bounding box of the settled map viewport. */
data class ViewportBounds(val minLat: Double, val maxLat: Double, val minLon: Double, val maxLon: Double) {
    /** Padded beyond the visible edges so a small pan doesn't immediately show empty edges. */
    val padded: ViewportBounds
        get() {
            val latPad = (maxLat - minLat) * 0.15 + Config.ATAC_VIEWPORT_PADDING_DEGREES
            val lonPad = (maxLon - minLon) * 0.15 + Config.ATAC_VIEWPORT_PADDING_DEGREES
            return ViewportBounds(minLat - latPad, maxLat + latPad, minLon - lonPad, maxLon + lonPad)
        }

    fun contains(point: LatLon): Boolean =
        point.latitude in minLat..maxLat && point.longitude in minLon..maxLon
}

/** Lifecycle of a static GTFS store (download → extract → parse → ready). */
sealed interface GtfsLoadingState {
    data object Idle : GtfsLoadingState
    data object Downloading : GtfsLoadingState
    data object Extracting : GtfsLoadingState
    data object Parsing : GtfsLoadingState
    data object Ready : GtfsLoadingState
    data class Failed(val message: String) : GtfsLoadingState
}

sealed interface NetworkState {
    data object Idle : NetworkState
    data object Loading : NetworkState
    data object Loaded : NetworkState
    data object Empty : NetworkState
    data class Error(val message: String) : NetworkState
}

sealed interface VehicleTrackingState {
    /** Not following any vehicle right now. */
    data object Idle : VehicleTrackingState
    /** Actively polling and receiving fresh positions. */
    data object Tracking : VehicleTrackingState
    /** The followed vehicle stopped transmitting — surfaced explicitly, never a silently frozen marker. */
    data class Lost(val lastUpdateMillis: Long?) : VehicleTrackingState
}
