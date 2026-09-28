package dev.gab8bit.madovai

import android.content.Context
import dev.gab8bit.madovai.data.atac.AtacGtfsStore
import dev.gab8bit.madovai.data.atac.AtacRealtimeService
import dev.gab8bit.madovai.data.cotral.AstralTrainRepository
import dev.gab8bit.madovai.data.cotral.PolesRepository
import dev.gab8bit.madovai.data.cotral.TransitsRepository
import dev.gab8bit.madovai.data.cotral.VehicleRepository
import dev.gab8bit.madovai.data.gtfs.CotralGtfsStore
import dev.gab8bit.madovai.net.AstralTrainClient
import dev.gab8bit.madovai.net.CotralXmlClient
import dev.gab8bit.madovai.service.CotralViewportVehicleService
import dev.gab8bit.madovai.service.FavoritesStore
import dev.gab8bit.madovai.service.LocationTracker
import dev.gab8bit.madovai.service.VehicleTracker
import dev.gab8bit.madovai.ui.MapViewModel
import dev.gab8bit.madovai.ui.SheetHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Manual dependency container, one per process. The Cotral and Atac pipelines are wired
 * completely separately here; they only meet in the UI's presentation models.
 */
class AppContainer(context: Context) {
    /** Main-thread scope: services mutate their state here, like the iOS @MainActor classes. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    // Cotral
    val cotralGtfsStore = CotralGtfsStore(context.filesDir)
    private val cotralXmlClient = CotralXmlClient()
    val polesRepository = PolesRepository(cotralGtfsStore)
    val transitsRepository = TransitsRepository(cotralXmlClient)
    val vehicleRepository = VehicleRepository(cotralXmlClient)
    val astralTrainRepository = AstralTrainRepository(AstralTrainClient())
    val vehicleTracker = VehicleTracker(vehicleRepository, appScope)
    val cotralViewportVehicles = CotralViewportVehicleService(transitsRepository, vehicleRepository, appScope)

    // Atac / Roma TPL
    val atacGtfsStore = AtacGtfsStore(context.filesDir)
    val atacRealtimeService = AtacRealtimeService(atacGtfsStore, appScope)

    // Shared
    val favoritesStore = FavoritesStore(context, appScope)
    val locationTracker = LocationTracker(context)

    // Presentation state (process-scoped, see MapViewModel's doc)
    val mapViewModel = MapViewModel(cotralGtfsStore, atacGtfsStore, polesRepository, cotralViewportVehicles, locationTracker, appScope)
    val sheets = SheetHostState()
    /** Location permission is requested automatically at most once per launch. */
    var hasRequestedLocationPermission = false

    private var loadingStarted = false

    /** Both loads start independently (separate failure domains) and only once per process. */
    fun startStaticDataLoading() {
        if (loadingStarted) return
        loadingStarted = true
        appScope.launch { cotralGtfsStore.ensureLoaded() }
        appScope.launch { atacGtfsStore.ensureLoaded() }
    }

    fun retryCotral() {
        appScope.launch { cotralGtfsStore.retry() }
    }

    fun retryAtac() {
        appScope.launch { atacGtfsStore.retry() }
    }

    /** Foreground-only live polling (battery/data friendly). */
    fun onForeground() {
        atacRealtimeService.startPolling()
        cotralViewportVehicles.startPolling()
        vehicleTracker.resume()
        if (locationTracker.hasPermission()) locationTracker.startUpdating()
    }

    fun onBackground() {
        atacRealtimeService.stopPolling()
        cotralViewportVehicles.stopPolling()
        vehicleTracker.pause()
        locationTracker.stopUpdating()
    }
}
