package de.bahnlive.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

class StationException(message: String) : Exception(message)

/**
 * Zugriff auf die öffentliche DBF-Abfahrtstafel (https://dbf.finalrewind.org),
 * die die IRIS-Echtzeitdaten der Deutschen Bahn als JSON bereitstellt.
 *
 * Nutzungsregeln des Dienstes: höchstens 30 Anfragen pro Minute und
 * nur eine Anfrage pro Bahnhof und Minute. Deshalb wird jede Tafel
 * 60 Sekunden zwischengespeichert.
 */
class DbfApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val cache = mutableMapOf<String, Pair<Long, List<BoardEntry>>>()
    private val mutex = Mutex()
    private val requestTimes = ArrayDeque<Long>()

    /**
     * Abfahrtstafel eines Bahnhofs. [optional] = Zusatztafel (Zwischenbahnhof): Sie bekommt
     * ein kleineres Kontingent, damit Start- und Zieltafel immer abgefragt werden können.
     */
    suspend fun board(station: String, optional: Boolean = false): List<BoardEntry> {
        val key = station.trim().lowercase()
        val cached = mutex.withLock { cache[key] }
        if (cached != null && System.currentTimeMillis() - cached.first < CACHE_MILLIS) return cached.second

        val allowed = mutex.withLock {
            val now = System.currentTimeMillis()
            while (requestTimes.isNotEmpty() && now - requestTimes.first() > 60_000) requestTimes.removeFirst()
            val limit = if (optional) OPTIONAL_PER_MINUTE else MAX_PER_MINUTE
            if (requestTimes.size >= limit) false else { requestTimes.addLast(now); true }
        }
        if (!allowed) {
            // Lieber etwas ältere Daten zeigen als die Nutzungsregeln verletzen.
            cached?.let { return it.second }
            throw IOException("Anfragelimit erreicht – bitte eine Minute warten.")
        }

        val data = fetch(station.trim())
        mutex.withLock { cache[key] = System.currentTimeMillis() to data }
        return data
    }

    private suspend fun fetch(station: String): List<BoardEntry> = withContext(Dispatchers.IO) {
        val url = "https://dbf.finalrewind.org/".toHttpUrl().newBuilder()
            .addPathSegment(station)
            .addQueryParameter("mode", "json")
            .addQueryParameter("version", "3")
            .addQueryParameter("no_related", "1")
            // Auch Züge der letzten Stunde – nötig für Züge, die gerade unterwegs sind.
            .addQueryParameter("past", "1")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", "Bahnschmerz-Android/1.0")
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 429) throw IOException("Zu viele Anfragen – bitte kurz warten.")
            val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull()
                ?: throw IOException("Server antwortete mit HTTP ${response.code}.")

            root["error"]?.let { err ->
                val text = (err as? JsonPrimitive)?.contentOrNull ?: err.toString()
                val candidates = (root["candidates"] as? JsonArray)
                    ?.mapNotNull { c ->
                        when (c) {
                            is JsonPrimitive -> c.contentOrNull
                            is JsonObject -> c.str("name")
                            is JsonArray -> (c.firstOrNull() as? JsonPrimitive)?.contentOrNull
                            else -> null
                        }
                    }
                    ?.take(5)
                    .orEmpty()
                val hint = if (candidates.isNotEmpty()) "\nMeinten Sie: ${candidates.joinToString(", ")}?" else ""
                throw StationException("„$station“: $text$hint")
            }

            val departures = root["departures"] as? JsonArray
                ?: throw IOException("Unerwartete Antwort für „$station“.")
            departures.mapNotNull { (it as? JsonObject)?.toEntry() }
        }
    }

    private fun JsonObject.toEntry(): BoardEntry? {
        val train = str("train") ?: return null
        return BoardEntry(
            train = train,
            trainNumber = str("trainNumber"),
            destination = str("destination"),
            scheduledArrival = str("scheduledArrival"),
            scheduledDeparture = str("scheduledDeparture"),
            delayArrival = int("delayArrival"),
            delayDeparture = int("delayDeparture"),
            platform = str("platform"),
            scheduledPlatform = str("scheduledPlatform"),
            isCancelled = bool("isCancelled"),
            missingRealtime = bool("missingRealtime"),
            route = (this["route"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val name = o.str("name") ?: return@mapNotNull null
                RouteStop(name, o.bool("isCancelled"), o.bool("isAdditional"))
            },
            messages = parseMessages(this["messages"]),
        )
    }

    private fun parseMessages(el: JsonElement?): List<String> {
        val obj = el as? JsonObject ?: return emptyList()
        return listOf("qos", "delay")
            .flatMap { (obj[it] as? JsonArray).orEmpty() }
            .mapNotNull { (it as? JsonObject)?.str("text") }
            .filter { it.isNotBlank() }
            .distinct()
    }

    companion object {
        private const val CACHE_MILLIS = 60_000L

        /** DBF erlaubt 30 Anfragen pro Minute – etwas Reserve bleibt. */
        private const val MAX_PER_MINUTE = 26
        private const val OPTIONAL_PER_MINUTE = 18
    }
}

// --- Hilfsfunktionen für tolerantes JSON-Parsen (Felder können fehlen, null, Zahl oder Text sein) ---

internal fun JsonObject.str(key: String): String? = when (val v = this[key]) {
    null, JsonNull -> null
    is JsonPrimitive -> v.contentOrNull?.takeIf { it.isNotBlank() }
    else -> null
}

internal fun JsonObject.int(key: String): Int? = when (val v = this[key]) {
    is JsonPrimitive -> v.intOrNull ?: v.contentOrNull?.toIntOrNull()
    else -> null
}

internal fun JsonObject.bool(key: String): Boolean = when (val v = this[key]) {
    is JsonPrimitive -> v.booleanOrNull ?: ((v.intOrNull ?: 0) != 0)
    else -> false
}
