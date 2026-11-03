package de.bahnlive.app

import android.os.Bundle
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

                val current = board
                if (current == null) {
                    RouteSelectScreen(
                        savedRoutes = routes,
                        onOpen = viewModel::open,
                        onDelete = viewModel::remove,
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
            }
        }
    }
}
