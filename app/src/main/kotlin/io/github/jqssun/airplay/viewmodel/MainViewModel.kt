package io.github.jqssun.airplay.viewmodel

import android.annotation.SuppressLint
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.Surface
import androidx.core.content.FileProvider
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jqssun.airplay.Pref
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.audio.TrackInfo
import io.github.jqssun.airplay.get
import io.github.jqssun.airplay.observe
import io.github.jqssun.airplay.renderer.TrackChoice
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.service.AirPlayService.ServerState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlin.math.abs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class LocalNetworkAccess { GRANTED, ASK, SETTINGS }

data class AudioDebug(
    val backlogMs: Int,
    val tunedCushionMs: Int,
    val trims: Int,
    val drops: Int,
    val silences: Int,
    val underruns: Int,
    val xrun: Int,
    val decodeMeanUs: Int,
    val decodeMaxUs: Int,
    val decodeHeld: Int,
    val decodeErrors: Int,
)

data class DebugInfo(
    val videoCodec: String = "",
    val videoRes: String = "",
    val videoFps: Int = 0,
    val videoBitrate: Long = 0,
    val videoFrames: Long = 0,
    val droppedFrames: Long = 0,
    val framePacingJitterUs: Long = 0,
    val audioCodec: String = "",
    val serverVolume: Int = 100,
    val clientVolume: Int = 100,
    val audio: AudioDebug? = null,
    val connections: Int = 0,
) {
    val bitrateStr: String get() {
        val kbps = videoBitrate / 1000
        return if (kbps >= 1000) "${"%.1f".format(kbps / 1000.0)} Mbps" else "$kbps Kbps"
    }
    val jitterStr: String get() {
        return if (framePacingJitterUs >= 1000) {
            "${"%.1f".format(framePacingJitterUs / 1000.0)} ms"
        } else {
            "$framePacingJitterUs us"
        }
    }
}

@HiltViewModel
class MainViewModel @Inject constructor(app: Application) : AndroidViewModel(app) {

    private val prefs = app.getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    private val logFile = File(app.filesDir, "airplay_logs.txt")
    // a Service, not an Activity: lives as long as the receiver and is cleared in unbindService
    @SuppressLint("StaticFieldLeak")
    private var service: AirPlayService? = null
    val dacpPlayer: Player? get() = service?.dacpPlayer

    fun audioScanBegin(forward: Boolean) {
        service?.dacpController?.let { if (forward) it.beginFastForward() else it.beginRewind() }
    }
    fun audioScanEnd() { service?.dacpController?.playResume() }

    /**
     * Media command for the sender's Now Playing app over DACP (works while mirroring too, when the
     * sender sent its DACP-ID). Returns false when no DACP endpoint is known, so callers show nothing.
     */
    fun senderCommand(command: SenderCommand): Boolean {
        val dacp = service?.dacpController?.takeIf { it.ready } ?: return false
        when (command) {
            SenderCommand.PLAY_PAUSE -> dacp.playPause()
            SenderCommand.PLAY -> dacp.play()
            SenderCommand.PAUSE -> dacp.pause()
            SenderCommand.NEXT -> dacp.nextItem()
            SenderCommand.PREVIOUS -> dacp.prevItem()
        }
        return true
    }

    private val _clientVolume = MutableStateFlow(1f)
    val clientVolume: StateFlow<Float> = _clientVolume.asStateFlow()
    private val _clientVolumeAdjustable = MutableStateFlow(false)
    val clientVolumeAdjustable: StateFlow<Boolean> = _clientVolumeAdjustable.asStateFlow()
    fun setClientVolume(frac: Float) { service?.setClientVolume(frac) }

    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount: StateFlow<Int> = _connectionCount.asStateFlow()

    private val _pinCode = MutableStateFlow<String?>(null)
    val pinCode: StateFlow<String?> = _pinCode.asStateFlow()

    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect: StateFlow<Float> = _videoAspect.asStateFlow()

