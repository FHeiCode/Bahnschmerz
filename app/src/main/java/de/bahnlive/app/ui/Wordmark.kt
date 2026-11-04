package de.bahnlive.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp

/** Verzerrung je Buchstabe von „Schmerz“: Drehung (Grad), Höhenversatz, Stauchung, Abstand. */
private data class Twist(val rotation: Float, val dy: Float, val scaleY: Float, val gap: Float)

private val SCHMERZ = listOf(
    Twist(-9f, 2f, 1.06f, 0f),
    Twist(7f, -3f, 0.92f, 1f),
    Twist(-4f, 3f, 1.04f, 0f),
    Twist(11f, -2f, 0.94f, 1f),
    Twist(-7f, 4f, 1.10f, 0f),
    Twist(5f, -4f, 0.90f, 1f),
    Twist(-16f, 6f, 1.02f, 2f),
)

/** Zickzack-Riss unter „Schmerz“ (x, y relativ zu Breite und Schriftgröße). */
private val CRACK = listOf(
    0.00f to 0.00f, 0.10f to -0.09f, 0.17f to 0.03f, 0.27f to -0.12f, 0.34f to -0.03f,
    0.46f to -0.09f, 0.54f to 0.03f, 0.65f to -0.09f, 0.74f to 0.00f, 0.84f to -0.06f,
)

/**
 * Schriftzug „BahnSchmerz“: „Bahn“ gerade, „Schmerz“ rot, mit verdrehten und
 * verrutschten Buchstaben und einem Riss darunter.
 */
@Composable
fun BahnSchmerzWordmark(modifier: Modifier = Modifier, fontSize: TextUnit = 22.sp) {
    val dark = isSystemInDarkTheme()
    val ink = MaterialTheme.colorScheme.onSurface
    val red = if (dark) Color(0xFFFF5A5F) else Color(0xFFD7001E)
    // Die Werte in SCHMERZ sind für 34 sp entworfen und werden mitskaliert.
    val unit = with(LocalDensity.current) { fontSize.toPx() } / 34f

    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("Bahn", color = ink, fontSize = fontSize, fontWeight = FontWeight.Bold)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.drawBehind {
                val path = Path()
                val baseY = size.height * 0.92f
                CRACK.forEachIndexed { i, (fx, fy) ->
                    val x = size.width * (0.03f + fx)
                    val y = baseY + fy * fontSize.toPx()
                    if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                }
                drawPath(path, red.copy(alpha = 0.8f), style = Stroke(width = 1.6f * unit, join = StrokeJoin.Round))
            },
        ) {
            "Schmerz".forEachIndexed { i, ch ->
                val t = SCHMERZ[i]
                Text(
                    ch.toString(),
                    color = red,
                    fontSize = fontSize,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        // Innenabstand darf nie negativ sein – Android beendet sonst die App.
                        .padding(start = with(LocalDensity.current) { (t.gap * unit).coerceAtLeast(0f).toDp() })
                        .graphicsLayer {
                            rotationZ = t.rotation
                            translationY = t.dy * unit
                            scaleY = t.scaleY
                        },
                )
            }
        }
    }
}
