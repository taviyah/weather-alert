package com.weatheralert.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.weatheralert.WeatherAlertApp
import com.weatheralert.data.BackgroundHealth
import com.weatheralert.data.DeviceLocation
import com.weatheralert.data.ForecastReuse
import com.weatheralert.data.Geocoding
import com.weatheralert.data.LastRun
import com.weatheralert.data.OpenMeteo
import com.weatheralert.domain.AdviceEngine
import com.weatheralert.domain.AlertEngine
import com.weatheralert.model.DayAdvice
import com.weatheralert.model.LocationForecast
import com.weatheralert.model.Thresholds
import com.weatheralert.model.WatchedLocation
import com.weatheralert.model.WeatherAlert
import com.weatheralert.widget.WeatherWidgetProvider
import com.weatheralert.work.KeepAliveService
import com.weatheralert.work.Scheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class LocationCard(
    val location: WatchedLocation,
    val forecast: LocationForecast?,
    val alerts: List<WeatherAlert>,
    val advice: List<DayAdvice>,
) {
    fun adviceFor(date: java.time.LocalDate): List<DayAdvice> =
        advice.filter { it.date == date.toString() }
}

data class HomeState(
    val cards: List<LocationCard> = emptyList(),
    val settings: Thresholds = Thresholds(),
    val lastRun: LastRun = LastRun(),
    val loaded: Boolean = false,
    val batteryExempt: Boolean = true,
    val stalled: Boolean = false,
    val hoursSinceLastRun: Long = 0,
) {
    /** Only worth nagging about once there is something to miss. */
    val showBatteryWarning: Boolean get() = !batteryExempt && cards.isNotEmpty()
}