    private val _videoResolution = MutableStateFlow("")
    val videoResolution: StateFlow<String> = _videoResolution.asStateFlow()

    private val _prefStates = mutableMapOf<Pref<*>, StateFlow<*>>()

    @Suppress("UNCHECKED_CAST")
    fun <T : Any> pref(p: Pref<T>): StateFlow<T> = _prefStates.getOrPut(p) {
        prefs.observe { this[p] }.stateIn(viewModelScope, SharingStarted.Eagerly, prefs[p])
    } as StateFlow<T>

    fun <T : Any> set(p: Pref<T>, v: T) { p.write(prefs, v) }

    private val _debugInfo = MutableStateFlow(DebugInfo())
    val debugInfo: StateFlow<DebugInfo> = _debugInfo.asStateFlow()

    private val _localNetwork = MutableStateFlow(LocalNetworkAccess.GRANTED)
    val localNetwork: StateFlow<LocalNetworkAccess> = _localNetwork.asStateFlow()
    fun setLocalNetwork(v: LocalNetworkAccess) { _localNetwork.value = v }

    // audio mode
    private val _audioOnly = MutableStateFlow(false)
    val audioOnly: StateFlow<Boolean> = _audioOnly.asStateFlow()

    private val _videoPlaybackActive = MutableStateFlow(false)
    val videoPlaybackActive: StateFlow<Boolean> = _videoPlaybackActive.asStateFlow()

    // video channel polling but /play not received yet
    private val _videoSessionPending = MutableStateFlow(false)
    val videoSessionPending: StateFlow<Boolean> = _videoSessionPending.asStateFlow()

    // airplay video transport overlay state

    // bumping the tick shows the overlay and restarts its auto-hide timer
    private val _videoOverlayTick = MutableStateFlow(0L)
    val videoOverlayTick: StateFlow<Long> = _videoOverlayTick.asStateFlow()

    private val _videoPositionMs = MutableStateFlow(0L)
    val videoPositionMs: StateFlow<Long> = _videoPositionMs.asStateFlow()

    private val _videoDurationMs = MutableStateFlow(0L)
    val videoDurationMs: StateFlow<Long> = _videoDurationMs.asStateFlow()

    private val _videoPlaybackAspect = MutableStateFlow(16f / 9f)
    val videoPlaybackAspect: StateFlow<Float> = _videoPlaybackAspect.asStateFlow()

    private val _videoPlaybackSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoPlaybackSize: StateFlow<Pair<Int, Int>?> = _videoPlaybackSize.asStateFlow()
    private val _videoFrameShown = MutableStateFlow(false)
    val videoFrameShown: StateFlow<Boolean> = _videoFrameShown.asStateFlow()
    private val _videoTracksTick = MutableStateFlow(0)
    val videoTracksTick: StateFlow<Int> = _videoTracksTick.asStateFlow()
    private val _videoCues = MutableStateFlow("")
    val videoCues: StateFlow<String> = _videoCues.asStateFlow()

    private val _videoBuffering = MutableStateFlow(false)
    val videoBuffering: StateFlow<Boolean> = _videoBuffering.asStateFlow()

    private val _videoTitle = MutableStateFlow("")
    val videoTitle: StateFlow<String> = _videoTitle.asStateFlow()

    private val _videoLocation = MutableStateFlow<String?>(null)
    val videoLocation: StateFlow<String?> = _videoLocation.asStateFlow()

    // optimistic for instant icon feedback, re-synced from the delayed snapshot
    private val _videoPlaying = MutableStateFlow(true)
    val videoPlaying: StateFlow<Boolean> = _videoPlaying.asStateFlow()
    private var _videoActionAt = 0L

    // null = idle, -1 = unknown, else 0..100
    private val _videoDownloadProgress = MutableStateFlow<Int?>(null)
    val videoDownloadProgress: StateFlow<Int?> = _videoDownloadProgress.asStateFlow()

