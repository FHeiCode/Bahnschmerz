package de.bahnlive.app.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.util.PriorityQueue
import kotlin.math.cos

/** Gleisnetz als Graph: Knoten = OSM-Punkte, Kanten = Gleisabschnitte mit Länge in km. */
class RailGraph(
    val lat: DoubleArray,
    val lon: DoubleArray,
    val neighbors: Array<IntArray>,
    val lengths: Array<DoubleArray>,
) {
    val size: Int get() = lat.size
    fun point(i: Int) = LatLon(lat[i], lon[i])
}

/**
 * Findet den Gleisverlauf zwischen Bahnhöfen auf Basis der OpenStreetMap-Gleise
 * (Overpass-Abfrage). Zwischen zwei aufeinanderfolgenden Bahnhöfen wird der kürzeste
 * Weg über das Gleisnetz gesucht. Ist er unplausibel lang oder fehlt er, bleibt der
 * Abschnitt eine gerade Linie.
 */
object RailRouter {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Bahnhöfe weiter als das vom nächsten Gleis entfernt → kein Gleisverlauf. */
    private const val MAX_SNAP_KM = 1.5

    /**
     * Höchstens so viel länger als die Luftlinie darf ein Gleisweg sein. Echte Bögen gibt es:
     * Flughafen München → Freising sind 18 km Gleis bei 5 km Luftlinie.
     */
    private const val MAX_DETOUR = 4.0

