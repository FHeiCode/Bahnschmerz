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
import de.bahnlive.app.data.Connection
import de.bahnlive.app.data.DelayLevel
import de.bahnlive.app.data.LatLon
import de.bahnlive.app.data.PositionEstimator
import de.bahnlive.app.data.TrainPosition
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

/**
 * Karte der Strecke mit allen Zügen, die gerade zwischen Start und Ziel unterwegs sind.
 * Die Züge werden nach Verspätung eingefärbt; ihre Position ist aus den Fahrzeiten geschätzt.
 */
@Composable
fun RouteMap(state: BoardState, modifier: Modifier = Modifier) {
    val context = LocalContext.current

    // Positionen alle 10 Sekunden neu schätzen, damit sich die Züge bewegen.
    var now by remember { mutableStateOf(LocalDateTime.now()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(10_000)
            now = LocalDateTime.now()
        }
    }

    val positions = remember(state.moving, state.coordinates, now) {
        state.moving.mapNotNull { c -> PositionEstimator.estimate(c, now) { state.coordinates[it] } }
    }
    val path = remember(state.route, state.connections, state.moving, state.coordinates) {
        routePath(state)
    }

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

    // Einmal pro Strecke auf Start und Ziel zoomen, sobald beide Koordinaten bekannt sind.
    val ends = listOfNotNull(state.coordinates[state.route.from], state.coordinates[state.route.to])
    LaunchedEffect(state.route, ends.size == 2) {
        if (ends.size == 2) fitTo(mapView, path.ifEmpty { ends })
    }

    Box(modifier) {
        AndroidView(
            factory = { mapView },
            update = { map -> render(map, state, path, positions) },
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
                        ends.size < 2 -> "Suche Bahnhofskoordinaten …"
                        state.moving.isEmpty() -> "Gerade ist kein Zug auf dieser Strecke unterwegs."
                        else -> "Positionen werden berechnet …"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }

        Legend(positions, Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 24.dp))
    }
}

@Composable
private fun Legend(positions: List<TrainPosition>, modifier: Modifier) {
    val counts = positions.groupingBy { level(it.connection) }.eachCount()
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
        }
    }
}

private fun level(c: Connection) = DelayLevel.of(c.currentDelay, c.hasRealtime)

/** Linienzug der Strecke A → B über die Zwischenhalte des Zuges mit den meisten bekannten Halten. */
private fun routePath(state: BoardState): List<LatLon> {
    val coords = state.coordinates
    val start = coords[state.route.from] ?: return emptyList()
    val end = coords[state.route.to] ?: return emptyList()
    val best = (state.connections + state.moving)
        .map { c ->
            val stops = c.stopsBetween.mapNotNull { coords[it.name] }
            if (c.from == state.route.from) stops else stops.reversed()
        }
        .maxByOrNull { it.size }
        .orEmpty()
    return listOf(start) + best + listOf(end)
}

private fun fitTo(map: MapView, points: List<LatLon>) {
    if (points.size < 2) return
    val box = BoundingBox.fromGeoPointsSafe(points.map { GeoPoint(it.lat, it.lon) })
        .increaseByScale(1.25f)
    val zoom = { map.zoomToBoundingBox(box, false, 48) }
    if (map.width > 0 && map.height > 0) zoom() else map.addOnFirstLayoutListener { _, _, _, _, _ -> zoom() }
}

private fun render(map: MapView, state: BoardState, path: List<LatLon>, positions: List<TrainPosition>) {
    val ctx = map.context
    map.overlays.clear()

    if (path.size >= 2) {
        map.overlays.add(Polyline(map).apply {
            setPoints(path.map { GeoPoint(it.lat, it.lon) })
            outlinePaint.color = 0xCC1565C0.toInt()
            outlinePaint.strokeWidth = 6f * ctx.resources.displayMetrics.density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            setOnClickListener { _, _, _ -> false }
        })
    }

    // Bahnhöfe der Strecke
    val stationNames = buildList {
        add(state.route.from)
        (state.connections + state.moving).forEach { c -> c.stopsBetween.forEach { add(it.name) } }
        add(state.route.to)
    }.distinct()
    stationNames.forEach { name ->
        val p = state.coordinates[name] ?: return@forEach
        val isEnd = name == state.route.from || name == state.route.to
        map.overlays.add(Marker(map).apply {
            position = GeoPoint(p.lat, p.lon)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = stationIcon(ctx, isEnd)
            title = name
        })
    }

    // Züge – verspätete zuletzt zeichnen, damit sie oben liegen
    positions.sortedBy { it.connection.currentDelay ?: 0 }.forEach { tp ->
        val c = tp.connection
        val lvl = level(c)
        map.overlays.add(Marker(map).apply {
            position = GeoPoint(tp.position.lat, tp.position.lon)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = trainIcon(ctx, c.train, lvl)
            title = "${c.train} → ${c.destination}"
            snippet = buildString {
                append("Richtung ${c.to}<br>")
                append(
                    when (val d = c.currentDelay) {
                        null -> "Keine Echtzeitdaten"
                        0 -> "Pünktlich"
                        else -> if (d > 0) "+$d Min. Verspätung" else "${-d} Min. früher"
                    }
                )
                c.arrivalExpected?.let { append("<br>Ankunft ${c.to}: ${it.format(HM)}") }
                append("<br>${(tp.progress * 100).toInt()} % der Strecke · Position geschätzt")
            }
        })
    }

    map.overlays.add(CopyrightOverlay(ctx))
    map.invalidate()
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
