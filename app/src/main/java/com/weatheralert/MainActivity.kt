package com.weatheralert

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.weatheralert.data.DeviceLocation
import com.weatheralert.ui.AddLocationScreen
import com.weatheralert.ui.HomeScreen
import com.weatheralert.ui.LocationDetailScreen
import com.weatheralert.ui.MainViewModel
import com.weatheralert.ui.SettingsScreen
import com.weatheralert.ui.WeatherAlertTheme

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Set by the compose tree so the permission result can resume what the user started. */
    private var onLocationGranted: (() -> Unit)? = null
    private var onLocationDenied: (() -> Unit)? = null

    private val requestLocation =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) onLocationGranted?.invoke() else onLocationDenied?.invoke()
        }

    private fun withLocation(denied: (() -> Unit)?, go: () -> Unit) {
        if (DeviceLocation.hasPermission(this)) {
            go()
        } else {
            onLocationGranted = go
            onLocationDenied = denied
            requestLocation.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Without this the app runs, checks the forecast and posts alerts into a void. Asked on
        // first launch rather than behind a rationale screen, since the whole point of the app is
        // notifications and refusing it makes everything else pointless.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            val vm: MainViewModel = viewModel()
            val state by vm.state.collectAsStateWithLifecycle()
            WeatherAlertTheme(eInk = state.settings.eInkMode) {
                val refreshing by vm.refreshing.collectAsStateWithLifecycle()
                val results by vm.searchResults.collectAsStateWithLifecycle()
                val searching by vm.searching.collectAsStateWithLifecycle()
                val searchError by vm.searchError.collectAsStateWithLifecycle()
                val locating by vm.locating.collectAsStateWithLifecycle()
                val locateError by vm.locateError.collectAsStateWithLifecycle()
                val nav = rememberNavController()

                LaunchedEffect(Unit) { vm.refresh() }

                // The battery exemption is granted in a system screen, so the only way to notice
                // it happened is to re-check when the user comes back.
                val owner = LocalLifecycleOwner.current
                DisposableEffect(owner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) vm.recheckHealth()
                    }
                    owner.lifecycle.addObserver(observer)
                    onDispose { owner.lifecycle.removeObserver(observer) }
                }

                NavHost(navController = nav, startDestination = "home") {
                    composable("home") {
                        HomeScreen(
                            state = state,
                            refreshing = refreshing,
                            onAdd = { vm.clearSearch(); nav.navigate("add") },
                            onSettings = { nav.navigate("settings") },
                            onRefresh = vm::refresh,
                            onRemove = vm::removeLocation,
                            onFixBattery = vm::requestBatteryExemption,
                            onReorder = vm::reorderLocations,
                            onOpen = { nav.navigate("detail/$it") },
                            onHere = {
                                // Navigate first so the screen can say what is happening while
                                // the permission prompt, the fix and the fetch each take a moment.
                                nav.navigate("here")
                                withLocation(denied = vm::hereDenied, go = vm::showHere)
                            },
                        )
                    }
                    composable("here") {
                        val here by vm.here.collectAsStateWithLifecycle()
                        val card = here.card
                        LocationDetailScreen(
                            card = card,
                            settings = state.settings,
                            onBack = { nav.popBackStack() },
                            emptyMessage = here.error
                                ?: if (here.locating) "Finding where you are…" else "Waiting for the forecast…",
                            isHere = true,
                            onWatch = vm::watchHere.takeIf {
                                card != null && state.cards.none { it.location.id == card.location.id }
                            },
                        )
                    }
                    composable("detail/{id}") { entry ->
                        val id = entry.arguments?.getString("id")
                        LocationDetailScreen(
                            card = state.cards.firstOrNull { it.location.id == id },
                            settings = state.settings,
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("add") {
                        AddLocationScreen(
                            results = results,
                            searching = searching,
                            error = searchError,
                            alreadyWatched = state.cards.map { it.location.id }.toSet(),
                            locating = locating,
                            locateError = locateError,
                            onSearch = vm::search,
                            onPick = { vm.addLocation(it); nav.popBackStack() },
                            onUseMyLocation = {
                                vm.clearLocateError()
                                withLocation(denied = null) { vm.addCurrentLocation { nav.popBackStack() } }
                            },
                            onBack = { nav.popBackStack() },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            settings = state.settings,
                            onChange = vm::saveSettings,
                            onResend = vm::resendAlerts,
                            onBack = { nav.popBackStack() },
                        )
                    }
                }
            }
        }
    }
}
