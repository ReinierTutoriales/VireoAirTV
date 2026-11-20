package io.github.jqssun.airplay.ui

import android.content.Context
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.annotation.OptIn as AndroidxOptIn
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.ui.compose.material3.MiniController
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import io.github.jqssun.airplay.ui.gestures.BrightnessState
import io.github.jqssun.airplay.ui.gestures.DoubleTapIndicator
import io.github.jqssun.airplay.ui.gestures.GestureInfoText
import io.github.jqssun.airplay.ui.gestures.SeekGestureState
import io.github.jqssun.airplay.ui.gestures.ServerVolumeState
import io.github.jqssun.airplay.ui.gestures.TapGestureState
import io.github.jqssun.airplay.ui.gestures.VerticalGesture
import io.github.jqssun.airplay.ui.gestures.VerticalProgressIndicator
import io.github.jqssun.airplay.ui.gestures.VideoPlayerGestures
import io.github.jqssun.airplay.ui.gestures.VolumeAndBrightnessGestureState
import io.github.jqssun.airplay.ui.gestures.ZoomState
import io.github.jqssun.airplay.viewmodel.DebugInfo
import io.github.jqssun.airplay.viewmodel.LocalNetworkAccess
import io.github.jqssun.airplay.viewmodel.MainViewModel
import io.github.jqssun.airplay.viewmodel.SenderCommand
import kotlin.math.abs
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
    receiverOnly: Boolean = false,
    onLeaveReceiverOnly: () -> Unit = {}
) {
    val pin by viewModel.pinCode.collectAsState()
    // the AirPlay code holds the remote from the root: preview events reach this node before whatever
    // keeps focus in the screens below, so OK cannot press their buttons and arrows cannot move to them
    Box(
        Modifier.fillMaxSize().onPreviewKeyEvent { e -> pin != null && pinKeys(e) { viewModel.dismissPin() } },
        propagateMinConstraints = true,
    ) {
        MainScreenContent(viewModel, isInPip, onSurfaceAvailable, onSurfaceDestroyed, onPip, onLocalNetwork, receiverOnly, onLeaveReceiverOnly)
    }
    val deviceRequest by viewModel.deviceRequest.collectAsState()
    DeviceRequestDialog(deviceRequest, viewModel::answerDeviceRequest, viewModel::dismissDeviceRequest)
}

