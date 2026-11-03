package de.bahnlive.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Ermittelt für eine Strecke A → B alle Züge, die in A abfahren und B
 * später auf ihrem Laufweg haben, und ergänzt die Echtzeit-Ankunft in B.
 */
class RouteRepository(private val api: DbfApi = DbfApi()) {

    suspend fun connections(route: SavedRoute, now: LocalDateTime = LocalDateTime.now()): BoardResult =
        try {
            coroutineScope {
                val originBoard = async { api.board(route.from) }
                // Die Zieltafel ist optional – sie liefert nur Ankunftszeit und -gleis.
                val destBoard = async { runCatching { api.board(route.to) }.getOrNull() }
                val a = originBoard.await()
                val b = destBoard.await().orEmpty()

                val forward = build(route, a, b, now)
                // Gegenrichtung aus denselben zwei Tafeln – keine zusätzlichen Anfragen.
                val backward = build(route.reversed(), b, a, now)

                BoardResult.Success(
                    connections = forward
                        .filter { !it.departureExpected.isBefore(now.minusMinutes(1)) }
                        .sortedBy { it.departureExpected },
                    moving = (forward + backward).filter { it.isMovingAt(now) },
                    fetchedAt = now,
                )
            }
        } catch (e: StationException) {
            BoardResult.Error(e.message ?: "Bahnhof nicht gefunden.")
        } catch (e: IOException) {
            BoardResult.Error("Keine Verbindung zum Datendienst: ${e.message ?: "unbekannter Fehler"}")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            BoardResult.Error("Daten konnten nicht verarbeitet werden: ${e.message ?: e.javaClass.simpleName}")
        }

    private fun build(
        route: SavedRoute,
        origin: List<BoardEntry>,
        dest: List<BoardEntry>,
        now: LocalDateTime,
    ): List<Connection> = origin.mapNotNull { dep ->
        val depTime = dep.scheduledDeparture?.let { nearest(it, now) } ?: return@mapNotNull null

        val destIndex = dep.route.indexOfFirst { sameStation(it.name, route.to) }
        val destIsFinal = dep.destination?.let { sameStation(it, route.to) } == true
        if (destIndex < 0 && !destIsFinal) return@mapNotNull null

        val originIndex = dep.route.indexOfFirst { sameStation(it.name, route.from) }
        if (originIndex >= 0 && destIndex in 0 until originIndex) return@mapNotNull null

        // Passende Zeile auf der Zieltafel (gleicher Zug, Ankunft nach Abfahrt).
        val arrival = dest
            .filter { it.scheduledArrival != null && sameTrain(it, dep) }
            .mapNotNull { e -> after(e.scheduledArrival!!, depTime)?.let { e to it } }
            .minByOrNull { it.second }

        // Zug kam vom Ziel her (Laufweg enthält auch vorherige Halte)? Dann überspringen.
        if (arrival == null && dest.any { e ->
                sameTrain(e, dep) && e.scheduledDeparture != null &&
                    nearest(e.scheduledDeparture, depTime)?.isBefore(depTime) == true
            }
        ) return@mapNotNull null

        val stops = dep.route.let { r ->
            val start = if (originIndex >= 0) originIndex + 1 else 0
            val end = if (destIndex >= 0) destIndex else r.size
            if (start < end) r.subList(start, end) else emptyList()
        }

        Connection(
            from = route.from,
            to = route.to,
            train = dep.train,
            trainNumber = dep.trainNumber,
            destination = dep.destination ?: route.to,
            departurePlanned = depTime,
            departureDelay = dep.delayDeparture,
            departurePlatform = dep.platform,
            departurePlatformPlanned = dep.scheduledPlatform,
            arrivalPlanned = arrival?.second,
            arrivalDelay = arrival?.first?.delayArrival,
            arrivalPlatform = arrival?.first?.platform,
            arrivalPlatformPlanned = arrival?.first?.scheduledPlatform,
            isCancelled = dep.isCancelled,
            destinationStopCancelled = (destIndex >= 0 && dep.route[destIndex].isCancelled) ||
                arrival?.first?.isCancelled == true,
            hasRealtime = !dep.missingRealtime,
            stopsBetween = stops,
            messages = (dep.messages + arrival?.first?.messages.orEmpty()).distinct(),
        )
    }

    /** Zug hat A verlassen, B aber noch nicht erreicht (nach Echtzeit). */
    private fun Connection.isMovingAt(now: LocalDateTime): Boolean {
        if (isCancelled) return false
        val arr = arrivalExpected ?: return false
        return !departureExpected.isAfter(now) && arr.isAfter(now)
    }

    private fun sameTrain(a: BoardEntry, b: BoardEntry): Boolean =
        if (a.trainNumber != null && b.trainNumber != null) a.trainNumber == b.trainNumber
        else normalize(a.train) == normalize(b.train)

    companion object {
        private val HHMM = DateTimeFormatter.ofPattern("H:mm")

        private fun parse(hhmm: String): LocalTime? =
            runCatching { LocalTime.parse(hhmm.trim(), HHMM) }.getOrNull()

        /** "HH:mm" → Zeitpunkt, der dem Bezugszeitpunkt am nächsten liegt (Tageswechsel). */
        fun nearest(hhmm: String, ref: LocalDateTime): LocalDateTime? {
            val t = parse(hhmm) ?: return null
            return listOf(-1L, 0L, 1L)
                .map { ref.toLocalDate().plusDays(it).atTime(t) }
                .minBy { kotlin.math.abs(java.time.Duration.between(ref, it).toMinutes()) }
        }

        /** "HH:mm" → erster Zeitpunkt ab [ref] innerhalb von 12 Stunden. */
        fun after(hhmm: String, ref: LocalDateTime): LocalDateTime? {
            val t = parse(hhmm) ?: return null
            var candidate = ref.toLocalDate().atTime(t)
            if (candidate.isBefore(ref)) candidate = candidate.plusDays(1)
            return candidate.takeIf { java.time.Duration.between(ref, it).toHours() < 12 }
        }

        fun normalize(s: String): String =
            Normalizer.normalize(s.lowercase(), Normalizer.Form.NFC)
                .replace("ß", "ss")
                .replace(Regex("[()\\[\\],.\\-/]"), " ")
                .replace(Regex("\\bhauptbahnhof\\b"), "hbf")
                .replace(Regex("\\s+"), " ")
                .trim()

        /** Vergleicht Bahnhofsnamen tolerant: "Köln" passt zu "Köln Hbf", nicht zu "Kölner Str". */
        fun sameStation(a: String, b: String): Boolean {
            val x = normalize(a)
            val y = normalize(b)
            if (x.isEmpty() || y.isEmpty()) return false
            if (x == y) return true
            val compactX = x.replace(" ", "")
            val compactY = y.replace(" ", "")
            if (compactX == compactY) return true
            return x.startsWith("$y hbf") || y.startsWith("$x hbf")
        }
    }
}
