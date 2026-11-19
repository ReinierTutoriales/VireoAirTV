package io.github.jqssun.airplay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.service.AirPlayService.ServerState

/**
 * Default TV standby screen: pure black with the receiver's room name and status, no chrome or tabs.
 * Pressing OK or Menu reveals the app's settings/logs panel on demand.
 */
@Composable
fun TvStandbyScreen(
    serverName: String,
    state: ServerState,
    needsLocalNetwork: Boolean,
    onOpenMenu: () -> Unit,
    onGrantLocalNetwork: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val currentOpenMenu by rememberUpdatedState(onOpenMenu)
    val currentGrant by rememberUpdatedState(onGrantLocalNetwork)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocusUntilLanded(attempts = 5) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(needsLocalNetwork) {
                detectTapGestures {
                    if (needsLocalNetwork) currentGrant() else currentOpenMenu()
                }
            }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyUp) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (needsLocalNetwork) currentGrant() else currentOpenMenu()
                        true
                    }
                    Key.Menu -> {
                        currentOpenMenu()
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(focus)
            .focusable(),
        contentAlignment = Alignment.Center,
    ) {
        val scale = minOf(maxWidth / 960.dp, maxHeight / 540.dp).coerceIn(0.6f, 2.2f)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = 48.dp * scale),
        ) {
            Icon(
                imageVector = Icons.Rounded.Cast,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.55f),
                modifier = Modifier.size(56.dp * scale),
            )
            Spacer(Modifier.height(20.dp * scale))
            Text(
                text = serverName,
                style = TextStyle(
                    fontSize = (34 * scale).sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                ),
            )
            Spacer(Modifier.height(10.dp * scale))
            val statusText = if (needsLocalNetwork) {
                stringResource(R.string.local_network_desc)
            } else {
                when (state) {
                    ServerState.RUNNING -> stringResource(R.string.waiting_for_connection)
                    ServerState.STOPPED -> stringResource(R.string.server_stopped)
                    ServerState.ERROR -> stringResource(R.string.error_starting_server)
                }
            }
            Text(
                text = statusText,
                style = TextStyle(
                    fontSize = (18 * scale).sp,
                    color = Color.White.copy(alpha = 0.65f),
                    textAlign = TextAlign.Center,
                ),
            )
        }
        Text(
            text = stringResource(R.string.tv_standby_menu_hint),
            style = TextStyle(
                fontSize = (14 * scale).sp,
                color = Color.White.copy(alpha = 0.35f),
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp * scale),
        )
    }
}
