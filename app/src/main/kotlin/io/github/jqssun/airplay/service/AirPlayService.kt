package io.github.jqssun.airplay.service

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.os.ConfigurationCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaStyleNotificationHelper
import io.github.jqssun.airplay.MainActivity
import io.github.jqssun.airplay.Prefs
import io.github.jqssun.airplay.R
import io.github.jqssun.airplay.changes
import io.github.jqssun.airplay.get
import io.github.jqssun.airplay.hasLocalNetwork
import io.github.jqssun.airplay.observe
import io.github.jqssun.airplay.realDisplaySize
import io.github.jqssun.airplay.audio.DacpController
import io.github.jqssun.airplay.audio.DacpPlayer
import io.github.jqssun.airplay.audio.DmapParser
import io.github.jqssun.airplay.audio.TrackInfo
import io.github.jqssun.airplay.audio.VolumeBroadcast
import io.github.jqssun.airplay.bridge.LogListener
import io.github.jqssun.airplay.bridge.NativeBridge
import io.github.jqssun.airplay.bridge.RaopCallbackHandler
import io.github.jqssun.airplay.discovery.MdnsService
import io.github.jqssun.airplay.discovery.NsdServiceManager
import io.github.jqssun.airplay.discovery.UsbMdnsAdvertiser
import io.github.jqssun.airplay.download.VideoDownloader
import io.github.jqssun.airplay.renderer.AirPlayVideoPlayer
import io.github.jqssun.airplay.renderer.AudioRenderer
import io.github.jqssun.airplay.renderer.VideoRenderer
import io.github.jqssun.airplay.viewmodel.DebugInfo
import java.io.ByteArrayOutputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch

data class VideoPlaybackInfo(
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val playing: Boolean = true,
    val speed: Float = 1f,
    val skipSilence: Boolean = false,
    val buffering: Boolean = false,
)

// debounce is FlowPreview; used for settings, audio config and LAN address changes
@OptIn(kotlinx.coroutines.FlowPreview::class)
class AirPlayService : LifecycleService(), RaopCallbackHandler, LogListener {

    private var nativeHandle = 0L
    private var nsdManager: NsdServiceManager? = null
    private var usbAdvertiser: UsbMdnsAdvertiser? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var foregroundStarted = false
    private var lastOrientation = Configuration.ORIENTATION_UNDEFINED

