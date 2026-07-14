package com.example.interlink.utils

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            
            // Prioritize Wi-Fi and Hotspot interfaces
            val prioritizedInterfaces = interfaces.sortedByDescending { 
                val name = it.name.lowercase()
                when {
                    name.contains("wlan") -> 3
                    name.contains("ap") -> 2
                    name.contains("eth") -> 1
                    else -> 0
                }
            }

            for (netInterface in prioritizedInterfaces) {
                // Skip mobile data interfaces and virtual interfaces
                val name = netInterface.name.lowercase()
                if (name.contains("rmnet") || name.contains("p2p") || name.contains("dummy")) continue
                
                val addresses = Collections.list(netInterface.inetAddresses)
                for (address in addresses) {
                    if (!address.isLoopbackAddress && address is Inet4Address) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return null
    }

    /**
     * Checks if the device is likely acting as a hotspot.
     * Most Android hotspots use 192.168.43.1 as the gateway.
     */
    fun isHotspotActive(): Boolean {
        return getLocalIpAddress() == "192.168.43.1"
    }
}