    /**
     * Linienverläufe aus einer Transitous/MOTIS-Antwort (`/plan`): je Fahrt im Zug die
     * Geometrie (`legGeometry`, „encoded polyline“). Fußwege werden ignoriert.
     * Issue #16: Overpass ist oft überlastet; Transitous antwortet in 1–2 Sekunden.
     */
    fun parseMotisPlan(body: String): List<List<LatLon>> {
        val root = json.parseToJsonElement(body) as? JsonObject ?: return emptyList()
        val itineraries = (root["itineraries"] as? JsonArray).orEmpty() + (root["direct"] as? JsonArray).orEmpty()
        val result = ArrayList<List<LatLon>>()
        for (it in itineraries) {
            val legs = ((it as? JsonObject)?.get("legs") as? JsonArray).orEmpty()
            for (l in legs) {
                val leg = l as? JsonObject ?: continue
                val mode = (leg["mode"] as? JsonPrimitive)?.contentOrNull ?: continue
                if (mode in NON_TRANSIT) continue
                val geom = leg["legGeometry"] as? JsonObject ?: continue
                val points = (geom["points"] as? JsonPrimitive)?.contentOrNull ?: continue
                val precision = (geom["precision"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 6
                val line = runCatching { decodePolyline(points, precision) }.getOrNull() ?: continue
                // An Sprüngen trennen (wie bei der DB-Linie), damit nichts quer übers Land führt.
                var current = ArrayList<LatLon>()
                for (p in line) {
                    if (current.isNotEmpty() && CorridorGeometry.distanceKm(current.last(), p) > 25.0) {
                        if (current.size >= 2) result.add(current)
                        current = ArrayList()
                    }
                    current.add(p)
                }
                if (current.size >= 2) result.add(current)
            }
        }
        return result
    }

    private val NON_TRANSIT = setOf("WALK", "BIKE", "CAR", "RENTAL", "CAR_PARKING", "CAR_DROPOFF", "ODM", "FLEX", "DEBUG_BUS_ROUTE", "DEBUG_RAILWAY_ROUTE", "DEBUG_FERRY_ROUTE")

    /** Google-„Encoded Polyline“ mit wählbarer Genauigkeit (MOTIS: 6 bzw. 7 Stellen). */
    fun decodePolyline(encoded: String, precision: Int = 6): List<LatLon> {
        val factor = Math.pow(10.0, precision.toDouble())
        val result = ArrayList<LatLon>()
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            for (which in 0..1) {
                var shift = 0
                var value = 0L
                while (true) {
                    val b = encoded[index++].code - 63
                    value = value or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val delta = if (value and 1L != 0L) (value shr 1).inv() else value shr 1
                if (which == 0) lat += delta else lon += delta
            }
            result.add(LatLon(lat / factor, lon / factor))
        }
        return result
    }

    /**
     * Teilt die Bahnhöfe in Stücke von höchstens [maxKm] Luftlinie (überlappend am Rand), damit jede
     * Overpass-Abfrage klein bleibt – rund um Großstädte ist das Gleisnetz sehr dicht.
     */
    fun <T> chunks(stations: List<Pair<T, LatLon>>, maxKm: Double = 60.0): List<List<Pair<T, LatLon>>> {
        if (stations.size < 2) return emptyList()
        val result = ArrayList<List<Pair<T, LatLon>>>()
        var start = 0
        while (start < stations.lastIndex) {
            var end = start + 1
            var km = CorridorGeometry.distanceKm(stations[start].second, stations[end].second)
            while (end < stations.lastIndex) {
                val next = CorridorGeometry.distanceKm(stations[end].second, stations[end + 1].second)
                if (km + next > maxKm) break
                km += next
                end++
            }
            result.add(stations.subList(start, end + 1))
            start = end
        }
        return result
    }

    /**
     * Abschnitte aus Linienverläufen der DB (Fahrtverlauf eines Zuges): das Stück zwischen den
     * Punkten, die den beiden Bahnhöfen am nächsten liegen. Ersatz, wo OSM nichts liefert.
     */
    fun segmentsFromPolylines(polylines: List<List<LatLon>>, stations: List<Pair<Int, LatLon>>): Map<Int, List<LatLon>> {
        val result = HashMap<Int, List<LatLon>>()
        if (polylines.isEmpty()) return result
        // Kilometerstand entlang jeder Linie, um Stücke schnell zu messen.
        val cumulative = polylines.map { poly ->
            DoubleArray(poly.size).also { c -> for (i in 1 until poly.size) c[i] = c[i - 1] + CorridorGeometry.distanceKm(poly[i - 1], poly[i]) }
        }
        for (k in 0 until stations.size - 1) {
            val a = stations[k].second
            val b = stations[k + 1].second
            var best: List<LatLon>? = null
            var bestLength = Double.MAX_VALUE
            polylines.forEachIndexed { n, poly ->
                // Alle Stellen, an denen die Linie nah an A bzw. B vorbeiführt (es können mehrere sein).
                val nearA = closeIndices(poly, a)
                val nearB = closeIndices(poly, b)
                for (ia in nearA) for (ib in nearB) {
                    if (ia == ib) continue
                    val length = kotlin.math.abs(cumulative[n][ib] - cumulative[n][ia])
                    if (length >= bestLength) continue
                    val part = if (ia < ib) poly.subList(ia, ib + 1) else poly.subList(ib, ia + 1).reversed()
                    if (!plausible(part, a, b)) continue
                    best = part
                    bestLength = length
                }
            }
            best?.let { result[stations[k].first] = thin(it) }
        }
        return result
    }

    /**
     * Indizes der Linienpunkte nahe [p]: pro Vorbeifahrt nur der nächste Punkt
     * (eine Linie kann mehrfach an einem Bahnhof vorbeiführen).
     */
    private fun closeIndices(poly: List<LatLon>, p: LatLon): List<Int> {
        val kx = cos(Math.toRadians(p.lat)) * 111.32
        val result = ArrayList<Int>()
        var runBest = -1
        var runBestD = Double.MAX_VALUE
        poly.forEachIndexed { i, q ->
            val dx = (q.lon - p.lon) * kx
            val dy = (q.lat - p.lat) * 110.57
            val d = kotlin.math.sqrt(dx * dx + dy * dy)
            if (d <= MAX_SNAP_KM) {
                if (d < runBestD) { runBestD = d; runBest = i }
            } else if (runBest >= 0) {
                result.add(runBest); runBest = -1; runBestD = Double.MAX_VALUE
            }
        }
        if (runBest >= 0) result.add(runBest)
        return result
    }

    /**
     * Ist ein Gleisstück zwischen A und B glaubwürdig? Nicht viel länger als die Luftlinie
     * und seitlich nicht zu weit davon entfernt. Echte Bögen gibt es (Flughafen München →
     * Freising: 18 km Gleis bei 5 km Luftlinie), Sprünge quer übers Land aber nicht.
     */
    fun plausible(path: List<LatLon>, a: LatLon, b: LatLon): Boolean {
        if (path.size < 2) return false
        val straight = CorridorGeometry.distanceKm(a, b)
        val length = path.zipWithNext { x, y -> CorridorGeometry.distanceKm(x, y) }.sum()
        if (length > straight * MAX_DETOUR + 5) return false
        val maxSide = maxOf(8.0, straight * 0.8)
        return path.all { p -> CorridorGeometry.distanceKm(a, p) + CorridorGeometry.distanceKm(p, b) <= straight + 2 * maxSide }
    }

    /**
     * Overpass-Abfrage für alle Gleise (ohne Abstell-, Rangier- und Anschlussgleise) in einem
     * Rechteck um die Bahnhöfe, mit mindestens 10 km Rand. Ein schmaler Streifen um die gerade
     * Linie reicht nicht: Die Strecke München → Pfaffenhofen führt über Dachau, 9 km daneben.
     * Rechteck-Abfragen sind außerdem viel schneller als „around“.
     */
    fun overpassQuery(stations: List<LatLon>): String {
        val south = stations.minOf { it.lat }
        val north = stations.maxOf { it.lat }
        val west = stations.minOf { it.lon }
        val east = stations.maxOf { it.lon }
        val spanKm = CorridorGeometry.distanceKm(LatLon(south, west), LatLon(north, east))
        val marginKm = maxOf(10.0, spanKm * 0.3)
        val dLat = marginKm / 110.57
        val dLon = marginKm / (111.32 * cos(Math.toRadians((south + north) / 2)))
        val box = "%.4f,%.4f,%.4f,%.4f".format(java.util.Locale.ROOT, south - dLat, west - dLon, north + dLat, east + dLon)
        return "[out:json][timeout:90];" +
            // Weichenverbindungen (service=crossover) werden gebraucht: Hauptstrecken sind oft je
            // Richtung ein eigenes Gleis, verbunden nur über Weichen.
            "way[\"railway\"=\"rail\"][\"service\"!~\"^(yard|siding|spur)$\"]($box);" +
            "out skel geom;" // ohne Attribute → deutlich kleinere Antwort
    }

    fun parseOverpass(body: String): RailGraph {
        val root = json.parseToJsonElement(body) as? JsonObject
        val elements = root?.get("elements") as? JsonArray ?: JsonArray(emptyList())
        val index = HashMap<Long, Int>()
        val lat = ArrayList<Double>()
        val lon = ArrayList<Double>()
        val edges = ArrayList<MutableList<Pair<Int, Double>>>()

        fun node(id: Long, la: Double, lo: Double): Int = index.getOrPut(id) {
            lat.add(la); lon.add(lo); edges.add(mutableListOf()); lat.size - 1
        }

        for (el in elements) {
            val o = el as? JsonObject ?: continue
            if ((o["type"] as? JsonPrimitive)?.contentOrNull != "way") continue
            val ids = (o["nodes"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.longOrNull } ?: continue
            val geom = (o["geometry"] as? JsonArray)?.mapNotNull { g ->
                val go = g as? JsonObject ?: return@mapNotNull null
                val la = (go["lat"] as? JsonPrimitive)?.doubleOrNull
                val lo = (go["lon"] as? JsonPrimitive)?.doubleOrNull
                if (la != null && lo != null) LatLon(la, lo) else null
            } ?: continue
            if (ids.size != geom.size || ids.size < 2) continue
            var prev = node(ids[0], geom[0].lat, geom[0].lon)
            for (k in 1 until ids.size) {
                val cur = node(ids[k], geom[k].lat, geom[k].lon)
                if (cur != prev) {
                    val d = CorridorGeometry.distanceKm(geom[k - 1], geom[k])
                    edges[prev].add(cur to d)
                    edges[cur].add(prev to d)
                }
                prev = cur
            }
        }
        return RailGraph(
            lat.toDoubleArray(),
            lon.toDoubleArray(),
            Array(edges.size) { i -> IntArray(edges[i].size) { edges[i][it].first } },
            Array(edges.size) { i -> DoubleArray(edges[i].size) { edges[i][it].second } },
        )
    }

    /**
     * Gleisverlauf für jeden Abschnitt zwischen aufeinanderfolgenden Bahnhöfen.
     * [stations]: (Korridor-Index, Koordinate) in Fahrtreihenfolge.
     * Ergebnis: Korridor-Index des Abschnittsbeginns → Punkte von dort bis zum nächsten Bahnhof.
     */
    fun segments(graph: RailGraph, stations: List<Pair<Int, LatLon>>): Map<Int, List<LatLon>> {
        if (graph.size == 0) return emptyMap()
        val snapped = stations.map { (_, p) -> candidates(graph, p) }
        val result = HashMap<Int, List<LatLon>>()
        for (k in 0 until stations.size - 1) {
            val from = snapped[k] ?: continue
            val to = snapped[k + 1] ?: continue
            val straight = CorridorGeometry.distanceKm(stations[k].second, stations[k + 1].second)
            val path = shortestPath(graph, from, to, maxKm = straight * MAX_DETOUR + 5) ?: continue
            if (path.size < 2) continue
            val length = path.zipWithNext { x, y -> CorridorGeometry.distanceKm(graph.point(x), graph.point(y)) }.sum()
            // Umwege über Nebenstrecken oder falsche Gleise erkennen.
            if (length > straight * MAX_DETOUR + 5) continue
            // Nur Punkte auf dem Gleis – keine Bahnhofskoordinate, die neben dem Gleis liegen kann.
            val points = path.map { graph.point(it) }
            if (!plausible(points, stations[k].second, stations[k + 1].second)) continue
            result[stations[k].first] = thin(points)
        }
        return result
    }

    /**
     * Gleisknoten in der Nähe eines Bahnhofs: der nächste und alle bis 250 m weiter entfernten
     * (z. B. die Gleise beider Fahrtrichtungen). Wert = Abstand zum Bahnhof in km.
     */
    private fun candidates(graph: RailGraph, p: LatLon): Map<Int, Double>? {
        val kx = cos(Math.toRadians(p.lat)) * 111.32
        val dist = DoubleArray(graph.size) { i ->
            val dx = (graph.lon[i] - p.lon) * kx
            val dy = (graph.lat[i] - p.lat) * 110.57
            kotlin.math.sqrt(dx * dx + dy * dy)
        }
        val best = dist.minOrNull() ?: return null
        if (best > MAX_SNAP_KM) return null
        return dist.indices.filter { dist[it] <= best + 0.25 }
            .sortedBy { dist[it] }
            .take(60)
            .associateWith { dist[it] }
    }

    /**
     * Kürzester Weg von einem der Startknoten zu einem der Zielknoten. Der Abstand der Knoten
     * zum Bahnhof zählt mit, damit der Weg möglichst nah am Bahnhof beginnt und endet.
     */
    private fun shortestPath(graph: RailGraph, from: Map<Int, Double>, to: Map<Int, Double>, maxKm: Double): List<Int>? {
        val dist = DoubleArray(graph.size) { Double.MAX_VALUE }
        val prev = IntArray(graph.size) { -1 }
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        for ((node, d) in from) {
            dist[node] = d
            queue.add(d to node)
        }
        var bestTarget = -1
        var bestTotal = Double.MAX_VALUE
        while (queue.isNotEmpty()) {
            val (d, u) = queue.poll()
            if (d > dist[u]) continue
            if (d >= bestTotal || d > maxKm) break
            to[u]?.let { extra -> if (d + extra < bestTotal) { bestTotal = d + extra; bestTarget = u } }
            val ns = graph.neighbors[u]
            val ls = graph.lengths[u]
            for (k in ns.indices) {
                val nd = d + ls[k]
                if (nd < dist[ns[k]]) {
                    dist[ns[k]] = nd
                    prev[ns[k]] = u
                    queue.add(nd to ns[k])
                }
            }
        }
        if (bestTarget < 0) return null
        val path = ArrayList<Int>()
        var cur = bestTarget
        while (cur >= 0) { path.add(cur); cur = prev[cur] }
        return path.reversed()
    }

    /** Entfernt Punkte, die näher als 30 m am vorigen liegen (weniger zu zeichnen und zu rechnen). */
    private fun thin(points: List<LatLon>): List<LatLon> {
        if (points.size <= 2) return points
        val out = ArrayList<LatLon>()
        out.add(points.first())
        for (i in 1 until points.lastIndex) {
            if (CorridorGeometry.distanceKm(out.last(), points[i]) >= 0.03) out.add(points[i])
        }
        out.add(points.last())
        return out
    }
}
