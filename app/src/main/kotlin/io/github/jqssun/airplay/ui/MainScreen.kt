package io.github.jqssun.airplay.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Article
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.rounded.BrightnessHigh
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.ui.compose.material3.MiniController
import androidx.media3.ui.compose.material3.buttons.NextButton
import androidx.media3.ui.compose.material3.buttons.PlayPauseButton
import androidx.media3.ui.compose.material3.buttons.PreviousButton
import androidx.media3.ui.compose.material3.indicator.DurationText
import androidx.media3.ui.compose.material3.indicator.PositionText
import androidx.media3.ui.compose.material3.indicator.ProgressSlider
import androidx.media3.ui.compose.state.rememberPlayPauseButtonState
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.audio.TrackInfo
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import io.github.jqssun.airplay.ui.gestures.BrightnessState
import io.github.jqssun.airplay.ui.gestures.DoubleTapIndicator
import io.github.jqssun.airplay.ui.gestures.GestureInfoText
import io.github.jqssun.airplay.ui.gestures.SeekGestureState
import io.github.jqssun.airplay.ui.gestures.ServerVolumeState
import io.github.jqssun.airplay.ui.gestures.TapGestureState
import io.github.jqssun.airplay.ui.gestures.VerticalGesture
import io.github.jqssun.airplay.ui.gestures.VerticalProgressIndicator
import io.github.jqssun.airplay.ui.gestures.VideoContentScale
import io.github.jqssun.airplay.ui.gestures.VideoPlayerGestures
import io.github.jqssun.airplay.ui.gestures.VolumeAndBrightnessGestureState
import io.github.jqssun.airplay.ui.gestures.ZoomState
import io.github.jqssun.airplay.viewmodel.DebugInfo
import io.github.jqssun.airplay.viewmodel.LocalNetworkAccess
import io.github.jqssun.airplay.viewmodel.MainViewModel
import io.github.jqssun.airplay.viewmodel.SenderCommand
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

