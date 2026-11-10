package com.pocketcctv.app

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.Inet4Address

const val SERVICE_TYPE = "_pocketcctv._tcp."

data class CameraEndpoint(val name: String, val host: String, val port: Int)

private fun cleanName(raw: String) = raw.removePrefix("PocketCCTV-").replace("\\032", " ")

/** Camera side: announces "I am a camera" on the Wi-Fi so the tablet finds it automatically. */
class NsdAdvertiser(ctx: Context, private val name: String, private val port: Int) {
    private val mgr = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private var listener: NsdManager.RegistrationListener? = null

    fun register() {
        val info = NsdServiceInfo().apply {
            serviceName = "PocketCCTV-$name"
            serviceType = SERVICE_TYPE
            port = this@NsdAdvertiser.port
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, e: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, e: Int) {}
        }
        listener = l
        try { mgr.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) {}
    }

    fun unregister() {
        listener?.let { try { mgr.unregisterService(it) } catch (_: Exception) {} }
        listener = null
    }
}

/** Monitor side: finds cameras on the same Wi-Fi. Callbacks come from a background thread. */
class NsdFinder(
    ctx: Context,
    private val onFound: (CameraEndpoint) -> Unit,
    private val onLost: (String) -> Unit
) {
    private val mgr = ctx.applicationContext.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    private var discovery: NsdManager.DiscoveryListener? = null

    fun start() {
        val l = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
            override fun onServiceFound(i: NsdServiceInfo) { handler.post { queue.add(i); next() } }
            override fun onServiceLost(i: NsdServiceInfo) { onLost(cleanName(i.serviceName)) }
        }
        discovery = l
        try { mgr.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, l) } catch (_: Exception) {}
    }

    fun stop() {
        discovery?.let { try { mgr.stopServiceDiscovery(it) } catch (_: Exception) {} }
        discovery = null
        handler.removeCallbacksAndMessages(null)
        queue.clear()
        resolving = false
    }

    // Android can only resolve one service at a time, so we queue them.
    private fun next() {
        if (resolving) return
        val info = queue.removeFirstOrNull() ?: return
        resolving = true
        try {
            @Suppress("DEPRECATION")
            mgr.resolveService(info, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) {
                    handler.post { resolving = false; next() }
                }

                override fun onServiceResolved(i: NsdServiceInfo) {
                    val h = i.host
                    if (h is Inet4Address) {
                        onFound(CameraEndpoint(cleanName(i.serviceName), h.hostAddress ?: "", i.port))
                    }
                    handler.post { resolving = false; next() }
                }
            })
        } catch (e: Exception) {
            resolving = false
        }
    }
}
