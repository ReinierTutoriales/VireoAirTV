package io.github.jqssun.airplay.ui

import android.graphics.Bitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.res.stringResource
import io.github.jqssun.airplay.R
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.min
import androidx.compose.ui.unit.sp
import io.github.jqssun.airplay.viewmodel.SenderCommand
import kotlinx.coroutines.delay

/** What the Apple TV style Now Playing screen shows; plain values, so it can be rendered in tests. */
data class NowPlayingState(
    val title: String,
    val artist: String,
    val album: String,
    val artwork: Bitmap?,
    val positionMs: Long,
    val durationMs: Long,
    val playing: Boolean,
)

/**
 * Full-screen Now Playing laid out like tvOS: the artwork, blurred, fills the screen; the sharp
 * artwork sits on the left and the title, artist, album and a thin progress line on the right.
 * Transport controls appear only while the remote is in use and take tvOS focus (white disc,
 * slight lift). Sizes follow the screen, so 720p, 1080p and 4K panels get the same composition.
 */
@Composable
fun NowPlayingTv(
    state: NowPlayingState,
    controlsVisible: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    playPauseFocus: FocusRequester = remember { FocusRequester() },
) {
    BoxWithConstraints(modifier.fillMaxSize().background(Color.Black)) {
        ArtworkBackdrop(state.artwork, Modifier.fillMaxSize())
        // a soft vignette keeps white text readable over bright artwork
        Box(
            Modifier
                .fillMaxSize()
                .background(Brush.horizontalGradient(listOf(Color.Black.copy(alpha = 0.15f), Color.Black.copy(alpha = 0.45f))))
        )

        // tvOS safe area is about 5 % of each side; the artwork takes a bit over half the height
        val art = min(maxHeight * 0.56f, maxWidth * 0.36f)
        val gap = maxWidth * 0.045f
        val textWidth = min(maxWidth * 0.42f, maxWidth - art - gap - maxWidth * 0.16f)
        val scale = (maxHeight / 540.dp).coerceIn(0.75f, 2.2f)

        val artScale by animateFloatAsState(
            targetValue = if (state.playing) 1f else 0.9f,
            animationSpec = tween(Motion.LONG_MS, easing = Motion.Ease),
            label = "artScale",
        )

        val artModifier = Modifier.graphicsLayer { scaleX = artScale; scaleY = artScale; alpha = if (state.playing) 1f else 0.85f }
        if (maxWidth < maxHeight) {
            // portrait (a phone in full screen): artwork above, text and controls below
            val w = maxWidth
            val h = maxHeight
            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = w * 0.08f, vertical = h * 0.06f),
                verticalArrangement = Arrangement.spacedBy(h * 0.04f, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                NowPlayingArt(state.artwork, artModifier.size(min(w * 0.84f, h * 0.48f)))
                NowPlayingInfo(state, controlsVisible, (w / 400.dp).coerceIn(0.75f, 1.4f), Modifier.fillMaxWidth(),
                    onPrevious, onPlayPause, onNext, playPauseFocus, centered = true)
            }
            return@BoxWithConstraints
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = maxWidth * 0.06f, vertical = maxHeight * 0.06f),
            horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            NowPlayingArt(state.artwork, artModifier.size(art))
            NowPlayingInfo(state, controlsVisible, scale, Modifier.width(textWidth), onPrevious, onPlayPause, onNext, playPauseFocus)
        }
    }
}

