package de.bahnlive.app.data

import kotlinx.serialization.Serializable
import java.time.LocalDateTime

/**
 * Eine vom Nutzer gespeicherte Strecke (Start- und Zielbahnhof).
 * [via] legt optional die Streckenführung fest: Nur Züge über diesen Bahnhof zählen.
 */
@Serializable
data class SavedRoute(
    val from: String,
    val to: String,
    val via: String? = null,
) {
    val id: String
        get() = "${from.trim().lowercase()}→${to.trim().lowercase()}" +
            (via?.takeIf { it.isNotBlank() }?.let { "|über ${it.trim().lowercase()}" } ?: "")

    fun reversed() = SavedRoute(from = to, to = from, via = via)
}

/** Ein Halt im Laufweg eines Zuges. */
data class RouteStop(
    val name: String,
    val isCancelled: Boolean,
    val isAdditional: Boolean,
)

/** Rohdaten einer Zeile aus der DBF-Abfahrtstafel (JSON Version 3). */
data class BoardEntry(
    val train: String,
    val trainNumber: String?,
    val destination: String?,
    val scheduledArrival: String?,
    val scheduledDeparture: String?,
    val delayArrival: Int?,
    val delayDeparture: Int?,
    val platform: String?,
    val scheduledPlatform: String?,
    val isCancelled: Boolean,
    val missingRealtime: Boolean,
    val route: List<RouteStop>,
    val messages: List<String>,
)

/** Eine aufbereitete Verbindung auf der gewählten Strecke. */
data class Connection(
    /** Start- und Zielbahnhof des Streckenabschnitts (zeigt die Fahrtrichtung). */
    val from: String,
    val to: String,
    val train: String,
    val trainNumber: String?,
    val destination: String,
    val departurePlanned: LocalDateTime,
    val departureDelay: Int?,
    val departurePlatform: String?,
    val departurePlatformPlanned: String?,
    val arrivalPlanned: LocalDateTime?,
    val arrivalDelay: Int?,
    val arrivalPlatform: String?,
    val arrivalPlatformPlanned: String?,
    val isCancelled: Boolean,
    val destinationStopCancelled: Boolean,
    val hasRealtime: Boolean,
    val stopsBetween: List<RouteStop>,
    val messages: List<String>,
) {
    val departureExpected: LocalDateTime
        get() = departurePlanned.plusMinutes((departureDelay ?: 0).toLong())
    val arrivalExpected: LocalDateTime?
        get() = arrivalPlanned?.plusMinutes((arrivalDelay ?: 0).toLong())
    /** Aktuellste bekannte Verspätung: Prognose für die Ankunft, sonst die Abfahrt. */
    val currentDelay: Int?
        get() = arrivalDelay ?: departureDelay

    val platformChanged: Boolean
        get() = departurePlatform != null && departurePlatformPlanned != null &&
            departurePlatform != departurePlatformPlanned
}

sealed interface BoardResult {
    data class Success(
        /** Kommende Abfahrten A → B (für die Liste). */
        val connections: List<Connection>,
        /** Züge, die gerade zwischen A und B unterwegs sind – beide Richtungen (für die Karte). */
        val moving: List<MovingTrain>,
        /** Bahnhöfe der Strecke in Reihenfolge, erster = Start, letzter = Ziel. */
        val corridor: List<String>,
        val fetchedAt: LocalDateTime,
    ) : BoardResult

    data class Error(val message: String) : BoardResult
}

/** Erwartete Zeit (inkl. Verspätung), zu der ein Zug an einem Bahnhof der Strecke ist. */
data class TimePoint(
    /** Position in [BoardResult.Success.corridor]. */
    val index: Int,
    val time: LocalDateTime,
    val delay: Int?,
)

/**
 * Ein Zug, der gerade auf der Strecke unterwegs ist – unabhängig davon, wann er am
 * Start abgefahren ist. Zusammengesetzt aus den Tafeln mehrerer Bahnhöfe der Strecke.
 */
data class MovingTrain(
    val key: String,
    val train: String,
    val trainNumber: String?,
    val destination: String,
    /** +1 = Richtung Ziel, -1 = Richtung Start. */
    val direction: Int,
    /** Bahnhof (Korridor-Index), an dem der Zug in die Strecke einfährt bzw. sie verlässt. */
    val entryIndex: Int,
    val exitIndex: Int,
    /** Beobachtete Zeitpunkte, nach Zeit sortiert. */
    val points: List<TimePoint>,
    /**
     * Wurde die Tafel des Ein-/Ausfahrtsbahnhofs abgefragt? Fehlt der Zug dort, liegt seine
     * Zeit außerhalb des Tafelfensters (letzte Stunde bis nächste drei Stunden).
     */
    val entryBoardFetched: Boolean,
    val exitBoardFetched: Boolean,
    val hasRealtime: Boolean,
    val messages: List<String>,
)
