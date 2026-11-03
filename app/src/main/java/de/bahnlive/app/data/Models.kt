package de.bahnlive.app.data

import kotlinx.serialization.Serializable
import java.time.LocalDateTime

/** Eine vom Nutzer gespeicherte Strecke (Start- und Zielbahnhof). */
@Serializable
data class SavedRoute(
    val from: String,
    val to: String,
) {
    val id: String get() = "${from.trim().lowercase()}→${to.trim().lowercase()}"
    fun reversed() = SavedRoute(from = to, to = from)
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
        val moving: List<Connection>,
        val fetchedAt: LocalDateTime,
    ) : BoardResult

    data class Error(val message: String) : BoardResult
}
