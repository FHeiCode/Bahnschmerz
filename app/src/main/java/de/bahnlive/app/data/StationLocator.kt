package de.bahnlive.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Liefert Koordinaten für Bahnhöfe:
 * 1. mitgelieferte Liste großer Bahnhöfe,
 * 2. dauerhafter Cache auf dem Gerät,
 * 3. Suche bei OpenStreetMap Nominatim (höchstens 1 Anfrage pro Sekunde, laut Nutzungsregeln).
 */
class StationLocator(context: Context) {
    private val prefs = context.getSharedPreferences("station_coordinates", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private var lastRequest = 0L

    /** Sofort verfügbare Koordinaten (ohne Netz), sonst null. */
    fun cached(name: String): LatLon? {
        prefs.getString(key(name), null)?.let { decode(it) }?.let { return it }
        return Stations.coordinatesOf(name)
    }

    /** True, wenn der Bahnhof schon gesucht und nicht gefunden wurde. */
    fun knownMissing(name: String): Boolean = prefs.getString(key(name), null) == MISSING

    /** Speichert genaue Koordinaten (z. B. aus dem DB-Fahrtverlauf) – sie haben Vorrang. */
    fun store(name: String, position: LatLon) {
        prefs.edit().putString(key(name), "${position.lat},${position.lon}").apply()
    }

    suspend fun locate(name: String): LatLon? {
        cached(name)?.let { return it }
        if (knownMissing(name)) return null
        return mutex.withLock {
            cached(name)?.let { return@withLock it }
            val wait = 1100 - (System.currentTimeMillis() - lastRequest)
            if (wait > 0) delay(wait)
            lastRequest = System.currentTimeMillis()
            val found = runCatching { search(name) }.getOrElse { return@withLock null } // Netzfehler: später erneut
            prefs.edit().putString(key(name), found?.let { "${it.lat},${it.lon}" } ?: MISSING).apply()
            found
        }
    }

    private suspend fun search(name: String): LatLon? = withContext(Dispatchers.IO) {
        val url = "https://nominatim.openstreetmap.org/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", searchText(name))
            .addQueryParameter("format", "jsonv2")
            .addQueryParameter("limit", "8")
            .addQueryParameter("countrycodes", "de,at,ch,nl,be,lu,fr,dk,pl,cz,it")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Bahnschmerz-Android/1.0")
            .header("Accept-Language", "de")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw java.io.IOException("HTTP ${response.code}")
            val results = json.parseToJsonElement(response.body?.string().orEmpty()) as? JsonArray
                ?: return@use null
            val objects = results.mapNotNull { it as? JsonObject }
            val station = objects.firstOrNull {
                it.str("category") == "railway" && (it.str("type") ?: "") in RAIL_TYPES
            } ?: objects.firstOrNull { it.str("category") == "railway" }
                ?: objects.firstOrNull { it.str("category") == "public_transport" }
                ?: objects.firstOrNull()
            station?.let { o ->
                val lat = o.str("lat")?.toDoubleOrNull()
                val lon = o.str("lon")?.toDoubleOrNull()
                if (lat != null && lon != null) LatLon(lat, lon) else null
            }
        }
    }

    private fun key(name: String) = RouteRepository.normalize(name)

    private fun decode(raw: String): LatLon? {
        if (raw == MISSING) return null
        val parts = raw.split(",")
        val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
        val lon = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
        return LatLon(lat, lon)
    }

    companion object {
        private const val MISSING = "-"
        private val RAIL_TYPES = setOf("station", "halt", "stop")

        /** "Frankfurt(Main)Hbf" → "Frankfurt (Main) Hauptbahnhof" – so findet OSM den Bahnhof. */
        fun searchText(name: String): String {
            val text = name
                .replace("(", " (").replace(")", ") ")
                .replace(Regex("\\bHbf\\b"), "Hauptbahnhof")
                .replace(Regex("\\bBf\\b"), "Bahnhof")
                .replace(Regex("\\s+"), " ")
                .trim()
            return if (text.contains("bahnhof", ignoreCase = true)) text else "$text Bahnhof"
        }
    }
}
