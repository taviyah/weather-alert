package com.weatheralert.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.weatheralert.model.LocationForecast
import com.weatheralert.model.Thresholds
import com.weatheralert.model.WatchedLocation
import com.weatheralert.model.WeatherAlert
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.LocalDate

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("weather_alert")

/**
 * Everything the app persists is a few KB of JSON in one DataStore file: a handful of watched
 * locations, one settings object, the last fetch per location, and the ledger of alerts already
 * shown. That is well under the point where a Room database and a KSP round-trip would pay off.
 */
class AppStore(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val keyLocations = stringPreferencesKey("locations")
    private val keySettings = stringPreferencesKey("settings")
    private val keyForecasts = stringPreferencesKey("forecasts")
    private val keyLedger = stringPreferencesKey("ledger")
    private val keyLastRun = stringPreferencesKey("last_run")

    // ---------------------------------------------------------------- locations

    val locations: Flow<List<WatchedLocation>> = context.dataStore.data.map { p ->
        decode(p[keyLocations], emptyList())
    }

    suspend fun locationsNow(): List<WatchedLocation> = locations.first()

    /** Adding a place already on the list is a no-op rather than a duplicate row. */
    suspend fun addLocation(loc: WatchedLocation) = context.dataStore.edit { p ->
        val current: List<WatchedLocation> = decode(p[keyLocations], emptyList())
        if (current.none { it.id == loc.id }) {
            p[keyLocations] = json.encodeToString(current + loc)
        }
    }

    /**
     * Rewrites the watched list into [orderedIds]. The stored order *is* the display order, so
     * there is no separate rank field to keep in step.
     */
    suspend fun reorderLocations(orderedIds: List<String>) = context.dataStore.edit { p ->
        val current: List<WatchedLocation> = decode(p[keyLocations], emptyList())
        val byId = current.associateBy { it.id }
        // Anything not named in the new order keeps its place at the end, so a list that changed
        // underneath the drag cannot silently drop a location.
        val reordered = orderedIds.mapNotNull { byId[it] } +
            current.filterNot { it.id in orderedIds.toSet() }
        if (reordered.size == current.size) {
            p[keyLocations] = json.encodeToString(reordered)
        }
    }

    suspend fun removeLocation(id: String) = context.dataStore.edit { p ->
        val current: List<WatchedLocation> = decode(p[keyLocations], emptyList())
        p[keyLocations] = json.encodeToString(current.filterNot { it.id == id })
        // Drop the cached forecast and this place's ledger entries too, so re-adding it later
        // starts clean instead of being silenced by alerts shown weeks ago.
        val forecasts: Map<String, LocationForecast> = decode(p[keyForecasts], emptyMap())
        p[keyForecasts] = json.encodeToString(forecasts - id)
        val ledger: Map<String, String> = decode(p[keyLedger], emptyMap())
        p[keyLedger] = json.encodeToString(ledger.filterKeys { !it.startsWith("$id|") })
    }

    // ---------------------------------------------------------------- settings

    val settings: Flow<Thresholds> = context.dataStore.data.map { p ->
        decode(p[keySettings], Thresholds())
    }

    suspend fun settingsNow(): Thresholds = settings.first()

    suspend fun saveSettings(t: Thresholds) = context.dataStore.edit { p ->
        p[keySettings] = json.encodeToString(t)
    }

    // ---------------------------------------------------------------- forecast cache

    val forecasts: Flow<Map<String, LocationForecast>> = context.dataStore.data.map { p ->
        decode(p[keyForecasts], emptyMap())
    }

    suspend fun forecastsNow(): Map<String, LocationForecast> = forecasts.first()

    suspend fun saveForecast(f: LocationForecast) = context.dataStore.edit { p ->
        val current: Map<String, LocationForecast> = decode(p[keyForecasts], emptyMap())
        p[keyForecasts] = json.encodeToString(current + (f.locationId to f))
    }

    // ---------------------------------------------------------------- alert ledger

    /**
     * Records which alerts have already been shown, so a storm four days out is announced once
     * and not again at every three-hourly check until it arrives. Values are the alert's date,
     * which is all [pruneLedger] needs to forget entries once they are in the past.
     */
    suspend fun filterUnseen(alerts: List<WeatherAlert>): List<WeatherAlert> {
        val seen: Map<String, String> = decode(context.dataStore.data.first()[keyLedger], emptyMap())
        return alerts.filter { it.key !in seen }
    }

    suspend fun recordSeen(alerts: List<WeatherAlert>) =
        recordSeenKeys(alerts.map { it.key to it.date })

    /**
     * Key-level access to the same ledger, so advice signals dedupe through it too rather than
     * needing a parallel record of their own. Keys must carry the location id as their first
     * segment; [removeLocation] prunes on that prefix.
     */
    suspend fun filterUnseenKeys(items: List<Pair<String, String>>): List<Pair<String, String>> {
        val seen: Map<String, String> = decode(context.dataStore.data.first()[keyLedger], emptyMap())
        return items.filter { it.first !in seen }
    }

    suspend fun recordSeenKeys(items: List<Pair<String, String>>) = context.dataStore.edit { p ->
        val seen: Map<String, String> = decode(p[keyLedger], emptyMap())
        p[keyLedger] = json.encodeToString(seen + items.toMap())
    }

    suspend fun pruneLedger(today: LocalDate) = context.dataStore.edit { p ->
        val seen: Map<String, String> = decode(p[keyLedger], emptyMap())
        val kept = seen.filterValues { date ->
            runCatching { !LocalDate.parse(date).isBefore(today) }.getOrDefault(false)
        }
        if (kept.size != seen.size) p[keyLedger] = json.encodeToString(kept)
    }

    /** Used by the "test alert" button in settings: forget everything so the next check re-fires. */
    suspend fun clearLedger() = context.dataStore.edit { p ->
        p[keyLedger] = json.encodeToString(emptyMap<String, String>())
    }

    // ---------------------------------------------------------------- last run

    val lastRun: Flow<LastRun> = context.dataStore.data.map { p ->
        decode(p[keyLastRun], LastRun())
    }

    suspend fun lastRunNow(): LastRun = lastRun.first()

    suspend fun saveLastRun(run: LastRun) = context.dataStore.edit { p ->
        p[keyLastRun] = json.encodeToString(run)
    }

    private inline fun <reified T> decode(raw: String?, fallback: T): T =
        if (raw.isNullOrBlank()) fallback
        // A settings object written by an older build can fail to parse after a field is added
        // or renamed. Falling back to defaults beats crashing on every launch.
        else runCatching { json.decodeFromString<T>(raw) }.getOrDefault(fallback)
}

@Serializable
data class LastRun(val epochMs: Long = 0L, val error: String? = null)
