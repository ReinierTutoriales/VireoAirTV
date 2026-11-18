package io.github.jqssun.airplay

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Rational
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.jqssun.airplay.service.AirPlayService
import io.github.jqssun.airplay.ui.MainScreen
import io.github.jqssun.airplay.ui.theme.AirPlayTheme
import io.github.jqssun.airplay.viewmodel.LocalNetworkAccess
import io.github.jqssun.airplay.viewmodel.MainViewModel
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private var service: AirPlayService? = null
    val isInPip = mutableStateOf(false)
    // brought up by the service for a mirroring/video session: give the screen back when it ends
    private var autoLaunched = false
    private var sawVisual = false
    private val logCallback: (String) -> Unit = { viewModel.addLog(it) }
    private val pinCallback: (String?) -> Unit = { viewModel.showPin(it) }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val svc = (binder as? AirPlayService.LocalBinder)?.service ?: return
            service = svc
            svc.logCallback = logCallback
            svc.pinCallback = pinCallback
            viewModel.bindService(svc)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) _refreshForeground()
            _autoStart()
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            service = null
            viewModel.unbindService()
        }
    }

    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { if (it.isNotEmpty()) _syncLocalNetwork(requested = true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) autoLaunched = intent.getBooleanExtra(AirPlayService.EXTRA_AUTO_LAUNCH, false)

        val missing = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !_granted(Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (!hasLocalNetwork()) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
        }
        if (missing.isNotEmpty()) permissions.launch(missing.toTypedArray())

        bindService(Intent(this, AirPlayService::class.java), connection, BIND_AUTO_CREATE)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.connectionCount, viewModel.pref(Prefs.DEBUG_ENABLED)) { conns, debug ->
                    conns > 0 || debug
                }.distinctUntilChanged().collectLatest { active ->
                    if (active) {
                        while (true) {
                            viewModel.updatePolledMetrics()
                            delay(200)
                        }
                    } else {
                        viewModel.updatePolledMetrics()
                    }
                }
            }
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(viewModel.pref(Prefs.KEEP_SCREEN_ON), viewModel.connectionCount) { keep, conns ->
                    keep && conns > 0
                }.collect { on ->
                    if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                }
            }
        }

        // return to the previous app once nothing is shown; the delay covers video handoffs between items
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                _visualSession().distinctUntilChanged().collectLatest { visible ->
                    if (visible) { sawVisual = true; return@collectLatest }
                    // only after a picture was shown: the service binding starts out all false
                    if (!autoLaunched || !sawVisual || isInPip.value) return@collectLatest
                    delay(RETURN_DELAY_MS)
                    autoLaunched = false
                    sawVisual = false
                    moveTaskToBack(true)
                }
            }
        }

        // auto-enter pre-declared in pip params
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            lifecycleScope.launch {
                repeatOnLifecycle(Lifecycle.State.STARTED) {
                    combine(
                        viewModel.serverState, _visualSession(),
                        viewModel.videoPlaybackActive, viewModel.videoPlaybackAspect,
                        viewModel.videoAspect
                    ) { _, _, _, _, _ -> }.collect { setPictureInPictureParams(_pipParams()) }
                }
            }
        }

        setContent {
            AirPlayTheme {
                MainScreen(
                    viewModel = viewModel,
                    isInPip = isInPip.value,
                    onSurfaceAvailable = { viewModel.onSurfaceAvailable(it) },
                    onSurfaceDestroyed = { viewModel.onSurfaceDestroyed(it) },
                    onPip = { enterPip() },
                    onLocalNetwork = ::_requestLocalNetwork
                )
            }
        }
    }

    private fun _granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun _autoStart() {
        val svc = service ?: return
        if (viewModel.pref(Prefs.AUTO_START).value && svc.serverState.value == AirPlayService.ServerState.STOPPED && hasLocalNetwork()) {
            viewModel.startServer()
        }
    }
    
    private fun _refreshForeground() {
        if (service?.serverState?.value == AirPlayService.ServerState.RUNNING) {
            startService(Intent(this, AirPlayService::class.java))
        }
    }

    private fun _syncLocalNetwork(requested: Boolean = false) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return
        val p = Manifest.permission.ACCESS_LOCAL_NETWORK
        val prev = viewModel.localNetwork.value
        val now = when {
            hasLocalNetwork() -> LocalNetworkAccess.GRANTED
            shouldShowRequestPermissionRationale(p) -> LocalNetworkAccess.ASK
            requested || prev == LocalNetworkAccess.SETTINGS -> LocalNetworkAccess.SETTINGS
            else -> LocalNetworkAccess.ASK
        }
        viewModel.setLocalNetwork(now)
        if (now == LocalNetworkAccess.GRANTED && prev != LocalNetworkAccess.GRANTED) _autoStart()
    }

    private fun _requestLocalNetwork() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.CINNAMON_BUN) return
        if (viewModel.localNetwork.value == LocalNetworkAccess.SETTINGS) {
            startActivity(appSettingsIntent())
        } else {
            permissions.launch(arrayOf(Manifest.permission.ACCESS_LOCAL_NETWORK))
        }
    }

    override fun onStart() {
        super.onStart()
        _syncLocalNetwork()
        _refreshForeground()
    }

    fun enterPip() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        enterPictureInPictureMode(_pipParams())
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun _pipParams(): PictureInPictureParams {
        val aspect = if (viewModel.videoPlaybackActive.value) viewModel.videoPlaybackAspect.value
            else viewModel.videoAspect.value
        val rational = Rational((aspect * 1000).toInt().coerceIn(1, 2390), 1000)
        val builder = PictureInPictureParams.Builder().setAspectRatio(rational)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setAutoEnterEnabled(_shouldAutoPip()).setSeamlessResizeEnabled(true)
        }
        return builder.build()
    }

    // a picture worth keeping on screen: mirroring, or an AirPlay video (playing or between items)
    private fun _visualSession() = combine(
        viewModel.mirroringActive, viewModel.videoPlaybackActive, viewModel.videoSessionPending
    ) { mirroring, video, pending -> mirroring || video || pending }

    // pip only when there is a picture; an audio-only or idle connection would float an empty window
    private fun _shouldAutoPip(): Boolean =
        viewModel.serverState.value == AirPlayService.ServerState.RUNNING &&
            (viewModel.mirroringActive.value || viewModel.videoPlaybackActive.value ||
                viewModel.videoSessionPending.value)

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // launcher or notification taps from the user keep the app open after the session
        autoLaunched = intent.getBooleanExtra(AirPlayService.EXTRA_AUTO_LAUNCH, false)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && _shouldAutoPip()) enterPip()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        isInPip.value = isInPictureInPictureMode
    }

    override fun onStop() {
        super.onStop()
        // never stop mid-session
        if (!viewModel.pref(Prefs.RUN_IN_BACKGROUND).value && !isChangingConfigurations &&
            viewModel.serverState.value == AirPlayService.ServerState.RUNNING &&
            viewModel.connectionCount.value == 0) {
            viewModel.stopServer()
        }
    }

    override fun onDestroy() {
        service?.let {
            if (it.logCallback === logCallback) it.logCallback = null
            if (it.pinCallback === pinCallback) it.pinCallback = null
        }
        unbindService(connection)
        super.onDestroy()
    }

    companion object {
        private const val RETURN_DELAY_MS = 2_000L
    }
}