    // mirror the player's playback parameters; sender /rate commands reset speed to 1
    private val _videoSpeed = MutableStateFlow(1f)
    val videoSpeed: StateFlow<Float> = _videoSpeed.asStateFlow()
    private val _videoSkipSilence = MutableStateFlow(false)
    val videoSkipSilence: StateFlow<Boolean> = _videoSkipSilence.asStateFlow()
    private var _videoParamsActionAt = 0L

    // non-null while a scrub is in flight; committed once after a debounce
    private val _videoScrubPositionMs = MutableStateFlow<Long?>(null)
    val videoScrubPositionMs: StateFlow<Long?> = _videoScrubPositionMs.asStateFlow()
    private var _scrubJob: Job? = null
    private var _lastVideoPlaySeq = 0L

    private val _mirroringActive = MutableStateFlow(false)
    val mirroringActive: StateFlow<Boolean> = _mirroringActive.asStateFlow()
    private val _mirrorSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val mirrorSize: StateFlow<Pair<Int, Int>?> = _mirrorSize.asStateFlow()

    private val _trackInfo = MutableStateFlow(TrackInfo())
    val trackInfo: StateFlow<TrackInfo> = _trackInfo.asStateFlow()


    // logs
    private val _logs = MutableStateFlow<List<String>>(emptyList())
    val logs: StateFlow<List<String>> = _logs.asStateFlow()
    private val _logLock = Any()
    private val _logList = mutableListOf<String>()
    private val _persistTrigger = Channel<Unit>(Channel.CONFLATED)
    private val _dateFmt = object : ThreadLocal<SimpleDateFormat>() {
        override fun initialValue() = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    }

    init {
        // restore first: a write before the restore finishes would truncate the previous session's log
        viewModelScope.launch(Dispatchers.IO) {
            loadPersistedLogs()
            persistLogs()
        }
    }

    fun addLog(msg: String) {
        val line = "${_dateFmt.get()!!.format(Date())} $msg"
        val snapshot: List<String>
        synchronized(_logLock) {
            _logList.add(line)
            while (_logList.size > MAX_LOG_LINES) _logList.removeAt(0)
            snapshot = _logList.toList()
        }
        _logs.value = snapshot
        _persistTrigger.trySend(Unit)
    }

