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
) {
    var from by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }
    val canSubmit = from.isNotBlank() && to.isNotBlank() && from.trim() != to.trim()
    val submit = { if (canSubmit) onOpen(SavedRoute(from.trim(), to.trim())) }

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
                        Text("Bahnschmerz", fontWeight = FontWeight.Bold)
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
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
                        StationField("Nach", to, { to = it }, ImeAction.Search) { submit() }
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = submit, enabled = canSubmit, modifier = Modifier.fillMaxWidth()) {
                            Text("Echtzeitdaten anzeigen")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Bahnhofsname, EVA-Nummer (z. B. 8000105) oder DS100-Kürzel (z. B. FF).",
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
                items(savedRoutes, key = { it.id }) { route ->
                    Card(
                        Modifier.fillMaxWidth().clickable { onOpen(route) },
                    ) {
                        Row(
                            Modifier.padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
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
                            }
                            IconButton(onClick = { onDelete(route) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Strecke entfernen")
                            }
                        }
                    }
                }
            }

            item {
                Text(
                    "Daten: DB IRIS über dbf.finalrewind.org · Aktualisierung jede Minute",
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