private enum class Tab(val labelRes: Int, val icon: ImageVector) {
    OVERVIEW(R.string.tab_overview, Icons.Default.Cast),
    LOGS(R.string.tab_logs, Icons.AutoMirrored.Filled.Article),
    SETTINGS(R.string.tab_settings, Icons.Default.Settings)
}

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    isInPip: Boolean = false,
    onSurfaceAvailable: (android.view.Surface) -> Unit,
    onSurfaceDestroyed: (android.view.Surface) -> Unit,
    onPip: () -> Unit = {},
    onLocalNetwork: () -> Unit = {},
    // brought up for a session (Apple TV style): only the sender's picture, never the app screens
    receiverOnly: Boolean = false,
    // Back during such a session: return to the previous app; the session keeps running
    onLeaveReceiverOnly: () -> Unit = {}
) {
    var tab by remember { mutableStateOf(Tab.OVERVIEW) }
    var fullscreen by remember { mutableStateOf(false) }
    val pin by viewModel.pinCode.collectAsState()
    val connections by viewModel.connectionCount.collectAsState()
    val audioOnly by viewModel.audioOnly.collectAsState()
    val videoPlaybackActive by viewModel.videoPlaybackActive.collectAsState()
    val videoSessionPending by viewModel.videoSessionPending.collectAsState()
    val mirroringActive by viewModel.mirroringActive.collectAsState()
    val autoFullscreen by viewModel.pref(Prefs.AUTO_FULLSCREEN).collectAsState()

    // don't use movableContentOf: moving AndroidView across subcomposition boundaries makes it crash on reparent
    val video: @Composable () -> Unit = {
        val aspect by viewModel.videoAspect.collectAsState()
        val mirrorSize by viewModel.mirrorSize.collectAsState()
        val maxFps by viewModel.pref(Prefs.MAX_FPS).collectAsState()
        Box(contentAlignment = Alignment.Center) {
            VideoSurfaceView(
                onSurfaceAvailable = onSurfaceAvailable,
                onSurfaceDestroyed = onSurfaceDestroyed,
                aspectRatio = aspect,
                bufferSize = mirrorSize,
                frameRate = if (mirrorSize != null) maxFps.toFloat() else 0f
            )
            FadeFromBlack(active = mirrorSize != null, modifier = Modifier.matchParentSize(), key = mirrorSize)
        }
    }

    // fullscreen only once mirroring reports a size or audio starts: connections rise before the kind is known
    var prevMirroringActive by remember { mutableStateOf(false) }
    var prevAudioOnly by remember { mutableStateOf(false) }
    LaunchedEffect(mirroringActive, audioOnly, videoPlaybackActive, pin) {
        val mirrorStarted = !prevMirroringActive && mirroringActive
        val audioStarted = !prevAudioOnly && audioOnly
        prevMirroringActive = mirroringActive
        prevAudioOnly = audioOnly
        if ((mirrorStarted || audioStarted) && !videoPlaybackActive && autoFullscreen && pin == null) {
            fullscreen = true
        }
    }

    // manual fullscreen over the idle preview is deliberate; only undo on disconnect
    LaunchedEffect(connections) {
        if (connections == 0) fullscreen = false
    }

    // leaving video playback must not fall through to a stale mirroring fullscreen
    LaunchedEffect(videoPlaybackActive) {
        if (videoPlaybackActive) fullscreen = false
    }

    val activity = LocalActivity.current
    val videoScreen = videoPlaybackActive || videoSessionPending
    LaunchedEffect(fullscreen, videoScreen) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (fullscreen || videoScreen || receiverOnly) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    if (videoScreen) {
        val videoPlaybackAspect by viewModel.videoPlaybackAspect.collectAsState()
        val frameShown by viewModel.videoFrameShown.collectAsState()
        val playback: @Composable () -> Unit = {
            Box(contentAlignment = Alignment.Center) {
                VideoSurfaceView(
                    onSurfaceAvailable = { viewModel.onVideoPlaybackSurfaceAvailable(it) },
                    onSurfaceDestroyed = { viewModel.onVideoPlaybackSurfaceDestroyed(it) },
                    aspectRatio = videoPlaybackAspect
                )
                FadeFromBlack(active = frameShown, modifier = Modifier.matchParentSize())
            }
        }
        if (isInPip) {
            Box(
                modifier = Modifier.fillMaxSize().background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                playback()
            }
            return
        }
        val overlayTick by viewModel.videoOverlayTick.collectAsState()
        val playing by viewModel.videoPlaying.collectAsState()
        val positionMs by viewModel.videoPositionMs.collectAsState()
        val durationMs by viewModel.videoDurationMs.collectAsState()
        val scrubPositionMs by viewModel.videoScrubPositionMs.collectAsState()
        val downloadProgress by viewModel.videoDownloadProgress.collectAsState()
        val scrubbing = scrubPositionMs != null
        val downloading = downloadProgress != null
        var overlayVisible by remember { mutableStateOf(false) }
        LaunchedEffect(overlayTick, playing, scrubbing, downloading, videoPlaybackActive) {
            // tick 0 = fresh session; a pending session or a running download pins the overlay
            if (!videoPlaybackActive) {
                overlayVisible = true
            } else if (overlayTick == 0L) {
                overlayVisible = false
            } else {
                overlayVisible = true
                if (playing && !scrubbing && !downloading) {
                    delay(VIDEO_OVERLAY_HIDE_MS)
                    overlayVisible = false
                }
            }
        }
        val gestureScope = rememberCoroutineScope()
        val tapGestureState = remember(viewModel) { TapGestureState(viewModel, gestureScope) }
        val seekGestureState = remember(viewModel) { SeekGestureState(viewModel) }
        val context = LocalContext.current
        val serverVolume = remember { ServerVolumeState(context) }
        val brightnessState = remember(activity) { activity?.window?.let { BrightnessState(it) } }
        val volumeAndBrightnessGestureState = remember(serverVolume, brightnessState) {
            VolumeAndBrightnessGestureState(serverVolume, brightnessState, gestureScope)
        }
        val zoomState = remember(viewModel) { ZoomState(viewModel, gestureScope) }
        var controlsLocked by remember { mutableStateOf(false) }
        DisposableEffect(serverVolume) { serverVolume.handleLifecycle(this) }
        LaunchedEffect(overlayTick) {
            if (overlayTick == 0L) {
                zoomState.reset()
                controlsLocked = false
            }
        }
        DisposableEffect(brightnessState) {
            onDispose { brightnessState?.clearOverride() }
        }
        // orientation follows the video aspect; manual rotate holds until the next video
        LaunchedEffect(videoPlaybackAspect) {
            activity?.requestedOrientation = if (videoPlaybackAspect < 1f) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            } else {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            }
        }
        DisposableEffect(activity) {
            onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
        }
        val videoPlaybackSize by viewModel.videoPlaybackSize.collectAsState()
        val buffering by viewModel.videoBuffering.collectAsState()
        val videoTitle by viewModel.videoTitle.collectAsState()
        val videoLocation by viewModel.videoLocation.collectAsState()
        val speed by viewModel.videoSpeed.collectAsState()
        val isPipSupported = remember {
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        }
        var showSpeedSelector by remember { mutableStateOf(false) }
        var showTrackSelector by remember { mutableStateOf(false) }
        // audio languages and subtitles of the current video (HLS renditions), refreshed when they change
        val tracksTick by viewModel.videoTracksTick.collectAsState()
        val cues by viewModel.videoCues.collectAsState()
        val trackOff = stringResource(R.string.tracks_off)
        val resources = LocalResources.current
        val audioChoices = remember(tracksTick) {
            viewModel.videoTrackChoices(C.TRACK_TYPE_AUDIO, trackOff) { resources.getString(R.string.tracks_untitled, it) }
        }
        val subtitleChoices = remember(tracksTick) {
            viewModel.videoTrackChoices(C.TRACK_TYPE_TEXT, trackOff) { resources.getString(R.string.tracks_untitled, it) }
        }
        val hasTrackChoices = audioChoices.size > 1 || subtitleChoices.isNotEmpty()
        BackHandler { viewModel.stopVideoPlayback() }

        val rootFocusRequester = remember { FocusRequester() }
        val playPauseFocusRequester = remember { FocusRequester() }
        val unlockFocusRequester = remember { FocusRequester() }
        var isPlayPauseFocused by remember { mutableStateOf(false) }
        var isUnlockFocused by remember { mutableStateOf(false) }
        LaunchedEffect(overlayVisible, controlsLocked, showSpeedSelector, showTrackSelector) {
            if (showSpeedSelector || showTrackSelector) return@LaunchedEffect
            if (!overlayVisible) {
                runCatching { rootFocusRequester.requestFocus() }
                return@LaunchedEffect
            }
            val locked = controlsLocked
            val target = if (locked) unlockFocusRequester else playPauseFocusRequester
            target.requestFocusUntilLanded(attempts = 20) { if (locked) isUnlockFocused else isPlayPauseFocused }
        }

        // dpad seeking (controls hidden): accumulate the skipped amount and briefly show it
        var dpadSeekOffsetMs by remember { mutableLongStateOf(0L) }
        var dpadSeekTargetMs by remember { mutableLongStateOf(0L) }
        var dpadSeekActive by remember { mutableStateOf(false) }
        var dpadSeekTick by remember { mutableIntStateOf(0) }
        LaunchedEffect(dpadSeekTick) {
            if (!dpadSeekActive) return@LaunchedEffect
            delay(1000)
            dpadSeekActive = false
        }
        val showDpadSeekFeedback: (Long) -> Unit = { deltaMs ->
            if (!dpadSeekActive) dpadSeekOffsetMs = 0L
            dpadSeekOffsetMs += deltaMs
            dpadSeekTargetMs = (dpadSeekTargetMs.takeIf { dpadSeekActive } ?: positionMs).plus(deltaMs)
                .coerceIn(0L, if (durationMs > 0) durationMs else Long.MAX_VALUE)
            dpadSeekActive = true
            dpadSeekTick++
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(rootFocusRequester)
                .focusable()
                .onPreviewKeyEvent { keyEvent ->
                    if (showSpeedSelector || showTrackSelector) {
                        false
                    } else {
                        handlePlayerKeyEvent(
                            keyEvent = keyEvent,
                            controlsVisible = overlayVisible,
                            controlsLocked = controlsLocked,
                            isPlayPauseFocused = isPlayPauseFocused,
                            seekIncrementMs = TapGestureState.SEEK_INCREMENT_MS,
                            viewModel = viewModel,
                            showControls = { viewModel.showVideoOverlay() },
                            unlockControls = {
                                controlsLocked = false
                                viewModel.showVideoOverlay()
                            },
                            onDpadSeek = showDpadSeekFeedback,
                        )
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            VideoContentFrame(
                aspect = videoPlaybackAspect,
                videoSizePx = videoPlaybackSize,
                contentScale = zoomState.contentScale,
                zoom = zoomState.zoom
            ) { sizeModifier ->
                VideoSurfaceView(
                    onSurfaceAvailable = { viewModel.onVideoPlaybackSurfaceAvailable(it) },
                    onSurfaceDestroyed = { viewModel.onVideoPlaybackSurfaceDestroyed(it) },
                    applyAspectRatio = false,
                    modifier = sizeModifier
                )
                // same size and transform as the surface; both centered by VideoContentFrame
                FadeFromBlack(active = frameShown, modifier = sizeModifier)
            }
            SubtitleText(cues)
            VideoPlayerGestures(
                enabled = videoPlaybackActive,
                locked = controlsLocked,
                onTap = {
                    // a pending session pins the overlay; taps must not unpin it
                    if (!videoPlaybackActive) return@VideoPlayerGestures
                    if (overlayVisible) overlayVisible = false else viewModel.showVideoOverlay()
                },
                tapGestureState = tapGestureState,
                seekGestureState = seekGestureState,
                volumeAndBrightnessGestureState = volumeAndBrightnessGestureState,
                zoomState = zoomState
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = overlayVisible && videoPlaybackActive && !controlsLocked,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut()
            ) {
                Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.3f)))
            }
            if (buffering) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(72.dp))
            }
            DoubleTapIndicator(tapGestureState = tapGestureState)
            val seekAmountMs = seekGestureState.seekAmountMs
            when {
                seekAmountMs != null -> GestureInfoText(
                    info = "${if (seekAmountMs < 0) "-" else "+"}${formatVideoTime(abs(seekAmountMs))}\n" +
                        "[${formatVideoTime(seekGestureState.targetPositionMs ?: 0)}]",
                    modifier = Modifier.align(Alignment.Center)
                )
                zoomState.isZooming -> GestureInfoText(
                    info = "${(zoomState.zoom * 100).toInt()}%",
                    modifier = Modifier.align(Alignment.Center)
                )
                zoomState.showContentScaleIndicator -> GestureInfoText(
                    info = stringResource(zoomState.contentScale.nameRes()),
                    modifier = Modifier.align(Alignment.Center)
                )
                else -> androidx.compose.animation.AnimatedVisibility(
                    visible = overlayVisible && videoPlaybackActive && !controlsLocked,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.Center)
                ) {
                    PlayPauseButton(
                        playing = playing,
                        onClick = { viewModel.toggleVideoPlayPause() },
                        modifier = Modifier
                            .focusRequester(playPauseFocusRequester)
                            .onFocusChanged { isPlayPauseFocused = it.hasFocus }
                    )
                }
            }
            DpadSeekIndicator(
                visible = dpadSeekActive && dpadSeekOffsetMs != 0L,
                offsetMs = dpadSeekOffsetMs,
                positionMs = dpadSeekTargetMs
            )
            androidx.compose.animation.AnimatedVisibility(
                visible = volumeAndBrightnessGestureState.activeGesture == VerticalGesture.VOLUME,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.CenterStart).padding(24.dp)
            ) {
                VerticalProgressIndicator(value = serverVolume.percentage, icon = Icons.AutoMirrored.Rounded.VolumeUp)
            }
            androidx.compose.animation.AnimatedVisibility(
                visible = volumeAndBrightnessGestureState.activeGesture == VerticalGesture.BRIGHTNESS,
                enter = androidx.compose.animation.fadeIn(),
                exit = androidx.compose.animation.fadeOut(),
                modifier = Modifier.align(Alignment.CenterEnd).padding(24.dp)
            ) {
                VerticalProgressIndicator(value = brightnessState?.percentage ?: 0, icon = Icons.Rounded.BrightnessHigh)
            }
            if (controlsLocked) {
                if (overlayVisible) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .safeDrawingPadding()
                            .padding(top = 24.dp)
                    ) {
                        UnlockButton(
                            onClick = {
                                controlsLocked = false
                                viewModel.showVideoOverlay()
                            },
                            modifier = Modifier
                                .focusRequester(unlockFocusRequester)
                                .onFocusChanged { isUnlockFocused = it.hasFocus }
                        )
                    }
                }
            } else {
                androidx.compose.animation.AnimatedVisibility(
                    visible = overlayVisible,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    VideoControlsTop(
                        title = videoTitle,
                        videoUrl = videoLocation,
                        downloadProgress = downloadProgress,
                        showDownload = durationMs > 0,
                        onBackClick = { viewModel.stopVideoPlayback() },
                        onSpeedClick = {
                            overlayVisible = false
                            showSpeedSelector = true
                        },
                        onTracksClick = if (hasTrackChoices) {
                            {
                                overlayVisible = false
                                showTrackSelector = true
                            }
                        } else null,
                        onDownloadClick = { viewModel.toggleVideoDownload() }
                    )
                }
                androidx.compose.animation.AnimatedVisibility(
                    visible = overlayVisible,
                    enter = androidx.compose.animation.fadeIn(),
                    exit = androidx.compose.animation.fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                    VideoControlsBottom(
                        positionMs = scrubPositionMs ?: positionMs,
                        durationMs = durationMs,
                        contentScale = zoomState.contentScale,
                        isPipSupported = isPipSupported,
                        onRotateClick = {
                            activity?.let {
                                it.requestedOrientation =
                                    if (it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
                                    } else {
                                        ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                                    }
                            }
                        },
                        onLockClick = {
                            viewModel.showVideoOverlay()
                            controlsLocked = true
                        },
                        onContentScaleClick = {
                            viewModel.showVideoOverlay()
                            zoomState.switchToNextContentScale()
                        },
                        onPipClick = onPip,
                        onSeek = { seekGestureState.onSeek(it) },
                        onSeekEnd = { seekGestureState.onSeekEnd() },
                        seekBarModifier = Modifier
                            .focusProperties { up = playPauseFocusRequester }
                            .dpadAdjust(
                                onLeft = {
                                    viewModel.seekVideoBy(-TapGestureState.SEEK_INCREMENT_MS)
                                    viewModel.showVideoOverlay()
                                },
                                onRight = {
                                    viewModel.seekVideoBy(TapGestureState.SEEK_INCREMENT_MS)
                                    viewModel.showVideoOverlay()
                                }
                            )
                    )
                }
            }
            val skipSilence by viewModel.videoSkipSilence.collectAsState()
            PlaybackSpeedSelector(
                show = showSpeedSelector,
                speed = speed,
                skipSilence = skipSilence,
                onSpeedChange = { viewModel.setVideoSpeed(it) },
                onSkipSilenceChange = { viewModel.setVideoSkipSilence(it) },
                onDismiss = { showSpeedSelector = false }
            )
            TrackSelector(
                show = showTrackSelector,
                audio = audioChoices,
                subtitles = subtitleChoices,
                onAudio = { viewModel.selectVideoTrack(C.TRACK_TYPE_AUDIO, it) },
                onSubtitle = { viewModel.selectVideoTrack(C.TRACK_TYPE_TEXT, it) },
                onDismiss = { showTrackSelector = false }
            )
        }
        return
    }

    // pip mode: show only the video surface
    if (isInPip) {
        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            video()
        }
        return
    }

    // restore system bars when exiting pip back to non-fullscreen
    LaunchedEffect(isInPip) {
        if (!isInPip && !fullscreen) {
            val window = activity?.window ?: return@LaunchedEffect
            WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // exit fullscreen while a pin is being shown so the dialog isn't covered
    LaunchedEffect(pin) {
        if (pin != null) fullscreen = false
    }

    if (receiverOnly) {
        BackHandler { onLeaveReceiverOnly() }
        if (mirroringActive) {
            FullscreenVideo(
                viewModel = viewModel,
                video = video,
                onExitFullscreen = onLeaveReceiverOnly,
                onPip = onPip,
                quiet = true
            )
        } else {
            // between pictures (and before returning to the previous app) hold black, not the app
            Box(Modifier.fillMaxSize().background(Color.Black))
        }
    } else if (fullscreen) {
        BackHandler { fullscreen = false }
        if (audioOnly && connections > 0) {
            NowPlayingContent(
                viewModel = viewModel,
                fullscreen = true,
                onExitFullscreen = { fullscreen = false }
            )
        } else {
            FullscreenVideo(
                viewModel = viewModel,
                video = video,
                onExitFullscreen = { fullscreen = false },
                onPip = onPip
            )
        }
    } else {
        Scaffold(
            bottomBar = {
                Column {
                    AudioMiniController(
                        viewModel,
                        visible = audioOnly && connections > 0 && tab != Tab.OVERVIEW,
                        onClick = { tab = Tab.OVERVIEW }
                    )
                    NavigationBar {
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, null) },
                                label = { Text(stringResource(t.labelRes)) },
                                modifier = Modifier.dpadFocus()
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
                TabContent(
                    tab, viewModel, video,
                    onFullscreen = { fullscreen = true }, onPip = onPip, onLocalNetwork = onLocalNetwork,
                    showAudioMode = audioOnly
                )
            }
        }
    }

    // pin dialog
    if (pin != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPin() },
            title = { Text(stringResource(R.string.dialog_pin_title)) },
            text = {
                Text(
                    text = pin!!,
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissPin() }) { Text(stringResource(R.string.btn_ok)) }
            }
        )
    }

}