    fun clearLogs() {
        synchronized(_logLock) {
            _logList.clear()
        }
        _logs.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { logFile.delete() }
        }
    }

    fun exportLogs() {
        val snapshot = synchronized(_logLock) { _logList.toList() }
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = getApplication<Application>()
            val file = File(ctx.cacheDir, "airplay_logs.txt")
            file.writeText(snapshot.joinToString("\n"))
            val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooserTitle = ctx.getString(io.github.jqssun.airplay.R.string.export_logs_chooser_title)
            withContext(Dispatchers.Main) {
                ctx.startActivity(Intent.createChooser(intent, chooserTitle).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }

    private fun loadPersistedLogs() {
        val restored = runCatching {
            if (logFile.exists()) logFile.readLines().takeLast(MAX_LOG_LINES) else emptyList()
        }.getOrDefault(emptyList())
        if (restored.isEmpty()) return
        val snapshot: List<String>
        synchronized(_logLock) {
            val merged = (restored + _logList).takeLast(MAX_LOG_LINES)
            _logList.clear()
            _logList.addAll(merged)
            snapshot = _logList.toList()
        }
        _logs.value = snapshot
    }

    private suspend fun persistLogs() {
        for (ignored in _persistTrigger) {
            delay(LOG_PERSIST_INTERVAL_MS)
            _persistTrigger.tryReceive()
            val snapshot = synchronized(_logLock) { _logList.toList() }
            if (snapshot.isNotEmpty()) {
                runCatching {
                    logFile.writeText(snapshot.joinToString("\n"))
                }
            }
        }
    }

    private var _serviceJobs: Job? = null
    private var _mirrorSurface: Surface? = null
    private var _videoPlaybackSurface: Surface? = null

    // service binding
    fun bindService(svc: AirPlayService) {
        _serviceJobs?.cancel()
        service = svc
        _mirrorSurface?.takeIf { it.isValid }?.let { svc.setVideoSurface(it) }
        _videoPlaybackSurface?.takeIf { it.isValid }?.let { svc.setVideoPlaybackSurface(it) }
        updateFromService()
        _serviceJobs = viewModelScope.launch {
            launch { svc.serverState.collect { _serverState.value = it } }
            launch { svc.connectionCount.collect { _connectionCount.value = it } }
            launch { svc.videoAspect.collect { _videoAspect.value = it } }
            launch { svc.videoResolution.collect { _videoResolution.value = it } }
            launch {
                svc.audioOnly.collect { audio ->
                    _audioOnly.value = audio
                    if (audio) _videoSessionPending.value = false
                }
            }
            launch {
                svc.mirroringActive.collect { mirroring ->
                    _mirroringActive.value = mirroring
                    if (mirroring) _videoSessionPending.value = false
                }
            }
            launch { svc.mirrorSize.collect { _mirrorSize.value = it } }
            launch { svc.trackInfo.collect { _trackInfo.value = it } }
            launch { svc.videoDownloader.progress.collect { _videoDownloadProgress.value = it } }
            launch {
                svc.videoPlaySeq.collect { playSeq ->
                    if (playSeq != _lastVideoPlaySeq) {
                        // fresh /play (incl. back-to-back): drop stale overlay and scrub state
                        _lastVideoPlaySeq = playSeq
                        _videoPlaying.value = true
                        _videoActionAt = 0
                        _videoOverlayTick.value = 0
                        _videoSpeed.value = 1f
                        _videoSkipSilence.value = false
                        _scrubJob?.cancel()
                        _videoScrubPositionMs.value = null
                    }
                }
            }
            launch {
                svc.videoPlaybackActive.collect { active ->
                    // update pending in the same step so the video screen never sees active=false, pending=stale
                    _videoSessionPending.value = svc.videoSessionPending()
                    _videoPlaybackActive.value = active
                    if (!active) {
                        _videoPositionMs.value = 0
                        _videoDurationMs.value = 0
                        _videoBuffering.value = false
                    }
                }
            }
            launch {
                svc.videoPlaybackInfo.collect { info ->
                    if (!svc.videoPlaybackActive.value) return@collect
                    _videoPositionMs.value = info.positionMs
                    _videoDurationMs.value = info.durationMs
                    if (SystemClock.elapsedRealtime() - _videoParamsActionAt > VIDEO_ACTION_SYNC_HOLD_MS) {
                        _videoSpeed.value = info.speed
                        _videoSkipSilence.value = info.skipSilence
                    }
                    _videoBuffering.value = info.buffering
                    // stale snapshots must not overwrite a just-made optimistic action
                    if (SystemClock.elapsedRealtime() - _videoActionAt > VIDEO_ACTION_SYNC_HOLD_MS) {
                        _videoPlaying.value = info.playing
                    }
                }
            }
            launch { svc.videoPlaybackAspect.collect { _videoPlaybackAspect.value = it } }
            launch { svc.videoPlaybackSize.collect { _videoPlaybackSize.value = it } }
            launch { svc.videoFrameShown.collect { _videoFrameShown.value = it } }
            launch { svc.videoTracksTick.collect { _videoTracksTick.value = it } }
            launch { svc.videoCues.collect { _videoCues.value = it } }
            launch { svc.videoTitle.collect { _videoTitle.value = it.ifEmpty { svc.trackInfo.value.title } } }
            launch { svc.videoLocation.collect { _videoLocation.value = it } }
        }
    }

    fun unbindService() {
        _serviceJobs?.cancel()
        _serviceJobs = null
        service = null
    }

    fun startServer() {
        service?.startServer()
    }

    fun stopServer() {
        service?.stopServer()
    }

    fun onSurfaceAvailable(surface: Surface) {
        _mirrorSurface = surface
        service?.setVideoSurface(surface)
    }

    fun onSurfaceDestroyed(surface: Surface) {
        if (_mirrorSurface === surface) _mirrorSurface = null
        service?.clearVideoSurface(surface)
    }

    fun onVideoPlaybackSurfaceAvailable(surface: Surface) {
        _videoPlaybackSurface = surface
        service?.setVideoPlaybackSurface(surface)
    }

    fun onVideoPlaybackSurfaceDestroyed(surface: Surface) {
        if (_videoPlaybackSurface === surface) _videoPlaybackSurface = null
        service?.clearVideoPlaybackSurface(surface)
    }

    fun toggleVideoPlayPause(showOverlay: Boolean = true) = setVideoPlaying(!_videoPlaying.value, showOverlay)

    fun setVideoPlaying(playing: Boolean, showOverlay: Boolean = true) {
        // pending session: there is no local media yet, nothing to toggle
        if (!_videoPlaybackActive.value) return
        _videoPlaying.value = playing
        _videoActionAt = SystemClock.elapsedRealtime()
        service?.setVideoPlaying(playing)
        if (showOverlay) showVideoOverlay()
    }

    // hold the target until the reported position catches up (hls rebuffer)
    private suspend fun _holdScrubUntilSettled(target: Long) {
        val deadline = SystemClock.elapsedRealtime() + SCRUB_SETTLE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            delay(SCRUB_SETTLE_POLL_MS)
            if (abs(_videoPositionMs.value - target) <= SCRUB_SETTLE_EPSILON_MS) break
        }
        _videoScrubPositionMs.value = null
    }

    // drag-seek: live seeks under scrubbing mode, settle-hold on release
    fun startVideoScrub() {
        _scrubJob?.cancel()
        service?.setVideoScrubbing(true)
    }

    fun scrubVideoTo(positionMs: Long) {
        val duration = _videoDurationMs.value
        if (duration <= 0) return
        val target = positionMs.coerceIn(0L, duration)
        _videoScrubPositionMs.value = target
        service?.seekVideoTo(target)
    }

    fun endVideoScrub() {
        service?.setVideoScrubbing(false)
        _scrubJob?.cancel()
        _scrubJob = viewModelScope.launch {
            _holdScrubUntilSettled(_videoScrubPositionMs.value ?: return@launch)
        }
    }

    fun showVideoOverlay() {
        _videoOverlayTick.value++
    }

    fun stopVideoPlayback() {
        service?.stopVideoPlayback()
    }

    /** Main thread only, like the player. */
    fun videoTrackChoices(type: Int, off: String, untitled: (Int) -> String): List<TrackChoice> =
        service?.videoTrackChoices(type, off, untitled).orEmpty()

    fun forgetTrustedClients() {
        service?.forgetTrustedClients()
    }

    fun selectVideoTrack(type: Int, choice: TrackChoice) {
        service?.selectVideoTrack(type, choice)
    }

    fun setVideoSpeed(speed: Float) {
        val value = speed.coerceIn(0.2f, 4f)
        _videoSpeed.value = value
        _videoParamsActionAt = SystemClock.elapsedRealtime()
        service?.setVideoSpeed(value)
    }

    fun setVideoSkipSilence(enabled: Boolean) {
        _videoSkipSilence.value = enabled
        _videoParamsActionAt = SystemClock.elapsedRealtime()
        service?.setVideoSkipSilence(enabled)
    }

    fun seekVideoBy(deltaMs: Long) {
        service?.seekVideoBy(deltaMs)
    }

    fun toggleVideoDownload() {
        val svc = service ?: return
        if (_videoDownloadProgress.value != null) svc.videoDownloader.cancel() else svc.downloadVideo()
        showVideoOverlay()
    }

    fun dismissPin() {
        _pinCode.value = null
    }

    fun showPin(pin: String?) {
        _pinCode.value = pin
    }

    fun updateFromService() {
        service?.let {
            _serverState.value = it.serverState.value
            _connectionCount.value = it.connectionCount.value
            _videoAspect.value = it.videoAspect.value
            _videoResolution.value = it.videoResolution.value
            _audioOnly.value = it.audioOnly.value
            val videoActive = it.videoPlaybackActive.value
            val playSeq = it.videoPlaySeq.value
            if (playSeq != _lastVideoPlaySeq) {
                // fresh /play (incl. back-to-back): drop stale overlay and scrub state
                _lastVideoPlaySeq = playSeq
                _videoPlaying.value = true
                _videoActionAt = 0
                _videoOverlayTick.value = 0
                _videoSpeed.value = 1f
                _videoSkipSilence.value = false
                _scrubJob?.cancel()
                _videoScrubPositionMs.value = null
            }
            _videoPlaybackActive.value = videoActive
            _videoSessionPending.value = it.videoSessionPending()
            _videoDownloadProgress.value = it.videoDownloader.progress.value
            if (videoActive) {
                val info = it.videoPlaybackInfo.value
                _videoPositionMs.value = info.positionMs
                _videoDurationMs.value = info.durationMs
                if (SystemClock.elapsedRealtime() - _videoParamsActionAt > VIDEO_ACTION_SYNC_HOLD_MS) {
                    _videoSpeed.value = info.speed
                    _videoSkipSilence.value = info.skipSilence
                }
                _videoBuffering.value = info.buffering
                _videoPlaybackAspect.value = it.videoPlaybackAspect.value
                _videoPlaybackSize.value = it.videoPlaybackSize.value
                _videoFrameShown.value = it.videoFrameShown.value
                // stream metadata first, dmap second; senders rarely provide either for video
                _videoTitle.value = it.videoTitle.value.ifEmpty { it.trackInfo.value.title }
                _videoLocation.value = it.videoLocation.value
                // stale snapshots must not overwrite a just-made optimistic action
                if (SystemClock.elapsedRealtime() - _videoActionAt > VIDEO_ACTION_SYNC_HOLD_MS) {
                    _videoPlaying.value = info.playing
                }
            } else {
                _videoPositionMs.value = 0
                _videoDurationMs.value = 0
                _videoBuffering.value = false
            }
            _mirroringActive.value = it.mirroringActive.value
            _mirrorSize.value = it.mirrorSize.value
            _trackInfo.value = it.trackInfo.value
            updatePolledMetrics()
        }
    }

    fun updatePolledMetrics() {
        val it = service ?: return
        _videoSessionPending.value = it.videoSessionPending()
        _clientVolume.value = it.clientVolume()
        _clientVolumeAdjustable.value = it.dacpController?.ready == true
        if (pref(Prefs.DEBUG_ENABLED).value) {
            _debugInfo.value = it.collectDebugInfo()
        }
    }

    private companion object {
        // post-commit: show the target until within epsilon, give up after the timeout
        const val SCRUB_SETTLE_POLL_MS = 100L
        const val SCRUB_SETTLE_EPSILON_MS = 2_000L
        const val SCRUB_SETTLE_TIMEOUT_MS = 3_000L
        // raise if the play/pause icon flickers back right after a local toggle
        const val VIDEO_ACTION_SYNC_HOLD_MS = 700L
        const val MAX_LOG_LINES = 9999
        const val LOG_PERSIST_INTERVAL_MS = 1_500L
    }
}

enum class SenderCommand { PLAY_PAUSE, PLAY, PAUSE, NEXT, PREVIOUS }
