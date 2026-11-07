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
import okhttp3.HttpUrl.Companion.toHttpUrl
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
 * Gerät. Die Strecke wird in Stücke von höchstens 60 km zerlegt (Rechteck mit Rand je
 * Stück); ist ein Server überlastet, wird der nächste versucht.
 * Kartendaten © OpenStreetMap-Mitwirkende (ODbL).
 */
class TrackRepository(context: Context) {
    private val dir = File(context.filesDir, "tracks").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        // Der Overpass-Server braucht für ein Stück oft 30–45 Sekunden.
        .readTimeout(90, TimeUnit.SECONDS)
        .build()
    private val fastClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    /** Kennung mit App-Version und Kontakt (Repo), nur ASCII. */
    private val userAgent = "BahnSchmerz/" +
        (runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "?") +
        " (https://github.com/FHeiCode/Bahnschmerz)"
    private val json = Json { ignoreUnknownKeys = true }
    private val memory = HashMap<String, Map<Int, List<LatLon>>>()
    private val failed = HashMap<String, Pair<Long, TrackOutcome>>()
    /** Transitous-Antworten je Start/Ziel – höchstens eine Anfrage pro Strecke und App-Start. */
    private val shapesCache = HashMap<String, List<List<LatLon>>>()
    private val mutex = Mutex()

