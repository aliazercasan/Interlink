package com.example.interlink.utils

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object NetworkUtils {
    fun getLocalIpAddress(): String? {
        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            
            // Prioritize Wi-Fi and Hotspot, then Mobile Data
            val prioritizedInterfaces = interfaces.sortedByDescending { 
                val name = it.name.lowercase()
                when {
                    name.contains("wlan") -> 4
                    name.contains("ap") -> 3
                    name.contains("eth") -> 2
                    name.contains("rmnet") -> 1 // Mobile Data
                    else -> 0
                }
            }

            for (netInterface in prioritizedInterfaces) {
                val name = netInterface.name.lowercase()
                // Only skip virtual/internal interfaces
                if (name.contains("p2p") || name.contains("dummy") || name.contains("lo")) continue
                
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

    fun getInterfaceType(ip: String?): String {
        if (ip == null) return "Unknown"
        if (ip == "192.168.43.1") return "Hotspot"
        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) return "Local Wi-Fi"
        return "Mobile Data / Internet"
    }

    fun isHotspotActive(): Boolean {
        return getLocalIpAddress() == "192.168.43.1"
    }
}
