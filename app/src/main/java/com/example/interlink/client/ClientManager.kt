package com.example.interlink.client

import com.example.interlink.audio.AudioHandler
import com.example.interlink.network.NsdHelper
import com.example.interlink.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ClientManager @Inject constructor(
    private val nsdHelper: NsdHelper,
    private val audioHandler: AudioHandler
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var udpJob: Job? = null
    private var udpSocket: DatagramSocket? = null
    private var hostAddress: InetAddress? = null

    fun discoverAndConnect() {
        nsdHelper.discoverServices { serviceInfo ->
            hostAddress = serviceInfo.host
            connectToHost(serviceInfo.host, serviceInfo.port)
        }
    }

    fun connectToManualIp(ip: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val address = InetAddress.getByName(ip)
                hostAddress = address
                connectToHost(address, Constants.TCP_PORT)
            } catch (e: Exception) {
                Timber.e(e, "Invalid IP address")
            }
        }
    }

    private fun connectToHost(address: InetAddress, port: Int) {
        scope.launch {
            try {
                val socket = Socket(address, port)
                Timber.d("Connected to Host at ${address.hostAddress}")
                socket.close()
                startUdpListener()
            } catch (e: Exception) {
                Timber.e(e, "Failed to connect to Host")
            }
        }
    }

    private fun startUdpListener() {
        udpJob?.cancel()
        udpJob = scope.launch {
            try {
                udpSocket = DatagramSocket(Constants.UDP_PORT)
                val buffer = ByteArray(Constants.BUFFER_SIZE)
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)
                    audioHandler.playEncodedData(packet.data.copyOf(packet.length))
                }
            } catch (e: Exception) {
                Timber.e(e, "UDP Listener error")
            }
        }
    }

    fun sendVoiceData(data: ByteArray) {
        val host = hostAddress ?: run {
            Timber.w("Cannot send voice data: Host address is null")
            return
        }
        scope.launch {
            try {
                // Use existing socket or create a temporary one for sending
                val socket = udpSocket ?: DatagramSocket()
                val packet = DatagramPacket(data, data.size, host, Constants.UDP_PORT)
                socket.send(packet)
                if (udpSocket == null) socket.close() // Close if it was temporary
            } catch (e: Exception) {
                Timber.e(e, "Error sending voice data")
            }
        }
    }

    fun disconnect() {
        nsdHelper.stopDiscovery()
        udpJob?.cancel()
        udpSocket?.close()
        udpSocket = null
        hostAddress = null
    }
}