@Composable
private fun TabContent(
    tab: Tab,
    viewModel: MainViewModel,
    video: @Composable () -> Unit,
    onFullscreen: () -> Unit,
    onPip: () -> Unit,
    onLocalNetwork: () -> Unit,
    showAudioMode: Boolean
) {
    when (tab) {
        Tab.OVERVIEW -> OverviewContent(
            viewModel, video,
            onFullscreen = onFullscreen, onPip = onPip, onLocalNetwork = onLocalNetwork, showAudioMode = showAudioMode
        )
        Tab.LOGS -> LogsScreen(viewModel)
        Tab.SETTINGS -> SettingsScreen(viewModel)
    }
}

@Composable
private fun OverviewContent(
    viewModel: MainViewModel,
    video: @Composable () -> Unit,
    onFullscreen: () -> Unit,
    onPip: () -> Unit,
    onLocalNetwork: () -> Unit,
    showAudioMode: Boolean = false
) {
    val state by viewModel.serverState.collectAsState()
    val localNetwork by viewModel.localNetwork.collectAsState()
    val grant = state != ServerState.RUNNING && localNetwork != LocalNetworkAccess.GRANTED
    val connections by viewModel.connectionCount.collectAsState()
    val serverName by viewModel.pref(Prefs.SERVER_NAME).collectAsState()
    val videoResolution by viewModel.videoResolution.collectAsState()
    val idlePreview by viewModel.pref(Prefs.IDLE_PREVIEW).collectAsState()
    val mirroringActive by viewModel.mirroringActive.collectAsState()
    val videoPlaybackActive by viewModel.videoPlaybackActive.collectAsState()
    val debugEnabled by viewModel.pref(Prefs.DEBUG_ENABLED).collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()
    val tv = isTv()
    val startFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (tv) startFocus.requestFocus()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // content area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            val connecting = state == ServerState.RUNNING && connections > 0 &&
                !mirroringActive && !videoPlaybackActive
            if (showAudioMode && state == ServerState.RUNNING && connections > 0) {
                NowPlayingContent(viewModel, onFullscreen = onFullscreen)
            } else {
                if (state == ServerState.RUNNING && (mirroringActive || idlePreview)) {
                    video()
                }
                if (state != ServerState.RUNNING || connections == 0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = if (connections > 0) Icons.Default.CastConnected else Icons.Default.Cast,
                            contentDescription = null,
                            modifier = Modifier.size(64.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = if (grant) stringResource(R.string.local_network_desc) else when (state) {
                                ServerState.STOPPED -> stringResource(R.string.server_stopped)
                                ServerState.RUNNING -> stringResource(R.string.waiting_for_connection)
                                ServerState.ERROR -> stringResource(R.string.error_starting_server)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }
                } else if (connecting) {
                    Text(
                        text = stringResource(R.string.waiting_for_playback),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
                if (state == ServerState.RUNNING && mirroringActive) {
                    Row(modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)) {
                        IconButton(onClick = onPip, modifier = Modifier.dpadFocus()) {
                            Icon(
                                painterResource(R.drawable.ic_pip), contentDescription = stringResource(R.string.cd_pip),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                        IconButton(onClick = onFullscreen, modifier = Modifier.dpadFocus()) {
                            Icon(
                                Icons.Default.Fullscreen, contentDescription = stringResource(R.string.cd_fullscreen),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                }
            }
            if (debugEnabled && connections > 0) {
                DebugOverlay(debugInfo, Modifier.align(Alignment.TopStart).padding(8.dp))
            }
            var showRes by remember { mutableStateOf(false) }
            LaunchedEffect(videoResolution) {
                if (videoResolution.isNotEmpty() && !showAudioMode) {
                    showRes = true
                    delay(5000)
                    showRes = false
                }
            }
            if (!showAudioMode) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = showRes && connections > 0,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
                ) {
                    Text(
                        text = videoResolution,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
            }
        }

        // controls
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp)
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = serverName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(Modifier.height(2.dp))
                        val statusColor by animateColorAsState(
                            if (grant) MaterialTheme.colorScheme.error else when (state) {
                                ServerState.RUNNING -> MaterialTheme.colorScheme.primary
                                ServerState.ERROR -> MaterialTheme.colorScheme.error
                                ServerState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
                            }, label = "status"
                        )
                        Text(
                            text = if (grant) stringResource(R.string.local_network_label) else when (state) {
                                ServerState.RUNNING -> pluralStringResource(R.plurals.connected_count, connections, connections)
                                ServerState.ERROR -> stringResource(R.string.error_label)
                                ServerState.STOPPED -> stringResource(R.string.stopped_label)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = statusColor
                        )
                    }

                    val settings = localNetwork == LocalNetworkAccess.SETTINGS
                    val (icon, label, action) = when {
                        grant -> Triple(
                            if (settings) Icons.Default.Settings else Icons.Default.LockOpen,
                            if (settings) R.string.btn_open_settings else R.string.btn_allow,
                            onLocalNetwork
                        )
                        state == ServerState.RUNNING -> Triple(Icons.Default.Stop, R.string.btn_stop, viewModel::stopServer)
                        else -> Triple(Icons.Default.PlayArrow, R.string.btn_start, viewModel::startServer)
                    }
                    FilledTonalButton(
                        onClick = action,
                        modifier = Modifier.dpadFocus().focusRequester(startFocus)
                    ) {
                        Icon(
                            imageVector = icon,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(label))
                    }
                }
            }
        }
    }
}

@Composable
private fun FullscreenVideo(
    viewModel: MainViewModel,
    video: @Composable () -> Unit,
    onExitFullscreen: () -> Unit,
    onPip: () -> Unit,
    // nothing over the picture until the user asks for it (a tap or key)
    quiet: Boolean = false
) {
    val videoResolution by viewModel.videoResolution.collectAsState()
    val debugEnabled by viewModel.pref(Prefs.DEBUG_ENABLED).collectAsState()
    val debugInfo by viewModel.debugInfo.collectAsState()

    var controlsVisible by remember { mutableStateOf(!quiet) }
    var tapTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(tapTick) {
        if (quiet && tapTick == 0) return@LaunchedEffect
        controlsVisible = true
        delay(8000)
        controlsVisible = false
    }

    // the remote drives the sender's Now Playing app over DACP; with no buttons on screen the
    // center key is play/pause too
    var feedbackIcon by remember { mutableStateOf<ImageVector?>(null) }
    var feedbackTick by remember { mutableIntStateOf(0) }
    // DACP reports no play state while mirroring: a toggle assumes the sender was playing
    var assumedPlaying by remember { mutableStateOf(true) }
    fun send(command: SenderCommand, icon: ImageVector): Boolean {
        if (!viewModel.senderCommand(command)) return false
        feedbackIcon = icon
        feedbackTick++
        return true
    }
    fun toggle(): Boolean {
        val icon = if (assumedPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
        return send(SenderCommand.PLAY_PAUSE, icon).also { if (it) assumedPlaying = !assumedPlaying }
    }
    val focus = remember { FocusRequester() }
    if (quiet) LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures { tapTick++ } }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.MediaPlayPause -> toggle()
                    Key.MediaPlay -> send(SenderCommand.PLAY, Icons.Rounded.PlayArrow).also { if (it) assumedPlaying = true }
                    Key.MediaPause -> send(SenderCommand.PAUSE, Icons.Rounded.Pause).also { if (it) assumedPlaying = false }
                    Key.MediaNext -> send(SenderCommand.NEXT, Icons.Rounded.SkipNext)
                    Key.MediaPrevious -> send(SenderCommand.PREVIOUS, Icons.Rounded.SkipPrevious)
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter ->
                        quiet && !controlsVisible && toggle()
                    else -> false
                }
            }
            .then(if (quiet) Modifier.focusRequester(focus).focusable() else Modifier),
        contentAlignment = Alignment.Center
    ) {
        video()
        CommandFeedback(feedbackIcon, feedbackTick)
        androidx.compose.animation.AnimatedVisibility(
            visible = controlsVisible,
            modifier = Modifier.align(Alignment.TopEnd)
        ) {
            Row(modifier = Modifier.padding(8.dp)) {
                IconButton(onClick = onPip, modifier = Modifier.dpadFocus()) {
                    Icon(
                        painterResource(R.drawable.ic_pip), contentDescription = stringResource(R.string.cd_pip),
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                }
                IconButton(onClick = onExitFullscreen, modifier = Modifier.dpadFocus()) {
                    Icon(
                        Icons.Default.FullscreenExit, contentDescription = stringResource(R.string.cd_exit_fullscreen),
                        tint = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        }
        if (debugEnabled) {
            DebugOverlay(debugInfo, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
        var showRes by remember { mutableStateOf(false) }
        LaunchedEffect(videoResolution) {
            if (videoResolution.isNotEmpty() && (!quiet || debugEnabled)) {
                showRes = true
                delay(5000)
                showRes = false
            }
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = showRes,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp)
        ) {
            Text(
                text = videoResolution,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.6f)
            )
        }
    }
}

// dacp scanning is press-and-hold: beginff/beginrew while held, playresume on release
@Composable
private fun HoldScanButton(
    icon: ImageVector,
    contentDescription: String,
    onBegin: () -> Unit,
    onEnd: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentBegin by rememberUpdatedState(onBegin)
    val currentEnd by rememberUpdatedState(onEnd)
    var keyScanning by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (keyScanning) currentEnd() }
    }
    Box(
        modifier = modifier
            .size(48.dp)
            .dpadFocus(CircleShape)
            .clip(CircleShape)
            .onPreviewKeyEvent { e ->
                when (e.key) {
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> when (e.type) {
                        KeyEventType.KeyDown -> {
                            if (!keyScanning) {
                                keyScanning = true
                                currentBegin()
                            }
                            true
                        }
                        KeyEventType.KeyUp -> {
                            if (keyScanning) {
                                keyScanning = false
                                currentEnd()
                            }
                            true
                        }
                        else -> false
                    }
                    else -> false
                }
            }
            .onFocusChanged {
                if (!it.isFocused && keyScanning) {
                    keyScanning = false
                    currentEnd()
                }
            }
            .focusable()
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    currentBegin()
                    try {
                        awaitRelease()
                    } finally {
                        currentEnd()
                    }
                })
            },
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription)
    }
}

