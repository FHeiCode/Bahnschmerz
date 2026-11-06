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
            // Weichenverbindungen (service=crossover) werden gebraucht: Hauptstrecken sind oft je
            // Richtung ein eigenes Gleis, verbunden nur über Weichen. Abstell-, Rangier- und
            // Anschlussgleise bleiben draußen.
            "way[\"railway\"~\"^(rail|light_rail|narrow_gauge)$\"][\"service\"!~\"^(yard|siding|spur)$\"](around:$radiusM,$line);" +
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
        val snapped = stations.map { (_, p) -> candidates(graph, p) }
        val result = HashMap<Int, List<LatLon>>()
        for (k in 0 until stations.size - 1) {
            val from = snapped[k] ?: continue
            val to = snapped[k + 1] ?: continue
            val straight = CorridorGeometry.distanceKm(stations[k].second, stations[k + 1].second)
            val path = shortestPath(graph, from, to, maxKm = straight * 3 + 5) ?: continue
            if (path.size < 2) continue
            val length = path.zipWithNext { x, y -> CorridorGeometry.distanceKm(graph.point(x), graph.point(y)) }.sum()
            // Umwege über Nebenstrecken oder falsche Gleise erkennen.
            if (length > straight * 2.5 + 3) continue
            // Nur Punkte auf dem Gleis – keine Bahnhofskoordinate, die neben dem Gleis liegen kann.
            result[stations[k].first] = thin(path.map { graph.point(it) })
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
