package io.github.jqssun.airplay.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.service.AirPlayService

/**
 * A new sender asking to connect, while new devices are asked about: Allow or Block, remembered and
 * changeable in Settings. The sender was turned away meanwhile, so once allowed the dialog says to
 * connect again. Adapted from CastBay's DeviceRequestDialog. An [AlertDialog] has its own window, so
 * the remote moves between its two buttons whatever holds focus below.
 */
@Composable
fun DeviceRequestDialog(
    request: AirPlayService.DeviceRequest?,
    onAnswer: (allow: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    if (request == null) return
    val focus = remember { FocusRequester() }
    if (!request.allowed) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.device_request_title, request.name)) },
            text = { Text(stringResource(R.string.device_request_help)) },
            confirmButton = {
                TextButton(onClick = { onAnswer(true) }, modifier = Modifier.focusRequester(focus)) {
                    Text(stringResource(R.string.btn_allow))
                }
                // inside the dialog's window: requested from outside, it runs before the button exists
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            },
            dismissButton = {
                TextButton(onClick = { onAnswer(false) }) { Text(stringResource(R.string.device_block)) }
            },
        )
    } else {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.device_request_allowed, request.name)) },
            text = { Text(stringResource(R.string.device_request_try_again)) },
            confirmButton = {
                TextButton(onClick = onDismiss, modifier = Modifier.focusRequester(focus)) {
                    Text(stringResource(R.string.btn_ok))
                }
                LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
            },
        )
    }
}
