package dev.gab8bit.madovai.ui.sheets

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.LocationOff
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import dev.gab8bit.madovai.AppContainer
import dev.gab8bit.madovai.Config
import dev.gab8bit.madovai.data.gtfs.GtfsTextUtils
import dev.gab8bit.madovai.model.AstralDeparture
import dev.gab8bit.madovai.model.AstralScheduleDirection
import dev.gab8bit.madovai.model.CotralTrainRoute
import dev.gab8bit.madovai.model.NetworkState
import dev.gab8bit.madovai.model.Pole
import dev.gab8bit.madovai.model.Transit
import dev.gab8bit.madovai.model.TransitTrackingStatus
import dev.gab8bit.madovai.model.VehicleTrackingState
import dev.gab8bit.madovai.ui.common.DelayFormat
import dev.gab8bit.madovai.ui.common.EmptyState
import dev.gab8bit.madovai.ui.common.LoadingState
import dev.gab8bit.madovai.ui.common.Pill
import dev.gab8bit.madovai.ui.common.StatusDot
import dev.gab8bit.madovai.ui.common.relativeMinutesLabel
import dev.gab8bit.madovai.ui.theme.TransitColors
import kotlinx.coroutines.delay
import kotlin.math.abs

/** A clock that ticks every 20s so "tra N min" labels and "upcoming" filters stay fresh between polls. */
@Composable
fun rememberMinuteTicker(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(20_000L)
            value = System.currentTimeMillis()
        }
    }
    return now
}

@Composable
fun SheetHeader(title: String, subtitle: String?, isFavorite: Boolean?, onToggleFavorite: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (!subtitle.isNullOrEmpty()) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (isFavorite != null) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Filled.Star else Icons.Filled.StarBorder,
                    contentDescription = if (isFavorite) "Rimuovi dai preferiti" else "Aggiungi ai preferiti",
                    tint = TransitColors.Favorite,
                    modifier = Modifier.size(28.dp),
                )
            }
        }
    }
}

/** Direction selector: M3 filter chips, horizontally scrollable (a station can have many platforms). */
@Composable
fun <T> DirectionChips(options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        options.forEach { option ->
            FilterChip(selected = option == selected, onClick = { onSelect(option) }, label = { Text(label(option), maxLines = 1) })
        }
    }
}

@Composable
fun PoleSheet(container: AppContainer, initialPole: Pole) {
    val detail = remember(initialPole.id) {
        PoleDetailState(initialPole, container.vehicleTracker, container.transitsRepository, container.cotralGtfsStore, container.astralTrainRepository)
    }
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(detail) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) { detail.runPolling() }
    }

    val pole by detail.pole.collectAsState()
    val favoriteIds by container.favoritesStore.favoriteIds.collectAsState()
    val trackedCode by container.vehicleTracker.vehicleCode.collectAsState()

    Column(Modifier.fillMaxWidth()) {
        val code = pole.codicePalina
        SheetHeader(
            title = pole.displayName,
            subtitle = pole.subtitle,
            isFavorite = code?.let { "cotral:${pole.id}" in favoriteIds },
            onToggleFavorite = { container.favoritesStore.toggle(pole) },
        )
        trackedCode?.let { TrackingBanner(container, it) }
        HorizontalDivider()
        if (pole.isTreno) RailContent(detail) else BusContent(container, detail)
    }
}

@Composable
private fun TrackingBanner(container: AppContainer, code: String) {
    val state by container.vehicleTracker.state.collectAsState()
    val isTreno by container.vehicleTracker.isTreno.collectAsState()
    val lost = state is VehicleTrackingState.Lost
    Surface(color = if (lost) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.tertiaryContainer) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (lost) Icons.Outlined.LocationOff else Icons.Filled.MyLocation,
                null,
                tint = if (lost) MaterialTheme.colorScheme.error else TransitColors.CotralOrange,
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                if (lost) {
                    Text("Tracking interrotto", style = MaterialTheme.typography.labelLarge)
                    Text("Il veicolo $code non trasmette più la posizione", style = MaterialTheme.typography.labelSmall)
                } else {
                    Text("Seguendo ${if (isTreno) "treno" else "bus"} $code", style = MaterialTheme.typography.labelLarge)
                    Text("Posizione aggiornata ogni ${Config.VEHICLE_POSITION_POLL_INTERVAL_MS / 1000}s", style = MaterialTheme.typography.labelSmall)
                }
            }
            OutlinedButton(onClick = { container.vehicleTracker.stopFollowing() }) { Text("Ferma") }
        }
    }
}

