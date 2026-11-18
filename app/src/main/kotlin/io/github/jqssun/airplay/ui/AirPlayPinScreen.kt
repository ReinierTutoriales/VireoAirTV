package io.github.jqssun.airplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cast
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jqssun.airplay.R

/**
 * The AirPlay code, full screen on black as Apple TV shows it: one tile per digit, large enough to read
 * from the sofa, and a line saying where to type it. OK or Back dismisses it.
 *
 * The remote is held by [pinKeys] at the root of [MainScreen], not here: the screens below stay
 * composed and may keep focus, and a key handler on this node only sees keys when it is focused.
 */
@Composable
fun AirPlayPinScreen(code: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val dismiss by rememberUpdatedState(onDismiss)
    BoxWithConstraints(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { dismiss() } }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        val scale = minOf(maxWidth / 960.dp, maxHeight / 540.dp).coerceIn(0.6f, 2.2f)
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.Cast, contentDescription = null, tint = Color.White.copy(alpha = 0.55f), modifier = Modifier.size(40.dp * scale))
            Spacer(Modifier.height(20.dp * scale))
            Text(
                stringResource(R.string.dialog_pin_title),
                style = TextStyle(fontSize = (28 * scale).sp, fontWeight = FontWeight.SemiBold, color = Color.White),
            )
            Spacer(Modifier.height(28.dp * scale))
            Row(
                horizontalArrangement = Arrangement.spacedBy(14.dp * scale),
                modifier = Modifier.semantics { contentDescription = code.toList().joinToString(" ") },
            ) {
                code.forEach { digit ->
                    Box(
                        Modifier
                            .size(width = 76.dp * scale, height = 96.dp * scale)
                            .clip(RoundedCornerShape(14.dp * scale))
                            .background(Color.White.copy(alpha = 0.1f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(digit.toString(), style = TextStyle(fontSize = (56 * scale).sp, fontWeight = FontWeight.Bold, color = Color.White))
                    }
                }
            }
            Spacer(Modifier.height(28.dp * scale))
            Text(
                stringResource(R.string.pin_instructions),
                style = TextStyle(fontSize = (18 * scale).sp, color = Color.White.copy(alpha = 0.6f), textAlign = TextAlign.Center),
            )
        }
    }
}

/**
 * Key policy while the code is shown, applied before any focused node sees the key: OK dismisses on
 * release, arrows and Tab go nowhere, everything else (Back, media keys) passes through.
 */
internal fun pinKeys(e: KeyEvent, onDismiss: () -> Unit): Boolean = when (e.key) {
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
        if (e.type == KeyEventType.KeyUp) onDismiss()
        true
    }
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.Tab -> true
    else -> false
}
