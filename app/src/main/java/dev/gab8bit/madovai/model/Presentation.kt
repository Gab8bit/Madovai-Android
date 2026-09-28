package dev.gab8bit.madovai.model

/**
 * A single moving vehicle shown on the map, from either source. This is the ONLY place
 * (together with search results / favorites) where the Cotral and Atac pipelines merge —
 * and even here the differing visibility guarantees are explicit (see [VisibilityScope]).
 */
data class TransitVehicle(
    val id: String,
    val coordinate: LatLon,
    val bearing: Double?,
    /** Atac: GTFS route_id. Cotral: the `percorso` code of the transit that surfaced it. */
    val routeId: String?,
    val routeLabel: String?,
    val kind: TransitVehicleKind,
    val transitOperator: TransitOperator,
    val scope: VisibilityScope,
    /** Seconds of delay, only when a realtime source reliably reported one. */
    val delaySeconds: Int?,
    /** The run this vehicle is on (Atac: looks up its full stop-by-stop schedule). */
    val tripId: String?,
) {
    enum class VisibilityScope {
        /** Atac/Roma TPL: one GTFS-RT call returns every active vehicle in the network. */
        FULL_FLEET,
        /** Cotral: only vehicles found by querying poles currently visible on screen. */
        VISIBLE_AREA_ONLY,
    }
}

/** A stop search result from either data source. */
sealed interface SearchStopResult {
    val id: String
    val name: String
    val subtitle: String
    val isRail: Boolean

    data class Cotral(val stop: GtfsStop) : SearchStopResult {
        override val id get() = "cotral-${stop.stopId}"
        override val name get() = stop.stopName
        override val subtitle get() = if (stop.isRail) "Cotral · treno" else "Cotral"
        override val isRail get() = stop.isRail
    }

    data class Atac(val stop: AtacStop) : SearchStopResult {
        override val id get() = "atac-${stop.stopId}"
        override val name get() = stop.stopName
        override val subtitle get() = "Atac / Roma TPL"
        override val isRail get() = false
    }
}

/** A line search result from either data source. */
sealed interface SearchRouteResult {
    val id: String
    /** Raw GTFS short name — used for matching and sorting. */
    val shortName: String
    val longName: String
    /** What a reader should see (e.g. "Metro A" instead of "MEA"). */
    val displayName: String
    val subtitle: String
    val kind: TransitVehicleKind

    data class Cotral(val route: GtfsRoute) : SearchRouteResult {
        override val id get() = "cotral-${route.routeId}"
        override val shortName get() = route.routeShortName
        override val longName get() = route.routeLongName
        override val displayName get() = route.routeShortName
        override val subtitle get() = if (route.isRail) "Cotral · treno" else "Cotral"
        override val kind get() = if (route.isRail) TransitVehicleKind.TRENO else TransitVehicleKind.BUS
    }

    data class Atac(val route: AtacRoute) : SearchRouteResult {
        override val id get() = "atac-${route.routeId}"
        override val shortName get() = route.routeShortName
        override val longName get() = route.routeLongName
        override val displayName get() = route.friendlyName
        override val subtitle get() = route.transitOperator.label
        override val kind get() = route.kind
    }
}

/**
 * A favorited stop from either operator. Cotral's `Pole` and Atac's `AtacStop` are
 * unrelated id spaces, so ids are namespaced ("cotral:" / "atac:") — a numeric code that
 * happens to collide as a bare string between operators can never merge two favorites.
 */
sealed interface FavoriteStop {
    val id: String
    val displayName: String
    val subtitle: String?

    data class Cotral(val pole: Pole) : FavoriteStop {
        override val id get() = "cotral:${pole.id}"
        override val displayName get() = pole.displayName
        override val subtitle get() = pole.subtitle
    }

    data class Atac(val stop: AtacStop) : FavoriteStop {
        override val id get() = "atac:${stop.stopId}"
        override val displayName get() = stop.stopName
        override val subtitle get() = "Atac / Roma TPL"
    }
}