// title, artist, album, progress and the transport row that appears while the remote is in use
@Composable
private fun NowPlayingInfo(
    state: NowPlayingState,
    controlsVisible: Boolean,
    scale: Float,
    modifier: Modifier,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    playPauseFocus: FocusRequester,
    centered: Boolean = false,
) {
            Column(modifier, verticalArrangement = Arrangement.Center) {
                Crossfade(
                    targetState = Triple(state.title, state.artist, state.album),
                    animationSpec = tween(Motion.MEDIUM_MS, easing = Motion.Ease),
                    label = "trackText",
                ) { (title, artist, album) ->
                    Column {
                        Text(
                            text = title,
                            style = TextStyle(fontSize = (30 * scale).sp, fontWeight = FontWeight.SemiBold, lineHeight = (36 * scale).sp),
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (artist.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp * scale))
                            Text(
                                text = artist,
                                style = TextStyle(fontSize = (20 * scale).sp, fontWeight = FontWeight.Medium),
                                color = Color.White.copy(alpha = 0.78f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (album.isNotEmpty()) {
                            Spacer(Modifier.height(2.dp * scale))
                            Text(
                                text = album,
                                style = TextStyle(fontSize = (17 * scale).sp),
                                color = Color.White.copy(alpha = 0.55f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                if (state.durationMs > 0) {
                    Spacer(Modifier.height(22.dp * scale))
                    ProgressLine(state.positionMs, state.durationMs, scale)
                }
                AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(tween(Motion.SHORT_MS, easing = Motion.Ease)),
                    exit = fadeOut(tween(Motion.MEDIUM_MS, easing = Motion.Ease)),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 18.dp * scale),
                        horizontalArrangement = Arrangement.spacedBy(14.dp * scale, if (centered) Alignment.CenterHorizontally else Alignment.Start),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TransportButton(Icons.Rounded.SkipPrevious, 44.dp * scale, onPrevious, label = R.string.cd_previous)
                        TransportButton(
                            if (state.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            56.dp * scale,
                            onPlayPause,
                            Modifier.focusRequester(playPauseFocus),
                            label = R.string.cd_play_pause,
                        )
                        TransportButton(Icons.Rounded.SkipNext, 44.dp * scale, onNext, label = R.string.cd_next)
                    }
                }
            }
}

@Composable
private fun NowPlayingArt(artwork: Bitmap?, modifier: Modifier) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier = modifier
            .shadow(32.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(Color(0xFF2C2C2E)),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(artwork, animationSpec = tween(Motion.SHORT_MS, easing = Motion.Ease), label = "art") { bmp ->
            if (bmp != null) {
                Image(bmp.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MusicNote, null, tint = Color.White.copy(alpha = 0.35f), modifier = Modifier.fillMaxSize(0.4f))
                }
            }
        }
    }
}

// thin line with elapsed time on the left and remaining time on the right, as in tvOS
@Composable
private fun ProgressLine(positionMs: Long, durationMs: Long, scale: Float) {
    val fraction = (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(5.dp * scale)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.22f))
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(fraction)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.9f))
            )
        }
        Spacer(Modifier.height(6.dp * scale))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val time = TextStyle(fontSize = (13 * scale).sp, fontWeight = FontWeight.Medium, color = Color.White.copy(alpha = 0.6f))
            Text(formatTime(positionMs), style = time)
            Text("−" + formatTime((durationMs - positionMs).coerceAtLeast(0)), style = time)
        }
    }
}

// tvOS focus: the focused button gets a white disc with a dark glyph and lifts slightly
@Composable
private fun TransportButton(icon: ImageVector, size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier, @androidx.annotation.StringRes label: Int) {
    var focused by remember { mutableStateOf(false) }
    val lift by animateFloatAsState(if (focused) 1.12f else 1f, tween(Motion.SHORT_MS, easing = Motion.Ease), label = "lift")
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = lift; scaleY = lift }
            .shadow(if (focused) 12.dp else 0.dp, CircleShape)
            .clip(CircleShape)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.12f))
            .onFocusChanged { focused = it.isFocused }
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = stringResource(label), tint = if (focused) Color.Black else Color.White, modifier = Modifier.fillMaxSize(0.58f))
    }
}

