package de.bahnlive.app.data

import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** Aktueller Zustand eines Zuges auf der Strecke. */
data class TrainStatus(
    val train: MovingTrain,
    /** Geschätzte Position; null, solange Bahnhofskoordinaten fehlen. */
    val position: LatLon?,
    /** Zurückgelegter Anteil seines Weges auf dieser Strecke (0..1), falls berechenbar. */
    val progress: Double?,
    /** Aktuellste Verspätung (Prognose für den nächsten Halt). */
    val delay: Int?,
    /** Nächster Bahnhof, für den eine Zeit bekannt ist. */
    val next: TimePoint?,
)

/**
 * Geometrie der Strecke als Linienzug. Zwischen zwei Bahnhöfen verläuft sie entlang der
 * echten Gleise (OpenStreetMap), sofern bekannt, sonst als gerade Linie. Bahnhöfe ohne
 * Koordinaten bekommen eine Kilometerposition zwischen ihren Nachbarn.
 */
class CorridorGeometry private constructor(
    /** Punkte des Linienzugs (Gleisverlauf). */
    val points: List<LatLon>,
    private val cumulative: List<Double>,
    private val stationKm: DoubleArray,
    /** Anteil der Abschnitte, die echten Gleisen folgen (0..1). */
    val trackShare: Double,
) {
    /** Kilometer ab Start für Bahnhof [index]. */
    fun km(index: Int): Double = stationKm[index.coerceIn(0, stationKm.lastIndex)]

    fun pointAt(km: Double): LatLon {
        val d = km.coerceIn(0.0, cumulative.last())
        // Binäre Suche: Gleisverläufe haben oft Tausende Punkte.
        var lo = 0
        var hi = points.lastIndex
        while (hi - lo > 1) {
            val mid = (lo + hi) / 2
            if (cumulative[mid] <= d) lo = mid else hi = mid
        }
        if (hi == 0) return points.first()
        val a = cumulative[lo]
        val b = cumulative[hi]
        val t = if (b > a) ((d - a) / (b - a)).coerceIn(0.0, 1.0) else 0.0
        val p = points[lo]
        val q = points[hi]
        return LatLon(p.lat + (q.lat - p.lat) * t, p.lon + (q.lon - p.lon) * t)
    }

    companion object {
        /**
         * [track]: Gleisverlauf je Abschnitt, Schlüssel = Korridor-Index des Abschnittsbeginns
         * (siehe [RailRouter.segments]). Passt ein Abschnitt nicht zu den Koordinaten, wird er
         * ignoriert und die gerade Linie genommen.
         */
        fun build(
            corridor: List<String>,
            coords: (String) -> LatLon?,
            track: Map<Int, List<LatLon>> = emptyMap(),
            /** Ersatz-Gleisverlauf (z. B. aus dem DB-Fahrtverlauf), wenn [track] nicht passt. */
            fallback: Map<Int, List<LatLon>> = emptyMap(),
        ): CorridorGeometry? {
            if (corridor.size < 2) return null
            val raw = corridor.map(coords)
            // Start und Ziel braucht es mindestens, sonst ist die Lage unklar.
            if (raw.first() == null || raw.last() == null) return null
            val known = raw.indices.filter { raw[it] != null }

            val points = ArrayList<LatLon>()
            val cumulative = ArrayList<Double>()
            fun add(p: LatLon) {
                cumulative.add(if (points.isEmpty()) 0.0 else cumulative.last() + distanceKm(points.last(), p))
                points.add(p)
            }
            val stationKm = DoubleArray(corridor.size)
            var onTrack = 0
            for (k in 0 until known.lastIndex) {
                val a = known[k]
                val b = known[k + 1]
                val pa = raw[a]!!
                val pb = raw[b]!!
                // Gleisabschnitt nur nehmen, wenn er wirklich zwischen diesen beiden Bahnhöfen liegt.
                fun fits(it: List<LatLon>) =
                    it.size >= 2 && distanceKm(it.first(), pa) < 2.0 && distanceKm(it.last(), pb) < 2.0
                val seg = track[a]?.takeIf(::fits) ?: fallback[a]?.takeIf(::fits)
                if (seg != null) onTrack++
                val line = seg ?: listOf(pa, pb)
                // Der Abschnitt beginnt am Gleis beim Bahnhof (nicht an der Bahnhofskoordinate,
                // die oft neben dem Gleis liegt). Kleine Sprünge zwischen Abschnitten entstehen,
                // wenn der nächste Abschnitt am anderen Richtungsgleis beginnt.
                val startKm = if (points.isEmpty()) 0.0 else cumulative.last()
                line.forEach(::add)
                val endKm = cumulative.last()
                if (k == 0) stationKm[a] = 0.0
                stationKm[b] = endKm
                for (i in a + 1 until b) stationKm[i] = startKm + (endKm - startKm) * (i - a) / (b - a)
            }
            val share = if (known.size > 1) onTrack.toDouble() / (known.size - 1) else 0.0
            return CorridorGeometry(points, cumulative, stationKm, share)
        }

        fun distanceKm(a: LatLon, b: LatLon): Double {
            val r = 6371.0
            val dLat = Math.toRadians(b.lat - a.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
            return 2 * r * atan2(sqrt(h), sqrt(1 - h))
        }
    }
}

/**
 * Schätzt, wo ein Zug gerade ist. Die DB-Daten enthalten keine GPS-Positionen, nur Zeiten an
 * Bahnhöfen. Zwischen zwei bekannten Zeitpunkten wird gleichmäßig interpoliert. Fehlt ein
 * Zeitpunkt (Zug liegt außerhalb des Tafelfensters oder hält nicht an einem abgefragten
 * Bahnhof), wird mit der Geschwindigkeit des Zuges hochgerechnet.
 */
object PositionEstimator {
    /** Zeitfenster der DBF-Tafeln (mit etwas Sicherheitsabstand). */
    private const val WINDOW_PAST_MIN = 55L
    private const val WINDOW_FUTURE_MIN = 175L

    fun status(t: MovingTrain, geo: CorridorGeometry?, now: LocalDateTime): TrainStatus? {
        val next = t.points.firstOrNull { it.time.isAfter(now) }
        // Aktuellste bekannte Verspätung: nächste Prognose, sonst die zuletzt gemessene.
        val delay = t.points.firstOrNull { it.time.isAfter(now) && it.delay != null }?.delay
            ?: t.points.lastOrNull { !it.time.isAfter(now) && it.delay != null }?.delay
        if (!roughlyMoving(t, now)) return null
        if (geo == null) return TrainStatus(t, null, null, delay, next)

        val timeline = timeline(t, geo, now)
        val k = timeline.indexOfLast { !it.time.isAfter(now) }
        if (k < 0 || k == timeline.lastIndex) return null
        val a = timeline[k]
        val b = timeline[k + 1]
        val span = Duration.between(a.time, b.time).seconds
        val f = if (span > 0) (Duration.between(a.time, now).seconds.toDouble() / span).coerceIn(0.0, 1.0) else 0.0
        val km = geo.km(a.index) + (geo.km(b.index) - geo.km(a.index)) * f

        val kmEntry = geo.km(t.entryIndex)
        val kmExit = geo.km(t.exitIndex)
        val progress = if (kmExit != kmEntry) ((km - kmEntry) / (kmExit - kmEntry)).coerceIn(0.0, 1.0) else null
        return TrainStatus(t, geo.pointAt(km), progress, delay, next)
    }

    /** Reine Zeitprüfung ohne Koordinaten: Ist der Zug zwischen Ein- und Ausfahrt? */
    private fun roughlyMoving(t: MovingTrain, now: LocalDateTime): Boolean {
        val anyPast = t.points.any { !it.time.isAfter(now) }
        val anyFuture = t.points.any { it.time.isAfter(now) }
        return when {
            anyPast && anyFuture -> true
            // Nur künftige Zeiten: unterwegs, wenn er die Einfahrt schon vor dem Tafelfenster passiert hat.
            anyFuture -> t.entryBoardFetched || t.points.first().index != t.entryIndex
            // Nur vergangene Zeiten: unterwegs, wenn die Ausfahrt erst nach dem Tafelfenster kommt.
            else -> t.exitBoardFetched || t.points.last().index != t.exitIndex
        }
    }

    /** Beobachtete Zeitpunkte, ergänzt um hochgerechnete Ein- und Ausfahrt. */
    private fun timeline(t: MovingTrain, geo: CorridorGeometry, now: LocalDateTime): List<TimePoint> {
        val pts = t.points.toMutableList()
        val speed = speedKmh(t, geo)

        val first = pts.first()
        if (first.index != t.entryIndex) {
            var time = first.time.minusSeconds(seconds(abs(geo.km(first.index) - geo.km(t.entryIndex)), speed))
            // Stand der Zug nicht auf der Tafel des Einfahrtsbahnhofs, war er vor dem Tafelfenster dort.
            if (t.entryBoardFetched) time = minOf(time, now.minusMinutes(WINDOW_PAST_MIN))
            pts.add(0, TimePoint(t.entryIndex, time, null))
        }
        val last = pts.last()
        if (last.index != t.exitIndex) {
            var time = last.time.plusSeconds(seconds(abs(geo.km(t.exitIndex) - geo.km(last.index)), speed))
            if (t.exitBoardFetched) time = maxOf(time, now.plusMinutes(WINDOW_FUTURE_MIN))
            pts.add(TimePoint(t.exitIndex, time, null))
        }
        return pts
    }

    private fun seconds(km: Double, kmh: Double): Long = (km / kmh * 3600).roundToLong()

    /** Geschwindigkeit aus zwei beobachteten Bahnhöfen, sonst typischer Wert der Zuggattung. */
    private fun speedKmh(t: MovingTrain, geo: CorridorGeometry): Double {
        val first = t.points.first()
        val last = t.points.lastOrNull { it.index != first.index }
        if (last != null) {
            val km = abs(geo.km(last.index) - geo.km(first.index))
            val hours = Duration.between(first.time, last.time).seconds / 3600.0
            if (km > 1.0 && hours > 0.0) return (km / hours).coerceIn(30.0, 250.0)
        }
        return defaultSpeed(t.train)
    }

    /** Luftlinien-Geschwindigkeit (Strecke zwischen Bahnhöfen als Gerade, inkl. Halte). */
    fun defaultSpeed(train: String): Double {
        val type = train.trim().takeWhile { it.isLetter() }.uppercase()
        return when (type) {
            "ICE", "ECE" -> 140.0
            "IC", "EC", "RJ", "RJX", "NJ", "EN", "FLX", "TGV", "ES", "D", "WB" -> 105.0
            "RE", "IRE", "MEX", "FEX", "RE+" -> 70.0
            "S" -> 40.0
            else -> 50.0
        }
    }
}

/** Farbstufen für die Karte. */
enum class DelayLevel(val label: String, val argb: Long, val darkText: Boolean) {
    ON_TIME("unter 5 Min.", 0xFF2E7D32, false),
    LIGHT("5–15 Min.", 0xFF9CCC65, true),
    MEDIUM("15–30 Min.", 0xFFFDD835, true),
    HIGH("30–60 Min.", 0xFFFB8C00, false),
    SEVERE("über 60 Min.", 0xFFD32F2F, false),
    UNKNOWN("ohne Echtzeit", 0xFF9E9E9E, false);

    companion object {
        fun of(delay: Int?, hasRealtime: Boolean = true): DelayLevel = when {
            delay == null && !hasRealtime -> UNKNOWN
            (delay ?: 0) < 5 -> ON_TIME
            delay!! < 15 -> LIGHT
            delay < 30 -> MEDIUM
            delay < 60 -> HIGH
            else -> SEVERE
        }
    }
}
