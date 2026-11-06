package de.bahnlive.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.bahnlive.app.data.BoardResult
import de.bahnlive.app.data.Connection
import de.bahnlive.app.data.LatLon
import de.bahnlive.app.data.MovingTrain
import de.bahnlive.app.data.StationLocator
import de.bahnlive.app.data.RouteRepository
import de.bahnlive.app.data.RouteStore
import de.bahnlive.app.data.SavedRoute
import de.bahnlive.app.data.SettingsStore
import de.bahnlive.app.data.TrackRepository
import de.bahnlive.app.data.TripEnricher
import de.bahnlive.app.data.VendoApi
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDateTime

data class BoardState(
    val route: SavedRoute,
    val loading: Boolean = true,
    val connections: List<Connection> = emptyList(),
    /** Züge, die gerade zwischen A und B unterwegs sind, beide Richtungen. */
    val moving: List<MovingTrain> = emptyList(),
    /** Bahnhöfe der Strecke in Reihenfolge (erster = Start, letzter = Ziel). */
    val corridor: List<String> = emptyList(),
    /** Bekannte Bahnhofskoordinaten, Schlüssel = Bahnhofsname wie in den Daten. */
    val coordinates: Map<String, LatLon> = emptyMap(),
    /** Gleisverlauf je Abschnitt aus OpenStreetMap (leer = gerade Linien). */
    val track: Map<Int, List<LatLon>> = emptyMap(),
    /** Für welche Bahnhofsfolge [track] berechnet wurde. */
    val trackFor: List<Pair<Int, LatLon>> = emptyList(),
    /** Zustand der Gleisabfrage für die Legende: null = fertig/nichts zu tun. */
    val trackStatus: String? = null,
    /** Linienverläufe aus dem DB-Fahrtverlauf – Ersatz, wo OSM keine Gleise liefert. */
    val dbShapes: List<List<LatLon>> = emptyList(),
    /** DB-Fahrtverlauf eingeschaltet, aber gerade nicht erreichbar → Schätzung aktiv. */
    val dbTripsUnavailable: Boolean = false,
    val error: String? = null,
    val fetchedAt: LocalDateTime? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = RouteStore(app)
    private val repository = RouteRepository()
    private val locator = StationLocator(app)
    private val settings = SettingsStore(app)
    private val vendo = VendoApi(app)
    private val enricher = TripEnricher(vendo)
    private val tracks = TrackRepository(app)
    private var geocodeJob: Job? = null
    private var trackJob: Job? = null

    /** Genauere Positionen über den DB-Fahrtverlauf (Einstellung). */
    val useDbTrips: StateFlow<Boolean> =
        settings.useDbTrips.stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setUseDbTrips(enabled: Boolean) {
        viewModelScope.launch { settings.setUseDbTrips(enabled) }
    }

    val savedRoutes: StateFlow<List<SavedRoute>> =
        store.routes.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _board = MutableStateFlow<BoardState?>(null)
    val board: StateFlow<BoardState?> = _board.asStateFlow()

    private var refreshJob: Job? = null

    fun open(route: SavedRoute) {
        viewModelScope.launch { store.add(route) }
        // Das Laden startet der Bildschirm über startAutoRefresh(), sobald er sichtbar ist.
        _board.value = BoardState(route = route)
    }

    fun close() {
        stopAutoRefresh()
        _board.value = null
    }

    fun swapDirection() {
        _board.value?.route?.let {
            open(it.reversed())
            startAutoRefresh()
        }
    }

    fun remove(route: SavedRoute) {
        viewModelScope.launch { store.remove(route) }
    }

    fun reorder(routes: List<SavedRoute>) {
        viewModelScope.launch { store.reorder(routes) }
    }

    fun refreshNow() {
        val route = _board.value?.route ?: return
        viewModelScope.launch { load(route) }
    }

    /** Lädt alle 60 Sekunden neu – häufiger aktualisiert die Datenquelle ohnehin nicht. */
    fun startAutoRefresh() {
        val route = _board.value?.route ?: return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            while (isActive) {
                load(route)
                delay(REFRESH_MILLIS)
            }
        }
    }

    fun stopAutoRefresh() {
        refreshJob?.cancel()
        refreshJob = null
    }

    private suspend fun load(route: SavedRoute) {
        _board.update { it?.copy(loading = true) }
        val result = repository.connections(route)
        if (result is BoardResult.Success) {
            val firstLoad = _board.value?.fetchedAt == null
            // Beim ersten Laden sofort anzeigen, danach erst mit verfeinerten Zeiten,
            // damit die Züge nicht zwischen Schätzung und Fahrtverlauf hin- und herspringen.
            if (firstLoad) publish(route, result, result.moving, emptyMap(), dbUnavailable = false)
            var moving = result.moving
            var coords = emptyMap<String, LatLon>()
            var shapes = emptyList<List<LatLon>>()
            var unavailable = false
            if (useDbTrips.value && result.moving.isNotEmpty()) {
                val enriched = withTimeoutOrNull(ENRICH_TIMEOUT_MILLIS) {
                    enricher.enrich(result.corridor, result.moving, result.fetchedAt)
                }
                if (enriched != null) {
                    moving = enriched.moving
                    coords = enriched.coordinates
                    shapes = enriched.polylines
                }
                unavailable = enriched == null || vendo.isPaused || moving.none { it.detailed }
            }
            publish(route, result, moving, coords, unavailable, shapes)
        } else if (result is BoardResult.Error) {
            _board.update { current ->
                if (current == null || current.route != route) current
                else current.copy(loading = false, error = result.message)
            }
        }
        resolveCoordinates()
    }

    private fun publish(
        route: SavedRoute,
        result: BoardResult.Success,
        moving: List<MovingTrain>,
        coords: Map<String, LatLon>,
        dbUnavailable: Boolean,
        shapes: List<List<LatLon>> = emptyList(),
    ) {
        _board.update { current ->
            if (current == null || current.route != route) return@update current
            current.copy(
                loading = false,
                connections = result.connections,
                moving = moving,
                corridor = result.corridor,
                // Koordinaten aus dem Fahrtverlauf nur ergänzen, nicht ersetzen.
                coordinates = coords + current.coordinates,
                dbTripsUnavailable = dbUnavailable,
                // Neue Linienverläufe zuerst, ältere behalten (höchstens 20).
                dbShapes = if (shapes.isEmpty()) current.dbShapes else (shapes + current.dbShapes).take(20),
                error = null,
                fetchedAt = result.fetchedAt,
            )
        }
    }

    /**
     * Sucht fehlende Bahnhofskoordinaten nacheinander (OSM erlaubt 1 Anfrage/Sekunde).
     * Bekannte Koordinaten erscheinen sofort, gesuchte nach und nach auf der Karte.
     */
    private fun resolveCoordinates() {
        val state = _board.value ?: return
        // Bahnhöfe der Strecke in Reihenfolge – die Karte füllt fehlende Punkte vorläufig auf.
        val names = (listOf(state.route.from, state.route.to) + state.corridor)
            .distinct()
            .filterNot { it in state.coordinates }

        val immediate = names.mapNotNull { n -> locator.cached(n)?.let { n to it } }.toMap()
        if (immediate.isNotEmpty()) {
            _board.update { it?.copy(coordinates = it.coordinates + immediate) }
        }
        val missing = names.filter { it !in immediate && !locator.knownMissing(it) }
        if (missing.isEmpty()) {
            loadTrack()
            return
        }
        if (geocodeJob?.isActive == true) return

        // Start und Ziel zuerst – damit kann die Karte schon Züge zeigen.
        val ordered = missing.sortedBy { if (it == state.route.from || it == state.route.to) 0 else 1 }
        geocodeJob = viewModelScope.launch {
            for (name in ordered) {
                if (_board.value?.route != state.route) break
                if (name in (_board.value?.coordinates ?: emptyMap())) continue
                val found = locator.locate(name) ?: continue
                _board.update { it?.copy(coordinates = it.coordinates + (name to found)) }
            }
            loadTrack()
        }
    }

    /**
     * Lädt den Gleisverlauf aus OpenStreetMap, sobald die Koordinaten der Bahnhöfe
     * feststehen. Einmal pro Strecke; danach aus dem Zwischenspeicher.
     */
    private fun loadTrack() {
        val state = _board.value ?: return
        val stations = state.corridor.mapIndexedNotNull { i, name -> state.coordinates[name]?.let { i to it } }
        if (stations.size < 2 || stations.first().first != 0 || stations.last().first != state.corridor.lastIndex) return
        if (stations == state.trackFor || trackJob?.isActive == true) return
        trackJob = viewModelScope.launch {
            _board.update { if (it == null || it.route != state.route) it else it.copy(trackStatus = "Gleise werden geladen …") }
            val outcome = tracks.track(stations)
            _board.update {
                if (it == null || it.route != state.route || it.corridor != state.corridor) it
                else it.copy(
                    track = if (outcome.segments.isEmpty()) it.track else outcome.segments,
                    // Bei einem Fehler später erneut versuchen (trackFor bleibt dann unverändert).
                    trackFor = if (outcome.problem == null) stations else it.trackFor,
                    trackStatus = outcome.problem?.let { p -> "Gleise: $p" },
                )
            }
        }
    }

    companion object {
        private const val REFRESH_MILLIS = 60_000L
        /** So lange darf das Verfeinern dauern, sonst bleibt es bei der Schätzung. */
        private const val ENRICH_TIMEOUT_MILLIS = 12_000L
    }
}
