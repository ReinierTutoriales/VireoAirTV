package io.github.jqssun.airplay.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer

// one curve and a few durations for every transition, so screens move alike
object Motion {
    // CSS "ease": slow start, long soft landing
    val Ease = CubicBezierEasing(0.25f, 0.1f, 0.25f, 1f)
    const val SHORT_MS = 250
    const val MEDIUM_MS = 450
}

/**
 * Black layer over a video surface: when [active] turns true (stream starts, or the surface is
 * shown again) the picture fades in from black. Transparent otherwise, so idle content stays visible.
 */
@Composable
fun FadeFromBlack(active: Boolean, modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(if (active) 1f else 0f) }
    LaunchedEffect(active) {
        if (active) {
            alpha.snapTo(1f)
            alpha.animateTo(0f, tween(Motion.MEDIUM_MS, easing = Motion.Ease))
        } else {
            alpha.snapTo(0f)
        }
    }
    Box(modifier.graphicsLayer { this.alpha = alpha.value }.background(Color.Black))
}
