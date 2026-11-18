package io.github.jqssun.airplay.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import io.github.jqssun.airplay.ui.isTv

@Composable
fun AirPlayTheme(
    // a TV is watched in a dim room and panels are often OLED: always dark there, with true black
    tv: Boolean = isTv(),
    darkTheme: Boolean = tv || isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        darkTheme -> darkColorScheme()
        else -> lightColorScheme()
    }.let { if (tv) it.tvBlack() else it }

    MaterialTheme(colorScheme = colorScheme, content = content)
}

// true black with near-black containers (Apple TV and OLED panels): cards and bars stay distinct
// without the grey slabs of the stock dark scheme
private fun androidx.compose.material3.ColorScheme.tvBlack() = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceVariant = Color(0xFF1C1C1E),
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0E0E10),
    surfaceContainer = Color(0xFF151517),
    surfaceContainerHigh = Color(0xFF1C1C1E),
    surfaceContainerHighest = Color(0xFF242426),
)
