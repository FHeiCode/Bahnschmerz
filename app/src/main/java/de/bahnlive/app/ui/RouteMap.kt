package de.bahnlive.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.bahnlive.app.data.CorridorGeometry
import de.bahnlive.app.data.DelayLevel
import de.bahnlive.app.data.PositionEstimator
import de.bahnlive.app.data.RailRouter
import de.bahnlive.app.data.TrainStatus
import kotlinx.coroutines.delay
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.CopyrightOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

private val HM = DateTimeFormatter.ofPattern("HH:mm")

/** Ergebnis der Positionsschätzung für Karte und Liste, alle 10 Sekunden neu berechnet. */
class StationMapData(
    val geometry: CorridorGeometry?,
    val statuses: List<TrainStatus>,
)

@Composable
fun rememberStationMapData(state: BoardState): StationMapData {
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            now = LocalDateTime.now()
        }
    }
    val geometry = remember(state.corridor, state.coordinates, state.track, state.dbShapes) {
        // Gleise aus OSM; wo die fehlen, der Linienverlauf aus dem DB-Fahrtverlauf.
        val stations = state.corridor.mapIndexedNotNull { i, n -> state.coordinates[n]?.let { i to it } }
        val fromDb = RailRouter.segmentsFromPolylines(state.dbShapes, stations)
        CorridorGeometry.build(state.corridor, { state.coordinates[it] }, state.track, fromDb)
    }
    val statuses = remember(state.moving, geometry, now) {
        state.moving.mapNotNull { PositionEstimator.status(it, geometry, now) }
    }
    return StationMapData(geometry, statuses)
}

fun TrainStatus.level(): DelayLevel = DelayLevel.of(delay, train.hasRealtime)

/**
 * Karte der Strecke mit allen Zügen, die gerade zwischen Start und Ziel unterwegs sind,
 * eingefärbt nach Verspätung. Die Positionen sind aus den Fahrzeiten geschätzt.
 */
