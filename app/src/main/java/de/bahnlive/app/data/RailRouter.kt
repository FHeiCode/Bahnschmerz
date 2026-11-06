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

    /** Overpass-Abfrage für Gleise in der Nähe der Linie durch alle Bahnhöfe. */
    fun overpassQuery(stations: List<LatLon>): String {
        val maxSegmentKm = stations.zipWithNext { a, b -> CorridorGeometry.distanceKm(a, b) }.maxOrNull() ?: 0.0
        // Puffer um die gerade Verbindungslinie: lange Abschnitte weichen stärker davon ab.
        val radiusM = (maxSegmentKm * 120).coerceIn(1500.0, 8000.0).toInt()
        val line = stations.joinToString(",") { "%.5f,%.5f".format(java.util.Locale.ROOT, it.lat, it.lon) }
        return "[out:json][timeout:60];" +
            "way[\"railway\"~\"^(rail|light_rail|narrow_gauge)$\"][\"service\"!~\".\"](around:$radiusM,$line);" +
            "out geom;"
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
        val snapped = stations.map { (_, p) -> nearest(graph, p) }
        val result = HashMap<Int, List<LatLon>>()
        for (k in 0 until stations.size - 1) {
            val from = snapped[k] ?: continue
            val to = snapped[k + 1] ?: continue
            val a = stations[k].second
            val b = stations[k + 1].second
            val straight = CorridorGeometry.distanceKm(a, b)
            val path = shortestPath(graph, from, to, maxKm = straight * 3 + 5) ?: continue
            val length = path.zipWithNext { x, y -> CorridorGeometry.distanceKm(graph.point(x), graph.point(y)) }.sum()
            // Umwege über Nebenstrecken oder falsche Gleise erkennen.
            if (length > straight * 2.5 + 3) continue
            result[stations[k].first] = thin(listOf(a) + path.map { graph.point(it) } + b)
        }
        return result
    }

    private fun nearest(graph: RailGraph, p: LatLon): Int? {
        val kx = cos(Math.toRadians(p.lat)) * 111.32
        var best = -1
        var bestD = Double.MAX_VALUE
        for (i in 0 until graph.size) {
            val dx = (graph.lon[i] - p.lon) * kx
            val dy = (graph.lat[i] - p.lat) * 110.57
            val d = dx * dx + dy * dy
            if (d < bestD) { bestD = d; best = i }
        }
        return best.takeIf { it >= 0 && bestD <= MAX_SNAP_KM * MAX_SNAP_KM }
    }

    private fun shortestPath(graph: RailGraph, from: Int, to: Int, maxKm: Double): List<Int>? {
        if (from == to) return listOf(from)
        val dist = DoubleArray(graph.size) { Double.MAX_VALUE }
        val prev = IntArray(graph.size) { -1 }
        val queue = PriorityQueue<Pair<Double, Int>>(compareBy { it.first })
        dist[from] = 0.0
        queue.add(0.0 to from)
        while (queue.isNotEmpty()) {
            val (d, u) = queue.poll()
            if (d > dist[u]) continue
            if (u == to) break
            if (d > maxKm) return null
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
        if (prev[to] < 0) return null
        val path = ArrayList<Int>()
        var cur = to
        while (cur >= 0) { path.add(cur); if (cur == from) break; cur = prev[cur] }
        return if (path.last() == from) path.reversed() else null
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
