package io.github.jqssun.airplay.discovery

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log

data class MdnsService(val name: String, val type: String, val port: Int, val txt: Map<String, String>)

class NsdServiceManager(private val ctx: Context) {

    private val nsdManager = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var multicastLock: WifiManager.MulticastLock? = null
    // touched only on the main thread; NSD callbacks are posted here
    private val registrations = mutableListOf<NsdManager.RegistrationListener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var released = false

    fun acquireMulticastLock() {
        val wifi = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        multicastLock = wifi.createMulticastLock("airplay_mdns").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    fun register(services: List<MdnsService>) = services.forEach { _register(it, 0) }

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
            }
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) {
                Log.e(TAG, "${s.type} registration failed: $code (attempt ${attempt + 1})")
                // a failed listener is no longer registered; retry with a fresh one
                mainHandler.post {
                    registrations -= this
                    if (released || attempt + 1 >= MAX_REGISTER_ATTEMPTS) return@post
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
        multicastLock?.release()
        multicastLock = null
    }

    companion object {
        private const val TAG = "NsdServiceManager"
        private const val MAX_REGISTER_ATTEMPTS = 4
        private const val RETRY_BASE_DELAY_MS = 1_000L
    }
}