@Composable
fun RouteMap(state: BoardState, data: StationMapData, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val positions = data.statuses.filter { it.position != null }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.SHOW_AND_FADEOUT)
            controller.setZoom(6.5)
            controller.setCenter(GeoPoint(51.1, 10.4)) // Deutschland
        }
    }

    // MapView an den Lebenszyklus koppeln (Kacheln laden, Speicher freigeben).
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        mapView.onResume()
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    // Einmal pro Strecke auf die Strecke zoomen, sobald Start und Ziel bekannt sind.
    val hasGeometry = data.geometry != null
    LaunchedEffect(state.route, hasGeometry) {
        data.geometry?.let { fitTo(mapView, it) }
    }

    Box(modifier) {
        AndroidView(
            factory = { mapView },
            update = { map -> render(map, state, data.geometry, positions) },
        )

        if (state.fetchedAt != null && positions.isEmpty()) {
            Surface(
                shape = RoundedCornerShape(8.dp),
                tonalElevation = 3.dp,
                shadowElevation = 2.dp,
                modifier = Modifier.align(Alignment.TopCenter).padding(12.dp),
            ) {
                Text(
                    when {
                        data.geometry == null -> "Suche Bahnhofskoordinaten …"
                        state.moving.isEmpty() -> "Gerade ist kein Zug auf dieser Strecke unterwegs."
                        else -> "Positionen werden berechnet …"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        Legend(positions, data.geometry?.trackShare ?: 0.0, state.trackStatus, Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 24.dp))
    }
}

@Composable
private fun Legend(positions: List<TrainStatus>, trackShare: Double, trackStatus: String?, modifier: Modifier) {
    val counts = positions.groupingBy { it.level() }.eachCount()
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shadowElevation = 2.dp,
        modifier = modifier,
    ) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Verspätung", style = MaterialTheme.typography.labelMedium)
            DelayLevel.entries.forEach { lvl ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).background(Color(lvl.argb), CircleShape))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        lvl.label + (counts[lvl]?.let { "  ($it)" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }
            Text(
                "Positionen geschätzt",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (trackShare > 0) {
                Text(
                    "Auf Gleisen: ${(trackShare * 100).toInt()} % · © OpenStreetMap",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            trackStatus?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (it.contains("geladen")) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

private fun fitTo(map: MapView, geometry: CorridorGeometry) {
    val box = BoundingBox.fromGeoPointsSafe(geometry.points.map { GeoPoint(it.lat, it.lon) })
        .increaseByScale(1.25f)
    val zoom = { map.zoomToBoundingBox(box, false, 48) }
    if (map.width > 0 && map.height > 0) zoom() else map.addOnFirstLayoutListener { _, _, _, _, _ -> zoom() }
}

private fun render(map: MapView, state: BoardState, geometry: CorridorGeometry?, positions: List<TrainStatus>) {
    val ctx = map.context
    map.overlays.clear()

    // Strecke als Linie über alle Bahnhöfe (fehlende Koordinaten vorläufig aufgefüllt).
    if (geometry != null) {
        map.overlays.add(Polyline(map).apply {
            setPoints(geometry.points.map { GeoPoint(it.lat, it.lon) })
            outlinePaint.color = 0xCC1565C0.toInt()
            outlinePaint.strokeWidth = 6f * ctx.resources.displayMetrics.density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            setOnClickListener { _, _, _ -> false }
        })
    }

    // Bahnhöfe mit bekannten Koordinaten
    state.corridor.forEachIndexed { i, name ->
        val p = state.coordinates[name] ?: return@forEachIndexed
        val isEnd = i == 0 || i == state.corridor.lastIndex
        map.overlays.add(Marker(map).apply {
            position = GeoPoint(p.lat, p.lon)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = stationIcon(ctx, isEnd)
            title = name
        })
    }

    // Züge – verspätete zuletzt zeichnen, damit sie oben liegen
    positions.sortedBy { it.delay ?: 0 }.forEach { s ->
        val t = s.train
        val pos = s.position ?: return@forEach
        map.overlays.add(Marker(map).apply {
            position = GeoPoint(pos.lat, pos.lon)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = trainIcon(ctx, t.train, s.level())
            title = if (t.destination.isNotBlank()) "${t.train} → ${t.destination}" else t.train
            snippet = buildString {
                append("Richtung ${if (t.direction > 0) state.route.to else state.route.from}<br>")
                append(delayText(s.delay, t.hasRealtime))
                s.next?.let { n ->
                    state.corridor.getOrNull(n.index)?.let { append("<br>$it: ${n.time.format(HM)}") }
                }
                s.progress?.let { append("<br>${(it * 100).toInt()} % seines Weges auf dieser Strecke") }
                append(
                    if (t.detailed) "<br>Position aus DB-Fahrtverlauf (alle Halte)"
                    else "<br>Position geschätzt (Abfahrtstafeln)",
                )
            }
        })
    }

    map.overlays.add(CopyrightOverlay(ctx))
    map.invalidate()
}

fun delayText(delay: Int?, hasRealtime: Boolean): String = when {
    delay == null && !hasRealtime -> "Keine Echtzeitdaten"
    delay == null || delay == 0 -> "Pünktlich"
    delay > 0 -> "+$delay Min. Verspätung"
    else -> "${-delay} Min. früher"
}

private fun stationIcon(ctx: Context, isEnd: Boolean): BitmapDrawable {
    val d = ctx.resources.displayMetrics.density
    val r = (if (isEnd) 7f else 4.5f) * d
    val size = (r * 2 + 4 * d).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.WHITE
    canvas.drawCircle(size / 2f, size / 2f, r + 1.5f * d, paint)
    paint.color = if (isEnd) 0xFF0D47A1.toInt() else 0xFF546E7A.toInt()
    canvas.drawCircle(size / 2f, size / 2f, r, paint)
    return BitmapDrawable(ctx.resources, bmp)
}

/** Farbiges Schild mit Zugname, z. B. „ICE 597“. */
private fun trainIcon(ctx: Context, train: String, lvl: DelayLevel): BitmapDrawable {
    val d = ctx.resources.displayMetrics.density
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 12f * d
        typeface = Typeface.DEFAULT_BOLD
        color = if (lvl.darkText) android.graphics.Color.BLACK else android.graphics.Color.WHITE
    }
    val padH = 7f * d
    val padV = 4f * d
    val stroke = 2f * d
    val w = (text.measureText(train) + padH * 2 + stroke * 2).toInt()
    val h = (text.textSize + padV * 2 + stroke * 2).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    val rect = RectF(stroke / 2, stroke / 2, w - stroke / 2, h - stroke / 2)
    val radius = h / 2f
    canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = lvl.argb.toInt()
    })
    canvas.drawRoundRect(rect, radius, radius, Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
        color = android.graphics.Color.WHITE
    })
    val baseline = h / 2f - (text.descent() + text.ascent()) / 2
    canvas.drawText(train, stroke + padH, baseline, text)
    return BitmapDrawable(ctx.resources, bmp)
}