@Composable
private fun MainScreenContent(
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

    // on TV the receiver stays clean (no tabs or Stop button) unless the user opens the tool menu
    var userExitedFullscreen by remember { mutableStateOf(false) }
    val tv = isTv()
    var showToolUi by remember(tv) { mutableStateOf(!tv) }
    val serverState by viewModel.serverState.collectAsState()
    val localNetwork by viewModel.localNetwork.collectAsState()
    val serverName by viewModel.pref(Prefs.SERVER_NAME).collectAsState()
    // TV: the screen follows the session. Each new content (mirroring, music, video) puts the projection in
    // front and hides the menu; Back brings the menu up until the next content starts. No timers: the
    // session ([io.github.jqssun.airplay.service.Session]) already holds music across pauses and tracks.
    val content = when {
        videoPlaybackActive || videoSessionPending -> 3
        mirroringActive -> 2
        audioOnly && connections > 0 -> 1
        else -> 0
    }
    LaunchedEffect(tv, content) {
        if (tv && content != 0) showToolUi = false
    }
    LaunchedEffect(mirroringActive, audioOnly, videoPlaybackActive, connections, pin) {
        if (tv) return@LaunchedEffect
        if (connections == 0) {
            // brief dip between tracks or apps must not flash the app UI; exit only if still idle
            delay(1_500)
            fullscreen = false
            userExitedFullscreen = false
            return@LaunchedEffect
        }
        if (videoPlaybackActive || pin != null) return@LaunchedEffect
        val wantsFullscreen = (mirroringActive && autoFullscreen) ||
            (audioOnly && (autoFullscreen || tv))
        if (wantsFullscreen && !userExitedFullscreen) {
            fullscreen = true
            if (tv) showToolUi = false
        }
    }

    val activity = LocalActivity.current
    val videoScreen = videoPlaybackActive || videoSessionPending
    val hideSystemBars = fullscreen || videoScreen || receiverOnly || pin != null || (tv && !showToolUi)
    LaunchedEffect(hideSystemBars) {
        val window = activity?.window ?: return@LaunchedEffect
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (hideSystemBars) {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
        }
    }

    // full screen, as Apple TV shows its AirPlay code; drawn over whatever is below (an HLS video keeps
    // playing), so it holds the remote: OK or Back dismisses it, arrows go nowhere
    val pinOverlay: @Composable () -> Unit = {
        pin?.let { code ->
            BackHandler { viewModel.dismissPin() }
            AirPlayPinScreen(code, onDismiss = { viewModel.dismissPin() })
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
        LaunchedEffect(overlayVisible, videoPlaybackActive, controlsLocked, showSpeedSelector, showTrackSelector) {
            if (showSpeedSelector || showTrackSelector) return@LaunchedEffect
            if (!overlayVisible || !videoPlaybackActive) {
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
            if (buffering) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(72.dp))
            }
            DoubleTapIndicator(tapGestureState = tapGestureState)
            val seekAmountMs = seekGestureState.seekAmountMs
            val dacpReady by viewModel.clientVolumeAdjustable.collectAsState()
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
                    VideoTransportControls(
                        playing = playing,
                        onRewind = {
                            viewModel.seekVideoBy(-TapGestureState.SEEK_INCREMENT_MS)
                            viewModel.showVideoOverlay()
                        },
                        onPlayPause = { viewModel.toggleVideoPlayPause() },
                        onForward = {
                            viewModel.seekVideoBy(TapGestureState.SEEK_INCREMENT_MS)
                            viewModel.showVideoOverlay()
                        },
                        playPauseModifier = Modifier
                            .focusRequester(playPauseFocusRequester)
                            .onFocusChanged { isPlayPauseFocused = it.hasFocus },
                        onPrevious = if (dacpReady) {
                            {
                                viewModel.senderCommand(SenderCommand.PREVIOUS)
                                viewModel.showVideoOverlay()
                            }
                        } else null,
                        onNext = if (dacpReady) {
                            {
                                viewModel.senderCommand(SenderCommand.NEXT)
                                viewModel.showVideoOverlay()
                            }
                        } else null,
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
        pinOverlay()
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

    if (receiverOnly) {
        BackHandler { onLeaveReceiverOnly() }
        when {
            mirroringActive -> FullscreenVideo(
                viewModel = viewModel,
                video = video,
                onExitFullscreen = onLeaveReceiverOnly,
                onPip = onPip,
                quiet = true
            )
            audioOnly && connections > 0 -> NowPlayingTvScreen(viewModel)
            else -> Box(Modifier.fillMaxSize().background(Color.Black))
        }
    } else if (fullscreen) {
        BackHandler {
            userExitedFullscreen = true
            fullscreen = false
            if (tv) showToolUi = true
        }
        if (mirroringActive) {
            FullscreenVideo(
                viewModel = viewModel,
                video = video,
                onExitFullscreen = {
                    userExitedFullscreen = true
                    fullscreen = false
                    if (tv) showToolUi = true
                },
                onPip = onPip,
                quiet = tv
            )
        } else if (audioOnly && connections > 0) {
            // Back leaves through the BackHandler above; the screen itself has no buttons to exit
            NowPlayingTvScreen(viewModel)
        } else {
            // between tracks or video items, hold black instead of flashing the app UI
            Box(Modifier.fillMaxSize().background(Color.Black))
        }
    } else if (tv && !showToolUi) {
        when {
            mirroringActive -> {
                BackHandler {
                    userExitedFullscreen = true
                    showToolUi = true
                }
                FullscreenVideo(
                    viewModel = viewModel,
                    video = video,
                    onExitFullscreen = {
                        userExitedFullscreen = true
                        showToolUi = true
                    },
                    onPip = onPip,
                    quiet = true
                )
            }
            audioOnly && connections > 0 -> {
                BackHandler {
                    userExitedFullscreen = true
                    showToolUi = true
                }
                NowPlayingTvScreen(viewModel)
            }
            else -> TvStandbyScreen(
                serverName = serverName,
                state = serverState,
                needsLocalNetwork = serverState != ServerState.RUNNING && localNetwork != LocalNetworkAccess.GRANTED,
                onOpenMenu = { showToolUi = true },
                onGrantLocalNetwork = onLocalNetwork,
            )
        }
    } else {
        if (tv) BackHandler { showToolUi = false }
        val overviewTabFocus = remember { FocusRequester() }
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
                            val tabModifier = if (t == Tab.OVERVIEW) Modifier.focusRequester(overviewTabFocus) else Modifier
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { Icon(t.icon, null) },
                                label = { Text(stringResource(t.labelRes)) },
                                modifier = tabModifier.dpadFocus()
                            )
                        }
                    }
                }
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding).consumeWindowInsets(padding)) {
                TabContent(
                    tab, viewModel, video,
                    onFullscreen = {
                        userExitedFullscreen = false
                        fullscreen = true
                        if (tv) showToolUi = false
                    },
                    onPip = onPip, onLocalNetwork = onLocalNetwork,
                    showAudioMode = audioOnly,
                    safeFocus = overviewTabFocus
                )
            }
        }
    }

    pinOverlay()

}

