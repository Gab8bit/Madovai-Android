package dev.gab8bit.madovai.ui.favorites

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.gab8bit.madovai.AppContainer
import dev.gab8bit.madovai.model.FavoriteStop
import dev.gab8bit.madovai.model.TransitVehicleKind
import dev.gab8bit.madovai.ui.SheetRoute
import dev.gab8bit.madovai.ui.common.EmptyState
import dev.gab8bit.madovai.ui.common.kindIcon
import dev.gab8bit.madovai.ui.map.atacStationGroup
import dev.gab8bit.madovai.ui.theme.TransitColors

/**
 * "Preferiti": one unified list of Cotral poles and Atac stops. Each opens the exact same
 * sheet as tapping it on the map, so there's one schedule view per stop kind.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FavoritesScreen(container: AppContainer) {
    val favorites by container.favoritesStore.favorites.collectAsState()

    Scaffold(contentWindowInsets = WindowInsets(0), topBar = { TopAppBar(title = { Text("Preferiti") }) }) { padding ->
        if (favorites.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState(Icons.Outlined.StarBorder, "Nessun preferito", "Tocca la stella su una fermata o palina per salvarla qui.")
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(favorites, key = { it.id }) { item ->
                    ElevatedCard(onClick = { open(container, item) }, modifier = Modifier.fillMaxWidth()) {
                        ListItem(
                            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                            leadingContent = {
                                when (item) {
                                    is FavoriteStop.Cotral -> Icon(
                                        kindIcon(if (item.pole.isTreno) TransitVehicleKind.TRENO else TransitVehicleKind.BUS),
                                        null,
                                        tint = if (item.pole.isTreno) TransitColors.CotralPoleRail else TransitColors.CotralPoleBus,
                                    )
                                    is FavoriteStop.Atac -> Icon(Icons.Filled.Place, null, tint = TransitColors.AtacStop)
                                }
                            },
                            headlineContent = { Text(item.displayName) },
                            supportingContent = item.subtitle?.let { s -> { Text(s, color = MaterialTheme.colorScheme.onSurfaceVariant) } },
                            trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                        )
                    }
                }
            }
        }
    }
}

private fun open(container: AppContainer, item: FavoriteStop) {
    when (item) {
        is FavoriteStop.Cotral -> container.sheets.push(SheetRoute.CotralPole(item.pole))
        // Resolves back to every platform sharing this stop's name, like a map-pin tap.
        is FavoriteStop.Atac -> container.sheets.push(SheetRoute.AtacStation(container.atacStationGroup(item.stop), null))
    }
}
