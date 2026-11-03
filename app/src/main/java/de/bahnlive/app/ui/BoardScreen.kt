package de.bahnlive.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import de.bahnlive.app.data.Connection
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val TIME = DateTimeFormatter.ofPattern("HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(
    state: BoardState,
    onBack: () -> Unit,
    onSwap: () -> Unit,
    onRefresh: () -> Unit,
    onResume: () -> Unit,
    onPause: () -> Unit,
) {
    // Automatische Aktualisierung nur, solange die App im Vordergrund ist.
    LifecycleResumeEffect(state.route) {
        onResume()
        onPauseOrDispose { onPause() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.route.from, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "→ ${state.route.to}",
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
                    }
                },
                actions = {
                    IconButton(onClick = onSwap) {
                        Icon(Icons.Default.SwapHoriz, contentDescription = "Gegenrichtung")
                    }
                    IconButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Aktualisieren")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            var tab by rememberSaveable { mutableIntStateOf(0) }
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Abfahrten") })
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text(if (state.moving.isEmpty()) "Karte" else "Karte (${state.moving.size})") },
                )
            }
            if (state.loading) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            } else {
                Spacer(Modifier.height(4.dp))
            }
            if (tab == 1) {
                state.error?.let { Box(Modifier.padding(12.dp)) { ErrorCard(it) } }
                RouteMap(state, Modifier.fillMaxSize())
                return@Column
            }
            PullToRefreshBox(
                isRefreshing = false,
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { StatusLine(state) }
                    state.error?.let { msg -> item { ErrorCard(msg) } }
                    if (!state.loading && state.error == null && state.connections.isEmpty()) {
                        item {
                            Text(
                                "Keine direkten Züge in den nächsten Stunden gefunden. " +
                                    "Prüfe die Schreibweise des Zielbahnhofs (z. B. „Köln Hbf“).",
                                modifier = Modifier.padding(vertical = 24.dp),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(state.connections, key = { "${it.train}-${it.departurePlanned}" }) {
                        ConnectionCard(it)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusLine(state: BoardState) {
    val text = state.fetchedAt?.let { "Stand ${it.format(TIME)} · ${state.connections.size} Züge" }
        ?: "Lade Echtzeitdaten …"
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ErrorCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Warning, null, tint = MaterialTheme.colorScheme.onErrorContainer)
            Spacer(Modifier.width(12.dp))
            Text(message, color = MaterialTheme.colorScheme.onErrorContainer)
        }
    }
}

@Composable
private fun ConnectionCard(c: Connection) {
    var expanded by rememberSaveable(c.train, c.departurePlanned.toString()) { mutableStateOf(false) }
    val cancelled = c.isCancelled || c.destinationStopCancelled

    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TrainBadge(c.train)
                Spacer(Modifier.width(10.dp))
                Text(
                    "nach ${c.destination}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                MinutesUntil(c.departureExpected, cancelled)
            }

            Spacer(Modifier.height(10.dp))

            Row(Modifier.fillMaxWidth()) {
                TimeBlock(
                    label = "Ab",
                    planned = c.departurePlanned,
                    delay = c.departureDelay,
                    platform = c.departurePlatform,
                    platformPlanned = c.departurePlatformPlanned,
                    cancelled = c.isCancelled,
                    modifier = Modifier.weight(1f),
                )
                TimeBlock(
                    label = "An",
                    planned = c.arrivalPlanned,
                    delay = c.arrivalDelay,
                    platform = c.arrivalPlatform,
                    platformPlanned = c.arrivalPlatformPlanned,
                    cancelled = cancelled,
                    modifier = Modifier.weight(1f),
                )
            }

            when {
                c.isCancelled -> Banner("Fällt aus", Status.late)
                c.destinationStopCancelled -> Banner("Halt in Zielbahnhof entfällt", Status.late)
                c.platformChanged -> Banner("Gleiswechsel: Gleis ${c.departurePlatform}", Status.slight)
                !c.hasRealtime -> Banner("Keine Echtzeitdaten – Planzeiten", MaterialTheme.colorScheme.outline)
            }

            if (c.messages.isNotEmpty() && !expanded) {
                Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Info, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        c.messages.first() + if (c.messages.size > 1) " (+${c.messages.size - 1})" else "",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            AnimatedVisibility(expanded) {
                Details(c)
            }
        }
    }
}

@Composable
private fun Details(c: Connection) {
    Column(Modifier.padding(top = 10.dp)) {
        HorizontalDivider()
        if (c.messages.isNotEmpty()) {
            Text("Meldungen", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
            c.messages.forEach {
                Text("• $it", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 2.dp))
            }
        }
        Text(
            if (c.stopsBetween.isEmpty()) "Ohne Zwischenhalt" else "Zwischenhalte (${c.stopsBetween.size})",
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 8.dp),
        )
        c.stopsBetween.forEach { stop ->
            Text(
                buildString {
                    append("· ").append(stop.name)
                    if (stop.isCancelled) append("  (entfällt)")
                    if (stop.isAdditional) append("  (Zusatzhalt)")
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (stop.isCancelled) Status.late else Color.Unspecified,
                textDecoration = if (stop.isCancelled) TextDecoration.LineThrough else null,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        c.trainNumber?.let {
            Text(
                "Zugnummer $it",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun TrainBadge(train: String) {
    Box(
        Modifier
            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .widthIn(min = 40.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            train,
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun MinutesUntil(time: LocalDateTime, cancelled: Boolean) {
    if (cancelled) return
    val minutes = Duration.between(LocalDateTime.now(), time).toMinutes()
    val text = when {
        minutes <= 0 -> "jetzt"
        minutes < 60 -> "in $minutes Min."
        else -> "in ${minutes / 60} h ${minutes % 60} Min."
    }
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun TimeBlock(
    label: String,
    planned: LocalDateTime?,
    delay: Int?,
    platform: String?,
    platformPlanned: String?,
    cancelled: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (planned == null) {
            Text("–", style = MaterialTheme.typography.titleLarge)
            return@Column
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                planned.format(TIME),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
                textDecoration = if (cancelled || (delay ?: 0) > 0) TextDecoration.LineThrough else null,
            )
            if (!cancelled && delay != null) {
                Spacer(Modifier.width(6.dp))
                val shown = if (delay > 0) planned.plusMinutes(delay.toLong()).format(TIME) else "+0"
                Text(
                    if (delay > 0) "$shown (+$delay)" else shown,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Bold,
                    color = Status.forDelay(delay),
                )
            }
        }
        val p = platform ?: platformPlanned
        if (p != null) {
            val changed = platform != null && platformPlanned != null && platform != platformPlanned
            Text(
                if (changed) "Gleis $platform statt $platformPlanned" else "Gleis $p",
                style = MaterialTheme.typography.bodySmall,
                color = if (changed) Status.slight else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (changed) FontWeight.Bold else null,
            )
        }
    }
}

@Composable
private fun Banner(text: String, color: Color) {
    Text(
        text,
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier
            .padding(top = 8.dp)
            .background(color, RoundedCornerShape(4.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    )
}
