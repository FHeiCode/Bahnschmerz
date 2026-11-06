package de.bahnlive.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/** Ergebnis der Gleisabfrage: Abschnitte und ein Satz für die Anzeige. */
data class TrackOutcome(
    val segments: Map<Int, List<LatLon>>,
    /** Null = alles geladen; sonst Hinweis, z. B. „Server überlastet (HTTP 504)“. */
    val problem: String?,
)

/**
 * Lädt Gleisverläufe aus OpenStreetMap (Overpass API) und speichert sie 30 Tage auf dem
 * Gerät. Die Strecke wird in Stücke von etwa 40 km zerlegt, damit jede Abfrage klein
 * bleibt; ist ein Server überlastet, wird der nächste versucht.
 * Kartendaten © OpenStreetMap-Mitwirkende (ODbL).
 */
class TrackRepository(context: Context) {
    private val dir = File(context.filesDir, "tracks").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(40, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val memory = HashMap<String, Map<Int, List<LatLon>>>()
    private val failed = HashMap<String, Pair<Long, TrackOutcome>>()
    private val mutex = Mutex()

    /**
     * Gleisverlauf für die Bahnhöfe [stations] (Korridor-Index, Koordinate) in Fahrtreihenfolge.
     * Stücke, die nicht geladen werden konnten, fehlen – dort zeichnet die Karte gerade Linien.
     */
    suspend fun track(stations: List<Pair<Int, LatLon>>): TrackOutcome = mutex.withLock {
        if (stations.size < 2) return@withLock TrackOutcome(emptyMap(), null)
        val key = key(stations)
        memory[key]?.let { return@withLock TrackOutcome(it, null) }
        readCache(key)?.let { memory[key] = it; return@withLock TrackOutcome(it, null) }
        // Nach einem Fehler 5 Minuten warten, um die Server nicht zu belasten.
        failed[key]?.let { (at, outcome) -> if (System.currentTimeMillis() - at < RETRY_MILLIS) return@withLock outcome }

        val segments = HashMap<Int, List<LatLon>>()
        var problem: String? = null
        for (chunk in RailRouter.chunks(stations)) {
            try {
                val body = fetch(RailRouter.overpassQuery(chunk.map { it.second }))
                segments += withContext(Dispatchers.Default) {
                    RailRouter.segments(RailRouter.parseOverpass(body), chunk)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IOException) {
                problem = e.message ?: "nicht erreichbar"
            } catch (e: OutOfMemoryError) {
                problem = "Antwort zu groß"
            } catch (e: Throwable) {
                // Jeder andere Fehler (z. B. kaputte Antwort) darf die App nicht beenden.
                problem = "Fehler beim Auswerten (${e.javaClass.simpleName})"
            }
        }
        val outcome = TrackOutcome(segments, problem ?: if (segments.isEmpty()) "kein Gleisweg gefunden" else null)
        if (outcome.problem == null) {
            memory[key] = segments
            writeCache(key, segments)
        } else {
            failed[key] = System.currentTimeMillis() to outcome
        }
        outcome
    }

    /** Fragt die Overpass-Server der Reihe nach, bis einer eine gültige Antwort liefert. */
    private suspend fun fetch(query: String): String = withContext(Dispatchers.IO) {
        var last = "nicht erreichbar"
        for (server in SERVERS) {
            try {
                val request = Request.Builder()
                    .url(server)
                    .header("User-Agent", "BahnSchmerz-Android (Gleisverlauf für Zugkarte)")
                    .post(FormBody.Builder().add("data", query).build())
                    .build()
                client.newCall(request).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    when {
                        !r.isSuccessful -> last = if (r.code == 429 || r.code == 504) "Server überlastet (HTTP ${r.code})" else "HTTP ${r.code}"
                        !body.trimStart().startsWith("{") -> last = "unerwartete Antwort"
                        // Overpass meldet Abbrüche (Zeit, Speicher) als "remark" mit Teildaten.
                        body.contains("\"remark\"") && body.contains("runtime error") -> last = "Server-Zeitlimit"
                        else -> return@withContext body
                    }
                }
            } catch (e: IOException) {
                last = "nicht erreichbar"
            }
        }
        throw IOException(last)
    }

    private fun key(stations: List<Pair<Int, LatLon>>): String {
        // "v4": seit 1.5.4 mit Plausibilitätsprüfung – ältere, evtl. fehlerhafte Einträge verwerfen.
        val text = "v4|" + stations.joinToString(";") { (i, p) -> "%d:%.4f,%.4f".format(java.util.Locale.ROOT, i, p.lat, p.lon) }
        return MessageDigest.getInstance("SHA-1").digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }

    private fun readCache(key: String): Map<Int, List<LatLon>>? {
        val file = File(dir, "$key.json")
        if (!file.exists() || System.currentTimeMillis() - file.lastModified() > MAX_AGE_MILLIS) return null
        return runCatching {
            val root = json.parseToJsonElement(file.readText()).jsonObject
            root.mapNotNull { (k, v) ->
                val idx = k.toIntOrNull() ?: return@mapNotNull null
                idx to v.jsonArray.map { p -> p.jsonArray.let { LatLon(it[0].jsonPrimitive.double, it[1].jsonPrimitive.double) } }
            }.toMap()
        }.getOrNull()
    }

    private fun writeCache(key: String, track: Map<Int, List<LatLon>>) {
        runCatching {
            val obj = JsonObject(track.entries.associate { (i, pts) ->
                i.toString() to JsonArray(pts.map { JsonArray(listOf(JsonPrimitive(it.lat), JsonPrimitive(it.lon))) })
            })
            File(dir, "$key.json").writeText(obj.toString())
            // Alte Dateien aufräumen
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > MAX_AGE_MILLIS }?.forEach { it.delete() }
        }
    }

    companion object {
        /** Öffentliche Overpass-Server, der Reihe nach. */
        private val SERVERS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
        )
        private const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000
        private const val RETRY_MILLIS = 5 * 60_000L
    }
}
