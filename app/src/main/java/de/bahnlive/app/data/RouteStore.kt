package de.bahnlive.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.dataStore by preferencesDataStore(name = "routes")

/** Speichert die Liste der Lieblingsstrecken dauerhaft auf dem Gerät. */
class RouteStore(private val context: Context) {
    private val key = stringPreferencesKey("saved_routes")
    private val json = Json { ignoreUnknownKeys = true }

    val routes: Flow<List<SavedRoute>> = context.dataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { json.decodeFromString<List<SavedRoute>>(it) }.getOrNull() }
            ?: DEFAULT_ROUTES
    }

    /** Neue Strecken kommen nach oben; bekannte behalten ihren Platz (eigene Sortierung bleibt). */
    suspend fun add(route: SavedRoute) = update { list ->
        if (list.any { it.id == route.id }) list else listOf(route) + list
    }

    /** Speichert die vom Nutzer per Ziehen festgelegte Reihenfolge. */
    suspend fun reorder(ordered: List<SavedRoute>) = update { current ->
        // Nur bekannte Strecken übernehmen; zwischenzeitlich hinzugekommene vorn behalten.
        val ids = ordered.map { it.id }.toSet()
        current.filterNot { it.id in ids } + ordered.filter { o -> current.any { it.id == o.id } }
    }

    suspend fun remove(route: SavedRoute) = update { list -> list.filterNot { it.id == route.id } }

    private suspend fun update(transform: (List<SavedRoute>) -> List<SavedRoute>) {
        context.dataStore.edit { prefs ->
            val current = prefs[key]?.let {
                runCatching { json.decodeFromString<List<SavedRoute>>(it) }.getOrNull()
            } ?: DEFAULT_ROUTES
            prefs[key] = json.encodeToString(transform(current))
        }
    }

    companion object {
        val DEFAULT_ROUTES = listOf(
            SavedRoute("Frankfurt(Main)Hbf", "Mannheim Hbf"),
            SavedRoute("Berlin Hbf", "Hamburg Hbf"),
            SavedRoute("München Hbf", "Augsburg Hbf"),
        )
    }
}