@Composable
@AndroidxOptIn(UnstableApi::class)
private fun AudioMiniController(viewModel: MainViewModel, visible: Boolean, onClick: () -> Unit) {
    if (!visible) return
    val player = viewModel.dacpPlayer ?: return
    val context = LocalContext.current
    MiniController(
        player,
        bitmapLoader = remember { DataSourceBitmapLoader.Builder(context).build() },
        onClick = onClick
    )
}

@Composable
@AndroidxOptIn(UnstableApi::class)
private fun NowPlayingContent(
    viewModel: MainViewModel,
    fullscreen: Boolean = false,
    onFullscreen: (() -> Unit)? = null,
    onExitFullscreen: (() -> Unit)? = null,
) {
    val track by viewModel.trackInfo.collectAsState()
    val hasArt = track.coverArt != null
    // over the dimmed backdrop, text and icons read as on tvOS: white and translucent white
    val base = MaterialTheme.colorScheme
    val colors = if (hasArt || fullscreen) base.copy(
        onSurface = Color.White,
        onSurfaceVariant = Color.White.copy(alpha = 0.72f),
        surfaceContainerHighest = Color.White.copy(alpha = 0.12f)
    ) else base

    val player = viewModel.dacpPlayer
    // artwork recedes while paused, like the Music app
    val playing = player?.let { !rememberPlayPauseButtonState(it).showPlay } ?: true
    val artScale by animateFloatAsState(
        targetValue = if (playing) 1f else 0.86f,
        animationSpec = spring(dampingRatio = 0.75f, stiffness = Spring.StiffnessLow),
        label = "artScale"
    )

    var controlsVisible by remember(fullscreen) { mutableStateOf(true) }
    var wakeTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(fullscreen, wakeTick) {
        if (!fullscreen) {
            controlsVisible = true
            return@LaunchedEffect
        }
        controlsVisible = true
        delay(AUDIO_CONTROLS_HIDE_MS)
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

    val rootFocus = remember { FocusRequester() }
    val playPauseFocus = remember { FocusRequester() }
    LaunchedEffect(fullscreen, controlsVisible) {
        if (!fullscreen) return@LaunchedEffect
        if (controlsVisible) playPauseFocus.requestFocusUntilLanded(attempts = 5)
        else runCatching { rootFocus.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (fullscreen) Modifier.background(Color.Black) else Modifier)
            .pointerInput(fullscreen) {
                if (fullscreen) detectTapGestures { wakeTick++ }
            }
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.MediaPlayPause -> send(
                        SenderCommand.PLAY_PAUSE,
                        if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
                    )
                    Key.MediaPlay -> send(SenderCommand.PLAY, Icons.Rounded.PlayArrow)
                    Key.MediaPause -> send(SenderCommand.PAUSE, Icons.Rounded.Pause)
                    Key.MediaNext -> send(SenderCommand.NEXT, Icons.Rounded.SkipNext)
                    Key.MediaPrevious -> send(SenderCommand.PREVIOUS, Icons.Rounded.SkipPrevious)
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                        if (fullscreen && !controlsVisible) {
                            send(
                                SenderCommand.PLAY_PAUSE,
                                if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow
                            )
                        } else {
                            wakeTick++
                            false
                        }
                    }
                    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight -> {
                        val hidden = fullscreen && !controlsVisible
                        wakeTick++
                        hidden
                    }
                    else -> false
                }
            }
            .then(if (fullscreen) Modifier.focusRequester(rootFocus).focusable() else Modifier),
        contentAlignment = Alignment.Center
    ) {
        ArtworkBackdrop(track.coverArt, Modifier.matchParentSize())
        MaterialTheme(colorScheme = colors) {
            CompositionLocalProvider(LocalContentColor provides colors.onSurface) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val landscape = maxWidth > maxHeight
                    if (landscape) {
                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(
                                    horizontal = if (fullscreen) 56.dp else 24.dp,
                                    vertical = if (fullscreen) 36.dp else 16.dp
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(
                                if (fullscreen) 48.dp else 24.dp,
                                Alignment.CenterHorizontally
                            )
                        ) {
                            NowPlayingArtwork(
                                coverArt = track.coverArt,
                                hasArt = hasArt,
                                artScale = artScale,
                                modifier = Modifier
                                    .fillMaxHeight(if (fullscreen) 0.78f else 0.88f)
                                    .aspectRatio(1f, matchHeightConstraintsFirst = true)
                            )
                            // centred when it fits; the Overview card can be shorter than text, progress,
                            // transport and both volume rows, so the column scrolls instead of being cut off
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .widthIn(max = 560.dp)
                                    .fillMaxHeight(),
                                contentAlignment = Alignment.CenterStart
                            ) {
                            Column(
                                modifier = Modifier.verticalScroll(rememberScrollState()),
                                horizontalAlignment = Alignment.Start
                            ) {
                                NowPlayingTrackText(track = track, fullscreen = fullscreen, centered = false)
                                if (player != null) {
                                    Spacer(Modifier.height(if (fullscreen) 20.dp else 10.dp))
                                    NowPlayingProgress(player)
                                    androidx.compose.animation.AnimatedVisibility(
                                        visible = controlsVisible,
                                        enter = androidx.compose.animation.fadeIn(tween(Motion.SHORT_MS, easing = Motion.Ease)),
                                        exit = androidx.compose.animation.fadeOut(tween(Motion.MEDIUM_MS, easing = Motion.Ease))
                                    ) {
                                        Column {
                                            Spacer(Modifier.height(if (fullscreen) 16.dp else 8.dp))
                                            NowPlayingTransportAndVolume(
                                                viewModel = viewModel,
                                                player = player,
                                                playPauseFocus = playPauseFocus
                                            )
                                        }
                                    }
                                }
                            }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            NowPlayingArtwork(
                                coverArt = track.coverArt,
                                hasArt = hasArt,
                                artScale = artScale,
                                modifier = Modifier
                                    .weight(1f, fill = false)
                                    .aspectRatio(1f, matchHeightConstraintsFirst = true)
                                    .fillMaxWidth(0.7f)
                            )
                            Spacer(Modifier.height(20.dp))
                            NowPlayingTrackText(track = track, fullscreen = fullscreen, centered = true)
                            if (player != null) {
                                Spacer(Modifier.height(16.dp))
                                NowPlayingProgress(player)
                                androidx.compose.animation.AnimatedVisibility(
                                    visible = controlsVisible,
                                    enter = androidx.compose.animation.fadeIn(tween(Motion.SHORT_MS, easing = Motion.Ease)),
                                    exit = androidx.compose.animation.fadeOut(tween(Motion.MEDIUM_MS, easing = Motion.Ease))
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Spacer(Modifier.height(8.dp))
                                        NowPlayingTransportAndVolume(
                                            viewModel = viewModel,
                                            player = player,
                                            playPauseFocus = playPauseFocus
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (onFullscreen != null) {
                        IconButton(
                            onClick = onFullscreen,
                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).dpadFocus()
                        ) {
                            Icon(
                                Icons.Default.Fullscreen,
                                contentDescription = stringResource(R.string.cd_fullscreen),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                            )
                        }
                    }
                    if (onExitFullscreen != null) {
                        androidx.compose.animation.AnimatedVisibility(
                            visible = controlsVisible,
                            modifier = Modifier.align(Alignment.TopEnd)
                        ) {
                            IconButton(
                                onClick = onExitFullscreen,
                                modifier = Modifier.padding(8.dp).dpadFocus()
                            ) {
                                Icon(
                                    Icons.Default.FullscreenExit,
                                    contentDescription = stringResource(R.string.cd_exit_fullscreen),
                                    tint = Color.White.copy(alpha = 0.7f)
                                )
                            }
                        }
                    }
                }
            }
        }
        CommandFeedback(feedbackIcon, feedbackTick)
    }
}

@Composable
private fun NowPlayingArtwork(
    coverArt: android.graphics.Bitmap?,
    hasArt: Boolean,
    artScale: Float,
    modifier: Modifier = Modifier,
) {
    val artShape = RoundedCornerShape(16.dp)
    Box(
        modifier = modifier
            .graphicsLayer { scaleX = artScale; scaleY = artScale }
            .shadow(if (hasArt) 24.dp else 0.dp, artShape)
            .clip(artShape)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center
    ) {
        Crossfade(
            targetState = coverArt,
            animationSpec = tween(Motion.LONG_MS, easing = Motion.Ease),
            label = "coverArt"
        ) { art ->
            if (art != null) {
                Image(
                    bitmap = art.asImageBitmap(),
                    contentDescription = stringResource(R.string.cd_cover_art),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Default.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                    )
                }
            }
        }
    }
}

