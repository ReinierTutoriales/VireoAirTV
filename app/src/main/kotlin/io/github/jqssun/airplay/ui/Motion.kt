package io.github.jqssun.airplay.ui

import android.graphics.Bitmap
import android.os.Build
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.core.graphics.scale

// one curve and a few durations for every transition, so screens move alike
object Motion {
    // CSS "ease": slow start, long soft landing
    val Ease = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
    const val SHORT_MS = 250
    const val MEDIUM_MS = 450
    const val LONG_MS = 700
}

/**
 * Black layer over a video surface: when [active] turns true (stream starts, or the surface is
 * shown again) the picture fades in from black. A change of [key] while active (the sender rotated,
 * so the stream size changed) dips briefly through black instead of jumping to the new layout.
 * Transparent otherwise, so idle content stays visible.
 */
@Composable
fun FadeFromBlack(active: Boolean, modifier: Modifier = Modifier, key: Any? = null) {
    val alpha = remember { Animatable(if (active) 1f else 0f) }
    var wasActive by remember { mutableStateOf(false) }
    LaunchedEffect(active, key) {
        if (active) {
            val ms = if (wasActive) Motion.SHORT_MS else Motion.MEDIUM_MS
            wasActive = true
            alpha.snapTo(1f)
            alpha.animateTo(0f, tween(ms, easing = Motion.Ease))
        } else {
            wasActive = false
            alpha.snapTo(0f)
        }
    }
    Box(modifier.graphicsLayer { this.alpha = alpha.value }.background(Color.Black))
}

/**
 * Cover art as a soft, dimmed backdrop. The art is reduced to a few pixels once per track and
 * scaled up with filtering, which costs nothing per frame; API 31+ adds a real blur on top.
 */
@Composable
fun ArtworkBackdrop(coverArt: Bitmap?, modifier: Modifier = Modifier) {
    val tiny = remember(coverArt) {
        coverArt?.scale(BACKDROP_PX, BACKDROP_PX)?.asImageBitmap()
    }
    Box(modifier) {
        Crossfade(
            targetState = tiny,
            animationSpec = tween(Motion.LONG_MS, easing = Motion.Ease),
            label = "backdrop"
        ) { image ->
            if (image != null) {
                Image(
                    bitmap = image,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    filterQuality = FilterQuality.High,
                    modifier = Modifier
                        .fillMaxSize()
                        .then(if (Build.VERSION.SDK_INT >= 31) Modifier.blur(48.dp) else Modifier)
                )
            }
        }
        if (tiny != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Black.copy(alpha = 0.7f))))
            )
        }
    }
}

private const val BACKDROP_PX = 24
