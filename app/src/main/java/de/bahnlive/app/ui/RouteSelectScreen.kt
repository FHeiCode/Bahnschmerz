package de.bahnlive.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.ui.res.painterResource
import de.bahnlive.app.R
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Switch
import androidx.compose.runtime.remember
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Train
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import de.bahnlive.app.data.SavedRoute
import de.bahnlive.app.data.Stations

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteSelectScreen(
    savedRoutes: List<SavedRoute>,
    onOpen: (SavedRoute) -> Unit,
    onDelete: (SavedRoute) -> Unit,
    onReorder: (List<SavedRoute>) -> Unit,
    useDbTrips: Boolean,
    onUseDbTripsChange: (Boolean) -> Unit,
) {
    // Lokale Kopie, damit sich die Liste beim Ziehen sofort bewegt; gespeichert wird beim Loslassen.
    var routes by remember(savedRoutes) { mutableStateOf(savedRoutes) }
    val listState = rememberLazyListState()
    val haptics = LocalHapticFeedback.current
    val reorderState = rememberReorderableLazyListState(listState) { from, to ->
        val fromIndex = routes.indexOfFirst { it.id == from.key }
        val toIndex = routes.indexOfFirst { it.id == to.key }
        // Nur zwischen Strecken verschieben – nicht über Eingabekarte oder Überschrift.
        if (fromIndex >= 0 && toIndex >= 0) {
            routes = routes.toMutableList().apply { add(toIndex, removeAt(fromIndex)) }
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
        }
    }

    var from by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }
    var via by rememberSaveable { mutableStateOf("") }
    val viaOrNull = via.trim().takeIf { it.isNotEmpty() }
    val canSubmit = from.isNotBlank() && to.isNotBlank() && from.trim() != to.trim() &&
        viaOrNull != from.trim() && viaOrNull != to.trim()
    val submit = { if (canSubmit) onOpen(SavedRoute(from.trim(), to.trim(), viaOrNull)) }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Image(
                            painter = painterResource(R.drawable.ic_logo),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                        )
                        Spacer(Modifier.width(10.dp))
                        BahnSchmerzWordmark()
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Strecke wählen", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(12.dp))
                        StationField("Von", from, { from = it }, ImeAction.Next) {}
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            IconButton(onClick = { val f = from; from = to; to = f }) {
                                Icon(Icons.Default.SwapVert, contentDescription = "Richtung tauschen")
                            }
                        }
                        StationField("Nach", to, { to = it }, ImeAction.Next) {}
                        Spacer(Modifier.height(8.dp))
                        StationField("Über (optional)", via, { via = it }, ImeAction.Search) { submit() }
                        Text(
                            "Legt die Streckenführung fest – es zählen nur Züge über diesen Bahnhof.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp),
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
                            Text("Echtzeitdaten anzeigen")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Bahnhofsname wie bei der DB, z. B. „Köln Hbf“ oder „Frankfurt(Main)Hbf“.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (savedRoutes.isNotEmpty()) {
                item {
                    Text(
                        "Meine Strecken",
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(routes, key = { it.id }) { route ->
                  ReorderableItem(reorderState, key = route.id) { isDragging ->
                    val elevation by animateDpAsState(if (isDragging) 8.dp else 0.dp, label = "Ziehen")
                    Card(
                        Modifier.fillMaxWidth().clickable { onOpen(route) },
                        elevation = CardDefaults.cardElevation(defaultElevation = elevation),
                    ) {
                        Row(
                            Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Griff zum Ziehen
                            IconButton(
                                onClick = {},
                                modifier = Modifier.draggableHandle(
                                    onDragStarted = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    },
                                    onDragStopped = { onReorder(routes) },
                                ),
                            ) {
                                Icon(Icons.Default.DragHandle, contentDescription = "Strecke verschieben")
                            }
                            Icon(Icons.Default.Train, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(route.from, style = MaterialTheme.typography.bodyLarge)
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.AutoMirrored.Filled.ArrowForward, null,
                                        Modifier.padding(end = 4.dp).height(16.dp),
                                    )
                                    Text(route.to, style = MaterialTheme.typography.bodyLarge)
                                }
                                route.via?.takeIf { it.isNotBlank() }?.let {
                                    Text(
                                        "über $it",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            IconButton(onClick = { onDelete(route) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Strecke entfernen")
                            }
                        }
                    }
                  }
                }
            }

            item {
                Card(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                    Row(
                        Modifier.padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("Genauere Zugpositionen", style = MaterialTheme.typography.bodyLarge)
                            Text(
                                "Nutzt den DB-Fahrtverlauf mit Zeiten an allen Halten (inoffizielle " +
                                    "Schnittstelle). Ist sie nicht erreichbar, schätzt die App wie bisher.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Switch(checked = useDbTrips, onCheckedChange = onUseDbTripsChange)
                    }
                }
            }

            item {
                Text(
                    "Daten: DB IRIS über dbf.finalrewind.org, DB-Fahrtverlauf, Gleise © OpenStreetMap · Aktualisierung jede Minute",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StationField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    imeAction: ImeAction,
    onAction: () -> Unit,
) {
    Column {
        OutlinedTextField(
            value = value,
            onValueChange = onChange,
            label = { Text(label) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = imeAction),
            keyboardActions = KeyboardActions(onAny = { onAction() }),
            modifier = Modifier.fillMaxWidth(),
        )
        val suggestions = Stations.filter(value)
        if (suggestions.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                suggestions.forEach { s ->
                    SuggestionChip(onClick = { onChange(s) }, label = { Text(s, maxLines = 1) })
                }
            }
        }
    }
}