internal fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * [NowPlayingTv] fed by the service: track metadata, the DACP player for position and play state,
 * and the remote. Controls show on any remote key and hide after [CONTROLS_HIDE_MS]; while hidden,
 * OK plays or pauses and media keys act directly, as on Apple TV.
 */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun NowPlayingTvScreen(viewModel: io.github.jqssun.airplay.viewmodel.MainViewModel, modifier: Modifier = Modifier) {
    val state = rememberNowPlayingState(viewModel)
    val playing = state.playing
    var controlsVisible by remember { mutableStateOf(false) }
    var wake by remember { mutableIntStateOf(0) }
    LaunchedEffect(wake) {
        if (wake == 0) return@LaunchedEffect
        controlsVisible = true
        delay(CONTROLS_HIDE_MS)
        controlsVisible = false
    }

    var feedbackIcon by remember { mutableStateOf<ImageVector?>(null) }
    var feedbackTick by remember { mutableIntStateOf(0) }
    fun send(command: SenderCommand, icon: ImageVector): Boolean {
        if (!viewModel.senderCommand(command)) return false
        feedbackIcon = icon
        feedbackTick++
        return true
    }
    fun playPause() = send(
        SenderCommand.PLAY_PAUSE,
        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
    )

    var scanning by remember { mutableStateOf(false) }
    DisposableEffect(Unit) { onDispose { if (scanning) viewModel.audioScanEnd() } }
    val rootFocus = remember { FocusRequester() }
    val playFocus = remember { FocusRequester() }
    LaunchedEffect(controlsVisible) {
        if (controlsVisible) playFocus.requestFocusUntilLanded(attempts = 5)
        else runCatching { rootFocus.requestFocus() }
    }

    Box(
        modifier
            .fillMaxSize()
            // touch screens and pointer remotes (air mouse): a tap shows the controls
            .pointerInput(Unit) { detectTapGestures { wake++ } }
            .onPreviewKeyEvent { e ->
                // << and >> scan while held, like the hold buttons of the other layouts
                if (e.key == Key.MediaRewind || e.key == Key.MediaFastForward) {
                    when (e.type) {
                        KeyEventType.KeyDown -> if (!scanning) {
                            scanning = true
                            viewModel.audioScanBegin(forward = e.key == Key.MediaFastForward)
                        }
                        KeyEventType.KeyUp -> if (scanning) {
                            scanning = false
                            viewModel.audioScanEnd()
                        }
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.MediaPlayPause -> playPause()
                    Key.MediaPlay -> send(SenderCommand.PLAY, Icons.Rounded.PlayArrow)
                    Key.MediaPause -> send(SenderCommand.PAUSE, Icons.Rounded.Pause)
                    Key.MediaNext -> send(SenderCommand.NEXT, Icons.Rounded.SkipNext)
                    Key.MediaPrevious -> send(SenderCommand.PREVIOUS, Icons.Rounded.SkipPrevious)
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter ->
                        if (!controlsVisible) playPause() else { wake++; false }
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> {
                        val hidden = !controlsVisible
                        wake++
                        hidden // the first press only shows the controls
                    }
                    else -> false
                }
            }
            .focusRequester(rootFocus)
            .focusable(),
    ) {
        NowPlayingTv(
            state = state,
            controlsVisible = controlsVisible,
            onPrevious = { send(SenderCommand.PREVIOUS, Icons.Rounded.SkipPrevious); wake++ },
            onPlayPause = { playPause(); wake++ },
            onNext = { send(SenderCommand.NEXT, Icons.Rounded.SkipNext); wake++ },
            playPauseFocus = playFocus,
        )
        CommandFeedback(feedbackIcon, feedbackTick)
    }
}

private const val CONTROLS_HIDE_MS = 5_000L
private const val PROGRESS_TICK_MS = 500L

/** Track metadata from the service and position and play state from the DACP player, refreshed twice a second. */
@Composable
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun rememberNowPlayingState(viewModel: io.github.jqssun.airplay.viewmodel.MainViewModel): NowPlayingState {
    val track by viewModel.trackInfo.collectAsState()
    val player = viewModel.dacpPlayer
    val playing = player?.let { !androidx.media3.ui.compose.state.rememberPlayPauseButtonState(it).showPlay } ?: false
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(player) {
        while (player != null) {
            positionMs = player.currentPosition.coerceAtLeast(0)
            durationMs = player.duration.takeIf { it != androidx.media3.common.C.TIME_UNSET }?.coerceAtLeast(0) ?: 0
            delay(PROGRESS_TICK_MS)
        }
    }
    return NowPlayingState(
        title = track.title.ifEmpty { androidx.compose.ui.res.stringResource(io.github.jqssun.airplay.R.string.unknown_track) },
        artist = track.artist,
        album = track.album,
        artwork = track.coverArt,
        positionMs = positionMs,
        durationMs = durationMs,
        playing = playing,
    )
}

/** The same Now Playing in the Overview card; OK opens it full screen. */
@Composable
fun NowPlayingTvCard(
    viewModel: io.github.jqssun.airplay.viewmodel.MainViewModel,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    val state = rememberNowPlayingState(viewModel)
    Box(
        modifier
            .fillMaxSize()
            .focusRequester(focusRequester)
            .dpadFocus(RoundedCornerShape(16.dp))
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onOpen)
    ) {
        NowPlayingTv(state, controlsVisible = false, onPrevious = {}, onPlayPause = {}, onNext = {})
    }
}