    // IPv4 only: IPv6 temporary addresses rotate and would restart live sessions
    private val connectivityManager by lazy { getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager }
    private val _lanNetworks = ConcurrentHashMap<Network, Set<String>>()
    private val _lanAddresses = MutableStateFlow<Set<String>>(emptySet())
    private var _advertisedAddresses: Set<String> = emptySet()
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            connectivityManager.getLinkProperties(network)?.let { _updateLan(network, it) }
        }
        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = _updateLan(network, lp)
        override fun onLost(network: Network) {
            _lanNetworks.remove(network)
            _lanAddresses.value = _lanNetworks.values.flatten().toSet()
        }
    }

    val videoRenderer = VideoRenderer(this)
    val audioRenderer = AudioRenderer()
    private val audioManager by lazy { getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val airPlayVideoPlayer by lazy { AirPlayVideoPlayer(this) }
    val videoDownloader by lazy { VideoDownloader(this) }

    // hls urls point at the native httpd, valid only while the session lives
    private val _videoLocation = MutableStateFlow<String?>(null)
    val videoLocation = _videoLocation.asStateFlow()

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences(Prefs.NAME, Context.MODE_PRIVATE)
    }
    private val _serverState = MutableStateFlow(ServerState.STOPPED)
    val serverState = _serverState.asStateFlow()

    private val _connectionCount = MutableStateFlow(0)
    val connectionCount = _connectionCount.asStateFlow()

    private val _videoAspect = MutableStateFlow(16f / 9f)
    val videoAspect = _videoAspect.asStateFlow()

    private val _videoResolution = MutableStateFlow("")
    val videoResolution = _videoResolution.asStateFlow()

    private val _audioOnly = MutableStateFlow(false)
    val audioOnly = _audioOnly.asStateFlow()

    private val _videoPlaybackActive = MutableStateFlow(false)
    val videoPlaybackActive = _videoPlaybackActive.asStateFlow()

    private val _videoPlaybackInfo = MutableStateFlow(VideoPlaybackInfo())
    val videoPlaybackInfo = _videoPlaybackInfo.asStateFlow()

    // bumped per /play so the ui resets transport state on back-to-back plays too
    private val _videoPlaySeq = MutableStateFlow(0L)
    val videoPlaySeq = _videoPlaySeq.asStateFlow()

    private val _videoPlaybackAspect = MutableStateFlow(16f / 9f)
    val videoPlaybackAspect = _videoPlaybackAspect.asStateFlow()

    private val _videoPlaybackSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val videoPlaybackSize = _videoPlaybackSize.asStateFlow()
    // true once the current item has drawn a frame; drives the fade-in
    private val _videoFrameShown = MutableStateFlow(false)
    val videoFrameShown = _videoFrameShown.asStateFlow()

    // container/manifest metadata
    private val _videoTitle = MutableStateFlow("")
    val videoTitle = _videoTitle.asStateFlow()

    // recent /playback-info polls with no playback = pending video; polls start ~1s before /play
    @Volatile private var _lastVideoPollAt = 0L
    @Volatile private var _videoPollSuppressed = false
    // sender-side end (track change, autoplay): keep the video screen until the next /play arrives
    @Volatile private var _videoHandoffUntil = 0L

    fun videoSessionPending(): Boolean {
        if (_videoPlaybackActive.value || _audioOnly.value || _mirroringActive.value) return false
        val now = SystemClock.elapsedRealtime()
        if (now < _videoHandoffUntil && _connectionCount.value > 0) return true
        return !_videoPollSuppressed && now - _lastVideoPollAt < VIDEO_POLL_PENDING_TIMEOUT_MS
    }

    // set once mirroring reports a real size; stops with session
    private val _mirroringActive = MutableStateFlow(false)
    val mirroringActive = _mirroringActive.asStateFlow()
    // decoded mirroring stream size; null when no mirroring stream
    private val _mirrorSize = MutableStateFlow<Pair<Int, Int>?>(null)
    val mirrorSize = _mirrorSize.asStateFlow()

    private val _trackInfo = MutableStateFlow(TrackInfo())
    val trackInfo = _trackInfo.asStateFlow()

    private val _positionMs = MutableStateFlow(0L)
    val positionMs = _positionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs = _durationMs.asStateFlow()

    private val _playing = MutableStateFlow(true)
    val playing = _playing.asStateFlow()

    @Volatile private var _progressBaseMs = 0L
    @Volatile private var _progressBaseTime = 0L

    fun currentPositionMs(): Long {
        if (_progressBaseTime == 0L || !_playing.value) return _positionMs.value
        val elapsed = SystemClock.elapsedRealtime() - _progressBaseTime
        return (_progressBaseMs + elapsed).coerceIn(0, _durationMs.value)
    }

    var dacpController: DacpController? = null
        private set
    lateinit var dacpPlayer: DacpPlayer
        private set
    private val _mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var _coverArtBytes: ByteArray? = null
    private var mediaSession: MediaSession? = null
    private lateinit var sessionPlayer: SessionPlayer
    private var mediaReceiver: BroadcastReceiver? = null
    private var volumeReceiver: BroadcastReceiver? = null
    @Volatile private var _syncClientVol = Prefs.SYNC_CLIENT_VOLUME.def
    private var _pendingServerVolEchoes = 0
    // senders park volume at -30 as route closes
    private var _serverPreZeroIdx = -1
    @Volatile private var _clientVol: Float? = null
    private var _clientVolTarget = -1f
    private var _clientVolHint = 0
    private var _clientVolDir = 0
    private var _clientVolSteps = 0
    private val _clientVolTimeout = Runnable { _clientVolDriveEnd() }

    var logCallback: ((String) -> Unit)? = null

    @Volatile private var _lastPin: String? = null
    var pinCallback: ((String?) -> Unit)? = null
        set(value) {
            field = value
            value?.invoke(_lastPin)
        }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        logCallback?.invoke(msg)
    }

    override fun onLog(msg: String) {
        logCallback?.invoke(msg)
    }

    inner class LocalBinder : Binder() {
        val service: AirPlayService
            get() = this@AirPlayService
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return LocalBinder()
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        dacpController = DacpController(this)
        dacpPlayer = DacpPlayer(
            mainLooper,
            dacp = { dacpController },
            snapshot = {
                DacpPlayer.Snapshot(
                    track = _trackInfo.value,
                    artworkData = _coverArtBytes,
                    durationMs = _durationMs.value,
                    playing = _playing.value,
                    active = _audioOnly.value && _connectionCount.value > 0,
                )
            },
            positionMs = ::currentPositionMs,
            setPlaying = ::_setPlaying,
        )
        sessionPlayer = SessionPlayer(mainLooper, VIDEO_SEEK_STEP_MS, ::_sessionSnapshot, object : SessionPlayer.Controls {
            override fun setPlaying(playing: Boolean) {
                if (_videoPlaybackActive.value) {
                    airPlayVideoPlayer.setPlaying(playing)
                    return
                }
                _setPlaying(playing)
                dacpController?.let { if (playing) it.play() else it.pause() }
            }
            override fun stop() {
                if (_videoPlaybackActive.value) stopVideoPlayback()
            }
            override fun seekTo(positionMs: Long) {
                if (_videoPlaybackActive.value) airPlayVideoPlayer.scrub(positionMs / 1000f)
            }
            override fun next() {
                if (!_videoPlaybackActive.value) dacpController?.nextItem()
            }
            override fun previous() {
                if (!_videoPlaybackActive.value) dacpController?.prevItem()
            }
        })
        mediaReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                when (intent.action) {
                    ACTION_PLAY_PAUSE -> togglePlayPause()
                    ACTION_NEXT -> dacpController?.nextItem()
                    ACTION_PREV -> dacpController?.prevItem()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(ACTION_PLAY_PAUSE)
            addAction(ACTION_NEXT)
            addAction(ACTION_PREV)
        }
        ContextCompat.registerReceiver(this, mediaReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)

        volumeReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.getIntExtra(VolumeBroadcast.EXTRA_STREAM_TYPE, -1) != AudioManager.STREAM_MUSIC) return
                val idx = intent.getIntExtra(VolumeBroadcast.EXTRA_VALUE, -1)
                val prev = intent.getIntExtra(VolumeBroadcast.EXTRA_PREV_VALUE, -1)
                if (idx < 0 || prev < 0 || idx == prev) return
                if (_pendingServerVolEchoes > 0) { _pendingServerVolEchoes--; return }
                if (!_syncClientVol || _connectionCount.value == 0) return
                val target = idx.toFloat() / _serverVolMax()
                Log.d(TAG, "receiver volume $prev -> $idx, sync sender to $target")
                _serverPreZeroIdx = -1
                _driveClientVol(target, if (idx > prev) 1 else -1)
            }
        }
        ContextCompat.registerReceiver(this, volumeReceiver, IntentFilter(VolumeBroadcast.ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED)

        airPlayVideoPlayer.onVideoSize = { width, height, aspect ->
            _videoPlaybackAspect.value = aspect
            _videoPlaybackSize.value = width to height
        }
        airPlayVideoPlayer.onTitle = { _videoTitle.value = it ?: "" }
        airPlayVideoPlayer.onFirstFrame = { _videoFrameShown.value = true }
        airPlayVideoPlayer.onEnded = { _endVideoPlayback("AirPlay Video stopped (player)") }
        airPlayVideoPlayer.onPlaybackInfo = { snapshot ->
            if (nativeHandle != 0L) {
                NativeBridge.nativeUpdatePlaybackInfo(nativeHandle, snapshot.position, snapshot.duration, snapshot.rate, snapshot.ready)
            }
            if (_videoPlaybackActive.value) {
                _updateVideoPlaybackState(snapshot.position, snapshot.rate)
                _videoPlaybackInfo.value = VideoPlaybackInfo(
                    positionMs = (snapshot.position * 1000).toLong(),
                    durationMs = if (snapshot.duration > 0f) (snapshot.duration * 1000).toLong() else 0L,
                    playing = snapshot.playWhenReady,
                    speed = snapshot.speed,
                    skipSilence = snapshot.skipSilence,
                    buffering = snapshot.buffering,
                )
            }
        }
        lifecycleScope.launch {
            prefs.audioConfigFlow()
                .debounce(AUDIO_CONFIG_DEBOUNCE_MS)
                .collect { audioRenderer.updateConfig(it) }
        }
        lifecycleScope.launch {
            prefs.observe { this[Prefs.SYNC_CLIENT_VOLUME] }.collect {
                _syncClientVol = it
                _clientVolDriveEnd()
                _applyClientVol()
            }
        }
        lifecycleScope.launch {
            prefs.changes()
                .filter { Prefs.all[it]?.restart == true }
                .debounce(SERVER_RESTART_DEBOUNCE_MS)
                .collect { _restartServer() }
        }
        val lanRequest = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addTransportType(NetworkCapabilities.TRANSPORT_ETHERNET)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        connectivityManager.registerNetworkCallback(lanRequest, networkCallback)
        lifecycleScope.launch {
            _lanAddresses
                .debounce(NETWORK_RESTART_DEBOUNCE_MS)
                .collect { addrs ->
                    if (addrs.isEmpty() || addrs == _advertisedAddresses || prefs[Prefs.USB_ONLY]) return@collect
                    if (_advertisedAddresses.isEmpty()) { _advertisedAddresses = addrs; return@collect }
                    log("LAN address changed to $addrs, restarting server")
                    _restartServer()
                }
        }
    }

    private fun _updateLan(network: Network, lp: LinkProperties) {
        _lanNetworks[network] = lp.linkAddresses.mapNotNull { (it.address as? Inet4Address)?.hostAddress }.toSet()
        _lanAddresses.value = _lanNetworks.values.flatten().toSet()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        // null intent: the system restarted this sticky service after killing the process
        if (intent == null || intent.action == ACTION_START_SERVER) {
            try {
                promoteToForeground()
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) extends IllegalStateException
                log("Foreground start not allowed: ${e.message}")
                stopSelf(startId)
                return START_NOT_STICKY
            }
            _startServer(ensureServiceStarted = false)
            if (_serverState.value != ServerState.RUNNING) stopSelf(startId)
        }
        return if (_serverState.value == ServerState.RUNNING) START_STICKY else START_NOT_STICKY
    }

    fun startServer() {
        _startServer(ensureServiceStarted = true)
    }

    // the wakelock spans the server lifetime so the receiver stays reachable; _shutdown/_failStart release it
    @SuppressLint("WakelockTimeout")
    private fun _startServer(ensureServiceStarted: Boolean) {
        if (_serverState.value == ServerState.RUNNING) return
        if (!hasLocalNetwork()) {
            log("Local network permission denied")
            _failStart()
            return
        }
        val effectiveName = prefs[Prefs.SERVER_NAME].ifBlank { Prefs.SERVER_NAME.def }

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "airplay:server").apply { acquire() }

        nsdManager = NsdServiceManager(this).apply { acquireMulticastLock() }

        val hwAddr = getHwAddr()
        val keyFile = filesDir.resolve("airplay.pem").absolutePath
        val nohold = prefs[Prefs.ALLOW_NEW_CONN]
        val requirePin = prefs[Prefs.REQUIRE_PIN]

        // oboe's OpenSL ES backend (pre-AAudio devices, API < 27) can't discover native
        // rate / burst size itself; feed it AudioManager values so low-latency buffer
        // sizing works there
        // see: https://github.com/google/oboe/blob/main/docs/GettingStarted.md#obtaining-optimal-latency
        NativeBridge.nativeSetDefaultStreamValues(
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
            audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0
        )
        nativeHandle = NativeBridge.nativeInit(this, hwAddr, effectiveName, keyFile, nohold, requirePin)
        if (nativeHandle == 0L) {
            log("Native init failed")
            _failStart()
            return
        }
        audioRenderer.attachEngine(nativeHandle)

        // apply settings from preferences
        val maxFps = prefs[Prefs.MAX_FPS]
        val overscanned = prefs[Prefs.OVERSCANNED]
        val audioLatencyMs = prefs[Prefs.AUDIO_LATENCY_MS]
        val (reqW, reqH) = _displaySize(clamp = false)
        val h265 = videoRenderer.selectDecoders(reqW, reqH, maxFps, Prefs.forced(prefs[Prefs.H265]))
        val alac = prefs[Prefs.ALAC_ENABLED]
        val aac = prefs[Prefs.AAC_ENABLED]

        videoRenderer.enforceSdr = prefs[Prefs.ENFORCE_SDR]
        videoRenderer.keyAllowFrameDrop = prefs[Prefs.KEY_ALLOW_FRAME_DROP]
        videoRenderer.selector.maxOperatingRate = Prefs.forced(prefs[Prefs.OPERATING_RATE])
        videoRenderer.benchmarkLog = prefs[Prefs.BENCHMARK_LOG]
        videoRenderer.benchmarkLogCallback = { msg -> logCallback?.invoke(msg) }
        videoRenderer.scheduledOutputBufferRelease = prefs[Prefs.SCHEDULED_OUTPUT_BUFFER_RELEASE]
        NativeBridge.nativeSetH265Enabled(nativeHandle, h265)
        NativeBridge.nativeSetCodecs(nativeHandle, alac, aac)
        val advertiseVideo = prefs[Prefs.ADVERTISE_VIDEO]
        val advertiseMirroring = prefs[Prefs.ADVERTISE_MIRRORING]
        val advertiseAudio = prefs[Prefs.ADVERTISE_AUDIO]
        NativeBridge.nativeSetHlsEnabled(nativeHandle, advertiseVideo)
        NativeBridge.nativeSetLang(nativeHandle, ConfigurationCompat.getLocales(resources.configuration).toLanguageTags().replace(',', ':'))
        NativeBridge.nativeSetFeature(nativeHandle, NativeBridge.FT_MIRROR, advertiseMirroring)
        NativeBridge.nativeSetFeature(nativeHandle, NativeBridge.FT_AUDIO, advertiseAudio)
        NativeBridge.nativeSetPlist(nativeHandle, "maxFPS", maxFps)
        NativeBridge.nativeSetPlist(nativeHandle, "overscanned", if (overscanned) 1 else 0)
        if (audioLatencyMs >= 0) {
            NativeBridge.nativeSetPlist(nativeHandle, "audio_delay_micros", audioLatencyMs * 1000)
        }

        // set display params
        lastOrientation = resources.configuration.orientation
        val (w, h) = _displaySize()
        videoRenderer.setResolution(w, h)
        _videoResolution.value = "${w}x${h}"
        _videoAspect.value = w.toFloat() / h
        NativeBridge.nativeSetDisplaySize(nativeHandle, w, h, maxFps)

        val requestedPort = prefs[Prefs.SERVER_PORT]
        val port = NativeBridge.nativeStart(nativeHandle, requestedPort)
        if (port < 0) {
            log("Failed to start on port $requestedPort")
            _failStart()
            return
        }

        // register mdns services
        val raopTxt = NativeBridge.nativeGetRaopTxtRecords(nativeHandle) ?: emptyMap()
        val airplayTxt = NativeBridge.nativeGetAirplayTxtRecords(nativeHandle) ?: emptyMap()
        val raopName = NativeBridge.nativeGetRaopServiceName(nativeHandle) ?: "AirPlay"
        val resolvedName = NativeBridge.nativeGetServerName(nativeHandle) ?: effectiveName

        val services = buildList {
            if (advertiseAudio) add(MdnsService(raopName, "_raop._tcp", port, raopTxt))
            add(MdnsService(resolvedName, "_airplay._tcp", port, airplayTxt))
        }
        if (prefs[Prefs.USB_ONLY]) {
            usbAdvertiser = UsbMdnsAdvertiser(services) { iface, addr ->
                log(if (iface == null) "USB link down" else "USB link up on $iface (${addr?.hostAddress})")
            }.apply { start() }
        } else {
            nsdManager?.register(services)
        }

        _serverState.value = ServerState.RUNNING
        _advertisedAddresses = _lanAddresses.value
        if (ensureServiceStarted) {
            ContextCompat.startForegroundService(this, Intent(this, AirPlayService::class.java))
        }
        promoteToForeground()
        log("Server started on port $port")
    }

    private fun _orientationFollowsDevice(): Boolean =
        prefs[Prefs.RESOLUTION] == Prefs.AUTO

    private fun _displaySize(clamp: Boolean = true): Pair<Int, Int> {
        val res = prefs[Prefs.RESOLUTION]
        val portrait = when (res) {
            Prefs.PORTRAIT -> true
            Prefs.LANDSCAPE -> false
            else -> resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        }
        val (rawW, rawH) = realDisplaySize()
        val device = if (portrait != rawH >= rawW) rawH to rawW else rawW to rawH
        if (res == Prefs.PORTRAIT || res == Prefs.LANDSCAPE) return device
        val (w, h) = if (res.contains("x")) {
            res.split("x").let { it[0].toInt() to it[1].toInt() }
        } else device
        if (!clamp) return w to h
        // strict decoders black-screen past their limits; advertised size is only upper bound for senders
        val (maxW, maxH) = videoRenderer.maxResolution()
        return w.coerceAtMost(maxW) to h.coerceAtMost(maxH)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == lastOrientation) return
        lastOrientation = newConfig.orientation
        if (nativeHandle == 0L || _serverState.value != ServerState.RUNNING) return
        if (!_orientationFollowsDevice()) return
        val (w, h) = _displaySize()
        NativeBridge.nativeSetDisplaySize(nativeHandle, w, h, prefs[Prefs.MAX_FPS])
        log("Advertising ${w}x${h} from next session")
    }

    private fun _failStart() {
        audioRenderer.detachEngine()
        if (nativeHandle != 0L) {
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        nsdManager?.release()
        nsdManager = null
        wakeLock?.release()
        wakeLock = null
        _serverState.value = ServerState.ERROR
        if (foregroundStarted) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            foregroundStarted = false
        }
    }

    fun stopServer() {
        _shutdown()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        foregroundStarted = false
        stopSelf()
    }

    private fun _restartServer() {
        if (_serverState.value != ServerState.RUNNING) return
        _shutdown()
        _startServer(ensureServiceStarted = false)
        if (_serverState.value != ServerState.RUNNING) stopSelf()
    }

    private fun _shutdown() {
        audioRenderer.detachEngine()
        if (nativeHandle != 0L) {
            NativeBridge.nativeStop(nativeHandle)
            NativeBridge.nativeDestroy(nativeHandle)
            nativeHandle = 0L
        }
        dacpController?.reset()
        usbAdvertiser?.stop()
        usbAdvertiser = null
        nsdManager?.release()
        nsdManager = null
        wakeLock?.release()
        wakeLock = null
        videoRenderer.release()
        airPlayVideoPlayer.stop()
        _setSessionActive(false)
        _audioOnly.value = false
        _videoPlaybackActive.value = false
        _mirroringActive.value = false
        _mirrorSize.value = null
        _videoPlaybackInfo.value = VideoPlaybackInfo()
        _lastVideoPollAt = 0
        _videoPollSuppressed = false
        _videoHandoffUntil = 0L
        _coverArtBytes = null
        _trackInfo.value = TrackInfo()
        _positionMs.value = 0
        _durationMs.value = 0
        _serverState.value = ServerState.STOPPED
        _connectionCount.value = 0
        _clientVol = null
        _clientVolDriveEnd()
        _applyClientVol()
        _refreshDacpPlayer()
        log("Server stopped")
    }

    fun setVideoSurface(surface: Surface) {
        videoRenderer.setSurface(surface)
    }

    fun clearVideoSurface(surface: Surface) {
        videoRenderer.clearSurface(surface)
    }

    fun setVideoPlaybackSurface(surface: Surface) {
        airPlayVideoPlayer.setSurface(surface)
    }

    fun clearVideoPlaybackSurface(surface: Surface) {
        airPlayVideoPlayer.clearSurface(surface)
    }

    fun setVideoPlaying(playing: Boolean) {
        airPlayVideoPlayer.setPlaying(playing)
    }

    fun seekVideoTo(positionMs: Long) {
        airPlayVideoPlayer.scrub(positionMs / 1000f)
    }

    fun setVideoScrubbing(enabled: Boolean) {
        airPlayVideoPlayer.setScrubbing(enabled)
    }

    fun setVideoSpeed(speed: Float) {
        airPlayVideoPlayer.setSpeed(speed)
    }

    fun setVideoSkipSilence(enabled: Boolean) {
        airPlayVideoPlayer.setSkipSilence(enabled)
    }

    fun seekVideoBy(deltaMs: Long) {
        airPlayVideoPlayer.seekBy(deltaMs)
    }

    fun stopVideoPlayback() = _endVideoPlayback("AirPlay Video stopped (local)", handoff = false)

    fun downloadVideo() {
        _videoLocation.value?.let { videoDownloader.start(it) }
    }

    // handoff = the sender (or the end of the item) stopped it: a next item usually follows within seconds
    private fun _endVideoPlayback(message: String, handoff: Boolean = true) {
        if (!_videoPlaybackActive.value) return
        // lingering polls after a stop must not bounce the UI back to a pending session
        _videoPollSuppressed = true
        _videoHandoffUntil = if (handoff) SystemClock.elapsedRealtime() + VIDEO_HANDOFF_GRACE_MS else 0L
        _videoPlaybackActive.value = false
        airPlayVideoPlayer.stop()
        if (!_audioOnly.value) _setSessionActive(false)
        log(message)
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    override fun onDestroy() {
        try { connectivityManager.unregisterNetworkCallback(networkCallback) } catch (_: Exception) {}
        stopServer()
        dacpPlayer.release()
        mediaReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        mediaReceiver = null
        volumeReceiver?.let {
            try { unregisterReceiver(it) } catch (_: Exception) {}
        }
        volumeReceiver = null
        dacpController?.release()
        dacpController = null
        mediaSession?.release()
        mediaSession = null
        sessionPlayer.release()
        super.onDestroy()
    }

    // RaopCallbackHandler (called from native threads)

    override fun onVideoData(data: ByteArray, ntpTimeNs: Long, isH265: Boolean) {
        videoRenderer.feedFrame(data, ntpTimeNs, isH265)
    }

    override fun onVideoSessionPoll() {
        _lastVideoPollAt = SystemClock.elapsedRealtime()
    }

    override fun onVideoPlay(location: String, startPositionSeconds: Float) {
        _videoLocation.value = location
        _videoPlaySeq.value++
        _videoPollSuppressed = false
        _videoHandoffUntil = 0L
        _videoPlaybackInfo.value = VideoPlaybackInfo(positionMs = (startPositionSeconds * 1000).toLong())
        _videoPlaybackAspect.value = 16f / 9f
        _videoPlaybackSize.value = null
        _videoFrameShown.value = false
        _videoTitle.value = ""
        _videoPlaybackActive.value = true
        _launchForContent()
        airPlayVideoPlayer.play(location, startPositionSeconds)
        // claim media-button routing for keys that arrive as media-session events
        _setSessionActive(true)
        log("AirPlay Video play: $location @ ${startPositionSeconds}s")
    }

    override fun onVideoScrub(positionSeconds: Float) {
        airPlayVideoPlayer.scrub(positionSeconds)
    }

    override fun onVideoRate(rate: Float) {
        airPlayVideoPlayer.setRate(rate)
    }

    override fun onVideoStop() = _endVideoPlayback("AirPlay Video stopped")

    override fun onAudioFormat(ct: Int, spf: Int, usingScreen: Boolean) {
        clearPin()
        audioRenderer.start()
        audioRenderer.setFormat(ct, spf)
        if (!usingScreen) _setPlaying(true)
        if (!usingScreen && !_audioOnly.value) {
            // pure music streaming (not screen mirroring audio)
            _setAudioOnly(true)
        }
        log("Audio format: ct=$ct spf=$spf screen=$usingScreen")
    }

    override fun onVideoSize(srcW: Float, srcH: Float, w: Float, h: Float) {
        clearPin()
        if (w > 0 && h > 0) {
            _videoAspect.value = w / h
            _videoResolution.value = "${w.toInt()}x${h.toInt()}"
            videoRenderer.setResolution(w.toInt(), h.toInt())
            _mirrorSize.value = w.toInt() to h.toInt()
            if (!_mirroringActive.value) _launchForContent()
            _mirroringActive.value = true
        }
        log("Video size: ${srcW}x${srcH} -> ${w}x${h}")
    }

    override fun onClientVolume(db: Float) {
        val frac = _dbToFrac(db)
        _clientVol = frac
        Log.d(TAG, "sender volume ${db}dB, frac $frac")
        _mainHandler.post {
            val driving = _clientVolTarget >= 0f
            if (driving) _clientVolStep()
            if (!driving || !_syncClientVol) _applyClientVol()
        }
    }

    override fun onClientVolumeQuery(): Float {
        val db = _fracToDb(_clientVolExpected())
        Log.d(TAG, "sender volume query -> ${db}dB")
        return db
    }

    private fun _clientVolExpected(): Float = if (_syncClientVol) _serverVolFrac() else _clientVol ?: 1f

    fun clientVolume(): Float = _clientVol ?: _clientVolExpected()

    // synced: receiver volume broadcast then drives sender
    fun setClientVolume(frac: Float) {
        if (_syncClientVol) {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, _serverVolIdx(frac), 0)
            return
        }
        _driveClientVol(frac, if (frac > clientVolume()) 1 else -1)
    }

    private fun _applyClientVol() {
        val frac = _clientVol
        if (!_syncClientVol) {
            _setClientGain(_clientGain(frac ?: 1f))
            return
        }
        _setClientGain(1f)
        if (frac == null) return
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val idx = _serverVolIdx(frac)
        _serverPreZeroIdx = if (idx == 0) (if (_serverPreZeroIdx < 0) cur else _serverPreZeroIdx) else -1
        if (idx != cur) {
            _pendingServerVolEchoes++
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, idx, 0)
        }
    }

    private fun _setClientGain(gain: Float) {
        if (gain == audioRenderer.clientGain) return
        audioRenderer.setClientGain(gain)
        airPlayVideoPlayer.setClientGain(gain)
    }

    private fun _serverVolMax() = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    private fun _serverVolIdx(frac: Float) = (frac * _serverVolMax()).roundToInt()
    private fun _serverVolFrac() = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / _serverVolMax()

    private fun _driveClientVol(target: Float, hint: Int) {
        val active = _clientVolTarget >= 0f
        _clientVolTarget = target
        _clientVolHint = hint
        _clientVolDir = 0
        _clientVolSteps = 0
        if (!active) _clientVolStep()
    }

    private fun _clientVolStep() {
        _mainHandler.removeCallbacks(_clientVolTimeout)
        val target = _clientVolTarget
        if (target < 0f) return
        val frac = _clientVol
        val want = when {
            frac == null -> _clientVolHint
            abs(target - frac) <= CLIENT_VOL_EPS -> 0
            target > frac -> 1
            else -> -1
        }
        if (want == 0 || (_clientVolDir != 0 && want != _clientVolDir) || _clientVolSteps >= CLIENT_VOL_MAX_STEPS) {
            _clientVolDriveEnd()
            return
        }
        _clientVolDir = want
        _clientVolSteps++
        if (want > 0) dacpController?.volumeUp() else dacpController?.volumeDown()
        _mainHandler.postDelayed(_clientVolTimeout, CLIENT_VOL_TIMEOUT_MS)
    }

    private fun _clientVolDriveEnd() {
        _mainHandler.removeCallbacks(_clientVolTimeout)
        if (_clientVolTarget >= 0f) Log.d(TAG, "sender volume drive done: sender $_clientVol, target $_clientVolTarget")
        _clientVolTarget = -1f
        _clientVolDir = 0
    }

    override fun onConnectionInit() {
        _connectionCount.value++
        log("Sender connected (${_connectionCount.value})")
    }

    override fun onConnectionDestroy() {
        _connectionCount.value = (_connectionCount.value - 1).coerceAtLeast(0)
        if (_connectionCount.value == 0) {
            // clients may drop without POST /stop; must run before the poll-state reset
            _endVideoPlayback("AirPlay Video stopped (disconnect)")
            // last client gone: release audio output devices to save power
            audioRenderer.stop()
            _audioOnly.value = false
            _lastVideoPollAt = 0
            _videoPollSuppressed = false
            _videoHandoffUntil = 0L
            _coverArtBytes = null
            _trackInfo.value = TrackInfo()
            _positionMs.value = 0
            _durationMs.value = 0
            dacpController?.reset()
            _clientVol = null
            _mainHandler.post {
                _clientVolDriveEnd()
                if (_serverPreZeroIdx >= 0) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, _serverPreZeroIdx, 0)
                    _serverPreZeroIdx = -1
                }
                _applyClientVol()
            }
            _setSessionActive(false)
            _refreshDacpPlayer()
            _updateMediaNotification()
        }
        log("Sender disconnected (${_connectionCount.value})")
    }

    override fun onConnectionReset(reason: Int) {
        log("Connection reset: $reason")
    }

    override fun onDisplayPin(pin: String) {
        if (_lastPin == pin) return
        _lastPin = pin
        pinCallback?.invoke(pin)
        _updateMediaNotification()
    }

    override fun onMetadata(data: ByteArray) {
        val map = DmapParser.parse(data)
        val info = TrackInfo.fromDmap(map, _trackInfo.value.coverArt)
        _trackInfo.value = info
        if (info.durationMs > 0) _durationMs.value = info.durationMs
        _updateMediaMetadata()
        _refreshDacpPlayer()
        log("Track: ${info.artist} - ${info.title}")
    }

    override fun onCoverArt(data: ByteArray) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(data, 0, data.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= COVER_ART_MAX_PX) sample *= 2
        val bmp = BitmapFactory.decodeByteArray(data, 0, data.size, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return
        // the session decodes artwork again: hand it the reduced image, not the sender's original
        _coverArtBytes = if (sample == 1) data else ByteArrayOutputStream().also {
            bmp.compress(Bitmap.CompressFormat.JPEG, COVER_ART_JPEG_QUALITY, it)
        }.toByteArray()
        _trackInfo.value = _trackInfo.value.copy(coverArt = bmp)
        _updateMediaMetadata()
        _refreshDacpPlayer()
    }

    override fun onProgress(start: Long, curr: Long, end: Long) {
        val rate = 44100.0
        val posMs = ((curr - start) / rate * 1000).toLong().coerceAtLeast(0)
        val durMs = ((end - start) / rate * 1000).toLong().coerceAtLeast(0)
        // pause/resume transitions emit degenerate progress; keep the last good value
        if (durMs <= 0) return
        _positionMs.value = posMs
        _durationMs.value = durMs
        _progressBaseMs = posMs
        _progressBaseTime = SystemClock.elapsedRealtime()
        _playing.value = true
        _updatePlaybackState()
        _refreshDacpPlayer()
    }

    override fun onAudioTeardown() {
        _setPlaying(false)
    }

    override fun onDacpId(dacpId: String, activeRemote: String) {
        dacpController?.update(dacpId, activeRemote)
        log("DACP: $dacpId")
    }

    override fun onMirrorRunning(running: Boolean) {
        if (running) videoRenderer.startSession() else {
            videoRenderer.stopSession()
            _mirroringActive.value = false
            _mirrorSize.value = null
        }
        _setAudioOnly(!running)
    }

    private fun _setAudioOnly(audioOnly: Boolean) {
        val prev = _audioOnly.value
        _audioOnly.value = audioOnly
        _refreshDacpPlayer()
        if (audioOnly && !prev) {
            _setSessionActive(true)
            log("Audio mode")
        } else if (!audioOnly && prev) {
            _setSessionActive(false)
            _coverArtBytes = null
            _trackInfo.value = TrackInfo()
            _positionMs.value = 0
            _durationMs.value = 0
            _updateMediaNotification()
            log("Mirror mode")
        }
    }

    private fun _updateMediaMetadata() {
        _refreshSession()
        _updateMediaNotification()
    }

    fun togglePlayPause() {
        val nowPlaying = !_playing.value
        _setPlaying(nowPlaying)
        dacpController?.let { if (nowPlaying) it.play() else it.pause() }
    }

    private fun _setPlaying(playing: Boolean) {
        _playing.value = playing
        _refreshDacpPlayer()
        if (playing) {
            // resume extrapolation from current position
            _progressBaseMs = _positionMs.value
            _progressBaseTime = SystemClock.elapsedRealtime()
        } else {
            // freeze position
            _positionMs.value = currentPositionMs()
            _progressBaseTime = 0
        }
        _updatePlaybackState()
    }

    private fun _updatePlaybackState() {
        // the sender's silent raop audio session must not overwrite video session state
        if (_videoPlaybackActive.value) return
        _refreshSession()
        _updateMediaNotification()
    }

    // consumers extrapolate position from (position, speed, updateTime): push only discontinuities
    private fun _updateVideoPlaybackState(positionSeconds: Float, rate: Float) {
        val posMs = (positionSeconds * 1000).toLong()
        val now = SystemClock.elapsedRealtime()
        val expectedMs = _lastVideoStatePosMs +
            if (_lastVideoStateRate > 0f) ((now - _lastVideoStateAtMs) * _lastVideoStateRate).toLong() else 0L
        if (rate == _lastVideoStateRate && abs(posMs - expectedMs) < 1000) return
        _lastVideoStateRate = rate
        _lastVideoStatePosMs = posMs
        _lastVideoStateAtMs = now
        _refreshSession()
    }

    private var _lastVideoStateRate = -1f
    private var _lastVideoStatePosMs = 0L
    private var _lastVideoStateAtMs = 0L

    private fun _refreshDacpPlayer() {
        _mainHandler.post { dacpPlayer.refresh() }
    }

    private fun _refreshSession() {
        _mainHandler.post { if (::sessionPlayer.isInitialized) sessionPlayer.refresh() }
    }

    // a live session claims media-button routing, so it exists only while audio or video plays
    private fun _setSessionActive(active: Boolean) {
        _mainHandler.post {
            if (active && mediaSession == null) {
                mediaSession = MediaSession.Builder(this, sessionPlayer).setId(SESSION_ID).build()
            } else if (!active) {
                mediaSession?.release()
                mediaSession = null
            }
            sessionPlayer.refresh()
            _updateMediaNotification()
        }
    }

    private fun _sessionSnapshot(): SessionPlayer.Snapshot {
        if (_videoPlaybackActive.value) {
            val info = _videoPlaybackInfo.value
            return SessionPlayer.Snapshot(
                mode = SessionPlayer.Mode.VIDEO,
                title = _videoTitle.value.ifEmpty { _trackInfo.value.title },
                durationMs = info.durationMs,
                positionMs = info.positionMs,
                playing = info.playing,
                speed = info.speed,
            )
        }
        if (!_audioOnly.value || _connectionCount.value == 0) return SessionPlayer.Snapshot(SessionPlayer.Mode.IDLE)
        val track = _trackInfo.value
        return SessionPlayer.Snapshot(
            mode = SessionPlayer.Mode.AUDIO,
            title = track.title,
            artist = track.artist,
            album = track.album,
            artworkData = _coverArtBytes,
            durationMs = _durationMs.value,
            positionMs = currentPositionMs(),
            playing = _playing.value,
        )
    }

    private fun clearPin() {
        _lastPin = null
        pinCallback?.invoke(null)
        _updateMediaNotification()
    }

    fun collectDebugInfo() = DebugInfo(
        videoCodec = videoRenderer.codecName,
        videoRes = _videoResolution.value,
        videoFps = videoRenderer.fps,
        videoBitrate = videoRenderer.bitrateBps,
        videoFrames = videoRenderer.frameCount,
        droppedFrames = videoRenderer.droppedFrames,
        framePacingJitterUs = videoRenderer.framePacingJitterUs,
        audioCodec = audioRenderer.codecLabel,
        serverVolume = 100 * audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) / _serverVolMax(),
        clientVolume = (100 * clientVolume()).roundToInt(),
        audio = audioRenderer.audioDebug(),
        connections = _connectionCount.value,
    )

    // helpers

    private fun getHwAddr(): ByteArray {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            for (iface in interfaces) {
                if (iface.name.startsWith("wlan") || iface.name.startsWith("eth")) {
                    val mac = iface.hardwareAddress
                    if (isUsableMac(mac)) return mac!!
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get hardware address", e)
        }

        // fall back to stable per-install random address
        return persistedRandomMac()
            ?: byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
    }

    private fun isUsableMac(mac: ByteArray?): Boolean =
        mac != null && mac.size == 6 &&
            mac.any { it != 0.toByte() } &&
            !(mac[0] == 0x02.toByte() && mac.drop(1).all { it == 0.toByte() })

    private fun persistedRandomMac(): ByteArray? {
        macFromString(prefs.getString(Prefs.FALLBACK_MAC_ADDRESS, null))
            ?.takeIf { isUsableMac(it) }?.let { return it }
        repeat(10) {
            val mac = randomAaiMac()
            if (isUsableMac(mac)) {
                prefs.edit { putString(Prefs.FALLBACK_MAC_ADDRESS, macToString(mac)) }
                return mac
            }
        }
        return null
    }

    // random locally-administered unicast MAC in AAI SLAP quadrant
    private fun randomAaiMac(): ByteArray {
        val mac = ByteArray(6).also { SecureRandom().nextBytes(it) }
        mac[0] = ((mac[0].toInt() and 0xF0) or 0x0A).toByte()
        return mac
    }

    private fun macToString(mac: ByteArray): String = mac.joinToString(":") { "%02x".format(it) }

    private fun macFromString(s: String?): ByteArray? {
        if (s == null) return null
        return try {
            s.split(":").map { it.toInt(16).toByte() }.toByteArray()
        } catch (e: Exception) { null }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel),
                NotificationManager.IMPORTANCE_LOW
            )
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ALERTS_ID,
                getString(R.string.notification_alerts_channel),
                NotificationManager.IMPORTANCE_HIGH
            )
        )
    }

    private fun buildNotification(): Notification {
        return _buildMediaNotification()
    }

    // ServiceCompat drops the type below API 29, so the inlined constant is safe on 24-28
    @SuppressLint("InlinedApi")
    private fun promoteToForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        )
        foregroundStarted = true
    }

    // like an Apple TV: audio plays in the background; the screen opens only for mirroring or video
    private fun _launchForContent() {
        if (prefs[Prefs.LAUNCH_ON_CONNECT]) launchMainActivity()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun _buildMediaNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
        val info = _trackInfo.value
        val isAudio = _audioOnly.value && info.title.isNotEmpty()

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(pi)
            .setOngoing(true)

        if (isAudio) {
            builder.setContentTitle(info.title).setContentText(info.artist).setSubText(info.album)
            info.coverArt?.let { builder.setLargeIcon(it) }
            mediaSession?.let { session ->
                builder.setStyle(
                    MediaStyleNotificationHelper.MediaStyle(session)
                        .setShowActionsInCompactView(0, 1, 2)
                )
                // transport action buttons
                builder.addAction(android.R.drawable.ic_media_previous, "Prev", _mediaAction(ACTION_PREV))
                builder.addAction(android.R.drawable.ic_media_pause, "Pause", _mediaAction(ACTION_PLAY_PAUSE))
                builder.addAction(android.R.drawable.ic_media_next, "Next", _mediaAction(ACTION_NEXT))
            }
        } else {
            if (_lastPin != null) {
                builder.setContentTitle(getString(R.string.notification_pin_title))
                    .setContentText(getString(R.string.notification_pin_text, _lastPin))
            } else {
                builder.setContentTitle(getString(R.string.notification_title))
                    .setContentText(getString(R.string.notification_text))
            }
        }
        return builder.build()
    }

    private fun launchMainActivity() {
        Handler(Looper.getMainLooper()).post {
            if (_isAppInForeground()) return@post
            val launchIntent = Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(EXTRA_AUTO_LAUNCH, true)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !Settings.canDrawOverlays(this)) {
                log("Overlay permission missing; posting launch notification")
                _notifyLaunchFallback(launchIntent, includeOverlayAction = true)
                return@post
            }
            try {
                startActivity(launchIntent)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to launch activity", e)
                _notifyLaunchFallback(launchIntent, includeOverlayAction = false)
            }
        }
    }

    private fun _isAppInForeground(): Boolean {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
        val appProcesses = am.runningAppProcesses ?: return false
        return appProcesses.any {
            it.processName == packageName &&
                it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        }
    }

    private fun _notifyLaunchFallback(launchIntent: Intent, includeOverlayAction: Boolean) {
        val openPi = PendingIntent.getActivity(
            this,
            NOTIFICATION_LAUNCH_ID,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ALERTS_ID)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(getString(R.string.notification_launch_title))
            .setContentText(getString(R.string.notification_launch_text))
            .setContentIntent(openPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)

        if (includeOverlayAction) {
            val overlayIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.fromParts("package", packageName, null)
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val overlayPi = PendingIntent.getActivity(
                this,
                NOTIFICATION_LAUNCH_ID + 1,
                overlayIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            builder.addAction(
                android.R.drawable.ic_menu_preferences,
                getString(R.string.notification_launch_action_overlay),
                overlayPi
            )
        }
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_LAUNCH_ID, builder.build())
    }

    private fun _mediaAction(action: String): PendingIntent {
        val intent = Intent(action).setPackage(packageName)
        return PendingIntent.getBroadcast(
            this,
            action.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun _updateMediaNotification() {
        if (_serverState.value != ServerState.RUNNING) return
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, _buildMediaNotification())
    }

    enum class ServerState {
        STOPPED,
        RUNNING,
        ERROR
    }

    companion object {
        private const val TAG = "AirPlayService"
        // set when the service brought the activity up; the activity returns to the previous app afterwards
        const val EXTRA_AUTO_LAUNCH = "io.github.jqssun.airplay.AUTO_LAUNCH"
        private const val CLIENT_VOL_EPS = 0.033f
        private const val CLIENT_VOL_MAX_STEPS = 32
        private const val CLIENT_VOL_TIMEOUT_MS = 800L
        private const val CHANNEL_ID = "airplay_service"
        private const val CHANNEL_ALERTS_ID = "airplay_alerts"
        private const val NOTIFICATION_ID = 1
        private const val NOTIFICATION_LAUNCH_ID = 2
        const val ACTION_PLAY_PAUSE = "io.github.jqssun.airplay.PLAY_PAUSE"
        const val ACTION_NEXT = "io.github.jqssun.airplay.NEXT"
        const val ACTION_PREV = "io.github.jqssun.airplay.PREV"
        const val ACTION_START_SERVER = "io.github.jqssun.airplay.START_SERVER"
        // shared with dpad/double-tap seeks
        const val VIDEO_SEEK_STEP_MS = 10_000L
        private const val SESSION_ID = "AirPlay"
        // the TV player shows covers large on 1080p/4K panels: only oversized originals (>= 2x this) are reduced
        private const val COVER_ART_MAX_PX = 1920
        private const val COVER_ART_JPEG_QUALITY = 90
        const val VIDEO_POLL_PENDING_TIMEOUT_MS = 3_000L
        // long enough for a sender to queue the next track, short enough that a real stop still exits
        const val VIDEO_HANDOFF_GRACE_MS = 6_000L

        private const val AUDIO_CONFIG_DEBOUNCE_MS = 500L
        private const val SERVER_RESTART_DEBOUNCE_MS = 500L
        // waits for DHCP to settle so one address change causes one restart
        private const val NETWORK_RESTART_DEBOUNCE_MS = 2_000L

        // airplay volume is linear in dB along sender slider
        private fun _dbToFrac(db: Float) = if (db <= -144f) 0f else ((db + 30f) / 30f).coerceIn(0f, 1f)
        private fun _fracToDb(frac: Float) = if (frac <= 0f) -144f else -30f + 30f * frac
        private fun _clientGain(frac: Float) = if (frac <= 0f) 0f else 10f.pow(_fracToDb(frac) / 20f)
    }
}
