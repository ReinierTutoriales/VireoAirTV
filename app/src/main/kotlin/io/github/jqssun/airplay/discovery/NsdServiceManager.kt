package io.github.jqssun.airplay.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.jqssun.airplay.discovery.own.MdnsResponder

data class MdnsService(val name: String, val type: String, val port: Int, val txt: Map<String, String>)

class NsdServiceManager(private val ctx: Context) {

    private val nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var multicastLock: WifiManager.MulticastLock? = null
    // touched only on the main thread; NSD callbacks are posted here
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var released = false
    // what Android has registered; if a service never gets there, we announce everything ourselves
    private val registered = mutableSetOf<String>()
    private var services: List<MdnsService> = emptyList()
    private val ownResponder by lazy { MdnsResponder(ctx) }
    private val ownExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()

    fun acquireMulticastLock() {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("airplay_mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    fun register(services: List<MdnsService>) {
        this.services = services
        services.forEach { _register(it, 0) }
        // some TV boxes' NsdManager never answers at all (CastBay #214, #239)
        mainHandler.postDelayed({ if (registered.size < services.size) _announceByItself("no answer") }, NSD_ANSWER_TIMEOUT_MS)
    }

    /**
     * Android's NsdManager failed or never answered: announce the services with our own responder
     * (ported from CastBay). Android's registrations are removed first, so both never announce together.
     */
    private fun _announceByItself(reason: String) {
        if (released) return
        Log.w(TAG, "NsdManager did not register the services ($reason); announcing them ourselves")
        registrations.forEach { try { nsdManager.unregisterService(it) } catch (_: Exception) {} }
        registrations.clear()
        val host = services.firstOrNull()?.name.orEmpty()
            .replace(Regex("[^A-Za-z0-9-]"), "-").trim('-').ifEmpty { "VireoAirTV" }.take(60)
        val own = services.map {
            io.github.jqssun.airplay.discovery.own.MdnsService(it.type.trimEnd('.'), it.name, it.port, it.txt)
        }
        // sockets stay off the main thread (StrictMode forbids network I/O there)
        runCatching { ownExecutor.execute { ownResponder.start(host, own) } }
    }

    private fun _register(s: MdnsService, attempt: Int) {
        val info = NsdServiceInfo().apply {
            serviceName = s.name
            serviceType = s.type
            port = s.port
            s.txt.forEach { (k, v) -> setAttribute(k, v) }
        }
        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {
                Log.i(TAG, "${s.type} registered: ${info.serviceName}")
                mainHandler.post { registered += s.type }
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "${s.type} registration failed: $code (attempt ${attempt + 1})")
                // a failed listener is no longer registered; retry with a fresh one
                mainHandler.post {
                    registrations -= this
                    if (released) return@post
                    if (attempt + 1 >= MAX_REGISTER_ATTEMPTS) { _announceByItself("error $code"); return@post }
                    mainHandler.postDelayed({ if (!released) _register(s, attempt + 1) }, RETRY_BASE_DELAY_MS shl attempt)
                }
            }
            override fun onServiceUnregistered(info: NsdServiceInfo) {
                Log.i(TAG, "${s.type} unregistered")
            }
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "${s.type} unregister failed: $code")
            }
        }
        nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        registrations += listener
    }

    fun release() {
        released = true
        mainHandler.removeCallbacksAndMessages(null)
        registrations.forEach {
            try { nsdManager.unregisterService(it) } catch (_: Exception) {}
        }
        registrations.clear()
        registered.clear()
        ownExecutor.execute { if (ownResponder.running) ownResponder.stop() }
        ownExecutor.shutdown()
        multicastLock?.release()
        multicastLock = null
    }

    companion object {
        private const val TAG = "NsdServiceManager"
        private const val MAX_REGISTER_ATTEMPTS = 4
        private const val RETRY_BASE_DELAY_MS = 1_000L
        // retries take 1 + 2 + 4 s; an NsdManager silent past this is not going to answer
        private const val NSD_ANSWER_TIMEOUT_MS = 15_000L
    }
}
