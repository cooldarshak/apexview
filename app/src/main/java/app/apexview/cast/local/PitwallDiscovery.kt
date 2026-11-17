package app.apexview.cast.local

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Handler
import android.os.Looper
import java.net.InetAddress

internal data class PitwallDevice(val id: String, val name: String, val address: InetAddress, val port: Int)

/** Resolve sequentially: older Android NSD implementations reject concurrent resolve requests. */
internal class PitwallDiscovery(context: Context, private val changed: (List<PitwallDevice>) -> Unit,
                                private val failure: (String) -> Unit) : AutoCloseable {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val found = mutableSetOf<String>()
    private val devices = linkedMapOf<String, PitwallDevice>()
    private val queue = ArrayDeque<NsdServiceInfo>()
    private var resolving = false
    @Volatile private var closed = false
    private val listener = object : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(type: String) {}
        override fun onDiscoveryStopped(type: String) {}
        override fun onStartDiscoveryFailed(type: String, code: Int) { main.post { if (!closed) failure("TV discovery failed ($code). Reopen Cast to retry.") } }
        override fun onStopDiscoveryFailed(type: String, code: Int) {}
        override fun onServiceFound(info: NsdServiceInfo) { main.post {
            if (!closed && found.add(info.serviceName)) { queue.addLast(info); resolveNext() }
        } }
        override fun onServiceLost(info: NsdServiceInfo) { main.post {
            found.remove(info.serviceName); devices.remove(info.serviceName)
            if (!closed) changed(devices.values.toList())
        } }
    }
    fun start() {
        try { nsd.discoverServices(FcastProtocol.SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (error: RuntimeException) { failure("TV discovery is unavailable on this device.") }
    }
    private fun resolveNext() {
        if (closed || resolving || queue.isEmpty()) return
        val info = queue.removeFirst()
        if (info.serviceName !in found) { resolveNext(); return }
        resolving = true
        @Suppress("DEPRECATION")
        try { nsd.resolveService(info, object : NsdManager.ResolveListener {
            override fun onResolveFailed(service: NsdServiceInfo, code: Int) { main.post { resolving = false; resolveNext() } }
            override fun onServiceResolved(service: NsdServiceInfo) { main.post {
                resolving = false
                if (!closed && service.serviceName in found && service.attributes["pitwallScene"]?.toString(Charsets.UTF_8) == "1") {
                    @Suppress("DEPRECATION") val address = if (android.os.Build.VERSION.SDK_INT >= 34)
                        service.hostAddresses.firstOrNull { it is java.net.Inet4Address } else service.host
                    if (address is java.net.Inet4Address && (address.isSiteLocalAddress || address.isLinkLocalAddress) && service.port in 1..65535) {
                        devices[service.serviceName] = PitwallDevice(service.serviceName, service.serviceName, address, service.port)
                        changed(devices.values.toList())
                    }
                }
                resolveNext()
            } }
        }) } catch (_: RuntimeException) { resolving = false; resolveNext() }
    }
    override fun close() {
        closed = true; queue.clear(); found.clear(); devices.clear()
        runCatching { nsd.stopServiceDiscovery(listener) }
    }
}

internal class PitwallAdvertisement(context: Context, port: Int, name: String,
                                    private val result: (String, Boolean) -> Unit) : AutoCloseable {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var closed = false
    @Volatile private var registered = false
    private val listener = object : NsdManager.RegistrationListener {
        override fun onServiceRegistered(info: NsdServiceInfo) {
            registered = true
            if (closed) runCatching { nsd.unregisterService(this) }
            else main.post { if (!closed) result(info.serviceName, true) }
        }
        override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) { main.post { if (!closed) result("Discovery registration failed ($code). Reopen the receiver to retry.", false) } }
        override fun onServiceUnregistered(info: NsdServiceInfo) { registered = false }
        override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) {}
    }
    init {
        try { nsd.registerService(NsdServiceInfo().apply {
            serviceName = name.take(60); serviceType = FcastProtocol.SERVICE_TYPE; setPort(port)
            setAttribute("version", "3"); setAttribute("pitwallScene", "1")
        }, NsdManager.PROTOCOL_DNS_SD, listener) }
        catch (_: RuntimeException) { result("Local discovery is unavailable. Reopen the receiver to retry.", false) }
    }
    override fun close() { closed = true; if (registered) runCatching { nsd.unregisterService(listener) } }
}
