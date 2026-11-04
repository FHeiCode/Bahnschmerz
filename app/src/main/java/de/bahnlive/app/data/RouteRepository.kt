package de.bahnlive.app.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.io.IOException
import java.text.Normalizer
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

/**
 * Ermittelt für eine Strecke A → B
 * - alle kommenden Züge, die in A abfahren und B anfahren (mit Echtzeit-Ankunft in B), und
 * - alle Züge, die gerade irgendwo zwischen A und B unterwegs sind, in beiden Richtungen.
 */
class RouteRepository(private val api: DbfApi = DbfApi()) {

    /** Zuletzt bestimmte Bahnhofsfolge je Strecke – bleibt stabil, damit dieselben Tafeln abgefragt werden. */
    private val corridors = mutableMapOf<String, List<String>>()

    suspend fun connections(route: SavedRoute, now: LocalDateTime = LocalDateTime.now()): BoardResult =
        try {
            coroutineScope {
                val originBoard = async { api.board(route.from) }
                // Die Zieltafel ist optional – sie liefert Ankunftszeit und -gleis.
                val destBoard = async { optional { api.board(route.to) } }
                val a = originBoard.await()
                val b = destBoard.await()

                val forward = build(route, a, b.orEmpty(), now)
                // Gegenrichtung aus denselben zwei Tafeln – keine zusätzlichen Anfragen.
                val backward = build(route.reversed(), b.orEmpty(), a, now)

                // Bahnhofsfolge der Strecke und einige Zwischenbahnhöfe als zusätzliche Messpunkte.
                val corridor = corridorFor(route, forward, backward)
                val viaIndex = route.via?.takeIf { it.isNotBlank() }
                    ?.let { via -> corridor.indexOfFirst { sameStation(it, via) } }
                    ?.takeIf { it > 0 }
                val extra = sampleIndices(corridor, forward + backward, viaIndex).map { i ->
                    async { optional { api.board(corridor[i], optional = true) }?.let { MovingTrainFinder.StationBoard(i, it) } }
                }
                val boards = buildList {
                    add(MovingTrainFinder.StationBoard(0, a))
                    b?.let { add(MovingTrainFinder.StationBoard(corridor.lastIndex, it)) }
                    extra.forEach { job -> job.await()?.let { add(it) } }
                }

                BoardResult.Success(
                    connections = forward
                        .filter { !it.departureExpected.isBefore(now.minusMinutes(1)) }
                        .sortedBy { it.departureExpected },
                    moving = MovingTrainFinder.find(corridor, boards, now, viaIndex),
                    corridor = corridor,
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

        // Streckenführung: Der Zug muss zwischen Start und Ziel über den Via-Bahnhof fahren.
        route.via?.takeIf { it.isNotBlank() }?.let { via ->
            val viaIndex = dep.route.indexOfFirst { sameStation(it.name, via) }
            if (viaIndex < 0) return@mapNotNull null
            if (originIndex >= 0 && viaIndex <= originIndex) return@mapNotNull null
            if (destIndex >= 0 && viaIndex >= destIndex) return@mapNotNull null
        }

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

    /** Fehler einer Zusatztafel brechen nicht alles ab – die Tafel fehlt dann einfach. */
    private suspend fun <T> optional(block: suspend () -> T): T? =
        try {
            block()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }

    /**
     * Bahnhofsfolge A → B: der Laufweg des Zuges mit den meisten Zwischenhalten
     * (meist ein Regionalzug, der überall hält).
     */
    private fun corridorFor(route: SavedRoute, forward: List<Connection>, backward: List<Connection>): List<String> {
        val candidates =
            forward.map { c -> listOf(route.from) + c.stopsBetween.map { it.name } + route.to } +
                backward.map { c -> (listOf(route.to) + c.stopsBetween.map { it.name } + route.from).reversed() }
        val best = candidates.maxByOrNull { it.size }
            ?: listOfNotNull(route.from, route.via?.takeIf { it.isNotBlank() }, route.to)
        synchronized(corridors) {
            val previous = corridors[route.id]
            val chosen = if (previous != null && previous.size >= best.size) previous else best
            corridors[route.id] = chosen
            return chosen
        }
    }

    /**
     * Wählt bis zu [MAX_EXTRA_BOARDS] Zwischenbahnhöfe, gleichmäßig über die Strecke verteilt.
     * Pro Abschnitt der Bahnhof, an dem die meisten Züge halten (dort halten auch Fernzüge).
     */
    private fun sampleIndices(corridor: List<String>, connections: List<Connection>, viaIndex: Int?): List<Int> {
        val interior = (1 until corridor.lastIndex).toList()
        if (interior.isEmpty()) return emptyList()
        // Der Via-Bahnhof wird immer abgefragt: Dort fahren alle Züge der gewählten Führung durch.
        val fixed = listOfNotNull(viaIndex?.takeIf { it in interior })
        val bins = minOf(MAX_EXTRA_BOARDS - fixed.size, interior.size)
        if (bins <= 0) return fixed
        val score = interior.associateWith { i ->
            connections.count { c -> c.stopsBetween.any { sameStation(it.name, corridor[i]) } }
        }
        val spread = (0 until bins).mapNotNull { bin ->
            interior.subList(bin * interior.size / bins, (bin + 1) * interior.size / bins)
                .filterNot { it in fixed }
                .maxByOrNull { score[it] ?: 0 }
        }
        return (fixed + spread).distinct().take(MAX_EXTRA_BOARDS)
    }

    private fun sameTrain(a: BoardEntry, b: BoardEntry): Boolean =
        if (a.trainNumber != null && b.trainNumber != null) a.trainNumber == b.trainNumber
        else normalize(a.train) == normalize(b.train)

    companion object {
        /** Start + Ziel + 4 Zwischenbahnhöfe = 6 Anfragen pro Minute (DBF erlaubt 30). */
        private const val MAX_EXTRA_BOARDS = 4

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
