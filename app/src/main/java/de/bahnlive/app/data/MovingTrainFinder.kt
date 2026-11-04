package de.bahnlive.app.data

import java.time.LocalDateTime

/**
 * Findet alle Züge, die gerade auf der Strecke unterwegs sind.
 *
 * Grundlage sind die Tafeln mehrerer Bahnhöfe der Strecke (Start, Ziel, einige Zwischenhalte).
 * Jede Tafel deckt etwa die letzte Stunde und die nächsten drei Stunden ab. Ein Zug, der vor
 * vier Stunden am Start losgefahren ist, steht also nicht mehr auf der Starttafel – aber auf der
 * Tafel eines Zwischenhalts oder des Ziels. Die Einträge desselben Zuges (gleiche Zugnummer)
 * werden zusammengeführt; daraus ergeben sich Richtung und Zeitpunkte entlang der Strecke.
 */
object MovingTrainFinder {

    class StationBoard(val index: Int, val entries: List<BoardEntry>)

    private class Obs(val index: Int, val entry: BoardEntry, val routeIndices: List<Int>)

    /** [viaIndex]: Position des Via-Bahnhofs im Korridor, falls eine Streckenführung gewählt ist. */
    fun find(
        corridor: List<String>,
        boards: List<StationBoard>,
        now: LocalDateTime,
        viaIndex: Int? = null,
    ): List<MovingTrain> {
        if (corridor.size < 2) return emptyList()
        val indexCache = HashMap<String, Int>()
        fun indexOf(name: String) = indexCache.getOrPut(name) {
            corridor.indexOfFirst { RouteRepository.sameStation(it, name) }
        }
        val fetched = boards.map { it.index }.toSet()

        val groups = LinkedHashMap<String, MutableList<Obs>>()
        for (board in boards) {
            for (e in board.entries) {
                val routeIdx = e.route.map { indexOf(it.name) }
                // Nur Züge, die mindestens einen weiteren Bahnhof der Strecke anfahren.
                if (routeIdx.none { it >= 0 && it != board.index }) continue
                // Ohne Zugnummer lassen sich Einträge nicht sicher zusammenführen ("RE 1" ist eine Linie).
                val key = e.trainNumber?.let { "n$it" }
                    ?: "s${board.index}-${e.train}-${e.scheduledArrival}-${e.scheduledDeparture}"
                groups.getOrPut(key) { mutableListOf() }.add(Obs(board.index, e, routeIdx))
            }
        }
        return groups.mapNotNull { (key, obs) -> assemble(key, obs, fetched, now, viaIndex) }
    }

    private fun assemble(
        key: String,
        all: List<Obs>,
        fetched: Set<Int>,
        now: LocalDateTime,
        viaIndex: Int?,
    ): MovingTrain? {
        val obs = all.filterNot { it.entry.isCancelled }
        if (obs.isEmpty()) return null

        val dir = direction(obs, now)
        if (dir == 0) return null

        // Alle Bahnhöfe der Strecke, die der Zug laut Laufweg berührt.
        val onPath = (obs.map { it.index } + obs.flatMap { o -> o.routeIndices.filter { it >= 0 } }).toSet()
        val entry = if (dir > 0) onPath.min() else onPath.max()
        val exit = if (dir > 0) onPath.max() else onPath.min()
        if (entry == exit) return null

        // Gewählte Streckenführung: Ein Zug, der Bahnhöfe vor und hinter dem Via-Bahnhof
        // berührt, ihn selbst aber auslässt, fährt über eine andere Strecke.
        if (viaIndex != null && onPath.min() < viaIndex && onPath.max() > viaIndex && viaIndex !in onPath) {
            return null
        }

        val points = obs.flatMap { o ->
            val e = o.entry
            buildList {
                // Am Einfahrtsbahnhof zählt erst die Abfahrt, am Ausfahrtsbahnhof nur die Ankunft.
                if (o.index != entry) {
                    e.scheduledArrival?.let { RouteRepository.nearest(it, now) }?.let {
                        add(TimePoint(o.index, it.plusMinutes((e.delayArrival ?: 0).toLong()), e.delayArrival))
                    }
                }
                if (o.index != exit) {
                    e.scheduledDeparture?.let { RouteRepository.nearest(it, now) }?.let {
                        add(TimePoint(o.index, it.plusMinutes((e.delayDeparture ?: 0).toLong()), e.delayDeparture))
                    }
                }
            }
        }.sortedBy { it.time }
        if (points.isEmpty()) return null

        // Sicher nicht (mehr) unterwegs: Strecke schon verlassen oder noch nicht erreicht.
        points.lastOrNull { it.index == exit }?.let { if (!it.time.isAfter(now)) return null }
        points.firstOrNull { it.index == entry }?.let { if (it.time.isAfter(now)) return null }

        val ref = obs.maxBy { it.entry.route.size }.entry
        return MovingTrain(
            key = key,
            train = ref.train,
            trainNumber = ref.trainNumber,
            destination = ref.destination ?: "",
            direction = dir,
            entryIndex = entry,
            exitIndex = exit,
            points = points,
            entryBoardFetched = entry in fetched,
            exitBoardFetched = exit in fetched,
            hasRealtime = obs.any { !it.entry.missingRealtime },
            messages = obs.flatMap { it.entry.messages }.distinct(),
        )
    }

    /** +1 = Richtung Ziel, -1 = Richtung Start, 0 = nicht bestimmbar. */
    private fun direction(obs: List<Obs>, now: LocalDateTime): Int {
        // 1. Der Laufweg ist in Fahrtrichtung sortiert: Reihenfolge zweier Streckenbahnhöfe.
        var votes = 0
        for (o in obs) {
            val seq = o.routeIndices.filter { it >= 0 }
            if (seq.size >= 2 && seq.first() != seq.last()) votes += Integer.signum(seq.last() - seq.first())
        }
        if (votes != 0) return Integer.signum(votes)

        // 2. Zeiten an verschiedenen Bahnhöfen der Strecke.
        val timed = obs.mapNotNull { o -> anyTime(o.entry, now)?.let { o.index to it } }.sortedBy { it.second }
        val first = timed.firstOrNull()
        val last = timed.lastOrNull { it.first != first?.first }
        if (first != null && last != null) return Integer.signum(last.first - first.first)

        // 3. Nur ein weiterer Streckenbahnhof bekannt: Endet oder beginnt der Zug hier?
        for (o in obs) {
            val other = o.routeIndices.firstOrNull { it >= 0 && it != o.index } ?: continue
            if (o.entry.scheduledDeparture == null) return Integer.signum(o.index - other) // kommt von dort
            if (o.entry.scheduledArrival == null) return Integer.signum(other - o.index)   // fährt dorthin
        }
        return 0
    }

    private fun anyTime(e: BoardEntry, now: LocalDateTime): LocalDateTime? =
        (e.scheduledDeparture ?: e.scheduledArrival)?.let { RouteRepository.nearest(it, now) }
}
