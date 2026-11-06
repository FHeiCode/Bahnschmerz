package de.bahnlive.app.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsStore by preferencesDataStore(name = "settings")

/** Einstellungen der App. */
class SettingsStore(private val context: Context) {
    private val useDbTripsKey = booleanPreferencesKey("use_db_trips")

    /** Genauere Positionen über den DB-Fahrtverlauf (inoffizielle Schnittstelle). Standard: an. */
    val useDbTrips: Flow<Boolean> = context.settingsStore.data.map { it[useDbTripsKey] ?: true }

    suspend fun setUseDbTrips(enabled: Boolean) {
        context.settingsStore.edit { it[useDbTripsKey] = enabled }
    }
}
