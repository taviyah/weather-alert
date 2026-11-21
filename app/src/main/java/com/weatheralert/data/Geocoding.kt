package com.weatheralert.data

import android.util.Log
import com.weatheralert.model.WatchedLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Place search that works in Hebrew as well as English.
 *
 * Open-Meteo's geocoder is fast and unlimited but its `language` parameter covers only
 * en/de/fr/es/it/pt/ru/tr/hi — typing "חדרה" into it returns nothing at all. OpenStreetMap's
 * Nominatim does carry Hebrew names, so queries are routed by script: Latin goes to Open-Meteo,
 * Hebrew (or anything else non-Latin) goes to Nominatim.
 */
object Geocoding {

    private const val TAG = "WeatherAlert"
    private const val NOMINATIM = "https://nominatim.openstreetmap.org/search"

    private val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Hebrew, Arabic, Cyrillic and Greek blocks — anything Open-Meteo's geocoder cannot match. */
    fun needsNominatim(query: String): Boolean = query.any { ch ->
        ch in '֐'..'׿' || // Hebrew
            ch in '؀'..'ۿ' || // Arabic
            ch in 'Ѐ'..'ӿ' || // Cyrillic
            ch in 'Ͱ'..'Ͽ' // Greek
    }

    /**
     * Nominatim asks for at most one request a second, so a Hebrew query wants a longer debounce
     * than a Latin one. The caller reads this rather than hard-coding a single delay.
     */
    fun debounceMs(query: String): Long = if (needsNominatim(query)) 900L else 350L

    suspend fun search(query: String): List<WatchedLocation> =
        if (needsNominatim(query)) searchNominatim(query) else OpenMeteo.search(query)

    private suspend fun searchNominatim(query: String): List<WatchedLocation> =
        withContext(Dispatchers.IO) {
            val url = "$NOMINATIM?q=${URLEncoder.encode(query, "UTF-8")}" +
                "&format=jsonv2&addressdetails=1&accept-language=he&limit=10"
            // Nominatim's usage policy requires an identifying User-Agent and rejects requests
            // without one. The project page is the contact; no personal address is needed.
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", OpenMeteo.USER_AGENT)
                .build()

            val body = try {
                http.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        throw OpenMeteo.ApiException("HTTP ${resp.code}: ${text.take(200)}")
                    }
                    text
                }
            } catch (e: OpenMeteo.ApiException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "nominatim failed: ${e.javaClass.simpleName}: ${e.message}")
                throw OpenMeteo.ApiException("${e.javaClass.simpleName}: ${e.message}", e)
            }

            json.decodeFromString<List<NominatimResult>>(body)
                .mapNotNull { it.toWatchedLocation() }
                // Nominatim happily returns the same town twice from different OSM objects
                // (a node and a boundary, say); the rounded id collapses those.
                .distinctBy { it.id }
        }

    @Serializable
    private data class NominatimResult(
        val lat: String? = null,
        val lon: String? = null,
        val name: String? = null,
        @SerialName("display_name") val displayName: String? = null,
        @SerialName("addresstype") val addressType: String? = null,
        val address: NominatimAddress? = null,
    ) {
        fun toWatchedLocation(): WatchedLocation? {
            val la = lat?.toDoubleOrNull() ?: return null
            val lo = lon?.toDoubleOrNull() ?: return null
            val place = name?.takeIf { it.isNotBlank() }
                ?: address?.bestName()
                ?: displayName?.substringBefore(',')
                ?: return null
            return WatchedLocation(
                id = WatchedLocation.idFor(la, lo),
                name = place,
                admin = address?.regionName()?.takeIf { it != place },
                country = address?.country,
                latitude = la,
                longitude = lo,
            )
        }
    }

    @Serializable
    private data class NominatimAddress(
        val city: String? = null,
        val town: String? = null,
        val village: String? = null,
        val municipality: String? = null,
        val county: String? = null,
        val state: String? = null,
        val country: String? = null,
    ) {
        fun bestName(): String? = city ?: town ?: village ?: municipality
        fun regionName(): String? = state ?: county ?: municipality
    }
}