@Composable
private fun BusContent(container: AppContainer, detail: PoleDetailState) {
    val state by detail.state.collectAsState()
    val transits by detail.transits.collectAsState()
    val pole by detail.pole.collectAsState()
    val followedCode by container.vehicleTracker.vehicleCode.collectAsState()
    rememberMinuteTicker()

    when {
        (state == NetworkState.Loading || state == NetworkState.Idle) && transits.isEmpty() -> LoadingState("Carico i transiti…")
        state == NetworkState.Empty -> EmptyState(Icons.Outlined.Schedule, "Nessun transito disponibile", "Riprova più tardi o controlla un'altra palina.")
        state is NetworkState.Error -> EmptyState(Icons.Outlined.CloudOff, "Errore", (state as NetworkState.Error).message)
        else -> LazyColumn(Modifier.fillMaxWidth(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            items(transits) { transit ->
                TransitRow(
                    transit = transit,
                    isFollowing = followedCode != null && followedCode == transit.automezzo.codice,
                    onToggleFollow = {
                        transit.automezzo.codice?.let { container.vehicleTracker.toggleFollowing(it, pole.isTreno) }
                    },
                )
            }
        }
    }
}

@Composable
private fun TransitRow(transit: Transit, isFollowing: Boolean, onToggleFollow: () -> Unit) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.width(72.dp)) {
            Text(
                if (transit.displayTime.isEmpty()) "--:--" else transit.adjustedDisplayTime,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            )
            relativeMinutesLabel(transit.minutesFromNow, "in transito")?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = neutral)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(destinationLabel(transit), style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (transit.trackingStatus) {
                    TransitTrackingStatus.REALTIME -> {
                        StatusDot(TransitColors.Live, pulsing = true)
                        Spacer(Modifier.width(6.dp))
                        val reliable = transit.isDelayReliable && abs(transit.ritardoSeconds) >= 60
                        Text(
                            "Real-time · " + if (reliable) DelayFormat.secondsText(transit.ritardoSeconds) else "puntuale",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (reliable) DelayFormat.secondsColor(transit.ritardoSeconds) else neutral,
                        )
                    }
                    TransitTrackingStatus.MONITORED_OFFLINE -> {
                        StatusDot(TransitColors.TrackedOffline)
                        Spacer(Modifier.width(6.dp))
                        Text("Tracciata, bus non in trasmissione", style = MaterialTheme.typography.labelMedium, color = neutral)
                    }
                    TransitTrackingStatus.SCHEDULED -> {
                        StatusDot(TransitColors.Scheduled)
                        Spacer(Modifier.width(6.dp))
                        Text("Schedulata, no real-time", style = MaterialTheme.typography.labelMedium, color = neutral)
                    }
                }
            }
            if (transit.instradamento.isNotEmpty()) {
                Text(transit.instradamento, style = MaterialTheme.typography.labelSmall, color = neutral, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (transit.canTrackVehicle) {
            Spacer(Modifier.width(8.dp))
            if (isFollowing) {
                FilledTonalButton(
                    onClick = onToggleFollow,
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = TransitColors.CotralOrange, contentColor = androidx.compose.ui.graphics.Color.White),
                    contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
                ) {
                    Icon(Icons.Filled.MyLocation, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Seguito")
                }
            } else {
                OutlinedButton(onClick = onToggleFollow, contentPadding = ButtonDefaults.ButtonWithIconContentPadding) {
                    Icon(Icons.Filled.NearMe, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Segui")
                }
            }
        }
    }
}

private fun destinationLabel(transit: Transit): String {
    // Cotral appends an internal code to a destination ("Cristoforo Colombo (ff90006)").
    val destination = if (transit.arrivoCorsa.isEmpty()) "Destinazione N/D" else GtfsTextUtils.extractLocalityFromStopName(transit.arrivoCorsa)
    // A train's `percorso` is an internal ASTRAL route code — dropped rather than shown raw.
    if (CotralTrainRoute.fromRaw(transit.percorso) != null) return destination
    return if (transit.percorso.isEmpty()) destination else "${transit.percorso} · $destination"
}

@Composable
private fun RailContent(detail: PoleDetailState) {
    val state by detail.state.collectAsState()
    val raw by detail.railDirections.collectAsState()
    val now = rememberMinuteTicker()
    val directions = remember(raw, now) { PoleDetailState.upcomingDirections(raw) }

    when {
        directions.isNotEmpty() -> RailSchedule(directions)
        state is NetworkState.Error -> EmptyState(Icons.Outlined.CloudOff, "Errore", (state as NetworkState.Error).message)
        state == NetworkState.Loading || state == NetworkState.Idle -> LoadingState("Carico gli orari…")
        else -> EmptyState(
            Icons.Outlined.Schedule,
            "Nessun transito disponibile",
            "Al momento non risultano corse per questa stazione, in tempo reale né programmate, per il resto di oggi.",
        )
    }
}

@Composable
private fun RailSchedule(directions: List<AstralScheduleDirection>) {
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val current = directions.firstOrNull { it.destination == selected } ?: directions[0]
    // No entry with a known delay → this direction came from the static-GTFS tier.
    val isEstimate = current.entries.none { it.delayMinutes != null }

    Column {
        if (isEstimate) {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainerHigh).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.CalendarMonth, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.width(8.dp))
                Text("Orario stimato da tabella, non in tempo reale", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (directions.size > 1) {
            DirectionChips(directions.map { it.destination }, current.destination, { "Verso $it" }) { selected = it }
        } else {
            Text(
                "Verso ${current.destination}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
        if (current.entries.isEmpty()) {
            EmptyState(Icons.Outlined.Bedtime, "Corse terminate per oggi", "Non risultano altre corse programmate verso ${current.destination} per il resto di oggi.")
        } else {
            val nextId = current.entries.first().id
            LazyColumn(contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
                items(current.entries) { dep -> AstralDepartureRow(dep, dep.id == nextId) }
            }
        }
    }
}

@Composable
fun AstralDepartureRow(departure: AstralDeparture, isNext: Boolean) {
    val neutral = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier.fillMaxWidth().alpha(if (departure.isCancelled) 0.6f else 1f).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(72.dp)) {
            Text(departure.time, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            relativeMinutesLabel(Transit.minutesFromNow(departure.time), "in arrivo")?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = neutral)
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val delay = departure.delayMinutes
            if (!departure.isCancelled) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (delay != null) {
                        // Green pulsing dot = ASTRAL reported a real delay figure (even 0).
                        StatusDot(TransitColors.Live, pulsing = true)
                        Spacer(Modifier.width(6.dp))
                        Text(DelayFormat.minutesText(delay, capitalized = true), style = MaterialTheme.typography.titleSmall, color = DelayFormat.minutesColor(delay, neutral))
                    } else {
                        StatusDot(TransitColors.Scheduled)
                        Spacer(Modifier.width(6.dp))
                        Text("Schedulata", style = MaterialTheme.typography.titleSmall, color = neutral)
                    }
                }
            }
            if (departure.isReplacementBus) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.DirectionsBus, null, Modifier.size(14.dp), tint = neutral)
                    Spacer(Modifier.width(4.dp))
                    Text("Bus sostitutivo", style = MaterialTheme.typography.labelSmall, color = neutral)
                }
            }
        }
        when {
            departure.isCancelled -> Pill("Soppressa", MaterialTheme.colorScheme.error, MaterialTheme.colorScheme.onError)
            isNext -> Pill("Prossimo treno", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary)
        }
    }
}
