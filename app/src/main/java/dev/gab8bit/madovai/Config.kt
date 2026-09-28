package dev.gab8bit.madovai

/**
 * App-wide configuration — mirrors the iOS app's `Config.swift` value-for-value.
 *
 * The app talks directly to Cotral's own internal live-data endpoint (the same
 * undocumented one github.com/ChromuSx/cotral's server proxies) plus the public
 * static GTFS / GTFS-Realtime feeds. There is no middleman server.
 */
object Config {
    /** Cotral's internal live-data endpoint. Plain HTTP (see res/xml/network_security_config.xml). */
    const val COTRAL_BASE_URL = "http://travel.mob.cotralspa.it:7777/beApp"

    /**
     * Shared client id for PIV.do cmd=1 / Automezzi.do — the default value published in
     * ChromuSx/cotral's own (MIT-licensed) server config. If Cotral ever invalidates it,
     * check that repo for an updated value.
     */
    const val COTRAL_USER_ID = "1BB73DCDAFA007572FC51E7407AB497C"

    /** Look-ahead window (minutes) for transit queries, matching the reference server's default. */
    const val COTRAL_DELTA = "261"

    /** Cotral's public static GTFS feeds — plain HTTP on the same host/port as the live endpoint. */
    const val GTFS_BUS_ZIP_URL = "http://travel.mob.cotralspa.it:7777/GTFS/GTFS_COTRAL.zip"
    const val GTFS_RAIL_ZIP_URL = "http://travel.mob.cotralspa.it:7777/GTFS/GTFS_FERRO.zip"

    /** ASTRAL live-schedule API — primary source for Cotral's 3 rail lines. */
    const val ASTRAL_BASE_URL = "https://gestionecorse.astralspa.it/api"

    const val REQUEST_TIMEOUT_SECONDS = 15L
    const val GTFS_DOWNLOAD_TIMEOUT_SECONDS = 120L

    /** How often an open pole sheet refreshes its transits. */
    const val TRANSITS_POLL_INTERVAL_MS = 15_000L

    /** How often a followed vehicle's live position is re-fetched. */
    const val VEHICLE_POSITION_POLL_INTERVAL_MS = 12_000L

    /** Consecutive failed/empty position polls before live tracking is reported lost. */
    const val VEHICLE_TRACKING_LOSS_THRESHOLD = 2

    const val NEARBY_POLES_RANGE_DEGREES = 0.01

    // Roma Servizi per la Mobilità (Atac / Roma TPL), CC-BY 3.0 Italia
    const val ATAC_GTFS_ZIP_URL = "https://romamobilita.it/sites/default/files/rome_static_gtfs.zip"
    const val ATAC_VEHICLE_POSITIONS_URL = "https://romamobilita.it/sites/default/files/rome_rtgtfs_vehicle_positions_feed.pb"
    const val ATAC_TRIP_UPDATES_URL = "https://romamobilita.it/sites/default/files/rome_rtgtfs_trip_updates_feed.pb"

    const val ATAC_REALTIME_POLL_INTERVAL_MS = 18_000L
    const val ATAC_VIEWPORT_PADDING_DEGREES = 0.01

    /** Debounce before reacting to a map viewport change. */
    const val VIEWPORT_SETTLE_DELAY_MS = 600L

    const val COTRAL_VIEWPORT_VEHICLE_POLL_INTERVAL_MS = 18_000L
    const val COTRAL_VIEWPORT_POLE_QUERY_LIMIT = 15

    /** Identifies this app to the OSM tile servers, per the OSM tile usage policy. */
    val OSM_USER_AGENT = "Madovai-Android/${BuildConfig.VERSION_NAME} (${BuildConfig.APPLICATION_ID}; personal transit tracker)"
}
