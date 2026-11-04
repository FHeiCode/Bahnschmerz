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
    val error: String? = null,
    val fetchedAt: LocalDateTime? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = RouteStore(app)
    private val repository = RouteRepository()
    private val locator = StationLocator(app)
    private var geocodeJob: Job? = null

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
        _board.update { current ->
            if (current == null || current.route != route) return@update current
            when (result) {
                is BoardResult.Success -> current.copy(
                    loading = false,
                    connections = result.connections,
                    moving = result.moving,
                    corridor = result.corridor,
                    error = null,
                    fetchedAt = result.fetchedAt,
                )
                is BoardResult.Error -> current.copy(loading = false, error = result.message)
            }
        }
        resolveCoordinates()
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
        if (missing.isEmpty() || geocodeJob?.isActive == true) return

        // Start und Ziel zuerst – damit kann die Karte schon Züge zeigen.
        val ordered = missing.sortedBy { if (it == state.route.from || it == state.route.to) 0 else 1 }
        geocodeJob = viewModelScope.launch {
            for (name in ordered) {
                if (_board.value?.route != state.route) break
                val found = locator.locate(name) ?: continue
                _board.update { it?.copy(coordinates = it.coordinates + (name to found)) }
            }
        }
    }

    companion object {
        private const val REFRESH_MILLIS = 60_000L
    }
}
