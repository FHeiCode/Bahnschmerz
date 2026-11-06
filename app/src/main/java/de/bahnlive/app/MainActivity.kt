package de.bahnlive.app

import android.content.Intent
import android.os.Bundle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import de.bahnlive.app.ui.BahnLiveTheme
import de.bahnlive.app.ui.BoardScreen
import de.bahnlive.app.ui.MainViewModel
import de.bahnlive.app.ui.RouteSelectScreen
import org.osmdroid.config.Configuration
import java.io.File

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashReporter.install(this)
        super.onCreate(savedInstanceState)
        // OpenStreetMap-Karte: eindeutige App-Kennung (Pflicht laut OSM-Nutzungsregeln) und Kachel-Cache.
        Configuration.getInstance().apply {
            load(applicationContext, getSharedPreferences("osmdroid", MODE_PRIVATE))
            userAgentValue = packageName
            osmdroidBasePath = File(cacheDir, "osmdroid")
            osmdroidTileCache = File(cacheDir, "osmdroid/tiles")
        }
        enableEdgeToEdge()
        setContent {
            BahnLiveTheme {
                val board by viewModel.board.collectAsStateWithLifecycle()
                val routes by viewModel.savedRoutes.collectAsStateWithLifecycle()
                val useDbTrips by viewModel.useDbTrips.collectAsStateWithLifecycle()
                var report by remember { mutableStateOf(CrashReporter.read(this@MainActivity)) }

                val current = board
                if (current == null) {
                    RouteSelectScreen(
                        savedRoutes = routes,
                        onOpen = viewModel::open,
                        onDelete = viewModel::remove,
                        onReorder = viewModel::reorder,
                        useDbTrips = useDbTrips,
                        onUseDbTripsChange = viewModel::setUseDbTrips,
                    )
                } else {
                    BackHandler { viewModel.close() }
                    BoardScreen(
                        state = current,
                        onBack = viewModel::close,
                        onSwap = viewModel::swapDirection,
                        onRefresh = viewModel::refreshNow,
                        onResume = viewModel::startAutoRefresh,
                        onPause = viewModel::stopAutoRefresh,
                    )
                }

                // Bericht vom letzten Absturz/Fehler anzeigen – zum Teilen mit dem Entwickler.
                report?.let { text ->
                    CrashReportDialog(
                        text = text,
                        onShare = { shareReport(text) },
                        onDismiss = {
                            CrashReporter.clear(this@MainActivity)
                            report = null
                        },
                    )
                }
            }
        }
    }

    private fun shareReport(text: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "BahnSchmerz Absturzbericht")
            putExtra(Intent.EXTRA_TEXT, text)
        }
        runCatching { startActivity(Intent.createChooser(send, "Absturzbericht teilen")) }
    }
}

@Composable
private fun CrashReportDialog(text: String, onShare: () -> Unit, onDismiss: () -> Unit) {
    val crashed = text.startsWith("ABSTURZ")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (crashed) "BahnSchmerz ist abgestürzt" else "Es gab einen Fehler") },
        text = {
            Column {
                Text(
                    "Hier ist der Bericht dazu. Über „Teilen“ kannst du ihn verschicken, " +
                        "damit der Fehler behoben werden kann.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                )
            }
        },
        confirmButton = { TextButton(onClick = onShare) { Text("Teilen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Schließen") } },
    )
}
