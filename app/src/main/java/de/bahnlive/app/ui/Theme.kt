package de.bahnlive.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val BahnRed = Color(0xFFC8102E)

private val Light = lightColorScheme(
    primary = BahnRed,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFDAD8),
    onPrimaryContainer = Color(0xFF410006),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB3AE),
    onPrimary = Color(0xFF68000F),
    primaryContainer = Color(0xFF93001B),
    onPrimaryContainer = Color(0xFFFFDAD8),
)

/** Farben für Pünktlichkeit: grün = pünktlich, orange = bis 5 Min., rot = mehr. */
object Status {
    val onTime = Color(0xFF2E7D32)
    val slight = Color(0xFFEF6C00)
    val late = Color(0xFFC62828)

    fun forDelay(delay: Int?): Color = when {
        delay == null -> Color.Unspecified
        delay <= 0 -> onTime
        delay <= 5 -> slight
        else -> late
    }
}

@Composable
fun BahnLiveTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        content = content,
    )
}