data class HereState(
    val locating: Boolean = false,
    val error: String? = null,
    val card: LocationCard? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val store = (app as WeatherAlertApp).store

    /**
     * Battery-exemption state lives in the OS, not in a Flow, so nothing tells us when the user
     * comes back from the settings screen having granted it. Bumping this re-runs the combine.
     */
    private val healthTick = MutableStateFlow(0)

    fun recheckHealth() = healthTick.update { it + 1 }

    /**
     * Alerts are recomputed here from the cached forecast rather than being read back from the
     * notification ledger, so the list on screen always reflects the *current* thresholds. Move
     * a slider in settings and the home screen updates immediately, with no refetch.
     */
    val state: StateFlow<HomeState> =
        combine(
            store.locations,
            store.forecasts,
            store.settings,
            store.lastRun,
            healthTick,
        ) { locations, forecasts, settings, lastRun, _ ->
            val today = LocalDate.now()
            val app = getApplication<Application>()
            HomeState(
                cards = locations.map { loc ->
                    val fc = forecasts[loc.id]
                    val alerts = fc?.let { AlertEngine.evaluate(loc, it, settings, today) }.orEmpty()
                    LocationCard(
                        location = loc,
                        forecast = fc,
                        alerts = alerts,
                        advice = fc?.let { AdviceEngine.evaluate(it, settings, today, alerts) }.orEmpty(),
                    )
                },
                settings = settings,
                lastRun = lastRun,
                loaded = true,
                batteryExempt = BackgroundHealth.isExemptFromBatteryOptimisation(app),
                stalled = BackgroundHealth.isStalled(lastRun.epochMs, settings.checkEveryHours),
                hoursSinceLastRun = BackgroundHealth.hoursSince(lastRun.epochMs),
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeState())

    private val _refreshing = MutableStateFlow(false)
    val refreshing: StateFlow<Boolean> = _refreshing.asStateFlow()

    private val _searchResults = MutableStateFlow<List<WatchedLocation>>(emptyList())
    val searchResults: StateFlow<List<WatchedLocation>> = _searchResults.asStateFlow()

    private val _searching = MutableStateFlow(false)
    val searching: StateFlow<Boolean> = _searching.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    /**
     * Fetches every place for the screen, then starts the background check, which reuses these
     * fresh forecasts instead of downloading them again (see [ForecastReuse]).
     *
     * [force] is for pull-to-refresh: the user asked, so every place is downloaded. Otherwise a
     * place fetched in the last few minutes is left as it is, so reopening the app right after
     * closing it costs nothing.
     */
    fun refresh(force: Boolean = false) {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                val settings = store.settingsNow()
                var firstError: String? = null
                var failures = 0
                var attempted = 0
                val locations = store.locationsNow()
                val cached = if (force) emptyMap() else store.forecastsNow()
                val today = LocalDate.now()
                for (loc in locations) {
                    val reuse = ForecastReuse.canReuse(
                        cached[loc.id], settings.horizonDays, today, System.currentTimeMillis(),
                    )
                    if (reuse) continue
                    attempted++
                    runCatching { OpenMeteo.fetchForecast(loc, settings.horizonDays) }
                        .onSuccess { store.saveForecast(it) }
                        .onFailure {
                            failures++
                            if (firstError == null) firstError = it.message
                            android.util.Log.w(OpenMeteo.TAG, "refresh failed for ${loc.name}", it)
                        }
                }
                // Recording a clean run after a total failure made a broken app look healthy.
                // Nothing fetched (every place reused) means nothing new to record.
                if (attempted > 0) {
                    store.saveLastRun(
                        LastRun(
                            epochMs = System.currentTimeMillis(),
                            error = if (failures > 0) {
                                "$failures of $attempted failed" +
                                    (firstError?.let { ": $it" } ?: "")
                            } else {
                                null
                            },
                        ),
                    )
                }
            } finally {
                _refreshing.value = false
            }
            WeatherWidgetProvider.refresh(getApplication())
            // The foreground refresh above only updates what is on screen. The worker is what
            // decides whether any of it is worth a notification, so it still has to run.
            Scheduler.runNow(getApplication())
        }
    }

    fun search(query: String) {
        viewModelScope.launch {
            _searching.value = true
            _searchError.value = null
            runCatching { Geocoding.search(query) }
                .onSuccess { _searchResults.value = it }
                .onFailure {
                    _searchResults.value = emptyList()
                    _searchError.value = it.message ?: "Search failed"
                }
            _searching.value = false
        }
    }

    fun clearSearch() {
        _searchResults.value = emptyList()
        _searchError.value = null
    }

    fun addLocation(loc: WatchedLocation) {
        viewModelScope.launch {
            store.addLocation(loc)
            // Fetch immediately so the new card is not an empty placeholder until the next
            // scheduled check, which could be hours away.
            runCatching { OpenMeteo.fetchForecast(loc, store.settingsNow().horizonDays) }
                .onSuccess { store.saveForecast(it) }
                .onFailure {
                    android.util.Log.w(OpenMeteo.TAG, "first fetch failed for ${loc.name}", it)
                    store.saveLastRun(
                        LastRun(System.currentTimeMillis(), "Could not load ${loc.name}: ${it.message}"),
                    )
                }
            Scheduler.runNow(getApplication())
        }
    }

    fun reorderLocations(orderedIds: List<String>) {
        viewModelScope.launch {
            store.reorderLocations(orderedIds)
            // The widget speaks for whichever place is first, so a drag can change it entirely.
            WeatherWidgetProvider.refresh(getApplication())
        }
    }

    fun removeLocation(id: String) {
        viewModelScope.launch {
            store.removeLocation(id)
            WeatherWidgetProvider.refresh(getApplication())
        }
    }

    fun saveSettings(t: Thresholds) {
        viewModelScope.launch {
            val previous = store.settingsNow()
            store.saveSettings(t)
            if (previous.checkEveryHours != t.checkEveryHours) {
                Scheduler.schedulePeriodic(getApplication(), t.checkEveryHours)
            }
            if (previous.keepAlive != t.keepAlive) {
                KeepAliveService.sync(getApplication(), t.keepAlive)
            }
            // A widened horizon needs more days than the cache holds, so refetch rather than
            // showing a short forecast until the next scheduled run.
            if (previous.horizonDays != t.horizonDays) refresh()
            WeatherWidgetProvider.refresh(getApplication())
        }
    }

    private val _locating = MutableStateFlow(false)
    val locating: StateFlow<Boolean> = _locating.asStateFlow()

    private val _locateError = MutableStateFlow<String?>(null)
    val locateError: StateFlow<String?> = _locateError.asStateFlow()

    /** "Use my location": one coarse fix, reverse-geocoded to a name, added like any other place. */
    fun addCurrentLocation(onAdded: () -> Unit) {
        if (_locating.value) return
        viewModelScope.launch {
            _locating.value = true
            _locateError.value = null
            runCatching { DeviceLocation.current(getApplication()) }
                .onSuccess { loc ->
                    addLocation(loc)
                    onAdded()
                }
                .onFailure { _locateError.value = it.message ?: "Could not get your location" }
            _locating.value = false
        }
    }

    fun clearLocateError() { _locateError.value = null }

    // ------------------------------------------------------------- "where I am"

    /** The device's position and its forecast, kept apart from the watched places. */
    private data class Here(val location: WatchedLocation, val forecast: LocationForecast)

    private val _here = MutableStateFlow<Here?>(null)
    private val _hereLocating = MutableStateFlow(false)
    private val _hereError = MutableStateFlow<String?>(null)

    /**
     * Not saved, not watched and never notified about: it is a look, not a subscription. Alerts
     * and advice are derived from the current settings, like the watched cards, so a slider
     * moved in settings applies here too.
     */
    val here: StateFlow<HereState> =
        combine(_here, _hereLocating, _hereError, store.settings) { here, locating, error, settings ->
            val today = LocalDate.now()
            HereState(
                locating = locating,
                error = error,
                card = here?.let { h ->
                    val alerts = AlertEngine.evaluate(h.location, h.forecast, settings, today)
                    LocationCard(
                        location = h.location,
                        forecast = h.forecast,
                        alerts = alerts,
                        advice = AdviceEngine.evaluate(h.forecast, settings, today, alerts),
                    )
                },
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HereState())

    fun showHere() {
        if (_hereLocating.value) return
        viewModelScope.launch {
            _hereLocating.value = true
            _hereError.value = null
            // Dropped rather than shown while relocating: after a drive it would be another town.
            _here.value = null
            runCatching {
                // Ten minutes: walking pace never leaves an 11 km forecast cell in that time.
                val loc = DeviceLocation.current(getApplication(), maxCachedAgeMs = 10 * 60_000L)
                Here(loc, OpenMeteo.fetchForecast(loc, store.settingsNow().horizonDays))
            }
                .onSuccess { _here.value = it }
                .onFailure { _hereError.value = it.message ?: "Could not get the forecast here" }
            _hereLocating.value = false
        }
    }

    fun hereDenied() {
        _hereError.value = "Location access is needed to show the forecast where you are."
    }

    /** "Watch this place": the look becomes a subscription, with the forecast already in hand. */
    fun watchHere() {
        val h = _here.value ?: return
        viewModelScope.launch {
            store.addLocation(h.location)
            store.saveForecast(h.forecast)
            Scheduler.runNow(getApplication())
            WeatherWidgetProvider.refresh(getApplication())
        }
    }

    fun requestBatteryExemption() {
        BackgroundHealth.requestExemption(getApplication())
    }

    fun resendAlerts() {
        viewModelScope.launch {
            store.clearLedger()
            Scheduler.runNow(getApplication())
        }
    }

    // Kept last so every field above is initialised before it runs. The ViewModel outlives a
    // screen rotation, so this runs once per visit to the app; when it sat in the Compose tree
    // it ran again on every rotation and every dark-mode switch.
    init {
        refresh()
    }
}
