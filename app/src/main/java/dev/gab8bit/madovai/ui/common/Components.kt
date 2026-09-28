package dev.gab8bit.madovai.ui.common

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBus
import androidx.compose.material.icons.filled.Subway
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Tram
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.gab8bit.madovai.model.GtfsLoadingState
import dev.gab8bit.madovai.model.TransitVehicleKind
import dev.gab8bit.madovai.ui.theme.TransitColors
import kotlin.math.abs

fun kindIcon(kind: TransitVehicleKind): ImageVector = when (kind) {
    TransitVehicleKind.BUS -> Icons.Filled.DirectionsBus
    TransitVehicleKind.TRAM -> Icons.Filled.Tram
    TransitVehicleKind.METRO -> Icons.Filled.Subway
    TransitVehicleKind.TRENO -> Icons.Filled.Train
}

/** Delay presentation: under a minute reads as on time, never as a delay. */
object DelayFormat {
    fun secondsText(seconds: Int): String {
        val minutes = abs(seconds) / 60
        return if (seconds > 0) "in ritardo di $minutes min" else "in anticipo di $minutes min"
    }

    fun secondsColor(seconds: Int): Color = if (seconds > 0) TransitColors.Late else TransitColors.Early

    fun minutesText(minutes: Int, capitalized: Boolean = false): String {
        val s = when {
            abs(minutes) < 1 -> "puntuale"
            minutes > 0 -> "in ritardo di $minutes min"
            else -> "in anticipo di ${abs(minutes)} min"
        }
        return if (capitalized) s.replaceFirstChar { it.uppercase() } else s
    }

    fun minutesColor(minutes: Int, neutral: Color): Color = when {
        abs(minutes) < 1 -> neutral
        minutes > 0 -> TransitColors.Late
        else -> TransitColors.Early
    }
}

@Composable
fun StatusDot(color: Color, pulsing: Boolean = false, size: Int = 8) {
    if (pulsing) {
        val t = rememberInfiniteTransition(label = "pulse")
        val s by t.animateFloat(1f, 1.5f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "scale")
        val a by t.animateFloat(1f, 0.5f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "alpha")
        Box(Modifier.size(size.dp).scale(s).alpha(a).background(color, CircleShape))
    } else {
        Box(Modifier.size(size.dp).background(color, CircleShape))
    }
}

@Composable
fun EmptyState(icon: ImageVector, title: String, message: String? = null, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, null, Modifier.size(40.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (message != null) {
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun LoadingState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator()
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** One row per GTFS store that isn't ready yet; the map stays usable meanwhile. */
data class LoadingItem(val id: String, val label: String, val state: GtfsLoadingState, val progress: Double?, val retry: () -> Unit)

@Composable
fun DataLoadingBanner(items: List<LoadingItem>, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.forEach { item -> LoadingRow(item) }
            // Phase-agnostic wording on purpose: parsing runs on every launch (cache reused),
            // so saying "download" would make ordinary relaunches look like a fresh download.
            Text(
                "Percorsi, linee e altri dati potrebbero non essere ancora disponibili finché il caricamento non è completo.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun LoadingRow(item: LoadingItem) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(item.label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            item.progress?.let {
                Text("${(it * 100).toInt()}%", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        val state = item.state
        if (state is GtfsLoadingState.Failed) {
            Text(state.message, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            FilledTonalButton(onClick = item.retry) { Text("Riprova") }
        } else {
            val p = item.progress
            if (p != null) {
                LinearProgressIndicator(progress = { p.toFloat() }, modifier = Modifier.fillMaxWidth())
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            Text(phaseLabel(state), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun phaseLabel(state: GtfsLoadingState): String = when (state) {
    GtfsLoadingState.Idle -> "In attesa…"
    GtfsLoadingState.Downloading -> "Scaricamento…"
    GtfsLoadingState.Extracting -> "Estrazione…"
    GtfsLoadingState.Parsing -> "Elaborazione…"
    else -> ""
}

@Composable
fun ErrorBanner(message: String, onRetry: (() -> Unit)?, onDismiss: (() -> Unit)?, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.CloudOff, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Errore di connessione", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onErrorContainer)
                Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            if (onDismiss != null) TextButton(onClick = onDismiss) { Text("Chiudi") }
            if (onRetry != null) TextButton(onClick = onRetry) { Text("Riprova") }
        }
    }
}

/** A small filled pill label ("Soppressa", "Prossimo treno", …). */
@Composable
fun Pill(text: String, container: Color, content: Color = Color.White) {
    Box(
        Modifier.background(container, CircleShape).padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, color = content)
    }
}

fun relativeMinutesLabel(minutes: Int?, zeroLabel: String): String? = when {
    minutes == null -> null
    minutes <= 0 -> zeroLabel
    minutes == 1 -> "tra 1 min"
    else -> "tra $minutes min"
}
