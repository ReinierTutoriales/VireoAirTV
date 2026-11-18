package io.github.jqssun.airplay.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.renderer.TrackChoice

// same panel as PlaybackSpeedSelector: portrait slides up from the bottom, landscape in from the end
@Composable
fun BoxScope.TrackSelector(
    show: Boolean,
    audio: List<TrackChoice>,
    subtitles: List<TrackChoice>,
    onAudio: (TrackChoice) -> Unit,
    onSubtitle: (TrackChoice) -> Unit,
    onDismiss: () -> Unit,
) {
    val portrait = LocalConfiguration.current.orientation == Configuration.ORIENTATION_PORTRAIT
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(show) {
        if (show) focusRequester.requestFocusUntilLanded(attempts = 5)
    }
    if (show) {
        BackHandler(onBack = onDismiss)
        Box(Modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { onDismiss() } })
    }
    AnimatedVisibility(
        modifier = Modifier.align(if (portrait) Alignment.BottomCenter else Alignment.CenterEnd),
        visible = show,
        enter = if (portrait) slideInVertically { it } else slideInHorizontally { it },
        exit = if (portrait) slideOutVertically { it } else slideOutHorizontally { it },
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            modifier = if (portrait) Modifier.fillMaxWidth().fillMaxHeight(0.45f)
            else Modifier.fillMaxWidth(0.45f).fillMaxHeight(),
        ) {
            val endPadding = WindowInsets.safeDrawing.asPaddingValues().calculateEndPadding(LocalLayoutDirection.current)
            Column(
                modifier = Modifier
                    .focusRequester(focusRequester)
                    .focusGroup()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 24.dp, bottom = 24.dp)
                    .padding(end = endPadding)
            ) {
                Text(
                    modifier = Modifier.padding(horizontal = 24.dp),
                    text = stringResource(R.string.tracks_title),
                    style = MaterialTheme.typography.headlineSmall,
                )
                if (audio.size > 1) TrackSection(stringResource(R.string.tracks_audio), audio, onAudio)
                if (subtitles.isNotEmpty()) TrackSection(stringResource(R.string.tracks_subtitles), subtitles, onSubtitle)
            }
        }
    }
}

@Composable
private fun TrackSection(title: String, choices: List<TrackChoice>, onSelect: (TrackChoice) -> Unit) {
    Spacer(Modifier.size(16.dp))
    Text(
        modifier = Modifier.padding(horizontal = 24.dp),
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.size(4.dp))
    choices.forEach { choice ->
        val shape = RoundedCornerShape(8.dp)
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .clip(shape)
                .focusRing(shape)
                .clickable { onSelect(choice) }
                .padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
                if (choice.selected) Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            }
            Text(choice.label, style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Subtitle text over the bottom of the picture, readable on any background. */
@Composable
fun BoxScope.SubtitleText(text: String) {
    if (text.isEmpty()) return
    Text(
        text = text,
        color = Color.White,
        style = MaterialTheme.typography.titleLarge,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 48.dp, start = 48.dp, end = 48.dp)
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(6.dp))
            .padding(horizontal = 12.dp, vertical = 4.dp),
    )
}