@Composable
private fun NowPlayingTrackText(track: TrackInfo, fullscreen: Boolean, centered: Boolean) {
    Crossfade(
        targetState = Triple(track.title, track.artist, track.album),
        animationSpec = tween(Motion.MEDIUM_MS, easing = Motion.Ease),
        label = "trackText"
    ) { (title, artist, album) ->
        Column(
            horizontalAlignment = if (centered) Alignment.CenterHorizontally else Alignment.Start
        ) {
            Text(
                text = title.ifEmpty { stringResource(R.string.unknown_track) },
                style = if (fullscreen) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = if (fullscreen) 2 else 1,
                overflow = TextOverflow.Ellipsis
            )
            if (artist.isNotEmpty()) {
                Text(
                    text = artist,
                    style = if (fullscreen) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (album.isNotEmpty()) {
                Text(
                    text = album,
                    style = if (fullscreen) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
@AndroidxOptIn(UnstableApi::class)
private fun NowPlayingProgress(player: androidx.media3.common.Player) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PositionText(player, Modifier.padding(end = 8.dp))
        Box(modifier = Modifier.weight(1f)) { ProgressSlider(player) }
        DurationText(player, Modifier.padding(start = 8.dp))
    }
}

@Composable
@AndroidxOptIn(UnstableApi::class)
private fun NowPlayingTransportAndVolume(
    viewModel: MainViewModel,
    player: androidx.media3.common.Player,
    playPauseFocus: FocusRequester,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
    ) {
        HoldScanButton(
            icon = Icons.Default.FastRewind,
            contentDescription = stringResource(R.string.cd_rewind),
            onBegin = { viewModel.audioScanBegin(false) },
            onEnd = { viewModel.audioScanEnd() }
        )
        PreviousButton(player, modifier = Modifier.dpadFocus())
        PlayPauseButton(
            player,
            modifier = Modifier
                .size(63.dp)
                .focusRequester(playPauseFocus)
                .dpadFocus(CircleShape),
            iconSize = 40.dp
        )
        NextButton(player, modifier = Modifier.dpadFocus())
        HoldScanButton(
            icon = Icons.Default.FastForward,
            contentDescription = stringResource(R.string.cd_fast_forward),
            onBegin = { viewModel.audioScanBegin(true) },
            onEnd = { viewModel.audioScanEnd() }
        )
    }

    Spacer(Modifier.height(8.dp))

    val context = LocalContext.current
    val serverVolume = remember { ServerVolumeState(context) }
    DisposableEffect(serverVolume) { serverVolume.handleLifecycle(this) }
    VolumeSlider(
        icon = painterResource(R.drawable.ic_download),
        label = stringResource(R.string.volume_server),
        value = serverVolume.percentage / 100f,
        onValueChange = { serverVolume.update((it * 100).roundToInt()) },
        onStep = { serverVolume.adjust(it > 0) }
    )

    val clientVolume by viewModel.clientVolume.collectAsState()
    val clientAdjustable by viewModel.clientVolumeAdjustable.collectAsState()
    // sender reports back only after each dacp step
    var clientDrag by remember { mutableStateOf<Float?>(null) }
    val clientShown = clientDrag ?: clientVolume
    VolumeSlider(
        icon = painterResource(R.drawable.ic_upload),
        label = stringResource(R.string.volume_client),
        value = clientShown,
        enabled = clientAdjustable,
        onValueChange = { clientDrag = it; viewModel.setClientVolume(it) },
        onValueChangeFinished = { clientDrag = null },
        onStep = { viewModel.setClientVolume((clientShown + it * CLIENT_VOLUME_STEP).coerceIn(0f, 1f)) }
    )
}

@Composable
private fun VolumeSlider(
    icon: Painter,
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    onStep: (Int) -> Unit,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = 96.dp, max = 136.dp)
        )
        Spacer(Modifier.width(8.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            modifier = Modifier
                .weight(1f)
                .height(32.dp)
                .semantics { contentDescription = label }
                .dpadFocus()
                .dpadAdjust(onLeft = { onStep(-1) }, onRight = { onStep(1) })
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.volume_value, (value * 100).roundToInt()),
            style = MaterialTheme.typography.labelLarge,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 40.dp)
        )
    }
}

