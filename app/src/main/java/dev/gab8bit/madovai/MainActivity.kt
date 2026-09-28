package dev.gab8bit.madovai

import android.Manifest
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.navigation
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dev.gab8bit.madovai.model.SearchRouteResult
import dev.gab8bit.madovai.ui.favorites.FavoritesScreen
import dev.gab8bit.madovai.ui.linee.LineeDetailScreen
import dev.gab8bit.madovai.ui.linee.LineeListScreen
import dev.gab8bit.madovai.ui.map.MapScreen
import dev.gab8bit.madovai.ui.sheets.SheetHost
import dev.gab8bit.madovai.ui.theme.MadovaiTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as MadovaiApp).container
        // Both static-data loads start right away, independently of the visible tab; the map
        // stays usable while they run (non-blocking loading banner).
        container.startStaticDataLoading()
        setContent {
            MadovaiTheme {
                MadovaiRoot(container)
            }
        }
    }
}

private enum class Tab(val route: String, val label: String, val selected: ImageVector, val unselected: ImageVector) {
    MAP("map", "Mappa", Icons.Filled.Map, Icons.Outlined.Map),
    LINEE("linee", "Linee", Icons.AutoMirrored.Filled.List, Icons.AutoMirrored.Outlined.List),
    PREFERITI("preferiti", "Preferiti", Icons.Filled.Star, Icons.Outlined.StarBorder),
}

@Composable
private fun MadovaiRoot(container: AppContainer) {
    val nav = rememberNavController()
    val backStack by nav.currentBackStackEntryAsState()
    val lifecycleOwner = LocalLifecycleOwner.current

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        container.locationTracker.onPermissionResult(result.values.any { it })
    }
    fun requestLocation() {
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }

    // Standard Android runtime permission flow: asked once per launch if not yet granted.
    LaunchedEffect(Unit) {
        if (!container.locationTracker.hasPermission() && !container.hasRequestedLocationPermission) {
            container.hasRequestedLocationPermission = true
            requestLocation()
        }
    }

    // Live polling (Atac GTFS-RT, Cotral viewport scan, followed vehicle, GPS) only while visible.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    container.locationTracker.refreshPermission()
                    container.onForeground()
                }
                Lifecycle.Event.ON_STOP -> container.onBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            NavigationBar {
                val destination = backStack?.destination
                Tab.entries.forEach { tab ->
                    val selected = destination?.hierarchy?.any { it.route == tab.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            nav.navigate(tab.route) {
                                popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(if (selected) tab.selected else tab.unselected, contentDescription = null) },
                        label = { Text(tab.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(nav, startDestination = Tab.MAP.route, modifier = Modifier.fillMaxSize().padding(padding)) {
            composable(Tab.MAP.route) {
                MapScreen(container, onRequestLocationPermission = ::requestLocation)
            }
            navigation(startDestination = "linee/list", route = Tab.LINEE.route) {
                composable("linee/list") {
                    LineeListScreen(container) { route ->
                        val (kind, id) = when (route) {
                            is SearchRouteResult.Atac -> "atac" to route.route.routeId
                            is SearchRouteResult.Cotral -> "cotral" to route.route.routeId
                        }
                        nav.navigate("linee/detail/$kind/${Uri.encode(id)}")
                    }
                }
                composable(
                    "linee/detail/{kind}/{id}",
                    arguments = listOf(navArgument("kind") { type = NavType.StringType }, navArgument("id") { type = NavType.StringType }),
                ) { entry ->
                    val kind = entry.arguments?.getString("kind") ?: "atac"
                    val id = Uri.decode(entry.arguments?.getString("id") ?: "")
                    LineeDetailScreen(container, kind, id, onBack = { nav.popBackStack() })
                }
            }
            composable(Tab.PREFERITI.route) {
                FavoritesScreen(container)
            }
        }
    }

    SheetHost(container)
}