    /**
     * Gleisverlauf für die Bahnhöfe [stations] (Korridor-Index, Koordinate) in Fahrtreihenfolge.
     * Stücke, die nicht geladen werden konnten, fehlen – dort zeichnet die Karte gerade Linien.
     */
    suspend fun track(
        stations: List<Pair<Int, LatLon>>,
        /** Zwischenstand nach jedem geladenen Stück (fertig, gesamt, bisherige Abschnitte). */
        onProgress: (Int, Int, Map<Int, List<LatLon>>) -> Unit = { _, _, _ -> },
    ): TrackOutcome = mutex.withLock {
        if (stations.size < 2) return@withLock TrackOutcome(emptyMap(), null)
        val key = key(stations)
        memory[key]?.let { return@withLock TrackOutcome(it, null) }
        readCache(key)?.let { memory[key] = it; return@withLock TrackOutcome(it, null) }
        // Nach einem Fehler 5 Minuten warten, um die Server nicht zu belasten.
        failed[key]?.let { (at, outcome) -> if (System.currentTimeMillis() - at < RETRY_MILLIS) return@withLock outcome }

        val segments = HashMap<Int, List<LatLon>>()
        var problem: String? = null

        // Issue #16: Die öffentlichen Overpass-Server sind oft überlastet („at capacity“) oder
        // antworten minutenlang nicht. Deshalb zuerst Transitous: eine Anfrage, 1–2 Sekunden,
        // Linienverläufe der Züge zwischen Start und Ziel (aus Fahrplandaten und OSM).
        try {
            val a = stations.first().second
            val b = stations.last().second
            val shapesKey = "%.4f,%.4f>%.4f,%.4f".format(java.util.Locale.ROOT, a.lat, a.lon, b.lat, b.lon)
            val shapes = shapesCache[shapesKey] ?: transitousShapes(a, b).also { shapesCache[shapesKey] = it }
            segments += withContext(Dispatchers.Default) { RailRouter.segmentsFromPolylines(shapes, stations) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            problem = "Transitous: ${e.message ?: e.javaClass.simpleName}"
        }

        // Nur für Abschnitte ohne Linienverlauf noch Overpass fragen.
        val missing = stations.zipWithNext().map { it.first.first }.filter { it !in segments }
        val parts = RailRouter.chunks(stations).filter { c -> c.dropLast(1).any { it.first in missing } }
        if (missing.isEmpty()) problem = null
        for ((n, chunk) in parts.withIndex()) {
            onProgress(n, parts.size, HashMap(segments))
            try {
                val body = fetch(RailRouter.overpassQuery(chunk.map { it.second }))
                val found = withContext(Dispatchers.Default) {
                    RailRouter.segments(RailRouter.parseOverpass(body), chunk)
                }
                // Transitous-Abschnitte behalten, Overpass füllt nur Lücken.
                found.forEach { (k, v) -> segments.putIfAbsent(k, v) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: IOException) {
                problem = e.message ?: "nicht erreichbar"
            } catch (e: OutOfMemoryError) {
                problem = "Antwort zu groß"
            } catch (e: Throwable) {
                // Jeder andere Fehler (z. B. kaputte Antwort) darf die App nicht beenden.
                problem = "Fehler beim Auswerten (${e.javaClass.simpleName}: ${e.message?.take(80) ?: "-"})"
            }
        }
        val stillMissing = stations.zipWithNext().count { it.first.first !in segments }
        val outcome = TrackOutcome(
            segments,
            when {
                stillMissing == 0 -> null
                problem != null -> problem
                segments.isEmpty() -> "kein Gleisweg gefunden"
                else -> "$stillMissing Abschnitt(e) ohne Gleisverlauf"
            },
        )
        if (outcome.problem == null) {
            memory[key] = segments
            writeCache(key, segments)
        } else {
            failed[key] = System.currentTimeMillis() to outcome
        }
        outcome
    }

    /**
     * Fragt Transitous (MOTIS, https://transitous.org) nach direkten Zugverbindungen von [a]
     * nach [b] und liefert deren Linienverläufe. Nutzungsregeln: quelloffen, nicht kommerziell,
     * eigene Kennung mit Kontakt, sichtbarer Quellenhinweis – alles erfüllt. Eine Anfrage
     * pro Strecke; das Ergebnis wird 30 Tage gespeichert.
     */
    private suspend fun transitousShapes(a: LatLon, b: LatLon): List<List<LatLon>> = withContext(Dispatchers.IO) {
        val url = TRANSITOUS.toHttpUrl().newBuilder()
            .addQueryParameter("fromPlace", "%.6f,%.6f".format(java.util.Locale.ROOT, a.lat, a.lon))
            .addQueryParameter("toPlace", "%.6f,%.6f".format(java.util.Locale.ROOT, b.lat, b.lon))
            .addQueryParameter("transitModes", "RAIL")
            .addQueryParameter("maxTransfers", "0")
            .build()
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", userAgent)
            .header("Accept", "application/json")
            .get()
            .build()
        fastClient.newCall(request).execute().use { r ->
            val body = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            RailRouter.parseMotisPlan(body)
        }
    }

    /** Fragt die Overpass-Server der Reihe nach, bis einer eine gültige Antwort liefert. */
    private suspend fun fetch(query: String): String = withContext(Dispatchers.IO) {
        var last = "nicht erreichbar"
        for (server in SERVERS) {
            try {
                val request = Request.Builder()
                    .url(server)
                    // Nur ASCII: OkHttp lehnt Umlaute in Kopfzeilen mit IllegalArgumentException ab.
                    .header("User-Agent", "BahnSchmerz-Android (Gleisverlauf fuer Zugkarte)")
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
                last = "nicht erreichbar (${e.javaClass.simpleName})"
            }
        }
        throw IOException(last)
    }

    private fun key(stations: List<Pair<Int, LatLon>>): String {
        // "v6": seit 1.5.8 Transitous + Overpass – ältere, meist lückenhafte Einträge verwerfen.
        val text = "v6|" + stations.joinToString(";") { (i, p) -> "%d:%.4f,%.4f".format(java.util.Locale.ROOT, i, p.lat, p.lon) }
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
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        )
        private const val TRANSITOUS = "https://api.transitous.org/api/v5/plan"
        private const val MAX_AGE_MILLIS = 30L * 24 * 60 * 60 * 1000
        private const val RETRY_MILLIS = 5 * 60_000L
    }
}