@Composable
private fun TabContent(
    tab: Tab,
    viewModel: MainViewModel,
    video: @Composable () -> Unit,
    onFullscreen: () -> Unit,
    onPip: () -> Unit,
    onLocalNetwork: () -> Unit,
    showAudioMode: Boolean,
    safeFocus: FocusRequester
) {
    when (tab) {
        Tab.OVERVIEW -> OverviewContent(
            viewModel, video,
            onFullscreen = onFullscreen, onPip = onPip, onLocalNetwork = onLocalNetwork, showAudioMode = showAudioMode,
            safeFocus = safeFocus
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
    showAudioMode: Boolean = false,
    // where focus goes on a TV when the first button would be Stop
    safeFocus: FocusRequester? = null
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
    val nowPlayingFocus = remember { FocusRequester() }
    // on return from full screen OK must reopen Now Playing, not land on Stop and end the server
    val nowPlayingCard = tv && showAudioMode && state == ServerState.RUNNING && connections > 0
    // a running server's first button is Stop: OK pressed again after opening the menu would end it
    val stopFirst = state == ServerState.RUNNING && !grant
    LaunchedEffect(nowPlayingCard, stopFirst) {
        if (tv) runCatching {
            when {
                nowPlayingCard -> nowPlayingFocus
                stopFirst -> safeFocus ?: return@runCatching
                else -> startFocus
            }.requestFocus()
        }
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
                NowPlayingTvCard(viewModel, onOpen = onFullscreen, focusRequester = nowPlayingFocus)
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
                Spacer(Modifier.height(12.dp))
                RoomNameChips(
                    serverName = serverName,
                    onSelect = { viewModel.set(Prefs.SERVER_NAME, it) },
                )
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
    // ↓ or Info on the remote shows the stream's stats (fps, drops, bitrate, codec) without going to
    // Settings; ↑ or Back hides them
    var statsShown by remember { mutableStateOf(false) }
    DisposableEffect(statsShown) {
        viewModel.statsVisible = statsShown
        onDispose { viewModel.statsVisible = false }
    }
    if (statsShown) BackHandler { statsShown = false }

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
                    Key.Info -> { statsShown = !statsShown; true }
                    Key.DirectionDown -> (quiet && !controlsVisible && !statsShown).also { if (it) statsShown = true }
                    Key.DirectionUp -> statsShown.also { statsShown = false }
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
        if (debugEnabled || statsShown) {
            DebugOverlay(debugInfo, Modifier.align(Alignment.TopStart).padding(if (statsShown) 32.dp else 8.dp), large = statsShown)
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

private const val VIDEO_OVERLAY_HIDE_MS = 4000L

@Composable
private fun DebugOverlay(info: DebugInfo, modifier: Modifier = Modifier, large: Boolean = false) {
    Column(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        // read from the sofa when asked for from the remote; small when it is the always-on debug overlay
        val style = if (large) MaterialTheme.typography.bodyLarge else MaterialTheme.typography.labelSmall
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
