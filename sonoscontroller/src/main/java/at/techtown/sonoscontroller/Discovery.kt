package at.techtown.sonoscontroller

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.net.URL

internal data class WifiRoute(val network: Network, val address: Inet4Address)

internal class WifiConnection(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    fun route(): WifiRoute {
        for (network in connectivity.allNetworks) {
            if (connectivity.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) != true) continue
            val address = connectivity.getLinkProperties(network)?.linkAddresses
                ?.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress }?.address as? Inet4Address
            if (address != null) return WifiRoute(network, address)
        }
        throw SonosException(SonosErrorCode.NO_WIFI, "Kein WLAN verbunden. Handy und Sonos müssen im selben WLAN sein.")
    }
    fun open(url: URL): HttpURLConnection = route().network.openConnection(url) as HttpURLConnection
    fun multicastLock(): WifiManager.MulticastLock = wifi.createMulticastLock("SonosPlay:discovery").apply {
        setReferenceCounted(false)
    }
    @Suppress("DEPRECATION")
    fun streamingLock(): WifiManager.WifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "SonosPlay:stream").apply {
        setReferenceCounted(false)
    }
}

internal fun interface DeviceDiscovery {
    fun discover(): List<SonosDevice>
}

internal class SsdpDiscovery(private val wifi: WifiConnection, private val client: SonosControl) : DeviceDiscovery {
    override fun discover(): List<SonosDevice> {
        val route = wifi.route()
        val lock = wifi.multicastLock()
        try {
            lock.acquire()
            val hosts = linkedSetOf<String>()
            DatagramSocket(null).use { socket ->
                socket.reuseAddress = true
                route.network.bindSocket(socket)
                socket.bind(InetSocketAddress(route.address, 0))
                socket.soTimeout = 700
                val query = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\n" +
                    "MAN: \"ssdp:discover\"\r\nMX: 2\r\nST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n").toByteArray()
                repeat(2) { socket.send(DatagramPacket(query, query.size, InetAddress.getByName("239.255.255.250"), 1900)) }
                val deadline = android.os.SystemClock.elapsedRealtime() + 3500
                while (android.os.SystemClock.elapsedRealtime() < deadline) {
                    val packet = DatagramPacket(ByteArray(8192), 8192)
                    try {
                        socket.receive(packet)
                        val response = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        if (response.contains("Sonos", true) || response.contains("ZonePlayer", true)) {
                            packet.address.hostAddress?.let { hosts.add(it) }
                        }
                    } catch (_: SocketTimeoutException) { /* Collect until the response window ends. */ }
                }
            }
            val failures = mutableListOf<Exception>()
            val devices = hosts.mapNotNull { host ->
                try { client.device(host) }
                catch (error: Exception) {
                    android.util.Log.w("SonosPlay", "Geräteinformationen von $host konnten nicht gelesen werden", error)
                    failures.add(error)
                    null
                }
            }
                .distinctBy { it.id }.sortedBy { it.name }
            if (hosts.isNotEmpty() && devices.isEmpty()) {
                val reason = failures.firstOrNull()?.let { it.message ?: it.javaClass.simpleName }.orEmpty()
                throw SonosException("Sonos antwortet, aber die Geräteinformationen konnten nicht gelesen werden: $reason")
            }
            return devices
        } finally { if (lock.isHeld) lock.release() }
    }
}
