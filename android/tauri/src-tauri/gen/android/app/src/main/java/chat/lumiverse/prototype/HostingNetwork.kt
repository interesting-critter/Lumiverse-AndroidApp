package chat.lumiverse.prototype

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

internal object HostingNetwork {
    /** The listener is IPv4; don't advertise cellular or IPv6 addresses it can't serve. */
    fun urls(context: Context, port: Int): List<String> {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        return manager.allNetworks.flatMap { network ->
            val caps = manager.getNetworkCapabilities(network)
            if (caps == null || (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))) emptyList()
            else manager.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull { link ->
                val address = link.address
                if (address is Inet4Address && !address.isLoopbackAddress && !address.isLinkLocalAddress && !address.isAnyLocalAddress)
                    "http://${address.hostAddress}:$port" else null
            }
        }.distinct().sorted()
    }
}
