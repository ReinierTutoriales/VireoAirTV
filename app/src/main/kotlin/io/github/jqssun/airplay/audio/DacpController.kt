package io.github.jqssun.airplay.audio

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi
import android.util.Log
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import java.io.IOException
import java.net.Inet4Address
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Sends DACP (Digital Audio Control Protocol) commands back to the AirPlay sender.
 * Resolves the sender's control port via mDNS, then sends HTTP GET requests.
 */
class DacpController(ctx: Context) {

    private val nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val exec = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var fallbackTask: Runnable? = null

    @Volatile var dacpId = ""
    @Volatile var activeRemote = ""
    @Volatile private var host = ""
    @Volatile private var port = 0
    // NsdManager.ServiceInfoCallback on API 34+; typed Any so older runtimes never resolve the class
    private var infoCallback: Any? = null

    val ready: Boolean get() = host.isNotEmpty() && activeRemote.isNotEmpty()

    fun update(dacpId: String, activeRemote: String) {
        this.dacpId = dacpId
        this.activeRemote = activeRemote
        _resolve()
    }

    fun play() = _send("/ctrl-int/1/play")
    fun pause() = _send("/ctrl-int/1/pause")
    fun playPause() = _send("/ctrl-int/1/playpause")
    fun nextItem() = _send("/ctrl-int/1/nextitem")
    fun prevItem() = _send("/ctrl-int/1/previtem")
    fun volumeUp() = _send("/ctrl-int/1/volumeup")
    fun volumeDown() = _send("/ctrl-int/1/volumedown")
    fun beginFastForward() = _send("/ctrl-int/1/beginff")
    fun beginRewind() = _send("/ctrl-int/1/beginrew")
    fun playResume() = _send("/ctrl-int/1/playresume")

    fun reset() {
        _stopTracking()
        dacpId = ""
        activeRemote = ""
        host = ""
        port = 0
    }

    fun release() {
        reset()
        exec.shutdownNow()
    }

    private fun _resolve() {
        _stopTracking()
        if (dacpId.isEmpty()) return
        val info = NsdServiceInfo().apply {
            serviceType = "_dacp._tcp"
            serviceName = "iTunes_Ctrl_$dacpId"
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) _track(info) else _resolveLegacy(info)
        } catch (e: Exception) {
            Log.w(TAG, "DACP resolve error", e)
        }
    }

    // API 34+: keeps following the sender, so an address change does not strand DACP
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private fun _track(info: NsdServiceInfo) {
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceInfoCallbackRegistrationFailed(code: Int) {
                Log.w(TAG, "DACP resolve failed: $code; falling back to resolveService")
                _resolveLegacy(info)
            }
            override fun onServiceUpdated(si: NsdServiceInfo) {
                val addr = si.hostAddresses.firstOrNull { it is Inet4Address } ?: si.hostAddresses.firstOrNull()
                host = addr?.hostAddress ?: return
                port = si.port
                fallbackTask?.let { mainHandler.removeCallbacks(it) }
                fallbackTask = null
                Log.i(TAG, "DACP resolved: $host:$port")
            }
            override fun onServiceLost() {}
            override fun onServiceInfoCallbackUnregistered() {}
        }
        nsdManager.registerServiceInfoCallback(info, Runnable::run, callback)
        infoCallback = callback
        // some Android TV 14 mDNS stacks never fire onServiceUpdated for a hand-built NsdServiceInfo;
        // fall back to resolveService if no address arrives shortly
        val task = Runnable {
            if (host.isEmpty() && infoCallback === callback) {
                Log.i(TAG, "DACP ServiceInfoCallback timed out; trying resolveService")
                _resolveLegacy(info)
            }
        }
        fallbackTask = task
        mainHandler.postDelayed(task, CALLBACK_FALLBACK_MS)
    }

    @Suppress("DEPRECATION")
    private fun _resolveLegacy(info: NsdServiceInfo) {
        nsdManager.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(si: NsdServiceInfo, code: Int) {
                Log.w(TAG, "DACP resolve failed: $code")
            }
            override fun onServiceResolved(si: NsdServiceInfo) {
                host = si.host.hostAddress ?: return
                port = si.port
                Log.i(TAG, "DACP resolved: $host:$port")
            }
        })
    }

    private fun _stopTracking() {
        fallbackTask?.let { mainHandler.removeCallbacks(it) }
        fallbackTask = null
        val callback = infoCallback ?: return
        infoCallback = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            try { nsdManager.unregisterServiceInfoCallback(callback as NsdManager.ServiceInfoCallback) } catch (_: Exception) {}
        }
    }

    private fun _send(path: String): ListenableFuture<Unit> {
        val result = SettableFuture.create<Unit>()
        if (!ready) {
            result.setException(IOException("dacp endpoint not resolved"))
            return result
        }
        try {
            exec.execute {
                try {
                    // IPv6 literals need brackets (and an escaped zone id) inside a URL authority
                    val authority = if (':' in host) "[${host.replace("%", "%25")}]:$port" else "$host:$port"
                    val url = "http://$authority$path"
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = "GET"
                    conn.setRequestProperty("Active-Remote", activeRemote)
                    conn.setRequestProperty("Host", authority)
                    conn.connectTimeout = 2000
                    conn.readTimeout = 2000
                    val code = conn.responseCode
                    try { conn.inputStream.readBytes() } catch (_: Exception) {}
                    conn.disconnect()
                    if (code in 200..299) {
                        result.set(Unit)
                    } else {
                        Log.w(TAG, "DACP $path -> HTTP $code")
                        result.setException(IOException("HTTP $code"))
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "DACP send failed: $path", e)
                    result.setException(e)
                }
            }
        } catch (e: Exception) {
            result.setException(e)
        }
        return result
    }

    companion object {
        private const val TAG = "DacpController"
        private const val CALLBACK_FALLBACK_MS = 1_500L
    }
}