private const val AUDIO_CONTROLS_HIDE_MS = 5000L

// sender has 16 volume steps above mute
private const val CLIENT_VOLUME_STEP = 1f / 16

private const val VIDEO_OVERLAY_HIDE_MS = 4000L

@Composable
private fun DebugOverlay(info: DebugInfo, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        val style = MaterialTheme.typography.labelSmall
        val headingStyle = style.copy(fontWeight = FontWeight.Bold)
        val color = Color.White.copy(alpha = 0.9f)
        val headingColor = Color(0xFF80D8FF) // cyan
        val context = LocalContext.current
        debugOverlaySections(context, info).forEachIndexed { i, section ->
            if (i > 0) Spacer(Modifier.height(6.dp))
            Text(section.title.uppercase(), style = headingStyle, color = headingColor)
            section.lines.forEach { Text(it, style = style, color = color) }
        }
    }
}

private data class DebugSection(val title: String, val lines: List<String>)

private fun debugOverlaySections(context: Context, info: DebugInfo): List<DebugSection> = buildList {
    buildList {
        if (info.videoCodec.isNotEmpty()) {
            add(context.getString(R.string.debug_video, info.videoCodec, info.videoRes))
            add(context.getString(R.string.debug_fps_bitrate, info.videoFps, info.bitrateStr))
            add(context.getString(R.string.debug_frames_drops, info.videoFrames, info.droppedFrames))
            add(context.getString(R.string.debug_jitter, info.jitterStr))
        }
    }.takeIf { it.isNotEmpty() }?.let { add(DebugSection(context.getString(R.string.debug_section_video), it)) }

    buildList {
        if (info.audioCodec.isNotEmpty()) add(context.getString(R.string.debug_audio, info.audioCodec, info.serverVolume, info.clientVolume))
        info.audio?.let { a ->
            add(context.getString(R.string.debug_audio_buffer, a.backlogMs, a.tunedCushionMs))
            add(context.getString(R.string.debug_audio_glitch, a.trims, a.drops, a.silences, a.underruns, a.xrun))
            add(context.getString(R.string.debug_audio_decode, formatDecode(a.decodeMeanUs, a.decodeMaxUs, a.decodeHeld, a.decodeErrors)))
        }
    }.takeIf { it.isNotEmpty() }?.let { add(DebugSection(context.getString(R.string.debug_section_audio), it)) }

    add(DebugSection(context.getString(R.string.debug_section_network), listOf(context.getString(R.string.debug_clients, info.connections))))
}

// "mean/max ms held=N", or "held=N" before first latency window lands
private fun formatDecode(meanUs: Int, maxUs: Int, held: Int, errors: Int): String =
    (if (meanUs == 0) "held=$held"
    else "%.1f/%.1f ms held=%d".format(meanUs / 1000.0, maxUs / 1000.0, held)) + " errs=$errors"
