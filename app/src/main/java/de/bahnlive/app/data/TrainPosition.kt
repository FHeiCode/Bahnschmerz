package de.bahnlive.app.data

import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Geschätzte Position eines Zuges auf der Karte. */
data class TrainPosition(
    val connection: Connection,
    val position: LatLon,
    /** Anteil der zurückgelegten Strecke zwischen Start und Ziel (0..1). */
    val progress: Double,
)

/**
 * Schätzt, wo ein Zug gerade ist: Aus Ist-Abfahrt in A und prognostizierter Ankunft in B
 * wird der zeitliche Fortschritt berechnet und auf den Laufweg (A, Zwischenhalte, B)
 * übertragen. Echte GPS-Positionen liefert die DB-Schnittstelle nicht.
 */
object PositionEstimator {

    fun estimate(
        c: Connection,
        now: LocalDateTime,
        coordinates: (String) -> LatLon?,
    ): TrainPosition? {
        val dep = c.departureExpected
        val arr = c.arrivalExpected ?: return null
        val total = Duration.between(dep, arr).seconds
        if (total <= 0) return null
        val progress = (Duration.between(dep, now).seconds.toDouble() / total).coerceIn(0.0, 1.0)

        val start = coordinates(c.from) ?: return null
        val end = coordinates(c.to) ?: return null
        val path = buildList {
            add(start)
            c.stopsBetween.mapNotNullTo(this) { coordinates(it.name) }
            add(end)
        }
        return TrainPosition(c, pointAlong(path, progress), progress)
    }

    /** Punkt bei [fraction] der Gesamtlänge eines Linienzugs. */
    fun pointAlong(path: List<LatLon>, fraction: Double): LatLon {
        if (path.size < 2) return path.first()
        val lengths = path.zipWithNext { a, b -> distanceKm(a, b) }
        val total = lengths.sum()
        if (total <= 0.0) return path.first()
        var remaining = fraction * total
        lengths.forEachIndexed { i, len ->
            if (remaining <= len || i == lengths.lastIndex) {
                val t = if (len > 0) (remaining / len).coerceIn(0.0, 1.0) else 0.0
                val a = path[i]
                val b = path[i + 1]
                return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
            }
            remaining -= len
        }
        return path.last()
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
