package io.github.jqssun.airplay.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.NetworkCheck
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import io.github.jqssun.airplay.service.NetworkCheck
import io.github.jqssun.airplay.service.NetworkCheck.Reason
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.viewmodel.MainViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogsScreen(viewModel: MainViewModel) {
    val logs by viewModel.logs.collectAsState()
    val listState = rememberLazyListState()
    var check by remember { mutableStateOf<List<NetworkCheck.Result>?>(null) }

    // follow new lines only while the reader is at the end; animating every line in a burst janks
    val following by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            (info.visibleItemsInfo.lastOrNull()?.index ?: -1) >= info.totalItemsCount - 2
        }
    }
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty() && following) listState.scrollToItem(logs.size - 1)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.tab_logs)) },
                actions = {
                    IconButton(onClick = { check = viewModel.networkCheck() }, modifier = Modifier.dpadFocus()) {
                        Icon(Icons.Outlined.NetworkCheck, stringResource(R.string.netcheck_run))
                    }
                    IconButton(onClick = viewModel::exportLogs, modifier = Modifier.dpadFocus()) {
                        Icon(Icons.Outlined.Share, stringResource(R.string.btn_export))
                    }
                    IconButton(onClick = viewModel::clearLogs, modifier = Modifier.dpadFocus()) {
                        Icon(Icons.Outlined.Delete, stringResource(R.string.btn_clear))
                    }
                },
            )
        },
    ) { padding ->
        SelectionContainer(Modifier.padding(padding)) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                check?.let { results ->
                    item(contentType = "check") { NetworkCheckCard(results) }
                }
                items(logs, contentType = { "log" }) {
                    Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
                }
            }
        }
    }
}

/** The network check, one line per item: its outcome in colour, what it means and what to do. */
@Composable
private fun NetworkCheckCard(results: List<NetworkCheck.Result>) {
    Card(Modifier.padding(bottom = 12.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.netcheck_title), style = MaterialTheme.typography.titleMedium)
            results.forEach { r ->
                val (label, color) = when (r.outcome) {
                    NetworkCheck.Outcome.PASS -> stringResource(R.string.netcheck_pass) to Color(0xFF69F0AE)
                    NetworkCheck.Outcome.PROBLEM -> stringResource(R.string.netcheck_problem) to Color(0xFFFF8A80)
                    NetworkCheck.Outcome.CANT_VERIFY -> stringResource(R.string.netcheck_cant_verify) to Color(0xFFFFD180)
                }
                Row {
                    Text(label, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(140.dp))
                    Column {
                        Text(stringResource(itemTitle(r.item)), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(reasonText(r.reason), r.detail), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

private fun itemTitle(item: NetworkCheck.Item) = when (item) {
    NetworkCheck.Item.RECEIVER -> R.string.netcheck_item_receiver
    NetworkCheck.Item.NETWORK -> R.string.netcheck_item_network
    NetworkCheck.Item.AIRPLAY_SERVICE -> R.string.netcheck_item_airplay
    NetworkCheck.Item.RAOP_SERVICE -> R.string.netcheck_item_raop
    NetworkCheck.Item.SAME_NETWORK -> R.string.netcheck_item_same_network
}

private fun reasonText(reason: Reason) = when (reason) {
    Reason.RECEIVER_RUNNING -> R.string.netcheck_receiver_running
    Reason.RECEIVER_OFF -> R.string.netcheck_receiver_off
    Reason.RECEIVER_ERROR -> R.string.netcheck_receiver_error
    Reason.NETWORK_WIFI -> R.string.netcheck_network_wifi
    Reason.NETWORK_ETHERNET -> R.string.netcheck_network_ethernet
    Reason.NETWORK_OTHER -> R.string.netcheck_network_other
    Reason.NETWORK_NO_ADDRESS -> R.string.netcheck_network_no_address
    Reason.NETWORK_NONE -> R.string.netcheck_network_none
    Reason.SERVICE_REGISTERED -> R.string.netcheck_service_registered
    Reason.SERVICE_OWN -> R.string.netcheck_service_own
    Reason.SERVICE_PENDING -> R.string.netcheck_service_pending
    Reason.SERVICE_FAILED -> R.string.netcheck_service_failed
    Reason.SERVICE_OFF -> R.string.netcheck_service_off
    Reason.SERVICE_USB -> R.string.netcheck_service_usb
    Reason.SERVICE_NOT_USED -> R.string.netcheck_service_not_used
    Reason.EXTERNAL -> R.string.netcheck_external
}
