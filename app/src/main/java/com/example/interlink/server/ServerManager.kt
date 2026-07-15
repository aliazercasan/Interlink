package com.example.interlink.server

import com.example.interlink.audio.AudioHandler
import com.example.interlink.models.Device
import com.example.interlink.models.MusicRequest
import com.example.interlink.network.NsdHelper
import com.example.interlink.repository.DeviceRepository
import com.example.interlink.utils.Constants
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var tcpJob: Job? = null
    private var udpJob: Job? = null
    private var tcpSocket: ServerSocket? = null
    private var udpSocket: DatagramSocket? = null

    private val connectedClients = mutableMapOf<String, InetAddress>()
    
    private val _pendingMusicRequests = MutableStateFlow<List<MusicRequest>>(emptyList())
    val pendingMusicRequests = _pendingMusicRequests.asStateFlow()

    private var currentMusicBroadcasterIp: String? = null

    fun startServer() {
        scope.launch { deviceRepository.clearDevices() }
        nsdHelper.registerService(Constants.TCP_PORT)
        startTcpServer()
        startUdpServer()
    }

    private fun startTcpServer() {
        tcpJob = scope.launch {
            try {
                tcpSocket = ServerSocket(Constants.TCP_PORT).apply { reuseAddress = true }
                while (isActive) {
                    val clientSocket = tcpSocket?.accept() ?: break
                    handleClientConnection(clientSocket)
                }
            } catch (e: Exception) { Timber.e(e, "TCP Server error") }
        }
    }

    private fun handleClientConnection(socket: Socket) {
        scope.launch {
            try {
                val clientIp = socket.inetAddress.hostAddress ?: return@launch
                val reader = socket.getInputStream().bufferedReader()
                
                if (socket.getInputStream().available() > 0) {
                    val message = reader.readLine()
                    if (message != null) {
                        when {
                            message.startsWith(Constants.CMD_MUSIC_REQUEST) -> {
                                val name = message.substringAfter(":")
                                _pendingMusicRequests.value += MusicRequest(clientIp, name)
                            }
                            message == Constants.CMD_MUSIC_STOP -> {
                                if (currentMusicBroadcasterIp == clientIp) currentMusicBroadcasterIp = null
                            }
                        }
                    }
                } else {
                    synchronized(connectedClients) { connectedClients[clientIp] = socket.inetAddress }
                    deviceRepository.insertDevice(Device(clientIp, "Client-$clientIp", true))
                }
                socket.close()
            } catch (e: Exception) { Timber.e(e, "Client logic error") }
        }
    }

    private fun startUdpServer() {
        udpJob = scope.launch {
            try {
                udpSocket = DatagramSocket(Constants.UDP_PORT).apply { 
                    reuseAddress = true
                    receiveBufferSize = 65536 
                    sendBufferSize = 65536
                }
                val buffer = ByteArray(Constants.BUFFER_SIZE)
                val packet = DatagramPacket(buffer, buffer.size)
                
                while (isActive) {
                    udpSocket?.receive(packet)
                    relayPacket(packet)
                    // Play locally for host if not the sender
                    if (packet.address.hostAddress != "127.0.0.1") {
                        audioHandler.playEncodedData(packet.data.copyOf(packet.length))
                    }
                }
            } catch (e: Exception) { Timber.e(e, "UDP Server error") }
        }
    }

    private fun relayPacket(packet: DatagramPacket) {
        val data = packet.data.copyOf(packet.length)
        val senderIp = packet.address.hostAddress
        
        synchronized(connectedClients) {
            connectedClients.forEach { (clientIp, address) ->
                if (clientIp != senderIp) {
                    // Send directly on calling thread to avoid coroutine overhead in relay
                    try {
                        val relayPacket = DatagramPacket(data, data.size, address, Constants.UDP_PORT)
                        udpSocket?.send(relayPacket)
                    } catch (e: Exception) { Timber.e("Relay failed to $clientIp") }
                }
            }
        }
    }

    fun broadcastData(data: ByteArray) {
        synchronized(connectedClients) {
            connectedClients.values.forEach { address ->
                try {
                    val packet = DatagramPacket(data, data.size, address, Constants.UDP_PORT)
                    udpSocket?.send(packet)
                } catch (e: Exception) { }
            }
        }
    }

    fun approveMusicRequest(request: MusicRequest) {
        scope.launch {
            _pendingMusicRequests.value -= request
            currentMusicBroadcasterIp = request.clientIp
            sendCommand(request.clientIp, Constants.CMD_MUSIC_APPROVE)
        }
    }

    fun denyMusicRequest(request: MusicRequest) {
        scope.launch {
            _pendingMusicRequests.value -= request
            sendCommand(request.clientIp, Constants.CMD_MUSIC_DENY)
        }
    }

    private suspend fun sendCommand(ip: String, cmd: String) {
        withContext(Dispatchers.IO) {
            try {
                Socket(ip, Constants.TCP_PORT).use { s ->
                    s.getOutputStream().write((cmd + "\n").toByteArray())
                    s.getOutputStream().flush()
                }
            } catch (e: Exception) { }
        }
    }

    fun stopServer() {
        nsdHelper.unregisterService()
        tcpJob?.cancel(); udpJob?.cancel()
        tcpSocket?.close(); udpSocket?.close()
        tcpSocket = null; udpSocket = null
        _pendingMusicRequests.value = emptyList()
        currentMusicBroadcasterIp = null
        scope.launch { deviceRepository.clearDevices() }
    }
}
