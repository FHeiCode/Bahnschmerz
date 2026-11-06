package de.bahnlive.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.time.Duration
import java.time.LocalDateTime

/** Quelle für Fahrtverläufe – im Betrieb die DB-Schnittstelle, im Test ein Ersatz. */
interface TripSource {
    suspend fun station(name: String): VendoStation?
    suspend fun arrivals(eva: String): List<VendoBoardEntry>
    suspend fun trip(tripId: String): VendoTrip?
}

/**
 * Verfeinert die unterwegs befindlichen Züge mit dem kompletten DB-Fahrtverlauf:
 * Echtzeit an jedem Halt statt nur an den sechs abgefragten Bahnhöfen.
 *
 * Klappt etwas nicht (Schnittstelle nicht erreichbar, Zug nicht gefunden, unerwartetes
 * Format), bleibt der Zug unverändert – die bisherige Schätzung ist der Rückfall.
 */
class TripEnricher(private val source: TripSource) {

    data class Result(
        val moving: List<MovingTrain>,
        /** Bahnhofskoordinaten aus dem Fahrtverlauf, Schlüssel = Name wie im Korridor. */
        val coordinates: Map<String, LatLon>,
    )

    suspend fun enrich(corridor: List<String>, moving: List<MovingTrain>, now: LocalDateTime): Result = coroutineScope {
        if (corridor.size < 2 || moving.isEmpty()) return@coroutineScope Result(moving, emptyMap())

        // Zuglauf-IDs über die Ankunftstafeln von Start und Ziel: Dort kommen die Züge
        // beider Richtungen als Nächstes an.
        val ends = listOf(corridor.first(), corridor.last())
            .map { async { safe { source.station(it) } } }.awaitAll()
        val boards = ends.filterNotNull()
            .map { st -> async { safe { source.arrivals(st.eva) }.orEmpty() } }.awaitAll()
        val tripIds = HashMap<String, String>()
        boards.flatten().forEach { e -> e.trainNumber?.let { tripIds.putIfAbsent(it, e.tripId) } }

        val wanted = moving.filter { it.trainNumber != null && it.trainNumber in tripIds }.take(MAX_TRIPS)
        val trips = wanted.map { t -> async { t.key to safe { source.trip(tripIds.getValue(t.trainNumber!!)) } } }
            .awaitAll().toMap()

        val coordinates = HashMap<String, LatLon>()
        ends[0]?.position?.let { coordinates[corridor.first()] = it }
        ends[1]?.position?.let { coordinates[corridor.last()] = it }

        val result = moving.map { t ->
            val trip = trips[t.key] ?: return@map t
            trip.stops.forEach { s ->
                val idx = indexIn(corridor, s.name)
                if (idx >= 0 && s.position != null) coordinates.putIfAbsent(corridor[idx], s.position)
            }
            apply(t, trip, corridor, now) ?: t
        }
        Result(result, coordinates)
    }

    private suspend fun <T> safe(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    companion object {
        /** Höchstens so viele Fahrtverläufe pro Aktualisierung (schont die Schnittstelle). */
        const val MAX_TRIPS = 12

        private fun indexIn(corridor: List<String>, name: String) =
            corridor.indexOfFirst { RouteRepository.sameStation(it, name) }

        /**
         * Ersetzt die Zeitpunkte eines Zuges durch die aus dem Fahrtverlauf.
         * Null, wenn der Fahrtverlauf nicht zum Zug passt (dann bleibt die Schätzung).
         */
        fun apply(t: MovingTrain, trip: VendoTrip, corridor: List<String>, now: LocalDateTime): MovingTrain? {
            val matched = trip.stops.mapNotNull { s -> indexIn(corridor, s.name).takeIf { it >= 0 }?.let { it to s } }
            if (matched.size < 2) return null
            // Die Reihenfolge der Halte muss zur erkannten Fahrtrichtung passen.
            val dir = Integer.signum(matched.last().first - matched.first().first)
            if (dir != t.direction) return null

            val entry = matched.first().first
            val exit = matched.last().first
            var anyRealtime = false
            val points = matched.filterNot { it.second.cancelled }.flatMap { (i, s) ->
                buildList {
                    if (i != entry) s.arrivalPlanned?.let { p ->
                        s.arrivalRealtime?.let { anyRealtime = true }
                        add(TimePoint(i, s.arrivalRealtime ?: p, s.arrivalRealtime?.let { minutes(p, it) }))
                    }
                    if (i != exit) s.departurePlanned?.let { p ->
                        s.departureRealtime?.let { anyRealtime = true }
                        add(TimePoint(i, s.departureRealtime ?: p, s.departureRealtime?.let { minutes(p, it) }))
                    }
                }
            }.sortedBy { it.time }
            if (points.size < 2) return null

            // Ist der Zug laut Fahrtverlauf schon angekommen oder noch nicht losgefahren,
            // blendet ihn die Positionsschätzung aus (now liegt außerhalb der Zeitpunkte).
            return t.copy(
                entryIndex = entry,
                exitIndex = exit,
                points = points,
                entryBoardFetched = true,
                exitBoardFetched = true,
                hasRealtime = anyRealtime || t.hasRealtime,
                detailed = true,
            )
        }

        private fun minutes(planned: LocalDateTime, actual: LocalDateTime): Int =
            Duration.between(planned, actual).toMinutes().toInt()
    }
}
