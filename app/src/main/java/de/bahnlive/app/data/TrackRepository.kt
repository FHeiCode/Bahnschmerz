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
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

/**
 * Lädt Gleisverläufe aus OpenStreetMap (Overpass API) und speichert sie 30 Tage auf dem
 * Gerät. Gleise ändern sich selten, deshalb gibt es pro Strecke nur eine Abfrage.
 * Kartendaten © OpenStreetMap-Mitwirkende (ODbL).
 */
class TrackRepository(context: Context) {
    private val dir = File(context.filesDir, "tracks").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val json = Json { ignoreUnknownKeys = true }
    private val memory = HashMap<String, Map<Int, List<LatLon>>>()
    private val failedAt = HashMap<String, Long>()
    private val mutex = Mutex()

    /**
     * Gleisverlauf für die Bahnhöfe [stations] (Korridor-Index, Koordinate) in Fahrtreihenfolge.
     * Null, wenn er (noch) nicht verfügbar ist – die Karte zeichnet dann gerade Linien.
     */
    suspend fun track(stations: List<Pair<Int, LatLon>>): Map<Int, List<LatLon>>? = mutex.withLock {
        if (stations.size < 2) return@withLock null
        val key = key(stations)
        memory[key]?.let { return@withLock it }
        readCache(key)?.let { memory[key] = it; return@withLock it }
        // Nach einem Fehler 10 Minuten warten, um den Overpass-Server nicht zu belasten.
        failedAt[key]?.let { if (System.currentTimeMillis() - it < 10 * 60_000L) return@withLock null }

        // Leeres Ergebnis (z. B. Zeitüberschreitung bei Overpass) gilt als Fehler.
        val result = runCatching { download(stations) }.getOrNull()?.takeIf { it.isNotEmpty() }
        if (result == null) {
            failedAt[key] = System.currentTimeMillis()
            return@withLock null
        }
        memory[key] = result
        writeCache(key, result)
        result
    }

    private suspend fun download(stations: List<Pair<Int, LatLon>>): Map<Int, List<LatLon>> =
        withContext(Dispatchers.IO) {
            val query = RailRouter.overpassQuery(stations.map { it.second })
            val request = Request.Builder()
                .url(OVERPASS)
                .header("User-Agent", "BahnSchmerz-Android (Gleisverlauf für Zugkarte)")
                .post(FormBody.Builder().add("data", query).build())
                .build()
            val body = client.newCall(request).execute().use { r ->
                if (!r.isSuccessful) throw java.io.IOException("Overpass HTTP ${r.code}")
                r.body?.string() ?: throw java.io.IOException("leere Antwort")
            }
            // Graph aufbauen und Wege suchen ist Rechenarbeit → nicht im IO-Pool blockieren.
            withContext(Dispatchers.Default) {
                RailRouter.segments(RailRouter.parseOverpass(body), stations)
            }
        }

    private fun key(stations: List<Pair<Int, LatLon>>): String {
        // "v2": Format seit 1.5.1 (nur Gleispunkte, mit Weichenverbindungen) – alte Einträge verwerfen.
        val text = "v2|" + stations.joinToString(";") { (i, p) -> "%d:%.4f,%.4f".format(java.util.Locale.ROOT, i, p.lat, p.lon) }
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
        private const val OVERPASS = "https://overpass-api.de/api/interpreter"
        private const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000
    }
}
