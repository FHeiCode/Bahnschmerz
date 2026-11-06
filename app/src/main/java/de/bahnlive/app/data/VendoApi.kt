package de.bahnlive.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Zugriff auf die Schnittstelle der DB-Navigator-App („vendo“).
 *
 * Achtung: Die Schnittstelle ist nicht offiziell freigegeben und kann sich jederzeit ändern.
 * Deshalb: kurze Zeitlimits, Zwischenspeicher, höchstens [MAX_PER_MINUTE] Anfragen pro Minute,
 * und nach einem Fehler 10 Minuten Pause. Die App funktioniert ohne sie weiter.
 */
class VendoApi(context: Context) : TripSource {
    private val prefs = context.getSharedPreferences("vendo_stations", Context.MODE_PRIVATE)
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()
    private val mutex = Mutex()
    private val requestTimes = ArrayDeque<Long>()
    private var pausedUntil = 0L
    private val boardCache = HashMap<String, Pair<Long, List<VendoBoardEntry>>>()
    private val tripCache = HashMap<String, Pair<Long, VendoTrip>>()

    /** True, solange die Schnittstelle nach einem Fehler pausiert. */
    val isPaused: Boolean get() = System.currentTimeMillis() < pausedUntil

    override suspend fun station(name: String): VendoStation? {
        val key = RouteRepository.normalize(name)
        prefs.getString(key, null)?.let { decodeStation(name, it) }?.let { return it }
        val body = buildJson {
            put("locationTypes", JsonArray(listOf(JsonPrimitive("ST"))))
            put("searchTerm", JsonPrimitive(name))
            put("maxResults", JsonPrimitive(5))
        }
        val text = request("location/search", body, LOCATION_TYPE) ?: return null
        val all = VendoParser.parseLocations(text)
        val best = all.firstOrNull { RouteRepository.sameStation(it.name, name) } ?: all.firstOrNull() ?: return null
        prefs.edit().putString(key, encodeStation(best)).apply()
        return best
    }

    override suspend fun arrivals(eva: String): List<VendoBoardEntry> {
        mutex.withLock { boardCache[eva] }?.let { (t, data) -> if (System.currentTimeMillis() - t < BOARD_CACHE_MILLIS) return data }
        val now = LocalDateTime.now()
        val body = buildJson {
            put("anfragezeit", JsonPrimitive(now.format(DateTimeFormatter.ofPattern("HH:mm"))))
            put("datum", JsonPrimitive(now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))))
            put("ursprungsBahnhofId", JsonPrimitive("A=1@L=$eva@"))
            put("verkehrsmittel", JsonArray(listOf(JsonPrimitive("ALL"))))
        }
        val text = request("bahnhofstafel/ankunft", body, BOARD_TYPE) ?: return emptyList()
        val data = VendoParser.parseBoard(text)
        mutex.withLock { boardCache[eva] = System.currentTimeMillis() to data }
        return data
    }

    override suspend fun trip(tripId: String): VendoTrip? {
        mutex.withLock { tripCache[tripId] }?.let { (t, data) -> if (System.currentTimeMillis() - t < TRIP_CACHE_MILLIS) return data }
        val text = request("zuglauf", null, TRIP_TYPE, pathSegment = tripId) ?: return null
        val trip = VendoParser.parseTrip(text) ?: return null
        mutex.withLock {
            tripCache[tripId] = System.currentTimeMillis() to trip
            if (tripCache.size > 200) tripCache.entries.removeAll { System.currentTimeMillis() - it.value.first > TRIP_CACHE_MILLIS }
        }
        return trip
    }

    /** Führt eine Anfrage aus; null bei Pause, Limit oder Fehler. */
    private suspend fun request(path: String, body: JsonObject?, type: String, pathSegment: String? = null): String? {
        val allowed = mutex.withLock {
            val now = System.currentTimeMillis()
            if (now < pausedUntil) return@withLock false
            while (requestTimes.isNotEmpty() && now - requestTimes.first() > 60_000) requestTimes.removeFirst()
            if (requestTimes.size >= MAX_PER_MINUTE) false else { requestTimes.addLast(now); true }
        }
        if (!allowed) return null

        return withContext(Dispatchers.IO) {
            val url = BASE.toHttpUrl().newBuilder().apply {
                path.split('/').forEach { addPathSegment(it) }
                pathSegment?.let { addPathSegment(it) } // kodiert #, | und Leerzeichen korrekt
            }.build()
            val builder = Request.Builder()
                .url(url)
                .header("Accept", type)
                .header("Content-Type", type)
                .header("X-Correlation-ID", "${UUID.randomUUID()}_${UUID.randomUUID()}")
                .header("Accept-Language", "de")
                .header("User-Agent", "BahnSchmerz-Android")
            if (body != null) builder.post(body.toString().toRequestBody(type.toMediaType())) else builder.get()
            try {
                client.newCall(builder.build()).execute().use { response ->
                    when {
                        response.isSuccessful -> response.body?.string()
                        // Gesperrt, zu viele Anfragen oder Serverproblem → pausieren.
                        response.code == 403 || response.code == 429 || response.code >= 500 ->
                            throw IOException("HTTP ${response.code}")
                        // Einzelne Anfrage ungültig (z. B. Zuglauf abgelaufen) → nur diese überspringen.
                        else -> null
                    }
                }
            } catch (e: IOException) {
                // Fehler → Schnittstelle 10 Minuten in Ruhe lassen, Schätzung übernimmt.
                mutex.withLock { pausedUntil = System.currentTimeMillis() + PAUSE_MILLIS }
                null
            }
        }
    }

    private fun buildJson(block: MutableMap<String, kotlinx.serialization.json.JsonElement>.() -> Unit) =
        JsonObject(LinkedHashMap<String, kotlinx.serialization.json.JsonElement>().apply(block))

    private fun encodeStation(s: VendoStation) =
        listOf(s.eva, s.name, s.position?.lat ?: "", s.position?.lon ?: "").joinToString("|")

    private fun decodeStation(fallbackName: String, raw: String): VendoStation? {
        val p = raw.split("|")
        val eva = p.getOrNull(0)?.takeIf { it.isNotBlank() } ?: return null
        val lat = p.getOrNull(2)?.toDoubleOrNull()
        val lon = p.getOrNull(3)?.toDoubleOrNull()
        return VendoStation(p.getOrNull(1) ?: fallbackName, eva, if (lat != null && lon != null) LatLon(lat, lon) else null)
    }

    companion object {
        private const val BASE = "https://app.services-bahn.de/mob/"
        private const val LOCATION_TYPE = "application/x.db.vendo.mob.location.v3+json"
        private const val BOARD_TYPE = "application/x.db.vendo.mob.bahnhofstafeln.v2+json"
        private const val TRIP_TYPE = "application/x.db.vendo.mob.zuglauf.v2+json"
        private const val MAX_PER_MINUTE = 20
        private const val BOARD_CACHE_MILLIS = 120_000L
        private const val TRIP_CACHE_MILLIS = 90_000L
        private const val PAUSE_MILLIS = 10 * 60_000L
    }
}
