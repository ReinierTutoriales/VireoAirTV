package io.github.jqssun.airplay.renderer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.os.Build
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.text.CueGroup
import androidx.media3.common.VideoSize
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.common.util.Util
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter

// rate is 0 while buffering; speed is the configured rate regardless of pause state
// native reads the effective rate, overlay reads playWhenReady + speed + skipSilence
data class PlaybackSnapshot(
    val position: Float,
    val duration: Float,
    val rate: Float,
    val ready: Boolean,
    val playWhenReady: Boolean,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val buffering: Boolean = false,
)

// exoplayer calls stay on the main thread; native only reads the onPlaybackInfo snapshot
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class AirPlayVideoPlayer(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var pendingSurface: Surface? = null
    private var clientGain = 1f
    // shared across items so each new track starts from the measured throughput, not from scratch;
    // the default estimate is tuned for phones on mobile data and opens TV playback at the lowest rendition
    private val bandwidthMeter by lazy {
        DefaultBandwidthMeter.Builder(context).setInitialBitrateEstimate(INITIAL_BITRATE_ESTIMATE_BPS).build()
    }
    // senders hand over URLs meant for an Apple TV; some CDNs reject other user agents (UxPlay PR #625)
    // and third-party web players often redirect between http and https
    private val mediaSourceFactory by lazy {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(HLS_USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
        val data = DefaultDataSource.Factory(context, http)
        HlsAwareFactory(
            HlsMediaSource.Factory(data).setPlaylistParserFactory(YtDubFilterParserFactory()),
            DefaultMediaSourceFactory(data),
        )
    }

    var onPlaybackInfo: ((PlaybackSnapshot) -> Unit)? = null
    var onVideoSize: ((width: Int, height: Int, aspect: Float) -> Unit)? = null
    var onTitle: ((String?) -> Unit)? = null
    var onEnded: (() -> Unit)? = null
    var onFirstFrame: (() -> Unit)? = null
    // track list changed (audio languages, subtitles) and the subtitle text to show ("" = none)
    var onTracksChanged: (() -> Unit)? = null
    var onCues: ((String) -> Unit)? = null

    private val _reportTick = object : Runnable {
        override fun run() {
            _reportPlaybackInfo()
            mainHandler.postDelayed(this, REPORT_INTERVAL_MS)
        }
    }

    private val _listener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            Log.w(TAG, "playback error", error)
            // a seek outside a live stream's window (live sports pages) is recoverable: rejoin at the
            // live edge as the Media3 live-streaming guide does, instead of reporting the video finished
            val p = player
            if (error.errorCode == androidx.media3.common.PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW && p != null) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            onEnded?.invoke()
        }
        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_ENDED) onEnded?.invoke()
        }
        override fun onTimelineChanged(timeline: Timeline, reason: Int) {
            // duration is usually established here (esp. hls): report so the held /play releases
            _reportPlaybackInfo()
        }
        override fun onRenderedFirstFrame() {
            onFirstFrame?.invoke()
        }
        override fun onTracksChanged(tracks: Tracks) {
            onTracksChanged?.invoke()
        }
        override fun onCues(cueGroup: CueGroup) {
            onCues?.invoke(cueGroup.cues.mapNotNull { it.text?.toString()?.trim()?.ifEmpty { null } }.joinToString("\n"))
        }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) {
            onTitle?.invoke(mediaMetadata.title?.toString())
        }
        override fun onVideoSizeChanged(videoSize: VideoSize) {
            if (videoSize.height == 0) return
            val width = (videoSize.width * videoSize.pixelWidthHeightRatio).toInt()
            onVideoSize?.invoke(width, videoSize.height, width.toFloat() / videoSize.height)
            _applyFrameRate()
        }
    }

    fun play(location: String, startPositionSeconds: Float) = mainHandler.post {
        // recycling must not report the stopped sentinel: senders poll right after /play
        _stopInternal(reportStopped = false)
        val p = ExoPlayer.Builder(context)
            .setBandwidthMeter(bandwidthMeter)
            .setMediaSourceFactory(mediaSourceFactory)
            .build().also {
            // the display mode is asked for below with CHANGE_FRAME_RATE_ALWAYS; Media3's own request
            // (ONLY_IF_SEAMLESS) would keep 24 fps films on a 60 Hz mode on most TVs
            it.videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            it.addListener(_listener)
            pendingSurface?.takeIf { s -> s.isValid }?.let { s -> it.setVideoSurface(s) }
            it.volume = clientGain
        }
        player = p
        p.setMediaItem(MediaItem.fromUri(location), (startPositionSeconds * 1000).toLong())
        p.playWhenReady = true
        p.prepare()
        onPlaybackInfo?.invoke(PlaybackSnapshot(startPositionSeconds, 0f, 0f, false, true))
        mainHandler.postDelayed(_reportTick, REPORT_INTERVAL_MS)
    }

    fun scrub(positionSeconds: Float) = mainHandler.post {
        player?.seekTo((positionSeconds * 1000).toLong())
    }

    // coalesces the seek spam from drag-seeking
    fun setScrubbing(enabled: Boolean) = mainHandler.post {
        player?.isScrubbingModeEnabled = enabled
    }

    fun setRate(rate: Float) = mainHandler.post {
        val p = player ?: return@post
        if (rate <= 0f) {
            p.playWhenReady = false
        } else {
            p.playbackParameters = PlaybackParameters(rate.coerceAtLeast(0.1f))
            p.playWhenReady = true
        }
    }

    // local-only: the sender self-syncs from its next /playback-info poll
    fun setPlaying(playing: Boolean) = mainHandler.post {
        player?.playWhenReady = playing
    }

    // local speed toggle: unlike setRate it must not resume a paused player
    /** Main thread only. */
    fun trackChoices(type: Int, off: String, untitled: (Int) -> String): List<TrackChoice> =
        player?.let { MediaTracks.choices(it, type, off, untitled) }.orEmpty()

    fun selectTrack(type: Int, choice: TrackChoice) = mainHandler.post {
        player?.let { MediaTracks.select(it, type, choice) }
    }

    fun setSpeed(speed: Float) = mainHandler.post {
        player?.playbackParameters = PlaybackParameters(speed.coerceAtLeast(0.1f))
    }

    // linear, unity unless sender volume isn't synced to receiver
    fun setClientGain(gain: Float) = mainHandler.post {
        clientGain = gain
        player?.volume = gain
    }

    fun setSkipSilence(enabled: Boolean) = mainHandler.post {
        player?.skipSilenceEnabled = enabled
    }

    fun seekBy(deltaMs: Long) = mainHandler.post {
        val p = player ?: return@post
        var target = (p.currentPosition + deltaMs).coerceAtLeast(0)
        val duration = p.duration
        if (duration != C.TIME_UNSET) {
            target = target.coerceAtMost(duration)
        }
        p.seekTo(target)
    }

    private inline fun _runOnMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block()
        else mainHandler.post { block() }
    }

    // run synchronously on the main thread so SurfaceHolder.Callback.surfaceDestroyed never returns
    // while MediaCodec is still rendering to the dying surface (avoids CodecException on Realtek SoCs)
    fun setSurface(surface: Surface) = _runOnMain {
        if (!surface.isValid) return@_runOnMain
        pendingSurface = surface
        player?.setVideoSurface(surface)
        _applyFrameRate()
    }

    /**
     * Ask the display for the video's frame rate, as Android TV's frame-rate guide describes: a fixed-source
     * rate with CHANGE_FRAME_RATE_ALWAYS. The system still applies the user's "Match content frame rate"
     * setting (never / seamless only / always), so nothing switches against the user's choice.
     */
    private fun _applyFrameRate(clear: Boolean = false) {
        if (Build.VERSION.SDK_INT < 31) return
        val surface = pendingSurface?.takeIf { it.isValid } ?: return
        val fps = if (clear) 0f else player?.videoFormat?.frameRate?.takeIf { it > 0f } ?: return
        runCatching {
            surface.setFrameRate(fps, Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE, Surface.CHANGE_FRAME_RATE_ALWAYS)
        }
    }

    // no-op if a newer surface already replaced this one
    fun clearSurface(surface: Surface) = _runOnMain {
        if (pendingSurface === surface) pendingSurface = null
        player?.clearVideoSurface(surface)
    }

    fun stop() = mainHandler.post { _stopInternal(reportStopped = true) }

    private fun _stopInternal(reportStopped: Boolean) {
        mainHandler.removeCallbacks(_reportTick)
        // only a real stop gives the display mode back: between two items it would switch twice
        if (reportStopped) _applyFrameRate(clear = true)
        player?.let {
            it.removeListener(_listener)
            it.release()
        }
        player = null
        liveOriginMs = null
        onCues?.invoke("")
        onTracksChanged?.invoke()
        // duration=-1 is the "video finished" sentinel for the playback-info handler
        if (reportStopped) {
            onPlaybackInfo?.invoke(PlaybackSnapshot(0f, -1f, 0f, false, false))
        }
    }

    private fun _reportPlaybackInfo() {
        val p = player ?: return
        val durationMs = p.duration
        val live = p.isCurrentMediaItemLive
        // a live stream's duration is only the window it keeps and its position runs back each time the
        // window slides, so the sender saw a short video that kept jumping backwards (CastBay 8edbc8a).
        // Tell it no duration (0 = live/unknown) and a position that only grows.
        val position = if (live) _livePositionSeconds(p) else p.currentPosition / 1000f
        val duration = if (live || durationMs == C.TIME_UNSET) 0f else durationMs / 1000f
        val rate = if (p.playWhenReady && p.playbackState == Player.STATE_READY) p.playbackParameters.speed else 0f
        // readyToPlay = the timeline is established, NOT exoplayer's buffering state: for vod that means the duration is known; for live there is no duration so being playable is enough. the sender holds its timeline (and /play) until this, so it must not go true early
        val ready = if (p.isCurrentMediaItemLive) p.playbackState == Player.STATE_READY
                    else durationMs != C.TIME_UNSET
        onPlaybackInfo?.invoke(
            PlaybackSnapshot(
                position = position,
                duration = duration,
                rate = rate,
                ready = ready,
                playWhenReady = p.playWhenReady,
                speed = p.playbackParameters.speed,
                skipSilence = p.skipSilenceEnabled,
                buffering = p.playbackState == Player.STATE_BUFFERING,
            )
        )
    }

    // where the live window started when first reported (main thread only); reset per player
    private var liveOriginMs: Long? = null

    private fun _livePositionSeconds(p: ExoPlayer): Float {
        val timeline = p.currentTimeline
        if (timeline.isEmpty) return p.currentPosition / 1000f
        val window = timeline.getWindow(p.currentMediaItemIndex, androidx.media3.common.Timeline.Window())
        val absoluteMs = p.currentPosition + window.positionInFirstPeriodUs / 1000
        val origin = liveOriginMs ?: absoluteMs.also { liveOriginMs = it }
        return (absoluteMs - origin) / 1000f
    }

    companion object {
        private const val TAG = "AirPlayVideoPlayer"
        private const val REPORT_INTERVAL_MS = 250L
        // LAN-connected TV; ABR still steps down if the measured throughput is lower
        private const val INITIAL_BITRATE_ESTIMATE_BPS = 20_000_000L
        private const val HLS_USER_AGENT =
            "AppleCoreMedia/1.0.0.21L569 (Apple TV; U; CPU OS 17_4 like Mac OS X; en_us)"
    }
}

// HLS goes through HlsMediaSource with the YouTube dub filter; everything else through the default factory
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class HlsAwareFactory(
    private val hls: HlsMediaSource.Factory,
    private val other: DefaultMediaSourceFactory,
) : MediaSource.Factory {
    override fun setDrmSessionManagerProvider(provider: DrmSessionManagerProvider): MediaSource.Factory {
        hls.setDrmSessionManagerProvider(provider); other.setDrmSessionManagerProvider(provider); return this
    }
    override fun setLoadErrorHandlingPolicy(policy: LoadErrorHandlingPolicy): MediaSource.Factory {
        hls.setLoadErrorHandlingPolicy(policy); other.setLoadErrorHandlingPolicy(policy); return this
    }
    override fun getSupportedTypes(): IntArray = other.supportedTypes
    override fun createMediaSource(mediaItem: MediaItem): MediaSource {
        val config = mediaItem.localConfiguration
        val isHls = config != null && Util.inferContentTypeForUriAndMimeType(config.uri, config.mimeType) == C.CONTENT_TYPE_HLS
        return if (isHls) hls.createMediaSource(mediaItem) else other.createMediaSource(mediaItem)
    }
}
