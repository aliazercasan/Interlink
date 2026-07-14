package com.example.interlink.server

import com.example.interlink.audio.AudioHandler
import com.example.interlink.models.Device
import com.example.interlink.network.NsdHelper
import com.example.interlink.repository.DeviceRepository
import com.example.interlink.utils.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServerManager @Inject constructor(
    private val nsdHelper: NsdHelper,
    private val deviceRepository: DeviceRepository,
    private val audioHandler: AudioHandler
) {
    private val scope = CoroutineScope(Dispatchers.IO)
    private var tcpJob: Job? = null
    private var udpJob: Job? = null
    private var tcpSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null

    private val connectedClients = mutableSetOf<InetAddress>()

    fun startServer() {
        scope.launch {
            deviceRepository.clearDevices()
        }
        nsdHelper.registerService(Constants.TCP_PORT)
        startTcpServer()
        startUdpServer()
    }

    private fun startTcpServer() {
        tcpJob = scope.launch {
            try {
                tcpSocket = ServerSocket(Constants.TCP_PORT)
                Timber.d("TCP Server started on port ${Constants.TCP_PORT}")
                while (true) {
                    val clientSocket = tcpSocket?.accept() ?: break
                    handleClientConnection(clientSocket)
                }
            } catch (e: Exception) {
                Timber.e(e, "TCP Server error")
            }
        }
    }

    private fun handleClientConnection(socket: Socket) {
        scope.launch {
            val clientIp = socket.inetAddress.hostAddress ?: return@launch
            val clientName = "Client-$clientIp" 
            Timber.d("Client connected: $clientIp")
            synchronized(connectedClients) {
                connectedClients.add(socket.inetAddress)
            }
            deviceRepository.insertDevice(Device(clientIp, clientName, true))
            socket.close()
        }
    }

    private fun startUdpServer() {
        udpJob = scope.launch {
            try {
                udpSocket = DatagramSocket(Constants.UDP_PORT)
                val buffer = ByteArray(Constants.BUFFER_SIZE)
                Timber.d("UDP Server started on port ${Constants.UDP_PORT}")
                while (true) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    udpSocket?.receive(packet)
                    broadcastUdpPacket(packet)
                    // Also play locally if needed, but Host might just be a server
                    audioHandler.playEncodedData(packet.data.copyOf(packet.length))
                }
            } catch (e: Exception) {
                Timber.e(e, "UDP Server error")
            }
        }
    }

    private fun broadcastUdpPacket(packet: DatagramPacket) {
        val data = packet.data.copyOf(packet.length)
        val senderAddress = packet.address
        broadcastData(data, senderAddress)
    }

    fun broadcastData(data: ByteArray, senderAddress: InetAddress? = null) {
        synchronized(connectedClients) {
            connectedClients.forEach { clientAddress ->
                if (clientAddress != senderAddress) {
                    scope.launch {
                        try {
                            val broadcastPacket = DatagramPacket(data, data.size, clientAddress, Constants.UDP_PORT)
                            udpSocket?.send(broadcastPacket)
                        } catch (e: Exception) {
                            Timber.e(e, "Error broadcasting to $clientAddress")
                        }
                    }
                }
            }
        }
    }

    fun stopServer() {
        nsdHelper.unregisterService()
        tcpJob?.cancel()
        udpJob?.cancel()
        tcpSocket?.close()
        udpSocket?.close()
        tcpSocket = null
        udpSocket = null
        scope.launch {
            deviceRepository.clearDevices()
        }
    }
}
